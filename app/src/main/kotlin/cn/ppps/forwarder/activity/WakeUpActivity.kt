package cn.ppps.forwarder.activity

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.WindowManager

/**
 * 点亮屏幕并解锁（无密码锁屏）
 *
 * 通过 FLAG_TURN_SCREEN_ON + WakeLock + requestDismissKeyguard 点亮并解锁，
 * 保持约1.5秒让系统完成点亮后再关闭，避免立即finish导致点不亮。
 */
class WakeUpActivity : Activity() {

    private val TAG = "WakeUpActivity"
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val pm = getSystemService(POWER_SERVICE) as? PowerManager
            if (pm != null) {
                val interactive = pm.isInteractive
                Log.i(TAG, "当前屏幕状态: " + if (interactive) "亮" else "灭")
                if (!interactive) {
                    // 强制点亮（部分机型FLAG_TURN_SCREEN_ON不生效）
                    try {
                        @Suppress("DEPRECATION")
                        wakeLock = pm.newWakeLock(
                            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                                or PowerManager.ACQUIRE_CAUSES_WAKEUP
                                or PowerManager.ON_AFTER_RELEASE,
                            "smsforwarder:wakeup",
                        )
                        wakeLock?.acquire(2000)
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock获取失败: ${e.message}")
                    }
                }
            }

            // 解锁无密码锁屏
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val km = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
                    if (km != null && km.isKeyguardLocked) {
                        km.requestDismissKeyguard(this, null)
                        Log.i(TAG, "已请求解除锁屏")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "解除锁屏失败: ${e.message}")
                }
            }

            // 保持1.5秒确保点亮生效后再关闭
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1500)
        } catch (e: Exception) {
            Log.e(TAG, "点亮屏幕失败: ${e.message}")
            finish()
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
