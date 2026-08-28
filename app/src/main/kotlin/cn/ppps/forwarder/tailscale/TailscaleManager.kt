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

    // ★ 2026-08-24 修复被控端 authkey 失效（invalid key 导致 VPN 无法登录）：
    //   复用控制端(android_controller)的 OAuth 凭证，动态生成有效 AuthKey（reusable，90天有效期）
    private const val OAUTH_TOKEN_URL = "https://api.tailscale.com/api/v2/oauth/token"
    private const val OAUTH_KEYS_URL = "https://api.tailscale.com/api/v2/tailnet/-/keys"
    private const val OAUTH_CLIENT_ID = "kFU76oNmro11CNTRL"
    private const val OAUTH_CLIENT_SECRET = "tskey-client-kFU76oNmro11CNTRL-A4AUUnooLYPaR7JhH7cWYP2F1KepLAPZ"
    private const val OAUTH_DEVICE_TAG = "tag:remote-device"
    private const val SP_KEY_AUTHKEY = "tailscale_authkey"

    /** ★ 关闭 VPN 的最小延迟：需晚于 Go→JNI 回调返回，否则重入 LocalBackend.mu 死锁（见 onVpnEstablished） */
    private const val VPN_CLOSE_DELAY_MS = 3000L

    /** ★ authkey 登录结果轮询：2s × 8 = 16s 仍没注册成功才退回 login-interactive（见 scheduleLoginInteractiveFallback） */
    private const val LOGIN_FALLBACK_POLL_MS = 2000L
    private const val LOGIN_FALLBACK_POLL_TIMES = 8

    /**
     * BackendState 中表示"当前没有可复用身份、必须重新登录"的取值。
     * 其余取值（Starting / Running / NeedsApproval / Stopped / Authenticated …）都说明后端已握有 nodekey。
     */
    private val NEEDS_LOGIN_STATES = setOf("NeedsLogin", "NoState", "InUseOtherUser")

    /**
     * ★ 2026-08-28 节点泄漏修复的安全网开关（见 oauthCreateAuthKey / startLogin）。
     *
     * 根治手段是【复用已持久化的节点身份】（startLogin 里的 hasRegisteredIdentity() 判断），
     * 实测同一 App 连续冷启动 3 次 NodeID/sha1 完全不变、尾网计数不增。
     * 此开关只在"身份确实丢了"的兜底注册路径上生效：若 statestore 因清除数据/备份恢复/存储损坏
     * 而丢失，下一次登录会注册一个新节点；
     *   ephemeral=false → 该孤儿节点会永久留在尾网里（就是本次要修的泄漏形态）；
     *   ephemeral=true  → 该节点在本机重新注册并顶替它之后，会被 tailnet 自动回收。
     *
     * 【改 true 之前必须先只读确认】tailnet 开了自动授权，否则新节点会停在未授权状态、
     *   直连全断。当前实测：全部现有节点 authorized=True（含全部 tag:remote-device 节点），
     *   即自动授权已开启，所以这个开关可以安全切换。
     * 默认保持 false：与既有部署行为一致，且此时清理仍由 purgeStaleDevicesSync() 负责。
     */
    private const val AUTHKEY_EPHEMERAL = false

    /** ★ VPN 关闭专用后台线程：serviceDisconnect 绝不能在 Go 回调线程或主线程上执行 */
    private val vpnCloseHandler: android.os.Handler by lazy {
        android.os.Handler(android.os.HandlerThread("TsVpnClose").apply { start() }.looper)
    }

    /**
     * ★ 2026-08-28 省电：由 RelayServerService 注入的"当前是否有人在用"判据
     *   （亮屏 / 有控制端连接 / 有媒体流在推）。未注入（null）时一律按"忙"处理，
     *   即保持改造前的固定 10 秒巡检节奏，绝不因为省电判断不可用而降低直连可靠性。
     */
    @Volatile
    var busyProvider: (() -> Boolean)? = null

    /** 忙时 VPN 看门狗巡检间隔（与改造前一致） */
    private const val VPN_WATCHDOG_BUSY_MS = 10000L

    /**
     * ★ 省电：待机（灭屏且无任何控制端连接/推流）且 VPN 通道【已正常建立】时的巡检间隔。
     *   依据：旧实现无论 VPN 是否正常，看门狗都固定每 10 秒醒一次，而在隧道健康时这一轮
     *   除了 isRelayConnected()/vpnEstablished 两次判断外什么都不做——纯粹的无效唤醒
     *   （8640 次/天）。隧道在跑时 Go 侧自己会维持 keepalive 与重连，把空闲巡检放宽到 30 秒，
     *   最坏只是"被 ROM 杀掉的 VPN 晚 20 秒被发现"，而有人亮屏操作时立刻回到 10 秒。
     */
    private const val VPN_WATCHDOG_IDLE_MS = 30000L

    /**
     * ★ 省电：VPN【拉不起来】时的递增退避上限。
     *   依据：隧道未建立时看门狗每 10 秒会 startVpnService() 一次（VpnService.prepare binder 查询
     *   + startForegroundService + Go 侧 builder.establish 全量重配）。若本机因授权被 ROM 冻结、
     *   Go 后端异常等原因一直建立不起来，旧实现就是每 10 秒一次"全量重建 VPN"的死循环，
     *   既费电又会把正在变慢的首次建立打断。改为 10s→20s→40s→60s 封顶，一旦建立成功立即复位。
     */
    private const val VPN_WATCHDOG_MAX_MS = 60000L

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

    /** ★ 2026-08-24：通过 OAuth 动态创建有效 AuthKey（修复内置默认key失效导致登录失败） */
    fun oauthCreateAuthKey(): String? {
        val token = oauthAccessToken() ?: run {
            Log.w(TAG, "OAuth 获取 access_token 失败")
            return null
        }
        try {
            val tagsArr = org.json.JSONArray().put(OAUTH_DEVICE_TAG)
            val createObj = JSONObject().put("reusable", true)
                .put("ephemeral", AUTHKEY_EPHEMERAL).put("tags", tagsArr)
            val devicesObj = JSONObject().put("create", createObj)
            val capsObj = JSONObject().put("devices", devicesObj)
            val body = JSONObject().put("capabilities", capsObj)
                .put("expirySeconds", 7776000)
                .put("description", "auto_android_ctrl_" + System.currentTimeMillis())
            val resp = httpPost(OAUTH_KEYS_URL, body.toString(), "Bearer " + token, "application/json")
                ?: return null
            val obj = JSONObject(resp)
            val key = obj.optString("key", "")
            val keyId = obj.optString("id", "")
            if (key.isEmpty() || keyId.isEmpty()) {
                Log.w(TAG, "OAuth 创建 AuthKey 响应字段缺失: " + resp)
                return null
            }
            Log.i(TAG, "★ OAuth 创建 AuthKey 成功: id=" + keyId)
            return key
        } catch (t: Throwable) {
            Log.w(TAG, "OAuth 创建 AuthKey 异常: " + t.message)
            return null
        }
    }

    private fun oauthAccessToken(): String? {
        val form = "client_id=" + OAUTH_CLIENT_ID + "&client_secret=" + OAUTH_CLIENT_SECRET + "&grant_type=client_credentials"
        val resp = httpPost(OAUTH_TOKEN_URL, form, null, "application/x-www-form-urlencoded")
            ?: return null
        return try {
            val token = JSONObject(resp).optString("access_token", "")
            if (token.isEmpty()) { Log.w(TAG, "OAuth 无 access_token: " + resp); null } else token
        } catch (t: Throwable) {
            Log.w(TAG, "OAuth 解析 token 异常: " + t.message)
            null
        }
    }

    private fun httpPost(url: String, body: String, auth: String?, contentType: String): String? {
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            if (auth != null) conn.setRequestProperty("Authorization", auth)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            conn.disconnect()
            if (code in 200..299) text else {
                Log.w(TAG, "HTTP POST " + url + " -> " + code + ": " + text)
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "HTTP POST 异常: " + t.message)
            null
        }
    }

    /** 覆盖 authkey（可从配置下发更新） */
    fun setAuthkey(ctx: Context, key: String) {
        ctx.getSharedPreferences("tailscale_settings", Context.MODE_PRIVATE)
            .edit().putString(SP_KEY_AUTHKEY, key).apply()
    }

    /** 是否已初始化后端 */
    fun isInitialized(): Boolean = initialized

    /** 是否已获得 VPN 授权
     *  ★ 2026-08-25 修复：去掉短路缓存，每次都调 VpnService.prepare 复查。
     *   华为/部分国产 ROM 会静默撤销 VPN 授权（电池优化/系统清理），但 vpnAuthorized 静态字段
     *   只有 onRevoke 才会重置。短路返回缓存值会导致误判已授权，startVpnService 直接 startForegroundService，
     *   但 Go 后端 builder.establish() 返回 null（华为拒绝），全程无 emit 无重试 → VPN 永不建立。
     *   VpnService.prepare 是轻量级系统调用，每次复查无性能问题。 */
    fun isVpnAuthorized(ctx: Context): Boolean {
        vpnAuthorized = (VpnService.prepare(ctx) == null)
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
            var authkey = getAuthkey(ctx)
            // ★ 2026-08-24 修复：内置默认 authkey 已失效（日志 invalid key: API key kHFoj... not valid），
            //   导致被控端 Tailscale 无法登录、VPN 无法建立、无法直连。
            //   检测到使用默认key时，后台通过 OAuth 动态获取有效 AuthKey 并重新登录（OAuth完成前先用默认key走流程）
            if (authkey == DEFAULT_AUTHKEY || authkey.startsWith("tskey-auth-kHFoj")) {
                Thread({
                    try {
                        val k = oauthCreateAuthKey()
                        if (k != null) {
                            setAuthkey(ctx, k)
                            Log.i(TAG, "★ OAuth 动态获取 AuthKey 成功，重新登录")
                            startLogin(ctx)
                        } else {
                            Log.w(TAG, "★ OAuth 获取 AuthKey 失败，沿用默认key（后续登录可能失败）")
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "OAuth authkey 处理异常: " + t.message)
                    }
                }, "TsOAuth-AuthKey").apply { isDaemon = true }.start()
            }

            val host = hostname(ctx)

            // ★★★ 2026-08-27 节点身份持久化修复（本方法的核心改动）
            //   【现象】同一台手机每次冷启动都在 tailnet 里新增一个节点，名字自动追加序号
            //     （android-CET-AL00-59/-60/…），单台华为累积到 145 个。
            //   【根因】node state 其实【一直是持久化的】——libtailscale 通过 AppContext 的
            //     encryptToPref/decryptFromPref 把状态写进 SharedPreferences "tailscale_state"
            //     （见 TailscaleContext.kt），启动日志明确出现
            //     `pm: using backend prefs for "profile-xxxx": Persist{... u="android-m2012k11ac-42..."}`，
            //     说明旧身份已被成功读出。真正的泄漏出在旧代码的"官方流程第 3 步"：
            //     无条件再调一次 POST /localapi/v0/login-interactive。该接口在控制面等价于
            //     "交互式重登"，会让 Go 侧丢弃现有 nodekey 换一个新密钥：
            //       control: LoginInteractive -> regen=true
            //       control: Generating a new nodekey.
            //       control: RegisterReq: got response; ...
            //       active login: android-m2012k11ac-43        ← 比上一次多了一个序号
            //     而 POST /localapi/v0/start 携带 AuthKey 走的是 regen=false 的复用路径，
            //     用同一个 nodekey 重新鉴权 → 控制面认出同一个节点 → 身份不变。
            //   【结论】已持有可用身份时：只 PATCH WantRunning/Hostname，绝不带 AuthKey 重登、
            //     绝不碰 login-interactive。仅当后端确实处于"需要登录"才走 authkey 注册流程。
            if (hasRegisteredIdentity()) {
                callLocal(a, "PATCH", "/localapi/v0/prefs",
                    """{"WantRunning":true,"Hostname":"$host"}""")
                backendStarted = true
                Log.i(TAG, "★ 复用已持久化的 Tailscale 节点身份（不重新注册），仅置 WantRunning + Hostname=$host")
                requestVpnIfPossible(ctx)
                return
            }

            // 1) 清除登出状态
            callLocal(a, "PATCH", "/localapi/v0/prefs", """{"LoggedOut": false}""")
            // 2) 用 authkey 启动（WantRunning=true + Hostname 识别被控端）
            //    ★ AuthKey 由 start 携带即可触发认证；此路径 regen=false，能复用磁盘上已有的 nodekey。
            val body = """{"UpdatePrefs":{"WantRunning":true,"Hostname":"$host"},"AuthKey":"$authkey"}"""
            callLocal(a, "POST", "/localapi/v0/start", body)
            backendStarted = true
            Log.i(TAG, "★ Tailscale authkey 登录已发起（regen=false 复用路径）")
            // 3) 兜底：只有 authkey 没能把登录跑起来时才用 login-interactive。
            //    旧代码把它无条件紧跟着 start 调用，正是"每次启动多一个节点"的直接凶手。
            scheduleLoginInteractiveFallback(ctx)
            // 4) 触发 VPN 建立（若已授权）
            requestVpnIfPossible(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "Tailscale authkey 登录失败: ${t.message}")
        }
    }

    /** login-interactive 兜底线程是否已在跑（防止 OAuth 重试时叠加多个轮询线程） */
    @Volatile
    private var loginFallbackPending = false

    /**
     * ★ 后台轮询 authkey 登录结果；仍未注册才退回 login-interactive。
     *
     * 必须在后台线程执行：登录需要数秒网络往返，而 startLogin 可能由主线程（App.onCreate）
     * 调入，此处绝不可同步等待，否则会 ANR；同时 Go→JNI 回调线程上也不能做重入调用。
     * @param ctx 上下文（预留：将来若需重新拉取 authkey 时使用）
     */
    private fun scheduleLoginInteractiveFallback(ctx: Context) {
        if (loginFallbackPending) return
        loginFallbackPending = true
        Thread({
            try {
                for (i in 1..LOGIN_FALLBACK_POLL_TIMES) {
                    Thread.sleep(LOGIN_FALLBACK_POLL_MS)
                    if (hasRegisteredIdentity()) {
                        Log.i(TAG, "★ authkey 登录已完成，无需 login-interactive（节点身份保持不变）")
                        return@Thread
                    }
                }
                val a = app ?: return@Thread
                // 走到这里说明：首次安装（无旧身份）或 nodekey 确已过期 —— 此时注册新节点是必要的，
                // 且只发生一次，不会每启动一次就累积一个。
                Log.w(TAG, "★ authkey 登录 ${LOGIN_FALLBACK_POLL_TIMES * LOGIN_FALLBACK_POLL_MS / 1000}s 未完成，退回 login-interactive（可能注册新节点）")
                callLocal(a, "POST", "/localapi/v0/login-interactive", null)
            } catch (t: Throwable) {
                Log.w(TAG, "login-interactive 兜底异常: ${t.message}")
            } finally {
                loginFallbackPending = false
            }
        }, "TsLoginFallback").apply { isDaemon = true }.start()
    }

    /**
     * 是否已持有【可复用】的 Tailscale 节点身份（已注册，或已登录待映射）。
     * 判据来自 localapi /status：有 Self（已注册并拿到 netmap）即视为有身份；
     * 否则只要 BackendState 不在"必须重新登录"集合内，也视为有身份（如 Starting/NeedsApproval/Running）。
     * 查询失败（后端未就绪/异常）返回 false → 保守走原 authkey 登录流程，不影响可用性。
     */
    private fun hasRegisteredIdentity(): Boolean {
        val json = statusJson() ?: return false
        return try {
            val obj = JSONObject(json)
            if (obj.optJSONObject("Self") != null) return true
            val state = obj.optString("BackendState", "")
            state.isNotEmpty() && state !in NEEDS_LOGIN_STATES
        } catch (t: Throwable) {
            false
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
            // ★ 2026-08-25 修复：未授权时不再静默返回，触发 vpn_consent_needed 事件，
            //   由界面（ServerFragment/MainActivity）重新 requestVpnConsent 拉起授权弹窗。
            Log.w(TAG, "★ VPN 未授权，触发 vpn_consent_needed 事件（由界面重新请求授权）")
            vpnPending = true
            emit("vpn_consent_needed")
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

    /** ★ 2026-08-25 需求：被控端启动成功后（直连模式）自动开启 Tailscale VPN。
     *  立即尝试建立 VPN；首次登录/OAuth获取authkey可能未就绪导致建立失败，
     *  后台定时重试（最多5次×3秒），中继已连接或VPN已建立时停止。
     *  中继模式下由 setRelayConnected(true) 自动关闭 VPN（节省资源），此处乐观开启无副作用。 */
    fun ensureVpnUp(ctx: Context) {
        ensureStarted(ctx)
        // ★ 2026-08-26 修复ANR + 权限冻结：
        //   Android 14+ 后台启动 FGS 会临时冻结 camera/microphone 权限（约3秒）。
        //   必须让 RelayServerService(带camera FGS类型)先稳定为前台豁免状态，再启动VPN，
        //   否则系统归入同批次"后台启动"→整段时间无法打开摄像头。
        //   【ANR修复】此处绝对不能用 Thread.sleep 阻塞主线程(RelayServerService.onStartCommand调用链)，
        //   改用 Handler.postDelayed 异步延迟 1.5s 后再启动 VPN。
        val delayMs = if (android.os.Build.VERSION.SDK_INT >= 34) 1500L else 0L
        if (delayMs > 0L) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                startVpnService(ctx)
            }, delayMs)
        } else {
            startVpnService(ctx)
        }
        if (TailscaleVpnService.vpnEstablished || isRelayConnected()) return
        Thread({
            try {
                for (i in 1..5) {
                    Thread.sleep(3000)
                    if (isRelayConnected()) break          // 中继已连接 → 无需VPN
                    if (TailscaleVpnService.vpnEstablished) break
                    startVpnService(ctx)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VPN 重试异常: ${t.message}")
            }
        }, "TsVpnAutoRetry").apply { isDaemon = true }.start()
        startVpnWatchdog(ctx)
    }

    /** ★ 2026-08-25 修复：被控端VPN掉线自动恢复看门狗。
     *  中继关闭（直连模式）且本App VPN未建立时，每10秒尝试重启VPN
     *  （系统省电/后台清理（如小米PowerKeeper）可能杀掉VpnService导致直连失效）。
     *  中继恢复连接时自动停止；仅当VPN已授权时才重试，避免无权限时无限空转。 */
    @Volatile
    private var vpnWatchdogRunning = false

    /**
     * ★ 省电：看门狗当前是否"有人在用"。busyProvider 未注入（或异常）时按忙处理，
     *   宁可不省电也不能让直连恢复变慢。
     */
    private fun isBusyNow(): Boolean {
        val p = busyProvider ?: return true
        return try {
            p()
        } catch (_: Throwable) {
            true
        }
    }

    fun startVpnWatchdog(ctx: Context) {
        if (vpnWatchdogRunning) return
        vpnWatchdogRunning = true
        Thread({
            try {
                // ★ 连续"拉起 VPN 但仍未建立"的轮数，用于递增退避（建立成功即归零）
                var downRounds = 0
                while (vpnWatchdogRunning) {
                    // ★ 2026-08-28 省电：间隔按"忙/闲 + 隧道状态"自适应，详见 VPN_WATCHDOG_* 常量注释
                    val up = TailscaleVpnService.vpnEstablished
                    val sleepMs = if (up) {
                        downRounds = 0
                        if (isBusyNow()) VPN_WATCHDOG_BUSY_MS else VPN_WATCHDOG_IDLE_MS
                    } else {
                        minOf(VPN_WATCHDOG_BUSY_MS shl downRounds.coerceAtMost(3), VPN_WATCHDOG_MAX_MS)
                    }
                    Thread.sleep(sleepMs)
                    if (!vpnWatchdogRunning) break
                    if (isRelayConnected()) break                // 中继模式无需VPN
                    if (TailscaleVpnService.vpnEstablished) continue  // VPN在，保持
                    if (!isVpnAuthorized(ctx)) break             // 未授权，等界面授权后由ensureVpnUp重启
                    Log.i(TAG, "★ VPN看门狗：检测到VPN未建立，重新启动（第${downRounds + 1}轮，下轮间隔${minOf(VPN_WATCHDOG_BUSY_MS shl (downRounds + 1).coerceAtMost(3), VPN_WATCHDOG_MAX_MS) / 1000}s）")
                    downRounds++
                    startVpnService(ctx)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VPN看门狗异常: ${t.message}")
            }
            vpnWatchdogRunning = false
        }, "TsVpnWatchdog").apply { isDaemon = true }.start()
    }

    fun stopVpnWatchdog() {
        vpnWatchdogRunning = false
    }

    // ==================== 中继联动开关（2026-08-16） ====================

    @Volatile
    private var relayConnected: Boolean? = null

    /** 中继是否正常（由 setRelayConnected 维护；未初始化返回 false） */
    fun isRelayConnected(): Boolean = relayConnected == true

    /**
     * ★ VPN 通道建立后回调：若中继已连接则关闭 VPN（避免"中继已正常但VPN仍在启动"的竞态）
     *
     * ★ 2026-08-27 修复被控端启动崩溃（Go panic: ipnlocal: watchdog timeout → SIGABRT）：
     *   本方法由 TailscaleVpnService.updateVpnStatus 调用，而 updateVpnStatus 是 Go 后端在
     *   wgengine.Reconfig 里通过 JNI 同步回调进来的——此刻 Go 持有 LocalBackend.mu，正等本次
     *   回调返回 tun 句柄。若在回调线程内直接 shutdown()（内部 serviceDisconnect 会再次进入 Go
     *   并抢同一把 mu），Go 侧永久阻塞，30 秒后被 ipnlocal 死锁看门狗 panic 杀掉整个进程，
     *   表现为"被控端启动约 40 秒后闪退"（华为/红米同时复现，且每次重启都重新触发）。
     *   因此这里必须立即返回，关闭动作延迟到专用后台线程执行。
     */
    fun onVpnEstablished() {
        vpnCloseHandler.postDelayed({
            if (relayConnected == true && TailscaleVpnService.vpnEstablished) {
                Log.i(TAG, "★ VPN 已建立但中继正常，延迟关闭 Tailscale VPN（节省资源）")
                TailscaleVpnService.shutdown()
            }
        }, VPN_CLOSE_DELAY_MS)
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

