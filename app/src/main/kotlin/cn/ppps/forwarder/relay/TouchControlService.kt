package cn.ppps.forwarder.relay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍触摸服务 - 用于无root环境下模拟点击/滑动
 *
 * 控制端远程触摸命令（md/mm/mu）通过本服务 dispatchGesture 注入手势，
 * 实现在被控端手机屏幕上模拟点击和滑动操作。
 */
class TouchControlService : AccessibilityService() {

    private val TAG = "TouchControlService"

    companion object {
        @Volatile
        var instance: TouchControlService? = null
    }

    private var screenWidth = 1080
    private var screenHeight = 2400
    private val handler = Handler(Looper.getMainLooper())

    // 触摸状态（供远程桌面使用）
    private var pressX = -1f
    private var pressY = -1f
    private var lastX = -1f
    private var lastY = -1f
    private var dragging = false
    private val swipeThresholdPx = 20f

    fun isEnabled(): Boolean = instance != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                val display: Display = wm.defaultDisplay
                val size = Point()
                display.getRealSize(size)
                screenWidth = size.x
                screenHeight = size.y
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取屏幕尺寸失败: ${e.message}")
        }
        Log.i(TAG, "无障碍服务已连接 屏幕=${screenWidth}x${screenHeight}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理事件
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ==================== 远程触摸 ====================

    /** 触摸按下（归一化坐标） */
    fun touchDown(nx: Float, ny: Float) {
        pressX = nx
        pressY = ny
        lastX = nx
        lastY = ny
        dragging = false
    }

    /** 触摸移动（归一化坐标） */
    fun touchMove(nx: Float, ny: Float) {
        lastX = nx
        lastY = ny
        if (pressX >= 0 && pressY >= 0) {
            val dx = Math.abs((nx - pressX) * screenWidth)
            val dy = Math.abs((ny - pressY) * screenHeight)
            if (!dragging && (dx > swipeThresholdPx || dy > swipeThresholdPx)) {
                dragging = true
            }
        }
    }

    /** 触摸抬起：拖动→swipe，否则→tap */
    fun touchUp(nx: Float, ny: Float) {
        lastX = nx
        lastY = ny
        if (pressX < 0 || pressY < 0) {
            resetTouch()
            return
        }
        if (dragging) {
            swipe(pressX * screenWidth, pressY * screenHeight, lastX * screenWidth, lastY * screenHeight, 300)
        } else {
            tap(lastX * screenWidth, lastY * screenHeight)
        }
        resetTouch()
    }

    private fun resetTouch() {
        pressX = -1f
        pressY = -1f
        lastX = -1f
        lastY = -1f
        dragging = false
    }

    /** 单击 */
    fun tap(x: Float, y: Float) {
        Log.i(TAG, "远程点击 (${x.toInt()},${y.toInt()})")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path()
        path.moveTo(x, y)
        dispatchGestureInternal(path, 120)
    }

    /** 滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        Log.i(TAG, "远程滑动 (${x1.toInt()},${y1.toInt()}) -> (${x2.toInt()},${y2.toInt()})")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        dispatchGestureInternal(path, duration)
    }

    private fun dispatchGestureInternal(path: Path, duration: Long) {
        handler.post {
            try {
                val svc = instance ?: return@post
                val builder = GestureDescription.Builder()
                builder.addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                svc.dispatchGesture(builder.build(), null, null)
            } catch (e: Exception) {
                Log.e(TAG, "注入手势失败: ${e.message}")
            }
        }
    }

    /**
     * 熄屏：执行全局锁屏动作（GLOBAL_ACTION_LOCK_SCREEN，API 28+）
     * 效果等同按下电源键锁屏，屏幕立即熄灭；无需root/设备管理员权限，
     * 依赖无障碍服务（远程触摸功能已要求开启）。
     * @return 是否成功执行
     */
    fun screenOff(): Boolean {
        Log.i(TAG, "执行熄屏（GLOBAL_ACTION_LOCK_SCREEN）")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            Log.w(TAG, "当前系统版本过低，不支持无障碍全局锁屏动作")
            false
        }
    }
}
