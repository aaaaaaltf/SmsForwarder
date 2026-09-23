package cn.ppps.forwarder

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import androidx.multidex.MultiDex
import androidx.work.Configuration
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.entity.SimInfo
import cn.ppps.forwarder.service.LocationService
import cn.ppps.forwarder.service.RelayServerService
import cn.ppps.forwarder.utils.ACTION_START
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
                        val on = intent.getBooleanExtra("relay_on", false)
                        Log.i(TAG, "★ 收到同机控制端中继状态广播: "
                                + (if (on) "中继开启→关闭VPN" else "中继关闭→开启VPN"))
                        // ★ 记录权威状态窗口：180秒内忽略本机中继client的传输层信号
                        //   （连上→关VPN / 断连→开VPN），防止与权威状态互相打架
                        cn.ppps.forwarder.tailscale.TailscaleManager.externalRelayStateUntil =
                            System.currentTimeMillis() + 180_000L
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

            //启动被控端中继服务（开机自启）
            if (RelaySettings.enableServerAutorun) {
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
