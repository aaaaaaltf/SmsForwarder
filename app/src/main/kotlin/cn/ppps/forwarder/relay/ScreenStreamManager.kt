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
 *    - ★ ZeroTier直连模式（RelaySettings.directMode=true）：监听 56788 端口，接受控制端直接连接（无需中继）
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

    /** 写阻塞看门狗阈值：超过该时间未成功发完一帧则强制断开，允许控制端重试重建推流（★ 2026-08-06新增） */
    private const val WRITE_WATCHDOG_MS = 10000L

    /** 最近一次成功发送帧的时间戳（看门狗用） */
    @Volatile
    private var lastSendTime = 0L

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var running = false
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
     * @param channel 命令来源通道：RelayServerHandler.CHANNEL_RELAY=中继 / CHANNEL_DIRECT=直连监听(56786) / CHANNEL_ZT=ZT直连(56789)
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
        //   命令经直连监听(56786)/ZT直连(56789)到达 → 监听56788等待控制端直连收流。
        //   原逻辑用 RelayServerService.isConnected 判断，被控端"始终连接中继"后恒为中继推流，
        //   控制端直连模式（云服务不可达）下连被控端56788无人监听 → 预览失败。
        val direct = channel != cn.ppps.forwarder.relay.RelayServerHandler.CHANNEL_RELAY

        running = true
        streamThread = Thread({
            try {
                val s: Socket
                val out: OutputStream
                if (direct) {
                    // ★ ZeroTier直连模式：监听 56788 端口，接受控制端直接连接（无需中继）
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress("0.0.0.0", RelayCommands.RELAY_VIDEO_PORT))
                    serverSocket = ss
                    Log.i(TAG, "屏幕推流已监听 ${RelayCommands.RELAY_VIDEO_PORT}，等待控制端接入...")
                    val accepted = if (running) ss.accept() else null
                    if (accepted == null) {
                        return@Thread
                    }
                    accepted.tcpNoDelay = true
                    s = accepted
                    socket = s
                    Log.i(TAG, "控制端已接入屏幕推流: ${s.inetAddress.hostAddress}")
                    out = s.getOutputStream()
                } else {
                    // 中继模式：主动连接中继 56788 + PUSHER 认证
                    val cs = Socket()
                    cs.tcpNoDelay = true
                    cs.connect(InetSocketAddress(relayHost, RelayCommands.RELAY_VIDEO_PORT), 8000)
                    if (!running) {
                        try {
                            cs.close()
                        } catch (_: Exception) {
                        }
                        return@Thread
                    }
                    s = cs
                    socket = s
                    Log.i(TAG, "已连接中继视频流端口 $relayHost:${RelayCommands.RELAY_VIDEO_PORT}")
                    out = s.getOutputStream()
                    out.write("PUSHER:$safeClientId\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                startCapture(s, out, safeFps, safeQuality)
            } catch (e: Exception) {
                Log.e(TAG, "屏幕推流启动失败: ${e.message}")
            } finally {
                cleanup()
                running = false
            }
        }, "ScreenStreamThread").apply { isDaemon = true }.also { it.start() }
        return true
    }

    /** 停止屏幕推流 */
    @Synchronized
    fun stop() {
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
        //   弱网（ZT隧道丢包/高延迟）下TCP写阻塞帧传不出去→黑屏）。
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
        val watchdog = Thread({
            while (running) {
                try {
                    Thread.sleep(3000)
                } catch (_: InterruptedException) {
                    break
                }
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
            while (running && !s.isClosed) {
                val now = System.currentTimeMillis()
                if (now - lastSend < intervalMs) {
                    Thread.sleep(5)
                    continue
                }
                val image = try {
                    reader.acquireLatestImage()
                } catch (_: Exception) {
                    null
                }
                if (image == null) {
                    Thread.sleep(5)
                    continue
                }
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
