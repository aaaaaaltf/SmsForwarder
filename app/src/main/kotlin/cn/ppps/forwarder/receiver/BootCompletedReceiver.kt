package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.ppps.forwarder.service.RelayServerService
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.RelaySettings

@Suppress("PrivatePropertyName")
class BootCompletedReceiver : BroadcastReceiver() {

    private val TAG: String = BootCompletedReceiver::class.java.simpleName

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        val isSelfRecover = action == Intent.ACTION_MY_PACKAGE_REPLACED
                || action == Intent.ACTION_PACKAGE_RESTARTED
                || action == Intent.ACTION_USER_PRESENT
        if (!isBoot && !isSelfRecover) return

        try {
            // ★★★ 2026-11-08【不再依赖「开机自启」开关】
            //   用户明确：不要求应用内的「开机自启」开关，只要自动授权时能导航到系统自启动设置页。
            //   因此开机/覆盖安装/进程重启一律拉起服务，仅尊重用户在图里主动点过「停止服务」的意图。
            //   没有服务就没有 :56786 监听，也没有 relayst 开关状态同步通道 → 直连必然全 RST。
            if (RelaySettings.userStoppedServer) {
                Log.i(TAG, "收到 $action → 用户曾主动停止服务，不自动拉起")
                return
            }
            Log.i(TAG, "收到广播 $action → 拉起被控端中继服务")
            RelayServerService.start(context)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "广播自恢复启动失败($action):${e.message}")
        }
    }
}
