package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.utils.ACTION_RESTART
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.LocationUtils
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SettingUtils
import com.king.location.LocationErrorCode
import com.king.location.OnExceptionListener
import com.king.location.OnLocationListener
import com.xuexiang.xaop.util.PermissionUtils
import java.util.Date

@SuppressLint("SimpleDateFormat")
@Suppress("PrivatePropertyName", "DEPRECATION")
class LocationService : Service() {

    private val TAG: String = LocationService::class.java.simpleName

    /**
     * ★ 2026-08-28 省电：触发一次地理编码反查的最小位移（度）。
     *   0.0003° ≈ 30 米，远小于"街道级地址"会变化的尺度，所以复用上次地址不会带来任何可见差异；
     *   目的只是把静止时每次定位（默认 10 秒一次）都发一次同步网络反查的浪费去掉。
     */
    private val GEOCODE_MOVE_DEG = 0.0003

    private val locationStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                handleLocationStatusChanged()
            }
        }
    }

    companion object {
        var isRunning = false
    }

    override fun onBind(p0: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        Log.i(TAG, "onCreate: ")
        super.onCreate()

        if (!SettingUtils.enableLocation) return

        //注册广播接收器
        registerReceiver(locationStatusReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))
        startService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent == null) return START_NOT_STICKY
        Log.i(TAG, "onStartCommand: ${intent.action}")

        when {
            intent.action == ACTION_START && !isRunning -> startService()
            intent.action == ACTION_STOP && isRunning -> stopService()
            intent.action == ACTION_RESTART -> restartLocation()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: ")
        super.onDestroy()

        if (!SettingUtils.enableLocation) return
        stopService()
        //在 Service 销毁时记得注销广播接收器
        unregisterReceiver(locationStatusReceiver)
    }

    private fun startService() {
        try {
            //清空缓存
            HttpServerUtils.apiLocationCache = LocationInfo()

            if (SettingUtils.enableLocation && PermissionUtils.isGranted(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION)) {

                //设置定位监听
                App.LocationClient.setOnLocationListener(object : OnLocationListener() {
                    override fun onLocationChanged(location: Location) {
                        //位置信息
                        Log.d(TAG, "onLocationChanged(location = ${location})")

                        val locationInfoNew = LocationInfo(
                            location.longitude, location.latitude, "", App.DateFormat.format(Date(location.time)), location.provider.toString()
                        )

                        // ★★★ 2026-08-28 省电：坐标没动就不做【同步网络反查】。
                        //   依据：本回调按 locationMinInterval（默认 10 秒）持续触发，而旧代码每次都无条件
                        //   调 Geocoder.getFromLocation() —— 那是一次同步的地理编码网络往返（还跑在
                        //   定位回调线程上，顺带是 ANR 风险）。手机静置在桌上时每次拿到的经纬度几乎相同，
                        //   地址字符串也必然相同 → 每小时约 360 次毫无产出的网络请求 + 唤醒基带/Wi-Fi。
                        //   现在只在位移超过 GEOCODE_MOVE_DEG（≈30 米，市/区级地址在这个量级内不会变化）
                        //   或缓存里还没有地址时才反查；返回给控制端的字段、语义、精度完全不变。
                        val prev = HttpServerUtils.apiLocationCache
                        val moved = prev.address.isEmpty() ||
                            Math.abs(prev.latitude - location.latitude) > GEOCODE_MOVE_DEG ||
                            Math.abs(prev.longitude - location.longitude) > GEOCODE_MOVE_DEG
                        if (moved) {
                            //根据坐标经纬度获取位置地址信息（WGS-84坐标系）
                            val list = App.Geocoder.getFromLocation(location.latitude, location.longitude, 1)
                            if (list?.isNotEmpty() == true) {
                                locationInfoNew.address = list[0].getAddressLine(0)
                            }
                            Log.d(TAG, "locationInfoNew = $locationInfoNew")
                        } else {
                            // 复用上次解析结果：地址与旧值一致，因此不再打日志（旧实现每 10 秒写 5 行日志文件）
                            locationInfoNew.address = prev.address
                        }
                        HttpServerUtils.apiLocationCache = locationInfoNew
                    }
                })

                //设置异常监听
                App.LocationClient.setOnExceptionListener(object : OnExceptionListener {
                    override fun onException(@LocationErrorCode errorCode: Int, e: Exception) {
                        //定位出现异常 && 尝试重启定位
                        Log.w(TAG, "onException(errorCode = $errorCode, e = ${e})")
                        restartLocation()
                    }
                })

                restartLocation()
                isRunning = true
            } else if (!SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                Log.d(TAG, "stopLocation")
                App.LocationClient.stopLocation()
                isRunning = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "startService: ${e.message}")
            isRunning = false
        }
    }

    private fun stopService() {
        //清空缓存
        HttpServerUtils.apiLocationCache = LocationInfo()

        isRunning = try {
            //如果已经开始定位，则先停止定位
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
            stopForeground(true)
            stopSelf()
            false
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "stopService: ${e.message}")
            true
        }
    }

    private fun restartLocation() {
        //如果已经开始定位，则先停止定位
        if (App.LocationClient.isStarted()) {
            App.LocationClient.stopLocation()
        }
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //可根据具体需求设置定位配置参数（这里只列出一些主要的参数）
            val locationOption = App.LocationClient.getLocationOption().setAccuracy(SettingUtils.locationAccuracy)//设置位置精度：高精度
                .setPowerRequirement(SettingUtils.locationPowerRequirement) //设置电量消耗：低电耗
                .setMinTime(SettingUtils.locationMinInterval)//设置位置更新最小时间间隔（单位：毫秒）； 默认间隔：10000毫秒，最小间隔：1000毫秒
                .setMinDistance(SettingUtils.locationMinDistance)//设置位置更新最小距离（单位：米）；默认距离：0米
                .setOnceLocation(false)//设置是否只定位一次，默认为 false，当设置为 true 时，则只定位一次后，会自动停止定位
                .setLastKnownLocation(false)//设置是否获取最后一次缓存的已知位置，默认为 true
            //设置定位配置参数
            App.LocationClient.setLocationOption(locationOption)
            App.LocationClient.startLocation()
        } else {
            Log.w(TAG, "onException: GPS未开启")
        }
    }

    private fun handleLocationStatusChanged() {
        //处理状态变化
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //已启用
            Log.d(TAG, "handleLocationStatusChanged: 已启用")
            if (SettingUtils.enableLocation && !App.LocationClient.isStarted()) {
                App.LocationClient.startLocation()
            }
        } else {
            //已停用
            Log.d(TAG, "handleLocationStatusChanged: 已停用")
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
            }
        }
    }

}
