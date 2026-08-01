package cn.ppps.forwarder.fragment

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentServerBinding
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.service.LocationService
import cn.ppps.forwarder.service.RelayServerService
import cn.ppps.forwarder.service.ScreenProjectionService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.LocationUtils
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import com.xuexiang.xaop.annotation.SingleClick
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar

@Page(name = "主动控制·服务端")
class ServerFragment : BaseFragment<FragmentServerBinding?>(), View.OnClickListener {

    private val TAG: String = ServerFragment::class.java.simpleName
    private var appContext: App? = null

    /** 屏幕捕获授权请求码 */
    companion object {
        private const val REQ_SCREEN_CAPTURE = 0x501
    }

    //定时更新界面（每5秒刷新连接状态）
    private val handler: Handler = Handler(Looper.getMainLooper())
    private val runnable: Runnable = object : Runnable {
        override fun run() {
            handler.postDelayed(this, 5000) //每隔5秒刷新一次
            refreshButtonText()
        }
    }

    override fun initViews() {
        appContext = requireActivity().application as App
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.menu_server)
        return titleBar
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentServerBinding {
        return FragmentServerBinding.inflate(inflater, container, false)
    }

    override fun initListeners() {
        binding!!.btnToggleServer.setOnClickListener(this)

        //中继服务器地址
        binding!!.etRelayHost.setText(RelaySettings.relayHost)
        binding!!.etRelayHost.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                RelaySettings.relayHost = binding!!.etRelayHost.text.toString().trim()
            }
        })

        //被控端端口
        binding!!.etServerPort.setText(RelaySettings.relayServerPort.toString())
        binding!!.etServerPort.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                val portText = binding!!.etServerPort.text.toString().trim()
                val port = try {
                    portText.toInt()
                } catch (e: Exception) {
                    RelayCommands.RELAY_SERVER_PORT
                }
                if (port < 1 || port > 65535) {
                    XToastUtils.error(getString(R.string.wol_port_error))
                    return
                }
                RelaySettings.relayServerPort = port
            }
        })

        //设备备注
        binding!!.etDeviceMark.setText(SettingUtils.extraDeviceMark)
        binding!!.etDeviceMark.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                SettingUtils.extraDeviceMark = binding!!.etDeviceMark.text.toString().trim()
            }
        })

        //开机自启
        binding!!.scbServerAutorun.isChecked = RelaySettings.enableServerAutorun
        binding!!.scbServerAutorun.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            RelaySettings.enableServerAutorun = isChecked
        }

        //功能开关
        binding!!.sbApiQuerySms.isChecked = HttpServerUtils.enableApiSmsQuery
        binding!!.sbApiQuerySms.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiSmsQuery = isChecked
            if (isChecked) checkReadSmsPermission()
        }

        binding!!.sbApiQueryCall.isChecked = HttpServerUtils.enableApiCallQuery
        binding!!.sbApiQueryCall.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiCallQuery = isChecked
            if (isChecked) checkCallPermission()
        }

        binding!!.sbApiQueryContacts.isChecked = HttpServerUtils.enableApiContactQuery
        binding!!.sbApiQueryContacts.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiContactQuery = isChecked
            if (isChecked) checkContactsPermission()
        }

        binding!!.sbApiAddContacts.isChecked = HttpServerUtils.enableApiContactAdd
        binding!!.sbApiAddContacts.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiContactAdd = isChecked
            if (isChecked) checkContactsPermission()
        }

        binding!!.sbApiWol.isChecked = HttpServerUtils.enableApiWol
        binding!!.sbApiWol.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiWol = isChecked
        }

        binding!!.sbApiLocation.isChecked = HttpServerUtils.enableApiLocation
        binding!!.sbApiLocation.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            // 检查系统定位服务是否开启（权限已在授权时校验），而非应用内设置项
            if (isChecked && !LocationUtils.isLocationEnabled(requireContext())) {
                XToastUtils.error(getString(R.string.api_location_permission_tips))
                binding!!.sbApiLocation.isChecked = false
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                //自动启用应用内GPS定位服务，确保远程定位能获取实时位置
                SettingUtils.enableLocation = true
                try {
                    val intent = Intent(requireContext(), LocationService::class.java)
                    intent.action = ACTION_START
                    requireContext().startService(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "启动定位服务失败: ${e.message}")
                }
            }
            HttpServerUtils.enableApiLocation = isChecked
        }

        binding!!.sbApiQueryBattery.isChecked = HttpServerUtils.enableApiBatteryQuery
        binding!!.sbApiQueryBattery.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiBatteryQuery = isChecked
        }

        //远程摄像头开关
        binding!!.sbApiCamera.isChecked = HttpServerUtils.enableApiCamera
        binding!!.sbApiCamera.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiCamera = isChecked
            if (isChecked) checkCameraPermission()
        }

        //屏幕预览授权开关（MediaProjection）
        binding!!.sbApiScreenPreview.isChecked = cn.ppps.forwarder.relay.ScreenStreamManager.isReady()
        binding!!.sbApiScreenPreview.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            if (isChecked) {
                requestScreenProjection()
            } else {
                cn.ppps.forwarder.relay.ScreenStreamManager.releaseProjection()
            }
        }

        //启动更新UI定时器
        handler.post(runnable)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                // 启动 mediaProjection 前台服务保存授权（Android 14+ 必需）
                ScreenProjectionService.start(requireContext(), resultCode, data)
                binding!!.sbApiScreenPreview.isChecked = true
                XToastUtils.success(R.string.screen_preview_auth_success)
            } else {
                XToastUtils.error(R.string.screen_preview_auth_denied)
                binding!!.sbApiScreenPreview.isChecked = false
            }
        }
    }

    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_toggle_server -> {
                if (RelayServerService.isRunning) {
                    RelayServerService.stop(requireContext())
                } else {
                    checkCallPermission()
                    checkContactsPermission()
                    checkLocationPermission()
                    RelayServerService.start(requireContext())
                }
                refreshButtonText()
            }

            else -> {}
        }
    }

    //刷新按钮
    private fun refreshButtonText() {
        if (RelayServerService.isRunning) {
            binding!!.btnToggleServer.text = resources.getText(R.string.stop_server)
            // ★ 两段连接状态：被控端↔中继、控制端↔中继
            binding!!.tvServerTips.text = if (RelayServerService.isConnected) {
                String.format(getString(R.string.relay_server_link), getString(R.string.relay_link_connected))
            } else {
                getString(R.string.relay_server_connecting)
            }
            binding!!.tvCtrlStatus.text = String.format(
                getString(R.string.relay_ctrl_link),
                if (RelayServerService.isControllerOnline) getString(R.string.relay_ctrl_online) else getString(R.string.relay_ctrl_offline)
            )
        } else {
            binding!!.btnToggleServer.text = resources.getText(R.string.start_server)
            binding!!.tvServerTips.text = getString(R.string.relay_server_stopped)
            binding!!.tvCtrlStatus.text = String.format(getString(R.string.relay_ctrl_link), getString(R.string.relay_ctrl_offline))
        }
    }

    //读取短信权限
    private fun checkReadSmsPermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getReadSmsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiSmsQuery = false
                        binding!!.sbApiQuerySms.isChecked = false
                    }
                }
            })
    }

    //电话权限
    private fun checkCallPermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getReadPhoneStatePermission())
            .permission(PermissionLists.getReadPhoneNumbersPermission())
            .permission(PermissionLists.getReadCallLogPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiCallQuery = false
                        binding!!.sbApiQueryCall.isChecked = false
                    }
                }
            })
    }

    //联系人权限
    private fun checkContactsPermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getReadContactsPermission())
            .permission(PermissionLists.getWriteContactsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiContactQuery = false
                        HttpServerUtils.enableApiContactAdd = false
                        binding!!.sbApiQueryContacts.isChecked = false
                        binding!!.sbApiAddContacts.isChecked = false
                    }
                }
            })
    }

    //定位权限
    private fun checkLocationPermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getAccessCoarseLocationPermission())
            .permission(PermissionLists.getAccessFineLocationPermission())
            .permission(PermissionLists.getAccessBackgroundLocationPermission())
            .permission(PermissionLists.getReadPhoneStatePermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiLocation = false
                        binding!!.sbApiLocation.isChecked = false
                    }
                }
            })
    }

    //摄像头权限
    private fun checkCameraPermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getCameraPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(R.string.toast_denied)
                        HttpServerUtils.enableApiCamera = false
                        binding!!.sbApiCamera.isChecked = false
                    }
                }
            })
    }

    /** 请求屏幕捕获授权（MediaProjection 系统对话框） */
    private fun requestScreenProjection() {
        val pm = requireActivity().getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        try {
            startActivityForResult(pm.createScreenCaptureIntent(), REQ_SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "请求屏幕捕获授权失败: ${e.message}")
            XToastUtils.error(R.string.screen_preview_auth_failed)
            binding!!.sbApiScreenPreview.isChecked = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //取消定时器
        handler.removeCallbacks(runnable)
    }
}
