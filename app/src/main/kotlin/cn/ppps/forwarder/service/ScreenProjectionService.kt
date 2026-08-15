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
import android.os.Parcel
import android.util.Base64
import cn.ppps.forwarder.utils.Log
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
 *
 * ★ 2026-08-05 增强：
 * 1. 授权成功后【保持前台服务运行】（不立即 stopSelf）——MediaProjection 生命周期与服务绑定，
 *    服务停止会导致授权失效（Android 14+），从而无法远程屏幕控制。
 * 2. 【保存并复用授权 Intent】——授权结果序列化保存到 SharedPreferences，App/服务重启后
 *    通过 [restore] 自动恢复授权，无需被控端重新点授权框（Android 13 及以下有效；
 *    Android 14 的 token 随系统会话管理，跨进程/重启可能失效，失败时静默跳过）。
 */
class ScreenProjectionService : Service() {

    companion object {
        private const val TAG = "ScreenProjectionService"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val SP_NAME = "screen_projection"
        private const val SP_RESULT_CODE = "result_code"
        private const val SP_RESULT_DATA = "result_data"

        /** 启动屏幕捕获前台服务并传入授权结果（同时保存授权供复用） */
        fun start(context: Context, resultCode: Int, data: Intent) {
            save(context, resultCode, data)
            val intent = Intent(context, ScreenProjectionService::class.java)
            intent.putExtra(EXTRA_RESULT_CODE, resultCode)
            intent.putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** ★ 保存授权结果（resultCode + Intent 序列化），供服务/App重启后复用 */
        private fun save(context: Context, resultCode: Int, data: Intent) {
            try {
                val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.putInt(SP_RESULT_CODE, resultCode)
                // ★★★ 2026-08-14 修复"重启后授权失效"：
                //   旧代码 p.writeValue(data) 序列化MediaProjection授权Intent可能抛异常，
                //   异常被外层catch吞掉 → result_code已写但result_data未写 → 重启后restore读不到token
                //   → 无法恢复授权 → 一键授权误判未授权 → 重新弹窗。
                //   改为 writeParcelable + 独立try/降级writeValue + 详细错误日志。
                var saved = false
                try {
                    val p = Parcel.obtain()
                    p.writeParcelable(data, 0)
                    val bytes = p.marshall()
                    p.recycle()
                    editor.putString(SP_RESULT_DATA, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    editor.apply()
                    saved = true
                } catch (se: Throwable) {
                    Log.e(TAG, "writeParcelable序列化授权Intent失败: ${se.message}")
                    // 降级：writeValue 方式
                    try {
                        val p2 = Parcel.obtain()
                        p2.writeValue(data)
                        val bytes2 = p2.marshall()
                        p2.recycle()
                        editor.putString(SP_RESULT_DATA, Base64.encodeToString(bytes2, Base64.NO_WRAP))
                        editor.apply()
                        saved = true
                        Log.i(TAG, "屏幕捕获授权已保存（writeValue降级成功）")
                    } catch (e2: Throwable) {
                        Log.e(TAG, "writeValue序列化也失败: ${e2.message}，授权将无法跨重启恢复（需每次重新授权）")
                        editor.apply() // 至少保存result_code
                    }
                }
                if (saved) Log.i(TAG, "屏幕捕获授权已保存（可复用）")
            } catch (e: Exception) {
                Log.w(TAG, "保存授权失败: ${e.message}")
            }
        }

        /** ★ 尝试恢复已保存的授权：成功则设置到 ScreenStreamManager 并返回 true */
        fun restore(context: Context): Boolean {
            try {
                val prefs = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
                val resultCode = prefs.getInt(SP_RESULT_CODE, 0)
                val b64 = prefs.getString(SP_RESULT_DATA, null)
                if (resultCode == 0 || b64 == null) return false
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val p = Parcel.obtain()
                p.unmarshall(bytes, 0, bytes.size)
                p.setDataPosition(0)
                val data = p.readValue(Intent::class.java.classLoader) as? Intent
                p.recycle()
                if (data == null) return false
                val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = pm.getMediaProjection(resultCode, data)
                if (projection == null) return false
                ScreenStreamManager.setProjection(projection)
                Log.i(TAG, "★ 已恢复屏幕捕获授权（复用保存的Intent）")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "恢复授权失败: ${e.message}")
                return false
            }
        }

        /** ★★★ 2026-08-14 修复"重启应用后授权失效"：仅启动前台服务（不传授权结果），
         *  onStartCommand 会自动走 [restore] 恢复已保存的 MediaProjection。
         *  RelayServerService.startRelay 中 restore 成功时必须调用本方法把前台服务拉起来，
         *  否则 isScreenProjectionAuthorized 判定"服务运行中"为 false → 一键授权误判未授权 → 重新弹窗。 */
        fun startForegroundOnly(context: Context) {
            try {
                val intent = Intent(context, ScreenProjectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动屏幕捕获前台服务失败: ${e.message}")
            }
        }

        /** ★ 停止屏幕捕获前台服务（释放投影时由 RelayServerService 调用） */
        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ScreenProjectionService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止屏幕捕获服务失败: ${e.message}")
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
        } else if (!restore(this)) {
            Log.w(TAG, "未收到有效的授权结果，且无已保存授权")
        }
        // ★ 保持前台服务运行：MediaProjection 与服务生命周期绑定，立即停止会导致授权失效（Android 14+）
        return START_STICKY
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
