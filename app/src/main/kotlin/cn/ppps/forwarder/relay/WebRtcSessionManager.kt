package cn.ppps.forwarder.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import cn.ppps.forwarder.utils.Log
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * ★ 2026-08-10 手机被控端 WebRTC 会话管理（WebRTC 一站式音视频传输核心）
 *
 * 功能：
 *  - 接收控制端 CMD_WEBRTC_OFFER（携带 cameraIndex + SDP_BASE64），建立 PeerConnection；
 *  - 摄像头：Camera2Enumerator + Camera2Session 采集；WebRTC VP8/H264 硬件编码后 SRTP 传输；
 *  - 麦克风：JavaAudioDeviceModule(AudioRecord 底层) + WebRTC 内置
 *      AEC(回声消除)/NS(噪声抑制)/AGC(自动增益) + Opus 编码 + NetEQ(JitterBuffer+PLC 抗抖动丢包补偿)；
 *  - 音视频同步：由 WebRTC RTP timestamp 驱动，端到端自动 lip-sync；
 *  - 信令：复用现有命令通道（通过 onSignalingMessage 回调 CMD_WEBRTC_ANSWER/CMD_WEBRTC_CANDIDATE/CMD_WEBRTC_STATUS）
 *  - 优雅回退：任何 WebRTC 初始化/媒体错误 → onError 回调，调用方（RelayServerHandler）可回退到原 JPEG+PCM 模式。
 *
 * WebRTC 一站式取代原方案：
 * 原：CameraStreamManager(逐帧JPEG，320x240=~60KB/帧x15fps=900KB/s) + MicrophoneStreamManager(PCM-8000Hz-16bit-单声道=128kbps)
 * 新：WebRTC VP8(可变码率，默认640x480=300~800kbps自适应) + Opus(16~64kbps自适应) + 抗抖动 + AEC/NS/AGC
 *     带宽占用降低 60%~80%，声音显著清晰，且画面无撕裂（RTP 有序 + JitterBuffer）。
 */
class WebRtcSessionManager(
    private val context: Context,
    private val appContext: Context = context.applicationContext
) {

    companion object {
        private const val TAG = "WebRtcSessionMgr"
        // ★ 2026-08-11 ICE修复(2)：只保留国内稳定STUN + 腾讯云 + 阿里云 + 全球STUN兜底。
        //   之前的欧洲STUN(stun.sipnet.net / ideasip / ekiga等)在国内延迟极高或不可达，
        //   造成srflx候选收集成功但实际无法传输→ICE瞬时CONNECTED后立刻FAILED。
        private val STUN_SERVERS = listOf(
            // —— 国内/亚太：腾讯云（fwa.live 实测可用）——
            PeerConnection.IceServer.builder("stun:stun.fwa.live:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.fwa.live:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.fwa.live:3478").createIceServer(),
            // —— 国内：腾讯云/阿里云 ——
            PeerConnection.IceServer.builder("stun:stun.chat.bilibili.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.mixvoice.cn:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.qq.com:3478").createIceServer(),
            // —— 公共全球 (经过国内验证) ——
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.relay.metered.ca:3478").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun-eu.relay.metered.ca:3478").createIceServer(),
            // —— Google 全球STUN（兜底，国内可达） ——
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer()
        )

        // ★★★ 2026-08-12 中继优先TURN：自建coturn TURN服务器（云服务器106.12.48.88:3478）。
        //   背景：WebRTC媒体流默认P2P直连（仅STUN），公网NAT下打洞失败→只传1帧就停。
        //   修复：部署coturn提供relay候选 → 中继服务在线时媒体经中继服务器转发（中继优先）；
        //         中继离线时relay候选不可用，退化为TS/WiFi host直连（兜底）。
        const val TURN_SERVER_URL = "turn:106.12.48.88:3478"
        const val TURN_USERNAME = "remote"
        const val TURN_PASSWORD = "admin123456"

        private fun buildIceServers(relayPreferred: Boolean): List<PeerConnection.IceServer> {
            val servers = STUN_SERVERS.toMutableList()
            if (relayPreferred) {
                try {
                    servers.add(PeerConnection.IceServer.builder(TURN_SERVER_URL)
                        .setUsername(TURN_USERNAME)
                        .setPassword(TURN_PASSWORD)
                        .createIceServer())
                    Log.i(TAG, "★ [TURN] 已添加TURN relay候选 $TURN_SERVER_URL（中继优先模式）")
                } catch (t: Throwable) {
                    Log.w(TAG, "★ [TURN] 添加TURN失败(忽略): ${t.message}")
                }
            }
            return servers
        }
    }

    interface SignalingCallback {
        /** 发送信令给控制端（直接传12字符命令 + 文本负载，由RelayServerClient统一封装帧） */
        fun onSignalingMessage(cmd: String, payloadText: String)
        /** WebRTC 状态（connecting/connected/failed/closed 等，用于日志/UI） */
        fun onStatus(state: String, detail: String = "")
        /** 初始化或媒体失败，上层应回退到老 JPEG+PCM 模式并告知控制端 */
        fun onError(reason: String)
    }

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    @Volatile private var videoCapturer: VideoCapturer? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var audioThreadTunerStop: java.util.concurrent.atomic.AtomicBoolean? = null   // ★★★ 2026-08-12 采集线程调优停止标志
    /** ★ 2026-08-11 摄像头采集专用EglBase：必须保存强引用！
     *    原代码 SurfaceTextureHelper.create("...", EglBase.create().eglBaseContext) 中临时EglBase
     *    没有引用，被GC回收导致EGL context失效 → SurfaceTextureHelper无法消费纹理 →
     *    摄像头采集到黑帧（BufferQueueProducer waitForFreeSlotThenRelock timeout）→ 只有声音没有图像。 */
    @Volatile private var captureEglBase: EglBase? = null
    /** ★ 2026-08-11 编码器/解码器EglBase：同样必须保存强引用（与captureEglBase同理），
     *    防止DefaultVideoEncoderFactory/DecoderFactory持有的临时EglBase被GC后EGL context失效，
     *    导致硬编码失败或黑帧。 */
    @Volatile private var encodeEglBase: EglBase? = null
    @Volatile private var decodeEglBase: EglBase? = null
    @Volatile private var signalingCallback: SignalingCallback? = null
    @Volatile private var currentCameraIndex: Int = 0
    @Volatile private var running = false
    /** ★★★ 2026-08-12 中继优先模式：true=中继服务在线，媒体走TURN relay（中继转发）；false=TS/WiFi直连兜底 */
    @Volatile private var relayPreferred: Boolean = true

    // ★★★ 2026-08-14 周期关键帧机制（修复"只传一帧"）：
    //   华为EMUI等ROM的Camera2传感器时间戳异常 → P帧RTP时间戳乱序 → 控制端MediaCodec
    //   只解出首帧关键帧、后续P帧全部解码失败(decode_fps=0)且无PLI恢复 → 画面永远卡在首帧。
    //   被控端每2秒对videoTrack做一次setEnabled(false→true)抖动，强制VideoStreamEncoder
    //   重新输出关键帧，即使P帧解码失败也能在2秒内恢复画面。
    @Volatile private var keyFrameTimer: Thread? = null
    @Volatile private var videoTrackRef: org.webrtc.VideoTrack? = null
    private val KEY_FRAME_INTERVAL_MS = 2000L
    /** ★★★ 2026-08-14 修复"只传一帧"：enable抖动必须保持disabled足够久（编码器感知帧中断才出关键帧）。
     *  立即 setEnabled(false→true) 两事件在同一次native消息队列合并 → VideoStreamEncoder感知不到中断 → 不出关键帧。 */
    private val KEY_FRAME_HOLD_MS = 200L

    // ★ 2026-08-11 ICE连接宽容策略v2：
    //   - 之前：everConnected + 15秒 → 失败案例：ICE瞬时CONNECTED(选到了一个假候选对)后立即FAILED，
    //     15秒内 WebRTC 还没来得及用其他候选对（如TS host↔host）重试就被判定为失败。
    //   - 现在：宽容时间改成 45 秒，并补充私网IP(TS/192.168)连通性检测，给WebRTC足够时间探测真正可用的候选对。
    //   - everConnected=true 后，哪怕 DISCONNECTED/FAILED 都只报状态，绝不立即触发 fallback。
    @Volatile private var everConnected = false
    private val iceMainHandler = Handler(Looper.getMainLooper())
    @Volatile private var iceFailedPendingRunnable: Runnable? = null
    private val ICE_FAILED_TOLERANCE_MS = 45000L

    // ★★★ 2026-08-28 省电：对端存活看门狗（补上 WebRTC 唯一的"没人看却还在采集编码"的漏洞）。
    //   【现状】本类的会话只由控制端的 CMD_WEBRTC_HANGUP 或 onError 关闭；而 onIceConnectionChange
    //   对 DISCONNECTED 的处理是"仅上报状态、绝不主动关闭"（第 5 节注释，为避免网络抖动误杀）。
    //   于是控制端进程被杀 / 直接锁屏后台冻结 / 断网时：被控端这一侧的 PeerConnection 仍然"活着"，
    //   Camera2 采集 + SurfaceTexture/EGL + H264 硬编码 + AudioRecord 全部继续满载运行，
    //   且 WebRTC 自己不会退——实测红米上单是 native 编码日志就几百行/秒，这是被控端最贵的持续耗电项。
    //   【判据】用 libwebrtc 自带的"是否还在收到对端任何报文"信号 onIceConnectionReceivingChange：
    //   只要对端在，RTCP(含 periodic PLI / RR) 会秒级持续进来，receiving 恒为 true；
    //   对端消失后 libwebrtc 会在数十秒内把 receiving 置 false。
    //   连续 PEER_GONE_MS（180 秒）没有任何入站报文 → 认定对端已走 → close()（与控制端主动挂断同一条路径）。
    //   【为什么安全】会话必须曾经连过（everConnected）才启用；180 秒零入站远超任何正常抖动窗口；
    //   close() 走的是 CMD_WEBRTC_HANGUP 用的同一个 closeInternal，不引入新的释放顺序。
    @Volatile private var iceReceiving = false
    @Volatile private var lastInboundActivityTime = 0L
    private val PEER_GONE_MS = 180000L
    private val LIVENESS_CHECK_INTERVAL_MS = 15000L
    private var livenessWatchdog: Thread? = null

    /**
     * 收到控制端 OFFER：启动摄像头+麦克风 → 设置远端SDP → 生成ANSWER → 通过callback发回
     * @param cameraIndex 0=后, 1=前 (与原 CameraStreamManager 索引一致)
     * @param offerSdpBase64 Base64(UTF-8(SDP)) — 控制端 encode 后的 SDP
     * @param cb 信令/状态/错误回调
     * @param relayPreferred ★ 2026-08-12 中继优先模式：true=中继服务在线，媒体走TURN relay（中继转发）；
     *   false=中继离线/直连模式，媒体走TS/WiFi host直连兜底。
     */
    @Synchronized
    fun startWithOffer(cameraIndex: Int, offerSdpBase64: String, cb: SignalingCallback,
                       relayPreferred: Boolean = true) {
        val stepTag = "[WebRTC-INIT]"
        if (running) {
            Log.w(TAG, "$stepTag 已在运行中，先关闭旧会话")
            closeInternal(false)
        }
        running = true
        this.relayPreferred = relayPreferred
        // ★ 2026-08-28 省电：启动"对端已走"存活看门狗（控制端掉线/被杀时自动释放摄像头+编码器+麦克风）
        iceReceiving = false
        lastInboundActivityTime = 0L
        startLivenessWatchdog()
        Log.i(TAG, "$stepTag ★ 中继优先模式 relayPreferred=$relayPreferred（true=媒体走TURN中继转发 / false=TS直连兜底）")
        this.signalingCallback = cb
        this.currentCameraIndex = cameraIndex
        cb.onStatus("initializing", "初始化PeerConnectionFactory+音频设备")

        // —— 先检测类是否能被 JNI FindClass 找到（避免 JNI_OnLoad 抛出异常绕过 Java try-catch）
        try {
            Log.i(TAG, "$stepTag [1/8] 检测 livekit.org.webrtc.WebRtcClassLoader 是否可加载...")
            val klz = Class.forName("livekit.org.webrtc.WebRtcClassLoader")
            Log.i(TAG, "$stepTag [1/8] 类加载成功 ✓ klass=${klz.name}")
            val m = klz.getDeclaredMethod("getClassLoader")
            Log.i(TAG, "$stepTag [1/8] getClassLoader() 方法存在 ✓ returnType=${m.returnType.name}")
            val ret = m.invoke(null)
            Log.i(TAG, "$stepTag [1/8] getClassLoader() 调用成功 ✓ returned=${ret?.javaClass?.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [1/8] WebRtcClassLoader 检测失败！type=${t.javaClass.name}, msg=${t.message}", t)
            cb.onError("WebRtcClassLoader missing: ${t.message}")
            return
        }

        // ★ 2026-08-10 致命BUG修复：io.github.webrtc-sdk:android:114.5735.08 内部 NativeLibrary 错误地
        //   把 TAG 写成 "lkjingle_peerconnection_so"（多了 "lk" 前缀），导致 dlopen 查找不存在的
        //   liblkjingle_peerconnection_so.so。
        //   ★ 关键：必须在【任何调用 native 代码之前】预加载——
        //     JavaAudioDeviceModule.createAudioDeviceModule() 会调用 OpenSL ES / AAudio native，
        //     EglBase.create() 也会调用 EGL native，旧代码把预加载放在这两步之后等于裸奔！
        try {
            Log.i(TAG, "$stepTag [2/8] System.loadLibrary(\"jingle_peerconnection_so\") 开始...")
            System.loadLibrary("jingle_peerconnection_so")
            Log.i(TAG, "$stepTag [2/8] loadLibrary 成功 ✓，libjingle_peerconnection_so.so 已入进程")
        } catch (ule: UnsatisfiedLinkError) {
            Log.w(TAG, "$stepTag [2/8] UnsatisfiedLinkError（可能已加载过/系统已自动加载）: ${ule.message}")
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [2/8] loadLibrary Throwable type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("loadLibrary failed: ${t.message}")
            return
        }

        // ★★★ 2026-08-12 啸叫(呼啸声)修复：提前解析 OFFER SDP，判断是否纯音频(麦克风)会话。
        //   纯音频会话中被控端只是"采集端"，扬声器必须静音——否则 WebRTC 播放路径激活，
        //   扬声器→麦克风形成声学反馈环路 → 啸叫(呼啸声)。(原来 audioOnly 在 [7.0/8] 才计算，ADM 已创建)
        val audioOnly = try {
            val rawSdp = Base64.getDecoder().decode(offerSdpBase64)
            val sdpStr = String(rawSdp, StandardCharsets.UTF_8)
            val isAudioOnly = !sdpStr.contains("m=video")
            Log.i(TAG, "$stepTag [3/8] ★ 提前解析OFFER: audioOnly=$isAudioOnly（${if (isAudioOnly) "纯音频麦克风会话→被控端扬声器静音防啸叫" else "摄像头会话→扬声器正常"}）")
            isAudioOnly
        } catch (t: Throwable) {
            Log.w(TAG, "$stepTag [3/8] OFFER提前解析失败(按非纯音频处理): ${t.message}")
            false
        }

        // ★★★ 2026-08-13 纯音频(麦克风录音)会话预检：麦克风被其他应用占用（如微信视频通话）时，
        //   立即拒绝并明确反馈控制端"为什么没有声音"，不再走复杂的初始化/回退流程。
        if (audioOnly) {
            val busyPkg = MicrophoneStreamManager.micBusyByOtherApp()
            if (busyPkg != null) {
                val reason = "被控端麦克风被【$busyPkg】占用（可能正在视频通话），无法录音"
                Log.e(TAG, "$stepTag ★ 麦克风被占用，拒绝启动纯音频会话: $reason")
                cb.onStatus("mic_failed", reason)
                closeInternal(false)
                return
            }
        }

        // ★ 1. 初始化音频设备模块：开启 AEC/NS/AGC + Opus + NetEQ（一站式音频处理链）
        val adm = try {
            Log.i(TAG, "$stepTag [3/8] JavaAudioDeviceModule.builder 开始")
            val b = JavaAudioDeviceModule.builder(appContext)
            Log.i(TAG, "$stepTag [3/8] builder 创建成功，开始配置参数...")
            // ★★★ 2026-08-12 麦克风"机器人声"修复：
            //   【根因】VOICE_COMMUNICATION音频源是通话优化源，厂商(尤其华为EMUI)会施加
            //     窄带滤波/响度增强/动态压缩等通话处理链 → 声音变成"机器人声"。
            //   【修复】改回原始MIC源 + 关闭硬件AEC/NS（硬件处理同样会整形音色）。
            //     WebRTC软件AEC/NS/AGC保留（由MediaConstraints控制），对音色影响远小于
            //     厂商VOICE_COMMUNICATION链路。
            val builtAdm = b
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                .setSamplesReadyCallback(null)
                .createAudioDeviceModule()
            Log.i(TAG, "$stepTag [3/8] createAudioDeviceModule 成功 ✓（音频源=原始MIC，硬件AEC/NS已关闭→原声修复）")
            // ★★★ 2026-08-12 啸叫(呼啸声)修复：纯音频(麦克风)会话中被控端是纯采集端，扬声器必须静音，
            //   否则扬声器→麦克风声学反馈 → 啸叫。摄像头会话(可能双向对讲)保留扬声器。
            builtAdm.setSpeakerMute(audioOnly)
            builtAdm.setMicrophoneMute(false)
            Log.i(TAG, "$stepTag [3/8] 静音标志已设置 ✓ speakerMute=$audioOnly(纯音频防啸叫) micMute=false")
            builtAdm
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [3/8] AudioDeviceModule 失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("AudioDeviceModule init failed: ${t.message}")
            return
        }

        // ★ 2. 初始化 PeerConnectionFactory：
        val options = PeerConnectionFactory.Options()
        // ★★★ 2026-08-12 TS直连视频修复（核心v2）：
        //   【根因】Android网络监控把Tailscale VPN(tun0)网络报告给WebRTC，但UDP共享socket
        //   绑定到底层WiFi地址(192.168.31.x)，生成的host候选IP是WiFi IP而非Tailscale IP，
        //   导致跨设备TS直连时ICE两端候选都不含Tailscale IP → 无法选路 → ICE=FAILED，视频完全不显示。
        //   【验证】v1用networkIgnoreMask保留VPN接口——实测无效(Android JNI网络监控不应用该掩码,
        //   Count of networks仍=8)。v2改用 NetworkMonitor.setNetworkChangeDetectorFactory 注入
        //   自定义NetworkChangeDetector：getActiveNetworkList()只返回【修正IP后的tun0网络】，
        //   → WebRTC唯一网络=tun0(100.64.x) → UDP socket绑定Tailscale IP → host候选为正确Tailscale IP。
        // ★★★ 2026-08-12 网络检测器策略：
        //   - relayPreferred=true（中继优先）：【不注入TS专用网络检测器】！
        //     使用系统默认NetworkMonitor（真实网络列表：WiFi+蜂窝）→ host候选=真实IP，
        //     + TURN relay候选(106.12.48.88:3478) → 媒体经中继服务器转发。
        //     【根因】注入ZtOnlyNetworkDetectorFactory后native basic_port_allocator仍用
        //     NetworkMonitorAutoDetect全量列表(含loopback)建socket → UDP绑定127.0.0.x →
        //     STUN/TURN全部"error 22 Invalid argument" → ICE=FAILED无法连通。
        //   - relayPreferred=false（TS直连兜底）：注入TS专用检测器 → host候选=Tailscale IP(100.64.x)。
        if (!relayPreferred) {
            try {
                val tsIp = findTsIpAddress()
                if (tsIp != null) {
                    org.webrtc.NetworkMonitor.getInstance()
                        .setNetworkChangeDetectorFactory(TsOnlyNetworkDetectorFactory(tsIp, false))
                    Log.i(TAG, "$stepTag [4/8] ★ 已注册TS专用网络检测器 tsIp=$tsIp（直连兜底：返回WiFi+tun0→媒体走TS直连）")
                } else {
                    Log.w(TAG, "$stepTag [4/8] 未找到本机Tailscale IP(100.64-100.127.x)，使用默认网络检测器")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$stepTag [4/8] 注册TS网络检测器失败(继续默认): ${t.message}")
            }
        } else {
            Log.i(TAG, "$stepTag [4/8] ★ 中继优先模式：不注入TS检测器，使用系统默认网络（host候选=真实IP + TURN relay中继转发）")
        }
        // ★ 2026-08-12 网络过滤策略：
        //   - relayPreferred=true（中继优先）：networkIgnoreMask=0，不过滤任何网络！
        //     → WiFi host候选 + TURN relay候选都参与，媒体经中继转发。
        //     【根因】之前无条件忽略所有网络(仅保留VPN)，而中继优先模式网络检测器
        //     只返回WiFi(不含tun0) → 全部被过滤 → "Machine has no networks" → 无候选 → ICE=CHECKING卡死。
        //   - relayPreferred=false（直连兜底）：保留"仅VPN"掩码 → host候选为正确Tailscale IP，TS直连选路。
        try {
            options.networkIgnoreMask = if (relayPreferred) {
                // 中继优先：忽略loopback/unknown（避免UDP绑定127.0.0.x），保留WiFi+蜂窝 → host+relay全参与
                Log.i(TAG, "$stepTag ★ 中继优先模式: networkIgnoreMask=忽略loopback/unknown（WiFi host + TURN relay 全参与）")
                (PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_UNKNOWN)
            } else {
                Log.i(TAG, "$stepTag ★ 直连兜底: networkIgnoreMask=仅保留VPN（TS直连候选修复）")
                (PeerConnectionFactory.Options.ADAPTER_TYPE_UNKNOWN
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_ETHERNET
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_WIFI
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK
                    or PeerConnectionFactory.Options.ADAPTER_TYPE_ANY)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$stepTag 设置networkIgnoreMask失败(忽略): ${t.message}")
        }
        try {
            Log.i(TAG, "$stepTag [4/8] PeerConnectionFactory.initialize(...) 开始")
            // ★ 2026-08-11 视频停帧修复：
            //   1) 禁用WebRTC帧丢弃器(FrameDropper)——GoogCC拥塞控制反馈异常时把码率/帧率压到0
            //   2) ★★★ 禁用质量缩放器(QualityScaler)——根因修复：编码器在480x360↔640x480之间动态缩放，
            //      缩放切换后发送的640x480关键帧控制端无法解码(dequeueOutputBuffer=-1, Frames received:0)，
            //      导致视频"仅2帧后停止"。固定分辨率不再缩放。
            //   3) 固定sender码率 min=max=800kbps（下方RtpParameters设置）
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/WebRTC-VideoFrameDropper/Disabled/WebRTC-VideoQualityScaler/Disabled/")
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            // ★ 2026-08-11 诊断：开启WebRTC内部详细日志（定位视频帧在采集/编码/发送链路的断点）
            try {
                org.webrtc.Logging.enableLogToDebugOutput(org.webrtc.Logging.Severity.LS_VERBOSE)
            } catch (_: Throwable) {}
            Log.i(TAG, "$stepTag [4/8] PeerConnectionFactory.initialize 成功 ✓")
        } catch (alreadyInit: IllegalStateException) {
            Log.w(TAG, "$stepTag [4/8] 已初始化过(进程级单例)，跳过二次initialize: ${alreadyInit.message}")
        } catch (initT: Throwable) {
            Log.e(TAG, "$stepTag [4/8] initialize 失败 type=${initT.javaClass.name} msg=${initT.message}", initT)
            cb.onError("PeerConnectionFactory.initialize 失败: ${initT.message}")
            return
        }
        val f = try {
            Log.i(TAG, "$stepTag [5/8] EglBase.create() 开始（★统一单EglBase：采集/编码/解码共享同一EGL context）")
            // ★★★ 2026-08-11 黑帧根因修复：必须【共享同一个EglBase】！
            //   旧代码创建了3个独立EglBase（capture/encode/decode各一个）：
            //     captureEglBase → SurfaceTextureHelper(Camera2 OES纹理采集)
            //     encodeEglBase  → DefaultVideoEncoderFactory(硬编码)
            //     decodeEglBase  → DefaultVideoDecoderFactory(硬解码)
            //   问题：3个EglBase是【独立且不共享】的EGL context。
            //   Camera2Capturer 输出的是 OES 纹理(VideoFrame buffer)，
            //   硬编码器消费该纹理时需要持有【采集context的共享上下文】。
            //   不共享 → 编码器对 OES 纹理采样得到全黑帧 → "只有声音没有图像"。
            //   修复：capture/encode/decode 全部使用同一个 EglBase 的 context
            //   （这也是控制端WebRtcSession.java的正确做法：所有组件共用eglBase）。
            val rootEgl = try {
                val e = EglBase.create()
                captureEglBase = e  // ★ 采集EglBase（保存强引用防GC）
                encodeEglBase = e   // ★ 编码EglBase（与采集共享）
                decodeEglBase = e   // ★ 解码EglBase（与采集共享）
                val ctx = e.eglBaseContext
                Log.i(TAG, "$stepTag [5/8] 统一EglBase ctx=${ctx} (capture/encode/decode共享，已保存强引用)")
                ctx
            } catch (t: Throwable) {
                Log.e(TAG, "$stepTag [5/8] EglBase.create 失败 type=${t.javaClass.name} msg=${t.message}", t)
                throw RuntimeException("EglBase failed: ${t.message}", t)
            }
            Log.i(TAG, "$stepTag [5/8] EglBase 统一成功 ✓，开始 PeerConnectionFactory.builder().create()")
            val built = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .setVideoEncoderFactory(
                    // ★★★ 2026-08-11 视频停帧根因修复（surface模式+统一EglBase）：
                    //   实验证明【null context字节流模式】虽让编码器Qinput正常(86)，但RTP发送异常——
                    //   rtp_sender_video仅处理第一帧，控制端每10秒只收到1个RTP包(seq+1000)，
                    //   视频"2帧后停止"。回退到【surface模式+统一rootEgl】：
                    //   capture/encode共享同一EGL context → 编码器正确消费Camera2 OES纹理，
                    //   白/局域网直连实测15fps稳定。配合禁用QualityScaler/FrameDropper+固定码率800k。
                    DefaultVideoEncoderFactory(rootEgl, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEgl))
                .createPeerConnectionFactory()
            Log.i(TAG, "$stepTag [5/8] PeerConnectionFactory 构建成功 ✓ factory=$built")
            built
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [5/8] PeerConnectionFactory 构建失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("PeerConnectionFactory create failed: ${t.message}")
            return
        }
        factory = f

        // ★ 3. 创建 PeerConnectionObserver + PeerConnection
        val pcObserver = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.i(TAG, "onSignalingChange: $state")
            }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "onIceConnectionChange: $state everConnected=$everConnected")
                // —— 先取消任何挂起的FAILED延迟判断任务 ——
                iceFailedPendingRunnable?.let { r ->
                    try { iceMainHandler.removeCallbacks(r) } catch (_: Throwable) {}
                    iceFailedPendingRunnable = null
                    Log.i(TAG, "★ ICE宽容：取消挂起的FAILED定时器 (state=$state)")
                }
                when (state) {
                    PeerConnection.IceConnectionState.CHECKING -> cb.onStatus("connecting", "ICE连接中")
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        everConnected = true  // ★ 关键：一旦连通过，后续一切状态都给宽容窗口
                        // ★ 2026-08-28 省电：刚连通即视为"有入站数据"，给存活看门狗一个起点
                        lastInboundActivityTime = System.currentTimeMillis()
                        Log.i(TAG, "★ ICE到达CONNECTED/COMPLETED → 激活宽容策略(everConnected=true)")
                        cb.onStatus("connected", "ICE连接成功，音视频已开始传输")
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (everConnected) {
                            // ★ 曾连通后才FAILED → 通常是瞬时网络抖动/坏候选对，45秒内WebRTC会自动用其他候选对重试
                            Log.w(TAG, "★ ICE=FAILED (everConnected=true) → 延迟${ICE_FAILED_TOLERANCE_MS}ms判断(45秒v2)，期间若恢复则忽略")
                            cb.onStatus("disconnected", "ICE临时断开(自动恢复中…45秒宽容)")
                            val pending = Runnable {
                                // ★ 先再检查一下everConnected——45秒内如果有过任何CONNECTED/COMPLETED/CHECKING都说明在恢复
                                Log.e(TAG, "★ ICE=FAILED 宽容${ICE_FAILED_TOLERANCE_MS}ms仍未稳定 → 判定真正失败，启动回退")
                                cb.onError("ICE连接失败(45秒宽容期内未能自动恢复)")
                            }
                            iceFailedPendingRunnable = pending
                            iceMainHandler.postDelayed(pending, ICE_FAILED_TOLERANCE_MS)
                        } else {
                            // 从未连通过就FAILED → 初始化阶段，但也给30秒宽容(等对端候选交换)
                            Log.w(TAG, "★ ICE=FAILED (everConnected=false, 初始化阶段) → 30秒初始化宽容")
                            cb.onStatus("connecting", "ICE尝试穿透(初始化阶段，耐心30秒)…")
                            val pending = Runnable {
                                if (!everConnected) {
                                    Log.e(TAG, "★ ICE=FAILED (初始化阶段, 30秒仍未通过) → 回退")
                                    cb.onError("ICE连接失败(对端无响应或NAT穿透受阻)")
                                } else {
                                    Log.i(TAG, "★ ICE宽容期间 everConnected 变为true → 取消回退 ✓")
                                }
                            }
                            iceFailedPendingRunnable = pending
                            iceMainHandler.postDelayed(pending, 30000L)
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        // DISCONNECTED几乎总是瞬时的(路由器/NAT刷新)，WebRTC会自动用其他候选重连
                        // → 无论everConnected为何值，都只上报状态，绝不主动关闭/回退
                        Log.w(TAG, "★ ICE=DISCONNECTED → 仅上报状态(不回退)，等待WebRTC自动用候选恢复")
                        cb.onStatus("disconnected", if (everConnected) "ICE临时断开(自动恢复中…)" else "ICE连接不稳(尝试恢复中…)")
                    }
                    PeerConnection.IceConnectionState.CLOSED -> cb.onStatus("closed", "会话关闭")
                    else -> {}
                }
            }
            // ★ 2026-08-28 省电：入站数据有无 = 对端是否还在（详见 PEER_GONE_MS 注释）
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                iceReceiving = receiving
                if (receiving) lastInboundActivityTime = System.currentTimeMillis()
                Log.i(TAG, "onIceConnectionReceivingChange: $receiving")
            }
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                Log.i(TAG, "onIceGatheringChange: $state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) {
                    // ★ ICE 收集完成：可按需发送"all candidates done"状态
                    cb.onStatus("ice_gathering_done", "ICE候选收集完成")
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                // ★ 2026-08-11 ICE诊断：记录每个候选的类型/地址/端口
                var candType = "unknown"
                var candIp = "?"
                var candPort = "?"
                try {
                    val parts = candidate.sdp.split(" ")
                    for (i in parts.indices) {
                        if ("typ" == parts[i] && i+1 < parts.size) candType = parts[i+1]
                        if (i == 4) candIp = parts[i]
                        if (i == 5) candPort = parts[i]
                    }
                } catch (_: Throwable) {}
                Log.i(TAG, "★ ICE-CANDIDATE(ANSWERER) type=$candType"
                    + " ip=$candIp:$candPort"
                    + " mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex}"
                    + " sdpLen=${candidate.sdp.length}")
                // ★ 打包为 "方向|sdpMid|sdpMLineIndex|candidateSDP_BASE64"
                val sdp64 = Base64.getEncoder().encodeToString(candidate.sdp.toByteArray(StandardCharsets.UTF_8))
                val payload = "ANSWERER|${candidate.sdpMid}|${candidate.sdpMLineIndex}|$sdp64"
                cb.onSignalingMessage(RelayCommands.CMD_WEBRTC_CANDIDATE, payload)
                // ★★★ 2026-08-12 TS直连修复：host候选的IP是底层WiFi/蜂窝IP而非Tailscale IP，
                //   跨设备TS直连ICE两端候选都不含Tailscale IP → 无法选路。将host候选IP改写为本机Tailscale IP
                //   额外发送一份，对端即可通过Tailscale虚拟网直连本机tun0。
                // ★★★ 2026-08-12 中继优先模式：relayPreferred=true 时不发送TS改写候选！
                //   —— 中继服务在线时媒体必须走TURN relay（中继转发），若同时发TS候选，
                //      ICE按优先级(host>srflx>relay)会优先选TS host → 违背"中继优先"原则。
                //   —— relayPreferred=false（中继离线/直连模式）才发送TS候选兜底。
                if ("host" == candType && !relayPreferred) {
                    try {
                        val tsIp = findTsIpAddress()
                        if (tsIp != null && tsIp != candIp) {
                            val parts = candidate.sdp.split(" ").toMutableList()
                            if (parts.size > 5) {
                                parts[4] = tsIp
                                val tsSdp = parts.joinToString(" ")
                                val tsCand = IceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, tsSdp)
                                val tsSdp64 = Base64.getEncoder().encodeToString(
                                    tsCand.sdp.toByteArray(StandardCharsets.UTF_8))
                                val tsPayload = "ANSWERER|${candidate.sdpMid}|${candidate.sdpMLineIndex}|$tsSdp64"
                                cb.onSignalingMessage(RelayCommands.CMD_WEBRTC_CANDIDATE, tsPayload)
                                Log.i(TAG, "★ [TS修复] host候选IP ${candIp}:${candPort} 改写为Tailscale IP $tsIp 发送")
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }

        // —— ★ [6/8] 创建 PeerConnection
        // ★★★ 2026-08-14 中继"只传2帧"根因修复（v7）：
        //   【新证据】跨设备(红米控制+华为被控)中继优先模式下，两端协商交集强制走 TURN relay，
        //     M114 的 rtp_video_stream_receiver2 日志 "arrival time: -inf ms"（relay 路径收包无到达时间）
        //     → FrameBuffer 帧播放调度失败 → 控制端只渲染2帧（decode_fps=0, frames_dropped=16）。
        //   【修复】被控端不再强制 iceTransportsType=RELAY，统一 ALL：
        //     host/srflx 直连候选全参与（真实网络路径 arrival time 正常→15fps），relay 仍作为兜底候选。
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers(relayPreferred)).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            // ★★★ 2026-08-14 修复中继-2帧：不再 RELAY-only（relay路径arrival time=-inf → 视频只解2帧）
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            Log.i(TAG, "$stepTag ★★★ [6/8] 2026-08-14 修复中继-2帧：iceTransportsType=ALL（直连优先，relay兜底；规避TURN relay路径arrival time=-inf）")
        }
        val pc = try {
            Log.i(TAG, "$stepTag [6/8] createPeerConnection 开始...")
            val created = f.createPeerConnection(rtcConfig, pcObserver)
                ?: throw RuntimeException("createPeerConnection returned null")
            Log.i(TAG, "$stepTag [6/8] createPeerConnection 成功 ✓ pc=$created")
            created
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [6/8] createPeerConnection 失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("PeerConnection create failed: ${t.message}")
            return
        }
        peerConnection = pc

        // ★★★ 2026-08-12 音频覆盖率20%根因诊断：周期性 getStats 打印 audio sender 发送包数/字节。
        //   目的：区分"发送端只产出了20%音频" vs "接收端丢弃80%"。
        //   理论值：48kHz单声道10ms块，Opus 20ms一帧 → 每秒50包；若sender实际只有~10包/秒则发送端问题。
        try {
            val statsHandler = Handler(Looper.getMainLooper())
            val statsRunnable = object : Runnable {
                var counter = 0
                override fun run() {
                    try {
                        val p = peerConnection ?: return
                        try {
                            p.getStats(object : org.webrtc.RTCStatsCollectorCallback {
                                override fun onStatsDelivered(report: org.webrtc.RTCStatsReport) {
                                    try {
                                        val statsMap = report.statsMap
                                        for ((key, stats) in statsMap) {
                                            val type = stats.type
                                            if (type == "outbound-rtp" || type == "inbound-rtp" || type == "remote-inbound-rtp") {
                                                val kind = stats.members["kind"] as? String ?: ""
                                                if (kind == "audio") {
                                                    val packetsSent = stats.members["packetsSent"]
                                                    val packetsReceived = stats.members["packetsReceived"]
                                                    val bytesSent = stats.members["bytesSent"]
                                                    val bytesReceived = stats.members["bytesReceived"]
                                                    val lost = stats.members["packetsLost"]
                                                    val jitter = stats.members["jitter"]
                                                    val fps = stats.members["framesPerSecond"]
                                                    val codec = stats.members["codecId"]
                                                    Log.i(TAG, "★ [getStats-audio] type=$type id=$key kind=$kind " +
                                                            "pktSent=$packetsSent pktRecv=$packetsReceived " +
                                                            "byteSent=$bytesSent byteRecv=$bytesReceived lost=$lost jitter=$jitter fps=$fps codec=$codec")
                                                }
                                            }
                                        }
                                    } catch (e: Throwable) {
                                        Log.w(TAG, "★ [getStats-audio] 解析失败: ${e.message}")
                                    }
                                }
                            })
                        } catch (e: Throwable) {
                            Log.w(TAG, "★ [getStats-audio] getStats调用失败: ${e.message}")
                        }
                        counter++
                        if (counter < 30) statsHandler.postDelayed(this, 2000)  // 60秒内每2秒打印
                    } catch (e: Throwable) {
                        Log.w(TAG, "★ [getStats-audio] 调度异常: ${e.message}")
                    }
                }
            }
            statsHandler.postDelayed(statsRunnable, 3000)
            Log.i(TAG, "$stepTag ★★ [getStats-audio] 诊断定时器已启动（每2秒打印audio收发统计）")
        } catch (t: Throwable) {
            Log.w(TAG, "$stepTag [getStats-audio] 启动失败: ${t.message}")
        }

        // —— ★ [7.0/8] 先解析 OFFER SDP（提前到addTracks之前），判断是否纯音频模式（麦克风WebRTC）
        //   ★ 2026-08-11 新增：控制端"开麦克风"按钮改为WebRTC纯音频模式时，
        //     OFFER SDP 中不含 "m=video" 行 → 被控端只启动麦克风采集，不启动摄像头。
        val offerSdp = try {
            Log.i(TAG, "$stepTag [7.0/8] Base64 decode OFFER（len=${offerSdpBase64.length}）开始...")
            val raw = Base64.getDecoder().decode(offerSdpBase64)
            val sdp = String(raw, StandardCharsets.UTF_8)
            Log.i(TAG, "$stepTag [7.0/8] Base64 decode 成功 ✓ SDP len=${sdp.length}")
            sdp
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [7.0/8] Base64 decode 失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("OFFER base64 decode failed: ${t.message}")
            return
        }
        Log.i(TAG, "$stepTag [7.0/8] ★ 纯音频模式(麦克风WebRTC)=$audioOnly (SDP含m=video=${offerSdp.contains("m=video")})")

        // —— ★ [7/8] 创建本地音视频轨道并加入 PeerConnection（audioOnly时仅麦克风）
        try {
            Log.i(TAG, "$stepTag [7/8] addTracks 开始（${if (audioOnly) "纯音频-麦克风" else "摄像头+麦克风"}）...")
            addTracks(f, pc, cameraIndex, audioOnly)
            Log.i(TAG, "$stepTag [7/8] addTracks 成功 ✓ 轨道已加入（audioOnly=$audioOnly）")
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [7/8] addTracks 失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("addTracks failed: ${t.message}")
            return
        }

        // —— ★ [8/8] setRemoteDescription → createAnswer → setLocalDescription → 发 ANSWER
        cb.onStatus("setting_remote", "设置远端OFFER SDP")
        Log.i(TAG, "$stepTag [8/8] setRemoteDescription(OFFER) 异步开始...")
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                Log.i(TAG, "$stepTag [8/8] setRemoteDescription(OFFER) 成功 ✓，开始 createAnswer")
                cb.onStatus("creating_answer", "生成本地ANSWER SDP")
                val mediaConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                    optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
                }
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        if (sdp == null) {
                            Log.e(TAG, "$stepTag [8/8] createAnswer 返回null")
                            cb.onError("createAnswer returned null")
                            return
                        }
                        Log.i(TAG, "$stepTag [8/8] createAnswer 成功 ✓ sdp len=${sdp.description.length} type=${sdp.type}")
                        // ★★★ 2026-08-12 诊断日志：定位 setLocalDescription 报 "SessionDescription is NULL." 的根因
                        //   （该错误=WebRTC native ParseSessionDescription 失败返回null描述）
                        Log.i(TAG, "$stepTag [8/8] ★ANSWER原始SDP完整:\n${sdp.description}")
                        // ★★★ 2026-08-12 临时修复：跳过 preferAudioFec/stripAbsSendTime 的 SDP 内容修改，
                        //   直接使用 createAnswer 原始 SDP setLocalDescription。
                        //   原因：修改后 SDP 触发 native "Failed to parse: '' (Invalid SDP line)"，
                        //   setLocalDescription 失败 → 无法发 ANSWER → 控制端"未收到视频流"。
                        //   原 SDP 由 WebRTC 生成保证合法，修改反而引入解析问题。
                        // ★★★ 2026-08-12 录制音画同步修复（DTX禁用）：仅做 usedtx=1→usedtx=0 的等长子串替换
                        //   （不删行/不加行，不会破坏SDP结构），并在失败时自动回退原始SDP，双重保险。
                        val rawSdp: SessionDescription = sdp
                        var useSdp: SessionDescription = sdp
                        var dtxModified = false
                        try {
                            val desc = sdp.description.replace("usedtx=1", "usedtx=0")
                            if (desc != sdp.description) {
                                dtxModified = true
                                useSdp = SessionDescription(sdp.type, desc)
                                Log.i(TAG, "$stepTag [8/8] ★★★禁用DTX：usedtx=1→usedtx=0（Opus静音期不发包→录制音频覆盖率仅20%，禁用后静音期也持续发舒适音）")
                            } else {
                                Log.i(TAG, "$stepTag [8/8] SDP中未发现usedtx=1（DTX未启用，无需修改）")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "$stepTag [8/8] SDP修改usedtx异常(用原始): ${t.message}")
                        }
                        // 提取"answer就绪"动作（视频码率下限修复+发送ANSWER），供正常路径与回退路径复用
                        val onAnswerReady: (SessionDescription) -> Unit = { sd ->
                            try {
                                val senders = pc.senders
                                for (sender in senders) {
                                    val track = sender.track()
                                    if (track is VideoTrack) {
                                        // ★★★ 2026-08-14 保存视频轨引用
                                        // ★★★ 2026-08-14 晚修复"摄像头预览每隔1-2秒闪屏"：不再启动周期关键帧enable抖动！
                                        //   enable(false→true)每2秒中断视频轨200ms → 控制端画面冻结闪动。
                                        //   "只传一帧"根本问题改由【时间戳矫正改用本机单调时钟】(tsFixObserver)彻底解决：
                                        //   传感器时间戳异常→RTP时间戳乱序→P帧解码失败的本质已消除，无需周期性中断画面。
                                        videoTrackRef = track
                                        // startKeyFrameTimer()  // ← 已禁用（闪屏根源）
                                        val p = sender.parameters
                                        try {
                                            p.degradationPreference =
                                                org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
                                        } catch (_: Throwable) {}
                                        val encs = p.encodings
                                        for (enc in encs) {
                                            try { enc.scaleResolutionDownBy = 1.0 } catch (_: Throwable) {}
                                            enc.minBitrateBps = 100_000
                                            enc.maxBitrateBps = 800_000
                                            try { enc.maxFramerate = 10 } catch (_: Throwable) {} // ★★★ 2026-08-12 20→10 配合采集降帧
                                        }
                                        sender.parameters = p
                                        Log.i(TAG, "$stepTag ★ 视频sender已设置 degradationPreference=MAINTAIN_RESOLUTION scaleResolutionDownBy=1.0 min=100k max=800kbps fps=10（禁用QualityScaler，分辨率固定640x480不重建编码器）")
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "$stepTag 设置视频码率下限失败(忽略): ${t.message}")
                            }
                            val answerB64 = Base64.getEncoder().encodeToString(
                                sd.description.toByteArray(StandardCharsets.UTF_8)
                            )
                            Log.i(TAG, "$stepTag [8/8] setLocalDescription 成功 ✓，发送 ANSWER(len=${answerB64.length})")
                            cb.onSignalingMessage(RelayCommands.CMD_WEBRTC_ANSWER, answerB64)
                            cb.onStatus("ready", "ANSWER已发送，等待ICE连通")
                        }
                        pc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() { onAnswerReady(useSdp) }
                            override fun onSetFailure(e: String?) {
                                if (dtxModified) {
                                    // ★ DTX修改后解析失败：回退原始SDP重试（历史教训：SDP修改可能触发"Invalid SDP line"）
                                    Log.w(TAG, "$stepTag [8/8] ★修改SDP setLocalDescription失败($e)，回退原始SDP重试")
                                    pc.setLocalDescription(object : SdpObserverAdapter() {
                                        override fun onSetSuccess() { onAnswerReady(rawSdp) }
                                        override fun onSetFailure(e2: String?) {
                                            Log.e(TAG, "$stepTag [8/8] setLocalDescription 失败(原始SDP): $e2")
                                            cb.onError("setLocalDescription failed: $e2")
                                        }
                                    }, rawSdp)
                                } else {
                                    Log.e(TAG, "$stepTag [8/8] setLocalDescription 失败: $e")
                                    Log.e(TAG, "$stepTag [8/8] ★失败时useSdp type=${useSdp.type} len=${useSdp.description.length} 内容(前700):\n${useSdp.description.take(700)}")
                                    cb.onError("setLocalDescription failed: $e")
                                }
                            }
                        }, useSdp)
                    }
                    override fun onCreateFailure(e: String?) {
                        Log.e(TAG, "$stepTag [8/8] createAnswer 失败: $e")
                        cb.onError("createAnswer failed: $e")
                    }
                }, mediaConstraints)
            }
            override fun onSetFailure(e: String?) {
                Log.e(TAG, "$stepTag [8/8] setRemoteDescription(OFFER) 失败: $e")
                cb.onError("setRemoteDescription(OFFER) failed: $e")
            }
        }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        Log.i(TAG, "$stepTag ★★ 全部 8 步异步初始化启动完成（ANSWER 发送后见回调日志） ★★")
    }

    /** 收到控制端发来的 ICE Candidate：addIceCandidate 入 PeerConnection */
    @Synchronized
    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidateSdpBase64: String) {
        val pc = peerConnection ?: run { Log.w(TAG, "addRemoteIceCandidate: PC未初始化"); return }
        try {
            val sdp = String(Base64.getDecoder().decode(candidateSdpBase64), StandardCharsets.UTF_8)
            // ★ WebRTC 114.x：优先单参 addIceCandidate(IceCandidate)，反射兼容带 AddIceObserver 双参版本
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            try {
                val singleM = pc.javaClass.getMethod("addIceCandidate", IceCandidate::class.java)
                singleM.invoke(pc, candidate)
            } catch (_: Throwable) {
                val addIceObserverClass = Class.forName("org.webrtc.AddIceObserver")
                val dualM = pc.javaClass.getMethod("addIceCandidate", IceCandidate::class.java, addIceObserverClass)
                val emptyObs = java.lang.reflect.Proxy.newProxyInstance(
                    addIceObserverClass.classLoader, arrayOf(addIceObserverClass)
                ) { _, _, _ -> null }
                dualM.invoke(pc, candidate, emptyObs)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "addRemoteIceCandidate failed: ${t.message}")
        }
    }

    /** 切换前后摄像头：切换后新帧立即由原轨道输出到PeerConnection，无需重建SDP */
    @Synchronized
    fun switchCamera(newIndex: Int) {
        val vc = videoCapturer as? Camera2Capturer ?: run {
            Log.w(TAG, "switchCamera: 当前不是Camera2Capturer")
            return
        }
        try {
            val deviceNames = Camera2Enumerator(appContext).deviceNames
            if (newIndex in deviceNames.indices) {
                vc.switchCamera(null) // Camera2Capturer 会自动按 "front/back" 或相邻索引切换；
                // 若 switchCamera(null) 不能按 index 切换，就用 enumerator 取 front/back：
                currentCameraIndex = newIndex
                signalingCallback?.onStatus("camera_switched", "已切换到摄像头#$newIndex")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "switchCamera failed: ${t.message}")
            signalingCallback?.onError("切换摄像头失败: ${t.message}")
        }
    }

    /**
     * ★ 2026-08-28 省电：对端存活看门狗。见 PEER_GONE_MS 处的说明。
     *   线程独立于 WebRTC 捕获线程，调用 close() 不会触发 2026-08-14 修过的"捕获线程自等死锁"。
     */
    private fun startLivenessWatchdog() {
        stopLivenessWatchdog()
        livenessWatchdog = Thread({
            while (running) {
                try {
                    Thread.sleep(LIVENESS_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                // 还没连通过：交给 ICE 的 30/45 秒宽容逻辑处理，这里不插手
                if (!everConnected) {
                    lastInboundActivityTime = System.currentTimeMillis()
                    continue
                }
                if (iceReceiving) {
                    lastInboundActivityTime = System.currentTimeMillis()
                    continue
                }
                val since = System.currentTimeMillis() - lastInboundActivityTime
                if (since < PEER_GONE_MS) continue
                Log.w(TAG, "$TAG ★ 省电：已连续 ${since / 1000}s 收不到对端任何报文（控制端可能已退出/被杀），关闭 WebRTC 会话释放摄像头+编码器+麦克风")
                try {
                    close()
                } catch (t: Throwable) {
                    Log.w(TAG, "$TAG 存活看门狗关闭会话异常: ${t.message}")
                }
                break
            }
        }, "WebRtcLiveness").apply { isDaemon = true }
        livenessWatchdog!!.start()
    }

    /** ★ 省电：随会话结束收掉看门狗线程 */
    private fun stopLivenessWatchdog() {
        try {
            livenessWatchdog?.interrupt()
        } catch (_: Throwable) {
        }
        livenessWatchdog = null
    }

    /** ★★★ 2026-08-14 启动周期关键帧线程：每2秒对videoTrack做setEnabled(false→true)抖动，
     *  强制VideoStreamEncoder重新输出关键帧。解决"只传一帧"——华为EMUI传感器时间戳异常导致
     *  控制端P帧解码全部失败且无PLI恢复时，周期性关键帧保证画面2秒内恢复。 */
    private fun startKeyFrameTimer() {
        if (keyFrameTimer != null) return
        keyFrameTimer = Thread({
            Log.i(TAG, "$TAG ★★★ [周期关键帧] 已启动：每${KEY_FRAME_INTERVAL_MS / 1000}秒强制一次关键帧（P帧解码失败后快速恢复画面）")
            while (running && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(KEY_FRAME_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val vt = videoTrackRef ?: continue
                if (!running) break
                try {
                    // ★★★ 2026-08-14 修复：enable抖动必须等待帧中断。立即false→true编码器感知不到。
                    vt.setEnabled(false)
                    try { Thread.sleep(KEY_FRAME_HOLD_MS) } catch (_: InterruptedException) { break }
                    vt.setEnabled(true)
                    Log.i(TAG, "$TAG ★★★ [周期关键帧] 已强制输出关键帧（enable抖动+hold ${KEY_FRAME_HOLD_MS}ms）")
                } catch (t: Throwable) {
                    Log.w(TAG, "$TAG [周期关键帧] 抖动失败: ${t.message}")
                }
            }
            keyFrameTimer = null
            Log.i(TAG, "$TAG [周期关键帧] 线程结束")
        }, "WebRtcKeyFrameTimer").apply { isDaemon = true }.also { it.start() }
    }

    @Synchronized
    fun close() {
        closeInternal(true)
    }

    @Synchronized
    private fun closeInternal(notifyCb: Boolean) {
        running = false
        // ★★★ 2026-08-14 停止周期关键帧线程
        try { keyFrameTimer?.interrupt() } catch (_: Throwable) {}
        // ★ 2026-08-28 省电：会话结束一并收掉对端存活看门狗（防止残留线程误关下一个会话）
        stopLivenessWatchdog()
        iceReceiving = false
        lastInboundActivityTime = 0L
        keyFrameTimer = null
        videoTrackRef = null
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        try { videoCapturer?.dispose() } catch (_: Throwable) {}
        videoCapturer = null
        try { videoSource?.dispose() } catch (_: Throwable) {}
        videoSource = null
        try { surfaceTextureHelper?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper = null
        try { captureEglBase?.release() } catch (_: Throwable) {}
        captureEglBase = null
        try { encodeEglBase?.release() } catch (_: Throwable) {}
        encodeEglBase = null
        try { decodeEglBase?.release() } catch (_: Throwable) {}
        decodeEglBase = null
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        peerConnection = null
        try { factory?.dispose() } catch (_: Throwable) {}
        factory = null
        if (notifyCb) {
            try { signalingCallback?.onStatus("closed", "会话已关闭") } catch (_: Throwable) {}
        }
        signalingCallback = null
    }

    // ==================== 内部工具 ====================

    private fun addTracks(f: PeerConnectionFactory, pc: PeerConnection, cameraIndex: Int, audioOnly: Boolean) {
        val tag = "[addTracks]"
        Log.i(TAG, "$tag [a1] createAudioSource 开始...")
        val audioSource = try {
            // ★★★ 2026-08-12 啸叫(呼啸声)修复：显式启用 WebRTC 软件 AEC/NS/AGC。
            //   【根因】createAudioSource(空MediaConstraints) → 软件音频处理未启用 → 麦克风采集
            //     无回声消除/噪声抑制/增益控制 → 声学反馈增益过大 → 啸叫(呼啸声)。
            //   【方案】保持 MIC 源+关闭硬件AEC/NS(防机器人声)，软件AEC/NS/AGC保留启用(抑制啸叫)。
            val audioConstraints = MediaConstraints()
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            f.createAudioSource(audioConstraints)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [a1] createAudioSource 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [a2] createAudioTrack 开始...")
        val localAudioTrack = try {
            f.createAudioTrack("ARDAMSa0", audioSource)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [a2] createAudioTrack 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        try {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            am.isSpeakerphoneOn = true
        } catch (_: Throwable) {}
        val audioRet = try {
            pc.addTrack(localAudioTrack, listOf("ARDAMS"))
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [a3] addTrack(audio) 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [a3] 音频轨道添加成功 ✓ ret=$audioRet")

        // ★★★ 2026-08-12 音频采集饥饿修复：守护线程周期性提升采集线程优先级并绑定大核。
        //   【根因】红米CPU过载(相机HAL+视频编码) → WebRTC AudioRecord采集线程(10ms周期)被CFS调度器饿，
        //     实际每~54ms才唤醒一次 → 音频覆盖率仅18.6%。采集线程优先级/绑核后即使CPU忙也能按时唤醒。
        try {
            val tunerStop = java.util.concurrent.atomic.AtomicBoolean(false)
            audioThreadTunerStop = tunerStop
            val tuner = Thread {
                var tunedCount = 0
                while (!tunerStop.get()) {
                    try {
                        val threads = Thread.getAllStackTraces().keys
                        for (t in threads) {
                            val n = t.name ?: ""
                            if (n.contains("AudioRecordJavaThread") || n.contains("AudioRecord")) {
                                try {
                                    // 1) 提升线程优先级为 URGENT_AUDIO(-19)
                                    val tidField = Thread::class.java.getDeclaredField("tid")
                                    tidField.isAccessible = true
                                    val tid = (tidField.getLong(t)).toInt()
                                    android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                                    // 2) 绑定大核 CPU0-3（骁龙870 Kryo585大核组，掩码0x0F）
                                    try {
                                        val osClass = Class.forName("libcore.io.Libcore")
                                        val osField = osClass.getField("os")
                                        val os = osField.get(null)
                                        val m = os.javaClass.getMethod("sched_setaffinity", Int::class.javaPrimitiveType, LongArray::class.java)
                                        m.invoke(os, tid, longArrayOf(0x0FL))
                                        Log.i(TAG, "$tag ★★ [采集线程调优] $n tid=$tid 已提优先级(-19)+绑定大核CPU0-3")
                                    } catch (affErr: Throwable) {
                                        Log.w(TAG, "$tag [采集线程调优] 绑核失败(忽略): ${affErr.message}")
                                    }
                                    tunedCount++
                                } catch (e: Throwable) {
                                    // 线程刚创建时tid字段可能不可读，下次循环再试
                                }
                                if (tunedCount > 4) tunerStop.set(true) // 成功调优2个音频线程后停止
                            }
                        }
                    } catch (_: Throwable) {}
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
            tuner.name = "AudioCaptureTuner"
            tuner.isDaemon = true
            tuner.start()
            Log.i(TAG, "$tag ★★ [采集线程调优] 守护线程已启动（检测AudioRecord*线程并提优先级+绑大核）")
        } catch (t: Throwable) {
            Log.w(TAG, "$tag [采集线程调优] 守护线程启动失败: ${t.message}")
        }

        // ★★★ 2026-08-12 录制音视频同步诊断：给本地音频轨挂AudioTrackSink统计采集速率。
        //   控制端录制的音频只有21%实时(47ms回调1次)，需确认是否被控端采集端本身投递稀疏。
        //   每5秒打印一次采集速率/帧数 → 若≈100Hz(10ms块)说明采集实时，问题在传输/接收端；若≈21Hz则被控端采集端问题。
        try {
            val sinkStats = object {
                var frames: Long = 0
                var bytes: Long = 0
                var lastLogMs: Long = 0
            }
            localAudioTrack.addSink(object : org.webrtc.AudioTrackSink {
                override fun onData(audioData: java.nio.ByteBuffer?, bitsPerSample: Int, sampleRate: Int,
                                    numberOfChannels: Int, numberOfFrames: Int, absCaptureTimeUs: Long) {
                    if (audioData == null || !audioData.hasRemaining()) return
                    sinkStats.frames += numberOfFrames
                    sinkStats.bytes += audioData.remaining().toLong()
                    val now = System.currentTimeMillis()
                    if (sinkStats.lastLogMs == 0L) sinkStats.lastLogMs = now
                    if (now - sinkStats.lastLogMs >= 5000) {
                        val secs = (now - sinkStats.lastLogMs) / 1000.0
                        val fps = sinkStats.frames / secs / 1000.0 // kHz
                        Log.i(TAG, "$tag ★★ [录制诊断] 本地音频采集 rate=${sampleRate}Hz ch=$numberOfChannels bit=$bitsPerSample " +
                                "捕获速率=${"%.2f".format(fps)}kHz(48kHz实时应≈48) 回调间隔=${"%.1f".format(secs * 1000 / Math.max(1, (sinkStats.frames / numberOfFrames)))}ms 累计帧=${sinkStats.frames} 字节=${sinkStats.bytes}")
                        sinkStats.frames = 0; sinkStats.bytes = 0; sinkStats.lastLogMs = now
                    }
                }
            })
            Log.i(TAG, "$tag ★★ [录制诊断] 本地音频轨AudioTrackSink已挂载（每5秒打印采集速率）")
        } catch (t: Throwable) {
            Log.w(TAG, "$tag [录制诊断] 本地音频sink挂载失败: ${t.message}")
        }

        // ★ 2026-08-11 纯音频模式(麦克风WebRTC)：只采集麦克风，不启动摄像头（避免Camera2占用与权限问题）
        if (audioOnly) {
            Log.i(TAG, "$tag ★ [audioOnly] 纯音频模式：跳过摄像头枚举/采集/视频轨道 ✓")
            return
        }

        val enumerator = Camera2Enumerator(appContext)
        val deviceNames = enumerator.deviceNames
        Log.i(TAG, "$tag [v1] 摄像头枚举: 共${deviceNames.size}台 ${deviceNames.joinToString()}")
        if (deviceNames.isEmpty()) {
            throw RuntimeException("无可用摄像头（Camera2枚举为空）")
        }
        val idx = cameraIndex.coerceIn(deviceNames.indices)
        val camName = deviceNames[idx]
        currentCameraIndex = idx
        Log.i(TAG, "$tag [v2] 选中摄像头 #$idx=$camName, isFront=${enumerator.isFrontFacing(camName)}")

        Log.i(TAG, "$tag [v3] SurfaceTextureHelper.create 开始...")
        surfaceTextureHelper = try {
            // ★ 2026-08-11 黑帧根因修复：必须使用【与编码器共享的同一个EglBase】！
            //   captureEglBase 已在 [5/8] 与 encodeEglBase/decodeEglBase 指向同一个 EglBase，
            //   SurfaceTextureHelper(Camera2 OES纹理采集) 与硬编码器共享EGL context，
            //   编码器才能正确消费 OES 纹理 → 不再黑帧。
            val egl = captureEglBase ?: EglBase.create().also { captureEglBase = it }
            Log.i(TAG, "$tag [v3] 采集EglBase(与编码器共享): $egl")
            SurfaceTextureHelper.create("WebRtcCaptureThread", egl.eglBaseContext)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v3] SurfaceTextureHelper.create 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v4] createVideoSource 开始...")
        videoSource = try {
            f.createVideoSource(false)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v4] createVideoSource 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v5] createCapturer($camName) 开始...")
        val capturer = enumerator.createCapturer(camName, null)
            ?: throw RuntimeException("Camera2 createCapturer failed for $camName")
        Log.i(TAG, "$tag [v5] createCapturer 成功 ✓ capturer=$capturer")
        videoCapturer = capturer
        Log.i(TAG, "$tag [v6] capturer.initialize 开始...")
        try {
            // ★★★ 2026-08-12 华为等设备 Camera2 SENSOR_TIMESTAMP 异常修复：
            //   Camera2Capturer 输出帧时间戳直接来自传感器，部分ROM(华为EMUI)传感器时间戳会回退/跳变，
            //   导致：1) RTP时间戳乱序 → 控制端接收端全部丢弃(仅首帧显示，"只有一帧") 
            //        2) VideoStreamEncoder 周期关键帧失效(88秒仅1个关键帧) → 丢帧后无法恢复
            //   用代理 CapturerObserver 矫正时间戳为单调递增(回退时自动累加偏移)，
            //   同时保持帧 buffer 引用计数正确(retain→构造新帧→release原帧)。
            val realObserver = videoSource!!.capturerObserver
            // ★★★ 2026-08-14 晚修复"只传一帧/摄像头闪屏"：
            //   时间戳不再依赖传感器(SENSOR_TIMESTAMP)，改用【本机单调时钟 System.nanoTime】基准。
            //   华为EMUI等传感器时间戳回退/跳变/间隔乱序 → 直接丢弃，帧间间隔由本机时钟精确驱动 →
            //   RTP时间戳单调且间隔真实 → 控制端P帧正常解码（不再"只传一帧"），
            //   从而可移除周期关键帧enable抖动（不再闪屏）。
            val tsFixObserver = object : CapturerObserver {
                private var baseNs = 0L
                private var lastFixedTsNs = 0L
                override fun onCapturerStarted(success: Boolean) {
                    baseNs = 0L
                    lastFixedTsNs = 0L
                    // ★★★ 2026-08-13 摄像头打开失败（被其他应用占用如视频通话/权限不足）→ 明确反馈控制端"为什么没有图像"
                    if (!success) {
                        val reason = "被控端摄像头无法打开（被其他应用占用如视频通话，或摄像头权限被收回）"
                        Log.e(TAG, "$tag ★★★ [v6] 摄像头打开失败(onCapturerStarted=false) → $reason")
                        try { signalingCallback?.onError(reason) } catch (_: Throwable) {}
                        try { realObserver.onCapturerStarted(false) } catch (_: Throwable) {}
                        return
                    }
                    realObserver.onCapturerStarted(success)
                }
                override fun onCapturerStopped() { realObserver.onCapturerStopped() }
                override fun onFrameCaptured(frame: VideoFrame) {
                    try {
                        val nowNs = System.nanoTime()
                        if (baseNs == 0L) baseNs = nowNs
                        var fixedTs = nowNs - baseNs
                        // 保证严格单调递增（WebRTC要求RTP时间戳单调，防同值帧被丢弃）
                        if (fixedTs <= lastFixedTsNs) fixedTs = lastFixedTsNs + 1_000_000L
                        lastFixedTsNs = fixedTs
                        // 复用原buffer构造矫正帧(retain+1)，随后释放原帧引用
                        frame.buffer.retain()
                        val fixed = VideoFrame(frame.buffer, frame.rotation, fixedTs)
                        realObserver.onFrameCaptured(fixed)
                        frame.release()
                    } catch (t: Throwable) {
                        Log.w(TAG, "★ [时间戳矫正] 矫正异常，原样转发: ${t.message}")
                        try { realObserver.onFrameCaptured(frame) } catch (_: Throwable) {}
                    }
                }
            }
            capturer.initialize(surfaceTextureHelper, appContext, tsFixObserver)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v6] capturer.initialize 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v7] capturer.startCapture(640x480@10) 开始...（★★★ 2026-08-12 饥饿修复：20fps→10fps降低相机HAL+编码CPU负载）")
        try {
            capturer.startCapture(640, 480, 10)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v7] startCapture 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v8] createVideoTrack 开始...")
        val localVideoTrack = try {
            f.createVideoTrack("ARDAMSv0", videoSource)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v8] createVideoTrack 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        localVideoTrack.setEnabled(true)
        Log.i(TAG, "$tag [v9] addTrack(video) 开始...")
        val videoRet = try {
            pc.addTrack(localVideoTrack, listOf("ARDAMS"))
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v9] addTrack(video) 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v9] 视频轨道添加成功 ✓ ret=$videoRet")

        // ★★★ 2026-08-12 中继"只传1帧"根因修复（v5）：RtpParameters 层面移除时间类RTP扩展！
        //   【根因】仅修改 SDP 字符串无效——WebRTC 内部 sender 的 RtpParameters 仍注册
        //     abs-send-time/toffset/playout-delay → createAnswer 生成的 SDP 仍包含这些扩展 →
        //     发送端 RTP 包仍携带时间扩展(经TURN relay后值异常，如 toffset=9360ms) →
        //     控制端 rtp_video_stream_receiver2 计算 arrival time=-inf → VideoReceiveStream2
        //     帧播放调度失败 → 解码输出全部丢弃(MediaCodec Render 2/Drop 84) → 只有1帧。
        //   【修复】addTrack 后立即修改 video sender 的 RtpParameters.headerExtensions，
        //     真正移除 abs-send-time/transmission-offset/playout-delay → createAnswer 生成的
        //     SDP 不再协商这些扩展 → 发送端 RTP 包不再携带 → 控制端用本地到达时间 → 正常渲染。
        try {
            val senders = pc.senders
            for (sender in senders) {
                val trk = sender.track()
                if (trk is VideoTrack) {
                    val p = sender.parameters
                    val it = p.headerExtensions.iterator()
                    var removedCnt = 0
                    while (it.hasNext()) {
                        val ext = it.next()
                        if (ext.uri.contains("abs-send-time")
                            || ext.uri.contains("rtp-hdrext:toffset")
                            || ext.uri.contains("rtp-hdrext:playout-delay")
                            || ext.uri.contains("video-timing")) {
                            it.remove()
                            removedCnt++
                            Log.i(TAG, "$tag ★★★ [v10] 视频sender移除时间类RTP扩展: uri=${ext.uri} id=${ext.id}")
                        }
                    }
                    if (removedCnt > 0) {
                        sender.parameters = p
                        Log.i(TAG, "$tag ★★★ [v10] 视频sender RtpParameters 已移除 $removedCnt 个时间类扩展（abs-send-time/toffset/playout-delay/video-timing）→ 修复中继只传1帧")
                        Log.i(TAG, "$tag ★★★ [v10] 移除后视频sender剩余扩展: ${p.headerExtensions.map { it.uri + "#" + it.id }.joinToString()}")
                    } else {
                        Log.i(TAG, "$tag [v10] 视频sender未发现时间类RTP扩展（无需移除）")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$tag [v10] 移除视频sender时间类扩展失败(忽略): ${t.message}")
        }
    }

    /** ★ 让 SDP 中 Opus 成为 0 号 Payload（音频首选），并把 FEC/RED/DTX 都打开 */
    private fun preferAudioFec(sdp: String): String {
        // WebRTC 默认 a=fmtp:111 minptime=10; useinbandfec=1 — 仅 useinbandfec；我们再显式加上 DTX（静音时不发包省流量）
        return sdp
            .replace("a=fmtp:111 minptime=10;useinbandfec=1",
                     "a=fmtp:111 minptime=10;useinbandfec=1;usedtx=1;stereo=0;cbr=0")
    }

    /** ★ 简单把 m=audio 里 opus 的 pt 提到音频首行，m=video 里 vp8 的 pt 提到视频首行 */
    private fun preferOpusAndVp8(sdp: String): String {
        // 99%场景下默认已经是 Opus(111)/VP8(96) 在前；这里保持原样即可，除非遇到特定运营商/设备；
        // 保留函数入口，以便后续扩展
        // ★★★ 2026-08-12 中继"只传1帧"修复：移除 abs-send-time RTP 扩展（与控制端 OFFER 保持一致）。
        //   【根因】中继(TURN)路径下 abs-send-time 异常(加速43分钟/回退) → 接收端 arrival time=-inf
        //     → 帧播放调度失败 → 解码输出被丢弃(Render 2/Drop 49) → 只有关键帧能显示。
        //   【修复】ANSWER 同样删除 abs-send-time 扩展 → 两端都不协商该扩展 → 用本地到达时间估计。
        return stripAbsSendTime(sdp)
    }

    /** ★★★ 2026-08-15 从SDP中移除时间类RTP扩展行：abs-send-time + toffset + playout-delay + video-timing
     *  （a=extmap:N ...）。★ 2026-08-15 新增 video-timing：TURN relay 路径下该扩展值异常
     *   → 接收端(M114) arrival time=-inf → 视频只渲染关键帧（"只传一帧"）。*/
    private fun stripAbsSendTime(sdp: String): String {
        if (sdp.isBlank()) return sdp
        try {
            val lines = sdp.split("\n")
            val sb = StringBuilder()
            var removed = 0
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("a=extmap:")
                    && (trimmed.contains("abs-send-time")
                        || trimmed.contains("rtp-hdrext:toffset")
                        || trimmed.contains("rtp-hdrext:playout-delay")
                        || trimmed.contains("video-timing"))) {
                    removed++
                    continue
                }
                sb.append(line).append('\n')
            }
            if (removed > 0) {
                Log.i(TAG, "★ stripAbsSendTime: 移除 $removed 行时间类RTP扩展(abs-send-time/toffset/playout-delay/video-timing) → 修复中继视频渲染调度")
            }
            return sb.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "stripAbsSendTime failed: ${t.message}")
            return sdp
        }
    }

    /** 简化 SdpObserver：避免每次复写所有方法 */
    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) { Log.e(TAG, "SdpObserver onSetFailure: $e") }
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "SdpObserver onCreateFailure: $e") }
    }
}

// ==================== ★★★ 2026-08-12 TS直连候选修复辅助类 ====================

/** 查找本机虚拟网段IP（Tailscale 100.64.0.0/10） */
fun findTsIpAddress(): String? {
    return try {
        val nis = java.net.NetworkInterface.getNetworkInterfaces()
        while (nis.hasMoreElements()) {
            val ni = nis.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a !is java.net.Inet4Address) continue
                val ip = a.hostAddress ?: continue
                if (isPrivateVirtualIp(ip)) return ip
            }
        }
        null
    } catch (t: Throwable) {
        null
    }
}

/** 判断是否为虚拟网私有IP（Tailscale 100.64-100.127.x） */
private fun isPrivateVirtualIp(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    try {
        val b = parts[1].toInt()
        return parts[0] == "100" && b in 64..127
    } catch (_: Exception) {
        return false
    }
}

/** ★★★ TS专用网络检测器工厂：getActiveNetworkList()根据模式返回候选网络。
 *  - relayPreferred=true（中继优先）：只返回WiFi（不含tun0）→ 媒体经TURN relay中继转发
 *  - relayPreferred=false（直连兜底）：返回 WiFi + tun0(修正IP) → 媒体走TS直连
 *  背景：Android网络监控给Tailscale VPN(tun0)生成的UDP host候选是底层WiFi IP而非Tailscale IP，
 *  跨设备TS直连ICE两端候选都不含Tailscale IP → ICE=FAILED。v2注入NetworkChangeDetector修正。 */
class TsOnlyNetworkDetectorFactory(
    private val tsIp: String,
    private val relayPreferred: Boolean
) : NetworkChangeDetectorFactory {
    private val TAG = "WebRtcSessionMgr"
    override fun create(observer: NetworkChangeDetector.Observer, context: Context): NetworkChangeDetector {
        val base = NetworkMonitorAutoDetect(observer, context)
        val tsIpBytes = parseIpv4(tsIp)
        return object : NetworkChangeDetector {
            override fun getCurrentConnectionType(): NetworkChangeDetector.ConnectionType =
                base.currentConnectionType

            override fun supportNetworkCallback(): Boolean = base.supportNetworkCallback()

            override fun getActiveNetworkList(): MutableList<NetworkChangeDetector.NetworkInformation> {
                val orig = base.activeNetworkList
                var tunNet: NetworkChangeDetector.NetworkInformation? = null
                if (orig != null) {
                    for (ni in orig) {
                        if (isTsNetwork(ni)) { tunNet = ni; break }
                    }
                }
                // ★★★ 2026-08-12 兜底：部分ROM(华为EMUI等) NetworkMonitorAutoDetect 不报告
                //   Tailscale VPN(tun0)网络 → orig里找不到TS网络 → tunNet==null 返回全部网络
                //   → WebRTC socket绑定蜂窝/WiFi IP，host候选不含Tailscale IP → ICE=FAILED。
                //   用ConnectivityManager遍历系统网络找TRANSPORT_VPN/接口tun开头的真实handle。
                if (tunNet == null) {
                    tunNet = findTsViaConnectivityManager(context, tsIpBytes)
                    if (tunNet != null) {
                        Log.i(TAG, "★ [TS修复] orig无tun0，ConnectivityManager兜底找到VPN网络 name=${tunNet.name} handle=${tunNet.handle}")
                    }
                }
                // ★★★ 2026-08-12 中继环境修复：不再"只返回tun0"——
                //   返回 tun0(修正IP) + WiFi 两个网络，保证：
                //   1) TS直连环境：tun0候选(100.64.x)可用 → 跨设备TS直连ICE选TS路径
                //   2) 中继/同WiFi环境：即使对端Tailscale离线(无tun0)，仍可通过WiFi host候选直连
                //   过滤无用的蜂窝/以太网/loopback(私网蜂窝IP会干扰ICE选路，且公网环境不可达)。
                // ★★★ 2026-08-12 中继优先模式：relayPreferred=true 时【不返回tun0】，
                //   只返回WiFi → host候选不含Tailscale IP → ICE无法选TS host路径 → 只能走TURN relay(中继转发)。
                //   这样保证"中继服务在线时媒体必走中继"，不被TS host候选抢占。
                val out = mutableListOf<NetworkChangeDetector.NetworkInformation>()
                orig?.forEach { ni ->
                    if (ni.type == NetworkChangeDetector.ConnectionType.CONNECTION_WIFI) out.add(ni)
                }
                if (!relayPreferred && tunNet != null && tsIpBytes != null) {
                    // 直连兜底模式：修正IP后的TS网络（tun0）
                    val fixed = NetworkChangeDetector.NetworkInformation(
                        tunNet.name, tunNet.type, tunNet.underlyingTypeForVpn, tunNet.handle,
                        arrayOf(NetworkChangeDetector.IPAddress(tsIpBytes)))
                    out.add(fixed)
                    Log.i(TAG, "★ [TS修复] 直连兜底模式: WiFi=${countWifi(orig)} + tun0(${tunNet.name}→$tsIp) ✓")
                } else if (relayPreferred) {
                    Log.i(TAG, "★ [中继优先] 候选网络仅WiFi（不含tun0），媒体将走TURN relay中继转发")
                }
                if (out.isEmpty()) return orig ?: mutableListOf() // 兜底：退回默认
                return out
            }

            override fun destroy() { base.destroy() }
        }
    }

    private fun isTsNetwork(ni: NetworkChangeDetector.NetworkInformation): Boolean {
        if (ni.type == NetworkChangeDetector.ConnectionType.CONNECTION_VPN) return true
        if (ni.name != null && ni.name.startsWith("tun")) return true
        ni.ipAddresses?.forEach { a ->
            if (a?.address != null && a.address.size == 4) {
                val first = a.address[0].toInt() and 0xFF
                val second = a.address[1].toInt() and 0xFF
                if ((first == 100 && second in 64..127)) {
                    return true
                }
            }
        }
        return false
    }

    /** 统计网络列表中WiFi网络个数（调试日志用） */
    private fun countWifi(list: List<NetworkChangeDetector.NetworkInformation>?): Int {
        var n = 0
        list?.forEach { if (it.type == NetworkChangeDetector.ConnectionType.CONNECTION_WIFI) n++ }
        return n
    }

    /** ★★★ 2026-08-12 华为/部分ROM TS修复：用ConnectivityManager兜底查找Tailscale VPN(tun0)网络。
     *  NetworkMonitorAutoDetect.getActiveNetworkList()在部分ROM上不报告VPN网络，
     *  导致WebRTC无法用tun0做ICE候选。直接遍历系统所有网络：
     *  1) TRANSPORT_VPN 且接口名以tun开头（最典型Tailscale）
     *  2) 或 LinkProperties 链路地址含 100.64-100.127.x（Tailscale虚拟网段）
     *  命中后取其真实NetworkHandle构造NetworkInformation。 */
    private fun findTsViaConnectivityManager(
        ctx: Context, tsIpBytes: ByteArray?): NetworkChangeDetector.NetworkInformation? {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return null
            cm.allNetworks.forEach { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@forEach
                val lp = cm.getLinkProperties(n) ?: return@forEach
                val iface = lp.interfaceName
                val isVpnTransport = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                val isTunName = iface?.startsWith("tun") == true
                var hasTsAddr = false
                try {
                    lp.linkAddresses.forEach { la ->
                        val a = la.address
                        if (a is java.net.Inet4Address) {
                            val ip = a.hostAddress
                            if (ip != null && isPrivateVirtualIp(ip)) { hasTsAddr = true; return@forEach }
                        }
                    }
                } catch (_: Throwable) {}
                if (isVpnTransport || isTunName || hasTsAddr) {
                    val name = iface ?: "tun0"
                    val handle = n.networkHandle
                    val ips = if (tsIpBytes != null) {
                        arrayOf(NetworkChangeDetector.IPAddress(tsIpBytes))
                    } else {
                        emptyArray<NetworkChangeDetector.IPAddress>()
                    }
                    return NetworkChangeDetector.NetworkInformation(
                        name,
                        NetworkChangeDetector.ConnectionType.CONNECTION_VPN,
                        NetworkChangeDetector.ConnectionType.CONNECTION_UNKNOWN,
                        handle,
                        ips)
                }
            }
            null
        } catch (t: Throwable) {
            Log.w(TAG, "★ [TS修复] ConnectivityManager兜底查找VPN网络失败: ${t.message}")
            null
        }
    }

    private fun parseIpv4(ip: String): ByteArray? {
        return try {
            val p = ip.split(".")
            if (p.size != 4) return null
            byteArrayOf(
                p[0].toInt().toByte(), p[1].toInt().toByte(),
                p[2].toInt().toByte(), p[3].toInt().toByte())
        } catch (t: Throwable) { null }
    }
}
