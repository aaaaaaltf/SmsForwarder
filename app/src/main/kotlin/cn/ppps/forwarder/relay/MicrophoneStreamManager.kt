package cn.ppps.forwarder.relay

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.PowerManager
import cn.ppps.forwarder.App
import cn.ppps.forwarder.utils.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.InetSocketAddress

/**
 * 被控端麦克风采集推流管理器
 *
 * ★ 2026-08-09 双通道实现（参考屏幕推流 ScreenStreamManager）：
 * - 数据通道（56791）：PUSHER/LISTENER 配对传输PCM音频，高效无命令帧开销
 * - 命令通道（56786）：CMD_MIC_START/STOP 控制命令 + 数据通道就绪通知
 *
 * 两种模式：
 * 1. 中继模式：被控端主动连中继56791(PUSHER)，控制端连中继56791(LISTENER)，中继桥接
 * 2. 直连/TS模式：被控端监听56791，控制端直接连接被控端
 *
 * 音频参数：采样率 8000Hz、单声道、16bit PCM，与控制端 AudioTrack 配置严格一致。
 */
object MicrophoneStreamManager {

    private const val TAG = "MicStreamManager"

    /** 音频采集参数（与控制端 AudioTrack 配置一致） */
    private const val SAMPLE_RATE = 8000
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /** 每帧采样数：320采样 = 640字节 = 40ms音频 */
    private const val FRAME_SAMPLES = 320

    @Volatile
    private var running = false
    @Volatile
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    /**
     * ★ 当前会话的"属主线程"：只有它可以清理公共字段。
     *   采集线程的 finally 会调 cleanup()（停 AudioRecord、关数据通道、running=false）。
     *   而 start() 在 running 时会先 stopInternal()，即"停止+立刻重开"很常见：
     *   旧线程还在退出路上，新会话已经把 audioRecord/running 建好，
     *   旧线程一视 cleanup() 就把**新**会话的麦克风关掉并把 running 置回 false
     *   → 控制端以为在听，实际永远无声。用线程身份判定归属，被顶替的旧线程只回收自己。
     */
    @Volatile
    private var ownerThread: Thread? = null
    @Volatile
    private var sender: RelaySender? = null

    /** 数据通道socket（PUSHER或服务端）
     *  ★ 必须 volatile：赋值发生在采集线程，而 STOP 在另一条线程上负责关流解阻塞，
     *    看不见就等于没关——调用方会一直卡在 accept/write 里直到对端超时。 */
    @Volatile
    private var dataSocket: Socket? = null
    @Volatile
    private var serverSocket: ServerSocket? = null

    /** 最近一次打开失败原因 */
    @Volatile
    private var lastErrMsg: String? = null

    /** 唤醒锁：防止关屏后 CPU 深度休眠中断麦克风采集 */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * ★ 2026-08-28 省电：麦克风"无人接收"自动收尾（与 CameraStreamManager 同一策略）。
     *   依据：麦克风推流由控制端命令开启、也只能靠控制端命令关闭。控制端进程被杀/网络掉线时
     *   STOP 命令丢失，而本端 s.send(CMD_MIC_FRAME) 是【异步投递到 sendExecutor】——即使 socket 已失效
     *   也不会向采集循环抛异常，于是采集循环里的 while (running) 永远不退：
     *   AudioRecord 持续录音 + 每 40ms 一次线程唤醒 + PARTIAL_WAKE_LOCK 让 CPU 无法深睡，灭屏也不停，
     *   这是被控端第二贵的空转（第一是同样没人看的摄像头）。
     *   策略：看门狗每 IDLE_CHECK_INTERVAL_MS 查一次命令通道，连续 NO_CONSUMER_OFFLINE_ROUNDS 轮
     *   （10s×6=60s）sender 都不可用 → 判定对端已走 → stop() 释放 AudioRecord 与 WakeLock。
     *   控制端还连着就永远不会触发，"麦克风监听"功能不受影响；掉线后重新点一次即可恢复，
     *   行为与手动开关完全一致。
     */
    private const val NO_CONSUMER_OFFLINE_ROUNDS = 6

    /** 空闲看门狗轮询间隔（10 秒 × 6 轮 = 60 秒判定窗口） */
    private const val IDLE_CHECK_INTERVAL_MS = 10000L

    /** 已触发空闲停止标志，避免看门狗反复调用 stop() */
    @Volatile
    private var idleStopTriggered = false

    private var idleWatchdog: Thread? = null

    fun lastError(): String = lastErrMsg ?: "未知错误"

    /** 是否正在采集推麦克风（供 RelayServerService 判断"会话进行中"，会话期间不降频） */
    fun isStreaming(): Boolean = running

    /**
     * ★★★ 2026-08-13 检测是否有其他应用正在使用麦克风（如微信视频通话）→ 返回占用应用的包名，无占用返回null。
     *  Android 10+ 通过 AudioManager.activeRecordingConfigurations 查询当前正在录音的应用。
     *  用于：启动麦克风前预检，被占用时给控制端明确反馈"为什么没有声音"。
     */
    fun micBusyByOtherApp(): String? {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val am = App.context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                val configs = am.activeRecordingConfigurations ?: return null
                for (c in configs) {
                    // Android 14+ 将 AudioRecordingConfiguration.getClientUid() 隐藏为非公开，
                    // 编译SDK中无法直接引用，用反射获取（运行时可取到占用应用的uid）
                    var uid = -1
                    try {
                        val m = c.javaClass.getDeclaredMethod("getClientUid")
                        if (!m.isAccessible) m.isAccessible = true
                        val v = m.invoke(c)
                        if (v is Int) uid = v
                    } catch (_: Throwable) {}
                    if (uid <= 0 || uid == android.os.Process.myUid()) continue
                    val pkg = try {
                        App.context.packageManager.getNameForUid(uid)
                    } catch (_: Throwable) { null }
                    if (pkg != null && pkg != App.context.packageName) return pkg
                }
            }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /** 获取本机IPv4地址（用于直连模式通知控制端）
     *  ★ 2026-08-10 修复：直连模式优先返回虚拟网 Tailscale IP（100.64.x.x），
     *  ★ 2026-08-16 增加：Tailscale 直连优先返回 100.64.x.x 的 Tailscale IP，
     *  其次返回 192.168.x.x / 10.x.x.x 的局域网IP，最后回退到第一个非回环IP。
     *  避免返回WiFi热点IP导致控制端无法连接。
     */
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "127.0.0.1"
            var fallbackIp: String? = null
            var lanIp: String? = null
            for (intf in interfaces) {
                val addrs = intf.inetAddresses?.toList() ?: continue
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        when {
                            ip.startsWith("100.") && isTailscaleCgnat(ip) -> {
                                Log.i(TAG, "getLocalIpAddress 选择Tailscale IP: $ip")
                                return ip
                            }
                            ip.startsWith("192.168.") || ip.startsWith("10.") -> {
                                lanIp = ip
                            }
                            else -> {
                                if (fallbackIp == null) fallbackIp = ip
                            }
                        }
                    }
                }
            }
            lanIp?.let { Log.i(TAG, "getLocalIpAddress 选择局域网IP: $it"); return it }
            fallbackIp?.let { Log.i(TAG, "getLocalIpAddress 选择fallback IP: $it"); return it }
        } catch (e: Exception) {
            Log.w(TAG, "getLocalIpAddress异常: ${e.message}")
        }
        return "127.0.0.1"
    }

    /** 判断是否为 Tailscale CGNAT 段（100.64.0.0/10） */
    private fun isTailscaleCgnat(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4 || p[0] != "100") return false
        return p[1].toIntOrNull()?.let { it in 64..127 } ?: false
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = App.context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder:MicStream").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "申请唤醒锁失败: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
        } catch (_: Exception) {
        }
    }

    /**
     * ★ 2026-08-28 省电：启动"无人收流"看门狗（详见 NO_CONSUMER_OFFLINE_ROUNDS 注释）。
     *   只在命令通道确实不可用时收尾，任何还连着的控制端都会让计数归零，绝不切断正常会话。
     *   stop() 不是 @Synchronized 的阻塞等待（stopInternal 不 join 采集线程），在本线程调用安全。
     */
    private fun startIdleWatchdog() {
        // ★ 不复用旧线程：旧线程可能正处于"检测到 running=false 即将退出"的尾巴上，
        //   复用会让本次新会话完全没有省电收尾。先收旧的再开新的，代价仅一个短生命周期线程。
        stopIdleWatchdog()
        idleStopTriggered = false
        idleWatchdog = Thread({
            var offlineRounds = 0
            while (running && !idleStopTriggered) {
                try {
                    Thread.sleep(IDLE_CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running || idleStopTriggered) break
                val s = sender
                if (s == null) break          // 已被 stopInternal 清理，无需再管
                if (s.isConnected()) {
                    offlineRounds = 0          // 控制端还在 → 继续全速采集
                    continue
                }
                offlineRounds++
                if (offlineRounds < NO_CONSUMER_OFFLINE_ROUNDS) continue
                idleStopTriggered = true
                Log.i(TAG, "★ 省电：麦克风命令通道连续 ${offlineRounds * IDLE_CHECK_INTERVAL_MS / 1000}s 不可用（控制端可能已掉线），自动停止采集并释放 WakeLock")
                try {
                    stop()
                } catch (_: Throwable) {
                }
                break
            }
        }, "MicIdleWatchdog").apply { isDaemon = true }
        idleWatchdog!!.start()
    }

    /** ★ 省电：会话结束时收掉空闲看门狗（避免残留线程下一轮误停新会话） */
    private fun stopIdleWatchdog() {
        try {
            idleWatchdog?.interrupt()
        } catch (_: Exception) {
        }
        idleWatchdog = null
    }

    /**
     * 启动麦克风采集推流（数据通道模式）
     *
     * @param s 命令通道发送器（用于发送CMD_MIC_DATA_READY通知）
     * @param relayHost 中继服务器地址（中继模式下PUSHER连接）
     * @param clientId 被控端pc_id（用于生成会话ID）
     * @param channel 命令来源通道：CHANNEL_RELAY/CHANNEL_DIRECT/CHANNEL_TS
     * @return 启动成功返回会话ID（控制端据此连接数据通道），失败返回null
     */
    @Synchronized
    fun start(s: RelaySender?, relayHost: String?, clientId: Int, channel: Int): String? {
        if (running) {
            stopInternal()
        }
        if (s == null) {
            lastErrMsg = "发送通道无效"
            return null
        }
        // ★★★ 2026-08-13 预检：麦克风被其他应用占用（如微信视频通话）→ 明确反馈，不让控制端误以为"无声正常"
        val busyPkg = micBusyByOtherApp()
        if (busyPkg != null) {
            lastErrMsg = "被控端麦克风被【$busyPkg】占用（可能正在视频通话），无法录音"
            Log.w(TAG, "麦克风启动失败: $lastErrMsg")
            return null
        }
        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufSize <= 0) {
                lastErrMsg = "不支持的音频参数（采样率/声道/格式）"
                return null
            }
            val bufSize = minBufSize * 4
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastErrMsg = "AudioRecord 初始化失败（可能被其他应用占用或权限不足）"
                try { recorder.release() } catch (_: Exception) {}
                return null
            }
            recorder.startRecording()
            audioRecord = recorder
            sender = s
            running = true
            acquireWakeLock()
            // ★ 2026-08-28 省电：挂上"无人收流"看门狗（控制端掉线导致 STOP 丢失时自动释放麦克风与WakeLock）
            startIdleWatchdog()

            // ★ 生成会话ID并连接数据通道
            val sessionId = "mic_${clientId}_${System.currentTimeMillis()}"
            val isDirect = channel != RelayServerHandler.CHANNEL_RELAY

            val capture = Thread({
                try {
                    val out: java.io.OutputStream
                    var useDataChannel = true
                    if (isDirect) {
                        // 直连模式：监听数据端口
                        val ss = ServerSocket()
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress("0.0.0.0", RelayCommands.MIC_DATA_PORT))
                        ss.soTimeout = 15000  // ★ 修复：accept最多等15秒，超时自动回退命令通道（避免卡死无日志）
                        serverSocket = ss
                        Log.i(TAG, "麦克风数据通道监听 ${RelayCommands.MIC_DATA_PORT}，等待控制端接入(最多15秒)...")

                        // ★★★ 先通知控制端数据通道就绪，再等待接入（原逻辑相反导致死锁）
                        try {
                            val localIp = getLocalIpAddress()
                            val info = "$localIp|${RelayCommands.MIC_DATA_PORT}"
                            s.send(RelayCommands.CMD_MIC_DATA_READY, info)
                            Log.i(TAG, "★ 已提前通知控制端数据通道就绪(直连模式): $info")
                        } catch (e: Exception) {
                            Log.w(TAG, "提前通知数据通道就绪失败(直连模式): ${e.message}")
                        }

                        // 再等待控制端接入（最多15秒）
                        val accepted = try {
                            if (running) ss.accept() else null
                        } catch (e: java.net.SocketTimeoutException) {
                            Log.w(TAG, "★ 直连模式等待控制端连接超时(15秒)，自动回退命令通道推流")
                            useDataChannel = false
                            null
                        }
                        if (!useDataChannel || accepted == null) {
                            // ★ 回退命令通道：关闭数据通道socket，但保留audioRecord继续采集
                            try { ss.close() } catch (_: Exception) {}
                            // ★ 只有还持有属主身份时才清公共句柄：
                            //   被顶替后 field 里已是新会话的 ServerSocket，清空会让下次 STOP 关不掉它
                            if (ownsSession()) serverSocket = null
                            if (running && ownsSession()) {
                                Log.i(TAG, "★ 回退到命令通道模式（直连超时无人接入）")
                                captureLoopCommandChannel()
                            }
                            return@Thread
                        }
                        accepted.tcpNoDelay = true
                        // ★ 等待接入期间已被 STOP/新会话顶替：绝不把 socket 挂到新会话的公共字段上
                        if (!ownsSession()) {
                            Log.i(TAG, "★ 等待接入期间会话已被顶替，关闭本次接入并退出")
                            try { accepted.close() } catch (_: Exception) {}
                            try { ss.close() } catch (_: Exception) {}
                            return@Thread
                        }
                        dataSocket = accepted
                        out = accepted.getOutputStream()
                        Log.i(TAG, "控制端已接入麦克风数据通道")
                    } else {
                        // 中继模式：连接中继数据端口，PUSHER认证
                        val cs = Socket()
                        cs.tcpNoDelay = true
                        try {
                            cs.connect(InetSocketAddress(relayHost, RelayCommands.MIC_DATA_PORT), 8000)
                        } catch (e: Exception) {
                            Log.e(TAG, "★ 连接中继麦克风数据通道失败: ${relayHost}:${RelayCommands.MIC_DATA_PORT} - ${e.message}，自动回退命令通道")
                            lastErrMsg = "连接中继麦克风数据通道失败: ${e.message}"
                            // ★ 修复：不要cleanup()！保留audioRecord，回退命令通道推流
                            useDataChannel = false
                        }
                        if (!useDataChannel) {
                            if (running && ownsSession()) {
                                Log.i(TAG, "★ 回退到命令通道模式（中继数据通道连接失败）")
                                captureLoopCommandChannel()
                            }
                            return@Thread
                        }
                        if (!running) {
                            try { cs.close() } catch (_: Exception) {}
                            return@Thread
                        }
                        // ★ connect 最长 8 秒，期间可能已被 STOP/新会话顶替：
                        //   此时公共字段已属于新会话，挂上来会让下次 STOP 关错 socket
                        if (!ownsSession()) {
                            Log.i(TAG, "★ 连接期间会话已被顶替，关闭本次数据通道并退出")
                            try { cs.close() } catch (_: Exception) {}
                            return@Thread
                        }
                        dataSocket = cs
                        out = cs.getOutputStream()
                        // ★ 发送PUSHER认证标签
                        out.write("PUSHER:$sessionId\n".toByteArray(Charsets.UTF_8))
                        out.flush()
                        Log.i(TAG, "★ 已连接中继麦克风数据通道 $relayHost:${RelayCommands.MIC_DATA_PORT} session=$sessionId")

                        // ★ 中继模式下也通知控制端数据通道就绪
                        try {
                            val info = "$sessionId|${RelayCommands.MIC_DATA_PORT}"
                            s.send(RelayCommands.CMD_MIC_DATA_READY, info)
                            Log.i(TAG, "★ 已通知控制端数据通道就绪(中继模式): $info")
                        } catch (e: Exception) {
                            Log.w(TAG, "通知数据通道就绪失败(中继模式): ${e.message}")
                        }
                    }

                    // ★ 开始音频采集推流（数据通道模式）
                    if (useDataChannel) {
                        val success = captureLoop(out)
                        // ★ 2026-08-10 修复：如果captureLoop因写入失败退出（LISTENER未连接/relay未配对），
                        // 且running仍为true，回退到命令通道模式继续推流，避免完全无数据发送
                        if (!success && running && ownsSession() && audioRecord != null) {
                            Log.i(TAG, "★ 数据通道写入失败（LISTENER未连接?），回退命令通道模式继续推流")
                            captureLoopCommandChannel()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "麦克风数据通道启动失败: ${e.message}")
                    lastErrMsg = e.message ?: "数据通道启动异常"
                    // ★ 修复：如果异常时还在running状态，尝试回退命令通道（最后的兜底）
                    if (running && ownsSession() && audioRecord != null) {
                        try {
                            Log.i(TAG, "★ 异常兜底：回退命令通道模式继续采集")
                            captureLoopCommandChannel()
                        } catch (_: Exception) {}
                    }
                } finally {
                    cleanup()
                }
            }, "MicCapture")
            // ★ 属主必须在 start() 之前登记：Thread.start() 建立 happens-before，
            //   新线程一进 cleanup() 就能认出"这一轮是我的"，被顶替的旧线程则认不出。
            captureThread = capture
            ownerThread = capture
            capture.start()

            return sessionId
        } catch (e: SecurityException) {
            lastErrMsg = "缺少麦克风权限（RECORD_AUDIO）：${e.message}"
            Log.e(TAG, "启动麦克风失败: $lastErrMsg")
            return null
        } catch (e: Exception) {
            lastErrMsg = e.message ?: "启动麦克风异常"
            Log.e(TAG, "启动麦克风异常: ${e.message}")
            return null
        }
    }

    /** 兼容旧接口：直接通过命令通道推流 */
    @Synchronized
    fun start(s: RelaySender?): Boolean {
        if (running) {
            stopInternal()
        }
        if (s == null) {
            lastErrMsg = "发送通道无效"
            return false
        }
        // ★★★ 2026-08-13 预检：麦克风被其他应用占用（如微信视频通话）→ 明确反馈
        val busyPkg = micBusyByOtherApp()
        if (busyPkg != null) {
            lastErrMsg = "被控端麦克风被【$busyPkg】占用（可能正在视频通话），无法录音"
            Log.w(TAG, "麦克风启动失败: $lastErrMsg")
            return false
        }
        try {
            val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBufSize <= 0) {
                lastErrMsg = "不支持的音频参数"
                return false
            }
            val bufSize = minBufSize * 4
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
                bufSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                lastErrMsg = "AudioRecord 初始化失败"
                try { recorder.release() } catch (_: Exception) {}
                return false
            }
            recorder.startRecording()
            audioRecord = recorder
            sender = s
            running = true
            acquireWakeLock()
            // ★ 2026-08-28 省电：挂上"无人收流"看门狗（控制端掉线导致 STOP 丢失时自动释放麦克风与WakeLock）
            startIdleWatchdog()

            // ★ 原来这个重载起完线程就再没人收尾：captureLoopCommandChannel 因
            //   sender.send 抛异常 / AudioRecord 读取异常而 break 时，running 仍是 true、
            //   AudioRecord 仍在录音、WakeLock 仍持有 → 控制端显示"麦克风开着"却永久无声，
            //   只能等 60 秒空闲看门狗碰运气。这里显式登记属主并在退出时收尾。
            val capture = Thread({
                try {
                    captureLoopCommandChannel()
                } finally {
                    cleanup()
                }
            }, "MicCapture-CmdCh")
            captureThread = capture
            ownerThread = capture
            capture.start()
            return true
        } catch (e: SecurityException) {
            lastErrMsg = "缺少麦克风权限：${e.message}"
            return false
        } catch (e: Exception) {
            lastErrMsg = e.message ?: "启动异常"
            return false
        }
    }

    /** 停止麦克风采集 */
    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        running = false
        // ★ 先收掉属主身份：本方法在调用线程上把资源全部释放干净了，
        //   采集线程随后退出的 finally 绝不能再清一遍——那时 audioRecord/running
        //   可能已经属于紧随其后的新会话（start() 里就是 stopInternal() + 重建）。
        ownerThread = null
        // ★ 省电看门狗随会话一起收掉（无论正常停止还是空闲自停）
        stopIdleWatchdog()
        try { captureThread?.interrupt() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { dataSocket?.close() } catch (_: Exception) {}
        dataSocket = null
        captureThread = null
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        sender = null
        releaseWakeLock()
    }

    /** ★ 当前线程是否仍是本会话属主（被 STOP 或新会话顶替后返回 false） */
    private fun ownsSession(): Boolean = Thread.currentThread() === ownerThread

    private fun cleanup() {
        // ★ 被顶替的旧采集线程不能清理公共字段：它一视会把**新**会话的
        //   AudioRecord 停掉、running 置回 false，控制端从此只听不到声音。
        if (!ownsSession()) {
            Log.i(TAG, "★ 本线程已非会话属主（已被停止或被新会话顶替），跳过公共资源清理")
            return
        }
        ownerThread = null
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { dataSocket?.close() } catch (_: Exception) {}
        dataSocket = null
        try {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        } catch (_: Exception) {}
        audioRecord = null
        releaseWakeLock()
    }

    /** 采集循环：数据通道模式（原始PCM流）
     * @return true=正常结束（running=false），false=写入异常（应回退命令通道）
     */
    private fun captureLoop(out: java.io.OutputStream): Boolean {
        val buffer = ShortArray(FRAME_SAMPLES)
        val byteBuffer = ByteArray(FRAME_SAMPLES * 2)
        var frameCount = 0
        var totalBytes = 0L
        var writeFailed = false  // ★ 2026-08-10 标记写入失败，用于回退判断
        val startTime = System.currentTimeMillis()
        Log.i(TAG, "★ 麦克风采集循环启动: 采样率=$SAMPLE_RATE Hz, 帧大小=${FRAME_SAMPLES * 2}字节/帧, 期望速率=${FRAME_SAMPLES * 8}字节/秒")
        try {
            while (running && !Thread.interrupted()) {
                val rec = audioRecord ?: break
                val shortsRead = try {
                    rec.read(buffer, 0, FRAME_SAMPLES)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord读取异常: ${e.message}")
                    break
                }
                if (shortsRead <= 0) {
                    Thread.sleep(20)
                    continue
                }
                // Short → ByteArray（小端序）
                var byteIdx = 0
                for (i in 0 until shortsRead) {
                    val s = buffer[i]
                    byteBuffer[byteIdx++] = s.toByte()
                    byteBuffer[byteIdx++] = (s.toInt() shr 8).toByte()
                }
                val frameLen = shortsRead * 2
                val frame = if (frameLen == byteBuffer.size) {
                    byteBuffer
                } else {
                    byteBuffer.copyOf(frameLen)
                }

                try {
                    out.write(frame)
                    out.flush()
                    frameCount++
                    totalBytes += frameLen
                    // ★ 每50帧打印详细调试信息（约2秒@8kHz/320samples）
                    if (frameCount % 50 == 0) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val rate = if (elapsed > 0) totalBytes * 1000.0 / elapsed else 0.0
                        Log.i(TAG, "★ 麦克风推流状态: 帧数=$frameCount, 总字节=$totalBytes, 耗时=${elapsed}ms, 速率=${String.format("%.1f", rate)}字节/秒")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "数据通道写入失败: ${e.message}")
                    writeFailed = true
                    break
                }
            }
        } catch (ie: InterruptedException) {
            // 正常停止
        } catch (e: Exception) {
            Log.e(TAG, "采集循环异常: ${e.message}")
            writeFailed = true
        } finally {
            val elapsed = System.currentTimeMillis() - startTime
            Log.i(TAG, "★ 麦克风数据通道采集结束，共发送${frameCount}帧, ${totalBytes}字节, 耗时${elapsed}ms")
            if (elapsed > 0 && frameCount > 0) {
                val rate = totalBytes * 1000.0 / elapsed
                val fps = frameCount * 1000.0 / elapsed
                Log.i(TAG, "★ 平均速率: ${String.format("%.1f", rate)}字节/秒, 帧率: ${String.format("%.1f", fps)}帧/秒")
            }
        }
        return !writeFailed  // true=正常结束, false=写入失败需回退
    }

    /** 采集循环：命令通道模式（CMD_MIC_FRAME） */
    private fun captureLoopCommandChannel() {
        val buffer = ShortArray(FRAME_SAMPLES)
        val byteBuffer = ByteArray(FRAME_SAMPLES * 2)
        var frameCount = 0
        try {
            while (running && !Thread.interrupted()) {
                val rec = audioRecord ?: break
                val shortsRead = try {
                    rec.read(buffer, 0, FRAME_SAMPLES)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioRecord读取异常: ${e.message}")
                    break
                }
                if (shortsRead <= 0) {
                    Thread.sleep(20)
                    continue
                }
                var byteIdx = 0
                for (i in 0 until shortsRead) {
                    val s = buffer[i]
                    byteBuffer[byteIdx++] = s.toByte()
                    byteBuffer[byteIdx++] = (s.toInt() shr 8).toByte()
                }
                val frameLen = shortsRead * 2
                val frame = if (frameLen == byteBuffer.size) {
                    byteBuffer
                } else {
                    byteBuffer.copyOf(frameLen)
                }

                val s = sender
                if (s != null && running) {
                    try {
                        s.send(RelayCommands.CMD_MIC_FRAME, frame)
                        frameCount++
                        if (frameCount % 50 == 0) {
                            Log.i(TAG, "麦克风命令通道推流中: 已发送${frameCount}帧")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "命令通道发送失败: ${e.message}")
                        break
                    }
                }
                Thread.sleep(40)
            }
        } catch (ie: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "命令通道采集异常: ${e.message}")
        } finally {
            Log.i(TAG, "麦克风命令通道采集结束，共发送${frameCount}帧")
        }
    }
}
