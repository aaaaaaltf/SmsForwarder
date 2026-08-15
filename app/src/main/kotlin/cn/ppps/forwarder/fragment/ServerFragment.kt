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

    /** ★ 2026-08-12 首次运行自动授权：本次启动是否已触发过自动授权流程（避免重复触发） */
    @Volatile
    private var autoAuthTriggered = false

    /** ★ 2026-08-12 自动授权流程开始时间戳（用户从设置页返回后据此自动继续下一个授权界面） */
    @Volatile
    private var autoAuthFlowStartTime = 0L

    /** ★ 已自动弹出过设置页/弹窗的特殊权限标记（防止用户不授权时反复跳同一个设置页造成死循环） */
    private val autoManualPrompted = mutableSetOf<String>()

    /** ★ 上一次自动跳转设置页/弹窗的时间戳（防快速 onResume 抖动重复跳转） */
    @Volatile
    private var lastAutoManualJumpTime = 0L

    //定时更新界面（每5秒刷新连接状态）
    private val handler: Handler = Handler(Looper.getMainLooper())
    /** ★ 2026-08-11 防止程序化设置 isChecked 时重复触发授权弹窗 */
    @Volatile
    private var suppressScreenPreviewToggle = false
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
        binding!!.btnOneClickAuth.setOnClickListener(this)

        // ============ 新增 6 项独立权限授权按钮（与图片中"远程触摸"一致的样式，点击立刻授权）============
        binding!!.btnPermMic.setOnClickListener { checkMicrophonePermission() }
        binding!!.btnPermStorage.setOnClickListener {
            checkStorageRuntimePermission()
            // 基础存储权限授权成功后，跳"所有文件访问"（Android 11+）
            handler.postDelayed({ jumpStorageSetting() }, 800)
        }
        binding!!.btnPermNotification.setOnClickListener { checkNotificationPermission() }
        binding!!.btnPermOverlay.setOnClickListener { jumpOverlaySetting() }
        binding!!.btnPermBattery.setOnClickListener { jumpBatterySetting() }
        binding!!.btnPermScreen.setOnClickListener {
            if (!cn.ppps.forwarder.relay.ScreenStreamManager.isReady()) {
                requestScreenProjection()
            } else {
                XToastUtils.toast("屏幕预览已授权")
            }
        }

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
            // ★ 2026-08-11 修复：防止 onActivityResult 程序化设置 isChecked 时重复触发授权弹窗
            //   原BUG：onActivityResult 设置 isChecked=true → 触发监听器 → requestScreenProjection() →
            //   再次弹出系统授权对话框 → 用户取消/重复授权导致状态混乱
            if (suppressScreenPreviewToggle) {
                Log.i(TAG, "★ sbApiScreenPreview 程序化设置，跳过授权弹窗触发")
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                requestScreenProjection()
            } else {
                cn.ppps.forwarder.relay.ScreenStreamManager.releaseProjection()
            }
        }

        //远程触摸（无障碍服务）开关
        binding!!.btnEnableTouch.setOnClickListener {
            val svc = cn.ppps.forwarder.relay.TouchControlService.instance
            if (svc != null) {
                XToastUtils.toast(getString(R.string.touch_service_enabled))
            } else {
                // 跳转无障碍设置页开启远程触摸服务
                try {
                    val intent = Intent("android.settings.ACCESSIBILITY_SETTINGS")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    XToastUtils.error("打开无障碍设置失败: ${e.message}")
                }
            }
        }

        //启动更新UI定时器
        handler.post(runnable)

        // ★★★ 2026-08-12 首次运行自动授权：无需点击"一键授权"，启动时自动触发完整授权流程
        //   已授权完成的权限不会重复弹窗（autoAuthorizeDone持久化标记）；用户拒绝的权限下次启动仍会提示。
        try {
            if (!SettingUtils.autoAuthorizeDone && !autoAuthTriggered) {
                autoAuthTriggered = true
                Log.i(TAG, "★ 首次运行自动授权：延迟1.5s自动触发完整授权流程")
                handler.postDelayed({ autoAuthorizeFlow() }, 1500)
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动授权触发异常: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshButtonText()
        // ★ 2026-08-11 自动恢复已保存的 MediaProjection 授权（App 重启后无需再次弹窗确认）
        //   Android 13 及以下：SP 中保存的 resultCode+Intent 可直接恢复
        //   Android 14+：token 随系统会话管理，恢复可能失败（需重新授权），失败时静默跳过
        if (!isScreenProjectionAuthorized()) {
            try {
                val restored = ScreenProjectionService.restore(requireContext())
                Log.i(TAG, "★ onResume 自动恢复屏幕预览权限: $restored")
                if (restored) {
                    // 更新开关状态（使用 suppress 标志防止重复触发授权弹窗）
                    suppressScreenPreviewToggle = true
                    try { binding!!.sbApiScreenPreview.isChecked = true } finally { suppressScreenPreviewToggle = false }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onResume 恢复屏幕预览权限异常: ${e.message}")
            }
        }

        // ★★★ 2026-08-12 首次运行自动授权收尾：检查所有权限是否已齐全
        try {
            if (autoAuthTriggered && !SettingUtils.autoAuthorizeDone) {
                if (isAllPermissionsAuthorized()) {
                    SettingUtils.autoAuthorizeDone = true
                    Log.i(TAG, "★ 首次运行自动授权全部完成，已持久化标记")
                    XToastUtils.success("所有权限已自动授权完成")
                } else {
                    Log.i(TAG, "★ 自动授权流程中：仍有权限未授权，onResume 继续")
                    // ★ 2026-08-12 增强：自动授权流程已执行一轮后，用户从设置页/弹窗返回时自动弹出下一个未授权权限界面
                    if (autoAuthFlowStartTime > 0 && System.currentTimeMillis() - autoAuthFlowStartTime > 8000) {
                        autoContinueManualAuth()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动授权完成检测异常: ${e.message}")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SCREEN_CAPTURE) {
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                // 启动 mediaProjection 前台服务保存授权（Android 14+ 必需）
                try {
                    ScreenProjectionService.start(requireContext(), resultCode, data)
                    // ★ 等待前台服务启动完成（1000ms），再检查真实授权状态
                    handler.postDelayed({
                        val ready = isScreenProjectionAuthorized()
                        Log.i(TAG, "★ 屏幕预览授权结果: resultCode=OK, ScreenStreamManager.isReady=$ready")
                        // ★ 2026-08-11 修复：程序化设置 isChecked 时屏蔽监听器，防止重复弹出授权对话框
                        suppressScreenPreviewToggle = true
                        try {
                            if (ready) {
                                binding!!.sbApiScreenPreview.isChecked = true
                                XToastUtils.success("屏幕预览权限已授权（MediaProjection已生效）")
                            } else {
                                // ★ 授权弹窗点了"确定"，但 MediaProjection 创建失败 → 真实反馈
                                binding!!.sbApiScreenPreview.isChecked = false
                                XToastUtils.error("屏幕预览授权失败：MediaProjection创建未生效，请重试")
                                Log.e(TAG, "★ 屏幕预览授权失败：用户同意但 ScreenStreamManager.isReady=false，可能原因：前台服务启动失败/Android 14 token失效/getMediaProjection返回null")
                            }
                        } finally {
                            suppressScreenPreviewToggle = false
                        }
                    }, 1000)
                } catch (e: Exception) {
                    Log.e(TAG, "★ 启动ScreenProjectionService失败: ${e.message}", e)
                    suppressScreenPreviewToggle = true
                    try { binding!!.sbApiScreenPreview.isChecked = false } finally { suppressScreenPreviewToggle = false }
                    XToastUtils.error("屏幕预览授权失败：${e.message}")
                }
            } else {
                Log.w(TAG, "★ 屏幕预览授权被用户拒绝: resultCode=$resultCode")
                XToastUtils.error(R.string.screen_preview_auth_denied)
                suppressScreenPreviewToggle = true
                try { binding!!.sbApiScreenPreview.isChecked = false } finally { suppressScreenPreviewToggle = false }
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

            R.id.btn_one_click_auth -> {
                oneClickAuthorize()
            }

            else -> {}
        }
    }

    //刷新按钮
    private fun refreshButtonText() {
        // 远程触摸状态
        val touchOn = cn.ppps.forwarder.relay.TouchControlService.instance != null
        binding!!.tvTouchStatus.text = if (touchOn) {
            resources.getText(R.string.touch_service_enabled)
        } else {
            resources.getText(R.string.touch_service_disabled)
        }
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

    //麦克风权限（录制音频 - 远程免提播放被控端声音）
    private fun checkMicrophonePermission() {
        XXPermissions.with(this)
            .permission(PermissionLists.getRecordAudioPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error("麦克风权限未授予，远程监听功能不可用")
                    } else {
                        XToastUtils.success("麦克风权限已授权")
                    }
                }
            })
    }

    // ============ 权限请求码（屏幕捕获 + 新增三类原生权限弹窗） ============
    companion object {
        private const val REQ_SCREEN_CAPTURE = 0x501
        private const val REQ_STORAGE_RUNTIME = 0x502
        private const val REQ_POST_NOTIFICATION = 0x503
        private const val REQ_CALL_PHONE = 0x504
    }

    //基础存储运行时权限（READ + WRITE_EXTERNAL_STORAGE）
    private fun checkStorageRuntimePermission() {
        // ★ Android 13 (API 33) 起 READ/WRITE_EXTERNAL_STORAGE 已废弃，但本项目 minSdk=21
        //   所以低版本仍需动态授权；高版本自动授予。用系统原生 API 最稳，不依赖 XXPermissions 版本差异。
        val needReq = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT < 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needReq.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                needReq.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (needReq.isEmpty()) {
            XToastUtils.success("基础存储权限已授权（或当前系统版本无需单独授权）")
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                requireActivity(), needReq.toTypedArray(), REQ_STORAGE_RUNTIME)
        }
    }

    //通知权限（POST_NOTIFICATIONS Android 13+ 前台服务必须）
    private fun checkNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            XToastUtils.toast("当前系统版本无需单独授权通知")
            return
        }
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    "android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                XToastUtils.success("通知权限已授权")
                return
            }
            androidx.core.app.ActivityCompat.requestPermissions(
                requireActivity(), arrayOf("android.permission.POST_NOTIFICATIONS"), REQ_POST_NOTIFICATION)
        } catch (e: Throwable) {
            Log.e(TAG, "checkNotificationPermission异常: ${e.message}")
            jumpNotificationSetting()
        }
    }

    //拨打电话权限（CALL_PHONE — 一键换新机/远程主动拨号功能）
    private fun checkCallPhonePermission() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            XToastUtils.success("拨号权限已授权")
            return
        }
        androidx.core.app.ActivityCompat.requestPermissions(
            requireActivity(), arrayOf(android.Manifest.permission.CALL_PHONE), REQ_CALL_PHONE)
    }

    // ============ 新增：原生 onRequestPermissionsResult 分发（处理上面新增三类权限的弹窗结果） ============
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
        when (requestCode) {
            REQ_STORAGE_RUNTIME -> {
                if (ok) XToastUtils.success("基础存储权限已授权")
                else XToastUtils.error("存储权限未授予，文件读写功能不可用")
            }
            REQ_POST_NOTIFICATION -> {
                if (ok) XToastUtils.success("通知权限已授权")
                else {
                    // 用户勾选"不再询问"时，直接跳通知设置页
                    val rationale = androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                        requireActivity(), "android.permission.POST_NOTIFICATIONS")
                    if (!rationale) jumpNotificationSetting()
                    else XToastUtils.error("通知权限未授予，前台服务状态可能不显示")
                }
            }
            REQ_CALL_PHONE -> {
                if (ok) XToastUtils.success("拨号权限已授权")
                else XToastUtils.error("拨号权限未授予，主动拨号功能不可用")
            }
        }
    }

    // ============ 以下为"必须跳转系统设置页"的特殊权限跳转方法 ============

    /** 跳转"所有文件访问"设置页（MANAGE_EXTERNAL_STORAGE Android 11+） */
    private fun jumpStorageSetting() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (android.os.Environment.isExternalStorageManager()) {
                XToastUtils.success("所有文件访问已授权")
                return
            }
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:" + requireContext().packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请在设置中开启「所有文件访问」")
            } catch (e: Exception) {
                Log.w(TAG, "打开所有文件访问设置失败: ${e.message}")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e2: Exception) {
                    XToastUtils.error("打开文件访问设置失败，请手动到系统设置中开启")
                }
            }
        } else {
            // Android 10及以下：基础存储权限即可覆盖
            checkStorageRuntimePermission()
        }
    }

    /** 跳转"悬浮窗权限"设置页（SYSTEM_ALERT_WINDOW） */
    private fun jumpOverlaySetting() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(requireContext())) {
                XToastUtils.success("悬浮窗权限已授权")
                return
            }
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = android.net.Uri.parse("package:" + requireContext().packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请在设置中开启「悬浮窗」/「显示在其他应用的上层」")
            } catch (e: Exception) {
                Log.w(TAG, "打开悬浮窗设置失败: ${e.message}")
                XToastUtils.error("打开悬浮窗设置失败，请手动到系统设置中开启")
            }
        } else {
            XToastUtils.toast("当前系统版本无需单独授权悬浮窗")
        }
    }

    /** 请求/跳转"电池优化白名单"（REQUEST_IGNORE_BATTERY_OPTIMIZATIONS → 直接弹系统确认框） */
    private fun jumpBatterySetting() {
        val ctx = requireContext()
        try {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                XToastUtils.success("已加入电池优化白名单")
                return
            }
            try {
                // 优先：直接弹系统"允许忽略电池优化？"确认框（无需跳设置页）
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:" + ctx.packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请点击「允许」以加入电池优化白名单")
            } catch (e: Exception) {
                Log.w(TAG, "请求电池优化白名单失败，跳设置页: ${e.message}")
                // 兜底：跳电池优化列表页（用户手动找到APP添加）
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
        } catch (e: Throwable) {
            XToastUtils.error("检查/授权电池优化白名单失败: ${e.message}")
        }
    }

    /** 兜底：跳系统通知设置页（当POST_NOTIFICATIONS被拒绝且勾选不再询问时） */
    private fun jumpNotificationSetting() {
        try {
            val intent = Intent()
            when {
                android.os.Build.VERSION.SDK_INT >= 26 -> {
                    intent.action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                    intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                android.os.Build.VERSION.SDK_INT >= 21 -> {
                    intent.action = "android.settings.APP_NOTIFICATION_SETTINGS"
                    intent.putExtra("app_package", requireContext().packageName)
                    intent.putExtra("app_uid", requireContext().applicationInfo.uid)
                }
                else -> {
                    intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    intent.data = android.net.Uri.fromParts("package", requireContext().packageName, null)
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            XToastUtils.toast("请在系统设置中开启本应用通知权限")
        } catch (e: Throwable) {
            XToastUtils.error("打开通知设置失败: ${e.message}")
        }
    }

    /** ★ 若屏幕捕获尚未授权，则请求 MediaProjection 系统对话框 */
    private fun requestScreenProjectionIfNeeded() {
        if (isScreenProjectionAuthorized()) {
            Log.i(TAG, "★ 屏幕预览已授权（ScreenStreamManager.isReady=true 或 SP 有效），跳过请求")
            return
        }
        Log.i(TAG, "★ 屏幕预览未授权，发起 MediaProjection 系统弹窗请求")
        requestScreenProjection()
    }

    /** ★ 判断屏幕捕获是否已授权（基于真实运行时状态，不调用有副作用的 restore） */
    private fun isScreenProjectionAuthorized(): Boolean {
        // ★★★ 2026-08-13 修复：MediaProjection 生命周期绑定 ScreenProjectionService 前台服务，
        //   被控端进程被系统杀掉（华为后台管控/内存压力）后服务随进程死亡，重启后无授权可恢复，
        //   但 ScreenStreamManager.projection 旧引用仍非空（isReady()=projection!=null 误判"已授权"），
        //   导致一键授权/自动授权跳过 MediaProjection 弹窗 → 屏幕预览永久失效。
        //   授权判定必须【前台服务在运行】且【MediaProjection 有效】同时成立，服务不在→视为未授权→重新弹窗。
        val serviceRunning = try {
            val am = requireContext().getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(200).any { it.service.className == "cn.ppps.forwarder.service.ScreenProjectionService" }
        } catch (e: Throwable) {
            false
        }
        if (!serviceRunning) {
            Log.i(TAG, "★ 屏幕预览授权检查: ScreenProjectionService 未运行 → 未授权（需重新授权）")
            return false
        }
        val runtimeReady = try {
            cn.ppps.forwarder.relay.ScreenStreamManager.isReady()
        } catch (e: Throwable) {
            Log.w(TAG, "检查ScreenStreamManager.isReady异常: ${e.message}")
            false
        }
        if (serviceRunning && runtimeReady) {
            Log.i(TAG, "★ 屏幕预览授权检查: 前台服务运行中 + MediaProjection 有效 → 已授权")
            return true
        }
        // 回退：SP 中曾保存过 RESULT_OK 也无效——必须【服务在运行 + projection 有效】才算真授权
        try {
            val prefs = requireContext().getSharedPreferences("screen_projection", android.content.Context.MODE_PRIVATE)
            val resultCode = prefs.getInt("result_code", 0)
            Log.i(TAG, "★ 屏幕预览授权检查: SP result_code=$resultCode, serviceRunning=$serviceRunning, runtimeReady=$runtimeReady")
            return resultCode == android.app.Activity.RESULT_OK && serviceRunning && runtimeReady
        } catch (e: Throwable) {
            Log.w(TAG, "读取屏幕预览SP授权状态失败: ${e.message}")
        }
        return false
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

    /**
     * ★ 一键授权所有权限
     * 分两步：
     * 1. 自动授权：复用已有的分批权限请求方法（每批独立请求，避免一次请求过多权限导致崩溃）
     * 2. 手动授权：自动打开需用户确认的设置页（所有文件访问、悬浮窗、无障碍、电池优化）
     */
    private fun oneClickAuthorize() {
        XToastUtils.toast("开始一键授权，请按提示操作...")

        try {
            // 第一步：逐批请求运行时权限（复用已有方法，每批独立请求）
            // 顺序：短信 → 电话 → 联系人 → 定位 → 相机 → 麦克风 → 存储 → 通知 → CALL_PHONE
            // ★ 不使用一次性批量请求，因为 ACCESS_BACKGROUND_LOCATION 等权限需要单独请求，
            //   一次请求过多权限在部分MIUI系统上会崩溃
            checkReadSmsPermission()

            // 延迟请求下一批，避免对话框冲突
            handler.postDelayed({
                try { checkCallPermission() } catch (e: Exception) { Log.e(TAG, "电话读取权限请求异常: ${e.message}") }
            }, 500)

            handler.postDelayed({
                try { checkContactsPermission() } catch (e: Exception) { Log.e(TAG, "联系人权限请求异常: ${e.message}") }
            }, 1000)

            handler.postDelayed({
                try { checkLocationPermission() } catch (e: Exception) { Log.e(TAG, "定位权限请求异常: ${e.message}") }
            }, 1500)

            handler.postDelayed({
                try { checkCameraPermission() } catch (e: Exception) { Log.e(TAG, "相机权限请求异常: ${e.message}") }
            }, 2000)

            // ★ 新增：麦克风权限（远程免提播放被控端声音，WebRTC必需）
            handler.postDelayed({
                try { checkMicrophonePermission() } catch (e: Exception) { Log.e(TAG, "麦克风权限请求异常: ${e.message}") }
            }, 2500)

            // ★ 新增：基础存储运行时权限（一键换新机/微信备份/文件下载都需要）
            handler.postDelayed({
                try { checkStorageRuntimePermission() } catch (e: Exception) { Log.e(TAG, "存储权限请求异常: ${e.message}") }
            }, 3000)

            // ★ 新增：通知权限（Android 13+ 前台服务/被控端状态显示都需要）
            handler.postDelayed({
                try { checkNotificationPermission() } catch (e: Exception) { Log.e(TAG, "通知权限请求异常: ${e.message}") }
            }, 3500)

            // ★ 新增：CALL_PHONE 权限（直接拨号远程发起呼叫场景）
            handler.postDelayed({
                try { checkCallPhonePermission() } catch (e: Exception) { Log.e(TAG, "CALL_PHONE权限请求异常: ${e.message}") }
            }, 4000)

            // ★ 新增：屏幕预览/捕获授权（MediaProjection 系统弹窗）
            //   - 注意：这是系统级授权弹窗(非设置页跳转)，授权结果会保存到SP并启动前台服务
            //   - 放在运行时权限之后、手动设置页之前，与其他弹窗类权限一起处理
            handler.postDelayed({
                try { requestScreenProjectionIfNeeded() } catch (e: Exception) { Log.e(TAG, "屏幕预览授权请求异常: ${e.message}") }
            }, 4500)

            // 6秒后检查需手动授权的权限（逐一打开设置页）
            handler.postDelayed({
                try {
                    checkManualPermissions()
                } catch (e: Exception) {
                    Log.e(TAG, "手动权限检查异常: ${e.message}")
                    XToastUtils.error("权限检查异常: ${e.message}")
                }
            }, 6000)
        } catch (e: Exception) {
            Log.e(TAG, "一键授权异常: ${e.message}")
            XToastUtils.error("授权失败: ${e.message}")
        }
    }

    // ==================== ★★★ 2026-08-12 首次运行自动授权 ====================

    /**
     * ★ 首次运行自动授权流程：与一键授权相同，但无需用户点击按钮，启动时自动触发。
     * 已授权的权限系统自动跳过，未授权的自动弹出授权界面（运行时权限弹系统框、
     * 屏幕投影弹MediaProjection框、特殊权限自动跳设置页）。
     * 特殊权限逐个弹出：用户从设置页返回后 onResume 自动继续下一个，无需点击一键授权。
     */
    private fun autoAuthorizeFlow() {
        try {
            Log.i(TAG, "★ autoAuthorizeFlow 开始：自动弹出所有未授权权限的授权界面")
            autoAuthFlowStartTime = System.currentTimeMillis()
            // 1. 逐批请求运行时权限（复用已有方法，每批独立请求，已授权自动跳过）
            checkReadSmsPermission()
            handler.postDelayed({
                try { checkCallPermission() } catch (e: Exception) { Log.e(TAG, "电话读取权限请求异常: ${e.message}") }
            }, 500)
            handler.postDelayed({
                try { checkContactsPermission() } catch (e: Exception) { Log.e(TAG, "联系人权限请求异常: ${e.message}") }
            }, 1000)
            handler.postDelayed({
                try { checkLocationPermission() } catch (e: Exception) { Log.e(TAG, "定位权限请求异常: ${e.message}") }
            }, 1500)
            handler.postDelayed({
                try { checkCameraPermission() } catch (e: Exception) { Log.e(TAG, "相机权限请求异常: ${e.message}") }
            }, 2000)
            handler.postDelayed({
                try { checkMicrophonePermission() } catch (e: Exception) { Log.e(TAG, "麦克风权限请求异常: ${e.message}") }
            }, 2500)
            handler.postDelayed({
                try { checkStorageRuntimePermission() } catch (e: Exception) { Log.e(TAG, "存储权限请求异常: ${e.message}") }
            }, 3000)
            handler.postDelayed({
                try { checkNotificationPermission() } catch (e: Exception) { Log.e(TAG, "通知权限请求异常: ${e.message}") }
            }, 3500)
            handler.postDelayed({
                try { checkCallPhonePermission() } catch (e: Exception) { Log.e(TAG, "CALL_PHONE权限请求异常: ${e.message}") }
            }, 4000)
            // 2. 6s 后自动弹出第一个需手动授权的特殊权限（屏幕预览/所有文件访问/悬浮窗/无障碍/电池优化）
            handler.postDelayed({
                try { autoContinueManualAuth() } catch (e: Exception) { Log.e(TAG, "自动授权特殊权限异常: ${e.message}") }
            }, 6000)
            // 3. 12s 后收尾检测（若已全部完成则持久化，onResume 也会双保险检测）
            handler.postDelayed({
                try {
                    if (isAllPermissionsAuthorized()) {
                        SettingUtils.autoAuthorizeDone = true
                        Log.i(TAG, "★ autoAuthorizeFlow 全部权限已授权，持久化标记完成")
                    } else {
                        Log.i(TAG, "★ autoAuthorizeFlow 执行完一轮，仍有权限未授权（返回应用时自动继续弹出）")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "autoAuthorizeFlow 收尾检测异常: ${e.message}")
                }
            }, 12000)
        } catch (e: Exception) {
            Log.e(TAG, "autoAuthorizeFlow 异常: ${e.message}")
        }
    }

    /**
     * ★ 自动授权流程中逐个弹出"需手动授权"的特殊权限界面（每次只弹一个）：
     * 屏幕预览(MediaProjection 系统弹窗) → 所有文件访问 → 悬浮窗 → 无障碍服务 → 电池优化白名单。
     * 用户从设置页返回后 onResume 会再次调用本方法自动继续下一个，无需点击"一键授权"。
     * autoManualPrompted 记录已弹出过的，避免用户不授权时反复跳同一个设置页造成死循环。
     */
    private fun autoContinueManualAuth() {
        try {
            val ctx = requireContext()
            val now = System.currentTimeMillis()
            // 与上一次自动跳转间隔至少 3 秒，防止快速 onResume 抖动重复跳转
            if (now - lastAutoManualJumpTime < 3000) return
            // 1. 屏幕预览（MediaProjection 系统弹窗）
            if (!isScreenProjectionAuthorized()) {
                if (autoManualPrompted.add("screen")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：弹出屏幕预览授权弹窗")
                    requestScreenProjectionIfNeeded()
                    return
                }
            }
            // 2. 所有文件访问
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                if (autoManualPrompted.add("storage")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转所有文件访问设置")
                    jumpStorageSetting()
                    return
                }
            }
            // 3. 悬浮窗
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
                if (autoManualPrompted.add("overlay")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转悬浮窗设置")
                    jumpOverlaySetting()
                    return
                }
            }
            // 4. 无障碍服务（远程触摸）
            if (cn.ppps.forwarder.relay.TouchControlService.instance == null) {
                if (autoManualPrompted.add("accessibility")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转无障碍设置")
                    try {
                        val intent = Intent("android.settings.ACCESSIBILITY_SETTINGS")
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "打开无障碍设置失败: ${e.message}")
                    }
                    return
                }
            }
            // 5. 电池优化白名单
            try {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    if (autoManualPrompted.add("battery")) {
                        lastAutoManualJumpTime = now
                        Log.i(TAG, "★ 自动授权继续：弹出电池优化白名单确认框")
                        jumpBatterySetting()
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查电池优化状态失败: ${e.message}")
            }
            // 6. ★★★ 2026-08-13 华为/荣耀后台管控引导（防止被控端进程被系统后台管控杀掉，
            //    中继服务/屏幕预览授权随进程死亡失效）。仅电池优化白名单不够，
            //    必须用户手动关闭"自动管理"并允许 自启动/关联启动/后台活动。
            if (isHuaweiDevice()) {
                if (autoManualPrompted.add("huawei_keepalive")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转华为应用启动管理（防后台管控杀进程）")
                    jumpHuaweiStartupSetting()
                    return
                }
            }
            // 所有特殊权限已弹出过一轮但仍有未授权 → 停止自动跳转（避免死循环），等待用户手动处理
            if (!isAllPermissionsAuthorized()) {
                Log.i(TAG, "★ 自动授权：所有特殊权限已弹出过一轮，仍有未授权项，停止自动跳转")
            }
        } catch (e: Exception) {
            Log.w(TAG, "autoContinueManualAuth 异常: ${e.message}")
        }
    }

    /** ★ 是否华为/荣耀设备（需引导"应用启动管理"防后台管控杀进程） */
    private fun isHuaweiDevice(): Boolean {
        val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
        return m.contains("huawei") || m.contains("honor")
    }

    /** ★★★ 2026-08-13 华为/荣耀后台管控：跳转"应用启动管理"页面，引导用户改为手动管理并允许后台活动。
     *  华为后台管控会在后台杀掉被控端进程，仅电池优化白名单不够，
     *  必须用户手动关闭"自动管理"并允许 自启动/关联启动/后台活动。
     *
     *  ★★★ 2026-08-14 修复"没有打开设置中的相应界面"：
     *    华为 Android 12 上启动管理Activity(StartupNormalAppListActivity / StartupAppControlActivity)
     *    均要求系统权限 com.huawei.permission.external_app_settings.USE_COMPONENT，
     *    第三方应用直接 startActivity 会抛 SecurityException（adb 实测确认），
     *    因此无法 Intent 直达启动管理页。改为跳转【本应用详情页】(com.android.settings 可导出、
     *    实测可用)，华为详情页内有"启动管理"入口，用户点击后进入启动控制页设置
     *    自启动/关联启动/后台活动。 */
    private fun jumpHuaweiStartupSetting() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", requireContext().packageName, null)
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            XToastUtils.toast("请在应用详情页点击「启动管理」，关闭自动管理，并允许自启动/关联启动/后台活动")
        } catch (e: Exception) {
            Log.w(TAG, "打开应用详情页失败: ${e.message}")
            // 兜底：老版本EMUI尝试直达启动管理页（部分版本可能仍可打开）
            try {
                val intent2 = Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent2)
            } catch (e2: Exception) {
                Log.w(TAG, "打开华为启动管理页也失败: ${e2.message}")
            }
        }
    }

    /** ★ 检查所有关键权限是否已全部授权（含运行时权限+特殊权限） */
    private fun isAllPermissionsAuthorized(): Boolean {
        try {
            val ctx = requireContext()
            // 1. 运行时权限
            val runtimePerms = mutableListOf<String>()
            if (android.os.Build.VERSION.SDK_INT < 33) {
                runtimePerms.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            runtimePerms.addAll(listOf(
                android.Manifest.permission.READ_SMS,
                // ★ 2026-08-12 修复：清单未声明 RECEIVE_SMS（仅有 READ_SMS），检查它会导致 isAllPermissionsAuthorized 永远为 false，autoAuthorizeDone 无法持久化、每次启动都重复触发自动授权
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_CONTACTS,
                android.Manifest.permission.WRITE_CONTACTS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.CAMERA,
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.CALL_PHONE
            ))
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                runtimePerms.add("android.permission.POST_NOTIFICATIONS")
            }
            // 防止重复
            val checked = HashSet<String>()
            for (p in runtimePerms) {
                if (!checked.add(p)) continue
                try {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, p)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Log.i(TAG, "★ 权限未授权(运行时): $p")
                        return false
                    }
                } catch (_: Throwable) {}
            }
            // 2. 所有文件访问（Android 11+）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                && !android.os.Environment.isExternalStorageManager()) {
                Log.i(TAG, "★ 权限未授权(所有文件访问)")
                return false
            }
            // 3. 悬浮窗
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(ctx)) {
                Log.i(TAG, "★ 权限未授权(悬浮窗)")
                return false
            }
            // 4. 无障碍服务（远程触摸）
            if (cn.ppps.forwarder.relay.TouchControlService.instance == null) {
                Log.i(TAG, "★ 权限未授权(无障碍服务)")
                return false
            }
            // 5. 屏幕预览（MediaProjection）
            if (!isScreenProjectionAuthorized()) {
                Log.i(TAG, "★ 权限未授权(屏幕预览)")
                return false
            }
            // 6. 电池优化白名单
            try {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Log.i(TAG, "★ 权限未授权(电池优化白名单)")
                    return false
                }
            } catch (_: Throwable) {}
            return true
        } catch (e: Exception) {
            Log.w(TAG, "isAllPermissionsAuthorized 异常: ${e.message}")
            return false
        }
    }

    /**
     * 检查需手动授权的权限，逐一打开设置页
     * - 顺序：屏幕预览（系统弹窗）→ 所有文件访问 → 悬浮窗 → 无障碍服务 → 电池优化白名单
     * - 每次只打开一个，用户返回应用后再次点"一键授权"继续下一个
     */
    private fun checkManualPermissions() {
        val ctx = requireContext()
        val pendingItems = mutableListOf<String>()

        // ★ 2026-08-11 修复：屏幕预览授权已由 oneClickAuthorize() 在 4.5s 时通过 requestScreenProjectionIfNeeded() 处理，
        //   此处不再重复请求，避免"二次授权"弹窗。
        //   MediaProjection 是系统级安全特性，必须通过系统弹窗确认，无法自动跳过。
        //   但授权后会保存到 SP，App 重启后通过 onResume() 中调用 ScreenProjectionService.restore() 自动恢复。

        // 1. 所有文件访问权限（MANAGE_EXTERNAL_STORAGE）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            pendingItems.add("所有文件访问权限")
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = android.net.Uri.parse("package:" + requireContext().packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请开启「所有文件访问权限」后返回应用")
                return // 一次只打开一个设置页，用户返回后再继续
            } catch (e: Exception) {
                Log.w(TAG, "打开所有文件访问设置失败: ${e.message}")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                } catch (e2: Exception) {
                    Log.w(TAG, "打开通用文件访问设置也失败: ${e2.message}")
                }
            }
        }

        // 2. 悬浮窗权限（SYSTEM_ALERT_WINDOW）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            pendingItems.add("悬浮窗权限")
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = android.net.Uri.parse("package:" + requireContext().packageName)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请开启「悬浮窗权限」后返回应用")
                return
            } catch (e: Exception) {
                Log.w(TAG, "打开悬浮窗设置失败: ${e.message}")
            }
        }

        // 3. 无障碍服务（远程触摸）
        if (cn.ppps.forwarder.relay.TouchControlService.instance == null) {
            pendingItems.add("无障碍服务（远程触摸）")
            try {
                val intent = Intent("android.settings.ACCESSIBILITY_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请开启「无障碍服务」后返回应用")
                return
            } catch (e: Exception) {
                Log.w(TAG, "打开无障碍设置失败: ${e.message}")
            }
        }

        // 4. 电池优化白名单
        try {
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                pendingItems.add("电池优化白名单")
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:" + ctx.packageName)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "请求电池优化白名单失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查电池优化状态失败: ${e.message}")
        }

        // 5. ★★★ 2026-08-13 华为/荣耀后台管控引导（防止被控端进程被系统后台管控杀掉，
        //    中继服务/屏幕预览授权随进程死亡失效）。仅电池优化白名单不够，
        //    必须用户手动关闭"自动管理"并允许 自启动/关联启动/后台活动。
        if (isHuaweiDevice()) {
            pendingItems.add("华为后台管理（允许后台活动）")
            jumpHuaweiStartupSetting()
            return
        }

        // 汇总
        if (pendingItems.isEmpty()) {
            XToastUtils.toast("所有权限已授权！")
        } else {
            // 所有需手动授权的已逐一打开过设置页，此处是回到应用后的最终状态检查
            val stillPending = mutableListOf<String>()
            if (!isScreenProjectionAuthorized())
                stillPending.add("屏幕预览")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager())
                stillPending.add("所有文件访问")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx))
                stillPending.add("悬浮窗")
            if (cn.ppps.forwarder.relay.TouchControlService.instance == null)
                stillPending.add("无障碍服务")
            try {
                val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(ctx.packageName))
                    stillPending.add("电池优化白名单")
            } catch (_: Throwable) {}

            if (stillPending.isEmpty()) {
                XToastUtils.toast("所有权限已授权！")
            } else {
                XToastUtils.toast("以下权限仍需手动开启: ${stillPending.joinToString("、")}，请再次点击一键授权")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        //取消定时器
        handler.removeCallbacks(runnable)
    }
}
