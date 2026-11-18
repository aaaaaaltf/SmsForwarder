package cn.ppps.forwarder.relay

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dedicated trace file for location-track decisions.
 *
 * This file is intentionally separate from the normal log file so that several days of
 * location callbacks can be inspected without being truncated by the 1 MB rotation of Log.kt.
 *
 * Output: <external-files>/track_debug/track_debug_yyyy-MM-dd.log
 */
object TrackDebugLog {
    private const val DIR_NAME = "track_debug"
    private const val FILE_PREFIX = "track_debug_"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    /** ★ 2026-11-18：翻页保留数（当前 + .1~.3），避免只留一份导致早段日志丢失 */
    private const val KEEP_ROTATIONS = 3

    private val lock = Any()

    fun event(event: String, vararg fields: Pair<String, Any?>) {
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(now)
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        val sb = StringBuilder(256)
        sb.append(stamp).append('\t').append(event)
        for ((k, v) in fields) {
            sb.append('\t').append(k).append('=').append(formatValue(v))
        }
        sb.append('\n')
        val ctx = runCatching { cn.ppps.forwarder.App.context }.getOrNull() ?: return
        write(ctx, day, sb.toString())
    }

    fun currentFile(ctx: Context): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val base = runCatching { ctx.getExternalFilesDir(null)?.absolutePath }
            .getOrNull() ?: ctx.cacheDir.absolutePath
        return File(File(base, DIR_NAME), FILE_PREFIX + day + ".log")
    }

    private fun write(ctx: Context, day: String, line: String) {
        synchronized(lock) {
            try {
                val base = runCatching { ctx.getExternalFilesDir(null)?.absolutePath }
                    .getOrNull() ?: ctx.cacheDir.absolutePath
                val dir = File(base, DIR_NAME)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, FILE_PREFIX + day + ".log")
                if (!file.exists()) file.createNewFile()
                if (file.length() > MAX_FILE_BYTES) rotate(dir, day, file)
                FileWriter(file, true).use { it.append(line) }
            } catch (_: Exception) {
            }
        }
    }

    /** 翻页轮转：当前 -> .1，原 .1 -> .2，原 .2 -> .3，最老的 .3 删除。 */
    private fun rotate(dir: File, day: String, file: File) {
        val oldest = File(dir, FILE_PREFIX + day + ".${KEEP_ROTATIONS}.log")
        if (oldest.exists()) oldest.delete()
        for (i in KEEP_ROTATIONS - 1 downTo 1) {
            val from = File(dir, FILE_PREFIX + day + ".$i.log")
            if (from.exists()) from.renameTo(File(dir, FILE_PREFIX + day + ".${i + 1}.log"))
        }
        file.renameTo(File(dir, FILE_PREFIX + day + ".1.log"))
        file.createNewFile()
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is Number -> v.toString()
        is Boolean -> if (v) "1" else "0"
        else -> v.toString().replace('\n', ' ').replace('\r', ' ')
    }
}
