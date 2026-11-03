package cn.ppps.forwarder.activity

import android.annotation.SuppressLint
import android.view.KeyEvent
import cn.ppps.forwarder.R
import cn.ppps.forwarder.utils.CommonUtils.Companion.showPrivacyDialog
import cn.ppps.forwarder.utils.SettingUtils.Companion.isAgreePrivacy
import com.xuexiang.xui.utils.KeyboardUtils
import com.xuexiang.xui.widget.activity.BaseSplashActivity
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.app.ActivityUtils
import me.jessyan.autosize.internal.CancelAdapt

@Suppress("PropertyName")
@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseSplashActivity(), CancelAdapt {

    val TAG: String = SplashActivity::class.java.simpleName

    /**
     * ★ 2026-11-04【需求】启动页不再停留：原来 500ms 会让人看到一帧"旧图标/白屏"，
     *   用户要求"启动时不要闪现以前的图标，直接进伪装界面"。
     *   背景已经改成纯白（见 xui_config_bg_splash.xml），停留时间也压到 0。
     */
    override fun getSplashDurationMillis(): Long {
        return 0L
    }

    /**
     * activity启动后的初始化
     */
    override fun onCreateActivity() {
        initSplashView(R.drawable.xui_config_bg_splash)
        startSplash(false)
    }

    /**
     * 启动页结束后的动作
     */
    override fun onSplashFinished() {
        if (isAgreePrivacy) {
            whereToJump()
        } else {
            showPrivacyDialog(this) { dialog: MaterialDialog, _: DialogAction? ->
                dialog.dismiss()
                isAgreePrivacy = true
                whereToJump()
            }
        }
    }

    private fun whereToJump() {
        // ★★★ 2026-11-03 需求：被控端服务在运行时，从桌面图标进来**直接**展示"像系统设置"
        //   的封面页（不经真实界面，连闪一下都没有）；真实界面只有封面页连点 10 次才进
        //   —— 那种 Intent 带 EXTRA_GOTO_AUTH 口令，必须放行。
        val fromFake = try {
            intent?.getBooleanExtra(FakeSettingsActivity.EXTRA_GOTO_AUTH, false) == true
        } catch (t: Throwable) {
            false
        }
        val serviceRunning = try {
            cn.ppps.forwarder.service.RelayServerService.isRunning
        } catch (t: Throwable) {
            false
        }
        if (serviceRunning && !fromFake) {
            android.util.Log.i(TAG, "★ 服务运行中：启动页直接进伪装设置页")
            ActivityUtils.startActivity(FakeSettingsActivity::class.java)
            finish()
            return
        }
        ActivityUtils.startActivity(MainActivity::class.java)
        finish()
    }

    /**
     * 菜单、返回键响应
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return KeyboardUtils.onDisableBackKeyDown(keyCode) && super.onKeyDown(keyCode, event)
    }
}
