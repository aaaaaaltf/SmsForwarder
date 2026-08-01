package cn.ppps.forwarder.fragment.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientWolSendBinding
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.WolData
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xrouter.utils.TextUtils
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.resource.ResUtils.getColors

@Suppress("PrivatePropertyName")
@Page(name = "远程WOL")
class WolSendFragment : BaseFragment<FragmentClientWolSendBinding?>(), View.OnClickListener {

    private val TAG: String = WolSendFragment::class.java.simpleName
    private var mCountDownHelper: CountDownButtonHelper? = null
    private var wolHistory: MutableMap<String, String> = mutableMapOf()

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientWolSendBinding {
        return FragmentClientWolSendBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        return super.initTitle()!!.setImmersive(false).setTitle(R.string.api_wol)
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        //发送按钮增加倒计时，避免重复点击
        mCountDownHelper = CountDownButtonHelper(binding!!.btnSubmit, SettingUtils.requestTimeout)
        mCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnSubmit.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnSubmit.text = getString(R.string.send)
            }
        })

        //取出历史记录
        val history = HttpServerUtils.wolHistory
        if (!TextUtils.isEmpty(history)) {
            wolHistory = Gson().fromJson(history, object : TypeToken<MutableMap<String, String>>() {}.type)
        }
    }

    override fun initListeners() {
        binding!!.btnServerHistory.setOnClickListener(this)
        binding!!.btnSubmit.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_server_history -> {
                if (wolHistory.isEmpty()) {
                    XToastUtils.warning(getString(R.string.no_server_history))
                    return
                }
                Log.d(TAG, "wolHistory = $wolHistory")

                MaterialDialog.Builder(requireContext())
                    .title(R.string.server_history)
                    .items(wolHistory.keys)
                    .itemsCallbackSingleChoice(0) { _: MaterialDialog?, _: View?, _: Int, text: CharSequence ->
                        binding!!.etMac.setText(text)
                        binding!!.etIp.setText(wolHistory[text])
                        true // allow selection
                    }
                    .positiveText(R.string.select)
                    .negativeText(R.string.cancel)
                    .neutralText(R.string.clear_history)
                    .neutralColor(getColors(R.color.red))
                    .onNeutral { _: MaterialDialog?, _: DialogAction? ->
                        wolHistory.clear()
                        HttpServerUtils.wolHistory = ""
                    }
                    .show()
            }

            R.id.btn_submit -> {
                val mac = binding!!.etMac.text.toString()
                val macRegex = getString(R.string.mac_regex).toRegex()
                if (!macRegex.matches(mac)) {
                    XToastUtils.error(getString(R.string.mac_error))
                    return
                }

                val ip = binding!!.etIp.text.toString()
                val ipRegex = getString(R.string.ip_regex).toRegex()
                if (!TextUtils.isEmpty(ip) && !ipRegex.matches(ip)) {
                    XToastUtils.error(getString(R.string.ip_error))
                    return
                }

                val portText = binding!!.etPort.text.toString()
                val portRegex = getString(R.string.wol_port_regex).toRegex()
                if (!TextUtils.isEmpty(portText) && !portRegex.matches(portText)) {
                    XToastUtils.error(getString(R.string.wol_port_error))
                    return
                }

                val wolData = WolData(
                    mac = mac,
                    ip = ip,
                    port = if (portText.isEmpty()) 9 else portText.toInt(),
                )

                mCountDownHelper?.start()
                RelayApi.request(RelayCommands.CMD_WOL, RelayClientHolder.gson.toJson(wolData), RelayCommands.RSP_WOL, { json ->
                    try {
                        val resp = RelayClientHolder.gson.fromJson<BaseResponse<String>>(json, object : TypeToken<BaseResponse<String>>() {}.type)
                        if (resp.code == 200) {
                            XToastUtils.success(getString(R.string.request_succeeded))
                            //添加到历史记录
                            wolHistory[mac] = ip
                            HttpServerUtils.wolHistory = Gson().toJson(wolHistory)
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
