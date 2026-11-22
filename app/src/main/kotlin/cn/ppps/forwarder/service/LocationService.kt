package cn.ppps.forwarder.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.relay.TrackDebugLog
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

    /** ★ 2026-11-17 省电：静止 60 秒后停定位 SDK；10 秒巡检，60 秒写一次心跳 */
    private val LOCATION_IDLE_STOP_MS = 60_000L
    private val IDLE_WATCH_INTERVAL_MS = 10_000L
    private val HEARTBEAT_INTERVAL_MS = 60_000L
    private val MOTION_WAKE_COOLDOWN_MS = 10_000L
    private val ACCURACY_SWITCH_COOLDOWN_MS = 10_000L
    /** ★ 2026-11-20 运动态 GPS 回调间隔：5 秒（原 30 秒太稀，移动中轨迹会拉直线） */
    private val MOTION_MIN_TIME_MS = 5_000L
    /**
     * ★ 2026-11-22 方案C：运动持续该时长仍没有 ≥25 米真实位移 ⇒ 判为「原地活动」，
     *   从高精度 GPS 退回低精度（不停 SDK，仍能感知真实移动后自动升回 GPS）。
     */
    private val MOVING_NO_DISPLACE_DOWNGRADE_MS = 120_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleWatchPosted = false
    private var heartbeatTicks = 0
    private var lastMotionWakeStartAt = 0L
    private var lastAccuracySwitchAt = 0L
    /** 当前已下发给 LocationClient 的精度；-1 表示尚未启动/未知 */
    private var clientAccuracy = -1

    private val idleWatchRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) {
                idleWatchPosted = false
                return
            }
            try {
                ensureAccuracyMatchesMotion("IDLE_WATCH")
                checkIdleStop()
                heartbeatTicks++
                if (heartbeatTicks * IDLE_WATCH_INTERVAL_MS >= HEARTBEAT_INTERVAL_MS) {
                    heartbeatTicks = 0
                    writeHeartbeat()
                }
            } finally {
                if (isRunning) {
                    mainHandler.postDelayed(this, IDLE_WATCH_INTERVAL_MS)
                } else {
                    idleWatchPosted = false
                }
            }
        }
    }

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
                        val hAcc = if (location.hasAccuracy()) location.accuracy else -1f
                        val provider = location.provider ?: ""
                        TrackDebugLog.event(
                            "LOCATION_CALLBACK",
                            "lng" to String.format("%.7f", location.longitude),
                            "lat" to String.format("%.7f", location.latitude),
                            "hAcc" to hAcc,
                            "provider" to provider,
                            "screenOn" to isScreenOn(),
                            "moving" to cn.ppps.forwarder.relay.MotionDetector.isMoving(),
                            "motionSensor" to cn.ppps.forwarder.relay.MotionDetector.isSensorAvailable(),
                            "motionAgeMs" to if (cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs() <= 0L) -1L
                                             else System.currentTimeMillis() - cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs(),
                            "clientStarted" to App.LocationClient.isStarted(),
                            "clientAccuracy" to clientAccuracy,
                            "baseAccuracy" to SettingUtils.locationAccuracy,
                            "targetAccuracy" to effectiveAccuracy(),
                            "movingHighAccuracy" to SettingUtils.locationMovingHighAccuracy
                        )

                        val locationInfoNew = LocationInfo(
                            location.longitude, location.latitude, "", App.DateFormat.format(Date(location.time)), location.provider.toString()
                        )

                        // ★★★ 2026-08-28 省电：坐标没动就不做【同步网络反查】。
                        //   依据：本回调按 locationMinInterval（2026-11-17 起默认 30 秒）持续触发，旧代码每次都无条件
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

                        // ★★★ 2026-11-16 全新设计：运动/静止状态机。静止时 MotionDetector 判定为
                        //   静止，LocationTrackStore 直接不采集；运动时按所选档位（10~300 秒）采样，
                        //   位移 ≥25 米才落盘，只保留最近 6 个月。
                        try {
                            cn.ppps.forwarder.relay.LocationTrackStore.onLocation(
                                this@LocationService, location.longitude, location.latitude, hAcc, provider)
                        } catch (e: Exception) {
                            Log.w(TAG, "记录轨迹异常: ${e.message}")
                        }
                    }
                })

                //设置异常监听
                App.LocationClient.setOnExceptionListener(object : OnExceptionListener {
                    override fun onException(@LocationErrorCode errorCode: Int, e: Exception) {
                        //定位出现异常 && 尝试重启定位
                        Log.w(TAG, "onException(errorCode = $errorCode, e = ${e})")
                        restartLocation("EXCEPTION_$errorCode")
                    }
                })

                // ★ 2026-11-17 运动唤醒：运动检测/threshold sensor 触发后重新拉起定位 SDK
                cn.ppps.forwarder.relay.MotionDetector.setMotionListener { source ->
                    mainHandler.post { onWakeByMotion(source) }
                }
                // ★ 运动检测常驻（比 GPS 低功耗）；静止时只停定位 SDK，不清空当前位置缓存
                cn.ppps.forwarder.relay.MotionDetector.start(this@LocationService)
                isRunning = true
                startLocationClient("SERVICE_START", force = true)
                scheduleIdleWatch()
                TrackDebugLog.event(
                    "LOCATION_SERVICE_START",
                    "idleStopMs" to LOCATION_IDLE_STOP_MS,
                    "baseAccuracy" to SettingUtils.locationAccuracy,
                    "movingHighAccuracy" to SettingUtils.locationMovingHighAccuracy,
                    "targetAccuracy" to effectiveAccuracy(),
                    "minIntervalMs" to SettingUtils.locationMinInterval
                )
            } else if (!SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                Log.d(TAG, "stopLocation")
                App.LocationClient.stopLocation()
                clientAccuracy = -1
                isRunning = false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(TAG, "startService: ${e.message}")
            TrackDebugLog.event("SERVICE_START_ERROR", "error" to e.message)
            isRunning = false
        }
    }

    private fun stopService() {
        cancelIdleWatch()
        TrackDebugLog.event(
            "LOCATION_SERVICE_STOP",
            "clientStarted" to App.LocationClient.isStarted(),
            "screenOn" to isScreenOn()
        )
        cn.ppps.forwarder.relay.MotionDetector.setMotionListener(null)
        cn.ppps.forwarder.relay.MotionDetector.stop()

        //清空当前位置缓存（仅真正的服务停止；静止停定位不清缓存）
        HttpServerUtils.apiLocationCache = LocationInfo()

        isRunning = try {
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
                clientAccuracy = -1
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

    private fun scheduleIdleWatch() {
        if (idleWatchPosted) return
        idleWatchPosted = true
        mainHandler.removeCallbacks(idleWatchRunnable)
        mainHandler.postDelayed(idleWatchRunnable, IDLE_WATCH_INTERVAL_MS)
    }

    private fun cancelIdleWatch() {
        idleWatchPosted = false
        mainHandler.removeCallbacks(idleWatchRunnable)
    }

    /** 静止超过门限：只停定位 SDK；MotionDetector 和位置缓存继续保留。 */
    private fun checkIdleStop() {
        if (!isRunning || !SettingUtils.enableLocation) return
        if (!App.LocationClient.isStarted()) return
        if (!cn.ppps.forwarder.relay.MotionDetector.isIdleFor(LOCATION_IDLE_STOP_MS)) return
        val cache = HttpServerUtils.apiLocationCache
        val md = cn.ppps.forwarder.relay.MotionDetector
        val anchor = if (md.lastMotionAtMs() > md.idleAnchorAtMs()) md.lastMotionAtMs() else md.idleAnchorAtMs()
        TrackDebugLog.event(
            "LOCATION_STOP_IDLE",
            "idleStopMs" to LOCATION_IDLE_STOP_MS,
            "idleAnchorMs" to anchor,
            "idleAgeMs" to (System.currentTimeMillis() - anchor),
            "motionAgeMs" to if (md.lastMotionAtMs() <= 0L) -1L else System.currentTimeMillis() - md.lastMotionAtMs(),
            "screenOn" to isScreenOn(),
            "cacheLng" to cache.longitude,
            "cacheLat" to cache.latitude,
            "cacheTime" to cache.time,
            "clientAccuracy" to clientAccuracy
        )
        Log.i(TAG, "★ 静止 ${LOCATION_IDLE_STOP_MS}ms，停止定位SDK（保留运动检测和位置缓存）")
        App.LocationClient.stopLocation()
        clientAccuracy = -1
    }

    /** 加速度 / trigger sensor 检测到运动：重新拉起定位，并确保切到运动态精度。 */
    private fun onWakeByMotion(source: String) {
        if (!isRunning || !SettingUtils.enableLocation) return
        val moving = cn.ppps.forwarder.relay.MotionDetector.isMoving()
        val high = highAccuracyActive()
        val target = if (high) Criteria.ACCURACY_FINE else SettingUtils.locationAccuracy
        val started = App.LocationClient.isStarted()
        if (started && clientAccuracy == target) {
            TrackDebugLog.event(
                "MOTION_WAKE_SKIPPED",
                "source" to source,
                "reason" to "ALREADY_TARGET_ACCURACY",
                "targetAccuracy" to target,
                "clientAccuracy" to clientAccuracy
            )
            return
        }
        val now = System.currentTimeMillis()
        if (!started && now - lastMotionWakeStartAt < MOTION_WAKE_COOLDOWN_MS) {
            TrackDebugLog.event(
                "MOTION_WAKE_SKIPPED",
                "source" to source,
                "reason" to "COOLDOWN",
                "gapMs" to (now - lastMotionWakeStartAt)
            )
            return
        }
        lastMotionWakeStartAt = now
        TrackDebugLog.event(
            "MOTION_WAKE_START",
            "source" to source,
            "screenOn" to isScreenOn(),
            "moving" to moving,
            "targetAccuracy" to target,
            "clientAccuracy" to clientAccuracy,
            "motionAgeMs" to if (cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs() <= 0L) -1L
                             else now - cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs()
        )
        val reason = if (target == Criteria.ACCURACY_FINE)
            "MOTION_HIGH_ACCURACY_$source" else "MOTION_LOW_ACCURACY_$source"
        startLocationClient(reason, force = started)
    }

    /** 巡检兜底：运动态高精度 / 运动结束低精度，防止 listener 漏掉时精度长期不切回。 */
    private fun ensureAccuracyMatchesMotion(reason: String) {
        if (!isRunning || !SettingUtils.enableLocation) return
        if (!App.LocationClient.isStarted()) return
        val moving = cn.ppps.forwarder.relay.MotionDetector.isMoving()
        val high = highAccuracyActive()
        val target = if (high) Criteria.ACCURACY_FINE else SettingUtils.locationAccuracy
        if (clientAccuracy == target) return
        val now = System.currentTimeMillis()
        if (now - lastAccuracySwitchAt < ACCURACY_SWITCH_COOLDOWN_MS) return
        lastAccuracySwitchAt = now
        val noDisplace = moving && !high && SettingUtils.locationMovingHighAccuracy
        val switchReason = when {
            high -> "MOTION_HIGH_ACCURACY_$reason"
            noDisplace -> "MOTION_NO_DISPLACE_LOW_POWER_$reason"
            else -> "MOTION_END_LOW_POWER_$reason"
        }
        TrackDebugLog.event(
            "ACCURACY_SWITCH",
            "reason" to switchReason,
            "moving" to moving,
            "noDisplace" to noDisplace,
            "from" to clientAccuracy,
            "to" to target
        )
        startLocationClient(switchReason, force = true)
    }

    /**
     * ★ 2026-11-22 方案C：是否应使用运动态高精度。
     *   除「运动中 + 允许运动高精度」外，还要求最近 [MOVING_NO_DISPLACE_DOWNGRADE_MS] 内
     *   真的走出过 25 米。室内手持/滑动屏幕只会持续命中运动事件而没有位移，不应占用 GPS。
     */
    private fun highAccuracyActive(): Boolean {
        if (!SettingUtils.locationMovingHighAccuracy) return false
        val md = cn.ppps.forwarder.relay.MotionDetector
        if (!md.isMoving()) return false
        return !md.isMovingWithoutDisplacement(MOVING_NO_DISPLACE_DOWNGRADE_MS)
    }

    private fun effectiveAccuracy(): Int =
        if (highAccuracyActive()) Criteria.ACCURACY_FINE else SettingUtils.locationAccuracy

    private fun startLocationClient(reason: String, force: Boolean = false) {
        val migrated = SettingUtils.migrateLowPowerLocationDefaults()
        if (migrated != null) {
            TrackDebugLog.event("LOW_POWER_MIGRATION", "changes" to migrated)
            Log.i(TAG, "★ 低耗定位配置迁移: $migrated")
        }
        if (!SettingUtils.enableLocation) return
        if (!LocationUtils.isLocationEnabled(App.context) || !LocationUtils.hasLocationCapability(App.context)) {
            Log.w(TAG, "onException: GPS未开启")
            TrackDebugLog.event("LOCATION_START_SKIPPED", "reason" to reason, "detail" to "LOCATION_DISABLED")
            return
        }
        if (App.LocationClient.isStarted()) {
            if (!force) {
                TrackDebugLog.event("LOCATION_START_SKIPPED", "reason" to reason, "detail" to "ALREADY_STARTED")
                return
            }
            App.LocationClient.stopLocation()
            clientAccuracy = -1
        }
        try {
            val denseMigrated = SettingUtils.migrateTrackIntervalDenser()
            if (denseMigrated != null) {
                TrackDebugLog.event("TRACK_INTERVAL_MIGRATION", "changes" to denseMigrated)
                Log.i(TAG, "★ 轨迹采样间隔加密迁移: $denseMigrated")
            }
            val moving = cn.ppps.forwarder.relay.MotionDetector.isMoving()
            // ★ 2026-11-22 方案C：运动中但连续 2 分钟没有 25 米位移 ⇒ 视为原地活动，不开 GPS。
            val highAccuracy = highAccuracyActive()
            val noDisplace = moving && !highAccuracy && SettingUtils.locationMovingHighAccuracy
            val accuracy = if (highAccuracy) Criteria.ACCURACY_FINE else SettingUtils.locationAccuracy
            // ★★★ 2026-11-19【让GPS真正生效】：底层 com.github.pppscn:location 用
            //   locationManager.getBestProvider(criteria, enabledOnly=true) 只注册【单个】provider。
            //   之前 criteria 是 accuracy=FINE 但仍带 POWER_LOW + costAllowed=false（库默认），
            //   getBestProvider 会判定 GPS「高耗电/付费、不满足条件」而排除，永远只返回 network ⇒
            //   全程 provider=network、hAcc 数百米、轨迹被 HACC_TOO_LARGE 丢弃（开车没轨迹根因）。
            //   方案A：运动态且允许高精度时，放开为 POWER_HIGH + costAllowed=true，让 GPS 入选；
            //   静止态维持原低耗配置（POWER_LOW + costAllowed=false，仍选 network 省电）。
            //   option 对象是复用的，两个字段每次都显式写，避免运动→静止后 cost 残留。
            val power = if (highAccuracy) Criteria.POWER_HIGH else SettingUtils.locationPowerRequirement
            val costAllowed = highAccuracy
            // ★★★ 2026-11-20【运动态强制 GPS + 加密回调，解决"拉直线"】：
            //   实测方案A（FINE+HIGH+cost=true）在华为上 getBestProvider 仍返回 network、GPS 从未被请求；
            //   network 基站定位只在基站切换时更新、不连续，叠加 minTime=30s 使点位更稀，
            //   地图上稀疏点被连成直线、弯道全丢。这里运动态直接 setProvider("gps") 绕过
            //   getBestProvider 强制卫星定位，并把回调间隔降到 5 秒；静止态传 null 走 getBestProvider
            //   （仍选 network 省电）且维持低耗间隔。库 setProvider 参数为 @Nullable，可传 null。
            val provider = if (highAccuracy) LocationManager.GPS_PROVIDER else null
            val minTime = if (highAccuracy) MOTION_MIN_TIME_MS else SettingUtils.locationMinInterval
            val locationOption = App.LocationClient.getLocationOption()
                .setAccuracy(accuracy)
                .setPowerRequirement(power)
                .setCostAllowed(costAllowed)
                .setProvider(provider)
                .setMinTime(minTime)
                .setMinDistance(SettingUtils.locationMinDistance)
                .setOnceLocation(false)
                .setLastKnownLocation(false)
            App.LocationClient.setLocationOption(locationOption)
            App.LocationClient.startLocation()
            clientAccuracy = accuracy
            TrackDebugLog.event(
                "LOCATION_START",
                "reason" to reason,
                "accuracy" to accuracy,
                "baseAccuracy" to SettingUtils.locationAccuracy,
                "movingHighAccuracy" to SettingUtils.locationMovingHighAccuracy,
                "moving" to moving,
                "highAccuracy" to highAccuracy,
                "noDisplace" to noDisplace,
                "power" to power,
                "costAllowed" to costAllowed,
                "provider" to (provider ?: "auto"),
                "minTimeMs" to minTime,
                "minDistanceM" to SettingUtils.locationMinDistance,
                "screenOn" to isScreenOn()
            )
        } catch (e: Exception) {
            Log.e(TAG, "startLocationClient: ${e.message}")
            TrackDebugLog.event("LOCATION_START_ERROR", "reason" to reason, "error" to e.message)
        }
    }

    private fun restartLocation(reason: String = "RESTART") {
        startLocationClient(reason, force = true)
    }

    private fun isScreenOn(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive == true
        } catch (_: Exception) {
            true
        }
    }

    private fun writeHeartbeat() {
        val cache = HttpServerUtils.apiLocationCache
        TrackDebugLog.event(
            "HEARTBEAT",
            "serviceRunning" to isRunning,
            "clientStarted" to App.LocationClient.isStarted(),
            "clientAccuracy" to clientAccuracy,
            "targetAccuracy" to effectiveAccuracy(),
            "moving" to cn.ppps.forwarder.relay.MotionDetector.isMoving(),
            "motionSensor" to cn.ppps.forwarder.relay.MotionDetector.isSensorAvailable(),
            "motionAgeMs" to if (cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs() <= 0L) -1L
                             else System.currentTimeMillis() - cn.ppps.forwarder.relay.MotionDetector.lastMotionAtMs(),
            "screenOn" to isScreenOn(),
            "cacheLng" to cache.longitude,
            "cacheLat" to cache.latitude,
            "cacheTime" to cache.time,
            "trackPoints" to cn.ppps.forwarder.relay.LocationTrackStore.count(this)
        )
    }

    private fun handleLocationStatusChanged() {
        //处理状态变化
        if (LocationUtils.isLocationEnabled(App.context) && LocationUtils.hasLocationCapability(App.context)) {
            //已启用
            Log.d(TAG, "handleLocationStatusChanged: 已启用")
            if (SettingUtils.enableLocation && !App.LocationClient.isStarted()) {
                startLocationClient("PROVIDER_ENABLED", force = false)
            }
        } else {
            //已停用
            Log.d(TAG, "handleLocationStatusChanged: 已停用")
            if (SettingUtils.enableLocation && App.LocationClient.isStarted()) {
                App.LocationClient.stopLocation()
                clientAccuracy = -1
                TrackDebugLog.event("LOCATION_STOP_PROVIDER_DISABLED", "screenOn" to isScreenOn())
            }
        }
    }

}
