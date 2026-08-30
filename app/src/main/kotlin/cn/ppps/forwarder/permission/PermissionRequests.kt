package cn.ppps.forwarder.permission

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import cn.ppps.forwarder.utils.Log

/**
 * ★ 2026-08-28 OEM（MIUI / EMUI 等）自启动与后台限制引导 + 各类跳转结果。
 *
 * 硬要求：任何一步都不得抛异常到调用方（国产 ROM 会裁剪/改系统设置页组件名，
 * 直接 startActivity 常见 ActivityNotFoundException / SecurityException），
 * 找不到组件时退回"给出可复制的手工步骤文案"，绝不崩。
 */

/** 一次跳转的结果，用于把"是否真的打开了对应设置页"如实反馈到 UI。 */
enum class OpenResult {
    /** 已成功拉起系统页面 */
    OPENED,

    /** 系统里找不到对应组件（该 ROM 无此入口或已改名） */
    COMPONENT_MISSING,

    /** 拉起时抛异常（权限受限，如 EMUI 启动管理页需要系统权限） */
    FAILED,

    /** 无需跳转（已授予 / 当前版本不适用） */
    NOT_NEEDED,
}

/**
 * OEM 自启动 / 后台活动管理页。
 * 第三方应用【读不到】MIUI 的自启动开关状态（系统未暴露任何公开 API），
 * 因此检测口径保持诚实：能读到就用真实 API（EMUI 用 appops RUN_ANY_IN_BACKGROUND），
 * 读不到就返回 UNKNOWN 并在 UI 上要求手工确认一次，绝不谎称"已自动授权"。
 */
object OemGuide {

    private const val TAG = "OemGuide"

    /** 各 ROM 的候选设置页（按优先级尝试，全部先经 resolveActivity 校验，避免抛异常）。 */
    private fun candidates(): List<Pair<String, String>> = when {
        RomType.isXiaomi -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.miui.securitycenter" to "com.miui.permcenter.permissions.PermissionsEditorActivity",
            "com.miui.safecenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        RomType.isHuawei -> listOf(
            // EMUI/Android 12 起这两个 Activity 需要系统权限，第三方跳会 SecurityException，
            // 仍保留作为老版本可直达的兜底
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        )
        RomType.isOppo -> listOf(
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        )
        RomType.isVivo -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        )
        RomType.isSamsung -> listOf(
            "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.ram.AutoRunActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        )
        else -> emptyList()
    }

    /** 找到第一个真实存在且可拉起的组件（只查询，不启动）。 */
    fun resolveComponent(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        for ((pkg, cls) in candidates()) {
            try {
                val intent = Intent().setComponent(ComponentName(pkg, cls))
                if (pm.resolveActivity(intent, 0) != null) return ComponentName(pkg, cls)
            } catch (t: Throwable) {
                Log.w(TAG, "resolveActivity($pkg/$cls) 异常: ${t.message}")
            }
        }
        return null
    }

    /** 该 ROM 是否有可直达的自启动/后台管理页（用于报告里区分"可跳转引导"与"仅手工步骤"）。 */
    fun hasJumpEntry(ctx: Context): Boolean = resolveComponent(ctx) != null

    /** 检测口径描述（展示在报告里，说明结论怎么来的）。 */
    fun probeDetector(): String = when {
        !RomType.needsOemGuide -> "原生/其他 ROM，无厂商后台管控"
        RomType.isHuawei -> "appops RUN_ANY_IN_BACKGROUND（EMUI 后台活动开关）+ 组件可达性探测"
        else -> "厂商自启动开关无公开读取 API → 只能手工确认（组件可达性已探测）"
    }

    /**
     * 自启动/后台限制状态：
     * - 非国产 ROM → NOT_APPLICABLE
     * - 华为 → 用真实 appops 判定（未被 IGNORED 即视为已放行）
     * - 小米等 → UNKNOWN（系统不暴露，如实标注"需手工确认"）
     */
    fun probeAutostartState(ctx: Context): GrantState {
        if (!RomType.needsOemGuide) return GrantState.NOT_APPLICABLE
        if (RomType.isHuawei) {
            return try {
                val am = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
                @Suppress("UNCHECKED_CAST")
                val mode = am.javaClass.getMethod(
                    "checkOpNoThrow", String::class.java, Int::class.javaPrimitiveType, String::class.java
                ).invoke(am, "android:run_any_in_background", ctx.applicationInfo.uid, ctx.packageName) as? Int
                when {
                    mode == null -> GrantState.UNKNOWN
                    mode == android.app.AppOpsManager.MODE_IGNORED -> GrantState.DENIED
                    else -> GrantState.GRANTED
                }
            } catch (t: Throwable) {
                Log.w(TAG, "EMUI 后台活动 appops 读取失败: ${t.message}")
                GrantState.UNKNOWN
            }
        }
        return GrantState.UNKNOWN
    }

    fun manualSteps(): String = when {
        RomType.isXiaomi ->
            "MIUI 自启动（无读取 API，必须手工确认一次）：\n" +
                    "① 安全中心 → 授权管理 → 自启动管理 → 允许「SmsForwarder」自启动；\n" +
                    "② 设置 → 应用设置 → 应用管理 → SmsForwarder → 省电策略 → 选「无限制」；\n" +
                    "③ 最近任务里给 SmsForwarder 下拉加锁（防止一键清理杀掉）；\n" +
                    "④ 如仍被杀：设置 → 电池与性能 → 关闭「智能省电」对该应用的作用"
        RomType.isHuawei ->
            "EMUI 启动管理（该页对第三方应用受系统权限保护，可能无法直达）：\n" +
                    "① 手机管家 → 应用启动管理 → 找到 SmsForwarder → 关闭「自动管理」；\n" +
                    "② 在弹出框里手动勾选 自启动 + 关联启动 + 后台活动 → 确定；\n" +
                    "③ 设置 → 应用 → 应用管理 → SmsForwarder → 省电/启动管理 同样设为允许"
        RomType.isOppo ->
            "ColorOS：手机管家 → 权限隐私 → 自启动管理 允许 SmsForwarder；" +
                    "设置 → 电池 → 更多设置 → 深度休眠/后台冻结 里排除 SmsForwarder"
        RomType.isVivo ->
            "OriginOS：i管家 → 应用管理 → 允许自启动 + 允许后台高耗电"
        RomType.isSamsung ->
            "三星：设备维护/智能管理器 → 电池 → 后台使用限制 → 将 SmsForwarder 设为「不受限制」"
        else -> "当前 ROM 无厂商自启动入口，仅需保证电池优化白名单即可"
    }

    /**
     * 跳转自启动/后台管理页：优先直达厂商组件；EMUI 直达被拒时退回本应用详情页；
     * 全部失败只返回结果码，由调用方在 UI 上展示手工步骤。
     */
    fun openAutostartPage(ctx: Context): OpenResult {
        val comp = resolveComponent(ctx)
        if (comp == null) {
            Log.w(TAG, "未找到 ${RomType.describe()} 的自启动管理组件")
            return OpenResult.COMPONENT_MISSING
        }
        return try {
            val intent = Intent().setComponent(comp)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "拉起 ${comp.flattenToShortString()} 失败: ${t.message}")
            // EMUI：启动管理页要求系统权限 → 退回本应用详情页（详情页内有启动管理入口）
            if (RomType.isHuawei && PermissionRequests.openAppDetails(ctx) == OpenResult.OPENED) OpenResult.OPENED else OpenResult.FAILED
        }
    }
}

/**
 * 申请/引导层：所有需要 Activity 或系统设置页的动作集中在这里，
 * 让「启动路径」与「一键授权」两条入口复用同一实现。
 */
object PermissionRequests {

    private const val TAG = "PermRequest"

    /** 批量运行时权限的申请码（与 Fragment 里 onActivityResult/onRequestPermissionsResult 对齐） */
    const val REQ_BATCH_RUNTIME = 0x5A01
    const val REQ_BACKGROUND_LOCATION = 0x5A02

    /**
     * ★ 电池优化白名单：先弹系统"忽略电池优化？"确认框（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
     * + package: data），MIUI/EMUI 上该 Intent 常被裁剪而抛 ActivityNotFoundException，
     * 因此必须逐级兜底：通用电池优化列表页 → 机型省电策略页 → 本应用详情页。
     * 已豁免时由调用方先判断并静默跳过，不在这里重复弹窗。
     */
    fun requestBatteryWhitelist(ctx: Context): OpenResult {
        if (PermissionProbe.isBatteryWhitelisted(ctx)) return OpenResult.NOT_NEEDED
        // 1) 首选：发起系统级"忽略电池优化"请求
        //    ★ 2026-08-29 实测措辞修正：原生 ROM 上是"忽略电池优化？"确认框；
        //      MIUI(V816) 会把该 Intent 重定向到 com.miui.powerkeeper 的后台应用配置页
        //      （HiddenAppsConfigActivity，即"省电策略"列表），需要用户再点一次"无限制"，
        //      并不会出现确认框。日志只描述"已发起跳转"，落到哪种界面由 ROM 决定，
        //      免得排查时误以为确认框被吞了。
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:" + ctx.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Log.i(TAG, "★ 已发起「忽略电池优化」请求：原生 ROM 为系统确认框；" +
                    "MIUI 实测会重定向到 com.miui.powerkeeper 后台配置页（需再选手动限制→无限制），" +
                    "并非弹确认框")
            return OpenResult.OPENED
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 不可用，走兜底: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "请求电池优化白名单异常，走兜底: ${t.message}")
        }
        // 2) 通用电池优化列表页（用户在其中把本应用改为"不优化"）
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            Log.i(TAG, "★ 已打开通用电池优化列表页")
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "通用电池优化列表页打不开: ${t.message}")
        }
        // 3) 机型省电策略/后台管理页
        val oem = OemGuide.openAutostartPage(ctx)
        if (oem == OpenResult.OPENED) {
            Log.i(TAG, "★ 已按机型(${RomType.describe()})打开后台/省电管理页")
            return OpenResult.OPENED
        }
        // 4) 本应用详情页兜底
        if (openAppDetails(ctx) == OpenResult.OPENED) return OpenResult.OPENED
        return OpenResult.FAILED
    }

    /**
     * ★ 一次性批量申请"项目实际用到的"未授予运行时权限。
     *
     * ★★ 2026-08-29 红米K40(MIUI V816 / API33) 端到端实测结论：
     *  1) 一次 requestPermissions 传 7 项【是有效的】，系统侧只拉起一个
     *     com.lbe.security.miui/...GrantPermissionsActivity（整个流程 START 计数=1），
     *     由 ROM 自己串行翻页逐项确认（实测 7 项全部走到、全部授予）。
     *     即"一次覆盖多个权限"＝一个入口队列，而不是一个界面里放 7 个勾。
     *  2) 【不剔除】可疑的"永久拒绝"项：实测 `pm revoke` 之后
     *     shouldShowRequestPermissionRationale 同样返回 false，若据此把权限从批量里剔掉，
     *     MIUI 上会变成"一项都不申请"，用户只能去设置页手点。是否真为永久拒绝，
     *     改由申请结果回调（onRequestPermissionsResult）配合"已申请过"登记来判定，
     *     见 PermissionProbe.isRuntimePermanentlyDenied。
     *  3) 后台定位 ACCESS_BACKGROUND_LOCATION 不进批量（混在批量里会被系统直接拒绝），
     *     仍由 requestBackgroundLocation 单独发起。
     *
     * @return 实际发起申请的权限列表（空表示无需申请）
     */
    fun requestMissingRuntimeBatch(activity: Activity, requestCode: Int = REQ_BATCH_RUNTIME): List<String> {
        val missing = missingRuntimePermissions(activity)
        if (missing.isEmpty()) {
            Log.i(TAG, "★ 批量运行时权限：全部已授予，无需申请")
            return emptyList()
        }
        PermissionProbe.markRuntimeRequested(activity, missing)
        try {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity, missing.toTypedArray(), requestCode
            )
            Log.i(TAG, "★ 批量申请 ${missing.size} 项运行时权限: $missing")
        } catch (t: Throwable) {
            Log.e(TAG, "批量申请运行时权限异常: ${t.message}")
        }
        return missing
    }

    /**
     * ★★ 2026-08-29 修复"批量申请没有结果收口"：必须用 Fragment.requestPermissions 发起，
     * 结果才会带 Fragment 的 pending 标记回到 Fragment.onRequestPermissionsResult。
     * 旧写法 ActivityCompat.requestPermissions(requireActivity(), …) 的 requestCode 不属于任何
     * Fragment，androidx FragmentActivity.onRequestPermissionsResult 里 findRequestPending 命中不了，
     * ServerFragment 中 REQ_BATCH_RUNTIME 分支（统计授予数 / 引导永久拒绝项）实测从未执行过。
     */
    fun requestMissingRuntimeBatch(fragment: androidx.fragment.app.Fragment, requestCode: Int = REQ_BATCH_RUNTIME): List<String> {
        val activity = fragment.activity ?: return emptyList()
        val missing = missingRuntimePermissions(activity)
        if (missing.isEmpty()) {
            Log.i(TAG, "★ 批量运行时权限：全部已授予，无需申请")
            return emptyList()
        }
        PermissionProbe.markRuntimeRequested(activity, missing)
        return try {
            fragment.requestPermissions(missing.toTypedArray(), requestCode)
            Log.i(TAG, "★ 批量申请 ${missing.size} 项运行时权限(Fragment 通道，有结果回调): $missing")
            missing
        } catch (t: Throwable) {
            Log.e(TAG, "批量申请运行时权限异常(Fragment 通道): ${t.message}")
            // 兜底退回 Activity 通道：至少把框弹出来，只是没有结果回调
            requestMissingRuntimeBatch(activity, requestCode)
        }
    }

    /** 批量清单：项目用到、manifest 已声明、当前未授予的运行时权限（后台定位除外）。 */
    fun missingRuntimePermissions(ctx: Context): List<String> {
        val missing = mutableListOf<String>()
        for (e in PermissionInventory.runtimeEntries) {
            if (PermissionProbe.checkRuntime(ctx, e.permission) != GrantState.GRANTED) {
                if (PermissionProbe.isDeclared(ctx, e.permission).not()) continue
                missing.add(e.permission)
            }
        }
        return missing
    }

    /** 需要用户去应用详情页处理的"永久拒绝"权限（requestPermissions 已无效）。 */
    fun permanentlyDeniedRuntimePermissions(activity: Activity): List<String> {
        val out = mutableListOf<String>()
        for (e in PermissionInventory.runtimeEntries) {
            if (PermissionProbe.checkRuntime(activity, e.permission) == GrantState.DENIED &&
                PermissionProbe.isRuntimePermanentlyDenied(activity, e.permission)
            ) {
                out.add(e.permission)
            }
        }
        return out
    }

    /** 单独申请后台定位（Android 10+ 必须在前台定位授予后单独发起）。 */
    fun requestBackgroundLocation(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (PermissionProbe.checkRuntime(activity, PermissionInventory.BACKGROUND_LOCATION) == GrantState.GRANTED) return
        if (PermissionProbe.checkRuntime(activity, "android.permission.ACCESS_FINE_LOCATION") != GrantState.GRANTED) return
        try {
            androidx.core.app.ActivityCompat.requestPermissions(
                activity, arrayOf(PermissionInventory.BACKGROUND_LOCATION), REQ_BACKGROUND_LOCATION
            )
        } catch (t: Throwable) {
            Log.w(TAG, "申请后台定位异常: ${t.message}")
        }
    }

    /** 悬浮窗授权页（ACTION_MANAGE_OVERLAY_PERMISSION + package: data）。 */
    fun openOverlaySettings(ctx: Context): OpenResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return OpenResult.NOT_NEEDED
        if (PermissionProbe.isOverlayGranted(ctx)) return OpenResult.NOT_NEEDED
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:" + ctx.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开悬浮窗设置失败: ${t.message}")
        }
        return openAppDetails(ctx)
    }

    /** 「所有文件访问」设置页（Android 11+）。 */
    fun openAllFilesAccess(ctx: Context): OpenResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return OpenResult.NOT_NEEDED
        if (PermissionProbe.isAllFilesAccessGranted(ctx)) return OpenResult.NOT_NEEDED
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:" + ctx.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开应用级所有文件访问失败: ${t.message}")
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开通用所有文件访问页失败: ${t.message}")
        }
        return openAppDetails(ctx)
    }

    /** 无障碍设置页（只能引导用户手动勾选，系统不允许第三方程序化开启）。 */
    fun openAccessibilitySettings(ctx: Context): OpenResult {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开无障碍设置失败: ${t.message}")
        }
        return openAppDetails(ctx)
    }

    /** 通知设置页（Android 8+ 支持直接带 EXTRA_APP_PACKAGE）。 */
    fun openNotificationSettings(ctx: Context): OpenResult {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            } else {
                @Suppress("DEPRECATION")
                Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                    .putExtra("app_package", ctx.packageName)
                    .putExtra("app_uid", ctx.applicationInfo.uid)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开通知设置失败: ${t.message}")
        }
        return openAppDetails(ctx)
    }

    /** 修改系统设置（WRITE_SETTINGS）授权页 —— 仅在 manifest 声明时才有意义。 */
    fun openWriteSettings(ctx: Context): OpenResult {
        if (!PermissionProbe.isDeclared(ctx, "android.permission.WRITE_SETTINGS")) return OpenResult.NOT_NEEDED
        if (PermissionProbe.isWriteSettingsGranted(ctx)) return OpenResult.NOT_NEEDED
        try {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:" + ctx.packageName)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开修改系统设置页失败: ${t.message}")
        }
        return openAppDetails(ctx)
    }

    /** 本应用详情页（MIUI/EMUI 上从这里能进到省电策略/启动管理/权限管理）。 */
    fun openAppDetails(ctx: Context): OpenResult {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", ctx.packageName, null)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return OpenResult.OPENED
        } catch (t: Throwable) {
            Log.w(TAG, "打开应用详情页失败: ${t.message}")
        }
        return OpenResult.FAILED
    }
}
