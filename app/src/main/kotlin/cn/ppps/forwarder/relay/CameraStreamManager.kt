package cn.ppps.forwarder.relay

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import cn.ppps.forwarder.App

/**
 * 被控端摄像头推流管理器
 *
 * 使用 Camera2 API 采集摄像头画面，转 JPEG 后通过中继命令通道（vcdfrm000000）推送到控制端。
 * 摄像头画面只推流，不显示在被控端屏幕（无本地预览 Surface）。
 *
 * 帧负载格式: "摄像头索引|" + JPEG二进制
 */
object CameraStreamManager {

    private const val TAG = "CameraStreamManager"

    /** 采集分辨率 */
    private const val WIDTH = 640
    private const val HEIGHT = 480

    /** 默认帧间隔(ms)，约10fps */
    private const val FRAME_INTERVAL_MS = 100L

    @Volatile
    private var client: RelayServerClient? = null

    @Volatile
    private var running = false
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraIndex = 0

    /** ★ 流ID：每次启动推流时递增（严格单调），控制端据此过滤旧流残留帧，避免重开/切换时显示旧视频 */
    @Volatile
    private var streamId = 0L

    /** 相机释放完成闩锁：stop() 等待 onClosed 确认相机真正释放，避免切换时复用冲突 */
    private var closeLatch = java.util.concurrent.CountDownLatch(0)

    /** 最近一次打开失败原因（供状态报告/控制端提示） */
    @Volatile
    private var lastError: String? = null

    /** 唤醒锁：防止关屏后 CPU 深度休眠中断摄像头推流 */
    private var wakeLock: PowerManager.WakeLock? = null

    fun lastError(): String = lastError ?: "未知错误"

    /** 摄像头推流期间持有 PARTIAL_WAKE_LOCK，防止关屏后 CPU 休眠中断推流 */
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = App.context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsForwarder:CameraStream").apply {
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

    /** 由 RelayServerService 在创建连接后设置，用于推流 */
    fun setClient(c: RelayServerClient?) {
        client = c
        if (c == null) stop()
    }

    fun isStreaming(): Boolean = running

    /**
     * 启动摄像头推流
     * @param index 摄像头索引（0=后置，1=前置）
     * @return 是否成功启动
     */
    @Synchronized
    fun start(index: Int): Boolean {
        val c = client
        if (c == null || !c.isConnected()) {
            lastError = "中继未连接，无法推流摄像头"
            Log.w(TAG, lastError!!)
            return false
        }
        if (running) {
            if (index == cameraIndex) {
                // ★ 同一摄像头继续推流：推进流ID，让控制端识别为新会话（过滤中继缓冲的旧流帧）
                streamId = maxOf(System.currentTimeMillis(), streamId + 1)
                Log.i(TAG, "摄像头流已在运行(camera=$cameraIndex)，推进流ID继续推流")
                return true
            }
            // ★ 不同索引：先停止旧流再启动新索引（应对STOP丢失后控制器直接发新索引START的情况）
            Log.i(TAG, "摄像头正在运行(camera=$cameraIndex)，收到新索引START($index)，自动切换")
            stop()
        }
        cameraIndex = index
        // ★ 每次启动生成严格递增的流ID：时间戳与上一流ID取较大者+1，保证单调递增
        streamId = maxOf(System.currentTimeMillis(), streamId + 1)
        lastError = null

        var opened = false
        try {
            opened = tryOpenCamera(index)
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "启动摄像头流失败: ${e.message}")
        }
        if (!opened) {
            // ★ 自动重试一次：应对 MIUI 等系统的瞬时相机策略限制（如后台限制、相机短暂占用）
            Log.w(TAG, "摄像头打开失败(${lastError})，500ms后自动重试一次")
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
            }
            try {
                opened = tryOpenCamera(index)
            } catch (e: Exception) {
                lastError = e.message
                Log.e(TAG, "重试摄像头失败: ${e.message}")
            }
        }
        if (!opened) {
            stop()
            return false
        }
        running = true
        acquireWakeLock()
        return true
    }

    /** 打开摄像头并启动采集会话；openCamera 同步抛异常时返回 false */
    private fun tryOpenCamera(index: Int): Boolean {
        val cm = App.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraIdList = cm.cameraIdList
        if (cameraIdList.isEmpty()) {
            lastError = "没有可用摄像头"
            Log.w(TAG, "没有可用摄像头")
            return false
        }
        val cameraId = if (index < cameraIdList.size) cameraIdList[index] else cameraIdList[0]

        cameraThread = HandlerThread("CameraStreamThread").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val reader = ImageReader.newInstance(WIDTH, HEIGHT, ImageFormat.YUV_420_888, 2)
        reader.setOnImageAvailableListener({ r ->
            if (!running) return@setOnImageAvailableListener
            val image = try {
                r.acquireLatestImage()
            } catch (_: Exception) {
                null
            }
            if (image != null) {
                try {
                    val jpeg = convertYuvToJpeg(image)
                    if (jpeg != null && running) {
                        // 帧负载: "摄像头索引|流ID|" + JPEG（流ID用于控制端过滤旧流残留帧）
                        val header = "$cameraIndex|$streamId|".toByteArray(Charsets.UTF_8)
                        val data = header + jpeg
                        try {
                            client?.send(RelayCommands.CMD_CAMERA_STREAM_FRAME, data)
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    image.close()
                }
            }
        }, cameraHandler!!)
        imageReader = reader

        @Suppress("MissingPermission") // CAMERA 权限已由 ServerFragment 校验
        cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                try {
                    // 兼容 minSdk 19：使用旧版 createCaptureSession API
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(reader.surface)
                                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                }.build()
                                try {
                                    session.setRepeatingRequest(request, null, cameraHandler)
                                    Log.i(TAG, "摄像头流已启动 camera=$cameraId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "开始预览失败: ${e.message}")
                                }
                            }

                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                Log.e(TAG, "摄像头会话配置失败")
                                stop()
                            }
                        },
                        cameraHandler,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "创建捕获会话失败: ${e.message}")
                    stop()
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "摄像头已断开")
                stop()
            }

            override fun onClosed(camera: CameraDevice) {
                Log.i(TAG, "摄像头已关闭释放")
                closeLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "摄像头错误: $error")
                stop()
            }
        }, cameraHandler!!)
        return true
    }

    /** 停止摄像头推流 */
    @Synchronized
    fun stop() {
        // ★ 立即置 false：正在执行的取帧回调会因此不再发送视频流
        running = false
        releaseWakeLock()
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        val device = cameraDevice
        cameraDevice = null
        if (device != null) {
            // ★ 等待 onClosed 确认相机真正释放（异步 close），确保再次打开不会冲突/串流
            closeLatch = java.util.concurrent.CountDownLatch(1)
            try {
                device.close()
            } catch (_: Exception) {
            }
            try {
                closeLatch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
            }
        }
        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraThread = null
        cameraHandler = null
        Log.i(TAG, "摄像头流已停止")
    }

    /** YUV_420_888 转 JPEG（NV21 交错后 compressToJpeg） */
    private fun convertYuvToJpeg(image: android.media.Image): ByteArray? {
        return try {
            val width = image.width
            val height = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            // 提取 YUV420 数据
            val ySize = yBuffer.remaining()
            val uvSize = uBuffer.remaining()
            val nv21 = ByteArray(ySize + uvSize * 2)

            yBuffer.get(nv21, 0, ySize)

            val uvOffset = ySize
            var pos = uvOffset
            val uSize = uBuffer.remaining()
            // YUV420_888 中 VU 交错写入 NV21
            val uArray = ByteArray(uSize)
            val vArray = ByteArray(vBuffer.remaining())
            uBuffer.get(uArray)
            vBuffer.get(vArray)
            for (i in 0 until uSize) {
                nv21[pos++] = vArray[i]
                nv21[pos++] = uArray[i]
            }

            val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 70, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "YUV转JPEG异常: ${e.message}")
            null
        }
    }
}
