package cn.ppps.forwarder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.MainActivity
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.relay.RelaySender
import cn.ppps.forwarder.relay.RelayServerClient
import cn.ppps.forwarder.relay.RelayServerHandler
import cn.ppps.forwarder.relay.RelayServerListener
import cn.ppps.forwarder.relay.ZtDirectClient
import cn.ppps.forwarder.relay.ZtDirectScanner
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.FRONT_CHANNEL_ID
import cn.ppps.forwarder.utils.FRONT_CHANNEL_NAME
import cn.ppps.forwarder.utils.FRONT_NOTIFY_ID
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.SettingUtils
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 被控端中继服务（前台服务）
 * 主动连接中继服务，接收控制端命令并响应。
 * ★ 每5秒上报设备状态（名称/锁屏/屏幕/电量/充电），由中继广播给所有手机控制端实时显示。
 */
class RelayServerService : Service() {

    private val TAG = "RelayServerService"
    private var client: RelayServerClient? = null
    private var listener: RelayServerListener? = null
    private var executor: ExecutorService? = null
    private var stateTimer: Timer? = null

    // ★ Tailscale直连客户端（多控制端：phoneIp → client，中继关闭时被控端主动连接控制端56789）
    private val ztDirectClients = ConcurrentHashMap<String, ZtDirectClient>()

    // ★ Tailscale直连扫描器（中继关闭时自动发现控制端，与PC被控端扫描兜底一致）
    private var ztScanner: ZtDirectScanner? = null

    companion object {
        @Volatile
        var isRunning = false

        @Volatile
        var isConnected = false

        /** 手机控制端是否已连接中继（由中继 sfsys0000000 广播维护） */
        @Volatile
        var isControllerOnline = false

        fun start(context: Context) {
            val intent = Intent(context, RelayServerService::class.java)
            intent.action = ACTION_START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RelayServerService::class.java)
            intent.action = ACTION_STOP
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Android 11+ 需要按 manifest 声明的前台服务类型启动（camera 类型用于后台摄像头推流，
        // microphone 类型用于麦克风采集（WebRTC 麦克风功能，Android 14+ 后台录音要求该 fgst 类型））
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(FRONT_NOTIFY_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                        or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(FRONT_NOTIFY_ID, buildNotification())
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY
        Log.i(TAG, "onStartCommand: ${intent.action}")
        when (intent.action) {
            ACTION_START -> startRelay()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startRelay() {
        if ((client != null || listener != null) && isRunning) return
        isRunning = true
        executor = Executors.newFixedThreadPool(2)

        // ★ 恢复已保存的屏幕捕获授权（复用上次授权的Intent，App重启后无需重新授权）
        if (ScreenProjectionService.restore(this)) {
            Log.i(TAG, "已恢复屏幕捕获授权，远程屏幕控制可用")
            // ★★★ 2026-08-14 修复"重启应用后授权失效"：
            //   restore只设置了ScreenStreamManager，未拉起前台服务；
            //   ServerFragment.isScreenProjectionAuthorized要求"ScreenProjectionService服务运行中"，
            //   服务不在→误判未授权→一键授权/自动授权重新弹MediaProjection框。
            //   这里必须把前台服务启动起来（onStartCommand内会再次restore，幂等）。
            ScreenProjectionService.startForegroundOnly(this)
        }

        val onConnected: () -> Unit = {
            isConnected = true
            Log.i(TAG, "被控端已连接中继 ${RelaySettings.relayHost}:${RelaySettings.relayServerPort}")
            // ★ 2026-08-16 中继联动：中继正常 → 关闭 Tailscale VPN（节省资源，Go 后端保留）
            cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(this, true)
        }
        val onDisconnected: () -> Unit = {
            isConnected = false
            Log.i(TAG, "被控端连接已断开")
            // ★ 2026-08-16 中继联动：中继不可用 → 自动开启 Tailscale 直连
            cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(this, false)
            // ★ 中继断开后由 ZtDirectScanner 自动探测并建立 TS 直连（扫描器每15秒探测56789），
            //   不再自动操作 Tailscale 开关（2026-08-13 取消：避免无障碍窗口出现在被控端；Tailscale 无公开API可编程开关）
        }
        // ★ 中继连接的命令处理：响应经中继回传（控制端经中继56782/56787接收）
        val onRelayCommand: (String, ByteArray) -> Unit = { cmd: String, payload: ByteArray ->
            // 命令处理放到线程池，避免阻塞接收循环
            executor?.execute {
                try {
                    // ★ 2026-08-06：传入中继client作为sender，文件下载等二进制推送经中继连接回传
                    val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_RELAY, client)
                    if (result != null) {
                        client?.send(result.first, result.second)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "命令处理异常: ${e.message}")
                }
            }
            Unit
        }
        // ★ 直连监听(56786)的命令处理：响应经来源连接回传（控制端ZT/局域网直连被控端时，多控制端按来源回发）
        val onDirectCommand: (Long, String, ByteArray) -> Unit = { connId: Long, cmd: String, payload: ByteArray ->
            executor?.execute {
                try {
                    // ★ 2026-08-06：修复重连后connId失效问题：
                    //   每次发送前动态从listener中查找"该connId是否仍存在"，
                    //   不存在则降级用"最新接入连接"发送，避免数据静默丢弃。
                    //   同时实现sendSync同步发送，供文件下载可靠传输。
                    val directSender = object : RelaySender {
                        private fun resolveConn(): Pair<Long, java.net.Socket?>? {
                            val directListener = listener ?: return null
                            val s = directListener.connections[connId]
                            if (s != null && !s.isClosed && s.isConnected) return connId to s
                            // 原connId失效（重连后connId变了），降级使用最新连接
                            var bestId = Long.MIN_VALUE
                            var best: java.net.Socket? = null
                            for ((id, sock) in directListener.connections) {
                                if (id > bestId && sock.isConnected && !sock.isClosed) {
                                    bestId = id
                                    best = sock
                                }
                            }
                            return if (best != null) bestId to best else null
                        }
                        override fun isConnected(): Boolean = resolveConn() != null
                        override fun send(cmd: String, payload: ByteArray) {
                            val (cid, _) = resolveConn() ?: return
                            listener?.sendTo(cid, cmd, payload)
                        }
                        override fun send(cmd: String, payload: String) {
                            val (cid, _) = resolveConn() ?: return
                            listener?.sendTo(cid, cmd, payload)
                        }
                        override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean {
                            val (cid, _) = resolveConn() ?: return false
                            return listener?.sendToSync(cid, cmd, payload, timeoutMs) ?: false
                        }
                    }
                    val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_DIRECT, directSender)
                    if (result != null) {
                        val (cid, _) = try {
                            val directListener = listener
                            val s = directListener?.connections?.get(connId)
                            if (s != null && !s.isClosed && s.isConnected) connId to s
                            else {
                                var bestId = Long.MIN_VALUE
                                var best: java.net.Socket? = null
                                if (directListener != null) {
                                    for ((id, sock) in directListener.connections) {
                                        if (id > bestId && sock.isConnected && !sock.isClosed) {
                                            bestId = id; best = sock
                                        }
                                    }
                                }
                                if (best != null) bestId to best else null
                            }
                        } catch (_: Exception) { null } ?: return@execute
                        listener?.sendTo(cid, result.first, result.second)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "直连命令处理异常: ${e.message}")
                }
            }
            Unit
        }

        // ★ 中继云优先（2026-08-05）：被控端始终主动连接中继（更稳定，控制端优先显示云服务），
        //   中继不可达时由直连监听(56786) + TS直连扫描器(56789)兜底
        val c = RelayServerClient(
            host = RelaySettings.relayHost,
            port = RelaySettings.relayServerPort,
            onConnected = onConnected,
            onDisconnected = onDisconnected,
            onCommand = onRelayCommand,
        )
        c.start()
        client = c
        // 摄像头推流复用中继连接发送视频帧（直连预览时被控端视频仍经中继56788推流）
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(c)

        // ★ 同时启动直连监听(56786)：接受控制端 ZT/局域网直连（中继关闭时仍可被控制，恢复原直连能力）
        if (listener == null) {
            val l = RelayServerListener(
                port = RelaySettings.relayServerPort,
                onConnected = { Log.i(TAG, "直连监听端口 ${RelaySettings.relayServerPort} 已就绪") },
                onDisconnected = { },
                onCommand = onDirectCommand,
            )
            l.start()
            listener = l
            // ★ 注册直连监听为摄像头帧发送通道（直连模式下摄像头仍可用）
            cn.ppps.forwarder.relay.CameraStreamManager.addSender(l)
        }
        // ★ Tailscale直连：设置启动器（收到ztdirect0000时触发主动连接控制端56789，兜底通道）
        RelayServerHandler.ztDirectLauncher = { phoneIp, port ->
            startZtDirect(phoneIp, port)
        }
        // ★ Tailscale直连扫描兜底：中继关闭时自动发现手机控制端并主动连接（与PC被控端一致）
        // ★ 2026-08-16 修复：多控制端场景下持续扫描所有未连接的控制端（华为+红米控制端并存），
        //   每台控制端各自建立独立直连。已连接的控制端IP由 connectedIps 提供，扫描时跳过。
        ztScanner = ZtDirectScanner(
            isDirectActive = { ztDirectClients.values.any { it.isConnected() } },
            onDirectFound = { phoneIp, port -> startZtDirect(phoneIp, port) },
            connectedIps = { ztDirectClients.keys.toSet() },
        ).also { it.start() }
        // ★ 启动设备状态周期上报（每5秒）
        startStateReport()
    }

    /** ★ 启动Tailscale直连客户端（主动连接手机控制端56789，中继关闭时仍可控制，多控制端各自独立连接） */
    private fun startZtDirect(phoneIp: String, port: Int) {
        Log.i(TAG, "★ startZtDirect: 连接手机控制端 $phoneIp:$port")
        // ★ 防止自连：本机IP（局域网/ZT）直接忽略——扫描器可能把本机56789(控制端App同机运行)当成控制端，
        //   自连会占住 isDirectActive 使扫描器休眠，导致真实控制端(华为/PC)永不被发现
        if (isOwnIp(phoneIp)) {
            Log.i(TAG, "★ 忽略本机IP直连 $phoneIp（防止自连）")
            return
        }
        // ★ 多控制端：每台控制端独立连接（与PC被控端_zt_direct_clients一致），已连接的直接复用
        val existing = ztDirectClients[phoneIp]
        if (existing != null && existing.isConnected()) {
            Log.i(TAG, "★ 已有到 $phoneIp 的TS直连，复用")
            return
        }
        existing?.let {
            // ★ 移除旧通道的摄像头帧发送权
            cn.ppps.forwarder.relay.CameraStreamManager.removeSender(it)
            it.stop()
            ztDirectClients.remove(phoneIp)
        }
        val c = ZtDirectClient(
            host = phoneIp,
            port = port,
            onConnected = {
                Log.i(TAG, "★ TS直连已连接控制端 $phoneIp:$port")
            },
            onDisconnected = {
                Log.i(TAG, "TS直连已断开 $phoneIp:$port")
            },
            onCommand = { cmd, payload ->
                // 命令处理放到线程池，避免阻塞接收循环（与中继一致）
                executor?.execute {
                    try {
                        // ★ 2026-08-06：传入该控制端TS直连client作为sender（文件下载二进制推送回发该控制端）
                        Log.i(TAG, "★ TS直连 handle命令: cmd=$cmd payloadLen=${payload.size} phoneIp=$phoneIp")
                        val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_ZT, ztDirectClients[phoneIp])
                        if (result != null) {
                            // ★ 响应经来源通道回传（查map取该控制端最新连接）
                            Log.i(TAG, "★ TS直连 响应发送: respCmd=${result.first} respLen=${result.second.length} phoneIp=$phoneIp")
                            ztDirectClients[phoneIp]?.send(result.first, result.second)
                        } else {
                            Log.w(TAG, "TS直连 handle 无响应: cmd=$cmd phoneIp=$phoneIp")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "TS直连命令处理异常: ${e.message}")
                    }
                }
            },
        )
        c.start()
        ztDirectClients[phoneIp] = c
        // ★ 注册TS直连为摄像头帧发送通道（中继关闭直连控制时摄像头仍可用）
        cn.ppps.forwarder.relay.CameraStreamManager.addSender(c)
    }

    /** ★ 判断目标IP是否本机（仅自己App的Tailscale IP + 局域网IP），防止被控端自连自己的控制端App */
    private fun isOwnIp(target: String): Boolean {
        // ★ 2026-08-16 修复：Tailscale 本机IP用 getOwnZtIps()（只含自己App节点IP）。
        //   原实现遍历 NetworkInterface，会把同一手机上"控制端App的VPN接口IP"误判为本机，
        //   导致 startZtDirect 忽略对控制端的直连。
        if (target in RelayServerHandler.getOwnZtIps()) return true
        return try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).any { ni ->
                if (!ni.isUp || ni.isLoopback) return@any false
                java.util.Collections.list(ni.inetAddresses).any { addr ->
                    val ip = addr.hostAddress ?: return@any false
                    // ★ 排除 Tailscale CGNAT 段（100.64-100.127.x）：该段可能是其他App(控制端)的VPN接口，
                    //   不属于"本机局域网IP"，不能用于自连判断
                    if (isTailscaleCgnat(ip)) return@any false
                    ip == target
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "本机IP判断异常: ${e.message}")
            false
        }
    }

    /** Tailscale CGNAT 段判断（100.64.0.0/10） */
    private fun isTailscaleCgnat(ip: String): Boolean {
        val p = ip.split(".")
        if (p.size != 4 || p[0] != "100") return false
        return p[1].toIntOrNull()?.let { it in 64..127 } ?: false
    }

    /** 启动设备状态上报定时器：每5秒发送 devstate0000（名称|锁屏|屏幕|电量|充电） */
    private fun startStateReport() {
        if (stateTimer != null) return
        stateTimer = Timer("DevStateReport", true)
        stateTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    val state = buildDeviceState()
                    // ★ 设备状态上报：中继连接 + 直连监听（广播所有已接入控制端）+ TS直连（广播所有控制端）
                    client?.send(RelayCommands.CMD_DEV_STATE, state)
                    listener?.broadcast(RelayCommands.CMD_DEV_STATE, state)
                    for (c in ztDirectClients.values) {
                        c.send(RelayCommands.CMD_DEV_STATE, state)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "设备状态上报异常: ${e.message}")
                }
            }
        }, 2000, 5000)
    }

    /** 采集并拼接设备状态：名称|锁屏|屏幕|电量|充电 */
    private fun buildDeviceState(): String {
        // 名称：用户备注优先，否则用品牌+型号（如 "Redmi K40"）
        val mark = SettingUtils.extraDeviceMark
        val name = if (mark.isNotBlank()) mark else "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        // 锁屏状态
        var locked = false
        try {
            val km = getSystemService(KeyguardManager::class.java)
            if (km != null) {
                locked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) km.isKeyguardLocked else false
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取锁屏状态失败: ${e.message}")
        }
        // 屏幕状态
        var screenOn = false
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null) {
                screenOn = pm.isInteractive
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取屏幕状态失败: ${e.message}")
        }
        // 电量与充电状态
        var battery = -1
        var charging = 0
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(null, filter)
            }
            if (batteryIntent != null) {
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                battery = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                charging = if (status == BatteryManager.BATTERY_STATUS_CHARGING || plugged != 0) 1 else 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取电量失败: ${e.message}")
        }
        return "$name|${if (locked) 1 else 0}|${if (screenOn) 1 else 0}|$battery|$charging"
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        isConnected = false
        isControllerOnline = false
        stateTimer?.cancel()
        stateTimer = null
        client?.stop()
        client = null
        listener?.stop()
        listener = null
        // ★ 停止全部TS直连（多控制端）
        ztDirectClients.values.forEach { it.stop() }
        ztDirectClients.clear()
        // ★ 停止TS直连扫描器
        ztScanner?.stop()
        ztScanner = null
        RelayServerHandler.ztDirectLauncher = null
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(null)
        cn.ppps.forwarder.relay.ScreenStreamManager.releaseProjection()
        // ★ 停止屏幕捕获前台服务（与投影释放同步，避免常驻）
        ScreenProjectionService.stop(this)
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(FRONT_CHANNEL_ID, FRONT_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentTitle = getString(R.string.app_name)
        val contentText = String.format(getString(R.string.relay_server_running), RelaySettings.relayHost)
        val flags = if (Build.VERSION.SDK_INT >= 30) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FRONT_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_forwarder)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
