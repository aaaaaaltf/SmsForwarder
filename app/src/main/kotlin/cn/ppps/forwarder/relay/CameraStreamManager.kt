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
import cn.ppps.forwarder.utils.Log
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
    private var client: RelaySender? = null

    /** ★ 所有可用的帧发送通道（中继client / 直连监听listener / ZT直连client），发送时按连接状态择可用通道 */
    private val senders: MutableList<RelaySender> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    private var running = false
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    @Volatile
    private var cameraIndex = 0

    /** ★ 当前摄像头传感器方向（ degrees: 0/90/180/270 ），用于正确旋转图像 */
    @Volatile
    private var sensorOrientation = 0

    /** ★ 当前摄像头朝向（ LENS_FACING_BACK / LENS_FACING_FRONT ） */
    @Volatile
    private var lensFacing = android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK

    /** ★ 流ID：每次启动推流时递增（严格单调），控制端据此过滤旧流残留帧，避免重开/切换时显示旧视频 */
    @Volatile
    private var streamId = 0L

    /** 相机释放完成闩锁：stop() 等待 onClosed 确认相机真正释放，避免切换时复用冲突 */
    private var closeLatch = java.util.concurrent.CountDownLatch(0)

    /** ★ 2026-08-10 防stop递归：避免 CameraDevice.onDisconnected/onError 在 stop 过程中再次调用 stop()
     *  该标志在 stop() 入口置 true，stop() 出口置 false；onDisconnected/onError 回调中若为 true 则直接忽略 */
    @Volatile
    private var inStopping = false

    /** ★ 2026-08-10 正在执行切换式stop：start(index不同)内部调用stop()后立即打开新索引，
     *  此时onDisconnected/onError属正常切换流程，不能调用stop()打断新流启动 */
    @Volatile
    private var inSwitchingStop = false

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

    /** 由 RelayServerService 在创建连接后设置，用于推流（同时加入发送通道列表） */
    fun setClient(c: RelaySender?) {
        client = c
        if (c != null) addSender(c)
        if (c == null) stop()
    }

    /** ★ 注册帧发送通道（中继/直连监听/ZT直连），同一连接重复注册自动忽略 */
    fun addSender(c: RelaySender) {
        synchronized(senders) {
            if (!senders.contains(c)) senders.add(c)
        }
    }

    /** ★ 移除帧发送通道（连接断开时调用） */
    fun removeSender(c: RelaySender) {
        synchronized(senders) {
            senders.remove(c)
        }
    }

    fun isStreaming(): Boolean = running

    /**
     * 启动摄像头推流
     * @param index 摄像头索引（0=后置，1=前置）
     * @return 是否成功启动
     */
    @Synchronized
    fun start(index: Int): Boolean {
        // ★ 2026-08-05修复：直连模式下中继client未连接（云服务关闭/中继不可达）时，
        //   只要存在任一已连接的发送通道（直连监听56786 / ZT直连56789），摄像头仍可推流。
        //   原逻辑只检查中继client，导致直连模式摄像头永远启动失败。
        val hasSender = synchronized(senders) { senders.any { it.isConnected() } }
        if (!hasSender) {
            lastError = "无可用连接通道，无法推流摄像头"
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
            // ★ 2026-08-10 修复：设置 inSwitchingStop，stop期间触发的onDisconnected/onError会忽略
            //   不打断新流启动；同时start返回后额外sleep确保相机服务完全释放
            Log.i(TAG, "摄像头正在运行(camera=$cameraIndex)，收到新索引START($index)，自动切换")
            inSwitchingStop = true
            try {
                stop()
            } finally {
                inSwitchingStop = false
            }
            // ★ 切换stop后额外等待：某些机型（如小米/红米）CameraDevice.close释放后需200ms才能被openCamera拿到
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
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

        // ★ 获取传感器方向和摄像头朝向，用于正确旋转图像
        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            lensFacing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
            Log.i(TAG, "摄像头$index(id=$cameraId) 传感器方向=$sensorOrientation 朝向=$lensFacing")
        } catch (e: Exception) {
            Log.w(TAG, "获取摄像头特征失败: ${e.message}")
            sensorOrientation = 0
        }

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
                            // ★ 2026-08-05：逐通道发送——中继/直连监听/ZT直连，只要有连接就推（直连模式下摄像头仍可用）
                            var sent = false
                            synchronized(senders) {
                                for (s in senders) {
                                    if (s.isConnected()) {
                                        try {
                                            s.send(RelayCommands.CMD_CAMERA_STREAM_FRAME, data)
                                            sent = true
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            }
                            if (!sent) client?.send(RelayCommands.CMD_CAMERA_STREAM_FRAME, data)
                        } catch (_: Exception) {
                        }
                        // ★ 流控：帧发送后sleep，防止中继服务器缓冲区溢出导致画面卡死
                        // 与文件下载的流控逻辑一致，每帧间隔100ms（约10fps）
                        try {
                            Thread.sleep(FRAME_INTERVAL_MS)
                        } catch (_: InterruptedException) {}
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
                // ★ 2026-08-10 修复：切换流程中的 onDisconnected 属正常回调，不能调用 stop() 打断新流
                if (inSwitchingStop || inStopping) {
                    Log.i(TAG, "摄像头onDisconnected(切换中忽略): inSwitchingStop=$inSwitchingStop inStopping=$inStopping")
                    return
                }
                Log.w(TAG, "摄像头已断开")
                stop()
            }

            override fun onClosed(camera: CameraDevice) {
                Log.i(TAG, "摄像头已关闭释放")
                closeLatch.countDown()
            }

            override fun onError(camera: CameraDevice, error: Int) {
                // ★ 2026-08-10 修复：切换流程中的 onError 不立即打断新流，只记录日志
                if (inSwitchingStop || inStopping) {
                    Log.w(TAG, "摄像头onError(切换中忽略): error=$error inSwitchingStop=$inSwitchingStop inStopping=$inStopping")
                    return
                }
                Log.e(TAG, "摄像头错误: $error")
                stop()
            }
        }, cameraHandler!!)
        return true
    }

    /** 停止摄像头推流 */
    @Synchronized
    fun stop() {
        // ★ 2026-08-10 修复：防stop递归——onDisconnected/onError回调中可能再次触发stop()
        if (inStopping) {
            Log.i(TAG, "stop()递归调用忽略，已有stop在执行")
            return
        }
        inStopping = true
        try {
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
        } finally {
            inStopping = false
        }
    }

    /** YUV_420_888 转 JPEG（NV21 交错后 compressToJpeg）
     *  ★ 前置摄像头(cameraIndex=1)垂直翻转，解决图像上下颠倒问题
     */
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
            val jpegBytes = out.toByteArray()

            // ★ 根据传感器方向旋转图像：前置摄像头通常270度，后置通常90度
            // 不做旋转会导致画面方向错误（上下翻转/旋转）
            rotateJpegBySensorOrientation(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "YUV转JPEG异常: ${e.message}")
            null
        }
    }

    /** ★ 根据传感器方向旋转JPEG图像：后置不变，前置翻转180度 */
    private fun rotateJpegBySensorOrientation(jpeg: ByteArray): ByteArray? {
        return try {
            if (sensorOrientation == 0) return jpeg
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val matrix = android.graphics.Matrix()
            // ★ 后置(sensorOrientation=90): 旋转0度（不变）；前置(sensorOrientation=270): 旋转180度
            val rotateDeg = (sensorOrientation - 90 + 360) % 360
            matrix.postRotate(rotateDeg.toFloat())
            val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val out = java.io.ByteArrayOutputStream()
            rotated.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (rotated != bmp) bmp.recycle()
            rotated.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "图像旋转异常: ${e.message}")
            jpeg
        }
    }

    /** JPEG 垂直翻转（上下翻转）—— 保留用于兼容，当前使用 rotateJpegBySensorOrientation */
    private fun flipJpegVertical(jpeg: ByteArray, width: Int, height: Int): ByteArray? {
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
            val matrix = android.graphics.Matrix()
            matrix.preScale(1f, -1f)  // 垂直翻转
            val flipped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
            val out = java.io.ByteArrayOutputStream()
            flipped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, out)
            if (flipped != bmp) bmp.recycle()
            flipped.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "JPEG翻转异常: ${e.message}")
            jpeg  // 翻转失败返回原图
        }
    }
}
