package cn.ppps.forwarder.fragment

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import com.google.gson.reflect.TypeToken
import cn.ppps.forwarder.R
import cn.ppps.forwarder.adapter.WidgetItemAdapter
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientBinding
import cn.ppps.forwarder.relay.RelayApi
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.relay.RelayControllerClient
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.ConfigData
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xpage.base.XPageFragment
import com.xuexiang.xpage.core.PageOption
import com.xuexiang.xpage.enums.CoreAnim
import com.xuexiang.xpage.model.PageInfo
import com.xuexiang.xui.adapter.recyclerview.RecyclerViewHolder
import com.xuexiang.xui.utils.DensityUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.actionbar.TitleBar

@Suppress("PrivatePropertyName", "DEPRECATION")
@Page(name = "主动控制·客户端")
class ClientFragment : BaseFragment<FragmentClientBinding?>(), View.OnClickListener, RecyclerViewHolder.OnItemClickListener<PageInfo> {

    private val TAG: String = ClientFragment::class.java.simpleName
    private val mainHandler = Handler(Looper.getMainLooper())

    private var controllerClient: RelayControllerClient? = null
    private var serverConfig: ConfigData? = null
    private var deviceAdapter: ArrayAdapter<String>? = null

    /** 被控端掉线提示防重复标记 */
    private var offlineNotified = false

    /** 每5秒检测并刷新连接状态（★ 子页面打开时也持续运行：保活中继连接，防止NAT空闲断连） */
    private val statusRunnable = object : Runnable {
        override fun run() {
            mainHandler.postDelayed(this, 5000)
            if (binding == null) return  // 视图已销毁则跳过刷新
            refreshUi()
            refreshDeviceList()
            checkSelectedOnline()
            pingSelectedDevice()
        }
    }

    private val CLIENT_FRAGMENT_LIST = listOf(
        PageInfo(getString(R.string.api_screen_preview), "cn.ppps.forwarder.fragment.client.ScreenPreviewFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_screen),
        PageInfo(getString(R.string.api_camera), "cn.ppps.forwarder.fragment.client.CameraPreviewFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_camera),
        PageInfo(getString(R.string.api_sms_query), "cn.ppps.forwarder.fragment.client.SmsQueryFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_sms_query),
        PageInfo(getString(R.string.api_call_query), "cn.ppps.forwarder.fragment.client.CallQueryFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_call_query),
        PageInfo(getString(R.string.api_contact_query), "cn.ppps.forwarder.fragment.client.ContactQueryFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_contact_query),
        PageInfo(getString(R.string.api_contact_add), "cn.ppps.forwarder.fragment.client.ContactAddFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_contact_add),
        PageInfo(getString(R.string.api_wol), "cn.ppps.forwarder.fragment.client.WolSendFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_wol),
        PageInfo(getString(R.string.api_location), "cn.ppps.forwarder.fragment.client.LocationFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_location),
        PageInfo(getString(R.string.api_battery_query), "cn.ppps.forwarder.fragment.client.BatteryQueryFragment", "{\"\":\"\"}", CoreAnim.slide, R.drawable.icon_api_battery_query),
    )

    override fun initViews() {
        WidgetUtils.initGridRecyclerView(binding!!.recyclerView, 3, DensityUtils.dp2px(1f))
        val widgetItemAdapter = WidgetItemAdapter(CLIENT_FRAGMENT_LIST)
        widgetItemAdapter.setOnItemClickListener(this)
        binding!!.recyclerView.adapter = widgetItemAdapter

        //在线被控端列表
        deviceAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, mutableListOf())
        binding!!.lvDevices.adapter = deviceAdapter
        binding!!.lvDevices.setOnItemClickListener { _: android.widget.AdapterView<*>?, _: View?, position: Int, _: Long ->
            selectDevice(position)
        }
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.menu_client)
        return titleBar
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientBinding {
        return FragmentClientBinding.inflate(inflater, container, false)
    }

    override fun initListeners() {
        binding!!.etRelayHost.setText(RelaySettings.relayHost)
        binding!!.etRelayHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                RelaySettings.relayHost = binding!!.etRelayHost.text.toString().trim()
            }
        })

        binding!!.etControllerPort.setText(RelaySettings.relayControllerPort.toString())
        binding!!.etControllerPort.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val port = try {
                    binding!!.etControllerPort.text.toString().trim().toInt()
                } catch (e: Exception) {
                    RelayCommands.RELAY_CONTROLLER_PORT
                }
                if (port in 1..65535) RelaySettings.relayControllerPort = port
            }
        })

        binding!!.btnConnect.setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        //打开页面自动连接中继
        ensureConnected()
        refreshUi()
        refreshDeviceList()
        //每5秒检测并刷新连接状态
        mainHandler.removeCallbacks(statusRunnable)
        mainHandler.postDelayed(statusRunnable, 5000)
    }

    override fun onPause() {
        super.onPause()
        // ★ 不停止心跳：摄像头/短信等子页面打开时仍需每5秒发送ping保活并检测连接，
        //   否则中继连接空转会被移动网络NAT断开，导致"以为已连接实际已断开"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 视图销毁后停止心跳循环，防止泄漏（子页面打开时视图仍存活，心跳继续运行）
        mainHandler.removeCallbacks(statusRunnable)
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_connect -> {
                if (controllerClient?.isConnected() == true) {
                    disconnect()
                } else {
                    ensureConnected()
                }
            }

            else -> {}
        }
    }

    // ==================== 中继连接管理 ====================

    private fun ensureConnected() {
        if (controllerClient?.isConnected() == true) return
        controllerClient?.stop()
        val c = RelayControllerClient(
            host = RelaySettings.relayHost,
            port = RelaySettings.relayControllerPort,
            listener = controllerListener,
        )
        controllerClient = c
        RelayClientHolder.client = c
        c.start()
    }

    private fun disconnect() {
        RelayClientHolder.client = null
        controllerClient?.stop()
        controllerClient = null
        RelayClientHolder.selectedPcId = -1
        serverConfig = null
        RelayClientHolder.devices.clear()
        refreshUi()
        refreshDeviceList()
        XToastUtils.info(getString(R.string.relay_client_disconnected))
    }

    private val controllerListener = object : RelayControllerClient.Listener {
        override fun onConnected() {
            mainHandler.post {
                XToastUtils.success(getString(R.string.relay_client_connected))
                refreshUi()
            }
        }

        override fun onDisconnected() {
            mainHandler.post {
                refreshUi()
            }
        }

        override fun onDeviceOnline(pcId: Int, ip: String) {
            mainHandler.post {
                RelayClientHolder.devices[pcId] = ip
                //自动选择第一个上线的被控端
                if (RelayClientHolder.selectedPcId < 0) {
                    RelayClientHolder.selectedPcId = pcId
                    serverConfig = null
                    queryConfig()
                }
                refreshDeviceList()
            }
        }

        override fun onDeviceOffline(pcId: Int) {
            mainHandler.post {
                RelayClientHolder.devices.remove(pcId)
                if (RelayClientHolder.selectedPcId == pcId) {
                    RelayClientHolder.selectedPcId = if (RelayClientHolder.devices.isNotEmpty()) RelayClientHolder.devices.keys.first() else -1
                    serverConfig = null
                    if (RelayClientHolder.selectedPcId >= 0) queryConfig()
                }
                refreshDeviceList()
            }
        }

        override fun onCommand(pcId: Int, cmd: String, payload: ByteArray) {
            //摄像头视频帧（二进制）转发给摄像头预览界面
            if (cmd == RelayCommands.CMD_CAMERA_STREAM_FRAME) {
                cn.ppps.forwarder.fragment.client.CameraPreviewFragment.dispatchCameraFrame(pcId, payload)
                return
            }
            //摄像头状态报告（文本）转发给摄像头预览界面
            if (cmd == RelayCommands.CMD_CAMERA_STATUS_REPORT) {
                cn.ppps.forwarder.fragment.client.CameraPreviewFragment.dispatchCameraStatus(String(payload, Charsets.UTF_8))
                return
            }
            //分发给已注册的请求回调
            controllerClient?.dispatchResponse(cmd, payload)
        }
    }

    private fun refreshUi() {
        val connected = controllerClient?.isConnected() == true
        binding!!.btnConnect.text = if (connected) getString(R.string.relay_btn_disconnect) else getString(R.string.relay_btn_connect)
        // ★ 两段连接状态实时显示：控制端↔中继、中继↔被控端
        binding!!.tvClientStatus.text = String.format(
            getString(R.string.relay_client_link),
            if (connected) getString(R.string.relay_link_connected) else getString(R.string.relay_link_disconnected)
        )
        val pcId = RelayClientHolder.selectedPcId
        val deviceText = if (connected && pcId >= 0 && RelayClientHolder.devices.containsKey(pcId)) {
            String.format(getString(R.string.relay_device_link_online), pcId)
        } else {
            getString(R.string.relay_link_disconnected)
        }
        binding!!.tvDeviceStatus.text = String.format(getString(R.string.relay_device_link), deviceText)
    }

    /** 每5秒检测：选中的被控端是否仍在线，不在线则清除选中并提示（只提示一次） */
    private fun checkSelectedOnline() {
        val pcId = RelayClientHolder.selectedPcId
        if (pcId >= 0 && !RelayClientHolder.devices.containsKey(pcId)) {
            RelayClientHolder.selectedPcId = -1
            serverConfig = null
            refreshUi()
            refreshDeviceList()
            if (!offlineNotified) {
                offlineNotified = true
                XToastUtils.info(getString(R.string.relay_device_offline_tips))
            }
        } else {
            offlineNotified = false
        }
    }

    /** 心跳探测失败连续计数（连续3次失败判为离线） */
    private var pingFailCount = 0

    /** 每5秒向选中的被控端发送心跳探测：连接保活（防NAT超时）+ 实时检测在线状态 */
    private fun pingSelectedDevice() {
        val c = RelayClientHolder.client ?: return
        if (!c.isConnected()) return
        val pcId = RelayClientHolder.selectedPcId
        if (pcId < 0) return
        c.request(
            pcId, RelayCommands.CMD_PING, "", RelayCommands.RSP_PONG,
            { _ ->
                // 探测成功：被控端在线，重置失败计数并保持显示
                pingFailCount = 0
                offlineNotified = false
            },
            { _ ->
                // 探测失败：连续3次(约15秒)无响应则判定被控端离线
                pingFailCount++
                if (pingFailCount >= 3) {
                    pingFailCount = 0
                    RelayClientHolder.devices.remove(pcId)
                    if (RelayClientHolder.selectedPcId == pcId) {
                        RelayClientHolder.selectedPcId = if (RelayClientHolder.devices.isNotEmpty()) RelayClientHolder.devices.keys.first() else -1
                        serverConfig = null
                        if (RelayClientHolder.selectedPcId >= 0) queryConfig()
                    }
                    refreshUi()
                    refreshDeviceList()
                    XToastUtils.info(getString(R.string.relay_device_offline_tips))
                }
            },
            4000,
        )
    }

    private fun refreshDeviceList() {
        val list = deviceAdapter ?: return
        list.clear()
        for ((pcId, ip) in RelayClientHolder.devices) {
            list.add(String.format(getString(R.string.relay_device_item), pcId, ip))
        }
        list.notifyDataSetChanged()
        //高亮当前选中设备
        highlightSelectedDevice()
    }

    private fun selectDevice(position: Int) {
        val keys = RelayClientHolder.devices.keys.toList()
        if (position < 0 || position >= keys.size) return
        RelayClientHolder.selectedPcId = keys[position]
        serverConfig = null
        queryConfig()
        highlightSelectedDevice()
    }

    private fun highlightSelectedDevice() {
        val listView: ListView = binding!!.lvDevices
        val keys = RelayClientHolder.devices.keys.toList()
        val index = keys.indexOf(RelayClientHolder.selectedPcId)
        if (index >= 0) {
            listView.setItemChecked(index, true)
            listView.setSelection(index)
        }
    }

    //查询被控端配置（获取功能开关与设备信息）
    private fun queryConfig() {
        RelayApi.request(RelayCommands.CMD_GET_CONFIG, "", RelayCommands.RSP_CONFIG, { json ->
            try {
                val resp = RelayClientHolder.gson.fromJson<BaseResponse<ConfigData>>(json, object : TypeToken<BaseResponse<ConfigData>>() {}.type)
                if (resp.code == 200) {
                    serverConfig = resp.data
                    val mark = resp.data?.extraDeviceMark
                    if (!mark.isNullOrEmpty()) {
                        //用设备备注更新设备列表显示
                        val keys = RelayClientHolder.devices.keys.toList()
                        val index = keys.indexOf(RelayClientHolder.selectedPcId)
                        if (index >= 0) {
                            deviceAdapter?.getItem(index)?.let {
                                deviceAdapter?.remove(it)
                                deviceAdapter?.insert(String.format(getString(R.string.relay_device_item_mark), mark, RelayClientHolder.selectedPcId), index)
                                deviceAdapter?.notifyDataSetChanged()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "queryConfig error: ${e.message}")
            }
        }, { msg ->
            Log.e(TAG, "queryConfig failed: $msg")
        })
    }

    override fun onItemClick(itemView: View, item: PageInfo, position: Int) {
        try {
            if (!RelayClientHolder.isReady()) {
                XToastUtils.error(getString(R.string.relay_need_connect_and_select))
                return
            }
            // ★ 操作前连接检测：选中的被控端必须在线，未连接则弹提示并返回
            val pcId = RelayClientHolder.selectedPcId
            if (pcId < 0 || !RelayClientHolder.devices.containsKey(pcId)) {
                XToastUtils.error(getString(R.string.relay_device_not_connected))
                return
            }
            val cfg = serverConfig
            val name = item.name
            if (cfg != null && ((name == getString(R.string.api_sms_query) && !cfg.enableApiSmsQuery) || (name == getString(R.string.api_call_query) && !cfg.enableApiCallQuery) || (name == getString(R.string.api_contact_query) && !cfg.enableApiContactQuery) || (name == getString(R.string.api_contact_add) && !cfg.enableApiContactAdd) || (name == getString(R.string.api_battery_query) && !cfg.enableApiBatteryQuery) || (name == getString(R.string.api_wol) && !cfg.enableApiWol) || (name == getString(R.string.api_location) && !cfg.enableApiLocation))) {
                XToastUtils.error(getString(R.string.disabled_on_the_server))
                return
            }
            @Suppress("UNCHECKED_CAST") PageOption.to(Class.forName(item.classPath) as Class<XPageFragment>).setNewActivity(true).open(this)
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "onItemClick error: ${e.message}")
            XToastUtils.error(e.message.toString())
        }
    }
}
