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

        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            Log.d(TAG, "开机广播：启动被控端中继服务")
            //开机自启被控端中继服务
            if (RelaySettings.enableServerAutorun) {
                RelayServerService.start(context)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "开机启动失败:${e.message}")
        }

    }
}
