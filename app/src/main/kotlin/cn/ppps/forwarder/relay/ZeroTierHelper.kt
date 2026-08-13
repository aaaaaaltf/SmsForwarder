package cn.ppps.forwarder.relay

import android.content.Context
import android.content.Intent
import cn.ppps.forwarder.utils.Log
import java.util.Collections

/**
 * ★★★ 2026-08-13 ZeroTier 直连辅助工具（被控端）：
 *   - 检测本机 ZeroTier 是否已开启（tun0/tun 接口或 172.26.x 网段IP）
 *   - 未开启时通过启动 ZeroTier One 应用(com.zerotier.one)来开启
 *   - 开启成功后回调（轮询等待接口出现，最长 ZT_WAIT_MS）
 * 用于"中继服务关闭时自动启用 ZeroTier 直连模式"。
 */
object ZeroTierHelper {
    private const val TAG = "ZeroTierHelper"

    /** ZeroTier One 官方应用包名 */
    const val ZT_PACKAGE = "com.zerotier.one"

    /** ZeroTier 网络列表页中"启动网络"开关的 resource-id */
    const val SWITCH_ID = "com.zerotier.one:id/network_start_network_switch"

    /**
     * ★ 开关固定坐标兜底：按设备屏幕尺寸动态推算（uiautomator实测：
     *   红米1080x2400→开关中心(986,348)、华为1088x2400→(989,370)，相对屏幕位置一致）。
     *   无障碍窗口树读取失败（NAF节点被过滤）时的坐标点击兜底。
     */
    private fun switchPos(context: Context): Pair<Float, Float> {
        val dm = context.resources.displayMetrics
        return (dm.widthPixels * 0.912f) to (dm.heightPixels * 0.15f)
    }

    /** 等待 ZeroTier 接口出现的最长时间（毫秒） */
    const val ZT_WAIT_MS = 30000L

    private const val POLL_INTERVAL_MS = 1000L

    @Volatile
    private var launching = false

    fun interface ZtCallback {
        /** @param up true=ZeroTier已开启；false=无法开启或等待超时 */
        fun onResult(up: Boolean)
    }

    /** ★ 本机 ZeroTier 是否已开启：存在 tun0/tun 接口 或 172.26.x 网段IP 即视为已开启 */
    fun isZeroTierUp(): Boolean {
        return try {
            Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).any { ni ->
                val name = ni.name?.lowercase() ?: ""
                if (name.contains("tun")) {
                    true
                } else {
                    if (!ni.isUp || ni.isLoopback) return@any false
                    Collections.list(ni.inetAddresses).any { addr ->
                        val ip = addr.hostAddress
                        !addr.isLoopbackAddress && ip != null && ip.contains(".") && ip.startsWith("172.26.")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "检测ZeroTier接口异常: ${t.message}")
            false
        }
    }

    /** ★ 本机 ZeroTier One 应用是否已安装 */
    fun isZeroTierInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(ZT_PACKAGE, 0)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** ★★★ 2026-08-13 关闭 ZeroTier One 进程（中继服务连接成功时调用，节省资源）：
     *   尝试 force-stop（需系统/root权限）；失败时仅记录（普通App无法强制停止他人进程，属预期）。 */
    fun stopZeroTier(ctx: Context) {
        try {
            val pm = ctx.packageManager
            // 方式1：am force-stop（部分ROM/root环境可用）
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("am", "force-stop", ZT_PACKAGE))
                proc.waitFor()
                Log.i(TAG, "★ 已请求关闭 ZeroTier One（am force-stop 退出码 ${proc.exitValue()}）")
            } catch (t: Throwable) {
                Log.w(TAG, "am force-stop ZeroTier 失败: ${t.message}")
            }
            // 方式2：通过 ActivityManager.killBackgroundProcesses（无系统权限时通常无效，尝试兜底）
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses(ZT_PACKAGE)
            } catch (t: Throwable) {
                Log.w(TAG, "killBackgroundProcesses ZeroTier 失败: ${t.message}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "关闭ZeroTier异常: ${t.message}")
        }
    }

    /**
     * ★★★ 确保 ZeroTier 已开启并建立 VPN 连接：
     *   - 已开启 → 立即回调 true
     *   - 未开启但已安装 → 启动 ZeroTier One 应用，并通过无障碍服务自动点击"启动网络"开关
     *     （仅启动App不会自动连接VPN，必须点击开关触发服务建立tun），轮询等待接口出现
     *   - 未安装 → 回调 false（无法开启）
     * 回调在独立线程执行（非主线程请自行切回）。
     */
    fun ensureZeroTierUp(context: Context, callback: ZtCallback) {
        if (isZeroTierUp()) {
            Log.i(TAG, "ZeroTier 已开启，直接返回")
            callback.onResult(true)
            return
        }
        if (!isZeroTierInstalled(context)) {
            Log.w(TAG, "未安装 ZeroTier One($ZT_PACKAGE)，无法自动开启")
            callback.onResult(false)
            return
        }
        if (launching) {
            // 已有启动进行中：直接轮询等待结果
            pollUntilUp(context, 0, callback)
            return
        }
        launching = true
        launchZtApp(context)
        // ★ 2026-08-13 增强：仅启动App不会自动建立VPN，需通过无障碍服务自动点击开关
        autoClickSwitchUntilUp(context) { up ->
            launching = false
            callback.onResult(up)
        }
    }

    /** 启动 ZeroTier One 主界面（网络列表页） */
    private fun launchZtApp(context: Context) {
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(ZT_PACKAGE)
            if (launch != null) {
                launch.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(launch)
                Log.i(TAG, "已启动 ZeroTier One，等待自动点击开关建立连接...")
            } else {
                Log.w(TAG, "ZeroTier One 无启动Intent")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "启动ZeroTier One失败: ${t.message}")
        }
    }

    /**
     * ★ 自动点击 ZeroTier 开关直到 VPN 建立或超时：
     *   优先读取无障碍窗口树判断开关状态并精确点击；窗口树不可用时按固定坐标点击兜底。
     *   - 开关关闭 → 点击开启
     *   - 开关显示开启但 VPN 未建立（服务未运行）→ toggle 一次触发服务启动
     */
    private fun autoClickSwitchUntilUp(context: Context, callback: ZtCallback) {
        var toggled = false
        var lastTapPair = 0L
        val deadline = System.currentTimeMillis() + ZT_WAIT_MS
        Thread {
            while (System.currentTimeMillis() < deadline) {
                if (isZeroTierUp()) {
                    Log.i(TAG, "ZeroTier 已开启（自动点击开关成功）")
                    callback.onResult(true)
                    return@Thread
                }
                try {
                    Thread.sleep(1200)
                } catch (e: InterruptedException) {
                    callback.onResult(false)
                    return@Thread
                }
                // 无障碍服务未开启时退化为仅轮询（保持旧行为，等待接口自行出现）
                val svc = TouchControlService.instance ?: continue
                val checked = svc.isNodeChecked(SWITCH_ID)
                when {
                    checked == true -> {
                        // 开关显示开启但 VPN 未建立：toggle 一次触发服务启动（必须切换状态才会启动服务）
                        if (!toggled) {
                            if (svc.clickNodeById(SWITCH_ID)) {
                                try { Thread.sleep(600) } catch (e: InterruptedException) { }
                                svc.clickNodeById(SWITCH_ID)
                                Log.i(TAG, "ZeroTier 开关显示开启但未连接，已toggle触发服务启动")
                                toggled = true
                            }
                        }
                    }
                    checked == false -> {
                        if (svc.clickNodeById(SWITCH_ID)) {
                            Log.i(TAG, "已点击 ZeroTier 开关（开启连接）")
                            toggled = false
                        }
                    }
                    else -> {
                        // 窗口树不可用（rootInActiveWindow 为空/找不到节点）：固定坐标点击兜底。
                        // 每次循环点击一次（间隔10秒以上），连续两次点击构成一次"关闭+开启"触发服务启动。
                        if (System.currentTimeMillis() - lastTapPair > 10000) {
                            val (sx, sy) = switchPos(context)
                            Log.w(TAG, "无障碍树不可用，坐标点击开关兜底(${sx.toInt()},${sy.toInt()})")
                            svc.tap(sx, sy)
                            lastTapPair = System.currentTimeMillis()
                        }
                    }
                }
            }
            Log.w(TAG, "等待ZeroTier接口超时(${ZT_WAIT_MS}ms)，判定未开启")
            callback.onResult(isZeroTierUp())
        }.start()
    }

    /**
     * ★★★ 2026-08-13 自动断开 ZeroTier VPN（中继连接成功时调用，节省资源）：
     *   通过无障碍服务点击"启动网络"开关关闭 VPN，随后补充 force-stop/killBackgroundProcesses。
     */
    fun disconnectZt(context: Context) {
        try {
            if (!isZeroTierUp()) {
                Log.i(TAG, "ZeroTier 当前未连接，无需断开")
                return
            }
            launchZtApp(context)
            val deadline = System.currentTimeMillis() + 15000L
            var clicked = false
            while (System.currentTimeMillis() < deadline) {
                if (!isZeroTierUp()) {
                    Log.i(TAG, "★ ZeroTier VPN 已断开")
                    break
                }
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    break
                }
                val svc = TouchControlService.instance ?: continue
                if (!clicked) {
                    val checked = svc.isNodeChecked(SWITCH_ID)
                    when (checked) {
                        true -> if (svc.clickNodeById(SWITCH_ID)) {
                            Log.i(TAG, "★ 已点击 ZeroTier 开关（关闭连接）")
                            clicked = true
                        }
                        false -> clicked = true // 开关已关，等待系统断开
                        else -> { } // 窗口未就绪，等待重试
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "断开ZeroTier异常: ${t.message}")
        }
        // 补充：force-stop / killBackgroundProcesses（尽力而为，普通App无FORCE_STOP权限时可能无效）
        stopZeroTier(context)
    }

    /** 轮询等待 tun/172.26 接口出现，ZT_WAIT_MS 后仍未出现则判定失败 */
    private fun pollUntilUp(context: Context, elapsed: Long, callback: ZtCallback) {
        if (isZeroTierUp()) {
            Log.i(TAG, "ZeroTier 已开启（等待 ${elapsed}ms）")
            callback.onResult(true)
            return
        }
        if (elapsed >= ZT_WAIT_MS) {
            Log.w(TAG, "等待ZeroTier接口超时(${ZT_WAIT_MS}ms)，判定未开启")
            callback.onResult(false)
            return
        }
        try {
            Thread.sleep(POLL_INTERVAL_MS)
        } catch (e: InterruptedException) {
            callback.onResult(false)
            return
        }
        pollUntilUp(context, elapsed + POLL_INTERVAL_MS, callback)
    }
}
