package cn.ppps.forwarder.utils

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import androidx.fragment.app.Fragment
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.webview.AgentWebActivity
import cn.ppps.forwarder.core.webview.AgentWebFragment
import cn.ppps.forwarder.entity.ImageInfo
import com.xuexiang.xpage.core.PageOption
import com.xuexiang.xui.widget.dialog.DialogLoader
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog.SingleButtonCallback
import com.xuexiang.xui.widget.imageview.preview.PreviewBuilder
import com.xuexiang.xutil.XUtil
import com.xuexiang.xutil.resource.ResUtils.getString

/**
 * 常用工具类
 */
@Suppress("RegExpRedundantEscape", "unused", "RegExpUnnecessaryNonCapturingGroup")
class CommonUtils private constructor() {
    companion object {
        /**
         * 这里填写你的应用隐私政策网页地址
         */
        private const val PRIVACY_URL = "https://gitee.com/pp/SmsForwarder/raw/main/PRIVACY"

        /**
         * 显示隐私政策的提示
         *
         * @param context
         * @param submitListener 同意的监听
         * @return
         */
        @Suppress("SameParameterValue", "NAME_SHADOWING")
        @JvmStatic
        fun showPrivacyDialog(context: Context, submitListener: SingleButtonCallback?): Dialog {
            val dialog = MaterialDialog.Builder(context).title(R.string.title_reminder).autoDismiss(false).cancelable(false).positiveText(R.string.lab_agree).onPositive { dialog1: MaterialDialog, which: DialogAction? ->
                if (submitListener != null) {
                    submitListener.onClick(dialog1, which!!)
                } else {
                    dialog1.dismiss()
                }
            }.negativeText(R.string.lab_disagree).onNegative { dialog, _ ->
                dialog.dismiss()
                DialogLoader.getInstance().showConfirmDialog(
                    context, getString(R.string.title_reminder), String.format(
                        getString(R.string.content_privacy_explain_again), getString(R.string.app_name)
                    ), getString(R.string.lab_look_again), { dialog, _ ->
                        dialog.dismiss()
                        showPrivacyDialog(context, submitListener)
                    }, getString(R.string.lab_still_disagree)
                ) { dialog, _ ->
                    dialog.dismiss()
                    DialogLoader.getInstance().showConfirmDialog(
                        context, getString(R.string.content_think_about_it_again), getString(R.string.lab_look_again), { dialog, _ ->
                            dialog.dismiss()
                            showPrivacyDialog(context, submitListener)
                        }, getString(R.string.lab_exit_app)
                    ) { dialog, _ ->
                        dialog.dismiss()
                        XUtil.exitApp()
                    }
                }
            }.build()
            dialog.setContent(getPrivacyContent(context))
            //开始响应点击事件
            dialog.contentView!!.movementMethod = LinkMovementMethod.getInstance()
            dialog.show()
            return dialog
        }

        /**
         * @return 隐私政策说明
         */
        private fun getPrivacyContent(context: Context): SpannableStringBuilder {
            return SpannableStringBuilder().append("    ").append(getString(R.string.privacy_content_1)).append(" ").append(getString(R.string.app_name)).append("!\n").append("    ").append(getString(R.string.privacy_content_2)).append("    ").append(getString(R.string.privacy_content_3)).append(getPrivacyLink(context, PRIVACY_URL)).append(getString(R.string.privacy_content_4)).append("    ").append(getString(R.string.privacy_content_5)).append(getPrivacyLink(context, PRIVACY_URL)).append(getString(R.string.privacy_content_6)).append("    ").append(getString(R.string.privacy_content_7))
        }

        /**
         * @param context 隐私政策的链接
         * @return
         */
        @Suppress("SameParameterValue")
        private fun getPrivacyLink(context: Context, privacyUrl: String): SpannableString {
            val privacyName = String.format(
                getString(R.string.lab_privacy_name), getString(R.string.app_name)
            )
            val spannableString = SpannableString(privacyName)
            spannableString.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    goWeb(context, privacyUrl)
                }
            }, 0, privacyName.length, Spanned.SPAN_MARK_MARK)
            return spannableString
        }

        /**
         * 请求浏览器
         *
         * @param url
         */
        @JvmStatic
        fun goWeb(context: Context, url: String?) {
            val intent = Intent(context, AgentWebActivity::class.java)
            intent.putExtra(AgentWebFragment.KEY_URL, url)
            context.startActivity(intent)
        }

        /**
         * 大图预览
         *
         * @param fragment
         * @param url      图片资源
         * @param view     小图加载控件
         */
        fun previewPicture(fragment: Fragment?, url: String, view: View?) {
            if (fragment == null || url.isEmpty()) {
                return
            }
            val bounds = android.graphics.Rect()
            view?.getGlobalVisibleRect(bounds)
            PreviewBuilder.from(fragment).setImgs(ImageInfo.newInstance(url, bounds)).setCurrentIndex(0).setSingleFling(true).setProgressColor(R.color.xui_config_color_main_theme).setType(PreviewBuilder.IndicatorType.Number).start()
        }
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
