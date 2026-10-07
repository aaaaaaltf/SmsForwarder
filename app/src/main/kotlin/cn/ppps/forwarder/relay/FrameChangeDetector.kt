package cn.ppps.forwarder.relay

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

/**
 * ★★★ 2026-10-07【静止画面不传输（自动变化检测）· 手机侧两路共用实现】
 *
 * 需求：手机摄像头 / 手机屏幕预览在图像**没有变化时不传数据**，检测到变化才传，自动检测。
 *
 * ★ 为什么必须配套"保活帧"（本文件最重要的设计取舍）：
 *   控制端有大量"画面停滞"检测 —— 8 秒零帧回退（WebRtcSession.armZeroFrameFallbackWatch）、
 *   JPEG 通道首帧/停滞看门狗（ScreenPreviewActivity.firstFrameRunnable / FRAME_STALL_TIMEOUT_MS）、
 *   远程桌面的重试链等。若静止时**真的一帧不发**，这些检测会把"静止"误判成"链路故障"
 *   并触发回退/重建 —— 比浪费带宽更糟。
 *   ⇒ 本实现保证：**静止时至少每 keepaliveMs 强发一帧**（默认 2 秒 ≈ 0.5fps），
 *     既让所有看门狗看到"链路活着"，又把静止期带宽压到原来的 ~1/30。
 *
 * ★ 判据：下采样灰度 + 平均绝对差（0~255）。用**下采样**而非逐像素，是为了忽略
 *   传感器噪声/编码抖动（逐像素比较会因 ±1 级噪声永不判"相同"）。
 *
 * ★ 用法（每个流一个实例，通常在 start() 时 new 一个）：
 *      private var idle = FrameChangeDetector("phone_screen")
 *      ...
 *      if (!idle.shouldSend(bitmap)) { image.close(); return }   // 静止：不发
 *      sendJpeg(...)
 */
class FrameChangeDetector(
    private val name: String,
    /** 判为"有变化"的平均绝对差阈值（灰阶 0~255）：1.5 ≈ 每格平均变化 1.5 级 */
    private val threshold: Float = 1.5f,
    /** 静止时最长静默时间：到点强发一帧（保活，防控制端误判链路故障） */
    private val keepaliveMs: Long = 2000L,
    private val gridW: Int = 48,
    private val gridH: Int = 27,
    @Volatile var enabled: Boolean = true
) {
    private var prev: IntArray? = null
    private var lastSentAt = 0L

    /** 统计（诊断用：跳过率能直接反映阈值是否合适） */
    @Volatile var sent = 0
    @Volatile var skipped = 0
    @Volatile var keptAlive = 0

    /** @return true = 该发（有变化 / 到保活点 / 首次）；false = 静止，跳过 */
    @Synchronized
    fun shouldSend(bmp: Bitmap?, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!enabled) return true
        if (bmp == null || bmp.isRecycled || bmp.width <= 0 || bmp.height <= 0) return true
        val sig = try {
            signature(bmp)
        } catch (t: Throwable) {
            Log.w(TAG, "变化检测签名失败($name): ${t.message}")
            return true                      // 算不出 ⇒ 宁可多发，不可少发
        } ?: return true
        val p = prev
        if (p == null) {
            prev = sig; lastSentAt = now; sent++
            return true
        }
        if (meanAbsDiff(p, sig) >= threshold) {
            prev = sig; lastSentAt = now; sent++
            return true
        }
        if (now - lastSentAt >= keepaliveMs) {
            // ★ 保活帧：画面没变也必须发，否则控制端的停滞检测会误判为链路故障
            lastSentAt = now; keptAlive++; sent++
            return true
        }
        skipped++
        return false
    }

    /** 换流/换源时调用：清掉上一路的历史签名，避免拿旧画面比新画面 */
    @Synchronized
    fun reset() {
        prev = null
        lastSentAt = 0L
    }

    fun statsLine(): String {
        val total = sent + skipped
        val rate = if (total > 0) 100f * skipped / total else 0f
        return "[静止跳过/$name] 已发 $sent（保活 $keptAlive） 跳过 $skipped（${"%.1f".format(rate)}%）"
    }

    // ---------------- 内部 ----------------
    private fun signature(bmp: Bitmap): IntArray? {
        val small = Bitmap.createScaledBitmap(bmp, gridW, gridH, false) ?: return null
        val px = IntArray(gridW * gridH)
        small.getPixels(px, 0, gridW, 0, 0, gridW, gridH)
        if (small !== bmp) small.recycle()
        val out = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            // BT.601 亮度：与 PC 侧判据一致，避免两端对"变化"的定义漂移
            out[i] = ((299 * r + 587 * g + 114 * b) / 1000)
        }
        return out
    }

    private fun meanAbsDiff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size) return 255f
        var sum = 0L
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum.toFloat() / a.size
    }

    /** ★ 最近一次**已发送**的字节（供"静止时重发上一帧"的保活路径用，不必再采集/编码） */
    @Volatile
    var lastSentBytes: ByteArray? = null
        private set

    /**
     * ★ 字节快路径（Kotlin 两路常态：JPEG 字节已就绪）：字节**完全相等**即"画面未变"。
     *   为什么可以直接比字节：JPEG 编码是确定性的 —— 同一画面 + 同一质量参数 ⇒ 输出字节完全一致。
     *   比"转 Bitmap 再下采样"便宜得多（手机 CPU 上 15~30fps 做 Bitmap 缩放/取像素是实打实的开销）。
     */
    @Synchronized
    fun shouldSendBytes(jpeg: ByteArray?, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!enabled) return true
        if (jpeg == null || jpeg.isEmpty()) return true
        val p = lastSentBytes
        if (p == null || !jpeg.contentEquals(p)) {
            lastSentBytes = jpeg; lastSentAt = now; sent++
            return true
        }
        if (now - lastSentAt >= keepaliveMs) {
            lastSentAt = now; keptAlive++      // ★ 保活帧（不更新缓存：内容与上一帧相同）
            return true
        }
        skipped++
        return false
    }

    /** 共用决策：与 Bitmap 路径完全同一套判据（变化立即发 / 静止到保活点发 / 否则丢） */
    private fun decide(sig: IntArray, now: Long): Boolean {
        val p = prev
        if (p == null) {
            prev = sig; lastSentAt = now; sent++
            return true
        }
        if (meanAbsDiff(p, sig) >= threshold) {
            prev = sig; lastSentAt = now; sent++
            return true
        }
        if (now - lastSentAt >= keepaliveMs) {
            lastSentAt = now; keptAlive++
            return true
        }
        skipped++
        return false
    }

    /**
     * ★★★ 2026-10-07【屏幕 WebRTC：从 RGBA_8888 平面直接采样】—— 供 ScreenWebRtcCapturer 用。
     *   为什么这样采样：该路采集是 VirtualDisplay → ImageReader(RGBA_8888)，整帧 RGBA 拷贝是最贵的一步；
     *   固定 48x27 网格**只读采样点**（不到 1300 次 get），判为静止时连 RGBA→I420 转换都省掉。
     *   ★ 采样失败/越界一律放行（宁可多传，不可把画面卡住）。
     */
    @Synchronized
    fun shouldSendRgba(image: android.media.Image, now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (!enabled) return true
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pxStride = maxOf(1, plane.pixelStride)
            val w = image.width
            val h = image.height
            if (w <= 0 || h <= 0) return true
            val sig = IntArray(gridW * gridH)
            var i = 0
            for (gy in 0 until gridH) {
                val y = gy * (h - 1) / maxOf(1, gridH - 1)
                val rowBase = y * rowStride
                for (gx in 0 until gridW) {
                    val x = gx * (w - 1) / maxOf(1, gridW - 1)
                    val idx = rowBase + x * pxStride
                    if (idx + 2 >= buf.limit()) {
                        sig[i++] = 0
                        continue
                    }
                    val r = buf.get(idx).toInt() and 0xFF
                    val g = buf.get(idx + 1).toInt() and 0xFF
                    val b = buf.get(idx + 2).toInt() and 0xFF
                    sig[i++] = (299 * r + 587 * g + 114 * b) / 1000
                }
            }
            decide(sig, now)
        } catch (t: Throwable) {
            true
        }
    }

    

    companion object {
        private const val TAG = "FrameChange"

        /**
         * ★★★ 2026-10-07【屏幕路的共用实例】——**只保留屏幕**。
         *   摄像头路已按用户决定**完全不做静止抑制**：真机实测"降帧率"让 20fps 档位只跑到
         *   9.7fps、观感卡顿，而摄像头的价值就在于实时；且该路取像素需 toI420() GL 回读
         *   （手机上几十毫秒），属"没实测基线不放重活"。
         *   原来的 camera 实例与 shouldSendVideoFrame 已**一并删除**（不留死代码）。
         *   ★ 屏幕路由 ScreenStreamManager（JPEG，含"静止时重发上一帧"的保活缓存）与
         *     ScreenWebRtcCapturer（WebRTC，直接读 RGBA 平面采样，便宜）共用。
         */
        val screen = FrameChangeDetector("phone_screen")
    }
}
