package cn.ppps.forwarder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.relay.ScreenStreamManager
import cn.ppps.forwarder.utils.FRONT_CHANNEL_ID
import cn.ppps.forwarder.utils.FRONT_CHANNEL_NAME
import cn.ppps.forwarder.utils.FRONT_NOTIFY_ID

/**
 * 屏幕捕获前台服务（mediaProjection 类型）
 *
 * Android 14+ 要求 MediaProjection 必须绑定到 foregroundServiceType="mediaProjection" 的前台服务，
 * 否则 getMediaProjection() 会抛异常。本服务接收 ServerFragment 授权返回的 resultCode/data，
 * 创建 MediaProjection 并保存到 ScreenStreamManager，供屏幕推流使用。
 */
class ScreenProjectionService : Service() {

    companion object {
        private const val TAG = "ScreenProjectionService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 启动屏幕捕获前台服务并传入授权结果 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenProjectionService::class.java)
            intent.putExtra(EXTRA_RESULT_CODE, resultCode)
            intent.putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(FRONT_NOTIFY_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != 0 && resultData != null) {
            try {
                val pm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = pm.getMediaProjection(resultCode, resultData)
                ScreenStreamManager.setProjection(projection)
                Log.i(TAG, "屏幕捕获授权成功，已保存MediaProjection")
            } catch (e: Exception) {
                Log.e(TAG, "获取MediaProjection失败: ${e.message}")
                ScreenStreamManager.setProjection(null)
            }
        } else {
            Log.w(TAG, "未收到有效的授权结果")
        }
        // 任务完成后停止服务，MediaProjection 由 ScreenStreamManager 持有
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FRONT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.screen_preview_auth_notify))
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
