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
import cn.ppps.forwarder.tailscale.TailscaleCredGuard
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
import cn.ppps.forwarder.utils.DeviceIdentity
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

    /** ★ 2026-09-04 文件下载分块大小（控制端 PhoneFileManagerActivity.CHUNK_SIZE 必须一致，
     *    断点续传的块序号/字节偏移换算依赖此常量，两侧不一致会导致续传错位）。 */
    private const val FS_BLOCK_BYTES = 128 * 1024

    /** ★ 2026-08-10 替换已删除控制端gson：使用本地Gson单例 */
    private val gson: Gson = GsonBuilder().serializeNulls().create()

    // ============ ★ 2026-08-30：每控制端连接一份会话（修"两个控制端互踩"） ============
    //
    // 下面这些状态原本是 object RelayServerHandler 的**单例字段**：webrtc /
    // webrtcCameraIndex / webrtcAudioOnly / fsDownloadCancelled / fsDownloadThread /
    // fsAckBlock。而本端可同时被多个控制端连着（手机控制端 / TS直连 / 中继）：
    //   · B 一连 WebRTC，`webrtc?.close(); webrtc = mgr` 就把 A 正在看的摄像头/麦克风顶掉，
    //     A 从此黑屏且不会自动恢复；
    //   · 任一连接发"取消下载"会把**另一个**控制端正在跑的下载一起取消；
    //   · fsAckBlock 被两条下载共用，ACK 游标互相跳。
    //
    // ★ 键用 peerKey 而不是 sender：CHANNEL_DIRECT 的 directSender 是"每条命令新建一个
    //   object : RelaySender"（RelayServerService.kt:196），按 sender 分桶等于每条命令
    //   一个新会话，WebRTC 状态在 OFFER 之后的信令里就丢了。peerKey 按连接稳定：
    //   中继=client 对象、直连=connId、TS直连=phoneIp。
    //
    // 回收：本 object 没有断开钩子可挂（链路实测每 15 秒还会重连），普通 Map 会无限
    // 堆积 → WeakHashMap，连接对象被 GC 时条目自动消失（connId/phoneIp 是值类型，
    // 装箱后同样随连接结束而回收）。
    private class PeerSession {
        @Volatile var webrtc: WebRtcSessionManager? = null
        /** WebRTC 启动时请求的摄像头索引，用于失败回退到老 JPEG+PCM 模式 */
        @Volatile var webrtcCameraIndex: Int = 0
        /** 当前 WebRTC 会话是否纯音频模式（麦克风）：回退时只启老麦克风，不启摄像头 */
        @Volatile var webrtcAudioOnly: Boolean = false
        /** 本连接的文件下载取消标志（发 CMD_FS_CANCEL 后置 true，下载线程每块前检查） */
        @Volatile var fsDownloadCancelled: Boolean = false
        /** 本连接当前下载线程（互斥：防旧下载残留线程与新下载并发写同一 socket） */
        @Volatile var fsDownloadThread: Thread? = null
        /** 本连接的分块确认游标：控制端每收一块回 CMD_FS_ACK，未确认不继续发 */
        @Volatile var fsAckBlock: Long = -1L
        /**
         * 下载代号：每次 startFsDownload 自增。下载线程持有自己启动时的代号，
         * 一旦会话里的代号变了就说明本线程已被新下载取代，必须在任何检查点退出。
         * 不能用 fsDownloadCancelled 做这件事——新下载会把它清回 false。
         */
        @Volatile var fsDownloadGeneration: Long = 0L

        // ★ 2026-09-07 文件上传状态（每控制端连接一份）
        @Volatile var fsUploadRaf: java.io.RandomAccessFile? = null
        @Volatile var fsUploadPath: String = ""
        @Volatile var fsUploadExpected: Long = 0L
        @Volatile var fsUploadReceived: Long = 0L
        /**
         * ★ 2026-09-21 期望的下一个上传块序号。
         * 起始值 = 续传偏移 / FS_BLOCK_BYTES（与控制端 uploadBlockSeq=(int)(offset/CHUNK_SIZE)
         * 同一公式），每成功写入一块 +1 —— 这样即使续传偏移不是块大小整数倍也不会算错。
         */
        @Volatile var fsUploadNextSeq: Int = 0
        @Volatile var fsUploadCancelled: Boolean = false
    }

    private val peerSessions: MutableMap<Any, PeerSession> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    /** peerKey 为 null 的旧调用路径退到共享会话——行为与改动前完全一致 */
    private val noKeySession = PeerSession()

    private fun sessionOf(peerKey: Any?): PeerSession {
        if (peerKey == null) return noKeySession
        synchronized(peerSessions) {
            peerSessions[peerKey]?.let { return it }
            return PeerSession().also { peerSessions[peerKey] = it }
        }
    }

    // ==================== ★ WebRTC 会话：被控端作为 ANSWERER（控制端发 OFFER） ====================

    /** 给 WebRtcSessionManager 发信令/状态：复用 sender(RelaySender) 的 sendText()/sendBin()，
     *  因 RelayServerHandler.handle 返回 String→String，所以直接用 sender 回发包 */
    private fun makeWebrtcSignalingCallback(sender: RelaySender, peerKey: Any?): WebRtcSessionManager.SignalingCallback {
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
                val fallbackCamera = sessionOf(peerKey).webrtcCameraIndex
                val fallbackSender = sender
                // ★ 2026-08-11 判断当前会话是否纯音频模式（麦克风WebRTC）：回退时只启动老麦克风，不启动摄像头
                val webrtcIsAudioOnly = sessionOf(peerKey).webrtcAudioOnly
                // ★★★ 2026-08-14 修复"摄像头被占用后回退失败/后续全部无图像"：
                //   根因：onError 在 WebRTC 捕获线程被回调 → close()(@Synchronized) 内 videoCapturer.stopCapture()
                //   会等待【捕获线程自身】退出 → 死锁 → 回退Thread永不启动 → 摄像头不推流；
                //   且卡死的close()持有this锁 → 后续新OFFER/挂断的webrtc?.close()也等锁 → 屏幕预览/麦克风全失效。
                //   修复：回退Thread提前启动(不依赖close完成)，close()移入回退线程异步执行(非捕获线程→不阻塞)。
                Thread {
                    try {
                        try { sessionOf(peerKey).webrtc?.close() } catch (_: Throwable) {}
                        sessionOf(peerKey).webrtc = null
                        // ★★★ 2026-08-28 省电：控制端已经不在了，就不要"回退到老模式"。
                        //   回退的语义是"WebRTC 通道打不开，但控制端还在看"——此时才需要改用 JPEG+PCM 继续服务。
                        //   实际线上最常见的 onError 是【控制端进程被杀/网络掉线】：ICE 在 45 秒宽容期后判 FAILED →
                        //   onError → 老代码无条件重启摄像头(JPEG编码)+麦克风(AudioRecord)+PARTIAL_WAKE_LOCK，
                        //   而 send() 是异步投递到 sendExecutor（不抛异常），采集循环因此永远不会自己结束，
                        //   表现就是"没人看的时候摄像头/麦克风/CPU 编码器还在全速跑"，是被控端最贵的空转。
                        //   这里先给 6 秒等命令通道恢复（正常网络抖动/重连窗口内即可救回，功能不受影响），
                        //   仍不可用则说明对端确实走了：只清理，不再重启任何采集，释放相机/麦克风/WakeLock。
                        if (!waitForConnection(fallbackSender, 6000L)) {
                            Log.w(TAG, "★ WebRTC 回退取消：控制端命令通道已断开($reason)，只释放采集资源，不重启老模式推流")
                            try { CameraStreamManager.stop() } catch (_: Throwable) {}
                            try { MicrophoneStreamManager.stop() } catch (_: Throwable) {}
                            return@Thread
                        }
                        // —— 1) 老摄像头：JPEG推（纯音频模式跳过，不占用摄像头）
                        if (!webrtcIsAudioOnly) {
                            val okCam = CameraStreamManager.start(fallbackCamera)
                            Log.i(TAG, "★ WebRTC回退：摄像头 ${if (okCam) "成功" else "失败:${CameraStreamManager.lastError()}"}")
                            try {
                                fallbackSender.send(
                                    RelayCommands.CMD_CAMERA_STATUS_REPORT,
                                    if (okCam) "${CameraStreamManager.currentFacingValue()}|success|摄像头流已启动(webrtc回退模式) cameraId=${CameraStreamManager.currentCameraName()}${CameraStreamManager.facingSuffix()}"
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
    const val CHANNEL_DIRECT = 1   // 命令经直连监听(56786)到达（控制端TS/局域网直连）
    const val CHANNEL_TS = 2       // 命令经TS直连(56789)到达（被控端主动连接控制端）

    /** ★ TS直连启动器（由RelayServerService设置，触发后主动连接控制端56789） */
    var tsDirectLauncher: ((phoneIp: String, port: Int) -> Unit)? = null


    /** ★ 本机虚拟网IP缓存（60秒）：Tailscale 100.64-100.127.x */
    private var ownTsIpsCache: Set<String>? = null
    private var ownTsIpsCacheTime = 0L

    /** 获取本机虚拟网IP（Tailscale 100.64.x，与PC被控端逻辑一致） */
    fun getOwnTsIps(): Set<String> {
        val now = System.currentTimeMillis()
        if (ownTsIpsCache != null && now - ownTsIpsCacheTime < 60000) return ownTsIpsCache!!
        val ips = LinkedHashSet<String>()
        // ★ 2026-08-16 修复：只使用 Tailscale 后端 Self IP（自己 App 的节点）。
        //   不再枚举网卡——同一手机上"控制端App的VPN接口IP"也会被 NetworkInterface 枚举到，
        //   导致把控制端IP(如100.106.79.58)误判为本机 → scanTsMembers 的 ip in own 跳过控制端探测，
        //   isOwnIp 也误判 → 中继关闭后被控端无法通过扫描发现控制端，彻底连不上。
        try {
            cn.ppps.forwarder.tailscale.TailscaleManager.getSelfIp()?.let { ips.add(it) }
        } catch (_: Throwable) {
        }
        ownTsIpsCache = ips
        ownTsIpsCacheTime = now
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
     * ★ 2026-08-29 凭证"申请"侧：本机还没有 OAuth 凭证时，借控制通道上任意一条常规命令
     *   （探活/取状态/取配置等）向**同一个对端**发一帧 HELLO，请它回一次 v2 加密凭证下发。
     *
     * 为什么挂在命令处理里而不是"连接建立回调"里：
     *   被控端同时有 中继(56786 出站) / 直连监听(56786 入站) / TS直连(56789) 三条控制通道，
     *   只有 handle() 是所有通道唯一的收口点，且这里天然拿得到"该连接的 sender"，
     *   能保证 HELLO 与随后的 tscred000000 应答走同一条链路，不会被发到另一个对端上。
     *
     * 失败/无凭证时绝不影响命令处理本身（全部 runCatching 包裹）。
     */
    private fun maybeRequestCredential(cmd: String, sender: RelaySender?, peerKey: Any?) {
        if (sender == null) return
        if (cmd == RelayCommands.CMD_TAILSCALE_CRED || cmd == RelayCommands.CMD_TAILSCALE_CRED_HELLO) return
        requestCredentialFrom(sender, peerKey, throttle = true)
    }

    /**
     * ★ 向指定对端发起一次"加密凭证下发"申请（HELLO）。
     *
     * @param peerKey   节流键 —— 必须用"这条连接"的稳定标识（直连用 connId、TS 直连用对端 IP、
     *                  中继用 client 对象）。★ 不能用 sender 对象身份：直连监听的 directSender
     *                  每条命令都新建一个对象，用它当键等于"永远不节流"。
     * @param throttle  true = 遵守 60 秒窗口（命令路径的常规申请）；
     *                  false = 新连接刚建立时的主动申请（绕过窗口，成功后再记时），
     *                  否则新接入的对端会被别的连接占着的窗口永久抢不到机会。
     * @return 是否真的把 HELLO 发了出去
     */
    fun requestCredentialFrom(
        sender: RelaySender?,
        peerKey: Any? = null,
        throttle: Boolean = true,
    ): Boolean {
        if (sender == null) return false
        try {
            if (cn.ppps.forwarder.tailscale.TailscaleManager.hasOAuthCred(App.context)) return false
            if (throttle && TailscaleCredGuard.helloThrottled(peerKey)) return false
            val payload = TailscaleCredGuard.buildHelloPayload() ?: return false
            val helloNonce = try {
                org.json.JSONObject(payload).optString("n", "")
            } catch (_: Throwable) { "" }
            runCatching { sender.send(RelayCommands.CMD_TAILSCALE_CRED_HELLO, payload.toByteArray(Charsets.UTF_8)) }
                .onFailure {
                    // 发不出去就立刻作废这个会话，别把私钥多留在内存里
                    TailscaleCredGuard.abandonHello(helloNonce)
                    Log.w(TAG, "★ HELLO 发送失败: ${it.javaClass.simpleName}")
                }
                .onSuccess {
                    // 主动申请路径不经过 helloThrottled()，这里补记时，避免下一条命令又立刻重发
                    if (!throttle) TailscaleCredGuard.markHelloSent(peerKey)
                    Log.i(TAG, "★ 已向控制端申请一次加密凭证下发 (session=${helloNonce.take(8)}, peer=${TailscaleCredGuard.describeThrottleKey(peerKey)}, 主动=${!throttle})")
                }
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "★ 申请凭证下发异常: ${t.javaClass.simpleName}")
            return false
        }
    }

    /**
     * 处理收到的命令
     * @param channel 命令来源通道：CHANNEL_RELAY / CHANNEL_DIRECT / CHANNEL_TS（供屏幕推流模式选择）
     * @param sender 命令来源通道的发送器（★ 2026-08-06新增：文件下载等二进制推送用，由RelayServerService传入）
     * @return (响应命令, 响应负载JSON)，未知命令返回 null
     */
    fun handle(cmd: String, payload: ByteArray, channel: Int = CHANNEL_RELAY,
               sender: RelaySender? = null, peerKey: Any? = null): Pair<String, String>? {
        val payloadText = String(payload, Charsets.UTF_8)
        // ★ 2026-08-29：顺手向该对端申请凭证下发（仅在本机尚无 OAuth 凭证时真正发出）
        //   ★ 2026-08-30：节流改为"按对端连接"，键由调用方给出（connId / 对端 IP / 中继 client）
        maybeRequestCredential(cmd, sender, peerKey)
        return try {
            when (cmd) {
                RelayCommands.CMD_GET_CONFIG -> RelayCommands.RSP_CONFIG to success(handleConfig())

                // ★ Tailscale直连请求（中继在线时触发）：负载 "目标Tailscale IP|控制端Tailscale IP|端口"
                RelayCommands.CMD_TS_DIRECT_CONNECT -> {
                    val parts = payloadText.split("|")
                    if (parts.size >= 3) {
                        val targetIp = parts[0].trim()
                        val phoneIp = parts[1].trim()
                        val port = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: RelayCommands.TS_DIRECT_PORT
                        if (targetIp in getOwnTsIps()) {
                            Log.i(TAG, "★ 收到TS直连请求，目标匹配本机，主动连接控制端 $phoneIp:$port")
                            tsDirectLauncher?.invoke(phoneIp, port)
                        } else {
                            Log.i(TAG, "TS直连目标 $targetIp 不是本机 (本机: ${getOwnTsIps()})，忽略")
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

                RelayCommands.CMD_FS_MKDIR -> {
                    // ★ 2026-09-21 新建文件夹：在所选目录内创建子文件夹；重名回"已存在"由控制端提示重新输入
                    RelayCommands.RSP_FS_MKDIR to handleFsMkdir(payloadText)
                }

                RelayCommands.CMD_FS_GET -> {
                    // ★ 下载：启动后台线程推送（RSP_FS_GET → CMD_FS_DATA分块 → CMD_FS_DONE），此处不返回
                    startFsDownload(payloadText, sender, peerKey)
                    null
                }

                RelayCommands.CMD_FS_CANCEL -> {
                    // ★ 取消下载：设置取消标志，下载线程在下一块发送前检测到后立即退出
                    //   通过专用命令通道（非数据通道）发送，响应RSP_FS_CANCEL确认已停止
                    Log.i(TAG, "★ 收到取消下载命令，设置取消标志")
                    sessionOf(peerKey).fsDownloadCancelled = true
                    RelayCommands.RSP_FS_CANCEL to "1|stopped|已停止发送"
                }

                RelayCommands.CMD_FS_ACK -> {
                    // ★ 分块确认：控制端收到数据块后回ACK，负载=块序号（被控端据此继续下一块）
                    val ack = payloadText.trim().toLongOrNull() ?: -1L
                    Log.i(TAG, "[FS调试] 收到ACK cmd=${RelayCommands.CMD_FS_ACK} 原始负载=[${payloadText}] 解析=$ack time=${System.currentTimeMillis()}")
                    if (ack >= 0) sessionOf(peerKey).fsAckBlock = ack
                    null  // ACK无需响应
                }

                // ==================== ★ 文件上传（2026-09-07新增） ====================
                RelayCommands.CMD_FS_UPLOAD -> {
                    // 上传请求：负载 "目标目录|文件名|文件大小"
                    startFsUpload(payloadText, sender, peerKey)
                    null
                }

                RelayCommands.CMD_FS_UPDATA -> {
                    // 上传数据块：二进制负载 = 4字节大端块序号 + 文件数据
                    handleFsUpData(payload, sender, peerKey)
                }

                RelayCommands.CMD_FS_UPDONE -> {
                    // 上传完成：关闭文件并校验（★ 负载=控制端下发的整包 sha256，旧控制端为空），回 RSP_FS_UPRST
                    finishFsUpload(sender, peerKey, payloadText)
                }

                // ==================== ★ 文件属性（2026-09-07新增） ====================
                RelayCommands.CMD_FS_STAT -> {
                    RelayCommands.RSP_FS_STAT to handleFsStat(payloadText)
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
                    // ★ 按命令来源通道决定推流模式（中继→PUSHER；直连/TS→监听56788）
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
                            // ★ 尺寸口径统一由 TouchControlService 负责（与单击一致），见 tapNormalized
                            svc.tapNormalized(nx, ny)
                            Thread.sleep(50)
                            svc.tapNormalized(nx, ny)
                        }
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_WHEEL -> {
                    touchUnavailable() ?: run {
                        val delta = payloadText.trim().toIntOrNull() ?: 0
                        TouchControlService.instance?.scroll(delta)
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

                // ★★★ 2026-08-29 新增：控制端下发的 Tailscale OAuth 凭证（v2 加密信封）
                //   openCredFrame 只认 v=2 的 X25519+AES-256-GCM 密文，且必须匹配本进程
                //   先前发出的那一次 HELLO 会话（一次性、60 秒 TTL、防重放、绑 AAD）。
                //   ★ 任何一步失败都只记日志并 return null —— 绝不写盘、绝不"当明文再试一次"。
                RelayCommands.CMD_TAILSCALE_CRED -> {
                    val cred = TailscaleCredGuard.openCredFrame(payload)
                    if (cred == null) {
                        Log.w(TAG, "★ 收到 tscred000000 但未通过 v2 解密校验，已拒绝（未改动本机凭证）")
                        null
                    } else {
                        val (cid, secret) = cred
                        val saved = TailscaleCredGuard.saveOAuthCred(App.context, cid, secret)
                        Log.i(TAG, "★ 已收到控制端加密下发的 OAuth 凭证 " +
                                TailscaleCredGuard.describeForLog(cid, secret) +
                                " 落盘(Keystore包裹)=" + saved)
                        if (saved) {
                            // 立即用新凭证换取 AuthKey，不必等下次冷启动（不在本线程做 VPN 操作）
                            try {
                                cn.ppps.forwarder.tailscale.TailscaleManager.onCredentialStored(App.context)
                            } catch (t: Throwable) {
                                Log.w(TAG, "凭证下发后触发签发异常: ${t.javaClass.simpleName}")
                            }
                        }
                        null
                    }
                }

                // ★★★ 2026-08-29 修复#1：同机控制端没有自己的 localapi（SKIP_OWN_BACKEND），
                //   由本进程（持有 libtailscale 后端）代答一份实时 tailnet 成员 IP 列表。
                RelayCommands.CMD_GET_TS_MEMBERS -> {
                    val members = try {
                        cn.ppps.forwarder.tailscale.TailscaleManager.getOnlineMemberIps()
                    } catch (t: Throwable) {
                        Log.w(TAG, "★ CMD_GET_TS_MEMBERS 取成员失败(返回空列表): ${t.message}")
                        emptyList()
                    }
                    Log.i(TAG, "★ 收到 CMD_GET_TS_MEMBERS → 回吐 tailnet 成员 ${members.size} 个")
                    RelayCommands.RSP_TS_MEMBERS to members.joinToString(",")
                }

                RelayCommands.CMD_CAMERA_STREAM_START -> {
                    if (!HttpServerUtils.enableApiCamera) return RelayCommands.CMD_CAMERA_STATUS_REPORT to error("0|failed|服务端已禁用摄像头")
                    // ★ 2026-08-29 payload 语义 = 朝向目标：0=后置(BACK) / 1=前置(FRONT)；
                    //   越界值（如2/3）由 CameraStreamManager 退化为 cameraIdList 数组下标解释（旧行为，向后兼容）
                    val facingTarget = payloadText.toIntOrNull() ?: CameraStreamManager.FACING_BACK
                    val ok = CameraStreamManager.start(facingTarget)
                    // 回报：首字段=实际打开的朝向（与帧头一致）；成功时末尾追加契约字段 |facing=<0|1>[|fallback=1]
                    // 失败分支保持旧格式 "<target>|failed|原因"，旧控制端无需改动即可解析
                    RelayCommands.CMD_CAMERA_STATUS_REPORT to if (ok)
                        "${CameraStreamManager.currentFacingValue()}|success|摄像头流已启动 cameraId=${CameraStreamManager.currentCameraName()}${CameraStreamManager.facingSuffix()}"
                    else
                        "$facingTarget|failed|${CameraStreamManager.lastError()}"
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
                        sessionOf(peerKey).webrtcCameraIndex = idx
                        makeWebrtcSignalingCallback(s, peerKey).onError("被控端缺权限: ${missing.joinToString()}")
                        return null
                    }
                    if (needCamera && !HttpServerUtils.enableApiCamera) {
                        // 禁用摄像头也回退老模式
                        sessionOf(peerKey).webrtcCameraIndex = idx
                        makeWebrtcSignalingCallback(s, peerKey).onError("服务端已禁用摄像头")
                        return null
                    }
                    // 解析 payload = cameraIndex|offerSdpBase64
                    if (firstPipeIdx < 0) {
                        Log.e(TAG, "★ [WebRTC OFFER IN] OFFER格式非法: 找不到'|'分隔符")
                        return RelayCommands.RSP_ERROR to error("OFFER格式非法: 应为 \"摄像头索引|SDP_BASE64\"")
                    }
                    Log.i(TAG, "★ [WebRTC OFFER IN] 解析成功: cameraIndex=$idx, offerB64Len=${offerB64.length}")
                    sessionOf(peerKey).webrtcCameraIndex = idx
                    sessionOf(peerKey).webrtcAudioOnly = audioOnly
                    // —— 先关闭旧 WebRTC / 旧 JPEG+PCM 会话（避免冲突）
                    try { sessionOf(peerKey).webrtc?.close() } catch (_: Throwable) {}
                    try { CameraStreamManager.stop() } catch (_: Throwable) {}
                    try { MicrophoneStreamManager.stop() } catch (_: Throwable) {}
                    sessionOf(peerKey).webrtc = null
                    // —— 初始化新 WebRTC 会话
                    val mgr = WebRtcSessionManager(App.context)
                    sessionOf(peerKey).webrtc = mgr
                    val cb = makeWebrtcSignalingCallback(s, peerKey)
                    // ★★★ 2026-08-12 中继优先模式：OFFER经中继到达(CHANNEL_RELAY)→relayPreferred=true
                    //   （媒体走TURN中继转发）；经TS直连/直连监听到达(CHANNEL_TS/DIRECT)→relayPreferred=false
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
                    sessionOf(peerKey).webrtc?.addRemoteIceCandidate(sdpMid, lineIdx, candB64)
                    null
                }

                RelayCommands.CMD_WEBRTC_ANSWER -> {
                    // 被控端是 ANSWERER，不会收到 ANSWER；正常忽略（除非双向通话）
                    Log.i(TAG, "★ WebRTC 收到 ANSWER（被控端是ANSWERER，忽略）")
                    null
                }

                RelayCommands.CMD_WEBRTC_HANGUP -> {
                    Log.i(TAG, "★ WebRTC 挂断")
                    try { sessionOf(peerKey).webrtc?.close() } catch (_: Throwable) {}
                    sessionOf(peerKey).webrtc = null
                    sessionOf(peerKey).webrtcAudioOnly = false
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
        // ★★★ 2026-08-29 与 RelayServerService.buildDeviceState 保持完全一致的尾部第6字段【唯一设备ID】。
        //   前5字段（名称|锁屏|屏幕|电量|充电）顺序含义不变，只做尾追加 → 旧控制端忽略尾段即可。
        return "$name|${if (locked) 1 else 0}|${if (screenOn) 1 else 0}|$battery|$charging|${DeviceIdentity.uniqueDeviceId(ctx)}"
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

    /**
     * 新建文件夹（目录递归创建）
     * @return "1|success|信息" 或 "0|failed|原因"（原因含"已存在"表示重名冲突，控制端据此提示重新输入）
     */
    private fun handleFsMkdir(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "0|failed|路径为空"
        val f = File(p)
        if (f.exists()) {
            // ★ 重名（文件或文件夹均视为冲突）：回失败，控制端弹“当前目录存在同名的文件夹，重新输入”
            return "0|failed|已存在: ${f.name ?: p}"
        }
        return try {
            if (f.mkdirs()) "1|success|新建文件夹成功: $p" else "0|failed|新建文件夹失败"
        } catch (e: Exception) {
            Log.w(TAG, "新建文件夹失败: $p ${e.message}")
            "0|failed|${e.message ?: "新建文件夹失败"}"
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

    // ==================== ★ 文件上传（2026-09-07新增） ====================

    /**
     * 上传请求处理：负载 "目标目录|文件名|文件大小"
     * 创建/打开目标文件，回 RSP_FS_UPREADY("ok|起始偏移") 通知控制端开始发数据。
     */
    private fun startFsUpload(raw: String, sender: RelaySender?, peerKey: Any?) {
        if (sender == null) {
            Log.w(TAG, "文件上传失败: sender为空")
            return
        }
        val parts = raw.split("|")
        if (parts.size < 3) {
            sender.sendSync(RelayCommands.RSP_FS_UPREADY, "error|参数不完整".toByteArray(Charsets.UTF_8), 5000)
            return
        }
        val targetDir = parts[0].trim()
        val fileName = parts[1].trim()
        val expectedSize = parts[2].trim().toLongOrNull() ?: -1L
        if (targetDir.isEmpty() || fileName.isEmpty() || expectedSize < 0) {
            sender.sendSync(RelayCommands.RSP_FS_UPREADY, "error|参数无效".toByteArray(Charsets.UTF_8), 5000)
            return
        }
        val dir = File(targetDir)
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) {
            sender.sendSync(RelayCommands.RSP_FS_UPREADY, "error|目标目录不存在".toByteArray(Charsets.UTF_8), 5000)
            return
        }
        val targetFile = File(dir, fileName)
        val session = sessionOf(peerKey)
        // 清理旧上传状态
        try { session.fsUploadRaf?.close() } catch (_: Throwable) {}
        session.fsUploadCancelled = false
        session.fsUploadPath = targetFile.absolutePath
        session.fsUploadExpected = expectedSize
        // 断点续传：同名文件已存在且小于 expectedSize → 从已有长度继续
        var offset = 0L
        if (targetFile.exists() && targetFile.length() < expectedSize && targetFile.length() > 0) {
            offset = targetFile.length()
        } else if (targetFile.exists()) {
            // 文件已存在且大小≥预期 → 覆盖，从头写
            targetFile.delete()
        }
        try {
            val raf = java.io.RandomAccessFile(targetFile, "rw")
            if (offset > 0) raf.seek(offset) else raf.setLength(0)
            session.fsUploadRaf = raf
            session.fsUploadReceived = offset
            // ★ 块序号基线：与控制端 uploadBlockSeq = (int)(offset / CHUNK_SIZE) 完全一致
            session.fsUploadNextSeq = (offset / FS_BLOCK_BYTES).toInt()
        } catch (e: Exception) {
            Log.w(TAG, "上传: 创建文件失败 ${targetFile.absolutePath} ${e.message}")
            sender.sendSync(RelayCommands.RSP_FS_UPREADY, "error|${e.message}".toByteArray(Charsets.UTF_8), 5000)
            return
        }
        Log.i(TAG, "★ 文件上传开始: ${targetFile.absolutePath} 预期=$expectedSize 续传偏移=$offset")
        val ok = sender.sendSync(RelayCommands.RSP_FS_UPREADY, "ok|$offset".toByteArray(Charsets.UTF_8), 10000)
        if (!ok) {
            Log.w(TAG, "上传: RSP_FS_UPREADY 发送失败")
        }
    }

    /**
     * 上传数据块处理：二进制负载 = 4字节大端块序号 + 文件数据
     * 写入文件后回 RSP_FS_UPACK(块序号) 确认。
     */
    private fun handleFsUpData(payload: ByteArray, sender: RelaySender?, peerKey: Any?): Pair<String, String>? {
        if (sender == null || payload.size < 4) return null
        val session = sessionOf(peerKey)
        if (session.fsUploadCancelled) return null
        val raf = session.fsUploadRaf
        if (raf == null) {
            Log.w(TAG, "上传数据到达但文件未打开")
            return null
        }
        // 解析块序号
        val seq = ((payload[0].toInt() and 0xFF) shl 24) or
                  ((payload[1].toInt() and 0xFF) shl 16) or
                  ((payload[2].toInt() and 0xFF) shl 8) or
                  (payload[3].toInt() and 0xFF)
        // ★★★ 2026-09-21 块序号必须连续（按"当前已写字节"推算期望序号）：
        //   控制端一旦把同一个文件的数据发了两遍（实测：一条 upready 被派发两次 →
        //   sendUploadData 跑两遍），第二遍的块会落在**下一份文件**的会话上，把那个文件
        //   写成 "大小不匹配(197169/241660)"；上一份文件则被多写 128KB 却回"上传成功"。
        //   两侧块号换算公式一致（控制端 uploadBlockSeq = 偏移/128K；被控端 = 已写/128K），
        //   所以序号对不上就一定不是本文件该收的块 —— 直接丢弃，绝不写进当前文件。
        val expectedSeq = session.fsUploadNextSeq
        if (seq != expectedSeq) {
            Log.w(TAG, "上传块序号不连续，丢弃: seq=$seq 期望=$expectedSeq 已写=${session.fsUploadReceived}")
            return null
        }
        val data = payload.copyOfRange(4, payload.size)
        try {
            raf.write(data)
            session.fsUploadReceived += data.size
            session.fsUploadNextSeq = seq + 1
        } catch (e: Exception) {
            Log.w(TAG, "上传写入失败: ${e.message}")
            try { raf.close() } catch (_: Throwable) {}
            session.fsUploadRaf = null
            sender.sendSync(RelayCommands.RSP_FS_UPRST, "0|failed|写入失败: ${e.message}".toByteArray(Charsets.UTF_8), 5000)
            return null
        }
        // 回 ACK
        sender.sendSync(RelayCommands.RSP_FS_UPACK, seq.toString().toByteArray(Charsets.UTF_8), 10000)
        return null
    }

    /**
     * 上传完成处理：关闭文件、校验大小，回 RSP_FS_UPRST
     */
    private fun finishFsUpload(sender: RelaySender?, peerKey: Any?, expectSha: String? = null): Pair<String, String>? {
        val session = sessionOf(peerKey)
        try { session.fsUploadRaf?.close() } catch (_: Throwable) {}
        session.fsUploadRaf = null
        val path = session.fsUploadPath
        val expected = session.fsUploadExpected
        val received = session.fsUploadReceived
        val wantSha = (expectSha ?: "").trim().lowercase()
        val file = File(path)
        if (sender != null) {
            val result = if (!file.exists()) {
                "0|failed|文件不存在"
            } else if (expected > 0 && file.length() != expected) {
                "0|failed|大小不匹配(${file.length()}/${expected})"
            } else if (wantSha.length == 64 && sha256File(file) != wantSha) {
                // ★ 2026-09-22 内容校验失败：只比大小无法发现"等长但内容损坏"，这里删除损坏文件，
                //   绝不留下坏文件（控制端据此判失败并重传）。
                try { file.delete() } catch (_: Throwable) {}
                Log.w(TAG, "★ 上传内容校验失败(sha256)，已删除损坏文件: $path")
                "0|failed|内容校验(sha256)不符，已删除损坏文件"
            } else {
                "1|success|上传成功"
            }
            Log.i(TAG, "★ 文件上传完成: $path 预期=$expected 实际=${file.length()} 结果=$result")
            sender.sendSync(RelayCommands.RSP_FS_UPRST, result.toByteArray(Charsets.UTF_8), 10000)
        }
        session.fsUploadPath = ""
        session.fsUploadExpected = 0L
        session.fsUploadReceived = 0L
        session.fsUploadCancelled = false
        return null
    }

    /** ★ 2026-09-22 计算文件 sha256（十六进制小写，64 字符）；失败返回空串 */
    private fun sha256File(f: File): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(f).use { input ->
                val buf = ByteArray(FS_BLOCK_BYTES)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        } catch (e: Exception) {
            Log.w(TAG, "计算 sha256 失败: ${e.message}")
            ""
        }
    }

    // ==================== ★ 文件属性（2026-09-07新增，Windows风格） ====================

    /**
     * 获取文件/目录属性：返回 JSON 字符串，包含 Windows 风格的属性信息。
     * Android File 类不支持创建时间和访问时间，这两项用 lastModified() 代替。
     */
    private fun handleFsStat(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "{}"
        val f = File(p)
        if (!f.exists()) return "{}"
        val isDir = f.isDirectory
        val map = linkedMapOf<String, Any>()
        map["name"] = f.name ?: p
        map["type"] = if (isDir) "目录" else "文件"
        map["path"] = f.parent ?: p
        map["size"] = if (isDir) 0L else f.length()
        map["mtime"] = f.lastModified()
        // Android 不支持创建/访问时间，用 lastModified 代替
        map["ctime"] = f.lastModified()
        map["atime"] = f.lastModified()
        map["hidden"] = f.isHidden
        map["readonly"] = !f.canWrite()
        map["canRead"] = f.canRead()
        map["canWrite"] = f.canWrite()
        if (isDir) {
            // ★★★ 2026-09-24 目录要给出【所有子文件夹与文件的大小总和】（用户要求）。
            //   以前目录 size 恒为 0，控制端属性框里只能看到"多少个文件/文件夹"、看不到体积。
            //   这里用一次递归统计：dirCount/fileCount（**全部层级**，不再只数一层）
            //   + totalSize（所有子文件大小之和），并额外给出占用空间（按 4KB 簇估算）。
            var dirCount = 0
            var fileCount = 0
            var totalSize = 0L
            val stack = java.util.ArrayDeque<File>()
            stack.add(f)
            while (!stack.isEmpty()) {
                val cur = stack.removeFirst()
                val children = try { cur.listFiles() } catch (e: Exception) { null } ?: continue
                for (c in children) {
                    if (c.isDirectory) {
                        dirCount++
                        stack.add(c)
                    } else {
                        fileCount++
                        try {
                            totalSize += c.length()
                        } catch (e: Exception) {
                            // 单个文件取大小失败（权限/已删除）不影响整体统计
                        }
                    }
                }
            }
            map["dirCount"] = dirCount
            map["fileCount"] = fileCount
            map["size"] = totalSize              // ★ 目录：所有子项大小总和
            map["totalSize"] = totalSize         // 兼容字段：控制端可读 totalSize
            val cluster = 4096L
            map["sizeOnDisk"] = ((totalSize + cluster - 1) / cluster) * cluster
        }
        return gson.toJson(map)
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
    private fun startFsDownload(raw: String, sender: RelaySender?, peerKey: Any?) {
        if (sender == null) {
            Log.w(TAG, "文件下载失败: sender为空，无法推送数据")
            return
        }
        // ★ 2026-09-04 断点续传：负载允许 "<路径>|<已收字节>"。
        //   只有竖线后全是数字才当作续传偏移，路径本身含'|'时不会被误解析。
        val trimmed = raw.trim()
        var p = trimmed
        var reqOffset = 0L
        run {
            val idx = trimmed.lastIndexOf('|')
            if (idx <= 0) return@run
            val tail = trimmed.substring(idx + 1)
            if (tail.isEmpty() || !tail.all { it.isDigit() }) return@run
            reqOffset = tail.toLongOrNull() ?: 0L
            if (reqOffset > 0L) p = trimmed.substring(0, idx)
        }
        val f = File(p)
        if (!f.exists()) {
            sender.send(RelayCommands.RSP_FS_GET, "0||not_found")
            return
        }
        if (f.isDirectory) {
            sender.send(RelayCommands.RSP_FS_GET, "0||error")
            return
        }
        // ★ 2026-09-04 修复①：续传偏移只有在 0 < reqOffset < 文件大小 时才可用，否则整份重传。
        //   原来写的是 minOf(reqOffset, 文件大小)/块大小：当本地 .part 比远端文件更长时
        //   （同名文件被换成了更小的版本），仍然返回一个**非零**起始块 → 控制端据此把半截
        //   .part 截断到该处再追加，拼出"总长度等于 total、开头却是另一份文件的前缀"的坏文件，
        //   并且能通过 onDownloadDone 的长度校验被改名成交付文件。
        //   PC 被控端 gui/client_gui.py::_cmd_download_file 对同一规则已是
        //   "_req_offset >= file_size → 整份重传"，这里补齐双端契约一致。
        // ★ 续传偏移向下取整到块边界：块序号与字节偏移必须一一对应，否则控制端去重会错位
        val _fileLen = f.length()
        val startBlock = if (reqOffset in 1L until _fileLen) (reqOffset / FS_BLOCK_BYTES).toInt() else 0
        val startBytes = startBlock.toLong() * FS_BLOCK_BYTES
        // ★★★ 下载互斥：同一时间只允许一个下载线程（2026-08-07致命修复）
        //   根因：旧下载（控制端超时但未发CANCEL）线程仍在等ACK重发死循环，
        //   新下载启动后两个线程并发写同一socket → 数据帧交错 → ACK全部丢失 → 下载卡死
        // ★ 2026-09-04 修复②：旧线程的引用必须在**命令线程**上取。原来取在线程体内，而
        //   `.apply { fsDownloadThread = this }.start()` 中 apply 先于 start 执行，线程体再读
        //   该字段读到的就是它自己，于是：
        //     ① 互斥完全失效（上一个下载线程的引用已被覆盖，再没人能取消它）；
        //     ② oldThread.join(3000) 变成自己 join 自己 → 每次下载必然白等满 3 秒；
        //     ③ 这 3 秒内控制端发来的 CMD_FS_CANCEL 被体内紧随的 cancelled=false 清掉。
        //   现在作废旧线程改由 fsDownloadGeneration 承担（代号一变，旧线程在下一个检查点自行
        //   退出），不再依赖会被新下载清回的 cancelled 标志；join 只用于缩小并发窗口。
        val sess = sessionOf(peerKey)
        val previousThread = sess.fsDownloadThread
        val myGeneration = sess.fsDownloadGeneration + 1L
        sess.fsDownloadGeneration = myGeneration
        sess.fsDownloadCancelled = false
        // ★ 每个下载会话独立的ACK序号
        //   续传时基线为 startBlock-1，等待第一块（startBlock）的ACK即可，无需重发已收块
        sess.fsAckBlock = startBlock.toLong() - 1L
        // ★ 连续ACK超时重发计数：超过上限自动放弃（防止控制端离线后线程永久重发占用连接）
        val maxConsecutiveRetries = 5
        val t = Thread({
            try {
                if (previousThread != null && previousThread !== Thread.currentThread()
                    && previousThread.isAlive) {
                    Log.w(TAG, "★ 检测到旧下载线程仍在运行，等待其退出 (新文件=$p)")
                    try {
                        previousThread.join(5000)
                    } catch (e: InterruptedException) {
                        // ignore
                    }
                    if (previousThread.isAlive) {
                        Log.w(TAG, "★ 旧下载线程5秒内未退出（可能阻塞在sendSync），已由下载代号作废")
                    } else {
                        Log.i(TAG, "★ 旧下载线程已退出，开始新下载")
                    }
                }
                // ★ 本线程的停止判据：被 CMD_FS_CANCEL 取消，或已被新下载取代（代号已变）
                val stopped: () -> Boolean = {
                    sess.fsDownloadCancelled || sess.fsDownloadGeneration != myGeneration
                }
                var consecutiveRetries = 0
                val total = f.length()
                // ★ 等待连接就绪后再发送元信息（连接断开时等待重连，最多30秒）
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载取消: 连接未恢复 $p")
                    return@Thread
                }
                // ★ 元信息(RSP_FS_GET)也同步发送，确保控制端一定收到才能显示进度条
                //   续传时追加第4/5字段（起始块号、起始字节），普通下载保持三字段兼容旧控制端
                val meta = if (startBlock > 0) {
                    "$total|${f.name}|ok|$startBlock|$startBytes"
                } else {
                    "$total|${f.name}|ok"
                }
                Log.i(TAG, "[FS调试] 发送元信息 $meta time=${System.currentTimeMillis()}")
                if (!sender.sendSync(RelayCommands.RSP_FS_GET, meta.toByteArray(Charsets.UTF_8), 15000)) {
                    Log.w(TAG, "文件下载: 发送元信息失败 $p")
                    return@Thread
                }
                val buf = ByteArray(FS_BLOCK_BYTES)
                var sentBytes = startBytes
                var blockIdx = startBlock
                var lastLogProgress = -1
                // ★ 2026-09-22 整包 sha256：续传时先把"已有前缀"喂进摘要，发送中再逐块累计，
                //   得到整包哈希随 CMD_FS_DONE 下发；控制端收完后据此校验内容（空=不校验）。
                val md = java.security.MessageDigest.getInstance("SHA-256")
                if (startBytes > 0L) {
                    try {
                        java.io.RandomAccessFile(f, "r").use { pre ->
                            var remaining = startBytes
                            val pb = ByteArray(FS_BLOCK_BYTES)
                            while (remaining > 0L) {
                                val want = minOf(pb.size.toLong(), remaining).toInt()
                                val r = pre.read(pb, 0, want)
                                if (r <= 0) break
                                md.update(pb, 0, r)
                                remaining -= r
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "下载续传前缀哈希计算失败: ${e.message}")
                    }
                }
                // ★ 分块确认：控制端每收到一块回ACK(块序号)，被控端等待确认后才发送下一块
                //   参照PC端微信_download_send_file_blocked：发送→等待ACK→确认后继续
                //   防止中继→控制端链路拥塞时盲目高速发送导致数据堆积丢失
                java.io.RandomAccessFile(f, "r").use { ins ->
                    if (startBytes > 0L) ins.seek(startBytes)
                    while (true) {
                        // ★ 检查停止：控制端发 CMD_FS_CANCEL，或本线程已被新下载取代（代号已变）
                        if (stopped()) {
                            Log.i(TAG, "文件下载已停止: $p (已发送 $sentBytes/$total bytes, 块$blockIdx)")
                            return@Thread
                        }
                        // ★ 2026-09-04 修复③：必须"读满一块或到文件尾"。RandomAccessFile.read(byte[])
                        //   不保证一次读满（并发写入的文件/短读都可能），一旦短读，blockIdx 与实际
                        //   文件位置从此永久错位——块号不再等于 偏移/块大小，而续传正是按
                        //   startBlock*FS_BLOCK_BYTES 去 seek 的，中间就会漏字节。
                        var n = 0
                        while (n < buf.size) {
                            val r = ins.read(buf, n, buf.size - n)
                            if (r <= 0) break
                            n += r
                        }
                        if (n <= 0) break
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        md.update(chunk, 0, chunk.size)   // ★ 2026-09-22 计入整包 sha256
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
                            if (stopped()) {
                                Log.i(TAG, "文件下载已停止(等ACK): $p (块$blockIdx)")
                                return@Thread
                            }
                            if (sessionOf(peerKey).fsAckBlock >= blockIdx) {
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
                                if (stopped()) {
                                    Log.i(TAG, "文件下载已停止(等延迟ACK): $p (块$blockIdx)")
                                    return@Thread
                                }
                                if (sessionOf(peerKey).fsAckBlock >= blockIdx) {
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
                // ★ 2026-09-04 修复④：发 DONE 前必须核对实际发出的字节数。控制端 onDownloadDone
                //   只比 "磁盘长度 == total"，所以残缺内容一旦收到 DONE 就会被改名成交付文件并
                //   提示"下载完成"。这里既不发 DONE（会让控制端把坏文件转正），也不回
                //   "0||error"（error 分支会删掉 .part、把断点一起毁掉）——只记日志并退出，
                //   交给控制端的停滞/中断处理保留断点，用户再点一次即可续传。
                if (sentBytes != total) {
                    Log.w(TAG, "★ 下载不完整，不发 CMD_FS_DONE: $p 已发 $sentBytes/$total (块$blockIdx) — 保留控制端断点")
                    return@Thread
                }
                // ★ 等待连接就绪后再同步发送完成标记（确保控制端收到后关闭进度条）
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载完成但连接断开，无法发送完成标记: $p")
                    return@Thread
                }
                // ★ 2026-09-22 完成帧携带整包 sha256（控制端收完后校验内容）
                val doneSha = try {
                    md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                } catch (e: Exception) {
                    ""
                }
                if (!sender.sendSync(RelayCommands.CMD_FS_DONE, doneSha.toByteArray(Charsets.UTF_8), 15000)) {
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
                if (sessionOf(peerKey).fsDownloadThread === Thread.currentThread()) {
                    sessionOf(peerKey).fsDownloadThread = null
                }
            }
        }, "FsDownload").apply {
            isDaemon = true
            sessionOf(peerKey).fsDownloadThread = this
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
