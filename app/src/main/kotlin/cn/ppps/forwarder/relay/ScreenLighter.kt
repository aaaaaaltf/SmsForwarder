package cn.ppps.forwarder.relay

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * 悬浮窗点亮屏幕（远程点亮被控端屏幕并解锁）
 *
 * ★ 使用悬浮窗代替 Activity 的原因：
 *   Android 10+ / MIUI 对"后台启动 Activity"有严格限制（MIUILOG-Permission Denied Activity），
 *   即使声明并授予 SYSTEM_ALERT_WINDOW 权限，后台 startActivity 仍会被 MIUI 拦截，
 *   导致 WakeUpActivity 无法启动、点不亮屏幕。
 *   而"添加悬浮窗"在有 SYSTEM_ALERT_WINDOW 权限时不受后台启动限制，可正常点亮。
 *
 * 原理：添加全屏透明悬浮窗，携带 FLAG_TURN_SCREEN_ON + FLAG_SHOW_WHEN_LOCKED
 *       + FLAG_DISMISS_KEYGUARD + FLAG_KEEP_SCREEN_ON，配合 WakeLock 强制点亮；
 *       1.5秒后自动移除悬浮窗。
 */
object ScreenLighter {

    private const val TAG = "ScreenLighter"
    private var lastWakeLock: PowerManager.WakeLock? = null

    /**
     * 点亮屏幕并解锁（无密码锁屏）
     * @return true=已执行（悬浮窗权限正常）；false=无悬浮窗权限，未执行
     */
    fun lightUp(context: Context): Boolean {
        // 同步检查悬浮窗权限（API 23+ 必须 Settings.canDrawOverlays）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "无悬浮窗权限，无法点亮屏幕")
            return false
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: return@post
                // 全屏透明悬浮窗
                val view = View(context)
                view.setBackgroundColor(Color.TRANSPARENT)
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                        or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(view, params)
                Log.i(TAG, "已添加点亮悬浮窗")
                // 无密码锁屏由 FLAG_DISMISS_KEYGUARD + FLAG_SHOW_WHEN_LOCKED 自动解除
                // 配合 WakeLock 强制点亮（部分机型 FLAG_TURN_SCREEN_ON 不生效）
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && !pm.isInteractive) {
                        @Suppress("DEPRECATION")
                        val wl = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                or PowerManager.ACQUIRE_CAUSES_WAKEUP
                                or PowerManager.ON_AFTER_RELEASE,
                            "smsforwarder:wakeup",
                        )
                        wl.acquire(2000)
                        lastWakeLock = wl
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "WakeLock获取失败: ${e.message}")
                }
                // 1.5秒后移除悬浮窗（完成点亮使命）
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        wm.removeView(view)
                        Log.i(TAG, "已移除点亮悬浮窗")
                    } catch (_: Exception) {
                    }
                    lastWakeLock?.takeIf { it.isHeld }?.release()
                    lastWakeLock = null
                }, 1500)
            } catch (e: Exception) {
                Log.e(TAG, "点亮屏幕失败: ${e.message}")
            }
        }
        return true
    }
}
