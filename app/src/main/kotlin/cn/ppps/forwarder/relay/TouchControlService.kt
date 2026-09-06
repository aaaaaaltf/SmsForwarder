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

    // ★ 2026-09-04 volatile：被控端可同时被多条控制通道连着（中继接收线程 / TS直连接收线程 /
    //   直连监听每连接线程），它们写的是同一个服务实例；命令分发已改为按连接内联串行，
    //   但跨通道并发读写这些字段仍会读到旧值，导致一次手势的 down/move/up 落到不同坐标。
    @Volatile private var screenWidth = 1080
    @Volatile private var screenHeight = 2400
    private val handler = Handler(Looper.getMainLooper())

    // 触摸状态（供远程桌面使用）
    @Volatile private var pressX = -1f
    @Volatile private var pressY = -1f
    @Volatile private var lastX = -1f
    @Volatile private var lastY = -1f
    @Volatile private var dragging = false
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

    /**
     * 归一化坐标单击（0..1）。
     * ★ 2026-09-04：双击/滚轮等命令原先在调用方用 displayMetrics.widthPixels 自行换算像素，
     *   而单击走 touchDown/touchUp 用的是 getRealSize 的尺寸——有导航栏、挖屏的机型上两者
     *   并不相等，同一个坐标"单击能点中、双击点不中"。统一收到本服务内换算。
     */
    fun tapNormalized(nx: Float, ny: Float) = tap(nx * screenWidth, ny * screenHeight)

    /** 归一化坐标滑动（0..1），尺寸口径同 [tapNormalized] */
    fun swipeNormalized(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) =
        swipe(x1 * screenWidth, y1 * screenHeight, x2 * screenWidth, y2 * screenHeight, duration)

    /** 滚轮滚动：以屏幕中心为起点按档滑动（每档 200 像素，尺寸口径同 [tapNormalized]） */
    fun scroll(delta: Int) {
        val cx = screenWidth / 2f
        val cy = screenHeight / 2f
        val dy = delta * 200f
        swipe(cx, cy, cx, cy - dy, 300)
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
