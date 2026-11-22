package cn.ppps.forwarder.relay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Motion detector for location track sampling.
 *
 * Design (2026-11-16, extended 2026-11-17 for battery saving):
 * - Uses the accelerometer to tell moving from stationary. Both test devices keep
 *   emitting accelerometer events with the screen off, so this works in background.
 * - Low-power trigger sensors (SIGNIFICANT_MOTION / MOTION_DETECT) are armed as one-shot
 *   wake sources so the location SDK can be restarted without polling it continuously.
 * - Moving detection runs four parallel signals on the accelerometer (any one triggers):
 *   gravity deviation, discrete jerk (sample-to-sample 3-axis delta), short-window
 *   jitter stddev, and gravity-direction tilt (EMA-smoothed angle change). The tilt channel
 *   covers "magnitude unchanged but direction changed" (phone picked up / turned / body roll),
 *   so any real movement is recognised even at the 0.4 sensitivity tier.
 * - isMoving() is true for MOTION_HOLD_MS after any motion event. Once the phone stays
 *   still for that long, track sampling stops and LocationService may stopLocation().
 * - If no motion sensor is available, degrade to "always moving" so track recording
 *   still works (never silently disable the feature).
 */
object MotionDetector {
    private const val TAG = "MotionDetector"
    private const val GRAVITY = 9.81f
    private const val MOTION_HOLD_MS = 30_000L

    /**
     * 可调灵敏度阈值（越小越灵敏）。三路检测共用同一档位，避免用户要记多套数值：
     * - 重力偏离：|模长 - 9.81| > threshold  （起步/急刹/颠簸）
     * - 离散 jerk：相邻采样三轴矢量变化量 > threshold * JERK_FACTOR （加速/换挡/方向变化）
     * - 抖动标准差：短窗内模长 stddev > threshold * STDDEV_FACTOR （持续路面/发动机振动）
     */
    private fun threshold(): Float = SettingUtils.normalizedMotionThreshold()
    private const val MOTION_WAKE_THROTTLE_MS = 5_000L
    private const val MOTION_DEBUG_THROTTLE_MS = 1_000L
    /**
     * ★ 2026-11-20 TILT 独立日志节流：路1 与路4 常在同一帧同时命中，共用 1 秒节流会把
     *   TILT 全部吞掉（只剩 GRAVITY 记录），无法确认方向检测是否真的在执行。
     */
    private const val MOTION_TILT_DEBUG_THROTTLE_MS = 5_000L
    /** 离散 jerk 阈值倍数（与 threshold 同量纲 m/s²，直觉一致）。 */
    private const val JERK_FACTOR = 1.0f
    /** 抖动标准差阈值倍数；0.4 档≈0.20、0.8 档≈0.40 m/s²，静止噪声远低于此。 */
    private const val STDDEV_FACTOR = 0.5f
    /** 小于该间隔的相邻样本跳过，避免高采样率下噪声被放大成 jerk。 */
    private const val MIN_SAMPLE_INTERVAL_MS = 10L
    private const val WINDOW_SIZE = 24
    private const val WINDOW_WARMUP = 8
    /**
     * ★ 2026-11-20 重力方向阈值倍数（度）：threshold * 4，即 0.4档≈1.6°、0.8档≈3.2°、2.0档≈8°。
     *   补"加速度模长几乎不变、但重力方向变了"的漏判（手机被拿起、转向、车身侧倾/上下坡）。
     *   用户要求"只要手机发生任何移动就触发"，故该路灵敏度最高。
     */
    private const val TILT_DEG_FACTOR = 4.0f
    /** 方向检测 EMA 平滑系数：越小越平滑，抑制静止时逐帧 1~3° 的加速度计方向噪声导致误触发。 */
    private const val TILT_EMA_ALPHA = 0.2f

    private var sensorManager: SensorManager? = null
    /** 真实运动事件时间；0 表示启动后尚未检测到运动，不能当成 moving。 */
    private var lastMotionAt = 0L
    /** 静止计时锚点：真实运动时间优先，否则用服务启动时间。 */
    private var idleAnchorAt = 0L
    /** ★ 2026-11-22 方案C：最后一次「实际位移 ≥25 米」的时间；0 表示本次启动后尚未验证过位移。 */
    private var lastMovedAt = 0L
    /** ★ 2026-11-22 方案C：本段连续运动的起始时间，用于识别「一直在动但原地没挪窝」。 */
    private var motionSegmentStartAt = 0L
    private var lastWakeNotifyAt = 0L
    private var lastDebugAt = 0L
    /** TILT 专用日志时间戳，避免被 GRAVITY 的同帧记录节流吞掉 */
    private var lastTiltDebugAt = 0L
    private var started = false
    private var sensorAvailable = false
    private var motionListener: ((String) -> Unit)? = null
    private val triggerSensors = ArrayList<Sensor>(2)

    // 三路检测用的采样状态
    private var prevX = 0f
    private var prevY = 0f
    private var prevZ = 0f
    private var prevTsNs = 0L
    private var hasPrev = false
    private val magWindow = FloatArray(WINDOW_SIZE)
    private var windowCount = 0
    private var windowIndex = 0

    // 第四路：重力方向（EMA 平滑后的三轴矢量）——检测倾斜/转向/被拿起
    private var tiltX = 0f
    private var tiltY = 0f
    private var tiltZ = 0f
    private var tiltInit = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            if (v.size < 3) return
            val x = v[0]
            val y = v[1]
            val z = v[2]
            val magnitude = sqrt(x * x + y * y + z * z)
            val tsNs = e.timestamp
            val t = threshold()

            // 路1：重力偏离（原逻辑，捕捉起步/急刹/颠簸的大幅动作）
            // ★ 2026-11-20 修复「TILT 被路1 短路」：原先命中即 return，本帧直接跳过路2~路4。
            //   实际拿起/走动/骑行时绝大多数帧都会命中路1（今天 18:49 后 87 条运动事件中
            //   62 条是 GRAVITY），导致重力方向检测几乎从未执行。现在路1 只标记运动，不再
            //   提前返回；后续通道照常评估（路2/3 仍跳过以免同帧重复上报，路4 必须执行）。
            val gravityHit = abs(magnitude - GRAVITY) > t
            if (gravityHit) {
                onMotion("ACC", magnitude, "GRAVITY")
            }

            // 路2：离散 jerk —— 相邻两次采样的三轴矢量变化量
            var jerk = 0f
            if (!gravityHit && hasPrev && tsNs - prevTsNs >= MIN_SAMPLE_INTERVAL_MS * 1_000_000L) {
                val dx = x - prevX
                val dy = y - prevY
                val dz = z - prevZ
                jerk = sqrt(dx * dx + dy * dy + dz * dz)
                if (jerk > t * JERK_FACTOR) {
                    onMotion("ACC", magnitude, "JERK", jerk = jerk)
                }
            }
            updateSamples(x, y, z, magnitude, tsNs)

            // 路3：短窗抖动标准差 —— 匀速行车时靠持续微振动识别
            if (!gravityHit && windowCount >= WINDOW_WARMUP) {
                val stddev = windowStddev()
                if (stddev > t * STDDEV_FACTOR) {
                    onMotion("ACC", magnitude, "JITTER", stddev = stddev)
                }
            }

            // 路4：重力方向变化 —— 模长几乎不变、但重力方向变了（拿起/转向/车身侧倾/上下坡）
            //   ★ 不受路1 影响：即使本帧已判 GRAVITY 也照常计算方向角，保证"任何方向变化"都能被看到。
            val tiltDeg = updateTiltAngle(x, y, z)
            if (tiltDeg > t * TILT_DEG_FACTOR) {
                onMotion("ACC", magnitude, "TILT", tiltDeg = tiltDeg)
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) { }
    }

    private fun updateSamples(x: Float, y: Float, z: Float, magnitude: Float, tsNs: Long) {
        prevX = x
        prevY = y
        prevZ = z
        prevTsNs = tsNs
        hasPrev = true
        magWindow[windowIndex] = magnitude
        windowIndex = (windowIndex + 1) % WINDOW_SIZE
        if (windowCount < WINDOW_SIZE) windowCount++
    }

    /**
     * ★ 2026-11-20 重力方向检测：三轴做 EMA 低通平滑后，计算本帧相对上帧的方向夹角（度）。
     * 用 EMA 抑制静止时逐帧 1~3° 的方向噪声，只保留真实倾斜/转向；首帧只建基线返回 0。
     */
    private fun updateTiltAngle(x: Float, y: Float, z: Float): Float {
        if (!tiltInit) {
            tiltX = x; tiltY = y; tiltZ = z
            tiltInit = true
            return 0f
        }
        val preX = tiltX; val preY = tiltY; val preZ = tiltZ
        tiltX += TILT_EMA_ALPHA * (x - tiltX)
        tiltY += TILT_EMA_ALPHA * (y - tiltY)
        tiltZ += TILT_EMA_ALPHA * (z - tiltZ)
        val m1 = sqrt(preX * preX + preY * preY + preZ * preZ)
        val m2 = sqrt(tiltX * tiltX + tiltY * tiltY + tiltZ * tiltZ)
        if (m1 < 1f || m2 < 1f) return 0f
        val dot = (preX * tiltX + preY * tiltY + preZ * tiltZ) / (m1 * m2)
        return acos(dot.coerceIn(-1f, 1f)) * 180f / PI.toFloat()
    }

    private fun resetSamplingState() {
        prevX = 0f
        prevY = 0f
        prevZ = 0f
        prevTsNs = 0L
        hasPrev = false
        magWindow.fill(0f)
        windowCount = 0
        windowIndex = 0
        tiltX = 0f
        tiltY = 0f
        tiltZ = 0f
        tiltInit = false
    }

    private fun windowStddev(): Float {
        if (windowCount <= 0) return 0f
        var sum = 0.0
        for (i in 0 until windowCount) sum += magWindow[i]
        val mean = sum / windowCount
        var varSum = 0.0
        for (i in 0 until windowCount) {
            val d = magWindow[i] - mean
            varSum += d * d
        }
        return sqrt(varSum / windowCount).toFloat()
    }

    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            val sensor = event.sensor
            onMotion("TRIGGER_" + sensor.type, 0f, "TRIGGER")
            armTrigger(sensor)
        }
    }

    /** Called by LocationService; source is ACC / TRIGGER_xxx. */
    fun setMotionListener(listener: ((String) -> Unit)?) {
        motionListener = listener
    }

    /** Start listening. Safe to call repeatedly. */
    fun start(ctx: Context) {
        if (started) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (sm == null) {
            sensorAvailable = false
            Log.w(TAG, "★ no sensor manager, degrade to always-moving")
            return
        }
        sensorManager = sm
        if (idleAnchorAt <= 0L) idleAnchorAt = System.currentTimeMillis()
        resetSamplingState()

        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (acc != null) {
            sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL)
            sensorAvailable = true
            Log.i(TAG, "★ motion detector started")
        } else {
            Log.w(TAG, "★ no accelerometer, try trigger sensors")
        }

        armTriggerSensor(sm, Sensor.TYPE_SIGNIFICANT_MOTION)
        armTriggerSensor(sm, Sensor.TYPE_MOTION_DETECT)

        started = true
        TrackDebugLog.event(
            "MOTION_DETECTOR_START",
            "acc" to (acc != null),
            "triggerCount" to triggerSensors.size,
            "sensorAvailable" to sensorAvailable,
            "lastMotionAt" to lastMotionAt,
            "idleAnchorAt" to idleAnchorAt
        )
    }

    /** Stop listening. */
    fun stop() {
        if (!started) return
        try {
            sensorManager?.unregisterListener(listener)
            triggerSensors.forEach { sensorManager?.cancelTriggerSensor(triggerListener, it) }
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
        triggerSensors.clear()
        started = false
        motionListener = null
        Log.i(TAG, "★ motion detector stopped")
        TrackDebugLog.event("MOTION_DETECTOR_STOP", "sensorAvailable" to sensorAvailable)
    }

    /** True while moving (or shortly after motion), false when stationary. */
    fun isMoving(): Boolean {
        if (!sensorAvailable) return true
        if (lastMotionAt <= 0L) return false
        return System.currentTimeMillis() - lastMotionAt < MOTION_HOLD_MS
    }

    /** LocationService uses this to decide whether the location SDK may be stopped. */
    fun isIdleFor(ms: Long): Boolean {
        if (!sensorAvailable) return false
        if (isMoving()) return false
        val anchor = if (lastMotionAt > idleAnchorAt) lastMotionAt else idleAnchorAt
        if (anchor <= 0L) return false
        return System.currentTimeMillis() - anchor >= ms
    }

    fun lastMotionAtMs(): Long = lastMotionAt

    fun idleAnchorAtMs(): Long = idleAnchorAt

    fun lastMovedAtMs(): Long = lastMovedAt

    /**
     * ★ 2026-11-22 方案C：本次运动已持续 [ms]，但期间没有任何「≥25 米」的真实位移。
     *
     * 用途：室内手持、滑动屏幕、把手机从桌上拿起都会持续命中运动事件，但它们并不构成移动。
     * 这种情况下没必要开高精度 GPS（5 秒一次），应退回低精度/省电，等真的走出 25 米再升回来。
     * 运动段刚开始时返回 false，给 GPS 留出 [ms] 的试探窗口，避免起步就降精度。
     */
    fun isMovingWithoutDisplacement(ms: Long): Boolean {
        if (!isMoving()) return false
        val base = if (lastMovedAt > motionSegmentStartAt) lastMovedAt else motionSegmentStartAt
        if (base <= 0L) return false
        return System.currentTimeMillis() - base >= ms
    }

    fun isSensorAvailable(): Boolean = sensorAvailable

    /**
     * ★ 2026-11-18「移动即记录」：由定位回调在检测到【位置确实变化】时反向调用。
     *
     * 背景：匀速行车 + 手持稳定时加速度计几乎读不到变化，单靠加速度会得到两条误判：
     *   ① LocationTrackStore 因 !isMoving() 直接 NOT_MOVING 拒绝记录；
     *   ② LocationService 判静止 60 秒后停掉定位 SDK，连回调都没有。
     * 位置变化是"手机真的在动"最可靠的证据，因此这里把运动状态续期，使 isMoving() 为真、
     * 且 idleAnchor 同步刷新，避免刚记一个点就被判静止停 SDK。不触发 wake listener
     * （避免精度切换/重启 SDK 的重入），只做状态续期 + 留痕。
     */
    fun noteLocationMoved(distMeters: Double, provider: String?) {
        val now = System.currentTimeMillis()
        lastMotionAt = now
        idleAnchorAt = now
        // ★ 2026-11-22 方案C：位置确实挪了 ≥25 米，重置"无位移"计时，允许继续使用高精度 GPS。
        lastMovedAt = now
        TrackDebugLog.event(
            "LOCATION_MOVED_WAKE",
            "distM" to String.format("%.1f", distMeters),
            "provider" to provider,
            "lastMotionAt" to lastMotionAt
        )
    }

    private fun onMotion(source: String, magnitude: Float,
                         reason: String = "GRAVITY",
                         jerk: Float = 0f, stddev: Float = 0f, tiltDeg: Float = 0f) {
        val now = System.currentTimeMillis()
        // ★ 2026-11-22 方案C：上一帧已超出运动保持期 ⇒ 这是一段新的运动，重置"无位移"观察窗口。
        if (lastMotionAt <= 0L || now - lastMotionAt >= MOTION_HOLD_MS) {
            motionSegmentStartAt = now
        }
        lastMotionAt = now
        idleAnchorAt = now
        val listener = motionListener
        if (listener != null && now - lastWakeNotifyAt >= MOTION_WAKE_THROTTLE_MS) {
            lastWakeNotifyAt = now
            try {
                listener(source)
            } catch (e: Exception) {
                Log.w(TAG, "motion listener failed: ${e.message}")
            }
        }
        // ★ 2026-11-20 TILT 走独立 5 秒窗口：它与 GRAVITY 常在同一帧命中，若共用 1 秒节流，
        //   日志里只会看到 GRAVITY，TILT 永远"不出现"，无法判断方向检测是否生效。
        val tiltLogDue = reason == "TILT" && now - lastTiltDebugAt >= MOTION_TILT_DEBUG_THROTTLE_MS
        if (tiltLogDue) lastTiltDebugAt = now
        if (tiltLogDue || now - lastDebugAt >= MOTION_DEBUG_THROTTLE_MS) {
            lastDebugAt = now
            TrackDebugLog.event(
                "MOTION_EVENT",
                "source" to source,
                "reason" to reason,
                "magnitude" to String.format("%.3f", magnitude),
                "jerk" to String.format("%.3f", jerk),
                "jitterStd" to String.format("%.3f", stddev),
                "tiltDeg" to String.format("%.2f", tiltDeg),
                "threshold" to threshold(),
                "moving" to isMoving(),
                "sensorAvailable" to sensorAvailable
            )
        }
    }

    private fun armTriggerSensor(sm: SensorManager, type: Int) {
        try {
            val sensor = sm.getSensorList(type).firstOrNull { it.isWakeUpSensor }
                ?: sm.getDefaultSensor(type)
                ?: return
            if (armTrigger(sm, sensor) && !triggerSensors.contains(sensor)) {
                triggerSensors.add(sensor)
            }
        } catch (e: Exception) {
            Log.w(TAG, "arm trigger $type failed: ${e.message}")
        }
    }

    private fun armTrigger(sm: SensorManager, sensor: Sensor): Boolean = try {
        sm.requestTriggerSensor(triggerListener, sensor)
    } catch (e: Exception) {
        Log.w(TAG, "requestTriggerSensor failed: ${e.message}")
        false
    }

    private fun armTrigger(sensor: Sensor) {
        val sm = sensorManager ?: return
        armTrigger(sm, sensor)
    }
}
