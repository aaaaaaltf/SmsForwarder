package cn.ppps.forwarder.tailscale

import android.content.Context
import android.os.Build
import cn.ppps.forwarder.utils.Log
import org.json.JSONObject
import tsnetpoc.Tsnetpoc
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ★ 2026-11-05【被控端去 VPN】用户态 Tailscale 通道（tsnet / netstack）
 *
 * 目的：被控端不再申请 Android VpnService（不建 tun、不弹 VPN 授权、状态栏无钥匙图标、
 * 也不与同机控制端争唯一 VPN 槽位），但仍能走 Tailscale 直连。
 *
 * 实现要点：
 *  - Go 侧 tsnet 在进程内起一个用户态节点（netstack），身份状态落在 files/tsnet/tailscaled.state；
 *  - 出站：Go 在本机 127.0.0.1 起「拨号代理」，Kotlin 侧仍用普通 Socket 连本地端口，
 *    原有帧协议 / 收发线程 / 重连逻辑零改动；
 *  - 入站：Go 在尾网监听 56786，转发到本机真实 ServerSocket（保留 RelayServerListener 语义）；
 *  - 成员 / 自身 IP 由 Go 的 localapi(status) 解析后回传，替代原 libtailscale localapi。
 *
 * 与 libtailscale 的差异：tsnet 不能复用 libtailscale 的加密 SharedPreferences 身份，
 * 首次启动会用 authkey 注册一个新节点（state 文件持久化，之后冷启动身份不变）。
 */
object TsnetTransport {
    private const val TAG = "TsnetTransport"

    /** 状态目录（App 私有）：与 libtailscale 的 SharedPreferences 身份相互独立 */
    private const val STATE_DIR = "tsnet"

    private const val START_TIMEOUT_SEC = 30L
    private const val STATUS_TIMEOUT_SEC = 15L
    private const val PROBE_TIMEOUT_SEC = 10L
    private const val PEER_CACHE_MS = 5000L

    @Volatile
    private var node: tsnetpoc.Node? = null

    private val starting = AtomicBoolean(false)

    /** 本节点 Tailscale IP（100.x） */
    @Volatile
    var selfIp: String? = null
        private set

    /** 最近一次成员 IP 列表（在线优先） */
    @Volatile
    private var peers: List<String> = emptyList()
    @Volatile
    private var peersAt = 0L

    @Volatile
    private var up = false

    @Volatile
    var lastError: String? = null
        private set

    /** 期望的入站转发端口（尾网 :port → 本机 127.0.0.1:port），0=不启用 */
    @Volatile
    private var inboundPort = 0

    @Volatile
    private var inboundStarted = false

    /** target("100.x:56789") → 本地代理地址("127.0.0.1:xxxxx") */
    private val dialProxies = ConcurrentHashMap<String, String>()

    fun isUp(): Boolean = up

    /**
     * 启动用户态节点（幂等）。必须从后台线程或允许阻塞的线程调用：
     * Up 会阻塞到节点在线或超时。
     */
    fun ensureStarted(ctx: Context) {
        if (up) return
        val appCtx = ctx.applicationContext
        if (!starting.compareAndSet(false, true)) return
        // ★ 2026-11-05【被控端去 VPN】自愈：上一次启动失败/节点掉线时先释放残留句柄，
        //   否则每轮重试都会新建一个 tsnet.Server（旧的 netstack/goroutine 泄漏）。
        if (!up) {
            try {
                node?.close()
            } catch (_: Throwable) {
            }
            node = null
            selfIp = null
            // ★★★ 2026-11-06 修复：重建节点后旧 node 的 Listen 代理已随 close() 消失，
            //   必须复位这些"已建立"标记，否则 startInboundIfNeeded 会早退，
            //   尾网 :56786/:56783/:56791 永远不再监听（控制端连上即 RST，且无日志）。
            inboundStarted = false
            dialProxies.clear()
            exposedTcpPorts.clear()
        }
        Thread({
            try {
                // ★ 2026-11-05【被控端去 VPN】关键修复：Android 10+ 禁止 App 读 netlink，
                //   Go 的 net.Interfaces() 会 EPERM（真机错误：route ip+net: netlinkrib: permission denied），
                //   tsnet 的 netmon 初始化会直接失败。这里把 Java 侧枚举出的接口 JSON 注入 Go，
                //   与 libtailscale 的做法一致（netmon.RegisterInterfaceGetter）。
                try {
                    TailscaleContext.init(appCtx)
                    Tsnetpoc.setInterfaceProvider(object : tsnetpoc.InterfaceProvider {
                        override fun getInterfacesJSON(): String = try {
                            TailscaleContext.get().getInterfacesAsJson()
                        } catch (_: Throwable) {
                            "[]"
                        }
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "注册网络接口提供者失败（将退化为仅 DERP 中继）: ${t.message}")
                }
                val authKey = resolveAuthKey(appCtx)
                val st = Tsnetpoc.start(
                    File(appCtx.filesDir, STATE_DIR).absolutePath,
                    hostname(),
                    authKey,
                    "",
                    false
                )
                node = st
                val json = st.up(START_TIMEOUT_SEC)
                applyStatus(json)
                val online = up
                Log.i(TAG, "★ 用户态节点启动结果: $json")
                if (online) {
                    TsnetTransport.selfIp?.let { ip ->
                        Log.i(TAG, "★ 用户态节点已在线，self=$ip（不需要 VPN 授权）")
                        TailscaleManager.onTsnetReady(ip)
                    }
                    startInboundIfNeeded()
                } else {
                    lastError = "节点未在线: $json"
                }
            } catch (t: Throwable) {
                lastError = t.message
                Log.e(TAG, "用户态节点启动失败: ${t.message}")
            } finally {
                starting.set(false)
            }
        }, "TsnetStart").apply { isDaemon = true }.start()
    }

    /** 凭证更新后调用（控制端下发 / NAS 自助补凭证）：未在线则用新 key 重试 */
    fun onCredentialUpdated(ctx: Context) {
        if (up) return
        ensureStarted(ctx)
    }

    /** 尾网成员 IP（在线优先，失败时回退上次缓存） */
    fun memberIps(): List<String> {
        val n = node ?: return peers
        if (!up) return peers
        val now = System.currentTimeMillis()
        if (peers.isNotEmpty() && now - peersAt < PEER_CACHE_MS) return peers
        return try {
            val json = n.status(STATUS_TIMEOUT_SEC)
            val obj = JSONObject(json)
            val arr = obj.optJSONArray("peers")
            val list = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val ip = arr.optString(i)
                    if (ip.contains(".") && ip != selfIp) list.add(ip)
                }
            }
            val self = obj.optString("self_ip", "")
            if (self.isNotEmpty() && self.contains(".")) selfIp = self
            if (list.isNotEmpty()) {
                peers = list
                peersAt = now
            }
            peers
        } catch (t: Throwable) {
            Log.w(TAG, "取用户态节点成员失败: ${t.message}")
            peers
        }
    }

    /**
     * 为 host:port 取（或创建）本地拨号代理地址；节点未在线返回 null。
     * 返回 "127.0.0.1:xxxxx"，调用方用普通 Socket 连它即可。
     */
    fun dialProxy(host: String, port: Int): String? {
        if (!up) return null
        val key = "$host:$port"
        dialProxies[key]?.let { return it }
        val n = node ?: return null
        synchronized(dialProxies) {
            dialProxies[key]?.let { return it }
            return try {
                val addr = n.startDialProxy(key)
                if (addr.isNullOrBlank()) {
                    Log.w(TAG, "本地拨号代理创建失败: $key")
                    null
                } else {
                    Log.i(TAG, "★ 本地拨号代理就绪: $addr → $key")
                    dialProxies[key] = addr
                    addr
                }
            } catch (t: Throwable) {
                Log.w(TAG, "创建本地拨号代理异常($key): ${t.message}")
                null
            }
        }
    }

    /**
     * 通过用户态隧道探测 ip:port 是否为控制端（要求读到至少 1 字节，语义与原 Socket 探测一致）。
     * 节点未在线时返回 false，调用方据此跳过本轮。
     */
    fun probe(ip: String, port: Int): Boolean {
        if (!up) return false
        val n = node ?: return false
        return try {
            val json = n.probeTCP("$ip:$port", ByteArray(0), PROBE_TIMEOUT_SEC)
            val obj = JSONObject(json)
            val ok = obj.optBoolean("connected", false) && obj.optInt("read_bytes", 0) > 0
            if (!ok) Log.d(TAG, "用户态探测未确认 $ip:$port: $json")
            ok
        } catch (t: Throwable) {
            Log.w(TAG, "用户态探测异常($ip:$port): ${t.message}")
            false
        }
    }

    /**
     * 需要入站直连时调用（应在 RelayServerListener.start() 之后）：
     * 尾网 :port 的连接会转发到本机 127.0.0.1:port。
     * 节点尚未在线时记录端口，在线后自动补建。
     */
    fun startInboundWhenReady(localPort: Int) {
        inboundPort = localPort
        if (up) startInboundIfNeeded()
    }

    /** ★★★ 2026-11-05【直连模式 ICE-TCP】已暴露到尾网的本地 TCP 端口集合（重复 Listen 会端口占用） */
    private val exposedTcpPorts: MutableSet<Int> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())

    /**
     * ★ 2026-11-06【直连模式媒体端口】节点尚未在线时先排队的暴露请求。
     *   冷启动时屏幕/麦克风推流可能早于 tsnet Up 完成，直接 expose 会返回 false；
     *   原先没有补建机制，只能靠用户手动重试预览。这里排队，Up 后自动补建。
     */
    private val pendingExposes = ConcurrentHashMap<Int, String>()

    /**
     * ★ 2026-11-05【直连模式 ICE-TCP】把本机 WebRTC 的 TCP 候选端口暴露到尾网：
     *   控制端（VPN tun）连 100.x:port → tsnet 接受 → 转发到 localAddr（候选真实 IP:port）。
     * ★ 2026-11-06 复用为直连模式媒体端口（56783 屏幕 / 56791 麦克风）的暴露入口。
     *   返回 true=已暴露（含此前已暴露过的端口）；节点未在线时排队并返回 false。
     */
    fun exposeTcpPort(port: Int, localAddr: String): Boolean {
        if (port <= 0 || localAddr.isBlank()) return false
        val n = node
        if (!up || n == null) {
            pendingExposes[port] = localAddr
            Log.i(TAG, "★ 端口暴露排队（节点未在线）：尾网 :$port → $localAddr")
            return false
        }
        synchronized(this) {
            if (!exposedTcpPorts.add(port)) return true
            return try {
                n.startListenProxy(port.toLong(), localAddr)
                Log.i(TAG, "★ 媒体/直连端口已暴露：尾网 :$port → $localAddr")
                true
            } catch (t: Throwable) {
                exposedTcpPorts.remove(port)
                Log.w(TAG, "端口暴露失败 :$port → $localAddr：${t.message}")
                false
            }
        }
    }

    /**
     * ★ 2026-11-06【媒体端口回收】会话结束时撤掉尾网监听（Go 侧 StopListenProxy）。
     *   幂等：未暴露过/节点已关闭时静默返回。停止推流后端口不应继续接受连接。
     */
    fun unexposeTcpPort(port: Int) {
        if (port <= 0) return
        pendingExposes.remove(port)
        if (!exposedTcpPorts.remove(port)) return
        val n = node ?: return
        try {
            n.stopListenProxy(port.toLong())
            Log.i(TAG, "★ 端口已回收：尾网 :$port")
        } catch (t: Throwable) {
            Log.w(TAG, "端口回收失败 :$port：${t.message}")
        }
    }

    /** 节点 Up 后补建所有排队中的端口暴露（由 startInboundIfNeeded 调用） */
    private fun flushPendingExposes() {
        if (!up || node == null || pendingExposes.isEmpty()) return
        val it = pendingExposes.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (exposeTcpPort(e.key, e.value)) {
                it.remove()
            }
        }
    }

    private fun startInboundIfNeeded() {
        if (inboundStarted || inboundPort <= 0) {
            // ★ 命令端口可能已建立过（inboundStarted=true），但媒体端口仍可能排队待建
            flushPendingExposes()
            return
        }
        val n = node ?: return
        synchronized(this) {
            if (!inboundStarted) {
                try {
                    n.startListenProxy(inboundPort.toLong(), "127.0.0.1:$inboundPort")
                    inboundStarted = true
                    Log.i(TAG, "★ 入站代理已建立：尾网 :$inboundPort → 本机 127.0.0.1:$inboundPort")
                } catch (t: Throwable) {
                    Log.w(TAG, "建立入站代理失败: ${t.message}")
                }
            }
        }
        flushPendingExposes()
    }

    /**
     * ★★★ 2026-11-08【服务停止 → 拆除入站代理】
     *   服务销毁时本地 56786 已关闭，但 tsnet 侧仍把尾网 :56786 转发到 127.0.0.1:56786，
     *   控制端收到的是 RST / "connection was refused"（真机实测：服务停后仍能看到
     *   CreateEndpoint error）。这里只摘除命令端口代理，节点本身保留（媒体端口/其他功能不受影响）。
     */
    fun stopInbound(reason: String) {
        val p = inboundPort
        synchronized(this) {
            if (inboundStarted && p > 0) {
                try {
                    node?.stopListenProxy(p.toLong())
                    Log.i(TAG, "★ 入站代理已拆除：尾网 :$p（$reason）")
                } catch (t: Throwable) {
                    Log.w(TAG, "拆除入站代理失败: ${t.message}")
                }
            }
            inboundStarted = false
            inboundPort = 0
        }
    }

    /** 停机（服务销毁/调试用）：关闭节点、代理与状态 */
    fun stop() {
        try {
            node?.close()
        } catch (_: Throwable) {
        }
        node = null
        up = false
        selfIp = null
        peers = emptyList()
        peersAt = 0L
        inboundStarted = false
        inboundPort = 0
        dialProxies.clear()
        exposedTcpPorts.clear()
        pendingExposes.clear()
    }

    /** 解析 Up/Status 返回的 JSON，更新 selfIp/up */
    private fun applyStatus(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            val obj = JSONObject(json)
            val ip = obj.optString("self_ip", "")
            if (ip.contains(".")) selfIp = ip
            up = obj.optBoolean("online", false)
                    || obj.optString("backend_state", "") == "Running"
                    || !selfIp.isNullOrBlank()
        } catch (t: Throwable) {
            Log.w(TAG, "解析节点状态失败: ${t.message}")
        }
    }

    /** authkey：先用 App 现有 key，为空/历史失效值时尝试 OAuth 现签 */
    private fun resolveAuthKey(ctx: Context): String {
        val existing = try {
            TailscaleManager.getAuthkey(ctx)
        } catch (_: Throwable) {
            ""
        }
        if (existing.isNotEmpty() && !existing.startsWith("tskey-auth-kHFoj")) return existing
        val fresh = try {
            TailscaleManager.oauthCreateAuthKey(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "OAuth 签发 authkey 异常: ${t.message}")
            null
        }
        if (!fresh.isNullOrBlank()) {
            try {
                TailscaleManager.setAuthkey(ctx, fresh)
            } catch (_: Throwable) {
            }
            return fresh
        }
        return existing
    }

    /** 节点名：与旧 VPN 节点区分，避免 tailnet 里同名追加序号 */
    private fun hostname(): String {
        val model = (Build.MODEL ?: "android").replace(Regex("[^A-Za-z0-9-]"), "-")
        val name = "android-$model-ut"
        return if (name.length > 63) name.substring(0, 63) else name
    }
}
