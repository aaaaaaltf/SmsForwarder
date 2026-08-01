package cn.ppps.forwarder.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.google.android.material.tabs.TabLayout
import cn.ppps.forwarder.BuildConfig
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseActivity
import cn.ppps.forwarder.databinding.ActivityMainBinding
import cn.ppps.forwarder.fragment.ClientFragment
import cn.ppps.forwarder.fragment.ServerFragment
import com.xuexiang.xui.utils.WidgetUtils

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>() {

    private val TAG: String = MainActivity::class.java.simpleName

    /** 打包模式：controller=控制端，server=被控端 */
    private val isControllerMode: Boolean = BuildConfig.APP_MODE == "controller"

    private lateinit var mTabLayout: TabLayout

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initViews()
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        //单一模式（打包控制端或被控端）隐藏标签栏，直接全屏显示对应页面
        if (isControllerMode) {
            WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_client), R.drawable.selector_icon_tabbar_settings)
            mTabLayout.visibility = View.GONE
            switchPage(ClientFragment::class.java)
        } else {
            WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_server), R.drawable.selector_icon_tabbar_settings)
            mTabLayout.visibility = View.GONE
            switchPage(ServerFragment::class.java)
        }
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
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
