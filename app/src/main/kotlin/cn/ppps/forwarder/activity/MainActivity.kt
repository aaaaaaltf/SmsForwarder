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
        loadFragmentDirect(fragment)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        android.util.Log.e(TAG, "★ initTab 完成 ★")
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
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }
}
