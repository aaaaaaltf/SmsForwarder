package cn.ppps.forwarder.relay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import cn.ppps.forwarder.utils.Log
import org.webrtc.CapturerObserver
import org.webrtc.JavaI420Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame

/**
 * ★★★ 2026-10-04 新增：屏幕 WebRTC 高清模式的**采集器**（org.webrtc.VideoCapturer 实现）。
 *
 * 用途：`WebRtcSessionManager` 在屏幕会话(screenMode=true)里用它替代 `Camera2Capturer`，
 * 把手机屏幕经 MediaProjection 采集 → RGBA_8888 → I420 → `onFrameCaptured` 交给 WebRTC 编码。
 * 控制端因此能用 WebRTC(H264/VP8 可变码率 + 抗丢包 + JitterBuffer)看屏，而不是逐帧 JPEG。
 *
 * ★ 为什么自研而不是用官方 `ScreenCapturerAndroid`：
 *   官方构造器要 `(Intent resultData, Callback)` 并**内部再调一次 getMediaProjection**——
 *   而本 App 已经有一个活着的 MediaProjection 实例（`ScreenStreamManager`，JPEG 预览在用它），
 *   Android 14+ 对同一授权 token 二次 `getMediaProjection` 会抛异常。
 *   这里改为**借用现有实例**（`ScreenStreamManager.currentProjection()`），只自建
 *   VirtualDisplay/ImageReader，停止时只释放自己建的东西，不碰投影实例。
 *
 * ★ 分辨率/尺寸口径与 JPEG 通道完全一致（见 `ScreenStreamManager.startCapture`）：
 *   按屏幕真实尺寸等比缩放到请求的 (w,h) 框内，且宽高取偶数（I420 要求偶数采样）。
 *   等比缩放保证不变形（直接把 VirtualDisplay 建在 720p 上会拉伸画面）。
 *
 * ★ 耗电：停止时必须释放 VirtualDisplay/ImageReader —— 带 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
 *   的 VirtualDisplay 会持续把整屏镜像到没人读的 ImageReader 上（GPU + 内存带宽），
 *   这是 2026-08-27 在 JPEG 通道上已经踩过的坑，这里照抄同款收尾。
 */
/**
 * ★★★ 2026-10-05 新增 maxW/maxH：控制端设置页「手机被控端屏幕预览」档位下发的
 * **人为分辨率上限**（经 OFFER 首段 `screen|<fps>|<maxW>|<maxH>|<b64>` 传入，
 * 见 RelayServerHandler.CMD_WEBRTC_OFFER 的解析）。
 * 默认值即改造前写死的 1280×720 ⇒ 控制端不带该段时行为逐字节不变。
 */
class ScreenWebRtcCapturer(private val appContext: Context,
                           private val maxW: Int = MAX_WIDTH,
                           private val maxH: Int = MAX_HEIGHT) : VideoCapturer {

    companion object {
        private const val TAG = "ScreenWebRtcCapturer"

        /** 采集上限的**默认值**（与 JPEG 通道的 DEFAULT_MAX_WIDTH/HEIGHT 同口径）。
         *  ★★★ 2026-10-05【画面质量】改为"默认值"：控制端设置页「手机被控端屏幕预览」档位
         *  会经 OFFER 首段 `screen|<fps>|<maxW>|<maxH>|<b64>` 下发（见 RelayServerHandler
         *  的 CMD_WEBRTC_OFFER 解析），由构造参数覆盖。控制端不带 ⇒ 用默认值，行为不变。 */
        private const val MAX_WIDTH = 1280
        private const val MAX_HEIGHT = 720

        /** I420 要求宽高为偶数 */
        private fun evenDown(v: Int): Int = if (v % 2 == 0) v else v - 1
    }

    private var helper: SurfaceTextureHelper? = null
    private var observer: CapturerObserver? = null

    @Volatile
    private var running = false
    @Volatile
    private var width = MAX_WIDTH
    @Volatile
    private var height = MAX_HEIGHT

    /** 目标帧间隔（ns）：由 startCapture 的 framerate 换算，用于节流（送多了只会白耗 CPU/带宽） */
    @Volatile
    private var frameIntervalNs = 100_000_000L

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** 复用的行缓冲，避免每帧新建大数组（GC 抖动会让编码帧率不稳） */
    private var rgbaRow: ByteArray? = null
    private var yRow: ByteArray? = null
    private var uRow: ByteArray? = null
    private var vRow: ByteArray? = null

    @Volatile
    private var lastPushedNs = 0L

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        helper = surfaceTextureHelper
        observer = capturerObserver
        Log.i(TAG, "initialize: helper=${surfaceTextureHelper != null} observer=${capturerObserver != null}")
    }

    override fun isScreencast(): Boolean = true

    override fun startCapture(width: Int, height: Int, framerate: Int) {
        if (running) {
            Log.w(TAG, "startCapture 已在运行，忽略重复调用")
            return
        }
        val obs = observer
        if (obs == null) {
            Log.e(TAG, "startCapture 失败：未 initialize（observer=null）")
            return
        }
        val proj = ScreenStreamManager.currentProjection()
        if (proj == null) {
            // ★ 未授权/投影已失效：必须让上层明确回退到 JPEG（并提示授权），不能静默黑屏
            Log.e(TAG, "startCapture 失败：屏幕捕获未授权（MediaProjection 为空）")
            try {
                obs.onCapturerStarted(false)
            } catch (_: Throwable) {
            }
            return
        }

        // —— 目标分辨率：按屏幕真实尺寸等比缩放到请求框内（口径同 JPEG 通道，避免画面变形）
        val metrics = appContext.resources.displayMetrics
        val dw = metrics.widthPixels.coerceAtLeast(1)
        val dh = metrics.heightPixels.coerceAtLeast(1)
        // ★★★ 2026-10-05【画面质量】再叠加控制端下发的**人为上限**（maxW/maxH）。
        //   改动前 scale 只受"请求尺寸"（width/height 参数，来自 WebRTC 协商值）约束，
        //   于是设置里调到"省流"对手机被控端的高清通路**毫无影响**。
        //   现在取 min(请求尺寸, 人为上限, 1f)：两者都只缩不放 ⇒ 给小值永远安全，
        //   小手机屏幕（本身小于上限）也不会被放大导致模糊。
        val scale = minOf(width.toFloat() / dw, height.toFloat() / dh,
                          maxW.toFloat() / dw, maxH.toFloat() / dh, 1f)
        // ★ 注意：本方法的参数 width/height 与类的字段同名，赋值/读取字段必须写 this. 前缀
        //   （首版漏写 → Kotlin 报 "Val cannot be reassigned"，因为它赋值到的是参数 val）。
        this.width = evenDown((dw * scale).toInt().coerceAtLeast(320))
        this.height = evenDown((dh * scale).toInt().coerceAtLeast(320))
        Log.i(TAG, "屏幕高清采集尺寸: 屏幕 ${dw}x${dh} → ${this.width}x${this.height}"
                + "（人为上限 ${maxW}x${maxH}，请求尺寸 ${width}x${height}）")
        frameIntervalNs = (1_000_000_000L / framerate.coerceIn(1, 60))
        lastPushedNs = 0L
        rgbaRow = ByteArray(this.width * 4)
        yRow = ByteArray(this.width)
        uRow = ByteArray(this.width / 2)
        vRow = ByteArray(this.width / 2)

        val t = HandlerThread("ScreenWebRtcCap").apply { start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        try {
            val r = ImageReader.newInstance(this.width, this.height, PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ onImageAvailable(it) }, h)

            val density = metrics.densityDpi
            virtualDisplay = proj.createVirtualDisplay(
                "ScreenWebRtc",
                this.width, this.height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, h,
            )
            projection = proj
            // 投影被系统/用户停止 → 自己收尾（不释放投影实例，只停本采集器）
            val cb = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(TAG, "MediaProjection 已被停止 → 结束屏幕采集")
                    try {
                        stopCapture()
                    } catch (_: Throwable) {
                    }
                }
            }
            projectionCallback = cb
            proj.registerCallback(cb, Handler(Looper.getMainLooper()))
            running = true
            Log.i(TAG, "★ 屏幕采集已启动：${this.width}x${this.height} @${framerate}fps（等比缩放自 ${dw}x$dh）")
            obs.onCapturerStarted(true)
        } catch (t2: Throwable) {
            Log.e(TAG, "startCapture 异常: ${t2.message}", t2)
            releaseCaptureResources()
            try {
                obs.onCapturerStarted(false)
            } catch (_: Throwable) {
            }
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // 分辨率变化需要重建 VirtualDisplay/ImageReader；这里只调整帧率（画质档位切换用），
        // 分辨率由会话建立时的请求决定（与 JPEG 通道 rdstrt 一次定档的口径一致）。
        frameIntervalNs = (1_000_000_000L / framerate.coerceIn(1, 60))
        Log.i(TAG, "changeCaptureFormat: 仅更新帧率 → ${framerate}fps（分辨率保持 ${this.width}x${this.height}）")
    }

    override fun stopCapture() {
        if (!running && reader == null && virtualDisplay == null) return
        running = false
        releaseCaptureResources()
        try {
            observer?.onCapturerStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "onCapturerStopped 回调异常: ${t.message}")
        }
        Log.i(TAG, "屏幕采集已停止，VirtualDisplay/ImageReader 已释放（投影实例保持给 JPEG 通道用）")
    }

    override fun dispose() {
        try {
            stopCapture()
        } catch (_: Throwable) {
        }
        observer = null
        helper = null
        Log.i(TAG, "dispose 完成")
    }

    private fun releaseCaptureResources() {
        val proj = projection
        val cb = projectionCallback
        if (proj != null && cb != null) {
            try {
                proj.unregisterCallback(cb)
            } catch (_: Throwable) {
            }
        }
        projectionCallback = null
        projection = null
        try {
            virtualDisplay?.release()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        val t = thread
        thread = null
        handler = null
        try {
            t?.quitSafely()
        } catch (_: Throwable) {
        }
    }

    /** 采集线程：取最新帧 → RGBA 转 I420 → 交给 WebRTC（节流到目标帧率） */
    private fun onImageAvailable(r: ImageReader) {
        val image = try {
            r.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return
        try {
            if (!running) return
            val nowNs = System.nanoTime()
            if (nowNs - lastPushedNs < frameIntervalNs) return
            lastPushedNs = nowNs
            val obs = observer ?: return
            val w = width
            val h = height
            val i420 = JavaI420Buffer.allocate(w, h)
            try {
                convertRgbaToI420(image, i420, w, h)
                // ★ 帧只在投递的 Runnable 内部构造：这样缓冲区的所有权归属唯一
                //   （避免"构造了 frame 但 post 抛异常"时出现重复/遗漏 release 的悬垂引用）。
                val deliver = Runnable {
                    val frame = VideoFrame(i420, 0, nowNs)
                    try {
                        obs.onFrameCaptured(frame)
                    } catch (t: Throwable) {
                        Log.w(TAG, "onFrameCaptured 异常: ${t.message}")
                    } finally {
                        frame.release()
                    }
                }
                // ★ 在 surfaceTextureHelper 的线程上回调：与 WebRTC 采集口径一致
                //   （WebRTC 期望采集回调发生在该线程；直接在本线程回调线程亲和性没保证）。
                val sth = helper
                if (sth != null) sth.handler.post(deliver) else deliver.run()
            } catch (t: Throwable) {
                Log.w(TAG, "送帧失败(忽略本帧): ${t.message}")
                try {
                    i420.release()
                } catch (_: Throwable) {
                }
            }
        } finally {
            try {
                image.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * RGBA_8888(ImageReader) → I420。
     * 逐行读 RGBA（bulk get，避免每像素 JNI 调用），行内循环算 Y，偶数行偶数点算 U/V，
     * 再用 bulk put 写回各平面 —— 1280x720 约 2~4ms/帧，10~15fps 下 CPU 占用可接受。
     */
    private fun convertRgbaToI420(image: Image, dst: JavaI420Buffer, w: Int, h: Int) {
        val plane = image.planes[0]
        val src = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rRow = rgbaRow ?: ByteArray(w * 4).also { rgbaRow = it }
        val yR = yRow ?: ByteArray(w).also { yRow = it }
        val uR = uRow ?: ByteArray(w / 2).also { uRow = it }
        val vR = vRow ?: ByteArray(w / 2).also { vRow = it }

        val dY = dst.dataY
        val dU = dst.dataU
        val dV = dst.dataV
        val sY = dst.strideY
        val sU = dst.strideU
        val sV = dst.strideV

        val readLen = minOf(rowStride, rRow.size)
        for (j in 0 until h) {
            val base = j * rowStride
            if (base >= src.capacity()) break
            src.position(base)
            val n = minOf(readLen, src.remaining())
            if (n <= 0) break
            src.get(rRow, 0, n)

            var o = 0
            for (i in 0 until w) {
                if (o + 2 >= n) {
                    // 行尾不足（最后一行常见）：重复上一像素，避免越界
                    yR[i] = if (i > 0) yR[i - 1] else 16
                } else {
                    val r = rRow[o].toInt() and 0xFF
                    val g = rRow[o + 1].toInt() and 0xFF
                    val b = rRow[o + 2].toInt() and 0xFF
                    yR[i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
                    if ((j and 1) == 0 && (i and 1) == 0) {
                        uR[i shr 1] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128)
                            .coerceIn(0, 255).toByte()
                        vR[i shr 1] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128)
                            .coerceIn(0, 255).toByte()
                    }
                }
                o += pixelStride
            }
            dY.position(j * sY)
            dY.put(yR, 0, w)
            if ((j and 1) == 0) {
                dU.position((j shr 1) * sU)
                dU.put(uR, 0, w / 2)
                dV.position((j shr 1) * sV)
                dV.put(vR, 0, w / 2)
            }
        }
    }
}
