package cn.ppps.forwarder.relay

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import cn.ppps.forwarder.utils.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Motion detector for location track sampling.
 *
 * Design (2026-11-16, verified on two real devices):
 * - Uses the accelerometer to tell moving from stationary. Both test devices keep
 *   emitting accelerometer events with the screen off, so this works in background.
 * - isMoving() is true for MOTION_HOLD_MS after any acceleration above MOVE_THRESHOLD.
 *   Once the phone stays still for that long, it becomes stationary and track sampling stops.
 * - If no accelerometer is available, degrade to "always moving" so track recording
 *   still works (never silently disable the feature).
 */
object MotionDetector {
    private const val TAG = "MotionDetector"
    private const val GRAVITY = 9.81f
    private const val MOVE_THRESHOLD = 1.2f
    private const val MOTION_HOLD_MS = 30_000L

    private var sensorManager: SensorManager? = null
    private var lastMotionAt = 0L
    private var started = false
    private var sensorAvailable = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            if (v.size < 3) return
            val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (abs(magnitude - GRAVITY) > MOVE_THRESHOLD) {
                lastMotionAt = System.currentTimeMillis()
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) { }
    }

    /** Start listening. Safe to call repeatedly. */
    fun start(ctx: Context) {
        if (started) return
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || acc == null) {
            sensorAvailable = false
            Log.w(TAG, "★ no accelerometer, degrade to always-moving")
            return
        }
        sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL)
        sensorManager = sm
        sensorAvailable = true
        started = true
        Log.i(TAG, "★ motion detector started")
    }

    /** Stop listening. */
    fun stop() {
        if (!started) return
        try {
            sensorManager?.unregisterListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister failed: ${e.message}")
        }
        started = false
        Log.i(TAG, "★ motion detector stopped")
    }

    /** True while moving (or shortly after motion), false when stationary. */
    fun isMoving(): Boolean {
        if (!sensorAvailable) return true
        return System.currentTimeMillis() - lastMotionAt < MOTION_HOLD_MS
    }
}
