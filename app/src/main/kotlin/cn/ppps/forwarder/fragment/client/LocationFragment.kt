package cn.ppps.forwarder.fragment.client

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientLocationBinding
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.utils.CountDownButtonHelper
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.grouplist.XUIGroupListView

@Suppress("PrivatePropertyName")
@Page(name = "远程找手机")
class LocationFragment : BaseFragment<FragmentClientLocationBinding?>(), View.OnClickListener {

    private val TAG: String = LocationFragment::class.java.simpleName
    private var mCountDownHelper: CountDownButtonHelper? = null

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientLocationBinding {
        return FragmentClientLocationBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        return super.initTitle()!!.setImmersive(false).setTitle(R.string.api_location)
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        //发送按钮增加倒计时，避免重复点击
        mCountDownHelper = CountDownButtonHelper(binding!!.btnRefresh, SettingUtils.requestTimeout)
        mCountDownHelper!!.setOnCountDownListener(object : CountDownButtonHelper.OnCountDownListener {
            override fun onCountDown(time: Int) {
                binding!!.btnRefresh.text = String.format(getString(R.string.seconds_n), time)
            }

            override fun onFinished() {
                binding!!.btnRefresh.text = getString(R.string.refresh)
            }
        })

        getLocation()
    }

    override fun initListeners() {
        binding!!.btnRefresh.setOnClickListener(this)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_refresh -> {
                getLocation()
            }

            else -> {}
        }
    }

    private fun getLocation() {
        mCountDownHelper?.start()
        RelayApi.request(RelayCommands.CMD_LOCATION, "", RelayCommands.RSP_LOCATION, { json ->
            try {
                val resp = RelayClientHolder.gson.fromJson<BaseResponse<LocationInfo>>(json, object : TypeToken<BaseResponse<LocationInfo>>() {}.type)
                if (resp.code == 200) {
                    XToastUtils.success(getString(R.string.request_succeeded))
                    val locationInfo = resp.data ?: return@request
                    val groupListView = binding!!.infoList
                    groupListView.removeAllViews()
                    val section = XUIGroupListView.newSection(context)
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.location_longitude), locationInfo.longitude))) {}
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.location_latitude), locationInfo.latitude))) {}
                    if (locationInfo.address != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.location_address), locationInfo.address))) {}
                    if (locationInfo.time != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.location_time), locationInfo.time))) {}
                    if (locationInfo.provider != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.location_provider), locationInfo.provider))) {}
                    section.addTo(groupListView)
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

    override fun onDestroyView() {
        if (mCountDownHelper != null) mCountDownHelper!!.recycle()
        super.onDestroyView()
    }

}
