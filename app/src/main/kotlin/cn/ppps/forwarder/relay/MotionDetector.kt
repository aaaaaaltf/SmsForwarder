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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motion detector for location track sampling.
 *
 * Design (2026-11-16, extended 2026-11-17 for battery saving):
 * - Uses the accelerometer to tell moving from stationary. Both test devices keep
 *   emitting accelerometer events with the screen off, so this works in background.
 * - Low-power trigger sensors (SIGNIFICANT_MOTION / MOTION_DETECT) are armed as one-shot
 *   wake sources so the location SDK can be restarted without polling it continuously.
 * - Moving detection runs three parallel signals on the accelerometer (any one triggers):
 *   gravity deviation, discrete jerk (sample-to-sample 3-axis delta) and short-window
 *   jitter stddev — so smooth car travel is recognized at the 0.4 sensitivity tier.
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
    /** 离散 jerk 阈值倍数（与 threshold 同量纲 m/s²，直觉一致）。 */
    private const val JERK_FACTOR = 1.0f
    /** 抖动标准差阈值倍数；0.4 档≈0.20、0.8 档≈0.40 m/s²，静止噪声远低于此。 */
    private const val STDDEV_FACTOR = 0.5f
    /** 小于该间隔的相邻样本跳过，避免高采样率下噪声被放大成 jerk。 */
    private const val MIN_SAMPLE_INTERVAL_MS = 10L
    private const val WINDOW_SIZE = 24
    private const val WINDOW_WARMUP = 8

    private var sensorManager: SensorManager? = null
    /** 真实运动事件时间；0 表示启动后尚未检测到运动，不能当成 moving。 */
    private var lastMotionAt = 0L
    /** 静止计时锚点：真实运动时间优先，否则用服务启动时间。 */
    private var idleAnchorAt = 0L
    private var lastWakeNotifyAt = 0L
    private var lastDebugAt = 0L
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
            if (abs(magnitude - GRAVITY) > t) {
                onMotion("ACC", magnitude, "GRAVITY")
                updateSamples(x, y, z, magnitude, tsNs)
                return
            }

            // 路2：离散 jerk —— 相邻两次采样的三轴矢量变化量
            var jerk = 0f
            if (hasPrev && tsNs - prevTsNs >= MIN_SAMPLE_INTERVAL_MS * 1_000_000L) {
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
            if (windowCount >= WINDOW_WARMUP) {
                val stddev = windowStddev()
                if (stddev > t * STDDEV_FACTOR) {
                    onMotion("ACC", magnitude, "JITTER", stddev = stddev)
                }
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

    private fun resetSamplingState() {
        prevX = 0f
        prevY = 0f
        prevZ = 0f
        prevTsNs = 0L
        hasPrev = false
        magWindow.fill(0f)
        windowCount = 0
        windowIndex = 0
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
        TrackDebugLog.event(
            "LOCATION_MOVED_WAKE",
            "distM" to String.format("%.1f", distMeters),
            "provider" to provider,
            "lastMotionAt" to lastMotionAt
        )
    }

    private fun onMotion(source: String, magnitude: Float,
                         reason: String = "GRAVITY",
                         jerk: Float = 0f, stddev: Float = 0f) {
        val now = System.currentTimeMillis()
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
        if (now - lastDebugAt >= MOTION_DEBUG_THROTTLE_MS) {
            lastDebugAt = now
            TrackDebugLog.event(
                "MOTION_EVENT",
                "source" to source,
                "reason" to reason,
                "magnitude" to String.format("%.3f", magnitude),
                "jerk" to String.format("%.3f", jerk),
                "jitterStd" to String.format("%.3f", stddev),
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
