package cn.ppps.forwarder.relay

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import cn.ppps.forwarder.utils.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * 被控端屏幕推流管理器
 *
 * 1. ServerFragment 通过 MediaProjection 授权后，把授权结果交给 ScreenProjectionService，
 *    ScreenProjectionService 调用 [setProjection] 保存 MediaProjection。
 * 2. 收到控制端 rdstrt000000 命令后调用 [startStream]：
 *    - ★ Tailscale直连模式（RelaySettings.directMode=true）：监听 56788 端口，接受控制端直接连接（无需中继）
 *    - 中继模式：主动连接中继 56788，发送 PUSHER:clientId\n 认证标签
 *    - 通过 VirtualDisplay + ImageReader 采集屏幕帧
 *    - 每帧发送 [4字节大端JPEG长度][JPEG二进制]
 * 3. 屏幕画面仅推送到控制端，不在被控端本地显示。
 */
object ScreenStreamManager {

    private const val TAG = "ScreenStreamManager"

    /** 推流分辨率上限的**默认值**（★ 2026-08-06修复：原代码定义了但未使用，全屏1080x2400推流在弱网下帧过大TCP写阻塞→黑屏）
     *
     *  ★★★ 2026-10-05【画面质量】改为"默认值"而非唯一值：控制端设置页的
     *    「手机被控端屏幕预览」档位会随 rdstrt 负载下发 `w=`/`h=`（见
     *    android_controller 的 core/QualityProfile.java 与这里 startStream 的
     *    maxWidth/maxHeight 参数）。控制端不带（旧版本）⇒ 用这两个默认值，
     *    行为与改造前逐字节一致。 */
    private const val DEFAULT_MAX_WIDTH = 1280
    private const val DEFAULT_MAX_HEIGHT = 720

    /**
     * ★ 2026-08-28 省电：帧节拍等待上限。
     *   等待"下一帧该发了"时不再 5 毫秒忙等，而是一次睡到节拍点；上限用于保证
     *   stop()/写阻塞看门狗仍能在 100 毫秒内被采集循环观察到（低帧率请求时也不会睡过头）。
     */
    private const val PACE_SLEEP_MAX_MS = 100L

    /** ★ 省电：acquireLatestImage() 无新帧时的起始等待（与改造前一致） */
    private const val NO_IMAGE_WAIT_MS = 5L

    /** ★ 省电：无新帧时的等待上限（灭屏/静止画面下把 200次/秒空转降到 20次/秒） */
    private const val NO_IMAGE_BACKOFF_MS = 50L

    /** 写阻塞看门狗阈值：超过该时间未成功发完一帧则强制断开，允许控制端重试重建推流（★ 2026-08-06新增） */
    private const val WRITE_WATCHDOG_MS = 10000L

    // ===== ★★★ 2026-10-05【自适应码率】常量（对照 rustdesk video_qos.rs）=====
    /** 评估周期（同 ADJUST_RATIO_INTERVAL=3s） */
    private const val QOS_ADJUST_INTERVAL_MS = 3000L
    /** 质量下限：再低画面不可用（类比 BR_MIN=0.2 的下界思想） */
    private const val QUALITY_MIN = 25
    /** 拥塞判据：写出耗时 EMA 超过此值（ms）——对应 DELAY_THRESHOLD_150MS=150，JPEG 写出更快故取 90 */
    private const val QOS_CONGEST_EMA_MS = 90.0
    /** 宽裕判据：低于此值才有资格升档，留滞回带防抖 */
    private const val QOS_SMOOTH_EMA_MS = 35.0
    /** 连续 N 次拥塞才降档（对应 consecutive_bad_samples>=2） */
    private const val QOS_CONGEST_GUARD = 2
    /** 连续 N 次宽裕才升档（对应 RESTORE_GUARD_SAMPLES=5，取 3 更快恢复） */
    private const val QOS_RESTORE_GUARD = 3

    /** 最近一次成功发送帧的时间戳（看门狗用） */
    @Volatile
    private var lastSendTime = 0L

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var running = false

    /**
     * ★ 2026-09-04 本次推流的归属线程（与 MicrophoneStreamManager 同一套做法）。
     * 建流线程体里有几条失败路径（中继连不上 + 回退监听也失败）会直接退出，退出时必须把
     * running 归位，否则此后每次 rdstrt 都命中"已在运行"分支返回 true，而实际没人在推流
     * ——即"预览黑屏且再也起不来"。但"谁有权收尾"必须绑定身份：旧线程晚到的收尾会把
     * 刚建好的新会话一起置停，所以用 AtomicReference 做 check-and-clear（CAS 一次完成，
     * 不留"检查通过后所有权刚好被换掉"的窗口）。
     */
    private val ownerRef = java.util.concurrent.atomic.AtomicReference<Thread?>(null)

    private fun ownsSession(): Boolean = ownerRef.get() === Thread.currentThread()

    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamThread: Thread? = null
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    /** 是否已完成屏幕捕获授权 */
    fun isReady(): Boolean = projection != null

    /**
     * ★★★ 2026-10-04 屏幕 WebRTC 高清模式用：把【已授权的 MediaProjection 实例】借给
     * `ScreenWebRtcCapturer`。
     *
     * 为什么不让它自己用保存的 resultCode/Intent 再 `getMediaProjection` 一次：
     * Android 14+ 对同一个授权 token 调 `getMediaProjection` 会抛异常（"只能用一次"），
     * 而且这里本来就已有一个活着的实例。借用方**只建 VirtualDisplay/ImageReader**，
     * 停止时只释放自己建的这些，绝不动投影实例本身（JPEG 预览还要继续用它）。
     */
    fun currentProjection(): android.media.projection.MediaProjection? = projection

    /** 由 ScreenProjectionService 设置已授权的 MediaProjection */
    fun setProjection(p: MediaProjection?) {
        if (p != null) {
            stop() // 先清理旧的
            projection = p
        } else {
            projection = null
        }
        Log.i(TAG, "屏幕捕获授权状态: ${if (projection != null) "已授权" else "未授权"}")
    }

    fun isStreaming(): Boolean = running

    // ==================== ★★★ 2026-10-05【自适应码率】====================
    //  对照 rustdesk/src/server/video_qos.rs：
    //    · BR_MIN=0.2/BR_MIN_HIGH_RESOLUTION=0.1（码率下界）、ADJUST_RATIO_INTERVAL=3s（调整冷却）
    //    · ratio_reduction() 分档 ×0.85/0.8/0.7…（:227-248），要求 consecutive_bad_samples>=2 才动作
    //    · 升档保守：延迟 <50ms 才 ×1.15、<100ms 才 ×1.1（:775-785），RESTORE_GUARD_SAMPLES=5 防抖动
    //  本项目没有可调码率的编码器（纯 JPEG 字节流），**把 ratio 语义映射为 JPEG 质量**：
    //    质量 ↓ ⇒ 每帧字节数 ↓ ⇒ 码率 ↓（分辨率与帧率不动，避免观众侧画面尺寸跳变）。
    //  拥塞信号取**本端写出耗时**（write+flush 的 EMA）——它直接反映"链路堵不堵"，
    //  与 RustDesk 用 TestDelay 往返测 RTT 同源，但**零协议改动**（不需要控制端配合）。
    /** 控制端下发的原始质量（作为自适应上限参考，不被修改） */
    @Volatile private var startQuality: Int = 50
    /** 当前实际用于编码的 JPEG 质量（由自适应调整） */
    @Volatile private var qosQuality: Int = 50
    private var qosBadSamples = 0
    private var qosGoodSamples = 0
    private var qosEmaMs = 0.0
    private var qosLastAdjustAt = 0L

    // ★★★ 2026-10-05【软编降分辨率 half-scale（对照 RustDesk `enable-android-software-encoding-half-scale`）】
    //   为什么需要：上方的自适应只有"质量"这一维，而 JPEG 质量有实用下限（QUALITY_MIN=25，
    //   再低画面就不可用了）。一旦**质量已触底但链路仍拥塞**，自适应就彻底失去手段 ——
    //   而这条链路是纯软编（Bitmap.compress，单线程 libjpeg-turbo），
    //   **编码耗时与本帧像素数近似成正比** ⇒ 分辨率减半 = 像素数减到 1/4 ⇒
    //   编码耗时和后端写出字节数同时大幅下降。这是"质量已无路可退"之后的最后一档。
    //   ★ 只在触底后启用、且优先于升质量恢复（分辨率对观感的影响大于质量档位）。
    @Volatile private var qosHalfScale: Boolean = false

    /**
     * 自适应质量：每 [QOS_ADJUST_INTERVAL_MS] 评估一次，拥塞降档、持续宽裕升档。
     * @param writeMs 本次写出的真实耗时（毫秒）
     */
    private fun updateQos(writeMs: Long) {
        // 发送耗时 EMA（0.8/0.2，与 PC 侧 rd_stream_hub._Sink 同口径）
        qosEmaMs = if (qosEmaMs <= 0.0) writeMs.toDouble()
                   else qosEmaMs * 0.8 + writeMs * 0.2
        val now = System.currentTimeMillis()
        if (now - qosLastAdjustAt < QOS_ADJUST_INTERVAL_MS) return
        qosLastAdjustAt = now
        val congested = qosEmaMs > QOS_CONGEST_EMA_MS
        val relaxed = qosEmaMs < QOS_SMOOTH_EMA_MS
        if (congested) {
            qosBadSamples++; qosGoodSamples = 0
        } else if (relaxed) {
            qosGoodSamples++; qosBadSamples = 0
        } else {
            // 中间地带：保持现状，不消耗降/升档预算（同 video_qos.rs:747-752）
            qosBadSamples = 0; qosGoodSamples = 0
            return
        }
        if (qosBadSamples >= QOS_CONGEST_GUARD) {
            qosBadSamples = 0
            val old = qosQuality
            val new = maxOf(QUALITY_MIN, (old * 0.85).toInt())
            if (new < old) {
                qosQuality = new
                Log.i(TAG, "自适应码率↓ 拥塞(ema=${"%.0f".format(qosEmaMs)}ms) 质量 $old→$new")
            } else if (!qosHalfScale) {
                // ★ 质量已触底仍拥塞 → 启用半分辨率（最后一档手段，见 qosHalfScale 注释）
                qosHalfScale = true
                Log.i(TAG, "自适应码率↓ 质量已触底($QUALITY_MIN)仍拥塞(ema=${"%.0f".format(qosEmaMs)}ms) → 启用半分辨率")
            }
        } else if (qosGoodSamples >= QOS_RESTORE_GUARD) {
            qosGoodSamples = 0
            if (qosHalfScale) {
                // ★ 恢复顺序：先还原分辨率，再升质量（分辨率对观感的权重更高）
                qosHalfScale = false
                Log.i(TAG, "自适应码率↑ 宽裕(ema=${"%.0f".format(qosEmaMs)}ms) → 先恢复全分辨率")
            } else {
                val old = qosQuality
                val new = minOf(startQuality, (old * 1.08).toInt() + 1)
                if (new > old) {
                    qosQuality = new
                    Log.i(TAG, "自适应码率↑ 宽裕(ema=${"%.0f".format(qosEmaMs)}ms) 质量 $old→$new")
                }
            }
        }
    }

    /**
     * 启动屏幕推流
     * @param relayHost 中继服务器地址
     * @param clientId 被控端pc_id（用于中继56788 PUSHER/LISTENER 配对）
     * @param fps 帧率
     * @param quality JPEG质量(10~90)
     * @param channel 命令来源通道：RelayServerHandler.CHANNEL_RELAY=中继 / CHANNEL_DIRECT=直连监听(56786) / CHANNEL_TS=TS直连(56789)
     * @return 是否成功启动
     */
    @Synchronized
    fun startStream(relayHost: String, clientId: Int, fps: Int, quality: Int,
                    channel: Int = cn.ppps.forwarder.relay.RelayServerHandler.CHANNEL_RELAY,
                    maxWidth: Int = 0, maxHeight: Int = 0): Boolean {
        val proj = projection
        if (proj == null) {
            Log.w(TAG, "屏幕捕获未授权，无法推流")
            return false
        }
        if (running) {
            Log.w(TAG, "屏幕推流已在运行")
            return true
        }
        val safeFps = fps.coerceIn(1, 30)
        val safeQuality = quality.coerceIn(10, 90)
        // ★★★ 2026-10-05【画面质量】分辨率上限来自控制端设置页的档位（rdstrt 负载的 `w=`/`h=`）。
        //   合法性夹取与三端口径一致（160..4096 / 120..4096，越界即视为"未指定"）；
        //   未指定 ⇒ 用 DEFAULT_*（= 改造前写死的 1280×720），行为不变。
        val safeMaxW = if (maxWidth in 160..4096) maxWidth else DEFAULT_MAX_WIDTH
        val safeMaxH = if (maxHeight in 120..4096) maxHeight else DEFAULT_MAX_HEIGHT
        Log.i(TAG, "★ 画面质量: fps=$safeFps 质量=$safeQuality 分辨率上限=${safeMaxW}x${safeMaxH}" +
                if (maxWidth in 160..4096) "（控制端指定）" else "（控制端未指定，用默认）")
        // ★★★ 2026-10-05【自适应码率】把质量控制权从"启动时定死"改为"运行期自适应"：
        //   `qosQuality` 是**当前实际用于编码的质量**，由推流循环按发送耗时/丢帧周期调整；
        //   `startQuality` 保留控制端下发的原始目标值作为上限参考。
        //   对照 rustdesk/src/server/video_qos.rs:731-815（adjust_ratio 降码率、775-785 分档升档）。
        startQuality = safeQuality
        qosQuality = safeQuality
        qosBadSamples = 0
        qosGoodSamples = 0
        qosEmaMs = 0.0
        qosLastAdjustAt = 0L
        // ★ 新会话必须复位半分辨率开关，否则上一段的降级会莫名其妙地延续到新会话
        qosHalfScale = false
        val safeClientId = if (clientId >= 0) clientId else 0
        // ★ 2026-08-05修复：推流模式由命令来源通道决定（而非本机中继连接状态）——
        //   命令经中继到达 → 主动连中继56788(PUSHER认证)；
        //   命令经直连监听(56786)/TS直连(56789)到达 → 监听56788等待控制端直连收流。
        //   原逻辑用 RelayServerService.isConnected 判断，被控端"始终连接中继"后恒为中继推流，
        //   控制端直连模式（云服务不可达）下连被控端56788无人监听 → 预览失败。
        // ★ 2026-09-04：入参 channel **不**决定推流模式（原因见下方 2026-08-15 智能模式注释）。
        //   原来的 `val direct = ...` 算出来从未被使用（编译器长期告警），删掉它，改为把命令来源
        //   打进日志——线上排查时仍需一眼看出"这次预览是哪条通道发起的、最终走了中继还是本机监听"。
        Log.i(TAG, "★ 收到屏幕推流请求：命令来源=${if (channel == cn.ppps.forwarder.relay.RelayServerHandler.CHANNEL_RELAY) "中继" else "直连"} clientId=$safeClientId fps=$safeFps 质量=$safeQuality")

        running = true
        val t = Thread({
            try {
                runStreamSession(relayHost, safeClientId, safeFps, safeQuality, channel,
                        safeMaxW, safeMaxH)
            } finally {
                // ★ 走到这里说明本线程的建流+采集已彻底结束（正常断开，或所有失败路径都走完）。
                //   CAS 成功 = 所有权仍在本线程 → 归位 running 并释放 VirtualDisplay/ImageReader，
                //   控制端下一次 rdstrt 才可能真正重建（原实现这几条失败路径直接退出，running 永真，
                //   之后每次请求都命中"已在运行"返回 true → 黑屏且再也起不来）。
                //   CAS 失败 = 已被 stop()/新会话接管 → 什么都不做，避免晚到的收尾误杀新会话。
                if (ownerRef.compareAndSet(Thread.currentThread(), null)) {
                    running = false
                    cleanup()
                    Log.i(TAG, "屏幕推流线程已退出，运行标志已归位（可重新发起预览）")
                }
            }
        }, "ScreenStreamThread").apply { isDaemon = true }
        streamThread = t
        ownerRef.set(t)
        t.start()
        return true
    }

    /**
     * 建流线程体：中继 PUSHER 优先，连不上再回退本机监听等待控制端直连收流。
     * 由 [startStream] 创建的 ScreenStreamThread 调用，返回后由该线程的 finally 统一收尾。
     */
    private fun runStreamSession(relayHost: String, safeClientId: Int, safeFps: Int, safeQuality: Int,
                                 channel: Int,
                                 safeMaxW: Int, safeMaxH: Int) {
            // ★★★ 2026-10-04【根因修复·控制端直连时"屏幕预览没有图像"】
            //   现场（2026-10-04 19:12 华为→红米，直连模式）：控制端连 **红米 TailscaleIP:56783**
            //   报 `ECONNREFUSED`，红米侧 19:12:14.983 建了 VirtualDisplay("ScreenPreview")、
            //   0.7 秒后又被移除。
            //   原因：**本方法无视命令来源通道，永远先 `connect(中继:56783)`**；而控制端在直连模式
            //   下按 `getVideoHostForDevice` 取的是**被控端 Tailscale IP**、等着本端 **listen**。
            //   ⇒ 本端连完中继就不监听 → 控制端必然被拒；随后建流线程因无配对方结束 → display 移除。
            //   （`startStream` 的 `channel` 入参此前完全未被使用，第 141-143 行注释甚至断言
            //     "channel 不决定推流模式" —— 那正是 2026-08-05 撤销旧逻辑时留下的坑。）
            //   ★ 修法：**推流模式由命令来源通道决定**（恢复 2026-08-05 的设计）：
            //       channel==CHANNEL_RELAY（命令经中继到达） → 本端 PUSHER 连中继 56783；
            //       channel!=CHANNEL_RELAY（直连 56786 / TS直连 56789 到达） → 本端 listen 56783 等控制端连入；
            //     各自保留"另一条路兜底"，避免单边失败就彻底无画面。
            val viaRelay = (channel == cn.ppps.forwarder.relay.RelayServerHandler.CHANNEL_RELAY)
            Log.i(TAG, "★ 屏幕推流通道决策：命令来源=${if (viaRelay) "中继" else "直连/TS直连"} → "
                    + (if (viaRelay) "本端PUSHER连中继$relayHost:${RelayCommands.RELAY_VIDEO_PORT}"
                       else "本端监听0.0.0.0:${RelayCommands.RELAY_VIDEO_PORT}等控制端连入"))
            if (viaRelay) {
                // —— A. 命令来自中继 → 本端主动连中继（PUSHER）
                try {
                    val cs = Socket()
                    cs.tcpNoDelay = true
                    cs.connect(InetSocketAddress(relayHost, RelayCommands.RELAY_VIDEO_PORT), 8000)
                    if (!running) {
                        try { cs.close() } catch (_: Exception) {}
                        return
                    }
                    socket = cs
                    Log.i(TAG, "中继通道已建立 $relayHost:${RelayCommands.RELAY_VIDEO_PORT}，发送PUSHER认证")
                    val out = cs.getOutputStream()
                    out.write("PUSHER:$safeClientId\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                    startCapture(cs, out, safeFps, safeQuality, safeMaxW, safeMaxH)
                    return
                } catch (e: Exception) {
                    if (!running) return
                    Log.e(TAG, "中继通道不可用(${e.message})，回退本机监听等待控制端直连")
                }
            }
            // —— B. 直连/TS直连（本端监听），或 A 失败后的兜底
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", RelayCommands.RELAY_VIDEO_PORT))
                serverSocket = ss
                Log.i(TAG, "直连通道已监听 ${RelayCommands.RELAY_VIDEO_PORT}，等待控制端接入...")
                val accepted = if (running) ss.accept() else null
                if (accepted != null) {
                    accepted.tcpNoDelay = true
                    socket = accepted
                    Log.i(TAG, "控制端已直连接入屏幕推流: ${accepted.inetAddress.hostAddress}")
                    val out = accepted.getOutputStream()
                    startCapture(accepted, out, safeFps, safeQuality, safeMaxW, safeMaxH)
                }
            } catch (e2: Exception) {
                if (running) Log.e(TAG, "屏幕推流启动失败: ${e2.message}")
            }
    }

    /** 停止屏幕推流 */
    @Synchronized
    fun stop() {
        // ★ 先交还所有权：本方法自己负责全部清理。采集线程随后退出时 CAS 会失败，
        //   就不会在"stop() 之后紧跟着新 startStream"的场景里把新会话的资源一起清掉。
        ownerRef.set(null)
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            mediaProjectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Exception) {
        }
        mediaProjectionCallback = null
        try {
            streamThread?.join(2000)
        } catch (_: Exception) {
        }
        streamThread = null
        Log.i(TAG, "屏幕推流已停止")
    }

    /** 释放授权（服务端关闭时调用） */
    fun releaseProjection() {
        stop()
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
    }

    private fun startCapture(s: Socket, out: OutputStream, fps: Int, quality: Int,
                             maxW: Int, maxH: Int) {
        val proj = projection ?: return
        val metrics = cn.ppps.forwarder.App.context.resources.displayMetrics
        // ★ 2026-08-06修复：限制推流分辨率（原代码直接用全屏尺寸1080x2400，每帧JPEG 200KB+，
        //   弱网（TS隧道丢包/高延迟）下TCP写阻塞帧传不出去→黑屏）。
        //   按比例缩放到上限以内，帧体积缩小数倍，弱网传输成功率大增。
        // ★★★ 2026-10-05【画面质量】上限改为**控制端下发**（原为写死的 DEFAULT_MAX_WIDTH/HEIGHT），
        //   仍只做"上限"用（scale 取 min(...,1f)，只缩不放）⇒ 给小尺寸永远安全。
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val scale = minOf(maxW.toFloat() / width, maxH.toFloat() / height, 1f)
        if (scale < 1f) {
            width = (width * scale).toInt().coerceAtLeast(320)
            height = (height * scale).toInt().coerceAtLeast(320)
            Log.i(TAG, "推流分辨率: ${metrics.widthPixels}x${metrics.heightPixels} -> $width x $height")
        }
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val display = proj.createVirtualDisplay(
            "ScreenPreview",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null,
        )
        virtualDisplay = display

        // MediaProjection 失效时自动停止
        // ★ 修复：回调必须绑定有 Looper 的 Handler（startCapture 运行在无 Looper 的后台线程，
        //   registerCallback 的 handler 传 null 会抛 "Can't create handler inside thread" 崩溃）
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection 已停止，结束推流")
                running = false
            }
        }
        proj.registerCallback(callback, Handler(Looper.getMainLooper()))
        mediaProjectionCallback = callback

        val intervalMs = 1000L / fps
        var lastSend = 0L
        lastSendTime = System.currentTimeMillis()
        // ★ 2026-08-06新增写阻塞看门狗：TCP写无超时，弱网下write可能永久阻塞，
        //   导致线程卡死+ running恒true，控制端重试永远走"已在运行"分支→永久黑屏。
        //   超过 WRITE_WATCHDOG_MS 未成功发出帧则强制断开清理，让下一次重试能重建推流。
        val sessionThread = Thread.currentThread()
        val watchdog = Thread({
            while (running) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
                // ★ 2026-09-04 只盯"本次会话"：sleep(3000) 期间可能已 stop() 或换了新会话，
                //   而 running / lastSendTime 都是全局字段，旧看门狗一旦醒来就会把新会话置停。
                if (ownerRef.get() !== sessionThread) break
                if (running && System.currentTimeMillis() - lastSendTime > WRITE_WATCHDOG_MS) {
                    Log.w(TAG, "屏幕推流写阻塞超时（${WRITE_WATCHDOG_MS / 1000}s无帧发出），强制断开等待重试")
                    running = false
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }, "ScreenStreamWatchdog").apply { isDaemon = true }.also { it.start() }
        try {
            // ★ 2026-08-28 省电：采集循环的等待策略。
            //   旧实现在两种情况下都固定 Thread.sleep(5)：
            //     a) 未到帧间隔 → 每帧要空转 (intervalMs/5) 次；fps=15 时 = 13 次/帧 ≈ 200 次/秒唤醒，
            //        纯烧 CPU 却不产出任何帧（灭屏/静止画面时 ImageReader 根本没有新镜像）；
            //     b) acquireLatestImage() 返回 null（屏幕没变化或已灭屏）→ 同样 200 次/秒空转，
            //        一直持续到 10 秒写阻塞看门狗把整条流拆掉为止。
            //   现在改为：a) 直接睡到下一个帧节拍（上限 PACE_SLEEP_MAX_MS，保证 stop() 仍能秒级响应）；
            //   b) 无新帧时 5→25→50ms 退避（最多让画面晚 50ms 被抓到，人手不可见）。
            //   输出帧率、分辨率、JPEG 质量、发送格式全部不变，控制端预览不受影响。
            var noImageWaits = NO_IMAGE_WAIT_MS
            while (running && !s.isClosed) {
                val now = System.currentTimeMillis()
                val waitLeft = intervalMs - (now - lastSend)
                if (waitLeft > 0) {
                    Thread.sleep(minOf(waitLeft, PACE_SLEEP_MAX_MS))
                    continue
                }
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
                if (image == null) {
                    // ★★★ 2026-10-07【静止不传输 · 保活】ImageReader 在静止/灭屏时常拿不到新镜像，
                    //   原实现此时什么都不做 ⇒ 既白等，又会让**本模块自己的** WRITE_WATCHDOG_MS(10s)
                    //   判成"10 秒没成功发帧"而强拆流（现网既有风险，审计已指出）。
                    //   ⇒ 静止时按保活周期（2s）重发**上一帧缓存**：不重新采集/编码，代价极小；
                    //     既实现"没有变化不传新数据"，又让本端写看门狗与控制端的停滞检测都看到"链路活着"。
                    val cached = FrameChangeDetector.screen.lastSentBytes
                    if (cached != null && FrameChangeDetector.screen.shouldSendBytes(cached)) {
                        try {
                            // ★ 保活帧也走同一帧格式（4 字节大端长度 + JPEG），控制端解析无需改动
                            synchronized(s) {
                                out.write(ByteBuffer.allocate(4).putInt(cached.size).array())
                                out.write(cached)
                                out.flush()
                            }
                            lastSend = System.currentTimeMillis()
                            lastSendTime = lastSend
                        } catch (_: Exception) {
                        }
                    }
                    // 无新帧：按 5ms → 25ms → 50ms（封顶）退避，避免灭屏/静止画面时 200次/秒空转
                    Thread.sleep(noImageWaits)
                    if (noImageWaits < NO_IMAGE_BACKOFF_MS) {
                        noImageWaits = minOf(noImageWaits * 5, NO_IMAGE_BACKOFF_MS)
                    }
                    continue
                }
                noImageWaits = NO_IMAGE_WAIT_MS
                // ★ 2026-10-05【自适应码率】用运行期自适应质量编码（而非启动时定死的 quality）
                val jpeg = imageToJpeg(image, qosQuality)
                image.close()
                if (jpeg == null || jpeg.isEmpty()) continue
                // ★ 2026-10-05【借鉴点④】冻结/退化帧检测（与 PC 侧 rd_stream_hub._check_degenerate 同口径）
                checkDegenerate(jpeg.size)
                // ★★★ 2026-10-07【静止不传输（自动变化检测）】与上一帧字节一致 ⇒ **不写 socket**，
                //   静止桌面/静止手机屏幕的带宽直接降到 ~0（到保活周期 2s 才发一帧）。
                //   ★ 位置在 checkDegenerate 之后、写 socket 之前：冻结帧检测仍然每帧都做（它靠"帧在流"判断）。
                if (!FrameChangeDetector.screen.shouldSendBytes(jpeg)) {
                    continue
                }
                // 帧格式: [4字节大端长度][JPEG]
                val header = ByteBuffer.allocate(4).putInt(jpeg.size).array()
                // ★ 测量"写出真实耗时"作为拥塞信号（write+flush，含 TCP 背压等待）
                val t0 = System.currentTimeMillis()
                synchronized(s) {
                    out.write(header)
                    out.write(jpeg)
                    out.flush()
                }
                val writeMs = System.currentTimeMillis() - t0
                updateQos(writeMs)
                lastSend = now
                lastSendTime = now
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "屏幕推流中断: ${e.message}")
        } finally {
            // ★★★ 2026-08-27 省电 + 缺陷修复：采集循环一结束（正常停止 / 写阻塞看门狗断开 / 对端掉线抛异常）
            //   必须立刻释放 VirtualDisplay 与 ImageReader 并把 running 归位。
            //   旧实现直接 return，导致：
            //    1) VirtualDisplay 仍带 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR，系统合成器持续把整屏镜像到
            //       一个没人读的 ImageReader 上（GPU + 内存拷贝长期占用，灭屏也不停），是实打实的耗电项；
            //    2) running 仍为 true → 控制端下一次 rdstrt 会命中"已在运行"分支直接返回 true，
            //       但实际上没有任何线程在推流（表现为"预览黑屏且再也起不来"）。
            //   这里同时中断看门狗线程，避免它在 running 已false 后继续空转 3 秒轮询。
            try { watchdog.interrupt() } catch (_: Exception) {}
            // ★ 2026-09-04 必须按"是否仍拥有本次会话"收尾：running=false 与 cleanup() 之间
            //   存在窗口——本线程刚置停，控制端的下一次 rdstrt 就能通过 startStream 的
            //   `if (running)` 检查并建立起新会话（socket/imageReader 字段已被新会话重新赋值），
            //   此时再执行这里的 cleanup() 就会把新会话的推流拆掉。所有权已交出去就什么都不做。
            if (ownerRef.get() === Thread.currentThread()) {
                running = false
                cleanup()
                Log.i(TAG, "屏幕推流已结束，VirtualDisplay/ImageReader 已释放")
            } else {
                Log.i(TAG, "采集循环结束时本会话已被接管，跳过清理（不误杀新推流）")
            }
        }
    }

    // ============================================================
    // ★★★ 2026-10-05【借鉴点④：冻结/退化帧检测】
    //   对齐 RustDesk src/server/vram.rs:120-142（AMF 连续 30 帧编码长度 <100 且**恒定** → 判卡死）。
    //   价值在于**发现"不报错的故障"**：采集/编码管线退化时既不抛异常也不超时、
    //   socket 依旧通畅，从常规指标看一切正常，只有"输出退化成常数"这一个可观测迹象。
    //   ★ 与"静止画面"不冲突：纯色 720p JPEG 也有数 KB，<100 字节且长度完全一致
    //     在正常编码下几乎不可能出现。
    // ============================================================
    // ★ 注意：本文件是 `object`（单例），**不能再声明 companion object**（Kotlin 禁止），
    //   常量直接写在对象体内即可（原写法会导致 "Object cannot have companion object" 编译失败）。
    /** 视为"退化输出"的字节上限 */
    private const val DEGENERATE_MAX_BYTES = 100
    /** 连续多少帧命中才告警 */
    private const val DEGENERATE_TRIGGER = 30

    private var degenerateRun = 0
    private var degenerateLastLen = -1

    /** ★ 2026-10-05【④】检测"编码/采集管线退化成常数输出"（不报错的故障）。 */
    private fun checkDegenerate(size: Int) {
        if (size < DEGENERATE_MAX_BYTES && size == degenerateLastLen) {
            degenerateRun++
            if (degenerateRun >= DEGENERATE_TRIGGER) {
                degenerateRun = 0
                Log.w(TAG, "[冻结检测] 连续 $DEGENERATE_TRIGGER 帧输出 $size 字节且长度恒定"
                        + " —— 疑似采集/编码管线卡死（画面可能已僵死但无异常抛出）")
            }
        } else {
            degenerateRun = 0
        }
        degenerateLastLen = size
    }

    /** RGBA_8888 图像转 JPEG
     *
     * ★ 2026-10-05【软编降分辨率】当 [qosHalfScale] 为真时先缩放一半再编码：
     *   像素数降到 1/4 ⇒ JPEG 编码耗时（libjpeg-turbo，成本 ∝ 像素数）与输出字节数同时大降。
     *   ★ 失败一律忽略并按原尺寸编码：降分辨率是"改善手段"，绝不能因为它把推流搞断。
     */
    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        var crop = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        if (bitmap != crop) bitmap.recycle()
        if (qosHalfScale) {
            try {
                val half = Bitmap.createScaledBitmap(
                    crop, maxOf(1, crop.width / 2), maxOf(1, crop.height / 2), true)
                if (half != crop) {
                    crop.recycle()
                    crop = half
                }
            } catch (t: Throwable) {
                Log.w(TAG, "半分辨率缩放失败(按原尺寸继续编码): ${t.message}")
            }
        }
        val bos = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        crop.recycle()
        return bos.toByteArray()
    }

    private fun cleanup() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
