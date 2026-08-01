package cn.ppps.forwarder.fragment.client

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientContactAddBinding
import cn.ppps.forwarder.entity.ContactInfo
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.EVENT_KEY_PHONE_NUMBERS
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar

@Suppress("PrivatePropertyName")
@Page(name = "远程加话簿")
class ContactAddFragment : BaseFragment<FragmentClientContactAddBinding?>(), View.OnClickListener {

    private val TAG: String = ContactAddFragment::class.java.simpleName
    private var mCountDownHelper: CountDownButtonHelper? = null

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientContactAddBinding {
        return FragmentClientContactAddBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        return super.initTitle()!!.setImmersive(false).setTitle(R.string.api_contact_add)
    }

    /**
     * 初始化控件
     */
    @SuppressLint("SetTextI18n")
    override fun initViews() {
        //发送按钮增加倒计时，避免重复点击
        mCountDownHelper = CountDownButtonHelper(binding!!.btnSubmit, SettingUtils.requestTimeout)
        mCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnSubmit.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnSubmit.text = getString(R.string.submit)
            }
        })
    }

    override fun initListeners() {
        binding!!.btnSubmit.setOnClickListener(this)
        LiveEventBus.get(EVENT_KEY_PHONE_NUMBERS, String::class.java).observeSticky(this) { value: String ->
            binding!!.etPhoneNumbers.setText(value)
        }
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_submit -> {
                val phoneNumbers = binding!!.etPhoneNumbers.text.toString()
                val phoneRegex = getString(R.string.phone_numbers_regex).toRegex()
                if (!phoneRegex.matches(phoneNumbers)) {
                    XToastUtils.error(getString(R.string.phone_numbers_error))
                    return
                }

                val name = binding!!.etDisplayName.text.toString()
                val contactInfo = ContactInfo(name, phoneNumbers)

                mCountDownHelper?.start()
                RelayApi.request(RelayCommands.CMD_CONTACT_ADD, RelayClientHolder.gson.toJson(contactInfo), RelayCommands.RSP_CONTACT_ADD, { json ->
                    try {
                        val resp = RelayClientHolder.gson.fromJson<BaseResponse<String>>(json, object : TypeToken<BaseResponse<String>>() {}.type)
                        if (resp.code == 200) {
                            XToastUtils.success(getString(R.string.request_succeeded))
                        } else {
                            XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        XToastUtils.error(getString(R.string.request_failed) + e.message)
                    }
                    mCountDownHelper?.finish()
                }, { msg ->
                    XToastUtils.error(getString(R.string.request_failed) + msg)
                    mCountDownHelper?.finish()
                })
            }

            else -> {}
        }
    }

    override fun onDestroyView() {
        if (mCountDownHelper != null) mCountDownHelper!!.recycle()
        super.onDestroyView()
    }

}
