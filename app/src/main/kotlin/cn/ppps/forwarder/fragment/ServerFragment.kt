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
import cn.ppps.forwarder.relay.UpgradeManager

@Page(name = "系统设置")
class ServerFragment : BaseFragment<FragmentServerBinding?>(), View.OnClickListener {

    private val TAG: String = ServerFragment::class.java.simpleName
    private var appContext: App? = null

    /** ★ 2026-08-28 本次进程是否已触发过启动静默自检（防打扰：至多一次，且只可能弹一个电池白名单框） */
    @Volatile
    private var startupChecked = false

    /** ★ 2026-10-26 本次进程是否已展示过"首次安装启动"总向导（防 onResume 反复弹） */
    @Volatile
    private var firstLaunchOnboardShown = false

    /** ★ 2026-11-07 "已完成全部授权"提示只弹一次（只有严格判定通过时才提示） */
    @Volatile
    private var allClearTipShown = false

    /** ★ 2026-08-28 用户是否已点击「一键授权」：只有为 true 时 onResume 才继续逐个打开系统页 */
    @Volatile
    // ★ 2026-08-29 本次一键授权是否真的发起了批量运行时权限申请（决定报告时机）
    private var batchRuntimeIssued = false
    private var batchReportShown = false

    private var manualAuthFlowActive = false

    /** ★ 一键授权流程开始时间戳（用户从设置页返回后据此自动继续下一个授权界面） */
    @Volatile
    private var manualAuthFlowStartTime = 0L

    /** ★★★ 2026-11-03 最近一次"授权动作"的时间戳：用于判断"授权流程是否正在进行"，
     *  MainActivity 据此决定"绝不把真实界面摘掉/踢回伪装页"（否则用户看到的就是授权中闪退）。 */
    @Volatile
    private var lastAuthStepAt = 0L

    /** 超过这么久没有任何授权动作 ⇒ 视为流程已结束（界面可以正常收起） */
    private val AUTH_FLOW_ACTIVE_WINDOW_MS = 180_000L

    /** ★ 已自动弹出过设置页/弹窗的特殊权限标记（防止用户不授权时反复跳同一个设置页造成死循环） */
    private val autoManualPrompted = mutableSetOf<String>()

    /** ★★★ 2026-11-08 从「逐项去处理」进入：即便 OEM 自启动探测显示"已放行"（如华为 appops=GRANTED），
     *  也要**强制导航一次**启动管理页 —— 否则用户在华为上点"逐项去处理"会毫无反应。 */
    @Volatile
    private var guideFromReport = false

    /** ★ 2026-11-08 授权完成提示（每轮流程只提示一次；通知未关闭时绝不提示完成） */
    private var authDoneTipShown = false

    /** ★★★ 2026-11-08 用户是否**真的离开过**本应用（去系统设置页）。
     *  仅在 onPause 期间置 true，onResume 消费后置 false —— 用来区分
     *  "从系统页返回" 与 "startActivity 后立即触发的 onResume"，
     *  避免刚打开的自启动页被下一个"通知设置"页瞬间覆盖。 */
    @Volatile
    private var leftAppForSystemUi = false

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
        // ★ 2026-11-08 用户要求：授权页只保留「自检报告 / 未自动授权项 / 检查更新」，
        //   中继服务器配置、开机自启开关、功能开关等全部隐藏（功能本身不受影响）。
        try {
            listOf(
                binding!!.etRelayHost, binding!!.etServerPort, binding!!.etDeviceMark,
                binding!!.scbServerAutorun, binding!!.btnToggleServer
            ).forEach { v -> (v.parent as? View)?.visibility = View.GONE }
        } catch (e: Exception) {
            Log.e(TAG, "精简授权页失败: ${e.message}")
        }
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        // ★ 2026-11-08 用户要求：授权界面顶部标题由「主动控制·服务端」改为「系统设置」
        titleBar.setTitle("系统设置")
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
        // ★ 2026-10-16【真机踩坑】本页的点击是**逐个显式绑定**（不是 @SingleClick 自动生效）：
        //   漏了这行 ⇒ 按钮能显示、点下去毫无反应（不崩，只是没事件），
        //   而状态文本永远是空 —— 真机上排查了半天才发现是"没绑 listener"。
        binding!!.btnCheckUpdate.setOnClickListener(this)

        // ★ 2026-08-28 「授权自检报告」：只重跑检测并展示三类清单（不弹任何授权窗，避免打扰）
        binding!!.btnPermReport.setOnClickListener {
            try {
                activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.snapshotNow(it) { continueManualAuthFromReport() } }
            } catch (e: Exception) {
                Log.e(TAG, "打开授权自检报告失败: ${e.message}")
            }
        }

        // ============ 新增 6 项独立权限授权按钮（与图片中"远程触摸"一致的样式，点击立刻授权）============
        binding!!.btnPermMic.setOnClickListener {
            markSystemUiHandoff()
            checkMicrophonePermission()
        }
        binding!!.btnPermStorage.setOnClickListener {
            markSystemUiHandoff()
            checkStorageRuntimePermission()
            // 基础存储权限授权成功后，跳"所有文件访问"（Android 11+）
            handler.postDelayed({ jumpStorageSetting() }, 800)
        }
        binding!!.btnPermNotification.setOnClickListener {
            markSystemUiHandoff()
            checkNotificationPermission()
        }
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

        // ★ 2026-11-07 自启动/后台活动（系统不开放读取，按机型跳转引导）
        binding!!.btnPermAutostart.setOnClickListener {
            markSystemUiHandoff()
            // ★★★ 2026-11-08 用户要求：通知未关闭也要继续导航，直到全部完成才提示授权完成。
            //   因此这里**不再终止**自动流程（旧实现置 false，导致返回后永远不再跳通知设置页）；
            //   由 onResume（leftAppForSystemUi 判定用户确实离开过本应用）继续下一项。
            manualAuthFlowActive = true
            if (manualAuthFlowStartTime <= 0L) manualAuthFlowStartTime = System.currentTimeMillis()
            autoManualPrompted.add("oem_autostart")
            // ★ 2026-11-08 用户点「去开启」只关心保活项：屏幕预览/存储/悬浮窗/安装未知应用
            //   会弹系统弹窗或打断，不应阻塞后续「通知 → 无障碍」的导航，故预置为已处理。
            autoManualPrompted.add("screen")
            autoManualPrompted.add("storage")
            autoManualPrompted.add("overlay")
            autoManualPrompted.add("install_unknown")
            openOemAutostartSetting()
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
            // ★ 2026-11-10：查联系人只申请 READ_CONTACTS，不因「写」被拒连带影响查询功能
            if (isChecked) checkContactsPermission(forAdd = false)
        }

        binding!!.sbApiAddContacts.isChecked = HttpServerUtils.enableApiContactAdd
        binding!!.sbApiAddContacts.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            HttpServerUtils.enableApiContactAdd = isChecked
            // ★ 2026-11-10：加联系人才申请 READ + WRITE_CONTACTS
            if (isChecked) checkContactsPermission(forAdd = true)
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
            // ★★★ 2026-11-08 用户要求：授权页的「无障碍设置」入口必须始终可用。
            //   即使服务已开启，也导航到系统无障碍设置页（便于关闭/重开），不再只弹 toast。
            if (cn.ppps.forwarder.relay.TouchControlService.instance != null) {
                XToastUtils.toast("无障碍服务已开启；如需关闭或重新开启，请在本页操作")
            }
            try {
                val intent = Intent("android.settings.ACCESSIBILITY_SETTINGS")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                XToastUtils.error("打开无障碍设置失败: ${e.message}")
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

        // ★★★ 2026-11-08 流程收尾：只有「可检测项全部就绪 + 通知已关闭」才算完成。
        //   不再用 isAllPermissionsAuthorized() 单独判定 —— 它不含"通知关闭"，
        //   MIUI 上自启动(UNKNOWN)处理完后会被误判成"已完成"，通知设置页永远不再导航（真机问题）。
        //   用 leftAppForSystemUi 区分「从系统页返回」与「startActivity 后立即触发的 onResume」。
        try {
            val ctx = requireContext()
            // ★ 2026-11-08 用户要求：通知要求关闭。只有**明确检测到已关闭**才算完成；
            //   读取异常（无法检测）时按"未关闭"处理 → 卡片显示、继续导航去系统通知页。
            val notificationsNotClosed =
                cn.ppps.forwarder.permission.PermissionProbe.areNotificationsEnabledOrNull(ctx) != false
            val stillPending = !isAllPermissionsAuthorized() || notificationsNotClosed
            if (manualAuthFlowActive && leftAppForSystemUi && stillPending) {
                leftAppForSystemUi = false
                Log.i(TAG, "★ 授权流程中：仍有未完成项（通知未关闭/无法检测=$notificationsNotClosed），onResume 继续下一个系统页")
                autoContinueManualAuth()
            } else if (manualAuthFlowActive && leftAppForSystemUi && !stillPending) {
                leftAppForSystemUi = false
                SettingUtils.autoAuthorizeDone = true
                // ★ 2026-11-04【需求】授权完成 → 继续停在真实界面（不自动退后台）。
                markAuthStayForeground(true)
                Log.i(TAG, "★ 授权流程：全部设置已就绪（含通知已关闭）")
            }
        } catch (e: Exception) {
            Log.w(TAG, "自动授权完成检测异常: ${e.message}")
        }
        // ★★★ 2026-11-07 用户要求：启动时不列全部授权项；诊断后只显示【未完成】项。
        try {
            cn.ppps.forwarder.permission.KeepAliveGuardian.diagnoseAsync(
                requireContext(), activity
            ) { report -> refreshPermissionCards(report) }
        } catch (t: Throwable) {
            Log.w(TAG, "刷新授权卡片异常: ${t.message}")
        }
        // ★ 2026-10-26 首次安装/升级后只跑一次的「权限 + 保活」总向导（自动申请 + 逐项跳转引导）
        try {
            maybeStartFirstLaunchOnboarding()
        } catch (e: Exception) {
            Log.w(TAG, "首次启动授权向导异常: ${e.message}")
        }
        // ★★★ 2026-11-08 用户要求：启动时把「使用情况访问权限」纳入自动授权（操作记录数据源）。
        //   appops 特殊权限 App 无法自授（adb 批处理 grant_target_perms.bat 可自动授）；
        //   已完成首启向导的设备：未授予时本进程首次进入界面自动导航到系统设置页引导一次。
        //   首启向导未完成的设备由向导内的一键授权流程引导（避免与向导弹窗抢跳）。
        try {
            if (SettingUtils.keepAliveOnboardDone && !usageAccessGuideShown &&
                !cn.ppps.forwarder.permission.PermissionProbe.isUsageAccessGranted(requireContext())) {
                usageAccessGuideShown = true
                markSystemUiHandoff()
                val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                XToastUtils.toast("请找到 SmsForwarder 并允许「使用情况访问权限」（远程查看操作记录需要）")
                Log.i(TAG, "★ 启动自动授权：使用情况访问未授予 → 已导航系统设置页")
            }
        } catch (e: Exception) {
            Log.w(TAG, "使用情况访问启动引导异常: ${e.message}")
        }
        // ★ 2026-10-16：把后台「自动检测/自动下载」的最新结果带出来 —— 否则用户在界面上
        //   看不到"其实已经自动下好新版了"（安装仍由他点「检查更新」确认）。
        try {
            val t = UpgradeManager.lastAutoTime
            val m = UpgradeManager.lastAutoResult
            // ★ 只在"自动结果比手动操作更新"时展示，避免把"安装失败原因"等手动反馈盖掉
            if (m.isNotEmpty() && t > shownAutoTime && t > lastManualTime) {
                shownAutoTime = t
                binding?.tvUpdateStatus?.text = "自动检测：$m（点「检查更新」可确认安装）"
            }
        } catch (e: Exception) {
            // 忽略：状态显示失败不影响功能
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

    // ==================== 手机被控端自升级（2026-10-16） ====================

    /**
     * 「检查更新」按钮（用户口径）：
     *  ① 先看手机上是否已有"自动下载的、高于当前版本的 APK" ⇒ 有则确认后直接安装；
     *  ② 否则取清单（中继模式→中继云 56790 / 直连模式→NAS，双地址）比对 ⇒ 有新版**自动下载**；
     *  ③ 下载完成弹确认 → 调起系统安装界面（普通应用无静默安装权限）。
     * ★ 只由按钮触发，不做自动检查（用户明确要求）。
     */
    /** 已展示过的自动检测时间戳（避免每次 onResume 重复刷同一句）。 */
    private var shownAutoTime = 0L
    /** 最近一次手动操作（检查更新/点安装）的时间，用来防止自动结果覆盖手动反馈。 */
    private var lastManualTime = 0L


    private fun checkUpdate() {
        val ctx = context ?: return
        val act = activity
        val relay = UpgradeManager.detectRelayMode()
        // ★ 用户要求：点「检查更新」时先显示"手机上已安装的版本"，随后补上云端版本
        lastManualTime = System.currentTimeMillis()
        binding?.tvUpdateStatus?.text =
            "已安装版本 ${UpgradeManager.localVersionName()}，正在检查云端版本…"
        Thread {
            val result = try {
                UpgradeManager.checkUpdate(ctx, relay) { st ->
                    act?.runOnUiThread { binding?.tvUpdateStatus?.text = st.text }
                }
            } catch (e: Exception) {
                null to ("检查失败: " + (e.message ?: e.javaClass.simpleName))
            }
            val file = result.first
            val msg = result.second
            act?.runOnUiThread {
                binding?.tvUpdateStatus?.text = msg
                if (file == null) {
                    android.widget.Toast.makeText(ctx, msg,
                        android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                // ★★ 2026-10-17【真机踩坑】这里原来用的是 android.app.AlertDialog（平台原生
                //   Holo 风格），在本项目的 Material 主题下**按钮整块不可见** —— 真机截图里
                //   弹窗只有标题和正文，"安装/稍后"根本看不到也点不到。
                //   改用项目统一的 XUI MaterialDialog（SplashActivity / CommonUtils /
                //   KeepAliveGuardian 用的都是它），并把"已安装版本 ↔ 云端版本"写进正文。
                // ★★ 2026-10-17【真机踩坑二连】
                //   ① 原来用 android.app.AlertDialog（平台原生 Holo 风格），在本项目 Material
                //      主题下按钮文字与背景同色 ⇒ 截图里"根本没有安装按钮"。
                //   ② 换成 XUI MaterialDialog 后，Kotlin 对它的 onPositive/onNegative SAM
                //      推断失败（Cannot infer a type for this parameter）。
                //   ⇒ 最终用 **androidx.appcompat.app.AlertDialog**：在任何 AppCompat/Material
                //      主题下按钮配色正常，且 setPositiveButton 的 lambda 是标准 OnClickListener。
                // ★★ 2026-10-17【华为真机】androidx.appcompat.AlertDialog 在 EMUI 上按钮
                //   节点存在、可点击，但文字色与背景被主题改成同色 ⇒ 视觉上"没有按钮"。
                //   ⇒ 改用 UpgradeManager 的**自绘对话框**（纯 TextView + 硬编码颜色），
                //     任何 ROM 都不受主题影响。
                lastManualTime = System.currentTimeMillis()
                UpgradeManager.showInstallDialog(ctx, file, onInstall = {
                    val tip = UpgradeManager.install(ctx, file)
                    binding?.tvUpdateStatus?.text = tip
                    // 安装结果也用自绘弹窗（同样避免主题问题）
                    UpgradeManager.showInfoDialog(ctx, "安装", tip)
                }, onLater = {
                    binding?.tvUpdateStatus?.text =
                        "已下载到手机，稍后点「检查更新」可直接安装"
                })
            }
        }.start()
    }


    @SingleClick
    override fun onClick(v: View) {
        when (v.id) {
            R.id.btn_toggle_server -> {
                if (RelayServerService.isRunning) {
                    // ★ 2026-11-08【服务自恢复】用户主动停止 → 记录意图，进程重启后不再自动拉起
                    RelaySettings.userStoppedServer = true
                    RelayServerService.stop(requireContext())
                } else {
                    // ★ 用户主动启动 → 清除"主动停止"标志，恢复进程启动时自动拉起
                    RelaySettings.userStoppedServer = false
                    checkCallPermission()
                    checkContactsPermission()
                    checkLocationPermission()
                    RelayServerService.start(requireContext())
                    // ★★★ 2026-11-03 需求（用户明确）：点【运行】启动服务后，弹出**像手机系统设置**
                    //   的封面页（FakeSettingsActivity）。页面里点任何一项都没有实际动作，
                    //   只有连续点「关于手机」10 次才进真实界面并开始授权。
                    openFakeSettings()
                }
                refreshButtonText()
            }

            R.id.btn_one_click_auth -> {
                oneClickAuthorize()
            }

            R.id.btn_check_update -> {
                checkUpdate()
            }

            else -> {}
        }
    }

    //刷新按钮
    private fun refreshButtonText() {
        // ★★★ 2026-11-08 远程触摸状态文案统一由 refreshPermissionCards 维护
        //   （无障碍卡片始终显示，文案含"进入系统无障碍设置"的入口说明）；
        //   旧代码每 5 秒用 touch_service_enabled/disabled 覆盖，会把卡片提示冲掉，故移除。
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
                        } else {
                            XToastUtils.error(R.string.toast_denied)
                        }
                        // ★ 2026-11-10【权限误报修复】权限请求被拒时不再自动关闭功能开关。
                        //   原实现会把 enable_api_sms_query 落盘为 false，用户事后在系统设置里
                        //   补授权后，控制端仍永久提示“已关闭「远程短信查询」功能”，形成误报。
                        //   开关只由用户手动控制；权限是否可用在收到查询命令时实时判定。
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
                        } else {
                            XToastUtils.error(R.string.toast_denied)
                        }
                        // ★ 2026-11-10【权限误报修复】权限请求被拒时不再自动关闭「远程查通话」开关。
                        //   原实现会把 enable_api_call_query 落盘为 false，用户事后在系统设置里补授权后，
                        //   控制端仍永久提示“服务端已禁用该功能”，形成“权限已授权却提示禁用”的误报。
                        //   开关只由用户手动控制；权限是否可用在收到查询命令时实时判定。
                    }
                }
            })
    }

    //联系人权限
    // ★ 2026-11-10【权限误报修复】查询与添加分开授权：
    //   查询只需 READ_CONTACTS，添加才需要 READ + WRITE_CONTACTS。
    //   旧实现两个开关共用一个函数（同时申请读+写），用户只拒绝「写」时，
    //   连「远程查联系人」开关也会被永久关闭，形成“权限已授权却提示禁用”的误报。
    private fun checkContactsPermission(forAdd: Boolean = true) {
        val builder = XXPermissions.with(this)
            .permission(PermissionLists.getReadContactsPermission())
        if (forAdd) {
            builder.permission(PermissionLists.getWriteContactsPermission())
        }
        builder.request(object : OnPermissionCallback {
            override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                val allGranted = deniedList.isEmpty()
                if (!allGranted) {
                    val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                    if (doNotAskAgain) {
                        XToastUtils.error(R.string.toast_denied_never)
                        XXPermissions.startPermissionActivity(requireContext(), deniedList)
                    } else {
                        XToastUtils.error(R.string.toast_denied)
                    }
                    // ★ 2026-11-10【权限误报修复】不再自动关闭「远程查联系人 / 远程加联系人」开关。
                    //   原实现会把 enable_api_contact_query / enable_api_contact_add 落盘为 false，
                    //   用户事后补授权后控制端仍永久提示“服务端已禁用该功能”。
                    //   开关只由用户手动控制；权限是否可用在收到命令时实时判定。
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
                        } else {
                            XToastUtils.error(R.string.toast_denied)
                        }
                        // ★ 2026-11-10【权限误报修复】同短信：权限请求失败不再自动关闭「远程定位」开关，
                        //   避免用户补授权后仍被“已关闭该功能”拦截（定位默认开启已被误关过一次）。
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
                        } else {
                            XToastUtils.error(R.string.toast_denied)
                        }
                        // ★ 2026-11-10【权限误报修复】权限请求被拒时不再自动关闭「远程摄像头」开关。
                        //   原实现会把 enable_api_camera 落盘为 false，用户事后补授权后仍提示“已禁用摄像头”。
                        //   开关只由用户手动控制；权限是否可用在收到推流命令时实时判定。
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
                        } else {
                            XToastUtils.error("麦克风权限未授予，远程监听功能不可用")
                        }
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

        /** ★ 2026-11-08 使用情况访问权限：本进程已自动导航过一次（进程级，重启后未授予会再引导） */
        private var usageAccessGuideShown = false
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
        // ★★★ 2026-11-08 用户要求：点「授权通知」必须**导航到系统通知设置页**（包含通知总开关），
        //   不再只做 POST_NOTIFICATIONS 运行时申请 —— 真机上"运行时权限已授予但通知总开关被关"
        //   时旧逻辑只弹 toast「已授权」，用户看不到任何设置页。
        val ctx = requireContext()
        // ★ 2026-11-10 用户要求：通知是"要求关闭"的项。
        //   点击授权按钮或自动授权过程中，只有**检测到通知开关仍处于开启状态**时才弹出系统通知设置页；
        //   已确认关闭（或无法检测）时不再弹窗，避免无意义地打断用户。
        val notifyEnabled = cn.ppps.forwarder.permission.PermissionProbe.areNotificationsEnabledOrNull(ctx)
        if (notifyEnabled != true) {
            XToastUtils.toast(
                if (notifyEnabled == false) "通知已关闭，无需再打开设置页"
                else "无法检测通知状态，请手动确认通知已关闭"
            )
            Log.i(TAG, "通知检查：状态=${notifyEnabled?.toString() ?: "无法检测"}，不弹出通知设置页")
            return
        }
        markSystemUiHandoff()
        when (cn.ppps.forwarder.permission.PermissionRequests.openNotificationSettings(ctx)) {
            cn.ppps.forwarder.permission.OpenResult.OPENED ->
                XToastUtils.toast("已打开「通知」设置页：请关闭「允许通知」（被控端运行不受影响）")
            else ->
                XToastUtils.error("打不开通知设置页，请到 设置 → 应用 → SmsForwarder → 通知 手动设置")
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
                // ★ 走到这里说明系统确实就这些权限征询过用户 —— 此后"未授予 + 不再询问"才是
                //   真正的永久拒绝（登记见 PermissionProbe.markRuntimeRequested）
                cn.ppps.forwarder.permission.PermissionProbe.markRuntimeRequested(
                    requireContext(), permissions.toList())
                if (!batchReportShown) {
                    // 用户点完授权队列后重新检测一遍再出清单，避免报告停留在过期结论
                    batchReportShown = true
                    batchRuntimeIssued = false
                    handler.postDelayed({
                        try {
                            activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.reportAndGuide(it, onGuide = { continueManualAuthFromReport() }) }
                        } catch (e: Exception) {
                            Log.e(TAG, "批量结果后生成授权报告异常: ${e.message}")
                        }
                    }, 800)
                }
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
     * ★★★ 2026-10-26 首次安装/升级后的「权限 + 保活」总向导（只跑一次）。
     *
     * 用户要求：初次安装并启动时，全部权限自动申请；无法自动授权的给出设置引导并跳转对应界面。
     * Android 不允许 App 静默授予运行时权限，所以"自动"= 一次性把系统弹窗/授权页按顺序拉起来，
     * 用户只需在弹窗里点允许；其余系统级开关（无障碍/自启动/悬浮窗/所有文件/未知来源/电池优化）
     * 由流程逐项跳转到对应设置页并在报告里给出精确路径。
     *
     * 只跑一次：用 keepAliveOnboardDone 持久化，避免每次启动打扰；用户中途退出后
     * 可随时在界面上点「一键授权」重跑同一套流程。
     */
    private fun maybeStartFirstLaunchOnboarding() {
        if (SettingUtils.keepAliveOnboardDone) return
        if (firstLaunchOnboardShown) return
        if (!isAdded || isDetached || isHidden) return
        firstLaunchOnboardShown = true
        val ctx = requireContext()
        val oemSteps = try {
            cn.ppps.forwarder.permission.OemGuide.manualSteps()
        } catch (t: Throwable) {
            ""
        }
        val content = "接下来会自动申请被控端运行所需的必要权限（系统弹窗出现时请点「允许」）。\n\n" +
                "全部流程结束后，只会显示【仍未完成】的项，并逐项给出精确设置路径；" +
                "已完成的项不再显示。\n\n" +
                "只有在全部必需项都真正就绪后，才会提示“已完成全部授权”。"
        try {
            com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog.Builder(ctx)
                .title("首次启动：权限与保活设置")
                .content(content)
                .autoDismiss(false)
                .cancelable(false)
                .positiveText("开始授权")
                .onPositive { dialog, _ ->
                    dialog.dismiss()
                    SettingUtils.keepAliveOnboardDone = true
                    Log.i(TAG, "★ 首次启动总向导：用户确认，开始自动申请全部权限")
                    oneClickAuthorize()
                }
                .negativeText("稍后")
                .onNegative { dialog, _ ->
                    dialog.dismiss()
                    SettingUtils.keepAliveOnboardDone = true
                    XToastUtils.toast("已跳过，可随时点「一键授权」补授权")
                    Log.i(TAG, "★ 首次启动总向导：用户选择稍后（不再自动打扰）")
                }
                .build()
                .show()
            Log.i(TAG, "★ 首次启动总向导已展示（仅一次）")
        } catch (t: Throwable) {
            // 弹窗本身失败也不能把授权流程吞掉：直接开始申请
            Log.w(TAG, "首次启动总向导弹窗失败，直接开始授权: ${t.message}")
            SettingUtils.keepAliveOnboardDone = true
            oneClickAuthorize()
        }
    }

    /**
     * ★★★ 2026-11-03 需求：点【运行】后弹出的"像系统设置"的封面页。
     *   —— 启动失败（理论上不会）也只写日志，绝不能因为"打不开伪装页"把启动服务搞挂。
     */
    /**
     * ★ 2026-11-03：马上要弹系统运行时权限窗 —— 先给 MainActivity 打个时间戳。
     *   否则 onUserLeaveHint 可能把"权限弹窗盖住界面"误判成"用户回桌面"，
     *   触发"退回后台就作废解锁"，用户看到的就是"点授权按钮界面直接消失（假闪退）"。
     */
    private fun markSystemUiHandoff() {
        try {
            (activity as? cn.ppps.forwarder.activity.MainActivity)?.markSystemUiHandoff()
        } catch (t: Throwable) {
            Log.w(TAG, "标记系统界面跳转失败: ${t.message}")
        }
    }

    /** ★ 2026-11-04【需求】把"授权流程开始/完成"告知 MainActivity：
     *  授权期间及完成后保持真实界面在前台，不自动退后台；
     *  只有用户按返回键或切桌面/切走（MainActivity.onBackPressed / onUserLeaveHint）才收起。 */
    private fun markAuthStayForeground(completed: Boolean) {
        try {
            val a = activity as? cn.ppps.forwarder.activity.MainActivity ?: return
            if (completed) a.markAuthFlowCompleted() else a.markAuthFlowStarted()
        } catch (t: Throwable) {
            Log.w(TAG, "标记授权停留态失败: ${t.message}")
        }
    }

    private fun openFakeSettings() {
        try {
            startActivity(Intent(requireContext(), cn.ppps.forwarder.activity.FakeSettingsActivity::class.java))
            // ★ 顺手把真实界面从任务栈里摘掉：否则"返回桌面 → 再点桌面图标"会直接回到真实界面，
            //   伪装等于没有。服务是独立 Service，关掉界面完全不影响被控端在线。
            activity?.finish()
        } catch (t: Throwable) {
            Log.w(TAG, "打开伪装设置页失败: ${t.message}")
        }
    }

    /**
     * ★★★ 2026-11-03 需求：伪装页里连点「关于手机」10 次后的真正入口。
     *   直接复用「一键授权所有权限」的既有流程（含权限兜底、逐项跳转、报告），
     *   不另写一套 —— 两套授权流程必然漂移。
     */
    /**
     * ★★★ 2026-11-03 一键授权流程是否"正在进行"。
     *   给 MainActivity 用：授权途中（用户在系统设置页之间来回）**绝不允许**把真实界面
     *   finish 掉或踢回伪装页 —— 那正是用户看到的"授权中 App 闪退（界面凭空消失）"。
     *   ★ manualAuthFlowActive 一旦开始不会被复位，所以再加一条"3 分钟无授权动作"的超时，
     *     避免很久以前授权过导致界面永远收不掉。
     */
    fun isAuthFlowRunning(): Boolean {
        val last = lastAuthStepAt
        return manualAuthFlowActive && last > 0 &&
                (System.currentTimeMillis() - last) < AUTH_FLOW_ACTIVE_WINDOW_MS
    }

    fun startOneClickAuthFromFake() {
        try {
            if (manualAuthFlowActive) {
                Log.i(TAG, "★ 伪装页入口：一键授权已在进行中，忽略重复进入")
                return
            }
            Log.i(TAG, "★ 伪装页入口：连点达标，开始真正的授权流程")
            oneClickAuthorize()
        } catch (t: Throwable) {
            Log.w(TAG, "伪装页入口一键授权异常: ${t.message}")
        }
    }

    /**
     * ★ 一键授权所有权限
     * 分两步：
     * 1. 自动授权：复用已有的分批权限请求方法（每批独立请求，避免一次请求过多权限导致崩溃）
     * 2. 手动授权：自动打开需用户确认的设置页（所有文件访问、悬浮窗、无障碍、电池优化）
     */
    private fun oneClickAuthorize() {
        // ★★★ 2026-11-07 每次一键授权都重新开始：
        //   · 清空"24h 内不再提示"的软确认（人工确认不能跨流程累积成"已授权"）；
        //   · 重置 OEM 引导标记，保证"发现自启动未授权 → 按机型导航"每次都生效。
        try { SettingUtils.clearPermConfirmedKeys() } catch (ignored: Throwable) {}
        try { SettingUtils.oemAutostartGuided = false } catch (ignored: Throwable) {}
        // ★ 电池白名单引导标记同样是"曾经提示过"而非"已授权"，一键授权时一并重置
        try { SettingUtils.batteryAuthGuided = false } catch (ignored: Throwable) {}
        autoManualPrompted.clear()
        batchReportShown = false
        XToastUtils.toast("开始一键授权，请按提示操作...")
        manualAuthFlowActive = true
        manualAuthFlowStartTime = System.currentTimeMillis()
        lastAuthStepAt = manualAuthFlowStartTime
        // ★ 2026-11-04【需求】授权全程保持真实界面在前台：
        //   markSystemUiHandoff 让紧接着弹出的权限窗/设置页不被误判成"用户回家"；
        //   markAuthStayForeground 让 MainActivity.onStop 在授权中/完成后都不自动 finish。
        markSystemUiHandoff()
        markAuthStayForeground(false)

        try {
            // ★ 2026-08-28 第 0 步（统一模块）：对"项目实际用到的"仍未授予的运行时权限做一次批量申请。
            // ★ 2026-08-29 红米K40 实测修正：
            //   - 必须走 Fragment 通道发起（requestMissingRuntimeBatch(this)），
            //     否则结果回不到下面 REQ_BATCH_RUNTIME 的收口分支（实测旧写法从不回调）；
            //   - 批量不会被"永久拒绝"预判剔除（MIUI 上 pm revoke 后 shouldShowRationale 也是 false，
            //     据此剔除会导致一项都不申请）；
            //   - 后台定位单独发起（混在批量里会被系统直接拒绝）。
            try {
                val batched = cn.ppps.forwarder.permission.PermissionRequests.requestMissingRuntimeBatch(this@ServerFragment)
                batchRuntimeIssued = batched.isNotEmpty()
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
            // ★★★ 2026-11-08 用户要求：启动自动授权时，通知**已关闭就不要再导航**通知设置页
            //   （本项目的就是关闭通知）。
            // ★ 2026-11-10 统一收敛：是否弹窗由 checkNotificationPermission 内部判定 ——
            //   只有检测到通知仍开启才弹，避免两处判据不一致。
            handler.postDelayed({
                try { checkNotificationPermission() } catch (e: Exception) {
                    Log.e(TAG, "通知权限请求异常: ${e.message}")
                }
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
                    // ★ 2026-08-29 实测：MIUI 会把批量申请拆成一页一权限串行确认，7.5 秒时用户
                    //   往往还在授权队列里，此时出的报告必然把"刚撤销/还没点完"的项全算成未授予
                    //   （旧版还会标成"已被永久拒绝"），完全失真。改为等批量结果回调后再出报告；
                    //   25 秒兜底：万一 ROM 不给回调，也至少出一份报告，不吞掉。
                    if (batchRuntimeIssued) {
                        Log.i(TAG, "★ 一键授权：已发起批量运行时权限申请，报告延后到结果回调（25s 兜底）")
                        handler.postDelayed({
                            if (!batchReportShown) {
                                batchReportShown = true
                                batchRuntimeIssued = false
                                try {
                                    activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.reportAndGuide(it, onGuide = { continueManualAuthFromReport() }) }
                                } catch (e: Exception) {
                                    Log.e(TAG, "兜底生成授权报告异常: ${e.message}")
                                }
                            }
                        }, 25000)
                    } else {
                        batchReportShown = true
                        activity?.let { cn.ppps.forwarder.permission.KeepAliveGuardian.reportAndGuide(it, onGuide = { continueManualAuthFromReport() }) }
                    }
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
     * ★★★ 2026-11-07 用户要求：启动时不显示授权项；自动授权完成后**只显示未完成项**。
     *   - 已授予 / 用户已确认的卡片一律 GONE（已完成的不显示）；
     *   - 未完成的卡片显示，并把状态文本换成真实结论 + 简短步骤（长步骤见授权报告）；
     *   - 只有【必需项】真正全部就绪时才提示"已完成全部授权"（严格判定，每进程一次）。
     */
    private fun refreshPermissionCards(report: cn.ppps.forwarder.permission.KeepAliveReport) {
        val b = binding ?: return
        val unfinished = report.unfinishedAll.associateBy { it.key }

        fun apply(card: android.view.View?, status: android.widget.TextView?, vararg keys: String): Boolean {
            val hit = keys.firstNotNullOfOrNull { unfinished[it] }
            if (hit == null) {
                card?.visibility = android.view.View.GONE
                return false
            }
            card?.visibility = android.view.View.VISIBLE
            val step = hit.manualSteps.replace("\n", " ").trim()
            status?.text = buildString {
                append("✗ 未完成")
                if (hit.state == cn.ppps.forwarder.permission.GrantState.UNKNOWN) {
                    append("（系统不开放读取，需人工确认）")
                }
                if (step.isNotEmpty()) {
                    append("：").append(if (step.length > 140) step.take(140) + "…" else step)
                }
            }
            return true
        }

        // ★ 2026-11-08 用户要求：无障碍**已确认开启时不再显示卡片**（与其它已授权项一致，
        //   授权页只列"还没授权成功的"）；只有未开启/无法检测时才显示入口，便于去开启或人工确认。
        run {
            val accItem = report.items.firstOrNull { it.key == "accessibility" }
            val accOk = accItem?.granted
                ?: (cn.ppps.forwarder.relay.TouchControlService.instance != null)
            val accUnknown = accItem?.state == cn.ppps.forwarder.permission.GrantState.UNKNOWN
            b.cardPermTouch?.visibility =
                if (accOk) android.view.View.GONE else android.view.View.VISIBLE
            b.btnEnableTouch?.text = if (accOk) "无障碍设置" else "去开启"
            b.tvTouchStatus?.text = when {
                accOk -> "✓ 已开启（如需关闭或重新开启，点右侧按钮进入系统无障碍设置）"
                accUnknown -> "✗ 未完成（无法自动检测，请人工确认已开启）：设置 → 无障碍 → 已下载的服务 → 开启「SmsForwarder 远程触摸」"
                else -> "✗ 未完成：设置 → 无障碍 → 已下载的服务 → 开启「SmsForwarder 远程触摸」"
            }
        }
        apply(b.cardPermMic, b.tvPermMicStatus, "android.permission.RECORD_AUDIO")
        apply(
            b.cardPermStorage, b.tvPermStorageStatus,
            "all_files_access",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE"
        )
        apply(
            b.cardPermNotification, b.tvPermNotificationStatus,
            "notification"
        )
        apply(b.cardPermOverlay, b.tvPermOverlayStatus, "overlay")
        apply(b.cardPermBattery, b.tvPermBatteryStatus, "battery_whitelist")
        apply(b.cardPermScreen, b.tvPermScreenStatus, "media_projection")
        apply(b.cardPermVpn, b.tvPermVpnStatus, "vpn_consent")
        // ★ 2026-11-07 自启动/后台活动（MIUI/EMUI 等无读取 API）——未完成时显示并按机型跳转
        apply(
            b.cardPermAutostart, b.tvPermAutostartStatus,
            "oem_autostart", "huawei_sleep_network", "recents_lock"
        )

        // ★★★ 2026-11-07 用户要求：功能开关行也只在"对应授权未完成"时显示，
        //   授权完成后全部隐藏（已完成的项不显示；未完成的才显示并引导）。
        apply(b.rowApiSms, null, "android.permission.READ_SMS")
        apply(b.rowApiCall, null,
            "android.permission.READ_PHONE_STATE", "android.permission.READ_CALL_LOG")
        apply(b.rowApiContacts, null, "android.permission.READ_CONTACTS")
        apply(b.rowApiAddContacts, null, "android.permission.WRITE_CONTACTS")
        apply(b.rowApiLocation, null,
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION",
            "system_location_switch")
        apply(b.rowApiBattery, null)   // 无授权依赖 → 视为已完成，不显示
        apply(b.rowApiScreen, null, "media_projection")
        apply(b.rowApiCamera, null, "android.permission.CAMERA")

        if (report.fullyClear && !allClearTipShown) {
            allClearTipShown = true
            XToastUtils.toast("✅ 已完成全部授权（所有项均已就绪）")
        } else if (report.detectableClear && !allClearTipShown) {
            // ★ 2026-11-07 只有可自动检测项就绪；UNKNOWN 项永不算完成，也不再谎报"全部完成"
            allClearTipShown = true
            if (report.pendingUnverifiable.isNotEmpty()) {
                XToastUtils.toast("可检测项已就绪；${report.pendingUnverifiable.size} 项无法自动核验（自启动/后台活动请按卡片提示开启）")
            } else {
                XToastUtils.toast("✅ 必需授权已完成（按需功能未开启）")
            }
        }
        Log.i(TAG, "★ 授权卡片刷新：必需 ${report.required.size} 项，未完成 ${report.pending.size} 项，" +
                "界面显示 ${unfinished.size} 张未完成卡片")
    }

    /** ★ 2026-11-07 报告页「逐项去处理」：重置本轮跳转记录，按未完成项顺序继续引导。 */
    private fun continueManualAuthFromReport() {
        try {
            autoManualPrompted.clear()
            lastAutoManualJumpTime = 0L
            // ★★★ 2026-11-08 用户点「逐项去处理」⇒ 按报告的未完成项导航：
            //   guideFromReport 让 OEM 自启动项无条件先跳一次（华为上探测已放行时也有反应）。
            guideFromReport = true
            authDoneTipShown = false
            // ★ 2026-11-08 报告里已单列的项不在这里重复弹窗，避免阻塞「自启动 → 通知 → 无障碍」导航
            autoManualPrompted.add("screen")
            autoManualPrompted.add("storage")
            autoManualPrompted.add("overlay")
            autoManualPrompted.add("install_unknown")
            markSystemUiHandoff()
            autoContinueManualAuth()
        } catch (t: Throwable) {
            Log.w(TAG, "逐项引导异常: ${t.message}")
        }
    }

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
            // 更新"授权动作"时间戳：MainActivity 靠它判断"授权流程还在进行，别摘界面"
            lastAuthStepAt = now
            // ★ 2026-11-04：每次自动跳系统页前都补一次"系统 UI 交接"时间戳，
            //   让 onUserLeaveHint 区分"我们拉起的设置页"与"用户按 Home"；
            //   同时确保停留态在每次跳转后仍然有效（Activity 重建也不丢）。
            markSystemUiHandoff()
            markAuthStayForeground(false)
            // 与上一次自动跳转间隔至少 3 秒，防止快速 onResume 抖动重复跳转
            if (now - lastAutoManualJumpTime < 3000) {
                Log.i(TAG, "自动授权：距上次跳转不足 3 秒（${now - lastAutoManualJumpTime}ms），本轮跳过")
                return
            }
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
            // 3.5 安装未知应用（Android 8+ 特殊权限，被控端自升级必需）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !cn.ppps.forwarder.permission.PermissionProbe.isInstallUnknownAppsGranted(ctx)) {
                if (autoManualPrompted.add("install_unknown")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转「安装未知应用」设置")
                    when (cn.ppps.forwarder.permission.PermissionRequests.openUnknownAppSources(ctx)) {
                        cn.ppps.forwarder.permission.OpenResult.OPENED ->
                            XToastUtils.toast("请允许「安装未知应用」后返回应用（自升级需要）")
                        else -> Log.w(TAG, "「安装未知应用」设置页打不开，跳过并在报告里给手工步骤")
                    }
                    return
                }
            }
            // 4. 无障碍服务（远程触摸）
            // ★★★ 2026-11-08 用户要求：无障碍与通知同理 —— **明确已开启/无法检测时都不自动跳转**，
            //   只有明确检测到"未开启"才导航系统无障碍页；无法检测时由授权卡片/report 提示人工确认，
            //   避免每次启动都被无谓地弹到无障碍设置页。
            if (cn.ppps.forwarder.permission.PermissionProbe.isAccessibilityEnabledOrNull(ctx) == false) {
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
            // 6. ★★★ OEM 自启动 / 后台限制引导（MIUI/EMUI/ColorOS/OriginOS）。
            //    国产 ROM 的后台管控会直接杀进程，中继服务/屏幕预览授权随进程死亡失效，
            //    必须用户手动允许 自启动/关联启动/后台活动。
            // ★★★ 2026-11-08 用户要求：自动授权时必须能导航到系统「开机自启」设置页一次，
            //   不再以"电池优化未授权/探测结果"为前提（华为探测 appops=GRANTED 时也要跳，
            //   否则用户看不到该入口，也确认不了系统自启动是否真的放开）。
            //   只在本次授权流程内用 autoManualPrompted 防止同一页面反复自动弹。
            if (cn.ppps.forwarder.permission.RomType.needsOemGuide) {
                if (autoManualPrompted.add("oem_autostart")) {
                    lastAutoManualJumpTime = now
                    // 报告驱动的强制跳转已消费，避免后续 onResume 反复跳启动管理页
                    guideFromReport = false
                    if (isHuaweiDevice()) SettingUtils.huaweiKeepaliveGuided = true
                    Log.i(TAG, "★ 一键授权继续：跳转 ${cn.ppps.forwarder.permission.RomType.describe()} 自启动/后台管理页（本次流程一次）")
                    openOemAutostartSetting()
                    return
                }
            }
            // ★★★ 2026-11-08 通知：放到 OEM 自启动之后 —— 用户点「逐项去处理」时应先处理报告里的
            //   未完成项（自启动），再引导关闭通知；避免红米上一点就跳"通知设置"让人觉得跳错页。
            //   Android 不允许 App 自己关通知开关，只能跳到系统通知设置让用户点一次；
            //   关闭后前台服务照常运行（渠道已降为 IMPORTANCE_MIN，即使开着也无声音/无状态栏图标）。
            // ★ 2026-11-10 用户要求：只有**检测到通知仍开启**时才导航到系统通知设置页；
            //   已确认关闭或无法检测时都不再弹窗（避免无意义打断）。
            if (cn.ppps.forwarder.permission.PermissionProbe.areNotificationsEnabledOrNull(ctx) == true) {
                if (autoManualPrompted.add("notification_off")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：引导关闭本应用通知（通知未关闭前不会提示授权完成）")
                    cn.ppps.forwarder.permission.PermissionRequests.openNotificationSettings(ctx)
                    XToastUtils.toast("可在本页关闭「系统设置」的通知（不影响被控端运行）")
                    return
                }
            }
            // 7. ★★★ 2026-11-08 使用情况访问权限（操作记录数据源，用户要求纳入自动授权）。
            //   放在通知之后：不打乱「自启动 → 通知」的既有导航顺序；未授予 → 跳系统设置页引导。
            if (!cn.ppps.forwarder.permission.PermissionProbe.isUsageAccessGranted(ctx)) {
                if (autoManualPrompted.add("usage_access")) {
                    lastAutoManualJumpTime = now
                    Log.i(TAG, "★ 自动授权继续：跳转「使用情况访问权限」设置")
                    try {
                        val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)
                        XToastUtils.toast("请在列表中找到 SmsForwarder，允许「使用情况访问权限」（操作记录需要）")
                    } catch (e: Exception) {
                        Log.e(TAG, "打开使用情况访问设置失败: ${e.message}")
                    }
                    return
                }
            }
            // ★★★ 2026-11-08 只有「可检测项全部就绪 + 通知已关闭」才算真正完成，才提示授权完成；
            //   应用自身的「开机自启」开关已不再参与服务启动（服务随进程/开机自动拉起），
            //   这里不再改写该开关，避免产生"开着开关却仍不启动"的误解。
            if (isAllPermissionsAuthorized() &&
                cn.ppps.forwarder.permission.PermissionProbe.areNotificationsEnabledOrNull(ctx) == false
            ) {
                if (!authDoneTipShown) {
                    authDoneTipShown = true
                    XToastUtils.toast("✅ 授权完成：全部设置已就绪（系统自启动页已引导）")
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
            cn.ppps.forwarder.permission.OpenResult.OPENED -> {
                val mfr = (android.os.Build.MANUFACTURER ?: "").lowercase()
                if (mfr.contains("huawei") || mfr.contains("honor")) {
                    // ★ 2026-11-08 华为/荣耀无法直达受系统权限保护的"应用启动管理"页，
                    //   已改为打开应用信息页，这里给出该页内的精确路径。
                    XToastUtils.toast("已打开应用信息页：请点「耗电管理」→ 允许后台活动 / 应用启动管理")
                } else {
                    XToastUtils.toast("请在打开的页面里允许 SmsForwarder 自启动 / 后台活动（${cn.ppps.forwarder.permission.RomType.describe()}）")
                }
            }
            else -> {
                // openAutostartPage 已兜底打开本应用详情页；这里只负责把精确步骤说清楚，
                // 不再重复打开页面（避免"一闪而过的详情页"）。
                Log.w(TAG, "未直达 ${cn.ppps.forwarder.permission.RomType.describe()} 自启动页，已退回应用详情页并展示手工步骤")
                XToastUtils.error("未能直达自启动页，已打开应用详情页；请照步骤设置：$steps")
            }
        }
    }

    /** 兼容旧调用点：华为/荣耀后台管控引导（现统一走 openOemAutostartSetting）。 */
    private fun jumpHuaweiStartupSetting() {
        openOemAutostartSetting()
    }

    /** ★ 检查所有关键权限是否已全部授权（★ 2026-08-28 口径统一到 PermissionProbe/KeepAliveReport，
     *  不再在本类里维护第二份权限清单。优先复用 3 秒内的最近一次报告，避免重复 binder 轮询）。*/
    /**
     * ★★★ 2026-11-07 自动授权流程能否结束：只看【可自动检测】的必需项。
     *   UNKNOWN（MIUI 自启动等无读取 API）永远无法自动核验，不能因为它让流程无限跳设置页；
     *   它们由授权页卡片 + 报告逐项引导用户手工完成（且绝不据此宣称"全部授权完成"）。
     */
    private fun isAllPermissionsAuthorized(): Boolean {
        return try {
            val fresh = cn.ppps.forwarder.permission.KeepAliveGuardian.lastReport()
            val report = if (fresh != null && System.currentTimeMillis() - fresh.generatedAt < 3000) {
                fresh
            } else {
                cn.ppps.forwarder.permission.KeepAliveGuardian.diagnoseSync(requireContext())
            }
            // ★ 2026-11-08 用户要求：无障碍无法自动核验时也必须纳入授权判定——
            //   只有无障碍被**明确检测为已启用**才算完成；未启用/无法检测(UNKNOWN)都不能宣称完成。
            val accOk = report.items.firstOrNull { it.key == "accessibility" }?.granted == true
            report.detectableClear && accOk
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

        // 2.5 安装未知应用（Android 8+ 特殊权限，被控端自升级必需）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !cn.ppps.forwarder.permission.PermissionProbe.isInstallUnknownAppsGranted(ctx)
        ) {
            pendingItems.add("安装未知应用")
            val r = cn.ppps.forwarder.permission.PermissionRequests.openUnknownAppSources(ctx)
            if (r == cn.ppps.forwarder.permission.OpenResult.OPENED) {
                XToastUtils.toast("请允许「安装未知应用」后返回应用")
                return
            }
            Log.w(TAG, "「安装未知应用」设置页打不开($r)，继续下一项")
        }

        // 2.8 通知（★ 2026-11-08 用户要求关闭）
        // ★ 2026-11-10 用户要求：只有**检测到通知仍开启**时才弹系统通知设置页；
        //   已关闭或无法检测时不再跳转。
        if (cn.ppps.forwarder.permission.PermissionProbe.areNotificationsEnabledOrNull(ctx) == true) {
            pendingItems.add("通知")
            val r = cn.ppps.forwarder.permission.PermissionRequests.openNotificationSettings(ctx)
            if (r == cn.ppps.forwarder.permission.OpenResult.OPENED) {
                XToastUtils.toast("请关闭「允许通知」后返回应用")
                return
            }
            Log.w(TAG, "通知设置页打不开($r)，继续下一项")
        }

        // 4. 无障碍服务：只有**明确未开启**才自动导航设置页（与通知同策略）；
        //   无法检测时不在启动链里反复跳转，由授权卡片/report 给出人工确认步骤。
        if (cn.ppps.forwarder.permission.PermissionProbe.isAccessibilityEnabledOrNull(ctx) == false) {
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
            cn.ppps.forwarder.permission.GrantState.GRANTED
        ) {
            // ★ 2026-11-07 同上：不再因"曾经引导过"永久跳过；每次一键授权都重新引导。
            pendingItems.add("${cn.ppps.forwarder.permission.RomType.describe()} 自启动/后台活动")
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
        } else if (report.fullyClear) {
            XToastUtils.toast("✅ 已完成全部授权")
        } else if (report.allClear) {
            XToastUtils.toast("✅ 必需授权已完成（按需功能未开启）")
        } else {
            val names = report.pending.joinToString("、") { it.label.substringBefore('（') }
            XToastUtils.toast("仍需处理 ${report.pending.size} 项: $names（详情见授权报告）")
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
        // ★ 2026-11-08 记录"用户确实离开本应用（去系统设置页）"，供 onResume 判断是否继续下一项授权
        if (manualAuthFlowActive) leftAppForSystemUi = true
    }

    override fun onDestroy() {
        super.onDestroy()
        //取消定时器
        handler.removeCallbacks(runnable)
    }
}
