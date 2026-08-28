package cn.ppps.forwarder.fragment

import android.content.ComponentName
import android.content.Intent
import android.os.Build
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

    /** ★ 2026-08-28 本次进程是否已触发过启动静默自检（防打扰：至多一次，且只可能弹一个电池白名单框） */
    @Volatile
    private var startupChecked = false

    /** ★ 2026-08-28 用户是否已点击「一键授权」：只有为 true 时 onResume 才继续逐个打开系统页 */
    @Volatile
    private var manualAuthFlowActive = false

    /** ★ 一键授权流程开始时间戳（用户从设置页返回后据此自动继续下一个授权界面） */
    @Volatile
    private var manualAuthFlowStartTime = 0L

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

        // ★ 2026-08-28 「授权自检报告」：只重跑检测并展示三类清单（不弹任何授权窗，避免打扰）
        binding!!.btnPermReport.setOnClickListener {
            try {
                activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.snapshotNow(it) }
            } catch (e: Exception) {
                Log.e(TAG, "打开授权自检报告失败: ${e.message}")
            }
        }

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

        //VPN授权
        binding!!.btnPermVpn.setOnClickListener {
            // 调用TailscaleManager请求VPN授权
            cn.ppps.forwarder.tailscale.TailscaleManager.requestVpnConsent(requireActivity())
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

        // ★ 2026-08-27 省电：UI 定时刷新改到 onResume/onPause 挂/摘（见下面两个回调）。
        //   旧代码在这里 handler.post(runnable) 且只在 onDestroy 取消，而被控端绝大部分时间处于
        //   "Activity 已 stop 但未销毁"的后台状态 → 每 5 秒仍执行一次 refreshButtonText()，
        //   其中含 VpnService.prepare() 的 binder 调用 + TailscaleManager.getSelfIp()
        //   （= 一次 libtailscale localapi /status 的 Go/JNI 重入，超时上限 15 秒）。
        //   界面不可见时这些刷新没有任何意义，却每小时制造 720 次唤醒。

        // ★★★ 2026-08-28 启动路径改为「静默自检 + 至多一个电池优化白名单弹窗」（防打扰硬要求）。
        //   旧实现在启动时自动连弹：运行时权限×9 + 屏幕捕获 + 所有文件访问 + 悬浮窗 + 无障碍 + 电池 + VPN，
        //   现收敛到统一模块 KeepAliveGuardian.onStartup()：
        //     · 全量检测在专用后台线程跑（不阻塞主线程），结果只写 logcat；
        //     · 仅当未进电池优化白名单且此前未引导过时，弹【一个】系统"忽略电池优化"确认框；
        //     · 其余需要跳系统页的授权一律等用户点「一键授权」/「授权自检报告」。
        try {
            if (!startupChecked) {
                startupChecked = true
                Log.i(TAG, "★ 启动自检：交由 KeepAliveGuardian.onStartup（静默检测 + 至多1个电池白名单弹窗）")
                handler.postDelayed({
                    try {
                        activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.onStartup(it) }
                    } catch (e: Exception) {
                        Log.w(TAG, "启动自检异常: ${e.message}")
                    }
                }, 1200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动授权触发异常: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshButtonText()
        // ★ 2026-08-27 省电：界面可见时才启动 5 秒刷新（onPause 里摘掉）
        handler.removeCallbacks(runnable)
        handler.postDelayed(runnable, 5000)
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

        // ★★★ 2026-08-28 一键授权流程收尾：仅当用户点过「一键授权」时，从系统设置页返回后继续打开下一个未授权项
        try {
            if (manualAuthFlowActive && !isAllPermissionsAuthorized()) {
                if (System.currentTimeMillis() - manualAuthFlowStartTime > 8000) {
                    Log.i(TAG, "★ 一键授权流程中：仍有权限未授权，onResume 继续下一个系统页")
                    autoContinueManualAuth()
                }
            } else if (manualAuthFlowActive && isAllPermissionsAuthorized()) {
                SettingUtils.autoAuthorizeDone = true
                Log.i(TAG, "★ 一键授权：全部权限已就绪")
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
        // VPN授权状态（3级：未授权/已授权未建立/已建立）
        try {
            val vpnUp = cn.ppps.forwarder.tailscale.TailscaleManager.isVpnUp()
            val vpnAuth = cn.ppps.forwarder.tailscale.TailscaleManager.isVpnAuthorized(requireContext())
            val selfIp = cn.ppps.forwarder.tailscale.TailscaleManager.getSelfIp()
            binding!!.tvPermVpnStatus.text = when {
                vpnUp && selfIp != null -> "VPN通道已建立 (IP: $selfIp)"
                vpnAuth -> "VPN已授权（中继正常时无需建立）"
                else -> getString(R.string.perm_status_not_granted)
            }
        } catch (e: Exception) {
            binding!!.tvPermVpnStatus.text = "VPN状态检查异常"
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
            // ★ 2026-08-28 统一模块的批量运行时权限结果：不再逐项弹 toast，只给一句结论 +
            //   对"系统已不再询问"的项跳应用详情页引导（requestPermissions 已无法再修复它们）
            cn.ppps.forwarder.permission.PermissionRequests.REQ_BATCH_RUNTIME -> {
                val grantedCount = grantResults.count { it == android.content.pm.PackageManager.PERMISSION_GRANTED }
                Log.i(TAG, "★ 批量运行时权限结果: 申请 ${grantResults.size} 项，授予 $grantedCount 项")
                if (ok && grantResults.isNotEmpty()) {
                    XToastUtils.success("批量运行时权限已全部授予（$grantedCount 项）")
                } else if (grantResults.isNotEmpty()) {
                    val neverAsked = mutableListOf<String>()
                    for (r in grantResults.indices) {
                        if (grantResults[r] != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                            !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), permissions[r])
                        ) {
                            neverAsked.add(permissions[r].substringAfterLast('.'))
                        }
                    }
                    if (neverAsked.isNotEmpty()) {
                        XToastUtils.error("已被永久拒绝，需去设置里放行: ${neverAsked.joinToString("、")}")
                        cn.ppps.forwarder.permission.PermissionRequests.openAppDetails(requireContext())
                    } else {
                        XToastUtils.toast("部分运行时权限未授予（$grantedCount/${grantResults.size}），可再次点击一键授权")
                    }
                }
            }
            cn.ppps.forwarder.permission.PermissionRequests.REQ_BACKGROUND_LOCATION -> {
                if (ok) XToastUtils.success("后台定位已授予（锁屏后仍可上报位置）")
                else XToastUtils.error("后台定位未授予：请在 应用详情页→权限→位置信息 选「始终允许」")
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

    /** ★★★ 2026-08-28 电池优化/后台保活是否已授权：口径唯一化，委托统一模块
     *  （PowerManager.isIgnoringBatteryOptimizations，华为/荣耀再补查 appops RUN_ANY_IN_BACKGROUND）。
     *  模块实现见 cn.ppps.forwarder.permission.PermissionProbe.isKeepAliveEffective。 */
    private fun isBatteryOptimizationAuthorized(ctx: android.content.Context): Boolean =
        cn.ppps.forwarder.permission.PermissionProbe.isKeepAliveEffective(ctx)

    /** 请求/跳转"电池优化白名单"：委托统一模块（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 优先，
     *  MIUI/EMUI 上该 Intent 常抛 ActivityNotFound，模块内已逐级兜底到
     *  通用电池优化列表页 → 机型省电策略页 → 应用详情页）。 */
    private fun jumpBatterySetting() {
        val ctx = requireContext()
        try {
            if (isBatteryOptimizationAuthorized(ctx)) {
                // ★ 2026-08-16 Tailscale 内嵌于本应用进程（libtailscale + TailscaleVpnService），
                //   本应用加入电池优化白名单即一并保护 Tailscale VPN 服务，防止被后台杀死。
                XToastUtils.success("已加入电池优化白名单，Tailscale(VPN服务)已一并受保护")
                return
            }
            when (cn.ppps.forwarder.permission.PermissionRequests.requestBatteryWhitelist(ctx)) {
                cn.ppps.forwarder.permission.OpenResult.OPENED ->
                    XToastUtils.toast("请点击「允许」以加入电池优化白名单（Tailscale VPN服务一并受保护）")
                cn.ppps.forwarder.permission.OpenResult.NOT_NEEDED ->
                    XToastUtils.success("已加入电池优化白名单")
                else -> {
                    // 系统入口都打不开：给出可复制的手工步骤，绝不崩
                    Log.w(TAG, "电池优化白名单入口全部打不开，仅展示手工步骤")
                    XToastUtils.error("无法打开系统电池优化设置，请手工设置：${cn.ppps.forwarder.permission.PermissionProbe.batteryStepsForUi()}")
                }
            }
        } catch (e: Throwable) {
            XToastUtils.error("检查/授权电池优化白名单失败: ${e.message}")
        }
    }

    // 按机型跳转电池/自启动设置页的兜底链路已统一进模块：
    //   PermissionRequests.requestBatteryWhitelist() → OemGuide.openAutostartPage() → openAppDetails()
    // 这里不再保留第二份厂商组件清单（旧 jumpBatterySettingByManufacturer 已删除）。

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

    /** ★ 判断屏幕捕获是否已授权（★ 2026-08-28 口径统一委托 PermissionProbe.isScreenCaptureAuthorized：
     *  必须【ScreenProjectionService 前台服务在运行】且【MediaProjection 实例有效】同时成立，
     *  进程被杀后旧 projection 引用会误判"已授权"，故服务不在即视为未授权 → 需重新弹窗）。*/
    private fun isScreenProjectionAuthorized(): Boolean {
        val authorized = try {
            cn.ppps.forwarder.permission.PermissionProbe.isScreenCaptureAuthorized(requireContext())
        } catch (e: Throwable) {
            Log.w(TAG, "检查屏幕预览授权异常: ${e.message}")
            false
        }
        Log.i(TAG, "★ 屏幕预览授权检查（模块）: $authorized")
        return authorized
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
        manualAuthFlowActive = true
        manualAuthFlowStartTime = System.currentTimeMillis()

        try {
            // ★ 2026-08-28 第 0 步（统一模块）：对"项目实际用到的"仍未授予的运行时权限做一次批量申请，
            //   永久拒绝项由模块剔除（系统不会再弹框），后台定位单独走第二步。
            try {
                val batched = cn.ppps.forwarder.permission.PermissionRequests.requestMissingRuntimeBatch(requireActivity())
                if (batched.isNotEmpty()) {
                    Log.i(TAG, "★ 一键授权：批量申请 ${batched.size} 项运行时权限")
                    cn.ppps.forwarder.permission.PermissionRequests.requestBackgroundLocation(requireActivity())
                }
            } catch (e: Exception) {
                Log.w(TAG, "批量运行时权限申请异常（回退到分组申请）: ${e.message}")
            }

            // 第一步：逐批请求运行时权限（复用已有方法，每批独立请求）
            // 顺序：短信 → 电话 → 联系人 → 定位 → 相机 → 麦克风 → 存储 → 通知 → CALL_PHONE
            // ★ 不使用一次性批量请求，因为 ACCESS_BACKGROUND_LOCATION 等权限需要单独请求，
            //   一次请求过多权限在部分MIUI系统上会崩溃；批量申请未覆盖到的（如被永久拒绝后重新放行）由此兜底
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

            // ★ 新增：VPN授权（Tailscale 系统对话框，首次安装后一次性授权）
            handler.postDelayed({
                try {
                    if (!cn.ppps.forwarder.tailscale.TailscaleManager.isVpnAuthorized(requireContext())) {
                        cn.ppps.forwarder.tailscale.TailscaleManager.requestVpnConsent(requireActivity())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "VPN授权请求异常: ${e.message}")
                }
            }, 5000)

            // 6秒后检查需手动授权的权限（逐一打开设置页）
            handler.postDelayed({
                try {
                    checkManualPermissions()
                } catch (e: Exception) {
                    Log.e(TAG, "手动权限检查异常: ${e.message}")
                    XToastUtils.error("权限检查异常: ${e.message}")
                }
            }, 6000)

            // ★ 2026-08-28 7.5 秒后出「三类清单」报告（已自动处理 / 需手工确认+精确步骤 / 不支持自动），
            //   让用户一眼看到还剩什么；同时写 logcat（tag=KeepAliveGuardian）便于 adb 无人化核对。
            handler.postDelayed({
                try {
                    activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.reportAndGuide(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "生成授权报告异常: ${e.message}")
                }
            }, 7500)
        } catch (e: Exception) {
            Log.e(TAG, "一键授权异常: ${e.message}")
            XToastUtils.error("授权失败: ${e.message}")
        }
    }

    // ==================== ★★★ 2026-08-28 启动路径与一键授权共用统一模块 ====================
    //
    // 旧的 autoAuthorizeFlow()（启动时自动连弹所有授权界面）已删除：
    //   需求硬约束「启动时不要弹一堆窗口——启动路径只做静默检测 + 至多一个电池白名单弹窗」。
    //   启动路径现在只调用 cn.ppps.forwarder.permission.KeepAliveGuardian.onStartup()；
    //   完整授权链路保留在 oneClickAuthorize()（用户主动触发）+ autoContinueManualAuth()（返回后继续）里，
    //   两侧的"检测口径"都收敛到 PermissionProbe / PermissionRequests，避免两处判定不一致。

    /**
     * ★ 一键授权流程中逐个弹出"需手动授权"的特殊权限界面（每次只弹一个）：
     * 屏幕预览(MediaProjection 系统弹窗) → 所有文件访问 → 悬浮窗 → 无障碍服务 → 电池优化白名单 → OEM 自启动。
     * 用户从设置页返回后 onResume 会再次调用本方法自动继续下一个。
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
            // ★★★ 2026-08-15 防重复弹窗：已提示过（持久化batteryAuthGuided）或已授权则不再弹出
            if (!isBatteryOptimizationAuthorized(ctx) && !SettingUtils.batteryAuthGuided) {
                if (autoManualPrompted.add("battery")) {
                    lastAutoManualJumpTime = now
                    SettingUtils.batteryAuthGuided = true
                    Log.i(TAG, "★ 自动授权继续：弹出电池优化白名单确认框（已持久化batteryAuthGuided防重复弹窗）")
                    jumpBatterySetting()
                    return
                }
            }
            // VPN授权
            if (!autoManualPrompted.contains("vpn") && !cn.ppps.forwarder.tailscale.TailscaleManager.isVpnAuthorized(requireContext())) {
                autoManualPrompted.add("vpn")
                lastAutoManualJumpTime = System.currentTimeMillis()
                Log.i(TAG, "★ 自动授权流程：请求VPN授权")
                cn.ppps.forwarder.tailscale.TailscaleManager.requestVpnConsent(requireActivity())
                return
            }
            // 6. ★★★ 2026-08-28 OEM 自启动 / 后台限制引导（原来只覆盖华为，现按统一模块泛化到
            //    MIUI/EMUI/ColorOS/OriginOS）：仅电池优化白名单不够，国产 ROM 的后台管控会直接杀进程，
            //    中继服务/屏幕预览授权随进程死亡失效，必须用户手动允许 自启动/关联启动/后台活动。
            //    触发条件：保活未真正生效，或系统根本不开放自启动状态读取（MIUI）；只引导一次。
            if (cn.ppps.forwarder.permission.RomType.needsOemGuide &&
                (!isBatteryOptimizationAuthorized(ctx) ||
                        cn.ppps.forwarder.permission.OemGuide.probeAutostartState(ctx) !=
                        cn.ppps.forwarder.permission.GrantState.GRANTED) &&
                !SettingUtils.oemAutostartGuided
            ) {
                if (autoManualPrompted.add("oem_autostart")) {
                    lastAutoManualJumpTime = now
                    SettingUtils.oemAutostartGuided = true
                    if (isHuaweiDevice()) SettingUtils.huaweiKeepaliveGuided = true
                    Log.i(TAG, "★ 一键授权继续：跳转 ${cn.ppps.forwarder.permission.RomType.describe()} 自启动/后台管理页（只引导一次）")
                    openOemAutostartSetting()
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

    /** ★★★ 2026-08-28 跳转 OEM 自启动 / 后台活动管理页（MIUI 安全中心、EMUI 手机管家等），
     *  实现统一在 OemGuide：先按 resolveActivity 校验组件存在再拉起（避免 MIUI/EMUI 抛
     *  ActivityNotFoundException / SecurityException 把被控端带崩），
     *  打不开则退回本应用详情页 + 在 UI 上给出【可复制的手工步骤】。 */
    private fun openOemAutostartSetting() {
        val ctx = requireContext()
        val steps = cn.ppps.forwarder.permission.OemGuide.manualSteps()
        when (cn.ppps.forwarder.permission.OemGuide.openAutostartPage(ctx)) {
            cn.ppps.forwarder.permission.OpenResult.OPENED ->
                XToastUtils.toast("请在打开的页面里允许 SmsForwarder 自启动 / 后台活动（${cn.ppps.forwarder.permission.RomType.describe()}）")
            else -> {
                Log.w(TAG, "未找到 ${cn.ppps.forwarder.permission.RomType.describe()} 自启动管理入口，退回应用详情页并展示手工步骤")
                cn.ppps.forwarder.permission.PermissionRequests.openAppDetails(ctx)
                XToastUtils.error("该 ROM 无直达入口，请照步骤手工设置：$steps")
            }
        }
    }

    /** 兼容旧调用点：华为/荣耀后台管控引导（现统一走 openOemAutostartSetting）。 */
    private fun jumpHuaweiStartupSetting() {
        openOemAutostartSetting()
    }

    /** ★ 检查所有关键权限是否已全部授权（★ 2026-08-28 口径统一到 PermissionProbe/KeepAliveReport，
     *  不再在本类里维护第二份权限清单。优先复用 3 秒内的最近一次报告，避免重复 binder 轮询）。*/
    private fun isAllPermissionsAuthorized(): Boolean {
        return try {
            val fresh = cn.ppps.forwarder.permission.KeepAliveGuardian.lastReport()
            if (fresh != null && System.currentTimeMillis() - fresh.generatedAt < 3000) {
                fresh.allClear
            } else {
                cn.ppps.forwarder.permission.KeepAliveGuardian.diagnoseSync(requireContext()).allClear
            }
        } catch (e: Exception) {
            Log.w(TAG, "isAllPermissionsAuthorized 异常: ${e.message}")
            false
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            !cn.ppps.forwarder.permission.PermissionProbe.isAllFilesAccessGranted(ctx)
        ) {
            pendingItems.add("所有文件访问权限")
            val r = cn.ppps.forwarder.permission.PermissionRequests.openAllFilesAccess(ctx)
            if (r == cn.ppps.forwarder.permission.OpenResult.OPENED) {
                XToastUtils.toast("请开启「所有文件访问权限」后返回应用")
                return // 一次只打开一个设置页，用户返回后再继续
            }
            Log.w(TAG, "所有文件访问设置页打不开($r)，继续下一项并在报告里给手工步骤")
        }

        // 2. 悬浮窗权限（SYSTEM_ALERT_WINDOW）
        if (!cn.ppps.forwarder.permission.PermissionProbe.isOverlayGranted(ctx)) {
            pendingItems.add("悬浮窗权限")
            val r = cn.ppps.forwarder.permission.PermissionRequests.openOverlaySettings(ctx)
            if (r == cn.ppps.forwarder.permission.OpenResult.OPENED) {
                XToastUtils.toast("请开启「悬浮窗权限」后返回应用")
                return
            }
            Log.w(TAG, "悬浮窗设置页打不开($r)，继续下一项")
        }

        // 3. 无障碍服务（远程触摸）——系统禁止第三方程序化开启，只能跳设置页引导
        if (!cn.ppps.forwarder.permission.PermissionProbe.isAccessibilityEnabled(ctx)) {
            pendingItems.add("无障碍服务（远程触摸）")
            val r = cn.ppps.forwarder.permission.PermissionRequests.openAccessibilitySettings(ctx)
            if (r == cn.ppps.forwarder.permission.OpenResult.OPENED) {
                XToastUtils.toast("请开启「无障碍服务→SmsForwarder 远程触摸」后返回应用")
                return
            }
            Log.w(TAG, "无障碍设置页打不开($r)，继续下一项")
        }

        // 4. 电池优化白名单（防重复弹窗：已提示过 batteryAuthGuided 或已授权则不再打开）
        if (!isBatteryOptimizationAuthorized(ctx) && !SettingUtils.batteryAuthGuided) {
            pendingItems.add("电池优化白名单")
            SettingUtils.batteryAuthGuided = true
            jumpBatterySetting()
            return
        }

        // 5. OEM 自启动 / 后台活动引导（★ 2026-08-28 由"仅华为"泛化到 MIUI/EMUI/ColorOS/OriginOS）
        if (cn.ppps.forwarder.permission.RomType.needsOemGuide &&
            cn.ppps.forwarder.permission.OemGuide.probeAutostartState(ctx) !=
            cn.ppps.forwarder.permission.GrantState.GRANTED &&
            !SettingUtils.oemAutostartGuided
        ) {
            pendingItems.add("${cn.ppps.forwarder.permission.RomType.describe()} 自启动/后台活动")
            SettingUtils.oemAutostartGuided = true
            if (isHuaweiDevice()) SettingUtils.huaweiKeepaliveGuided = true
            openOemAutostartSetting()
            return
        }

        // 汇总：三类清单在 oneClickAuthorize 末尾由 KeepAliveGuardian.reportAndGuide 统一弹窗，
        // 这里只给一条简短 toast，避免多窗口打扰。
        val report = try {
            cn.ppps.forwarder.permission.KeepAliveGuardian.diagnoseSync(ctx)
        } catch (e: Exception) {
            Log.w(TAG, "汇总自检失败: ${e.message}")
            null
        }
        if (report == null) {
            XToastUtils.error("权限检查异常，请重试")
        } else if (report.allClear) {
            XToastUtils.toast("所有权限已授权！")
        } else {
            val names = report.needManual.joinToString("、") { it.label.substringBefore('（') }
            XToastUtils.toast("仍需手工确认: $names（详情见刚弹出的三类清单，可复制步骤）")
        }
        if (pendingItems.isNotEmpty()) {
            Log.i(TAG, "★ 本轮逐一打开过的系统页: $pendingItems")
        }
    }

    /**
     * ★ 2026-08-27 省电：界面不可见立即停止 5 秒轮询。
     *   被控端的常态是"启动完服务后就把 App 划到后台/锁屏"，此时旧代码仍每小时轮询 720 次
     *   VpnService.prepare + libtailscale localapi /status（Go/JNI 重入）。
     */
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(runnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        //取消定时器
        handler.removeCallbacks(runnable)
    }
}
