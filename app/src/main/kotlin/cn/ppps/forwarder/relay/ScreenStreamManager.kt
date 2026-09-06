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

    /** 推流分辨率上限（★ 2026-08-06修复：原代码定义了但未使用，全屏1080x2400推流在弱网下帧过大TCP写阻塞→黑屏） */
    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720

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
                    channel: Int = cn.ppps.forwarder.relay.RelayServerHandler.CHANNEL_RELAY): Boolean {
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
                runStreamSession(relayHost, safeClientId, safeFps, safeQuality)
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
    private fun runStreamSession(relayHost: String, safeClientId: Int, safeFps: Int, safeQuality: Int) {
            // ★★★ 2026-08-15 屏幕预览"无图像"修复（v2 智能模式）：
            //   【根因】控制端(华为)请求走TS直连通道 → 本端按channel监听56888等直连；但控制端实际
            //     连的是中继服务器56888（getVideoHostForDevice对TS设备取被控端Tailscale IP）→ 两端通道不匹配 → 控制端永远收不到帧（无图像）。
            //   【修复·智能模式】优先中继PUSHER（控制端中继优先必配对成功）；仅当中继连接失败
            //     （端口不通/超时）才回退直连监听——两端主/兜底通道对应，无闲置线程。
            var usedRelay = false
            try {
                val cs = Socket()
                cs.tcpNoDelay = true
                cs.connect(InetSocketAddress(relayHost, RelayCommands.RELAY_VIDEO_PORT), 8000)
                if (!running) {
                    try { cs.close() } catch (_: Exception) {}
                    return
                }
                usedRelay = true
                socket = cs
                Log.i(TAG, "中继通道已建立 $relayHost:${RelayCommands.RELAY_VIDEO_PORT}，发送PUSHER认证")
                val out = cs.getOutputStream()
                out.write("PUSHER:$safeClientId\n".toByteArray(Charsets.UTF_8))
                out.flush()
                startCapture(cs, out, safeFps, safeQuality)
            } catch (e: Exception) {
                if (usedRelay) {
                    // 中继已连但推流中断（写阻塞看门狗/断流）：仅记录，不重复回退
                    if (running) Log.e(TAG, "中继推流中断: ${e.message}")
                } else {
                    // 中继连接失败 → 智能回退直连监听（等待控制端直接接入）
                    Log.e(TAG, "中继通道不可用(${e.message})，回退直连监听")
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
                            startCapture(accepted, out, safeFps, safeQuality)
                        }
                    } catch (e2: Exception) {
                        if (running) Log.e(TAG, "屏幕推流启动失败: ${e2.message}")
                    }
                }
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

    private fun startCapture(s: Socket, out: OutputStream, fps: Int, quality: Int) {
        val proj = projection ?: return
        val metrics = cn.ppps.forwarder.App.context.resources.displayMetrics
        // ★ 2026-08-06修复：限制推流分辨率（原代码直接用全屏尺寸1080x2400，每帧JPEG 200KB+，
        //   弱网（TS隧道丢包/高延迟）下TCP写阻塞帧传不出去→黑屏）。
        //   按比例缩放到 MAX_WIDTH/MAX_HEIGHT 以内，帧体积缩小数倍，弱网传输成功率大增。
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val scale = minOf(MAX_WIDTH.toFloat() / width, MAX_HEIGHT.toFloat() / height, 1f)
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
                    // 无新帧：按 5ms → 25ms → 50ms（封顶）退避，避免灭屏/静止画面时 200次/秒空转
                    Thread.sleep(noImageWaits)
                    if (noImageWaits < NO_IMAGE_BACKOFF_MS) {
                        noImageWaits = minOf(noImageWaits * 5, NO_IMAGE_BACKOFF_MS)
                    }
                    continue
                }
                noImageWaits = NO_IMAGE_WAIT_MS
                val jpeg = imageToJpeg(image, quality)
                image.close()
                if (jpeg == null || jpeg.isEmpty()) continue
                // 帧格式: [4字节大端长度][JPEG]
                val header = ByteBuffer.allocate(4).putInt(jpeg.size).array()
                synchronized(s) {
                    out.write(header)
                    out.write(jpeg)
                    out.flush()
                }
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

    /** RGBA_8888 图像转 JPEG */
    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        val crop = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        if (bitmap != crop) bitmap.recycle()
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
