package cn.ppps.forwarder.tailscale

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import cn.ppps.forwarder.utils.Log
import libtailscale.Application
import libtailscale.InputStream
import libtailscale.Libtailscale
import libtailscale.LocalAPIResponse
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ★ Tailscale 管理器（2026-08-16 被控端集成）
 *
 * 职责：
 *  1. 启动 libtailscale Go 后端（Libtailscale.start）
 *  2. 用 authkey 无 UI 登录（PATCH /prefs + POST /start 带 AuthKey）
 *  3. 建立 VPN（Android VpnService，首次需用户一次性授权）
 *  4. 提供本机 Tailscale IP 与在线成员 IP（100.64.x）
 *
 * authkey 读取优先级：SharedPreferences "tailscale_authkey" → 内置默认值。
 */
object TailscaleManager {
    private const val TAG = "TailscaleMgr"
    private const val DEFAULT_AUTHKEY = "tskey-auth-kHFojQGTYV11CNTRL-EkjTQgBwRKWnTMyTHQ1EKWoCBbHeTp1h2"
    private const val SP_KEY_AUTHKEY = "tailscale_authkey"

    @Volatile
    private var app: Application? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var vpnAuthorized = false

    @Volatile
    private var vpnPending = false

    @Volatile
    private var backendStarted = false

    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    /** 登录/状态变化监听（"vpn_consent_needed" / "connected" / "ip:<x.x.x.x>"） */
    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }

    private fun emit(event: String) {
        listeners.forEach { runCatching { it(event) } }
    }

    fun getAuthkey(ctx: Context): String {
        val sp = ctx.getSharedPreferences("tailscale_settings", Context.MODE_PRIVATE)
        return sp.getString(SP_KEY_AUTHKEY, DEFAULT_AUTHKEY) ?: DEFAULT_AUTHKEY
    }

    /** 覆盖 authkey（可从配置下发更新） */
    fun setAuthkey(ctx: Context, key: String) {
        ctx.getSharedPreferences("tailscale_settings", Context.MODE_PRIVATE)
            .edit().putString(SP_KEY_AUTHKEY, key).apply()
    }

    /** 是否已初始化后端 */
    fun isInitialized(): Boolean = initialized

    /** 是否已获得 VPN 授权 */
    fun isVpnAuthorized(ctx: Context): Boolean {
        if (vpnAuthorized) return true
        val prepared = VpnService.prepare(ctx)
        vpnAuthorized = prepared == null
        return vpnAuthorized
    }

    /** 启动 Tailscale：初始化后端 + authkey 登录（幂等，可在服务/Application 任意处调用） */
    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (initialized) return
        initialized = true
        try {
            val dataDir = ctx.filesDir.absolutePath
            TailscaleContext.init(ctx)
            app = Libtailscale.start(dataDir, dataDir, false, TailscaleContext.get())
            Log.i(TAG, "libtailscale 后端已启动")
            startLogin(ctx)
        } catch (t: Throwable) {
            Log.e(TAG, "libtailscale 启动失败: ${t.message}")
            initialized = false
        }
    }

    /** authkey 无 UI 登录（幂等） */
    private fun startLogin(ctx: Context) {
        val a = app ?: return
        try {
            val authkey = getAuthkey(ctx)
            // 1) 清除登出状态
            callLocal(a, "PATCH", "/localapi/v0/prefs", """{"LoggedOut": false}""")
            // 2) 读取当前 prefs
            val prefsResp = callLocal(a, "GET", "/localapi/v0/prefs", null)
            val prefsJson = prefsResp?.let { String(it, Charsets.UTF_8) } ?: "{}"
            // 3) 用 authkey 启动（WantRunning=true + Hostname 识别被控端）
            val body = """{"UpdatePrefs":{"WantRunning":true,"Hostname":"${hostname(ctx)}"},"AuthKey":"$authkey"}"""
            callLocal(a, "POST", "/localapi/v0/start", body)
            // ★ 2026-08-16 修复：官方流程第 3 步必须调用 login-interactive 才能触发 authkey 认证，
            //   否则后端停在 NeedsLogin 状态（Tailscale 永不上线，直连不可用）
            //   注意：此版本（1.103.0）的路径是 /localapi/v0/login-interactive（新版才叫 start-login-interactive）
            callLocal(a, "POST", "/localapi/v0/login-interactive", null)
            backendStarted = true
            Log.i(TAG, "★ Tailscale authkey 登录已发起")
            // 4) 触发 VPN 建立（若已授权）
            requestVpnIfPossible(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "Tailscale authkey 登录失败: ${t.message}")
        }
    }

    private fun hostname(ctx: Context): String {
        val model = android.os.Build.MODEL ?: "android"
        return "android-$model".replace(" ", "-").replace(Regex("[^A-Za-z0-9._-]"), "-")
    }

    /** VPN 授权后调用：拉起 VpnService 使 Go 后端建立 tun 通道（幂等，VPN 已在运行时跳过） */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    fun startVpnService(ctx: Context) {
        if (!isVpnAuthorized(ctx)) {
            Log.w(TAG, "VPN 未授权，无法建立")
            return
        }
        if (TailscaleVpnService.vpnEstablished) {
            Log.i(TAG, "VPN 通道已存在，跳过启动")
            return
        }
        try {
            val intent = Intent(ctx, TailscaleVpnService::class.java).apply {
                action = TailscaleVpnService.ACTION_START_VPN
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "启动 TailscaleVpnService 失败: ${t.message}")
        }
    }

    // ==================== 中继联动开关（2026-08-16） ====================

    @Volatile
    private var relayConnected: Boolean? = null

    /** 中继是否正常（由 setRelayConnected 维护；未初始化返回 false） */
    fun isRelayConnected(): Boolean = relayConnected == true

    /**
     * ★ VPN 通道建立后回调：若中继已连接则立即关闭 VPN（避免"中继已正常但VPN仍在启动"的竞态）
     */
    fun onVpnEstablished() {
        if (relayConnected == true) {
            Log.i(TAG, "★ VPN 已建立但中继正常，立即关闭 Tailscale VPN（节省资源）")
            TailscaleVpnService.shutdown()
        }
    }

    /**
     * ★ 中继联动：中继正常时关闭 Tailscale VPN（节省资源）；中继不可用时开启直连。
     * @param connected 中继是否连接正常
     */
    fun setRelayConnected(ctx: Context, connected: Boolean) {
        // ★ 状态未变化时不重复执行/打日志（中继每5秒重试失败会高频触发，避免日志刷屏）
        if (relayConnected == connected) return
        relayConnected = connected
        try {
            if (connected) {
                // 中继正常 → 关闭直连 VPN 通道（Go 后端保留，随时可快速重建）
                if (TailscaleVpnService.vpnEstablished) {
                    Log.i(TAG, "★ 中继正常，关闭 Tailscale VPN 通道（节省资源）")
                    TailscaleVpnService.shutdown()
                }
            } else {
                // 中继不可用 → 自动开启 Tailscale 直连
                Log.i(TAG, "★ 中继不可用，自动开启 Tailscale 直连")
                ensureStarted(ctx)
                startVpnService(ctx)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "中继联动 Tailscale 开关失败: ${t.message}")
        }
    }

    /** 手动关闭 Tailscale VPN（保留 Go 后端） */
    fun stopVpn() {
        TailscaleVpnService.shutdown()
    }

    /** 由 VpnService.onStartCommand 回调：Go 后端请求 VPN */
    fun onVpnRequested() {
        vpnPending = true
    }

    /** VPN 授权被撤销（onRevoke） */
    fun notifyVpnRevoked() {
        vpnAuthorized = false
        vpnPending = false
        emit("vpn_consent_needed")
    }

    /** 需要 VPN 授权时从 Activity 发起授权（首次安装后必现的一次性系统弹窗） */
    fun requestVpnConsent(activity: Activity): Boolean {
        val intent = VpnService.prepare(activity) ?: run {
            vpnAuthorized = true
            return true
        }
        try {
            activity.startActivityForResult(intent, REQUEST_VPN_PREPARE)
        } catch (t: Throwable) {
            Log.w(TAG, "发起 VPN 授权失败: ${t.message}")
        }
        return false
    }

    /** 处理 onActivityResult 的 VPN 授权结果 */
    fun handleVpnConsentResult(activity: Activity, resultCode: Int) {
        if (resultCode == Activity.RESULT_OK) {
            vpnAuthorized = true
            Log.i(TAG, "★ VPN 授权成功")
            startVpnService(activity)
        } else {
            vpnAuthorized = false
            Log.w(TAG, "用户拒绝 VPN 授权")
        }
    }

    /** 内部：若已授权则拉起 VPN 服务 */
    fun requestVpnIfPossible(ctx: Context) {
        if (isVpnAuthorized(ctx)) {
            startVpnService(ctx)
        } else {
            vpnPending = true
            emit("vpn_consent_needed")
        }
    }

    /** 是否已建立 VPN 通道（tun） */
    fun isVpnUp(): Boolean = TailscaleVpnService.vpnEstablished

    // ==================== 状态查询（localapi /status） ====================

    /** 获取 status JSON（失败返回 null） */
    fun statusJson(): String? {
        val a = app ?: return null
        return try {
            val resp = callLocal(a, "GET", "/localapi/v0/status", null) ?: return null
            String(resp, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "查询 status 失败: ${t.message}")
            null
        }
    }

    /** 获取本机 Tailscale IP（100.64.x，取第一个 IPv4） */
    fun getSelfIp(): String? {
        val json = statusJson() ?: return null
        return try {
            val obj = JSONObject(json)
            val self = obj.optJSONObject("Self") ?: return null
            val ips = self.optJSONArray("TailscaleIPs") ?: return null
            for (i in 0 until ips.length()) {
                val ip = ips.optString(i)
                if (ip.contains(".") && !ip.startsWith("fe80")) return ip
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** 获取在线成员 IP（100.64.x，排除本机） */
    fun getOnlineMemberIps(): List<String> {
        val json = statusJson() ?: return emptyList()
        return try {
            val obj = JSONObject(json)
            val self = obj.optJSONObject("Self")
            val selfIp = self?.optJSONArray("TailscaleIPs")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val ip = arr.optString(i)
                    if (ip.contains(".")) return@let ip
                }
                null
            }
            val result = mutableListOf<String>()
            // ★ 2026-08-16 修复：localapi /status 的 Peer 是 map（{nodeKey:{…}}）而非数组，
            //   optJSONArray("Peer") 返回 null 导致永远拉不到成员。兼容 map + 数组两种形态。
            val peerObj = obj.optJSONObject("Peer")
            val peerArr = obj.optJSONArray("Peer")
            val peers = peerObj?.let { po ->
                val list = mutableListOf<org.json.JSONObject>()
                val it = po.keys()
                while (it.hasNext()) {
                    po.optJSONObject(it.next())?.let { list.add(it) }
                }
                list
            } ?: run {
                if (peerArr != null) {
                    val list = mutableListOf<org.json.JSONObject>()
                    for (i in 0 until peerArr.length()) {
                        peerArr.optJSONObject(i)?.let { list.add(it) }
                    }
                    list
                } else emptyList()
            }
            for (peer in peers) {
                if (!peer.optBoolean("Online", false)) continue
                val ips = peer.optJSONArray("TailscaleIPs") ?: continue
                for (j in 0 until ips.length()) {
                    val ip = ips.optString(j)
                    if (ip.contains(".") && ip != selfIp) {
                        result.add(ip)
                        break
                    }
                }
            }
            result
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 是否已连上 Tailscale 控制面（有 IP 即视为已上线） */
    fun isConnected(): Boolean = !getSelfIp().isNullOrBlank()

    // ==================== localapi 调用 ====================

    /** 调用 localapi，返回响应 body（无 body 返回 null） */
    private fun callLocal(a: Application, method: String, path: String, jsonBody: String?): ByteArray? {
        val bodyStream: InputStream? = jsonBody?.let { ByteArrayInputStreamAdapter(it.toByteArray(Charsets.UTF_8)) }
        val resp: LocalAPIResponse = a.callLocalAPI(15000, method, path, bodyStream)
        val code = resp.statusCode()
        val body = resp.bodyBytes() ?: ByteArray(0)
        if (code >= 400) {
            Log.w(TAG, "localapi $method $path → HTTP $code: ${String(body, Charsets.UTF_8)}")
            return null
        }
        return body
    }

    private class ByteArrayInputStreamAdapter(private val data: ByteArray) : InputStream {
        private val stream = ByteArrayInputStream(data)

        @Throws(Exception::class)
        override fun read(): ByteArray? {
            val buf = ByteArray(8192)
            val n = stream.read(buf)
            return if (n <= 0) null else buf.copyOf(n)
        }

        @Throws(Exception::class)
        override fun close() {
            stream.close()
        }
    }

    const val REQUEST_VPN_PREPARE = 0x5453 // "TS"
}
