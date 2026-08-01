package cn.ppps.forwarder.fragment.client

import android.view.LayoutInflater
import android.view.ViewGroup
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientBatteryQueryBinding
import cn.ppps.forwarder.entity.BatteryInfo
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar
import com.xuexiang.xui.widget.grouplist.XUIGroupListView

@Suppress("PrivatePropertyName")
@Page(name = "远程查电量")
class BatteryQueryFragment : BaseFragment<FragmentClientBatteryQueryBinding?>() {

    private val TAG: String = BatteryQueryFragment::class.java.simpleName

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientBatteryQueryBinding {
        return FragmentClientBatteryQueryBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_battery_query)
        return titleBar
    }

    /**
     * 初始化控件
     */
    override fun initViews() {
        RelayApi.request(RelayCommands.CMD_BATTERY, "", RelayCommands.RSP_BATTERY, { json ->
            try {
                val resp = RelayClientHolder.gson.fromJson<BaseResponse<BatteryInfo>>(json, object : TypeToken<BaseResponse<BatteryInfo>>() {}.type)
                if (resp.code == 200) {
                    XToastUtils.success(getString(R.string.request_succeeded))
                    val batteryInfo = resp.data ?: return@request
                    val groupListView = binding!!.infoList
                    val section = XUIGroupListView.newSection(context)
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_level), batteryInfo.level))) {}
                    if (batteryInfo.scale != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_scale), batteryInfo.scale))) {}
                    if (batteryInfo.voltage != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_voltage), batteryInfo.voltage))) {}
                    if (batteryInfo.temperature != "") section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_temperature), batteryInfo.temperature))) {}
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_status), batteryInfo.status))) {}
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_health), batteryInfo.health))) {}
                    section.addItemView(groupListView.createItemView(String.format(getString(R.string.battery_plugged), batteryInfo.plugged))) {}
                    section.addTo(groupListView)
                } else {
                    XToastUtils.error(getString(R.string.request_failed) + resp.msg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e(TAG, "onSuccess error: ${e.message}")
                XToastUtils.error(getString(R.string.request_failed) + e.message)
            }
        }, { msg ->
            XToastUtils.error(getString(R.string.request_failed) + msg)
        })
    }

}
