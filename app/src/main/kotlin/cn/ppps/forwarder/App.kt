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
