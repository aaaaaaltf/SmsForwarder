package cn.ppps.forwarder.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.ppps.forwarder.relay.ScreenStreamManager
import cn.ppps.forwarder.relay.TouchControlService
import cn.ppps.forwarder.service.ScreenProjectionService
import cn.ppps.forwarder.tailscale.TailscaleManager
import cn.ppps.forwarder.utils.Log

/**
 * ★ 2026-08-28 被控端「权限 + 保活」统一自诊断模块（纯检测层，无 UI、无副作用）。
 *
 * 设计目标（对应需求）：
 * 1. App 启动路径与「一键授权」共用同一份权限清单与检测函数，避免两处判定口径不一致；
 * 2. 每一项权限都用【真实系统 API】检测（Settings.canDrawOverlays / AppOpsManager /
 *    NotificationManagerCompat.areNotificationsEnabled / AccessibilityManager 已启用服务列表 /
 *    PowerManager.isIgnoringBatteryOptimizations / VpnService.prepare），不靠猜、不看 SP 缓存；
 * 3. 全部函数必须能在任意线程（含服务后台线程）与任意异常下安全返回：
 *    MIUI/EMUI 会裁剪部分系统 API，任何一处抛异常都不能把被控端带崩。
 *
 * 权限清单来源：不是把 manifest 的 32 条 uses-permission 全弹一遍，而是逐个 grep 代码里
 * 真实用到的地方（checkSelfPermission / ContentResolver.query / MediaRecorder / CameraX /
 * TelephonyManager / LocationClient ...）得出的【实际用到的】清单，见 [PermissionInventory]。
 */

/** 授权状态。DENIED_PERMANENTLY 表示系统已不再弹框（勾选"不再询问"/USER_FIXED），只能跳设置页。 */
enum class GrantState {
    GRANTED,
    DENIED,
    DENIED_PERMANENTLY,
    /** 当前系统版本/ROM 上不适用（如 Android 13 起 READ_EXTERNAL_STORAGE 已废弃） */
    NOT_APPLICABLE,
    /** 无法检测或该机型无对应入口 */
    UNKNOWN,
}

/** 权限类别：决定能否用 requestPermissions 自动授予。 */
enum class PermissionKind {
    /** 危险权限，可批量 requestPermissions 自动弹框 */
    RUNTIME,

    /** 特殊授权（悬浮窗/所有文件访问/无障碍/通知/电池白名单/VPN/屏幕捕获），必须走 Intent 或系统页 */
    SPECIAL,

    /** OEM 自启动/后台限制，只能跳厂商设置页引导 */
    OEM,

    /** 安装时自动授予（normal 级别），无需申请，仅列出以便核对 */
    INSTALL_TIME,
}

/** 单项检测结论 + 给用户的可复制手工步骤。 */
data class PermStatus(
    val key: String,
    val label: String,
    val kind: PermissionKind,
    val state: GrantState,
    /** 是否可由 App 自动完成（自动弹框或自动跳转系统页并由用户一次确认） */
    val autoFixable: Boolean,
    /** 无法自动时的精确手工步骤（含 MIUI/EMUI 菜单路径），直接展示给用户 */
    val manualSteps: String,
    /** 真实检测手段说明，便于排查"检测口径"问题 */
    val detector: String,
) {
    val granted: Boolean
        get() = state == GrantState.GRANTED || state == GrantState.NOT_APPLICABLE
}

/**
 * ROM 厂商识别。仅用于挑选自启动/后台限制设置页与手工步骤文案，
 * 不做任何"猜授权状态"的事。
 */
object RomType {

    private val man: String = (Build.MANUFACTURER ?: "").lowercase()
    private val brand: String = (Build.BRAND ?: "").lowercase()
    private val device: String = (Build.DEVICE ?: "").lowercase()

    val isHuawei: Boolean get() = man.contains("huawei") || man.contains("honor") || brand.contains("honor")
    val isXiaomi: Boolean get() = man.contains("xiaomi") || man.contains("redmi") || brand.contains("poco")
    val isOppo: Boolean get() = man.contains("oppo") || man.contains("realme") || man.contains("oneplus")
    val isVivo: Boolean get() = man.contains("vivo") || man.contains("iqoo")
    val isSamsung: Boolean get() = man.contains("samsung")

    /** 是否是需要额外"自启动/后台管理"引导的国产 ROM（MIUI / EMUI / ColorOS / OriginOS） */
    val needsOemGuide: Boolean get() = isHuawei || isXiaomi || isOppo || isVivo

    val miuiVersion: String
        get() = try {
            Build.VERSION.INCREMENTAL?.takeIf { it.isNotBlank() && isXiaomi } ?: ""
        } catch (_: Throwable) {
            ""
        }

    fun describe(): String = when {
        isXiaomi -> "小米/红米 MIUI(${miuiVersion})"
        isHuawei -> "华为/荣耀 EMUI"
        isOppo -> "OPPO/一加/realme"
        isVivo -> "vivo/iQOO"
        isSamsung -> "三星"
        else -> "原生/其他(${Build.MANUFACTURER})"
    }
}

/**
 * 项目"实际用到的"权限清单。每一项都注明在代码中的用途与检测函数，
 * 未使用的 manifest 声明（GET_ACCOUNTS / BATTERY_STATS / VIBRATE 等）不进入申请清单。
 */
object PermissionInventory {

    /** 需要动态申请、且项目功能真正用到的运行时权限（含用途说明）。 */
    data class RuntimeEntry(val permission: String, val label: String, val usage: String)

    /**
     * 运行时危险权限清单（按功能用途整理，逐个 grep 代码得到）：
     * - READ_SMS           : relay/RelayServerHandler「远程查短信」→ utils/PhoneUtils.getSmsInfoList
     * - READ_PHONE_STATE   : 远程查通话/设备信息 → utils/PhoneUtils（TelephonyManager、CallState）
     * - READ_PHONE_NUMBERS : 同上（本机号码），随电话组一起申请
     * - READ_CALL_LOG      : 远程查通话记录 → utils/PhoneUtils（CallLog.Calls.CONTENT_URI query）
     * - READ_CONTACTS      : 远程查联系人 → utils/PhoneUtils（Contacts content provider）
     * - WRITE_CONTACTS     : 远程加联系人 → utils/PhoneUtils
     * - ACCESS_FINE/COARSE_LOCATION + ACCESS_BACKGROUND_LOCATION : service/LocationService、utils/LocationUtils
     * - CAMERA             : relay/CameraStreamManager 摄像头推流
     * - RECORD_AUDIO       : relay/MicrophoneStreamManager 麦克风推流（WebRTC 免提）
     * - CALL_PHONE         : 远程主动拨号
     * - READ/WRITE_EXTERNAL_STORAGE : 仅 API<33（一键换新机备份文件读写）
     * - POST_NOTIFICATIONS : 仅 API>=33（前台服务通知，被控端保活必需）
     */
    val runtimeEntries: List<RuntimeEntry> by lazy {
        val list = mutableListOf(
            RuntimeEntry("android.permission.READ_SMS", "短信（读取）", "远程查短信"),
            RuntimeEntry("android.permission.READ_PHONE_STATE", "电话（状态）", "远程查通话/设备状态"),
            RuntimeEntry("android.permission.READ_PHONE_NUMBERS", "电话号码", "远程查本机号码"),
            RuntimeEntry("android.permission.READ_CALL_LOG", "通话记录", "远程查通话记录"),
            RuntimeEntry("android.permission.READ_CONTACTS", "通讯录（读）", "远程查联系人"),
            RuntimeEntry("android.permission.WRITE_CONTACTS", "通讯录（写）", "远程加联系人"),
            RuntimeEntry("android.permission.ACCESS_FINE_LOCATION", "精确定位", "远程定位"),
            RuntimeEntry("android.permission.ACCESS_COARSE_LOCATION", "粗略定位", "远程定位"),
            RuntimeEntry("android.permission.CAMERA", "相机", "远程摄像头推流"),
            RuntimeEntry("android.permission.RECORD_AUDIO", "麦克风", "远程监听/WebRTC 免提"),
            RuntimeEntry("android.permission.CALL_PHONE", "拨打电话", "远程主动拨号"),
        )
        // Android 13(API 33) 起存储权限按版本分叉
        if (Build.VERSION.SDK_INT < 33) {
            list.add(RuntimeEntry("android.permission.READ_EXTERNAL_STORAGE", "存储（读）", "备份/文件读写"))
            list.add(RuntimeEntry("android.permission.WRITE_EXTERNAL_STORAGE", "存储（写）", "备份/文件读写"))
        } else {
            list.add(RuntimeEntry("android.permission.POST_NOTIFICATIONS", "通知", "前台服务保活通知（Android 13+）"))
        }
        list
    }

    /**
     * 后台定位（ACCESS_BACKGROUND_LOCATION）：Android 10+ 必须在前台定位已授予后【单独】申请，
     * 混在批量里会被系统直接拒绝，所以从批量清单里单列出来。
     */
    const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

    /**
     * 安装时（normal/signature）自动授予、代码路径依赖但无需申请的权限：
     * 只用于「自检报告」里告诉用户"这些不用管"，不参与申请。
     */
    val installTimeEntries: List<Pair<String, String>> = listOf(
        "android.permission.INTERNET" to "中继/Tailscale 通信",
        "android.permission.ACCESS_NETWORK_STATE" to "网络可达性判定",
        "android.permission.ACCESS_WIFI_STATE" to "WLAN 信息",
        "android.permission.RECEIVE_BOOT_COMPLETED" to "开机自启被控端服务",
        "android.permission.WAKE_LOCK" to "持锁保活",
        "android.permission.VIBRATE" to "提示震动",
        "android.permission.MODIFY_AUDIO_SETTINGS" to "通话音频通路",
        "android.permission.FOREGROUND_SERVICE" to "前台服务",
        "android.permission.FOREGROUND_SERVICE_CAMERA" to "后台摄像头前台服务类型",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE" to "后台麦克风前台服务类型",
        "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" to "屏幕捕获前台服务类型",
        "android.permission.BIND_VPN_SERVICE" to "Tailscale VPN 服务",
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "申请进入电池优化白名单",
    )

    /** manifest 里声明了但项目代码没用到 → 不申请（需求要求"不是把 32 条全弹一遍"）。 */
    val notRequested: List<Pair<String, String>> = listOf(
        "android.permission.GET_ACCOUNTS" to "代码中无 AccountManager 调用",
        "android.permission.BATTERY_STATS" to "电量走 BatteryManager 系统服务，无需该权限",
    )
}

/**
 * 检测层。全部函数满足：① 任意线程可调；② 内部吞掉所有异常并给出保守结论；
 * ③ 结论只来自系统真实状态。
 */
object PermissionProbe {

    private const val TAG = "PermProbe"

    /**
     * 电池优化白名单（AOSP DeviceIdle）。
     * 这就是需求点名的"从未真正进过白名单"的判定函数：未豁免时 App 会被 MIUI PowerKeeper /
     * AOSP Doze 冻结，中继通道与 Tailscale VPN 服务反复被杀重建。
     */
    fun isBatteryWhitelisted(ctx: Context): Boolean {
        return try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } catch (t: Throwable) {
            Log.w(TAG, "isIgnoringBatteryOptimizations 异常: ${t.message}")
            false
        }
    }

    /**
     * 保活是否"够用"的宽松判定：标准白名单 OR（华为/荣耀）OP_RUN_ANY_IN_BACKGROUND 未被 IGNORED。
     * 保留既有口径（EMUI 上用户已设"不优化/允许后台活动"时标准 API 仍可能返回 false，
     * 会导致每次启动重复弹窗），本模块作为唯一实现，ServerFragment 也改为委托到这里。
     */
    fun isKeepAliveEffective(ctx: Context): Boolean {
        if (isBatteryWhitelisted(ctx)) return true
        if (RomType.isHuawei) {
            try {
                val am = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                @Suppress("UNCHECKED_CAST")
                val mode = am.javaClass.getMethod(
                    "checkOpNoThrow", String::class.java, Int::class.javaPrimitiveType, String::class.java
                ).invoke(am, "android:run_any_in_background", ctx.applicationInfo.uid, ctx.packageName) as? Int
                if (mode != null && mode != AppOpsManager.MODE_IGNORED) return true
            } catch (t: Throwable) {
                Log.w(TAG, "查 RUN_ANY_IN_BACKGROUND appops 失败: ${t.message}")
            }
        }
        return false
    }

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）：远程点亮屏幕 ScreenLighter 用 TYPE_APPLICATION_OVERLAY。 */
    fun isOverlayGranted(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (t: Throwable) {
        Log.w(TAG, "canDrawOverlays 异常: ${t.message}")
        false
    }

    /** WRITE_SETTINGS：仅当 manifest 声明才有意义（本项目未声明、代码未使用 → 不适用）。 */
    fun isWriteSettingsGranted(ctx: Context): Boolean = try {
        Settings.System.canWrite(ctx)
    } catch (t: Throwable) {
        false
    }

    /** 所有文件访问（MANAGE_EXTERNAL_STORAGE，Android 11+）。 */
    fun isAllFilesAccessGranted(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return try {
            Environment.isExternalStorageManager()
        } catch (t: Throwable) {
            Log.w(TAG, "isExternalStorageManager 异常: ${t.message}")
            false
        }
    }

    /** 通知是否可发（Android 13+ 受 POST_NOTIFICATIONS 约束，前台服务通知是被控端保活关键）。 */
    fun areNotificationsEnabled(ctx: Context): Boolean = try {
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    } catch (t: Throwable) {
        Log.w(TAG, "areNotificationsEnabled 异常: ${t.message}")
        false
    }

    /**
     * 无障碍服务（远程触摸 TouchControlService）是否真正启用：
     * ① Settings.Secure.enabled_accessibility_services 含本组件（系统持久化事实，进程未起也能判）；
     * ② AccessibilityManager 已启用服务列表（运行时事实）；
     * ③ 本进程已有服务实例（最强证据）。
     * 只看 ③ 会在"服务已启用但进程刚重启、尚未回调 onServiceConnected"时误判未授权。
     */
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val want = ComponentName(ctx, TouchControlService::class.java).flattenToString()
        try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabled.isNullOrEmpty() && enabled.split(':').any { it.equals(want, true) }) return true
        } catch (t: Throwable) {
            Log.w(TAG, "读 enabled_accessibility_services 异常: ${t.message}")
        }
        try {
            val ams = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val infos: List<AccessibilityServiceInfo> =
                ams.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            if (infos.any { it.resolveInfo?.serviceInfo?.let { si -> si.packageName == ctx.packageName && ctx.packageName + "/" + si.name == want } == true }) {
                return true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "读 AccessibilityManager 已启用服务异常: ${t.message}")
        }
        return TouchControlService.instance != null
    }

    /** VPN（Tailscale）授权是否仍有效：VpnService.prepare 返回 null 即已授权。含 binder 调用，勿在主线程轮询。 */
    fun isVpnAuthorized(ctx: Context): Boolean = try {
        TailscaleManager.isVpnAuthorized(ctx)
    } catch (t: Throwable) {
        Log.w(TAG, "VPN 授权检测异常: ${t.message}")
        false
    }

    /**
     * 屏幕捕获（MediaProjection）当前是否可用：
     * 必须【前台服务在运行】且【MediaProjection 实例有效】同时成立——
     * 进程被杀后旧 projection 引用会误报"已授权"（既有 bug 口径，沿用其修复结论）。
     */
    fun isScreenCaptureAuthorized(ctx: Context): Boolean {
        val serviceRunning = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(300)?.any { it.service.className == ScreenProjectionService::class.java.name } == true
        } catch (t: Throwable) {
            Log.w(TAG, "查 ScreenProjectionService 运行状态异常: ${t.message}")
            false
        }
        val ready = try {
            ScreenStreamManager.isReady()
        } catch (t: Throwable) {
            false
        }
        return serviceRunning && ready
    }

    /** 系统定位总开关（GPS）是否打开——权限授予了但系统定位关闭时远程定位仍拿不到点。 */
    fun isLocationServiceOn(ctx: Context): Boolean = try {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (t: Throwable) {
        false
    }

    fun checkRuntime(ctx: Context, permission: String): GrantState {
        return try {
            if (!isDeclared(ctx, permission)) GrantState.NOT_APPLICABLE
            else if (ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED)
                GrantState.GRANTED
            else GrantState.DENIED
        } catch (t: Throwable) {
            Log.w(TAG, "checkSelfPermission($permission) 异常: ${t.message}")
            GrantState.UNKNOWN
        }
    }

    /** 该权限是否真的在 manifest 里（决定要不要向用户解释）。 */
    fun isDeclared(ctx: Context, permission: String): Boolean = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.any { it == permission } == true
    } catch (t: Throwable) {
        Log.w(TAG, "读 manifest 权限失败: ${t.message}")
        true
    }

    /**
     * 是否"永久拒绝"（勾选不再询问 / MIUI 直接置为 USER_FIXED）：
     * 未授予 + shouldShowRequestPermissionRationale=false → 系统不会再弹框，只能跳设置页。
     * 注意：从未询问过的权限同样返回 false，所以调用方必须保证这是"申请过一次之后"的检测。
     */
    fun isRuntimePermanentlyDenied(activity: android.app.Activity, permission: String): Boolean {
        return try {
            if (checkRuntime(activity, permission) == GrantState.GRANTED) return false
            val shouldExplain = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            // Android 11+(API 30) 起：连续两次拒绝后系统自动等同"不再询问"，用 appops 兜底更准
            if (!shouldExplain) return true
            false
        } catch (t: Throwable) {
            Log.w(TAG, "isRuntimePermanentlyDenied($permission) 异常: ${t.message}")
            false
        }
    }

    /** 一次性收集全部检测结论（启动静默自检与一键授权收尾共用）。 */
    fun snapshot(ctx: Context, activity: android.app.Activity? = null): List<PermStatus> {
        val out = mutableListOf<PermStatus>()

        // ---------- 1. 运行时权限 ----------
        for (e in PermissionInventory.runtimeEntries) {
            var st = checkRuntime(ctx, e.permission)
            if (st == GrantState.DENIED && activity != null && isRuntimePermanentlyDenied(activity, e.permission)) {
                st = GrantState.DENIED_PERMANENTLY
            }
            out.add(
                PermStatus(
                    key = e.permission,
                    label = "${e.label}（运行时，${e.usage}）",
                    kind = PermissionKind.RUNTIME,
                    state = st,
                    autoFixable = st != GrantState.DENIED_PERMANENTLY,
                    manualSteps = if (st == GrantState.DENIED_PERMANENTLY)
                        "系统设置 → 应用设置 → 应用管理 → SmsForwarder → 权限管理 → 将「${e.label}」改为「始终允许」（App 内点「打开应用详情页」可直接进入）"
                    else "",
                    detector = "ContextCompat.checkSelfPermission",
                )
            )
        }
        // 后台定位单列：必须在前台定位已授予后单独申请
        run {
            val fine = checkRuntime(ctx, "android.permission.ACCESS_FINE_LOCATION")
            val bg = checkRuntime(ctx, PermissionInventory.BACKGROUND_LOCATION)
            val st = when {
                bg == GrantState.GRANTED -> GrantState.GRANTED
                Build.VERSION.SDK_INT < 29 -> GrantState.NOT_APPLICABLE
                else -> GrantState.DENIED
            }
            out.add(
                PermStatus(
                    key = PermissionInventory.BACKGROUND_LOCATION,
                    label = "后台定位（运行时，锁屏后持续上报位置）",
                    kind = PermissionKind.SPECIAL,
                    state = st,
                    autoFixable = true,
                    manualSteps = if (st != GrantState.GRANTED && st != GrantState.NOT_APPLICABLE)
                        ("需先授予前台定位（当前前台定位=${if (fine == GrantState.GRANTED) "已授予" else "未授予"}），" +
                                "再单独申请后台定位；或直接 系统设置 → 应用管理 → SmsForwarder → 权限 → 位置信息 → 选「始终允许」；" +
                                "MIUI 还需在「省电策略」里选「无限制」")
                    else "",
                    detector = "checkSelfPermission（Android 10+ 需单独申请，不能混入批量）",
                )
            )
        }

        // ---------- 2. 特殊权限 ----------
        out.add(
            PermStatus(
                key = "battery_whitelist",
                label = "电池优化白名单（保活·最关键）",
                kind = PermissionKind.SPECIAL,
                state = if (isKeepAliveEffective(ctx)) GrantState.GRANTED else GrantState.DENIED,
                autoFixable = true,
                manualSteps = if (!isKeepAliveEffective(ctx)) batteryStepsForUi() else "",
                detector = "PowerManager.isIgnoringBatteryOptimizations" + if (RomType.isHuawei) " + appops RUN_ANY_IN_BACKGROUND" else "",
            )
        )
        run {
            val notifyOn = areNotificationsEnabled(ctx)
            val notifyState = if (notifyOn) {
                GrantState.GRANTED
            } else if (Build.VERSION.SDK_INT >= 33 && activity != null &&
                isRuntimePermanentlyDenied(activity, "android.permission.POST_NOTIFICATIONS")
            ) {
                GrantState.DENIED_PERMANENTLY
            } else {
                GrantState.DENIED
            }
            out.add(
                PermStatus(
                    key = "notification",
                    label = "通知（前台服务保活通知）",
                    kind = PermissionKind.SPECIAL,
                    state = notifyState,
                    autoFixable = notifyState != GrantState.DENIED_PERMANENTLY,
                    manualSteps = if (!notifyOn)
                        "系统设置 → 通知管理 → 找到 SmsForwarder → 打开「允许通知」（前台服务通知被关闭时，被控端极易被系统回收）"
                    else "",
                    detector = "NotificationManagerCompat.areNotificationsEnabled" + if (Build.VERSION.SDK_INT >= 33) " + POST_NOTIFICATIONS" else "",
                )
            )
        }
        out.add(
            PermStatus(
                key = "overlay",
                label = "悬浮窗（远程点亮屏幕/解锁）",
                kind = PermissionKind.SPECIAL,
                state = if (isOverlayGranted(ctx)) GrantState.GRANTED else GrantState.DENIED,
                autoFixable = true,
                manualSteps = if (!isOverlayGranted(ctx)) overlayManualSteps() else "",
                detector = "Settings.canDrawOverlays",
            )
        )
        out.add(
            PermStatus(
                key = "accessibility",
                label = "无障碍服务（远程触摸/远程桌面点击）",
                kind = PermissionKind.SPECIAL,
                state = if (isAccessibilityEnabled(ctx)) GrantState.GRANTED else GrantState.DENIED,
                autoFixable = false,
                manualSteps = if (!isAccessibilityEnabled(ctx)) accessibilityManualSteps() else "",
                detector = "Settings.Secure.enabled_accessibility_services + AccessibilityManager 已启用服务列表 + 服务实例",
            )
        )
        run {
            val st = if (isScreenCaptureAuthorized(ctx)) GrantState.GRANTED else GrantState.DENIED
            out.add(
                PermStatus(
                    key = "media_projection",
                    label = "屏幕捕获（屏幕预览，MediaProjection）",
                    kind = PermissionKind.SPECIAL,
                    state = st,
                    autoFixable = true,
                    manualSteps = if (st == GrantState.DENIED)
                        "点「一键授权」后在系统弹窗「立即开始录制/投放」选允许。注意：该授权绑定前台服务进程，进程被杀后必须重新弹窗，无法持久化——所以电池白名单/自启动必须同时开启"
                    else "",
                    detector = "ScreenProjectionService 运行中 + ScreenStreamManager.isReady()",
                )
            )
        }
        run {
            val st = if (isVpnAuthorized(ctx)) GrantState.GRANTED else GrantState.DENIED
            out.add(
                PermStatus(
                    key = "vpn_consent",
                    label = "VPN 授权（Tailscale 直连）",
                    kind = PermissionKind.SPECIAL,
                    state = st,
                    autoFixable = true,
                    manualSteps = if (st == GrantState.DENIED)
                        "点「一键授权」→ 在「连接 SmsForwarder 的 VPN？」弹窗点允许；MIUI 若弹出「仅本次允许」请选择「始终允许」，否则锁屏后通道会断"
                    else "",
                    detector = "VpnService.prepare == null",
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val st = if (isAllFilesAccessGranted(ctx)) GrantState.GRANTED else GrantState.DENIED
            out.add(
                PermStatus(
                    key = "all_files_access",
                    label = "所有文件访问（MANAGE_EXTERNAL_STORAGE）",
                    kind = PermissionKind.SPECIAL,
                    state = st,
                    autoFixable = true,
                    manualSteps = if (st == GrantState.DENIED)
                        "设置 → 应用设置 → 特殊应用权限 → 所有文件访问 → SmsForwarder 允许（或点「一键授权」跳转该页）"
                    else "",
                    detector = "Environment.isExternalStorageManager",
                )
            )
        }
        if (isDeclared(ctx, "android.permission.WRITE_SETTINGS")) {
            // ★ 该权限来自依赖库合并进 manifest 的声明（项目自身源码 0 处使用，grep 已核实），
            //   因此不参与申请、也不算"待处理项"，只在报告里说明原委，避免误导用户去开无用开关。
            out.add(
                PermStatus(
                    key = "write_settings",
                    label = "修改系统设置（WRITE_SETTINGS，依赖库带入，项目代码未使用）",
                    kind = PermissionKind.SPECIAL,
                    state = GrantState.NOT_APPLICABLE,
                    autoFixable = false,
                    manualSteps = "",
                    detector = "Settings.System.canWrite（实测=${if (isWriteSettingsGranted(ctx)) "已授予" else "未授予"}，不影响被控端功能）",
                )
            )
        }

        // ---------- 3. OEM 自启动 / 后台限制 ----------
        out.add(
            PermStatus(
                key = "oem_autostart",
                label = "自启动 / 后台活动（${RomType.describe()}）",
                kind = PermissionKind.OEM,
                state = OemGuide.probeAutostartState(ctx),
                autoFixable = false,
                manualSteps = OemGuide.manualSteps(),
                detector = OemGuide.probeDetector(),
            )
        )
        if (!isLocationServiceOn(ctx)) {
            out.add(
                PermStatus(
                    key = "system_location_switch",
                    label = "系统定位总开关（非 App 权限）",
                    kind = PermissionKind.OEM,
                    state = GrantState.DENIED,
                    autoFixable = false,
                    manualSteps = "下拉快捷面板打开「位置信息」，或 设置 → 位置信息 → 开启",
                    detector = "LocationManager.isProviderEnabled",
                )
            )
        }
        return out
    }

    /** 电池优化白名单的手工步骤（按 ROM）。公开给 UI 在"系统入口打不开"时展示可复制文案。 */
    fun batteryStepsForUi(): String = when {
        RomType.isXiaomi -> "MIUI：设置 → 应用设置 → 应用管理 → SmsForwarder → 省电策略 → 选「无限制」；" +
                "再进 安全中心 → 授权管理 → 自启动管理 → 允许 SmsForwarder 自启动"
        RomType.isHuawei -> "EMUI：设置 → 应用 → 应用管理 → SmsForwarder → 启动管理 → 关闭「自动管理」，" +
                "手动勾选 自启动/关联启动/后台活动；同时 手机管家 → 电池 → 更多电池设置 里将本应用设为「不优化/受保护」"
        else -> "设置 → 电池 → 电池优化 → 找到 SmsForwarder → 选「不允许（不优化）」"
    }

    private fun overlayManualSteps(): String = when {
        RomType.isXiaomi -> "MIUI：设置 → 应用设置 → 特殊应用权限 → 显示在其他应用上层 → SmsForwarder 允许" +
                "（或在打开的页面里直接拨动开关）"
        RomType.isHuawei -> "EMUI：设置 → 应用 → 权限 → 悬浮窗 → SmsForwarder 允许"
        else -> "设置 → 应用 → 显示在其他应用上层（悬浮窗）→ SmsForwarder 允许"
    }

    private fun accessibilityManualSteps(): String {
        val svc = "SmsForwarder 远程触摸"
        return "【无障碍无法由 App 自动开启，必须手工确认一次】设置 → 更多设置（MIUI：应用设置）→ 无障碍 → " +
                "已下载的服务 → 勾选「$svc」→ 弹窗点「允许」。" +
                "如列表里找不到该服务，请先在 App 里点一次「远程触摸·开启服务」再回列表查看"
    }
}
