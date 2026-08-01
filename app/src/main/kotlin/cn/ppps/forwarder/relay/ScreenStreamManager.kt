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
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * 被控端屏幕推流管理器
 *
 * 1. ServerFragment 通过 MediaProjection 授权后，把授权结果交给 ScreenProjectionService，
 *    ScreenProjectionService 调用 [setProjection] 保存 MediaProjection。
 * 2. 收到控制端 rdstrt000000 命令后调用 [startStream]：
 *    - 主动连接中继 56788 端口
 *    - 发送 PUSHER:clientId\n 认证标签（clientId = 被控端pc_id，与控制端 LISTENER 配对）
 *    - 通过 VirtualDisplay + ImageReader 采集屏幕帧
 *    - 每帧发送 [4字节大端JPEG长度][JPEG二进制]
 * 3. 屏幕画面仅推送到控制端，不在被控端本地显示。
 */
object ScreenStreamManager {

    private const val TAG = "ScreenStreamManager"

    /** 推流分辨率上限 */
    private const val MAX_WIDTH = 1280
    private const val MAX_HEIGHT = 720

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var running = false
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
     * @return 是否成功启动
     */
    @Synchronized
    fun startStream(relayHost: String, clientId: Int, fps: Int, quality: Int): Boolean {
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

        running = true
        streamThread = Thread({
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(relayHost, RelayCommands.RELAY_VIDEO_PORT), 8000)
                if (!running) {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                    return@Thread
                }
                socket = s
                Log.i(TAG, "已连接中继视频流端口 $relayHost:${RelayCommands.RELAY_VIDEO_PORT}")
                // 发送 PUSHER 认证标签
                val out = s.getOutputStream()
                out.write("PUSHER:$safeClientId\n".toByteArray(Charsets.UTF_8))
                out.flush()
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
        val width = metrics.widthPixels
        val height = metrics.heightPixels
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
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
