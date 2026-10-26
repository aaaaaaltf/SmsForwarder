package cn.ppps.forwarder.relay

import android.content.Context
import android.location.Location
import android.util.Log
import cn.ppps.forwarder.utils.SettingUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.concurrent.locks.ReentrantLock

/**
 * 位置轨迹存储（★ 2026-10-25 新增）
 *
 * 需求：被控端白天 06:00~22:00 按可配置间隔采集定位（默认 5 分钟，可选 0.5/1/2/3/4/5/10/15/20/30 分钟），
 *      夜间 22:00~06:00 固定每 30 分钟；把"定位时间 + 经纬度"写入文件；
 *      与上一条位置相同（位移小于噪声阈值）则不记录，只有位置真的变化才记录。
 *
 * 要点：
 * - 采样节流由本对象负责（LocationService 的定位回调约 10 秒一次，不能每次都写）；
 * - 判重阈值 [MIN_MOVE_METERS]：GPS 静止抖动通常 < 10 米，25 米可稳定区分"真的移动了"；
 * - 文件位于 App 私有目录 files/location_track.jsonl，每行一条 JSON，追加写、无需全量重写；
 * - **保留策略：只保留最近 6 个月（180 天）**，超过 180 天的旧记录在下一次整理时被丢弃
 *   （滚动覆盖最旧数据）；[MAX_LINES] 仅作"异常高频写入"的兜底，正常 5 分钟采样远达不到；
 * - 坐标为定位 SDK 原始坐标（com.king.location 基于系统 LocationManager，即 WGS-84）；
 *   控制端绘制高德地图时由前端做 WGS-84 → GCJ-02 转换；
 * - 进程重启后从文件末行恢复"上一次位置/时间"，避免重启即重复记录同一个点。
 */
object LocationTrackStore {
    private const val TAG = "LocationTrack"
    private const val FILE_NAME = "location_track.jsonl"

    /**
     * ★ 2026-10-26 采集间隔可选档位（秒），与手机控制端历史轨迹页下拉选项一一对应：
     * 0.5 / 1 / 2 / 3 / 4 / 5 / 10 / 15 / 20 / 30 分钟。
     */
    val TRACK_INTERVAL_SECONDS: IntArray = intArrayOf(30, 60, 120, 180, 240, 300, 600, 900, 1200, 1800)

    /** 校验并纠正配置：不在可选档位内时退回默认 5 分钟（300 秒） */
    fun normalizeIntervalSec(sec: Int): Int =
        if (sec in TRACK_INTERVAL_SECONDS) sec else 300

    /** ★ 2026-10-26 需求：夜间采样间隔 30 分钟（22:00 ~ 次日 06:00），不随白天配置变化 */
    private const val NIGHT_SAMPLE_INTERVAL_MS = 30 * 60 * 1000L

    /** 夜间时段：22:00（含）~ 次日 06:00（不含） */
    private const val NIGHT_START_HOUR = 22
    private const val NIGHT_END_HOUR = 6

    /** 判重阈值（米）：小于该位移视为"位置未变化"，不记录 */
    private const val MIN_MOVE_METERS = 25.0

    /** ★ 2026-10-25 需求：轨迹数据最长保留 6 个月（180 天），超期部分被滚动覆盖 */
    private const val MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000L

    /** 清理节流：最多每 6 小时检查一次过期数据（避免每写一条就全量读文件） */
    private const val PURGE_INTERVAL_MS = 6L * 60 * 60 * 1000L

    /** 行数兜底上限（6 个月按 5~30 分钟采样理论 ≈ 3.2 万条，取 6 万防异常高频写入撑爆存储） */
    private const val MAX_LINES = 60000

    private val lock = ReentrantLock()

    /** 上次清理过期数据的时间 */
    private var lastPurgeAt = 0L

    /** 上次采样时间（节流用，0 = 尚未初始化） */
    private var lastSampleAt = 0L

    /** 上一条已记录的坐标；NaN = 文件里还没有历史点 */
    private var lastLng = Double.NaN
    private var lastLat = Double.NaN

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /**
     * ★ 2026-10-26 需求：22:00 ~ 次日 06:00 固定每 30 分钟采样一次；
     * 其余时间（06:00 ~ 22:00）按控制端下发的档位采样（0.5~30 分钟，默认 5 分钟）。
     * 用本地时区的小时数判断，跨天（22:00~23:59 与 00:00~05:59）都落在夜间时段。
     */
    private fun sampleIntervalMs(ctx: Context, now: Long): Long {
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        return if (hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR) {
            NIGHT_SAMPLE_INTERVAL_MS
        } else {
            normalizeIntervalSec(SettingUtils.locationTrackIntervalSec) * 1000L
        }
    }

    /**
     * 定位回调入口：由 LocationService 每次定位成功时调用。
     * 只有"距上次采样 ≥ 当前时段间隔（白天按所选档位 0.5~30 分钟 / 夜间 30 分钟）"且"位移 ≥ 25 米"才落盘。
     */
    fun onLocation(ctx: Context, lng: Double, lat: Double) {
        if (lng.isNaN() || lat.isNaN()) return
        if (lng == 0.0 || lat == 0.0) return   // 无效坐标（未定位到）
        try {
            lock.lock()
            if (!ensureLoaded(ctx)) return
            val now = System.currentTimeMillis()
            // ① 采样节流：按所处时段动态取间隔，未到下一次采样点直接返回（不改 lastSampleAt，保持周期稳定）
            if (lastSampleAt != 0L && now - lastSampleAt < sampleIntervalMs(ctx, now)) return
            lastSampleAt = now
            // ② 位置判重：上一条存在且位移不足阈值 → 不记录
            if (!lastLng.isNaN() && !lastLat.isNaN()) {
                val dist = distanceMeters(lastLat, lastLng, lat, lng)
                if (dist < MIN_MOVE_METERS) {
                    Log.d(TAG, "位置未变化(位移 %.1f 米)，跳过记录".format(dist))
                    return
                }
            }
            append(ctx, now, lng, lat)
            lastLng = lng
            lastLat = lat
            Log.i(TAG, "★ 记录轨迹点 t=$now lng=$lng lat=$lat")
        } catch (e: Exception) {
            Log.w(TAG, "记录轨迹失败: ${e.message}")
        } finally {
            if (lock.isHeldByCurrentThread) lock.unlock()
        }
    }

    /**
     * 按时间范围查询轨迹点（含两端）。
     * @param maxPoints 最多返回的点数，超出时按等间隔降采样（保证首尾点保留）
     * @return JSONArray，元素为 {"t":毫秒,"lng":经度,"lat":纬度}
     */
    fun query(ctx: Context, startMs: Long, endMs: Long, maxPoints: Int): JSONArray {
        val out = JSONArray()
        try {
            lock.lock()
            val f = file(ctx)
            if (!f.exists()) return out
            val all = ArrayList<JSONObject>(256)
            // ★ 2026-10-25：查询范围同样限制在最近 6 个月内（尚未被清理的过期点也不返回）
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
            // 等间隔降采样：索引 0 与最后一条必保留
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

    /** 首次调用时从文件末行恢复上次位置与时间（进程重启不重复记录同一个点） */
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

    /**
     * ★ 2026-10-25 需求：只保留最近 6 个月，超期记录被覆盖删除。
     * 每 [PURGE_INTERVAL_MS] 最多检查一次：读文件过滤出 t >= now-180 天的行，有变化才重写。
     */
    private fun purgeIfNeeded(f: File, now: Long) {
        try {
            if (lastPurgeAt != 0L && now - lastPurgeAt < PURGE_INTERVAL_MS) {
                // 清理节流窗口内，仅做行数兜底
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

    /** 时间策略之外的极端兜底：行数超限时只保留最新一半（重写一次文件） */
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

    /** 球面距离（米），不依赖 Context，供判重使用 */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, r)
        return r[0].toDouble()
    }
}
