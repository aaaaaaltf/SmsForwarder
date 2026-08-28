package cn.ppps.forwarder.permission

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ★ 2026-08-28 被控端「权限 + 保活」统一编排层。
 *
 * 两个入口复用同一实现（需求要求）：
 * - [onStartup]：App 启动路径（MainActivity / ServerFragment 首次可见 / RelayServerService.onCreate）。
 *   只做【静默检测 + 至多一个电池优化白名单弹窗】——防打扰硬要求：其余授权一律留给用户点「一键授权」。
 *   旧实现在启动时会自动连弹 运行时权限×9 + 屏幕捕获 + 所有文件访问 + 悬浮窗 + 无障碍 + 电池 + VPN，
 *   已按新需求收敛（见 ServerFragment 里的调用点变更）。
 * - [reportAndGuide] / [snapshotNow]：「一键授权」路径，走完整流程并给出三类清单。
 *
 * 检测一律走 [PermissionProbe]（真实系统 API），线程安全：诊断在专用 HandlerThread 上跑，
 * 不阻塞主线程（悬浮窗/无障碍/VPN/定位的 binder 调用在 MIUI 上单次可达数十毫秒）。
 */

/** 三类清单报告。 */
data class KeepAliveReport(
    val generatedAt: Long,
    val items: List<PermStatus>,
) {
    /** ① 已自动处理 / 已授予 / 无需处理 */
    val autoHandled: List<PermStatus>
        get() = items.filter { it.granted }

    /** ② 需手工确认（附精确步骤）——App 能跳到对应系统页，但最终开关需用户点一下 */
    val needManual: List<PermStatus>
        get() = items.filter { !it.granted && (it.state == GrantState.DENIED || it.state == GrantState.DENIED_PERMANENTLY) }

    /** ③ 不支持自动 / 无法检测（附精确步骤）——系统未暴露读取或跳转入口 */
    val unsupported: List<PermStatus>
        get() = items.filter { !it.granted && it.state == GrantState.UNKNOWN }

    /**
     * 是否"可核验项全部就绪"。
     * 注意：③（系统不开放读取，如 MIUI 自启动开关）属于"无法自动判定"，
     * 只作为提示项展示，不参与 allClear 判定——否则国产 ROM 上这个标志永远为 false，
     * 界面会一直提示"还有未授权项"，反而掩盖了真正需要处理的东西。
     */
    val allClear: Boolean
        get() = needManual.isEmpty()

    private fun stateText(state: GrantState): String = when (state) {
        GrantState.GRANTED -> "✓ 已授予"
        GrantState.DENIED -> "✗ 未授予"
        GrantState.DENIED_PERMANENTLY -> "✗ 已被永久拒绝（系统不再弹框，只能去设置里改）"
        GrantState.NOT_APPLICABLE -> "— 当前系统无需处理"
        GrantState.UNKNOWN -> "? 系统不开放读取，无法自动判定"
    }

    fun toDisplayText(): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(generatedAt))
        sb.append("系统：${RomType.describe()}  Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})  $time\n")
        sb.append("==================== ① 已自动处理 / 已授予 ====================\n")
        if (autoHandled.isEmpty()) sb.append("（无）\n")
        autoHandled.forEach { sb.append("· ${it.label} → ${stateText(it.state)}\n") }
        sb.append("==================== ② 需手工确认（按步骤点一下即可）====================\n")
        if (needManual.isEmpty()) sb.append("（无，全部已就绪）\n")
        needManual.forEachIndexed { i, it ->
            sb.append("${i + 1}. ${it.label} → ${stateText(it.state)}\n")
            sb.append("   检测方式：${it.detector}\n")
            if (it.manualSteps.isNotEmpty()) sb.append("   步骤：${it.manualSteps}\n")
        }
        sb.append("==================== ③ 不支持自动 / 无法检测 ====================\n")
        if (unsupported.isEmpty()) sb.append("（无）\n")
        unsupported.forEachIndexed { i, it ->
            sb.append("${i + 1}. ${it.label} → ${stateText(it.state)}\n")
            sb.append("   检测方式：${it.detector}\n")
            if (it.manualSteps.isNotEmpty()) sb.append("   步骤：${it.manualSteps}\n")
        }
        sb.append("\n说明：清单②③必须手工确认，App 无法替你点这些系统开关（无障碍、自启动、" +
                "所有文件访问、悬浮窗、媒体投影均为系统级安全授权）。\n")
        sb.append("manifest 声明但项目代码未使用、故不申请的权限：" +
                PermissionInventory.notRequested.joinToString("、") { it.first.substringAfterLast('.') } + "\n")
        return sb.toString()
    }
}

/** 构建报告 + 展示报告（含可复制文案）。 */
object KeepAliveReportBuilder {

    fun build(items: List<PermStatus>): KeepAliveReport = KeepAliveReport(System.currentTimeMillis(), items)

    /** 报告同时写 logcat，便于 adb 无人化核对（tag=KeepAliveGuardian）。 */
    fun logReport(report: KeepAliveReport) {
        Log.i("KeepAliveGuardian", "---- 权限与保活自检报告 ----\n" + report.toDisplayText())
    }

    /**
     * 在界面上列出三类清单。文案 selectable + 一键复制到剪贴板，
     * 这样 OEM 手工步骤（MIUI 自启动等无法自动的项）用户能直接粘给别人/自己照着做。
     */
    fun show(ctx: Context, report: KeepAliveReport) {
        val text = report.toDisplayText()
        try {
            MaterialDialog.Builder(ctx)
                .title(if (report.allClear) "授权自检：全部就绪" else "授权自检：还有 ${report.needManual.size + report.unsupported.size} 项需处理")
                .content(text)
                .autoDismiss(false)
                .cancelable(true)
                .positiveText("复制全文")
                .onPositive { dialog, _ ->
                    copyToClipboard(ctx, text)
                    dialog.dismiss()
                }
                .neutralText("应用详情页")
                .onNeutral { dialog, _ ->
                    PermissionRequests.openAppDetails(ctx)
                    dialog.dismiss()
                }
                .negativeText("关闭")
                .onNegative { dialog, _ -> dialog.dismiss() }
                .build()
                .show()
        } catch (t: Throwable) {
            // 弹窗本身出问题时退回 toast，绝不能让自检把被控端搞崩
            Log.e("KeepAliveGuardian", "展示报告失败，退回日志: ${t.message}")
            logReport(report)
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("permission_report", text))
        } catch (t: Throwable) {
            Log.w("KeepAliveGuardian", "复制报告到剪贴板失败: ${t.message}")
        }
    }
}

/**
 * 编排层：启动静默自检 + 一键授权收尾报告。
 */
object KeepAliveGuardian {

    private const val TAG = "KeepAliveGuardian"

    /** 诊断专用后台线程（不占主线程；避免每次都新建线程带来的开销）。 */
    private val workThread: HandlerThread by lazy {
        HandlerThread("KeepAlivePerm").apply { start() }
    }

    private val workHandler: Handler by lazy { Handler(workThread.looper) }
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 本进程是否已在启动路径弹过电池白名单弹窗（硬要求：启动时至多一个弹窗）。 */
    @Volatile
    private var startupBatteryPrompted = false

    @Volatile
    private var lastReport: KeepAliveReport? = null

    /** 最近一次报告（UI 复用，不必重跑检测）。 */
    fun lastReport(): KeepAliveReport? = lastReport

    /**
     * 后台线程跑一次全量检测，结果回到主线程回调。任何异常都不外抛。
     */
    fun diagnoseAsync(ctx: Context, activity: Activity?, onMain: ((KeepAliveReport) -> Unit)?) {
        val appCtx = ctx.applicationContext
        workHandler.post {
            val report = try {
                // 只有前台有 Activity 时才能判断"永久拒绝"（shouldShowRequestPermissionRationale 需要 Activity）
                KeepAliveReportBuilder.build(PermissionProbe.snapshot(appCtx, activity))
            } catch (t: Throwable) {
                Log.e(TAG, "诊断异常: ${t.message}")
                KeepAliveReportBuilder.build(emptyList())
            }
            lastReport = report
            if (onMain == null) return@post
            mainHandler.post {
                try {
                    onMain(report)
                } catch (t: Throwable) {
                    Log.e(TAG, "诊断回调异常: ${t.message}")
                }
            }
        }
    }

    /** 同步检测（调用方保证已在后台线程；用于服务启动路径）。 */
    fun diagnoseSync(ctx: Context): KeepAliveReport {
        val report = try {
            KeepAliveReportBuilder.build(PermissionProbe.snapshot(ctx.applicationContext, null))
        } catch (t: Throwable) {
            Log.e(TAG, "diagnoseSync 异常: ${t.message}")
            KeepAliveReportBuilder.build(emptyList())
        }
        lastReport = report
        return report
    }

    /**
     * ★ 入口一：App 启动路径（Activity）。静默检测 + 至多一个电池优化白名单弹窗。
     *
     * 为什么只有电池白名单能在启动时弹：它是唯一"一次系统确认框"就能真正完成的保活授权，
     * 且直接决定被控端会不会被 MIUI PowerKeeper 杀掉；其余（无障碍/自启动/悬浮窗/所有文件访问）
     * 必须跳系统页且无法自动完成，启动时连弹会严重打扰，故留给「一键授权」。
     *
     * 已豁免时静默跳过、不重复弹；用 batteryAuthGuided 持久化，避免用户拒绝后每次启动再弹。
     */
    fun onStartup(activity: Activity) {
        try {
            if (activity.isFinishing) return
            val ctx = activity.applicationContext
            diagnoseAsync(ctx, activity, null)
            workHandler.post {
                try {
                    if (PermissionProbe.isKeepAliveEffective(ctx)) {
                        Log.i(TAG, "★ 启动自检：已在电池优化白名单（或 EMUI 后台活动已放行），静默跳过弹窗")
                        return@post
                    }
                    if (startupBatteryPrompted) return@post
                    if (SettingUtils.batteryAuthGuided) {
                        Log.i(TAG, "★ 启动自检：未进白名单，但此前已引导过（batteryAuthGuided），" +
                                "按防打扰要求本次启动不再弹窗，可在 App 内点「一键授权」补授权")
                        return@post
                    }
                    startupBatteryPrompted = true
                    SettingUtils.batteryAuthGuided = true
                    mainHandler.post {
                        try {
                            if (activity.isFinishing) return@post
                            val result = PermissionRequests.requestBatteryWhitelist(activity)
                            Log.i(TAG, "★ 启动路径申请电池优化白名单，跳转结果=$result（本次启动唯一的一个弹窗）")
                        } catch (t: Throwable) {
                            Log.w(TAG, "启动路径申请电池白名单异常: ${t.message}")
                        }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "启动自检异常: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onStartup 异常: ${t.message}")
        }
    }

    /**
     * ★ 入口一（服务侧）：RelayServerService.onCreate 调用。只做检测 + 日志，不弹任何窗
     *（后台起 Activity 在 Android 10+ 会被拦，且违背防打扰要求）。
     */
    fun onServiceStartup(ctx: Context) {
        val appCtx = ctx.applicationContext
        workHandler.post {
            try {
                val report = diagnoseSync(appCtx)
                KeepAliveReportBuilder.logReport(report)
                Log.i(TAG, "★ 服务启动自检：已授予 ${report.autoHandled.size} 项，" +
                        "待手工 ${report.needManual.size} 项，不可自动 ${report.unsupported.size} 项（详见报告）")
            } catch (t: Throwable) {
                Log.w(TAG, "服务启动自检异常: ${t.message}")
            }
        }
    }

    /**
     * ★ 入口二：「一键授权」收尾。检测 + 出三类清单（界面弹窗 + logcat）。
     * 用户从系统设置页返回后也可以再次调用，看到"还剩什么"。
     */
    fun reportAndGuide(activity: Activity, alsoLog: Boolean = true) {
        diagnoseAsync(activity.applicationContext, activity) { report ->
            if (alsoLog) KeepAliveReportBuilder.logReport(report)
            try {
                if (!activity.isFinishing) KeepAliveReportBuilder.show(activity, report)
            } catch (t: Throwable) {
                Log.w(TAG, "展示一键授权报告异常: ${t.message}")
            }
            // 全部就绪则记入既有标记（保持与旧逻辑兼容：autoAuthorizeDone=true 后不再重复触发自检弹窗）
            if (report.allClear) {
                SettingUtils.autoAuthorizeDone = true
                Log.i(TAG, "★ 一键授权：全部权限已就绪，标记 autoAuthorizeDone")
            }
        }
    }

    /** 仅出报告不弹窗（供「授权自检报告」按钮复用，不打扰）。 */
    fun snapshotNow(activity: Activity) {
        diagnoseAsync(activity.applicationContext, activity) { report ->
            KeepAliveReportBuilder.logReport(report)
            try {
                if (!activity.isFinishing) KeepAliveReportBuilder.show(activity, report)
            } catch (t: Throwable) {
                Log.w(TAG, "snapshotNow 展示异常: ${t.message}")
            }
        }
    }
}
