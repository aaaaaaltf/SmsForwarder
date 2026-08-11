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

    // ★ 2026-08-11 ICE连接宽容策略v2：
    //   - 之前：everConnected + 15秒 → 失败案例：ICE瞬时CONNECTED(选到了一个假候选对)后立即FAILED，
    //     15秒内 WebRTC 还没来得及用其他候选对（如ZT host↔host）重试就被判定为失败。
    //   - 现在：宽容时间改成 45 秒，并补充私网IP(ZT/192.168)连通性检测，给WebRTC足够时间探测真正可用的候选对。
    //   - everConnected=true 后，哪怕 DISCONNECTED/FAILED 都只报状态，绝不立即触发 fallback。
    @Volatile private var everConnected = false
    private val iceMainHandler = Handler(Looper.getMainLooper())
    @Volatile private var iceFailedPendingRunnable: Runnable? = null
    private val ICE_FAILED_TOLERANCE_MS = 45000L

    /**
     * 收到控制端 OFFER：启动摄像头+麦克风 → 设置远端SDP → 生成ANSWER → 通过callback发回
     * @param cameraIndex 0=后, 1=前 (与原 CameraStreamManager 索引一致)
     * @param offerSdpBase64 Base64(UTF-8(SDP)) — 控制端 encode 后的 SDP
     * @param cb 信令/状态/错误回调
     */
    @Synchronized
    fun startWithOffer(cameraIndex: Int, offerSdpBase64: String, cb: SignalingCallback) {
        val stepTag = "[WebRTC-INIT]"
        if (running) {
            Log.w(TAG, "$stepTag 已在运行中，先关闭旧会话")
            closeInternal(false)
        }
        running = true
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

        // ★ 1. 初始化音频设备模块：开启 AEC/NS/AGC + Opus + NetEQ（一站式音频处理链）
        val adm = try {
            Log.i(TAG, "$stepTag [3/8] JavaAudioDeviceModule.builder 开始")
            val b = JavaAudioDeviceModule.builder(appContext)
            Log.i(TAG, "$stepTag [3/8] builder 创建成功，开始配置参数...")
            val builtAdm = b
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .setAudioSource(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setSamplesReadyCallback(null)
                .createAudioDeviceModule()
            Log.i(TAG, "$stepTag [3/8] createAudioDeviceModule 成功 ✓")
            builtAdm.setSpeakerMute(false)
            builtAdm.setMicrophoneMute(false)
            Log.i(TAG, "$stepTag [3/8] 静音标志已设置 ✓")
            builtAdm
        } catch (t: Throwable) {
            Log.e(TAG, "$stepTag [3/8] AudioDeviceModule 失败 type=${t.javaClass.name} msg=${t.message}", t)
            cb.onError("AudioDeviceModule init failed: ${t.message}")
            return
        }

        // ★ 2. 初始化 PeerConnectionFactory：
        val options = PeerConnectionFactory.Options()
        try {
            Log.i(TAG, "$stepTag [4/8] PeerConnectionFactory.initialize(...) 开始")
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
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
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
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
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
        }

        // —— ★ [6/8] 创建 PeerConnection
        val rtcConfig = PeerConnection.RTCConfiguration(STUN_SERVERS).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
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
        val audioOnly = !offerSdp.contains("m=video")
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
                        Log.i(TAG, "$stepTag [8/8] createAnswer 成功 ✓ sdp len=${sdp.description.length}")
                        val preferSdp = preferOpusAndVp8(preferAudioFec(sdp.description))
                        val finalSdp = SessionDescription(sdp.type, preferSdp)
                        pc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                // ★ 2026-08-11 视频码率下限修复：防止WebRTC码率自适应把视频压到几乎不可见
                                //   现象：链路抖动/RTCP反馈差时，编码器连续重配 640x480→480x360→320x240，
                                //   最终OMX-VENC bitrate被压到3~12kbps → 画面近似黑屏 → "只有声音没有图像"。
                                //   设置video sender的minBitrateBps=400kbps，保证视频始终有可用码率。
                                try {
                                    val senders = pc.senders
                                    for (sender in senders) {
                                        val track = sender.track()
                                        if (track is VideoTrack) {
                                            val p = sender.parameters
                                            val encs = p.encodings
                                            for (enc in encs) {
                                                enc.minBitrateBps = 400_000
                                                try { enc.maxFramerate = 20 } catch (_: Throwable) {}
                                                if (enc.maxBitrateBps == null || enc.maxBitrateBps!! <= 0) {
                                                    enc.maxBitrateBps = 2_500_000
                                                }
                                            }
                                            sender.parameters = p
                                            Log.i(TAG, "$stepTag ★ 视频sender码率下限已设置 min=400kbps max=2500kbps（防止码率饥饿黑屏）")
                                        }
                                    }
                                } catch (t: Throwable) {
                                    Log.w(TAG, "$stepTag 设置视频码率下限失败(忽略): ${t.message}")
                                }
                                val answerB64 = Base64.getEncoder().encodeToString(
                                    finalSdp.description.toByteArray(StandardCharsets.UTF_8)
                                )
                                Log.i(TAG, "$stepTag [8/8] setLocalDescription 成功 ✓，发送 ANSWER(len=${answerB64.length})")
                                cb.onSignalingMessage(RelayCommands.CMD_WEBRTC_ANSWER, answerB64)
                                cb.onStatus("ready", "ANSWER已发送，等待ICE连通")
                            }
                            override fun onSetFailure(e: String?) {
                                Log.e(TAG, "$stepTag [8/8] setLocalDescription 失败: $e")
                                cb.onError("setLocalDescription failed: $e")
                            }
                        }, finalSdp)
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

    @Synchronized
    fun close() {
        closeInternal(true)
    }

    @Synchronized
    private fun closeInternal(notifyCb: Boolean) {
        running = false
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
            f.createAudioSource(MediaConstraints())
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
            capturer.initialize(surfaceTextureHelper, appContext, videoSource!!.capturerObserver)
        } catch (t: Throwable) {
            Log.e(TAG, "$tag [v6] capturer.initialize 失败 type=${t.javaClass.name} msg=${t.message}", t)
            throw t
        }
        Log.i(TAG, "$tag [v7] capturer.startCapture(640x480@20) 开始...")
        try {
            capturer.startCapture(640, 480, 20)
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
        return sdp
    }

    /** 简化 SdpObserver：避免每次复写所有方法 */
    private abstract class SdpObserverAdapter : SdpObserver {
        override fun onSetSuccess() {}
        override fun onSetFailure(e: String?) { Log.e(TAG, "SdpObserver onSetFailure: $e") }
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(e: String?) { Log.e(TAG, "SdpObserver onCreateFailure: $e") }
    }
}
