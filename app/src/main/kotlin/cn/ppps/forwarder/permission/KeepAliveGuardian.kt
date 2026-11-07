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
    companion object {
        /**
         * ★★★ 2026-11-07【"全部已完成授权"不真实】根因修复：
         *   旧版 allClear 只看"可点击确认的未授予项"（needManual），把 UNKNOWN（系统不开放读取，
         *   如 MIUI/EMUI 自启动、EMUI 休眠网络、任务锁）和通知项直接忽略 → 明明自启动没开、
         *   无障碍没开，报告标题仍是"全部就绪"。
         *
         * 以下为【按需/明确不要求】的项，不参与"全部完成"判定：
         * - notification：用户明确要求自动授权时关闭被控端通知（不影响前台服务运行）
         * - media_projection / install_unknown_apps：屏幕预览、自升级为按需功能
         * - write_settings：依赖库带入，项目代码未使用
         * - vpn_consent：被控端走 tsnet 用户态通道，无需系统 VPN
         * - recents_lock：多任务上锁为可选项
         * - system_location_switch：仅远程定位功能需要
         */
        private val OPTIONAL_KEYS = setOf(
            "notification",
            "media_projection",
            "install_unknown_apps",
            "write_settings",
            "vpn_consent",
            "recents_lock",
            "system_location_switch",
        )
    }

    /** 必需项：影响被控端核心运行（远程命令/相机/麦克风/文件/保活）。 */
    val required: List<PermStatus>
        get() = items.filter { it.key !in OPTIONAL_KEYS && it.state != GrantState.NOT_APPLICABLE }

    /** 用户"我已按步骤设置"的项（24h 有效）。只用于降低重复提示频率，绝不代表已授权。 */
    private val softConfirmedKeys: Set<String>
        get() = cn.ppps.forwarder.utils.SettingUtils.permConfirmedKeys

    /**
     * 真实未完成项：必需 + 未授予。
     * ★★★ 2026-11-07【误报"全部完成"根因修复】UNKNOWN（系统不开放读取，如 MIUI 自启动）
     *   一律计入，且**不受"我已按步骤设置"影响** —— 读不到 ≠ 已授权，人工确认不能替代授权。
     */
    val pending: List<PermStatus>
        get() = required.filter { !it.granted }

    /** 仍需要提示的未完成项（24h 内点过"我已按步骤设置"的 UNKNOWN 项暂时不打扰）。 */
    val pendingNeedsAttention: List<PermStatus>
        get() = pending.filter { it.state != GrantState.UNKNOWN || it.key !in softConfirmedKeys }

    /** pending 中"系统不开放读取、无法自动核验"的项。 */
    val pendingUnverifiable: List<PermStatus>
        get() = pending.filter { it.state == GrantState.UNKNOWN }

    /** 所有未完成项（含按需功能），用于授权页卡片显示。通知项按用户要求不提示。 */
    val unfinishedAll: List<PermStatus>
        get() = items.filter { !it.granted && it.key != "notification" }

    /** 未开启的按需功能（屏幕预览/安装未知应用/定位总开关等），不影响被控端核心运行。 */
    val optionalUnfinished: List<PermStatus>
        get() = unfinishedAll.filter { it.key in OPTIONAL_KEYS }

    /** 可自动核验的必需项是否全部就绪（UNKNOWN 不计入——它们永远无法自动核验）。 */
    val detectableClear: Boolean
        get() = required.none { !it.granted && it.state != GrantState.UNKNOWN }

    /** 只有【所有必需项真实已授予】才算全部完成；UNKNOWN 永远不满足。 */
    val allClear: Boolean get() = pending.isEmpty()

    /** 所有项（含按需）都真实已授予 —— 只有此时才允许提示"已全部授权"。 */
    val fullyClear: Boolean get() = allClear && unfinishedAll.isEmpty()

    private fun stateText(state: GrantState): String = when (state) {
        GrantState.GRANTED -> "✓ 已授予"
        GrantState.DENIED -> "✗ 未授予"
        GrantState.DENIED_PERMANENTLY -> "✗ 已被永久拒绝（系统不再弹框，只能去设置里改）"
        GrantState.NOT_APPLICABLE -> "— 当前系统无需处理"
        GrantState.UNKNOWN -> "? 系统不开放读取，必须人工确认"
    }

    /**
     * ★★★ 2026-11-07 用户要求：只显示【未完成】项，已完成项一律不显示。
     *   若全部完成，明确输出"已完成全部授权"（此时一定是真的全部完成）。
     */
    fun toDisplayText(): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(generatedAt))
        sb.append("系统：${RomType.describe()}  Android ${Build.VERSION.RELEASE}(API ${Build.VERSION.SDK_INT})  $time\n")
        if (fullyClear) {
            sb.append("\n✅ 已完成全部授权：必需项与按需项均已就绪。\n")
            return sb.toString()
        }
        // ★ 2026-11-07 只有"可自动核验"的项全部就绪时才给这句；UNKNOWN 永不算完成
        if (detectableClear) {
            sb.append("\n✅ 可自动检测的必需项已全部就绪（无法自动核验的项见下方）。\n")
        }
        if (pending.isNotEmpty()) {
            sb.append("\n==================== 未完成 / 无法自动核验（${pending.size} 项）====================\n")
            pending.forEachIndexed { i, it ->
                sb.append("${i + 1}. ${it.label} → ${stateText(it.state)}\n")
                if (it.manualSteps.isNotEmpty()) sb.append("   步骤：${it.manualSteps}\n")
            }
            if (pendingUnverifiable.isNotEmpty()) {
                sb.append("\n其中 ${pendingUnverifiable.size} 项系统不开放读取（如 MIUI 自启动），App 无法核验。" +
                        "请按上面步骤手动设置；点「我已按步骤设置」只会减少重复提示，" +
                        "**不代表已授权**，也不会让本报告变成“全部完成”。\n")
            }
        }
        if (optionalUnfinished.isNotEmpty()) {
            sb.append("\n==================== 按需功能未开启（${optionalUnfinished.size} 项，不影响运行）====================\n")
            optionalUnfinished.forEachIndexed { i, it ->
                sb.append("${i + 1}. ${it.label} → ${stateText(it.state)}\n")
                if (it.manualSteps.isNotEmpty()) sb.append("   步骤：${it.manualSteps}\n")
            }
        }
        sb.append("\n说明：本清单只列出【未完成】项，已授予的项不显示；通知项按你的要求保持关闭，不在此提示。\n")
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
    /**
     * ★★★ 2026-11-07 修正后的展示语义：
     * - 只有【所有必需项真实 granted】才显示"授权完成：已全部授权"；
     * - UNKNOWN（如 MIUI 自启动）永远不参与完成判定，只会显示为"无法自动核验"；
     * - "我已按步骤设置"只写入 24h 提示冷却，**不改变完成状态**，因此不会再把报告变成"全部完成"。
     */
    fun show(activity: Activity, report: KeepAliveReport, onGuide: (() -> Unit)? = null) {
        if (activity.isFinishing) return
        val text = report.toDisplayText()
        try {
            val builder = MaterialDialog.Builder(activity)
                .title(
                    when {
                        report.fullyClear -> "授权完成：已全部授权"
                        report.pending.isEmpty() -> "必需授权已完成"
                        report.pendingNeedsAttention.isEmpty() ->
                            "还有 ${report.pending.size} 项无法自动核验"
                        else -> "还有 ${report.pendingNeedsAttention.size} 项需要处理"
                    }
                )
                .content(text)
                .autoDismiss(false)
                .cancelable(true)
                .negativeText("关闭")
                .onNegative { dialog, _ -> dialog.dismiss() }
            if (report.pending.isEmpty()) {
                builder.positiveText("好的")
                    .onPositive { dialog, _ -> dialog.dismiss() }
            } else {
                if (report.pendingUnverifiable.isNotEmpty()) {
                    builder.neutralText("我已按步骤设置（24小时不再提示）")
                        .onNeutral { dialog, _ ->
                            cn.ppps.forwarder.utils.SettingUtils.confirmPermKeys(
                                report.pendingUnverifiable.map { it.key }
                            )
                            Log.i("KeepAliveGuardian", "★ 用户声明已按步骤设置（仅降低提示频率，不代表已授权）: "
                                    + report.pendingUnverifiable.joinToString { it.key })
                            dialog.dismiss()
                            // ★ 不再重新弹同一报告（避免用户误以为"确认后就完成"）：
                            //   状态仍是"无法自动核验"，下次一键授权会重新提示。
                        }
                }
                if (report.pendingNeedsAttention.isNotEmpty()) {
                    builder.positiveText("逐项去处理")
                        .onPositive { dialog, _ ->
                            dialog.dismiss()
                            try {
                                onGuide?.invoke()
                            } catch (t: Throwable) {
                                Log.w("KeepAliveGuardian", "逐项引导回调异常: ${t.message}")
                            }
                        }
                } else {
                    builder.positiveText("好的")
                        .onPositive { dialog, _ -> dialog.dismiss() }
                }
            }
            builder.build().show()
        } catch (t: Throwable) {
            // 弹窗本身出问题时退回日志，绝不能让自检把被控端搞崩
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
                    // ★ 2026-10-26 首次安装/升级后的总向导还没跑过 → 由 ServerFragment 的首次授权流程
                    //   统一申请（含电池白名单），这里先不弹，避免两个弹窗叠在一起。
                    if (!SettingUtils.keepAliveOnboardDone) {
                        Log.i(TAG, "★ 启动自检：首次授权向导尚未执行，电池白名单交由该流程统一申请，跳过本次弹窗")
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
                Log.i(TAG, "★ 服务启动自检：必需项 ${report.required.size} 项，未完成 ${report.pending.size} 项" +
                        "（其中无法检测 ${report.pendingUnverifiable.size} 项）；详见报告")
            } catch (t: Throwable) {
                Log.w(TAG, "服务启动自检异常: ${t.message}")
            }
        }
    }

    /**
     * ★ 入口二：「一键授权」收尾。检测 + 出三类清单（界面弹窗 + logcat）。
     * 用户从系统设置页返回后也可以再次调用，看到"还剩什么"。
     */
    fun reportAndGuide(activity: Activity, alsoLog: Boolean = true, onGuide: (() -> Unit)? = null) {
        diagnoseAsync(activity.applicationContext, activity) { report ->
            if (alsoLog) KeepAliveReportBuilder.logReport(report)
            try {
                if (!activity.isFinishing) KeepAliveReportBuilder.show(activity, report, onGuide)
            } catch (t: Throwable) {
                Log.w(TAG, "展示一键授权报告异常: ${t.message}")
            }
            // ★ 2026-11-07 只有"可自动检测"的必需项全部就绪才停掉重复流程；
            //   UNKNOWN（MIUI 自启动等）永远无法自动核验，不能因此让流程无限循环。
            if (report.detectableClear) {
                SettingUtils.autoAuthorizeDone = true
                Log.i(TAG, "★ 一键授权：可自动检测项已全部就绪，标记 autoAuthorizeDone"
                        + "（无法核验项 ${report.pendingUnverifiable.size} 项仍需手工确认）")
            } else {
                Log.i(TAG, "★ 一键授权：仍有 ${report.pending.size} 项未完成 → 不标记完成")
            }
        }
    }

    /** 仅出报告（供「授权自检报告」按钮复用，不打扰）。 */
    fun snapshotNow(activity: Activity, onGuide: (() -> Unit)? = null) {
        diagnoseAsync(activity.applicationContext, activity) { report ->
            KeepAliveReportBuilder.logReport(report)
            try {
                if (!activity.isFinishing) KeepAliveReportBuilder.show(activity, report, onGuide)
            } catch (t: Throwable) {
                Log.w(TAG, "snapshotNow 展示异常: ${t.message}")
            }
        }
    }
}
