package cn.ppps.forwarder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import androidx.core.content.ContextCompat
import androidx.multidex.MultiDex
import androidx.work.Configuration
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.entity.SimInfo
import cn.ppps.forwarder.service.LocationService
import cn.ppps.forwarder.service.RelayServerService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.SharedPreference
import cn.ppps.forwarder.utils.sdkinit.XBasicLibInit
import com.hjq.language.MultiLanguages
import com.hjq.language.OnLanguageListener
import com.king.location.LocationClient
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("DEPRECATION")
class App : Application(), Configuration.Provider by Core {

    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())

    companion object {
        const val TAG: String = "SmsForwarder"

        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context

        //已插入SIM卡信息
        // ★ 修复：该字段在 relay 命令线程池上被读取与整体替换，原 mutableMapOf() 返回非线程安全的
        //   LinkedHashMap，并发迭代/替换会触发 ConcurrentModificationException；改用 ConcurrentHashMap
        //   （其迭代器弱一致，替换引用时旧实例仍可安全遍历）。
        var SimInfoList: MutableMap<Int, SimInfo> = java.util.concurrent.ConcurrentHashMap()

        /**
         * @return 当前app是否是调试开发模式
         */
        var isDebug: Boolean = BuildConfig.DEBUG

        //Location相关
        val LocationClient by lazy { LocationClient(context) }
        val Geocoder by lazy { Geocoder(context) }
        val DateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

        //是否需要在拼接字符串时添加空格
        var isNeedSpaceBetweenWords = false
    }

    override fun attachBaseContext(base: Context) {
        // 绑定语种
        super.attachBaseContext(MultiLanguages.attach(base))
        //解决4.x运行崩溃的问题
        MultiDex.install(this)
    }

    /**
     * ★ 2026-11-10【权限误报修复】一次性纠正历史误关的远程查询开关。
     * 背景：旧版 checkReadSmsPermission / checkLocationPermission 在系统权限弹窗被拒时，
     * 会把 enable_api_sms_query / enable_api_location 落盘为 false；用户事后在系统设置里补授权后，
     * 控制端仍永久提示"被控端已关闭该功能"，形成"权限已授权却提示禁用"的误报。
     * 这里仅在开关为 false 且权限确实已授予时恢复为 true，并只执行一次（标记落盘），
     * 不影响用户之后主动关闭开关。
     */
    private fun migrateRemoteQuerySwitchesIfNeeded() {
        try {
            val sp = createDeviceProtectedStorageContext()
                .getSharedPreferences("permission_misreport_fix", Context.MODE_PRIVATE)
            fun granted(permission: String): Boolean =
                try {
                    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) {
                    false
                }

            if (!sp.getBoolean("v1_done", false)) {
                if (granted(Manifest.permission.READ_SMS) && !HttpServerUtils.enableApiSmsQuery) {
                    HttpServerUtils.enableApiSmsQuery = true
                    Log.i(TAG, "[权限误报修复] READ_SMS 已授予，恢复「远程短信查询」开关为开启")
                }
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && !HttpServerUtils.enableApiLocation) {
                    HttpServerUtils.enableApiLocation = true
                    Log.i(TAG, "[权限误报修复] 定位权限已授予，恢复「远程定位」开关为开启")
                }
                sp.edit().putBoolean("v1_done", true).apply()
            }

            // ★ 2026-11-10 扩展：通话/联系人/相机 同样存在“弹窗被拒即永久落盘 false”的历史误关，
            //   权限已授予而开关仍为 false 时一次性恢复。用 v2 标记，保证已跑过 v1 的老设备也会执行。
            if (!sp.getBoolean("v2_done", false)) {
                if (granted(Manifest.permission.READ_CALL_LOG) && !HttpServerUtils.enableApiCallQuery) {
                    HttpServerUtils.enableApiCallQuery = true
                    Log.i(TAG, "[权限误报修复] READ_CALL_LOG 已授予，恢复「远程查通话」开关为开启")
                }
                if (granted(Manifest.permission.READ_CONTACTS) && !HttpServerUtils.enableApiContactQuery) {
                    HttpServerUtils.enableApiContactQuery = true
                    Log.i(TAG, "[权限误报修复] READ_CONTACTS 已授予，恢复「远程查联系人」开关为开启")
                }
                if (granted(Manifest.permission.WRITE_CONTACTS) && !HttpServerUtils.enableApiContactAdd) {
                    HttpServerUtils.enableApiContactAdd = true
                    Log.i(TAG, "[权限误报修复] WRITE_CONTACTS 已授予，恢复「远程加联系人」开关为开启")
                }
                if (granted(Manifest.permission.CAMERA) && !HttpServerUtils.enableApiCamera) {
                    HttpServerUtils.enableApiCamera = true
                    Log.i(TAG, "[权限误报修复] CAMERA 已授予，恢复「远程摄像头」开关为开启")
                }
                sp.edit().putBoolean("v2_done", true).apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[权限误报修复] 开关迁移失败: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 设置全局异常捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            throwable.printStackTrace()
            try {
                val logPath = this.cacheDir.absolutePath + "/logs"
                val logDir = File(logPath)
                if (!logDir.exists()) logDir.mkdirs()
                val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                val currentDateTime = dateFormat.format(Date())
                val logFile = File(logPath, "crash_$currentDateTime.txt")
                BufferedWriter(FileWriter(logFile, true)).use { writer ->
                    writer.append("$throwable\n")
                }
            } catch (ex: IOException) {
                ex.printStackTrace()
            }
            //使用默认的处理方式让APP停止运行
            defaultHandler?.uncaughtException(thread, throwable)
        }

        try {
            context = applicationContext
            initLibs()
            // ★ 2026-11-10【权限误报修复】纠正旧版因权限弹窗被拒而误关的短信/定位开关
            migrateRemoteQuerySwitchesIfNeeded()

            // ★ 2026-08-16 Tailscale 集成：启动 VPN 后端 + authkey 无 UI 登录
            cn.ppps.forwarder.tailscale.TailscaleManager.ensureStarted(this)

            // ★★★ 2026-09-23 与同机手机控制端联动（用户硬性要求）：
            //   "短信转发器也要和手机控制端的逻辑一样：中继开→VPN关闭，中继关→VPN开启；
            //   首次启动检测中继状态；状态变更即切换；优先被动响应（不要定时检测开销）"。
            //   实现方式【纯被动】：手机控制端（同机的远程控制App）在它的
            //   TailscaleManager.setRelayConnected（中继状态汇聚点：用户偏好+relayst云广播+心跳检测）
            //   处广播本动作（状态变更立即发；未变化每30秒补发一次，覆盖本App后启动的首次状态获取）。
            //   本接收器收到后调用 TailscaleManager.setRelayConnected（已有实现：
            //   中继开→stopVpn关VPN；中继关→ensureVpnUp开VPN），无任何轮询/探测开销。
            //   为什么不由本App自己探测云服务器：① 云服务器没有手机被控端注册端口
            //   （本App连中继56786一直ECONNREFUSED）；② 探测"服务可达"分不清用户是否偏好直连。
            //   唯有同机控制端知道权威状态（含用户开关偏好），由它推送最准确且零开销。
            val relayStateReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    try {
                        // ★★★ 2026-10-01 防误发/防串扰：本广播只应由【同机手机控制端】发出。
                        //   旧实现用 getBooleanExtra("relay_on", false) 的**默认值 false**，
                        //   于是任何 App 只要广播这个 action（不带 extra）就会被当成"中继关闭"，
                        //   把本机强行切到直连/开 VPN（2026-10-01 新增的粘性写入会让它更持久）。
                        //   控制端已用 setPackage 限制目标包，但广播【发送方】身份在接收侧无法直接
                        //   校验，这里做两道最小校验；★ 不用签名/令牌校验，避免与控制端 APK 版本
                        //   强耦合——老版本控制端没带令牌就会让整条联动静默失效。
                        //   ① 必须显式带 relay_on（我们的控制端一定会带）；
                        //   ② 若广播带了包名，必须是本包。
                        if (!intent.hasExtra("relay_on")) {
                            Log.w(TAG, "忽略缺少 relay_on 的中继状态广播（疑似误发/伪造）: action="
                                    + intent.action)
                            return
                        }
                        val pkg = intent.`package`
                        if (pkg != null && pkg != packageName) {
                            Log.w(TAG, "忽略非本包的中继状态广播（疑似串扰）: pkg=$pkg")
                            return
                        }
                        val on = intent.getBooleanExtra("relay_on", false)
                        // ★★★ 2026-09-26 修复"华为从中继切直连后连不上红米被控端"（实证根因）：
                        //   中继总闸是**全局**的，任一台手机把总闸关掉会物理断开所有手机的数据通道。
                        //   此时同机控制端若仍处在"中继模式"，它每 30 秒补发的 relay_on=true 会在
                        //   服务器 relayst=off 之后把状态又翻回"中继开"，把本机 VPN 关掉 →
                        //   已切到直连模式的那台控制端永远连不上本机（红米实测无 tun、TS 直连全超时）。
                        //   故服务器明确说 off 的权威窗口内，同机广播一律忽略，以服务器总闸为准。
                        // ★★★ 2026-10-01【升级为双向权威】用户硬性要求："一处控制端切换中继开关，
                        //   所有控制端同步；被控端按切换后的总闸状态决定是否开 VPN" → 总闸是唯一权威，
                        //   窗口内同机广播**不得与服务器值相反**。旧实现只拦 off 方向（且注释写"on 时
                        //   恢复以同机控制端为准"），于是总闸=on 时同机那条每 30s 补发的 relay_on=false
                        //   能把刚关掉的 VPN 又打开（establish 还会打断被控端到中继的 TCP）。
                        if (cn.ppps.forwarder.tailscale.TailscaleManager.isServerRelayStateFresh()
                                && on != cn.ppps.forwarder.tailscale.TailscaleManager.serverRelayStateValue) {
                            Log.i(TAG, "★ 服务器总闸["
                                    + (if (cn.ppps.forwarder.tailscale.TailscaleManager.serverRelayStateValue) "开" else "关")
                                    + "]仍在权威窗口内 → 忽略同机控制端的相反广播["
                                    + (if (on) "开" else "关") + "]（用户要求：总闸为唯一权威）")
                            return
                        }
                        Log.i(TAG, "★ 收到同机控制端中继状态广播: "
                                + (if (on) "中继开启→关闭VPN" else "中继关闭→开启VPN"))
                        // ★ 记录权威状态窗口：180秒内忽略本机中继client的传输层信号
                        //   （连上→关VPN / 断连→开VPN），防止与权威状态互相打架
                        cn.ppps.forwarder.tailscale.TailscaleManager.externalRelayStateUntil =
                            System.currentTimeMillis() + 180_000L
                        // ★★★ 2026-10-01 修复"中继→直连后近 1 分钟才开 VPN"：
                        //   同机控制端的广播同样是【权威状态值】（与服务器 relayst 同级，见 TailscaleManager 注释），
                        //   必须同时更新粘性判据。旧实现只写窗口、不写值 → 收到"中继关闭"时，
                        //   闸门 externalRelayStateOn 仍为 true（更早的中继态）→ setRelayConnected(false)
                        //   被闸门拦下、不建 VPN，只能等服务器 relayst=off + 看门狗退避（最坏约 60s）才起来。
                        cn.ppps.forwarder.tailscale.TailscaleManager.externalRelayStateOn = on
                        // ★★★ 2026-11-08【权威状态持久化】同机广播同样是用户开关的权威值，落盘供进程重启后沿用
                        cn.ppps.forwarder.utils.RelaySettings.rememberAuthorityRelayState(on, "同机控制端广播")
                        // ★ 权威状态变化 → 复位看门狗退避，使建立失败后也能在 10s 内快速重试
                        cn.ppps.forwarder.tailscale.TailscaleManager.resetVpnWatchdogBackoff()
                        cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(
                            applicationContext, on)
                    } catch (t: Throwable) {
                        Log.e(TAG, "处理中继状态广播异常: $t")
                    }
                }
            }
            val relayStateFilter = android.content.IntentFilter(
                "cn.ppps.forwarder.RELAY_STATE_CHANGED")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // 接收来自另一App（手机控制端）的显式包名广播 → 必须声明 RECEIVER_EXPORTED
                registerReceiver(relayStateReceiver, relayStateFilter,
                    Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(relayStateReceiver, relayStateFilter)
            }
            Log.i(TAG, "★ 已注册中继状态联动接收器（中继开→关VPN / 中继关→开VPN，被动响应）")

            // ★★★ 2026-11-08【服务自恢复：修复覆盖安装后直连永久不可用】
            //   真机实证：双机 relay_enable_server_autorun=false，且 Manifest 没有 MY_PACKAGE_REPLACED；
            //   今日 11 轮覆盖安装每次都杀进程，服务死后无人拉起（dumpsys 无 RelayServerService），
            //   于是 :56786 无监听 → 控制端直连全部 RST。模式互斥逻辑根本没机会执行。
            //   RelayServerService 是中继命令通道（relayst 开关状态同步）与直连监听的唯一载体，
            //   因此进程启动即拉起；仅当用户在图里主动点过「停止服务」才不自动拉起。
            //   「开机自启」开关语义保留：它只决定手机重启后是否由 BootCompletedReceiver 拉起进程。
            if (RelaySettings.userStoppedServer) {
                Log.i(TAG, "★ 用户曾主动停止被控端服务 → 本次进程启动不自动拉起（可在界面点「运行」恢复）")
            } else {
                Log.i(TAG, "★ 进程启动 → 自动拉起被控端服务（修复覆盖安装后服务不运行）")
                RelayServerService.start(this)
            }

            //启动定位服务
            if (SettingUtils.enableLocation) {
                val locationServiceIntent = Intent(this, LocationService::class.java)
                locationServiceIntent.action = ACTION_START
                startService(locationServiceIntent)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "onCreate: $e")
        }
    }

    /**
     * 初始化基础库
     */
    private fun initLibs() {
        Core.init(this)
        // 配置文件初始化
        SharedPreference.init(applicationContext)
        // X系列基础库初始化
        XBasicLibInit.init(this)
        // 初始化日志打印
        isDebug = SettingUtils.enableDebugMode
        Log.init(applicationContext)
        // 初始化语种切换框架
        MultiLanguages.init(this)
        // 设置语种变化监听器
        MultiLanguages.setOnLanguageListener(object : OnLanguageListener {
            override fun onAppLocaleChange(oldLocale: Locale, newLocale: Locale) {
                Log.i(TAG, "监听到应用切换了语种，旧语种：$oldLocale，新语种：$newLocale")
            }

            override fun onSystemLocaleChange(oldLocale: Locale, newLocale: Locale) {
                Log.i(TAG, "监听到系统切换了语种，旧语种：$oldLocale，新语种：$newLocale")
            }
        })
    }

}
