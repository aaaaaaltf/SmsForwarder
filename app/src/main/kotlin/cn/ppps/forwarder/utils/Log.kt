package cn.ppps.forwarder.utils

import android.content.Context
import android.os.Build
import cn.ppps.forwarder.App
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import android.util.Log as AndroidLog

@Suppress("unused", "MemberVisibilityCanBePrivate")
object Log {
    const val ASSERT = 7
    const val DEBUG = 3
    const val ERROR = 6
    const val INFO = 4
    const val VERBOSE = 2
    const val WARN = 5

    private const val TAG = "Logger"
    private var logFile: File? = null
    private lateinit var appContext: Context
    private var initDate: String = ""

    // ★ 2026-08-06：原实现每条日志 new Thread 写文件，高频日志导致线程创建耗尽/卡死，
    //   文件日志停止写入。改为单后台线程 + 阻塞队列串行写文件。
    private val logQueue: LinkedBlockingQueue<String> = LinkedBlockingQueue(20000)
    private val logWriterThread: Thread = Thread({
        while (true) {
            try {
                writeLine(logQueue.take())
            } catch (e: InterruptedException) {
                break
            }
        }
    }, "LogFileWriter").apply {
        isDaemon = true
        start()
    }

    fun init(context: Context) {
        appContext = context
        createLogFile()
    }

    private fun createLogFile() {
        val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (currentDate != initDate || logFile == null || !logFile!!.exists()) {
            initDate = currentDate
            // ★ 2026-08-05：日志文件写入持久目录（app外部文件目录），避免系统清理缓存导致日志丢失
            val baseDir = try {
                appContext.getExternalFilesDir(null)?.absolutePath ?: appContext.cacheDir.absolutePath
            } catch (e: Exception) {
                appContext.cacheDir.absolutePath
            }
            val logPath = baseDir + "/logs"
            val logDir = File(logPath)
            if (!logDir.exists()) logDir.mkdirs()
            logFile = File(logPath, "log_$currentDate.txt")
        }
    }

    fun logToFile(level: String, tag: String, message: String) {
        if (Build.DEVICE == null) return

        if (!::appContext.isInitialized) {
            throw IllegalStateException("Log not initialized. Call init(context) first.")
        }

        // ★ 2026-08-05：去掉 App.isDebug 限制——无论调试模式与否都写文件日志，
        //   否则 release 场景（enableDebugMode=false）下中继链路无法持久取证
        // ★ 2026-08-06：只提交到队列，由单后台线程串行写文件，避免高频日志线程耗尽
        try {
            val logTimeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            logQueue.offer("$logTimeStamp | $level | $tag | $message\n\n")
        } catch (e: Exception) {
            AndroidLog.e(TAG, "提交日志到队列失败: ${e.message}")
        }
    }

    /** 唯一写文件的线程入口 */
    private fun writeLine(line: String) {
        try {
            createLogFile()
            logFile?.let { file ->
                try {
                    // ★ 2026-08-05：日志文件超过1MB时清空（删除旧内容），避免无限膨胀
                    if (file.length() > 1024 * 1024) {
                        file.delete()
                        file.createNewFile()
                    }
                    val logWriter = FileWriter(file, true)
                    logWriter.append(line)
                    logWriter.close()
                } catch (e: Exception) {
                    AndroidLog.e(TAG, "Error writing to file: ${e.message}")
                }
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "Error writing to file: ${e.message}")
        }
    }

    fun v(tag: String, message: String) {
        AndroidLog.v(tag, message)
        logToFile("V", tag, message)
    }

    fun v(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.v(tag, logMessage)
        logToFile("V", tag, logMessage)
    }

    fun d(tag: String, message: String) {
        AndroidLog.d(tag, message)
        logToFile("D", tag, message)
    }

    fun d(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.d(tag, logMessage)
        logToFile("D", tag, logMessage)
    }

    fun i(tag: String, message: String) {
        AndroidLog.d(tag, message)
        logToFile("I", tag, message)
    }

    fun i(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.d(tag, logMessage)
        logToFile("I", tag, logMessage)
    }

    fun w(tag: String, message: String) {
        AndroidLog.w(tag, message)
        logToFile("W", tag, message)
    }

    fun w(tag: String, throwable: Throwable) {
        val logMessage = getStackTraceString(throwable)
        AndroidLog.w(tag, logMessage)
        logToFile("W", tag, logMessage)
    }

    fun w(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.w(tag, logMessage)
        logToFile("W", tag, logMessage)
    }

    fun e(tag: String, message: String) {
        AndroidLog.e(tag, message)
        logToFile("E", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.e(tag, logMessage)
        logToFile("E", tag, logMessage)
    }

    fun wtf(tag: String, message: String) {
        AndroidLog.wtf(tag, message)
        logToFile("WTF", tag, message)
    }

    fun wtf(tag: String, throwable: Throwable) {
        val logMessage = getStackTraceString(throwable)
        AndroidLog.wtf(tag, logMessage)
        logToFile("WTF", tag, logMessage)
    }

    fun wtf(tag: String, message: String, throwable: Throwable) {
        val logMessage = "${message}\n${getStackTraceString(throwable)}"
        AndroidLog.wtf(tag, logMessage)
        logToFile("WTF", tag, logMessage)
    }

    fun getStackTraceString(throwable: Throwable): String {
        return AndroidLog.getStackTraceString(throwable)
    }

    fun isLoggable(tag: String?, level: Int): Boolean {
        return AndroidLog.isLoggable(tag, level)
    }

    fun println(priority: Int, tag: String, message: String) {
        AndroidLog.println(priority, tag, message)
        logToFile("P", tag, message)
    }
}
