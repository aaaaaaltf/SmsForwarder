package cn.ppps.forwarder.relay

import android.content.Context
import android.location.Location
import android.util.Log
import cn.ppps.forwarder.utils.SettingUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.locks.ReentrantLock

/**
 * 位置轨迹存储（★ 2026-11-16 全新设计：运动/静止状态机）
 *
 * 采集规则：
 * - 静止状态：完全不采集（不写盘），由 [MotionDetector] 依据加速度计 / trigger sensor 判定。
 * - 运动状态：按控制端下发的间隔采样（10/20/30/60/120/300 秒，默认 20 秒）。
 * - 位移判重：上一条位置位移小于 [MIN_MOVE_METERS] 时不记录，避免 GPS 抖动产生垃圾点。
 * - 精度过滤：hAcc > MAX_HACC_METERS 的粗定位点不记录（2026-11-17）。
 * - 决策日志：每次回调的触发/拒绝原因写入 TrackDebugLog 专用文件，便于连续多日取证。
 *
 * 其它约束（保持不变）：
 * - 文件位于 App 私有目录 files/location_track.jsonl，每行一条 JSON，追加写。
 * - 只保留最近 6 个月（180 天），超期在下一次整理时丢弃；[MAX_LINES] 为异常高频兜底。
 * - 坐标为定位 SDK 原始坐标（WGS-84），控制端绘制高德地图时做 WGS-84 -> GCJ-02 转换。
 * - 进程重启后从文件末行恢复"上一次位置/时间"，避免重启即重复记录同一个点。
 */
object LocationTrackStore {
    private const val TAG = "LocationTrack"
    private const val FILE_NAME = "location_track.jsonl"

    /**
     * 采集间隔可选档位（秒），与手机控制端历史轨迹页下拉选项一一对应：
     * 10 / 20 / 30 / 60 / 120 / 300 秒（即 10 秒 ~ 5 分钟）。仅在运动状态生效。
     */
    val TRACK_INTERVAL_SECONDS: IntArray = intArrayOf(10, 20, 30, 60, 120, 300)

    /** 默认采集间隔：20 秒 */
    const val DEFAULT_INTERVAL_SEC = 20

    /** 校验并纠正配置：不在可选档位内时退回默认 20 秒 */
    fun normalizeIntervalSec(sec: Int): Int =
        if (sec in TRACK_INTERVAL_SECONDS) sec else DEFAULT_INTERVAL_SEC

    /** 判重阈值（米）：小于该位移视为"位置未变化"，不记录 */
    private const val MIN_MOVE_METERS = 25.0

    /** 精度阈值（米）：hAcc 大于该值的粗定位点直接拒绝（2026-11-17） */
    const val MAX_HACC_METERS = 500.0f

    /** 轨迹数据最长保留 6 个月（180 天） */
    private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000L

    /** 清理节流：最多每 6 小时检查一次过期数据 */
    private const val PURGE_INTERVAL_MS = 6L * 60 * 60 * 1000L

    /** 行数兜底上限 */
    private const val MAX_LINES = 60000

    private val lock = ReentrantLock()

    private var lastPurgeAt = 0L
    private var lastSampleAt = 0L
    private var lastLng = Double.NaN
    private var lastLat = Double.NaN

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /**
     * 定位回调入口：由 LocationService 每次定位成功时调用。
     * 所有拒绝原因写入 TrackDebugLog，便于连续几天核对是否真的由运动触发。
     */
    fun onLocation(ctx: Context, lng: Double, lat: Double, hAcc: Float, provider: String?) {
        val nowIn = System.currentTimeMillis()
        val motionAge = if (MotionDetector.lastMotionAtMs() <= 0L) -1L
                        else nowIn - MotionDetector.lastMotionAtMs()
        fun reject(reason: String, dist: Double = -1.0, intervalMs: Long = -1L, extra: String = "") {
            TrackDebugLog.event(
                "TRACK_REJECT",
                "reason" to reason,
                "lng" to String.format("%.7f", lng),
                "lat" to String.format("%.7f", lat),
                "hAcc" to hAcc,
                "provider" to provider,
                "moving" to MotionDetector.isMoving(),
                "motionSensor" to MotionDetector.isSensorAvailable(),
                "motionAgeMs" to motionAge,
                "distM" to dist,
                "intervalMs" to intervalMs,
                "extra" to extra
            )
        }
        if (lng.isNaN() || lat.isNaN()) {
            reject("INVALID_COORD")
            return
        }
        if (lng == 0.0 || lat == 0.0) {
            reject("ZERO_COORD")
            return
        }
        if (hAcc.isNaN() || hAcc > MAX_HACC_METERS) {
            reject("HACC_TOO_LARGE", extra = "limit=" + MAX_HACC_METERS)
            return
        }
        if (!MotionDetector.isMoving()) {
            reject("NOT_MOVING")
            return
        }
        try {
            lock.lock()
            if (!ensureLoaded(ctx)) {
                reject("LOAD_FAILED")
                return
            }
            val now = System.currentTimeMillis()
            val intervalMs = normalizeIntervalSec(SettingUtils.locationTrackIntervalSec) * 1000L
            val dist = if (!lastLng.isNaN() && !lastLat.isNaN())
                distanceMeters(lastLat, lastLng, lat, lng) else -1.0
            // 采样节流：未到下一次采样点直接返回（不改 lastSampleAt，保持周期稳定）
            if (lastSampleAt != 0L && now - lastSampleAt < intervalMs) {
                reject("INTERVAL_NOT_DUE", dist, intervalMs, "gap=" + (now - lastSampleAt))
                return
            }
            // 位置判重
            if (dist >= 0.0 && dist < MIN_MOVE_METERS) {
                reject("DIST_TOO_SMALL", dist, intervalMs)
                return
            }
            lastSampleAt = now
            append(ctx, now, lng, lat)
            lastLng = lng
            lastLat = lat
            Log.i(TAG, "★ 记录轨迹点(运动) t=$now lng=$lng lat=$lat hAcc=$hAcc")
            TrackDebugLog.event(
                "TRACK_ACCEPT",
                "t" to now,
                "lng" to String.format("%.7f", lng),
                "lat" to String.format("%.7f", lat),
                "hAcc" to hAcc,
                "provider" to provider,
                "moving" to MotionDetector.isMoving(),
                "motionSensor" to MotionDetector.isSensorAvailable(),
                "motionAgeMs" to (now - MotionDetector.lastMotionAtMs()),
                "distM" to dist,
                "intervalMs" to intervalMs
            )
        } catch (e: Exception) {
            Log.w(TAG, "记录轨迹失败: ${e.message}")
            TrackDebugLog.event("TRACK_ERROR", "error" to e.message)
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    /** 按时间范围查询轨迹点（含两端）。 */
    fun query(ctx: Context, startMs: Long, endMs: Long, maxPoints: Int): JSONArray {
        val out = JSONArray()
        try {
            lock.lock()
            val f = file(ctx)
            if (!f.exists()) return out
            val all = ArrayList<JSONObject>(256)
            val effStart = maxOf(startMs, System.currentTimeMillis() - MAX_AGE_MS)
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    try {
                        val o = JSONObject(line)
                        val t = o.optLong("t", 0)
                        if (t in effStart..endMs) all.add(o)
                    } catch (_: Exception) {
                    }
                }
            }
            if (all.isEmpty()) return out
            all.sortBy { it.optLong("t", 0) }
            val limit = if (maxPoints <= 0) all.size else maxPoints
            if (all.size <= limit) {
                all.forEach { out.put(it) }
                return out
            }
            val step = (all.size - 1).toDouble() / (limit - 1).toDouble()
            for (i in 0 until limit) {
                val idx = Math.round(i * step).toInt().coerceIn(0, all.size - 1)
                out.put(all[idx])
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询轨迹失败: ${e.message}")
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
        return out
    }

    /** 当前文件中的轨迹总条数（诊断用） */
    fun count(ctx: Context): Int {
        val f = file(ctx)
        if (!f.exists()) return 0
        return try {
            var n = 0
            f.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.forEach { if (it.isNotBlank()) n++ } }
            n
        } catch (_: Exception) {
            0
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun ensureLoaded(ctx: Context): Boolean {
        if (lastSampleAt != 0L || !lastLng.isNaN()) return true
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) {
            lastSampleAt = 0L
            return true
        }
        var lastLine: String? = null
        try {
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { if (it.isNotBlank()) lastLine = it }
            }
        } catch (_: Exception) {
        }
        val line = lastLine ?: return true
        try {
            val o = JSONObject(line)
            lastLng = o.optDouble("lng", Double.NaN)
            lastLat = o.optDouble("lat", Double.NaN)
            lastSampleAt = o.optLong("t", 0L)
        } catch (_: Exception) {
        }
        return true
    }

    private fun append(ctx: Context, t: Long, lng: Double, lat: Double) {
        val f = file(ctx)
        val obj = JSONObject().put("t", t).put("lng", lng).put("lat", lat)
        FileOutputStream(f, true).use { os ->
            os.write((obj.toString() + "\n").toByteArray(Charsets.UTF_8))
        }
        purgeIfNeeded(f, t)
    }

    private fun purgeIfNeeded(f: File, now: Long) {
        try {
            if (lastPurgeAt != 0L && now - lastPurgeAt < PURGE_INTERVAL_MS) {
                trimByLines(f)
                return
            }
            lastPurgeAt = now
            val cutoff = now - MAX_AGE_MS
            val kept = ArrayList<String>(512)
            var dropped = 0
            f.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val t = try {
                        JSONObject(line).optLong("t", 0L)
                    } catch (_: Exception) {
                        0L
                    }
                    if (t in 1..cutoff) dropped++ else kept.add(line)
                }
            }
            if (dropped > 0) {
                f.writeText(kept.joinToString("\n") + if (kept.isEmpty()) "" else "\n", Charsets.UTF_8)
                Log.i(TAG, "★ 已覆盖删除 $dropped 条超过 6 个月的轨迹，保留 ${kept.size} 条")
            }
            trimByLines(f)
        } catch (e: Exception) {
            Log.w(TAG, "清理过期轨迹失败: ${e.message}")
        }
    }

    private fun trimByLines(f: File) {
        try {
            val lines = f.bufferedReader(Charsets.UTF_8).readLines()
            if (lines.size <= MAX_LINES) return
            val keep = lines.subList(lines.size - MAX_LINES / 2, lines.size)
            f.writeText(keep.joinToString("\n") + "\n", Charsets.UTF_8)
            Log.i(TAG, "轨迹文件行数超限，已裁剪为最新 ${keep.size} 条")
        } catch (e: Exception) {
            Log.w(TAG, "裁剪轨迹文件失败: ${e.message}")
        }
    }

    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[0].toDouble()
    }
}
