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

    /** ★ 所有可用的帧发送通道（中继client / 直连监听listener / TS直连client），发送时按连接状态择可用通道 */
    private val senders: MutableList<RelaySender> = java.util.Collections.synchronizedList(mutableListOf())

    @Volatile
    private var running = false
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    // ★★★ 2026-08-29 前/后摄像头准确识别：
    //   - 不再把控制端传来的 0/1 当 cameraIdList 数组下标（多摄手机下下标1可能是超广角而非前置）
    //   - 改为：0 = 意图打开后置(LENS_FACING_BACK)；1 = 意图打开前置(LENS_FACING_FRONT)
    //   - 在 tryOpenCamera 中遍历 cameraIdList，按 CameraCharacteristics.LENS_FACING 精确匹配
    //   - 负数/2+ 保留 legacy：作为 cameraIdList 数组下标直接使用（兼容旧控制端）
    // ★★ 重要区分（2026-08-29 真机实测修出的反向BUG）：
    //   线上协议语义固定为 0=后置 / 1=前置（与旧版控制端一致），
    //   但 CameraCharacteristics.LENS_FACING_BACK == 1、LENS_FACING_FRONT == 0 —— 数值正好相反！
    //   绝不能把平台枚举值直接当协议值用，必须经 lensFacingToWire()/wireToLensFacing() 转换。
    /** 协议/帧头/回报使用的朝向语义：0 = 后置 */
    const val FACING_BACK = 0
    /** 协议/帧头/回报使用的朝向语义：1 = 前置 */
    const val FACING_FRONT = 1
    /** 平台枚举：后置镜头（值=1） */
    private const val LENS_BACK = CameraCharacteristics.LENS_FACING_BACK
    /** 平台枚举：前置镜头（值=0） */
    private const val LENS_FRONT = CameraCharacteristics.LENS_FACING_FRONT
    /** 平台枚举：外接镜头（值=2）——既不算前也不算后，不参与朝向匹配 */
    private const val LENS_EXTERNAL = CameraCharacteristics.LENS_FACING_EXTERNAL
    /** 平台枚举 → 协议朝向；EXTERNAL/未知不参与前后置匹配，按后置处理 */
    private fun lensFacingToWire(lens: Int): Int = when (lens) {
        LENS_BACK -> FACING_BACK
        LENS_FRONT -> FACING_FRONT
        else -> FACING_BACK
    }
    /** 协议朝向 → 平台枚举 */
    private fun wireToLensFacing(wire: Int): Int = if (wire == FACING_FRONT) LENS_FRONT else LENS_BACK
    /** 当前使用的 cameraId（字符串，避免 cameraId="0"/"1"/"2"... 与 facing 语义混淆） */
    @Volatile
    private var currentCameraId: String? = null
    /** 当前实际匹配到的朝向（LENS_FACING_BACK/FRONT/EXTERNAL）；用于帧头/状态回传/镜像翻转 */
    @Volatile
    private var currentFacing = FACING_BACK

    /** ★ 2026-08-29 最近一次 start() 是否发生"按朝向没匹配到 → 回退"（回退时状态回报追加 |fallback=1） */
    @Volatile
    private var lastStartFallback = false

    /** 供 RelayServerHandler 等调用方回报"实际朝向"（0=后置 / 1=前置 / 2=外接） */
    fun currentFacingValue(): Int = currentFacing

    /** 供调用方回报实际打开的 cameraId（可能为 null，表示尚未成功打开） */
    fun currentCameraName(): String? = currentCameraId

    /** 最近一次启动是否回退（控制端据此判断"UI 显示应与实际朝向一致"） */
    fun lastStartHadFallback(): Boolean = lastStartFallback

    /**
     * ★★★ 2026-08-29 契约：追加到"现有摄像头状态/错误消息体"末尾的朝向段。
     *   正常：|facing=<0|1>        0=后置(BACK) 1=前置(FRONT)
     *   回退：|facing=<实际打开朝向>|fallback=1
     *   控制端解析必须容忍不含该段的旧格式（缺失 → 不改 UI、不报错）。
     */
    fun facingSuffix(): String =
        if (lastStartFallback) "|facing=$currentFacing|fallback=1" else "|facing=$currentFacing"

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

    /**
     * ★ 2026-08-27 省电：摄像头"无人接收"自动收尾。
     *   依据：摄像头推流由控制端命令开启、也靠控制端命令关闭；若控制端进程被杀/网络掉线而 STOP 命令丢失，
     *   本端会一直开着摄像头 + 每帧做 640x480 YUV→JPEG 编码 + 持有 PARTIAL_WAKE_LOCK（见 acquireWakeLock），
     *   这是被控端最贵的一种"空转"：摄像头供电、ISP、CPU 三样同时满载，且灭屏也不会休眠。
     *   策略：连续 NO_CONSUMER_IDLE_MS 内【所有】发送通道（中继client / 直连监听56786 / TS直连56789）
     *   都没有成功送出过一帧（即全部处于未连接状态）→ 自动 stop() 释放摄像头与 WakeLock。
     *   【只在直连场景生效，绝不切断中继会话】中继模式下被控端与中继的 client 通道恒为已连接，
     *   因此只要中继在线就不会触发；实际能救回来的是"控制端经 TS/局域网直连看摄像头后掉线/被杀"
     *   这一类 STOP 命令丢失的场景——也正是最容易长时间空转烧电的场景。
     */
    private const val NO_CONSUMER_IDLE_MS = 60000L

    /** 最近一次"存在已连接接收通道"的时间戳 */
    @Volatile
    private var lastConsumerSeenTime = 0L

    private var idleWatchdog: Thread? = null

    /** 标记已触发空闲停止，避免看门狗反复调用 stop() */
    @Volatile
    private var idleStopTriggered = false

    /** 记录一次"有人在收流"（由取帧回调调用，代价仅一次时间戳写入） */
    private fun touchConsumer() {
        lastConsumerSeenTime = System.currentTimeMillis()
    }

    /** 启动空闲看门狗：每10秒检查一次是否有接收方，连续超时则自动停流（省电收尾） */
    private fun startIdleWatchdog() {
        lastConsumerSeenTime = System.currentTimeMillis()
        idleStopTriggered = false
        if (idleWatchdog?.isAlive == true) return
        idleWatchdog = Thread({
            while (running && !idleStopTriggered) {
                try {
                    Thread.sleep(10000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!running) break
                val idleMs = System.currentTimeMillis() - lastConsumerSeenTime
                if (idleMs > NO_CONSUMER_IDLE_MS) {
                    idleStopTriggered = true
                    Log.i(TAG, "★ 省电：摄像头连续 ${idleMs / 1000}s 无任何已连接接收通道（控制端可能已掉线），自动停流释放相机与WakeLock")
                    // 必须在独立线程调用：stop() 内部会等待 camera 线程的 onClosed，
                    // 若在 cameraHandler 线程里调用会自己等自己（靠 1500ms 超时兜底）
                    stop()
                    break
                }
            }
        }, "CameraIdleWatchdog").apply { isDaemon = true }
        idleWatchdog!!.start()
    }

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
        // ★ 2026-09-04：本对象是进程级单例，而服务会重建（设置变更/被系统回收后重启）。
        //   原先只 add 不 remove，senders 只增不减：每轮重启都留下一只已 stop 的中继通道，
        //   而它捕获的 onConnected/onCommand 闭包又把那个已销毁的 RelayServerService 钉在内存里。
        //   （不会造成串台：三种通道 stop() 后 isConnected() 恒为 false，帧永远发不出去，
        //    所以这里修的是泄漏与每帧的无效遍历，不是正确性。）
        val old = client
        client = c
        if (old != null && old !== c) removeSender(old)
        if (c != null) addSender(c)
        if (c == null) stop()
    }

    /** ★ 注册帧发送通道（中继/直连监听/TS直连），同一连接重复注册自动忽略 */
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
     * ★ 2026-08-29 参数语义化（facingTarget = 朝向目标，不再是 cameraIdList 数组下标）：
     *   - facingTarget=0 → 打开"第一个后置摄像头"（CameraCharacteristics.LENS_FACING_BACK 匹配）
     *   - facingTarget=1 → 打开"第一个前置摄像头"（LENS_FACING_FRONT 匹配）
     *   - facingTarget<0 或 >=2 → Legacy：按 cameraIdList 数组下标使用（兼容旧版控制端/其它调用方）
     *   LENS_FACING_EXTERNAL 既不算前也不算后，不参与朝向匹配。
     * @return 是否成功启动
     */
    @Synchronized
    fun start(facingTarget: Int): Boolean {
        // ★ 2026-08-05修复：直连模式下中继client未连接（云服务关闭/中继不可达）时，
        //   只要存在任一已连接的发送通道（直连监听56786 / TS直连56789），摄像头仍可推流。
        //   原逻辑只检查中继client，导致直连模式摄像头永远启动失败。
        val hasSender = synchronized(senders) { senders.any { it.isConnected() } }
        if (!hasSender) {
            lastError = "无可用连接通道，无法推流摄像头"
            Log.w(TAG, lastError!!)
            return false
        }
        // ★★★ 2026-08-29 先把意图 facing 算出来，供后面 running 判断"同一个 facing 无需切换"
        val intendedFacing = when (facingTarget) {
            0 -> FACING_BACK
            1 -> FACING_FRONT
            else -> null // legacy：按数组下标，不做 facing 等价判断
        }
        if (running) {
            if (intendedFacing != null && intendedFacing == currentFacing) {
                // ★ 同一 facing 继续推流：推进流ID，让控制端识别为新会话（过滤中继缓冲的旧流帧）
                streamId = maxOf(System.currentTimeMillis(), streamId + 1)
                touchConsumer()
                Log.i(TAG, "摄像头流已在运行(facing=${facingName(currentFacing)} id=$currentCameraId)，推进流ID继续推流")
                reportCameraReadyToControl()
                return true
            }
            if (intendedFacing == null && facingTarget == cameraIndex) {
                // Legacy 分支：同一下标 → 不变
                streamId = maxOf(System.currentTimeMillis(), streamId + 1)
                touchConsumer()
                Log.i(TAG, "摄像头流已在运行(legacy index=$cameraIndex id=$currentCameraId)，推进流ID继续推流")
                reportCameraReadyToControl()
                return true
            }
            Log.i(TAG, "摄像头正在运行(facing=${facingName(currentFacing)} id=$currentCameraId)，收到新意图START(facingTarget=$facingTarget)，自动切换")
            inSwitchingStop = true
            try {
                stop()
            } finally {
                inSwitchingStop = false
            }
            try { Thread.sleep(200) } catch (_: InterruptedException) {}
        }
        cameraIndex = facingTarget
        lastStartFallback = false
        currentFacing = intendedFacing ?: FACING_BACK // 打开前设置默认值；tryOpenCamera 中会再次用实际匹配覆盖
        streamId = maxOf(System.currentTimeMillis(), streamId + 1)
        lastError = null

        var opened = false
        try {
            opened = tryOpenCamera(facingTarget)
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "启动摄像头流失败: ${e.message}")
        }
        if (!opened) {
            for (retry in 1..2) {
                val waitMs = if (retry == 1) 500L else 3000L
                Log.w(TAG, "摄像头打开失败(${lastError})，第${retry}次等待${waitMs}ms后重试")
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                }
                try {
                    opened = tryOpenCamera(facingTarget)
                    if (opened) break
                } catch (e: Exception) {
                    lastError = e.message
                    Log.e(TAG, "第${retry}次重试摄像头失败: ${e.message}")
                }
            }
        }
        if (!opened) {
            stop()
            return false
        }
        running = true
        acquireWakeLock()
        startIdleWatchdog()
        // ★★★ 2026-08-29 启动成功 → 回传"实际打开的 facing"给控制端，让控制端 UI 显示文字与真实摄像头匹配
        reportCameraReadyToControl()
        return true
    }

    /** 把"协议朝向语义"翻译为中文名称（供日志/状态回传使用；只区分后置/前置） */
    private fun facingName(facing: Int): String = when (facing) {
        FACING_BACK -> "后置"
        FACING_FRONT -> "前置"
        else -> "未知$facing"
    }

    /** ★★★ 摄像头启动成功 → 向控制端回传"实际打开的摄像头（facing语义 + cameraId）"，
     *  控制端据此校准 UI 显示，避免"UI写前置实际打开超广角"。
     *  负载格式："<实际facing(0/1)>|success|摄像头流已启动 cameraId=xxx facing=后置/前置|facing=<0|1>[|fallback=1]" */
    private fun reportCameraReadyToControl() {
        try {
            val line = "$currentFacing|success|摄像头流已启动 cameraId=$currentCameraId facing=${facingName(currentFacing)}" + facingSuffix()
            val data = line.toByteArray(Charsets.UTF_8)
            synchronized(senders) {
                var sent = false
                for (s in senders) {
                    if (s.isConnected()) {
                        try { s.send(RelayCommands.CMD_CAMERA_STATUS_REPORT, data); sent = true } catch (_: Exception) {}
                    }
                }
                if (!sent) {
                    try { client?.send(RelayCommands.CMD_CAMERA_STATUS_REPORT, data) } catch (_: Exception) {}
                }
            }
            Log.i(TAG, "★ 回传摄像头就绪状态: $line")
        } catch (_: Throwable) {}
    }

    /** ★★★ 2026-08-29 根据"控制端意图朝向"解析出实际 cameraId：
     *   - facingTarget=0 → 枚举 cameraIdList 中第一个 LENS_FACING_BACK 镜头
     *   - facingTarget=1 → 枚举 cameraIdList 中第一个 LENS_FACING_FRONT 镜头
     *   - 其它值（越界）→ Legacy：cameraIdList[facingTarget]
     *   返回：Pair(cameraId, 协议朝向0后置/1前置)。匹配失败自动回退到 cameraIdList[0] 并置 lastStartFallback。
     *   同一朝向有多个镜头（超广角/微距/长焦）时只取列表第一个（通常是默认主摄），不做复杂分摄切换。 */
    private fun resolveCameraId(cm: CameraManager, facingTarget: Int): Pair<String, Int> {
        val cameraIdList = cm.cameraIdList
        if (cameraIdList.isEmpty()) throw RuntimeException("没有可用摄像头")
        // 目标 facing：0=BACK，1=FRONT；其它 → legacy 下标模式（向后兼容）
        val targetFacing: Int? = when (facingTarget) {
            0 -> FACING_BACK
            1 -> FACING_FRONT
            else -> null
        }
        if (targetFacing != null) {
            for (cid in cameraIdList) {
                try {
                    val chars = cm.getCameraCharacteristics(cid)
                    val lens = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                    // EXTERNAL(2) 既不算前也不算后：lensFacingToWire 不会把它匹配成任一目标
                    if (lens == LENS_EXTERNAL || lens != wireToLensFacing(targetFacing)) continue
                    if (lensFacingToWire(lens) == targetFacing) {
                        Log.i(TAG, "★ resolveCameraId: 意图=${facingName(targetFacing)} → 匹配 cameraId=$cid (LENS_FACING=$lens)")
                        return cid to targetFacing
                    }
                } catch (_: Exception) {}
            }
            Log.w(TAG, "★ resolveCameraId: 按朝向没有匹配到目标=${facingName(targetFacing)}，自动回退第一个可用摄像头（回报带 fallback=1）")
        } else {
            // Legacy：数组下标模式（越界值 / 旧控制端行为）
            val idx = facingTarget.coerceIn(cameraIdList.indices)
            val cid = cameraIdList[idx]
            val wire = try {
                lensFacingToWire(cm.getCameraCharacteristics(cid).get(CameraCharacteristics.LENS_FACING) ?: LENS_BACK)
            } catch (_: Exception) { FACING_BACK }
            Log.i(TAG, "★ resolveCameraId: legacy数组下标=$facingTarget→$idx → cameraId=$cid facing=${facingName(wire)}")
            return cid to wire
        }
        // 兜底：取列表第一个（通常是后置主摄），并标记为"回退"
        lastStartFallback = true
        val fallback = cameraIdList.first()
        val fbWire = try {
            lensFacingToWire(cm.getCameraCharacteristics(fallback).get(CameraCharacteristics.LENS_FACING) ?: LENS_BACK)
        } catch (_: Exception) { FACING_BACK }
        Log.w(TAG, "★ resolveCameraId: 兜底 cameraId=$fallback facing=${facingName(fbWire)}")
        return fallback to fbWire
    }

    /**
     * 释放采集线程与 ImageReader（不含 cameraDevice——那由 stop() 带 latch 负责）。
     * ★ 2026-09-04：本函数存在的原因是 start() 里的重试循环会多次调用 tryOpenCamera，
     *   而 tryOpenCamera 每次都直接覆盖 cameraThread/imageReader 字段。
     */
    private fun releaseReaderAndThread() {
        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null
        try {
            cameraThread?.quitSafely()
        } catch (_: Exception) {
        }
        cameraThread = null
        cameraHandler = null
    }

    /** 打开摄像头并启动采集会话；openCamera 同步抛异常时返回 false */
    private fun tryOpenCamera(facingTarget: Int): Boolean {
        // ★ 2026-09-04 修复重试泄漏：openCamera 同步抛异常后 start() 会重试最多 2 次，
        //   每次都新建 HandlerThread + ImageReader 并直接覆盖字段 —— 旧线程没人 quit（常驻
        //   空转）、旧 ImageReader 没人 close（各占 2 块 YUV 缓冲，且它挂着的 Surface 会让
        //   相机服务认为仍有消费者）。先释放上一轮遗留，再分配。
        releaseReaderAndThread()
        val cm = App.context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        if (cm.cameraIdList.isEmpty()) {
            lastError = "没有可用摄像头"
            Log.w(TAG, "没有可用摄像头")
            return false
        }
        val (cameraId, matchedFacing) = resolveCameraId(cm, facingTarget)
        currentCameraId = cameraId
        currentFacing = matchedFacing

        // ★ 获取传感器方向和摄像头朝向，用于正确旋转图像
        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            // 以枚举匹配的 matchedFacing（协议语义）为主；chars 能读到时二次确认（同样先转成协议语义）
            val charsWire = lensFacingToWire(chars.get(CameraCharacteristics.LENS_FACING) ?: wireToLensFacing(matchedFacing))
            if (charsWire != matchedFacing) {
                Log.w(TAG, "resolveCameraId 与chars不一致(matched=$matchedFacing chars=$charsWire)，以chars为准")
                currentFacing = charsWire
            }
            // lensFacing 供旋转/镜像判断使用，保持平台枚举语义
            lensFacing = wireToLensFacing(currentFacing)
            Log.i(TAG, "摄像头(意图朝向=$facingTarget) 最终id=$cameraId 传感器方向=$sensorOrientation 朝向=${facingName(currentFacing)}")
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
                        // ★ 2026-08-29 帧负载格式: "实际facing语义|流ID|" + JPEG
                        //   之前写 cameraIndex（数组下标），多摄手机上下标1可能等于超广角，控制端无法按帧头还原前后置
                        val header = "$currentFacing|$streamId|".toByteArray(Charsets.UTF_8)
                        val data = header + jpeg
                        try {
                            // ★ 2026-08-05：逐通道发送——中继/直连监听/TS直连，只要有连接就推（直连模式下摄像头仍可用）
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
                            // ★ 省电：本帧确实送达了至少一条已连接通道 → 记为"有人在收流"
                            if (sent) touchConsumer()
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
                val reason = "被控端摄像头连接中断（可能被其他应用抢占，如正在视频通话）"
                Log.w(TAG, reason)
                lastError = reason
                reportCameraErrorToControl(reason)
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
                // ★★★ 2026-08-13 明确反馈：摄像头打开失败原因（被占用/服务异常等）→ 控制端显示"为什么没有图像"
                val reason = when (error) {
                    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                        "被控端摄像头被其他应用占用（如正在视频通话），无法开启预览"
                    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                        "被控端摄像头被系统策略禁用"
                    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                        "被控端摄像头服务不可用"
                    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                        "被控端摄像头已达并发使用上限"
                    else -> "被控端摄像头打开失败（错误码$error）"
                }
                lastError = reason
                Log.e(TAG, "摄像头错误: $error → $reason")
                reportCameraErrorToControl(reason)
                stop()
            }
        }, cameraHandler!!)
        return true
    }

    /** ★★★ 2026-08-13 摄像头打开失败/中断时，向所有已连接通道发送状态报告，让控制端明确看到失败原因 */
    private fun reportCameraErrorToControl(reason: String) {
        try {
            val data = "0|failed|$reason".toByteArray(Charsets.UTF_8)
            synchronized(senders) {
                var sent = false
                for (s in senders) {
                    if (s.isConnected()) {
                        try {
                            s.send(RelayCommands.CMD_CAMERA_STATUS_REPORT, data)
                            sent = true
                        } catch (_: Exception) {
                        }
                    }
                }
                if (!sent) {
                    try { client?.send(RelayCommands.CMD_CAMERA_STATUS_REPORT, data) } catch (_: Exception) {}
                }
            }
        } catch (_: Throwable) {}
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
     *  ★ 镜像/旋转判断基于实际 currentFacing(LENS_FACING_FRONT)，而非 cameraIndex==1 */
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

