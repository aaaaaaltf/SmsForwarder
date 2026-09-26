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
import cn.ppps.forwarder.relay.TailscaleDirectClient
import cn.ppps.forwarder.relay.TailscaleDirectScanner
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.ACTION_STOP
import cn.ppps.forwarder.utils.FRONT_CHANNEL_ID
import cn.ppps.forwarder.utils.FRONT_CHANNEL_NAME
import cn.ppps.forwarder.utils.FRONT_NOTIFY_ID
import cn.ppps.forwarder.utils.DeviceIdentity
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
 * ★ 周期上报设备状态（名称/锁屏/屏幕/电量/充电），由中继广播给所有手机控制端实时显示；
 *   忙时（亮屏或有控制端连接/推流中）每 5 秒一次，纯待机时降为每 30 秒一次（省电，见 scheduleStateReport）。
 */
class RelayServerService : Service() {

    private val TAG = "RelayServerService"
    private var client: RelayServerClient? = null
    private var listener: RelayServerListener? = null
    private var executor: ExecutorService? = null
    private var stateTimer: Timer? = null

    // ★ Tailscale直连客户端（多控制端：phoneIp → client，中继关闭时被控端主动连接控制端56789）
    private val tsDirectClients = ConcurrentHashMap<String, TailscaleDirectClient>()

    // ★ Tailscale直连扫描器（中继关闭时自动发现控制端，与PC被控端扫描兜底一致）
    private var tsScanner: TailscaleDirectScanner? = null

    companion object {
        /** ★ 省电：忙时设备状态上报间隔（与历史行为一致） */
        private const val STATE_REPORT_BUSY_MS = 5000L

        /** ★ 省电：灭屏且无控制端连接时的状态上报间隔 */
        private const val STATE_REPORT_IDLE_MS = 30000L

        /**
         * ★ 2026-09-04 修复"下载卡在 0%/取消无效"：这些命令一律在**接收线程内联**执行，不进命令池。
         *
         * 三条通道（中继 / 直连监听56786 / TS直连）的全部命令原本共用一个
         * `Executors.newFixedThreadPool(2)`，而池里跑的既有毫秒级命令，也有能阻塞几十秒的：
         * CMD_FS_LIST 用 sendSync(15s)+waitForConnection(15s)+重试 sendSync(15s)，
         * CMD_WAKEUP_SCREEN 要等主线程加悬浮窗（最多 2 秒）。两条线程一旦被慢命令占住，
         * 排在后面的 CMD_FS_ACK 就到不了——下载线程每发一块都要等这个 ACK，于是整条传输停摆；
         * 控制端点"取消"发来的 CMD_FS_CANCEL 同样进不了池，取消彻底无效。
         *
         * 另一个问题是同一连接的命令会被两条线程**并发**执行：手势 down/move/up 可能乱序注入，
         * CMD_FS_GET 与其后的 CMD_FS_CANCEL 谁先改会话标志不确定。
         *
         * 这些命令的处理体只做内存读写或极快的调用（响应也全部经 sendExecutor 异步投递，
         * 不会回头阻塞socket写入），放在各自的接收线程上顺序执行，既消除排队饿死，
         * 又天然获得"与线路顺序一致"的每连接串行语义。慢命令仍进池，不影响接收循环。
         */
        private val FAST_COMMANDS: Set<String> = setOf(
            RelayCommands.CMD_PING,
            RelayCommands.CMD_FS_ACK,
            RelayCommands.CMD_FS_CANCEL,
            RelayCommands.CMD_FS_GET,
            RelayCommands.CMD_FS_UPDATA,
            RelayCommands.CMD_FS_UPDONE,
            RelayCommands.CMD_RD_MOUSE_DOWN,
            RelayCommands.CMD_RD_MOUSE_MOVE,
            RelayCommands.CMD_RD_MOUSE_UP,
            RelayCommands.CMD_RD_MOUSE_DBL,
            RelayCommands.CMD_RD_MOUSE_WHEEL,
        )

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

    /**
     * 命令分发：快命令在接收线程内联执行，其余进线程池（判据见 [FAST_COMMANDS]）。
     * 命令池只有两条线程且被三条通道共用，慢命令占满后连 CMD_FS_ACK 都要排队，
     * 下载必然停摆、取消必然无效——所以不能把所有命令一视同仁地排队。
     */
    private fun submitCommand(cmd: String, body: () -> Unit) {
        if (FAST_COMMANDS.contains(cmd)) {
            try {
                body()
            } catch (e: Exception) {
                Log.e(TAG, "命令处理异常(内联 $cmd): ${e.message}")
            }
            return
        }
        val pool = executor
        if (pool == null) {
            Log.w(TAG, "命令池已停止，丢弃 $cmd")
            return
        }
        try {
            pool.execute {
                try {
                    body()
                } catch (e: Exception) {
                    Log.e(TAG, "命令处理异常($cmd): ${e.message}")
                }
            }
        } catch (e: Exception) {
            // shutdown 后提交会抛 RejectedExecutionException：此时链路正在收尾，丢弃即可
            Log.w(TAG, "提交命令失败 $cmd: ${e.message}")
        }
    }

    /**
     * 解析"这条直连命令该回发到哪条连接"。
     *
     * · connId 仍存活 → 原样返回（多控制端并存时绝不串台）。
     * · connId 已失效（控制端重连后换了 id）→ 只有**全场只剩这一条活连接**时才认定它就是
     *   同一台控制端的新连接、可以续用（这是 2026-08-06 加降级的原始场景：单控制端重连后
     *   数据静默丢弃）。并存多条活连接时返回 null：旧实现取"最大 connId"会把 A 正在下载的
     *   文件字节/命令响应写进 B 的 socket，B 收到它从未请求过的 SF_DATA 帧；而 A 的 ACK 带着
     *   新 connId 落到新会话上，旧线程等不到确认，照样失败——失败快一点反而让控制端
     *   按 2026-08-29 的"接入稳定后带偏移续传"立刻在新连接上重下。
     */
    private fun resolveDirectConn(connId: Long): Pair<Long, java.net.Socket>? {
        val directListener = listener ?: return null
        try {
            val cur = directListener.connections[connId]
            if (cur != null && !cur.isClosed && cur.isConnected) return connId to cur
            var bestId = Long.MIN_VALUE
            var best: java.net.Socket? = null
            var live = 0
            for ((id, sock) in directListener.connections) {
                if (sock.isConnected && !sock.isClosed) {
                    live++
                    if (id > bestId) {
                        bestId = id
                        best = sock
                    }
                }
            }
            if (best == null) return null
            if (live > 1) {
                Log.w(TAG, "★ 直连连接#$connId 已失效，当前有${live}条活连接，拒绝降级到最新连接（避免把本端数据串到别人的socket）")
                return null
            }
            return bestId to best
        } catch (e: Exception) {
            Log.w(TAG, "解析直连回发连接异常#$connId: ${e.message}")
            return null
        }
    }

    /**
     * ★ 2026-09-04 按 phoneIp「现取现用」的 TS 直连发送器。
     *
     * 原来直接把 `tsDirectClients[phoneIp]` 这个具体对象交给 handle()，而文件下载线程会**全程
     * 持有**这个 sender。可 startTailscaleDirect 重连时是 `existing.stop()` + 换新对象：
     * 旧 client 的 sendExecutor/heartBeatExecutor 已 shutdownNow，旧下载线程此后每发一块都只
     * 落到一句"提交TS直连发送任务失败"里静默丢弃，isConnected() 又恒为 false，
     * 只能等 5×20 秒重发超时才放弃——控制端看到的就是"进度条停住不动"。
     * 换成解析型包装后，重连对进行中的下载透明（peerKey 本就是 phoneIp，会话与 ACK 游标不丢）。
     */
    private inner class TsDirectSender(private val phoneIp: String) : RelaySender {
        private fun current(): TailscaleDirectClient? =
            tsDirectClients[phoneIp]?.takeIf { it.isConnected() }

        override fun isConnected(): Boolean = current()?.isConnected() == true

        override fun send(cmd: String, payload: ByteArray) {
            current()?.send(cmd, payload)
        }

        override fun send(cmd: String, payload: String) {
            current()?.send(cmd, payload)
        }

        override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean =
            current()?.sendSync(cmd, payload, timeoutMs) ?: false
    }

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
        // ★ 2026-08-28 被控端服务启动入口的「权限+保活」静默自检：
        //   只后台检测 + 写 logcat（tag=KeepAliveGuardian），不开任何窗口——
        //   后台起 Activity 在 Android 10+ 会被系统拦截，且违背"启动防打扰"要求。
        //   需要用户确认的授权一律由 App 内「一键授权」完成。
        try {
            cn.ppps.forwarder.permission.KeepAliveGuardian.onServiceStartup(this)
        } catch (e: Throwable) {
            Log.w(TAG, "权限保活静默自检失败: ${e.message}")
        }
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

        // ★ 2026-08-28 省电：把本服务的"有人在用"判据注入 Tailscale 侧，
        //   供 VPN 看门狗在灭屏且无任何控制端连接/推流时降频巡检（详见 TailscaleManager.VPN_WATCHDOG_*）。
        //   必须在 ensureStarted/ensureVpnUp 之前设置，保证看门狗第一轮就能拿到判据。
        cn.ppps.forwarder.tailscale.TailscaleManager.busyProvider = { isBusyNow() }
        // ★ 提前初始化 Tailscale 后端，确保 VPN 授权弹窗能被 MainActivity 触发
        cn.ppps.forwarder.tailscale.TailscaleManager.ensureStarted(this)
        // ★ 2026-08-25 需求：被控端启动成功后，直连模式下 VPN 自动开启。
        //   中继连接是否可达由 connectLoop 后台异步判定：若中继实际可达，
        //   onConnected 会回调 setRelayConnected(true) 自动关闭 VPN（节省资源）；
        //   若中继不可达（直连模式），VPN 保持开启，保证控制端可经 Tailscale 直连。
        cn.ppps.forwarder.tailscale.TailscaleManager.ensureVpnUp(this)

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
            // ★★★ 2026-09-23 防振荡（与 onDisconnected 同一窗口）：本client"连上"只说明
            //   命令通道可达，不代表总闸开着——总闸 off 时命令通道仍然连得上（设计如此），
            //   若此时按 onConnected 强制 setRelayConnected(true) 关VPN，就会与服务器推送的
            //   relayst=off（开VPN）打架。权威窗口内一律以 relayst/同机广播为准。
            if (cn.ppps.forwarder.tailscale.TailscaleManager.isExternalRelayStateFresh()) {
                Log.i(TAG, "★ 权威中继状态窗口内 → 忽略本次 onConnected 关VPN（防振荡，以relayst为准）")
            } else {
                // ★ 2026-08-16 中继联动：中继正常 → 关闭 Tailscale VPN（节省资源，Go 后端保留）
                cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(this, true)
            }
        }
        val onDisconnected: () -> Unit = {
            isConnected = false
            Log.i(TAG, "被控端连接已断开")
            // ★★★ 2026-09-23 防振荡：若 180 秒内收到过权威中继状态（relayst 推送/同机广播），
            //   忽略本次"本client连不上→开VPN"（真机实测：两者打架导致 VPN 反复开关、
            //   系统同时出现 tun0+tun1）。窗口过后自动回落本信号，直连能力不丢。
            if (cn.ppps.forwarder.tailscale.TailscaleManager.isExternalRelayStateFresh()) {
                Log.i(TAG, "★ 权威中继状态窗口内 → 忽略本次断连开VPN（防振荡）")
            } else {
                // ★ 2026-08-16 中继联动：中继不可用 → 自动开启 Tailscale 直连
                cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(this, false)
            }
            // ★ 中继断开后由 TailscaleDirectScanner 自动探测并建立 TS 直连（扫描器每15秒探测56789），
            //   不再自动操作 Tailscale 开关（2026-08-13 取消：避免无障碍窗口出现在被控端；Tailscale 无公开API可编程开关）
        }
        // ★ 中继连接的命令处理：响应经中继回传（控制端经中继56782/56787接收）
        val onRelayCommand: (String, ByteArray) -> Unit = { cmd: String, payload: ByteArray ->
            // ★★★ 2026-09-23 服务器推送的中继总闸状态（relayst on/off）→ 被动联动开关VPN。
            //   这是"中继开→关VPN / 中继关→开VPN"的权威信号源（跨机场景，无需同机控制端）：
            //   服务器在①本机注册成功时②总闸每次变更时主动推送，本App零轮询开销。
            //   注意 setRelayConnected 内部有去重（状态未变直接返回），推送频次无关紧要。
            if (cmd == RelayCommands.CMD_RELAY_STATE_SERVER || cmd == "relayst00000") {
                val st = try {
                    String(payload, Charsets.US_ASCII).trim().lowercase()
                } catch (_: Throwable) { "" }
                if (st == "on" || st == "off") {
                    val on = (st == "on")
                    Log.i(TAG, "★ 收到服务器中继状态推送: $st → "
                            + if (on) "关闭VPN（中继开）" else "开启VPN（中继关）")
                    // ★ 刷新权威窗口：180秒内忽略本client onConnected/onDisconnected 的传输层信号
                    cn.ppps.forwarder.tailscale.TailscaleManager.externalRelayStateUntil =
                        System.currentTimeMillis() + 180_000L
                    // ★★★ 2026-09-26 总闸 off 的权威时间戳（只由服务器推送刷新）：
                    //   窗口内同机控制端的 relay_on=true 广播将被忽略（见 App.relayStateReceiver）——
                    //   总闸 off 意味着所有手机的数据通道已被物理断开，本机必须开 VPN 才能被
                    //   已切到直连模式的控制端连上。on 时清零，恢复"以同机控制端为准"。
                    cn.ppps.forwarder.tailscale.TailscaleManager.serverRelayOffUntil =
                        if (on) 0L else System.currentTimeMillis() + 180_000L
                    cn.ppps.forwarder.tailscale.TailscaleManager.setRelayConnected(this, on)
                } else {
                    Log.w(TAG, "中继状态推送负载异常: ${payload.size}字节，忽略")
                }
                Unit
            } else {
                // 慢命令进线程池避免阻塞接收循环；快命令（ACK/CANCEL/下载/手势/心跳）内联执行
                submitCommand(cmd) {
                    try {
                        // ★ 2026-08-06：传入中继client作为sender，文件下载等二进制推送经中继连接回传
                        val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_RELAY, client, peerKey = client)
                        if (result != null) {
                            client?.send(result.first, result.second)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "命令处理异常: ${e.message}")
                    }
                }
                Unit
            }
        }
        // ★ 直连监听(56786)的命令处理：响应经来源连接回传（控制端TS/局域网直连被控端时，多控制端按来源回发）
        val onDirectCommand: (Long, String, ByteArray) -> Unit = { connId: Long, cmd: String, payload: ByteArray ->
            submitCommand(cmd) {
                try {
                    // ★ 2026-08-06：修复重连后connId失效问题——每次发送前动态解析该连接；
                    //   ★ 2026-09-04 降级规则收紧为"仅剩一条活连接才续用"，见 [resolveDirectConn]。
                    //   同时实现sendSync同步发送，供文件下载可靠传输。
                    val directSender = object : RelaySender {
                        private fun resolveConn(): Pair<Long, java.net.Socket?>? = resolveDirectConn(connId)
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
                    val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_DIRECT, directSender, peerKey = connId)
                    if (result != null) {
                        val resolved = resolveDirectConn(connId) ?: return@submitCommand
                        listener?.sendTo(resolved.first, result.first, result.second)
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
            // ★ 2026-08-28 省电：中继不可达时的重连间隔按忙/闲自适应（灭屏无人 5s→20s 退避）。
            //   只影响"连不上时的重试频率"，已建立连接的收发、命令响应、文件传输通路完全不变。
            isBusy = { isBusyNow() },
        )
        c.start()
        client = c
        // 摄像头推流复用中继连接发送视频帧（直连预览时被控端视频仍经中继56788推流）
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(c)

        // ★ 同时启动直连监听(56786)：接受控制端 TS/局域网直连（中继关闭时仍可被控制，恢复原直连能力）
        if (listener == null) {
            // 回调里要用到"正在构造的这个监听器"，构造参数里自引用无法编译，
            // 故先用一个在 start() 前就位、之后只读地指向它的槽位（回调只在 accept 线程触发）。
            val listenerHolder = arrayOfNulls<RelayServerListener>(1)
            val l = RelayServerListener(
                port = RelaySettings.relayServerPort,
                onConnected = { Log.i(TAG, "直连监听端口 ${RelaySettings.relayServerPort} 已就绪") },
                onDisconnected = { },
                onCommand = onDirectCommand,
                // ★★★ 2026-08-30 打通下发路径的关键一环：新控制端一连进来就主动朝它要一次
                //   加密凭证下发（HELLO），不等"恰好抢到 60 秒节流窗口"的那条常规命令。
                //   原实现只在 handle() 里顺手申请，同网多条连接并存时窗口被无关连接长期占住，
                //   真正持有凭证的控制端一次 HELLO 都收不到 → 全新安装的手机永远拿不到凭证。
                onNewConnection = { connId ->
                    val listenerRef = listener ?: listenerHolder[0]
                    if (listenerRef == null) {
                        Log.w(TAG, "直连监听尚未就绪，跳过本次主动 HELLO#$connId")
                    } else {
                        val helloSender = object : RelaySender {
                            override fun isConnected(): Boolean =
                                listenerRef.connections[connId]?.isClosed == false
                            override fun send(cmd: String, payload: ByteArray) =
                                listenerRef.sendTo(connId, cmd, payload)
                            override fun send(cmd: String, payload: String) =
                                listenerRef.sendTo(connId, cmd, payload)
                            override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean =
                                listenerRef.sendToSync(connId, cmd, payload, timeoutMs)
                        }
                        // 本机已有凭证时 requestCredentialFrom 内部直接返回，不会多发任何帧
                        RelayServerHandler.requestCredentialFrom(helloSender, connId, throttle = false)
                    }
                },
            )
            listenerHolder[0] = l
            l.start()
            listener = l
            // ★ 注册直连监听为摄像头帧发送通道（直连模式下摄像头仍可用）
            cn.ppps.forwarder.relay.CameraStreamManager.addSender(l)
        }
        // ★ Tailscale直连：设置启动器（收到ztdirect0000时触发主动连接控制端56789，兜底通道）
        RelayServerHandler.tsDirectLauncher = { phoneIp, port ->
            startTailscaleDirect(phoneIp, port)
        }
        // ★ Tailscale直连扫描兜底：中继关闭时自动发现手机控制端并主动连接（与PC被控端一致）
        // ★ 2026-08-16 修复：多控制端场景下持续扫描所有未连接的控制端（华为+红米控制端并存），
        //   每台控制端各自建立独立直连。已连接的控制端IP由 connectedIps 提供，扫描时跳过。
        tsScanner = TailscaleDirectScanner(
            isDirectActive = { tsDirectClients.values.any { it.isConnected() } },
            onDirectFound = { phoneIp, port -> startTailscaleDirect(phoneIp, port) },
            // ★★★ 2026-08-28 修复直连"一断永断"：connectedIps 只返回【真正连通】的控制端IP。
            //   原实现返回 map.keys——client 在发起连接前就 put 进 map，重试耗尽（停止重试）后
            //   死 client 永远留在 map 里，扫描器把死IP当"已连接"永久跳过，不再探测/重连，
            //   直到重启服务。改为 filterValues { isConnected } 后，失败的IP会被重新探测并
            //   经 startTailscaleDirect 清理重建，自动恢复直连。
            connectedIps = { tsDirectClients.filterValues { it.isConnected() }.keys.toSet() },
            // ★ 2026-08-27 省电：灭屏且无人连接时直连扫描自动降频（详见 TailscaleDirectScanner）
            isBusy = { isBusyNow() },
        ).also { it.start() }
        // ★ 启动设备状态周期上报（忙时5秒，待机30秒）
        startStateReport()
    }

    /**
     * ★ 2026-08-27 省电：判断被控端当前是否"有人在用"。
     * 任一条件成立即视为忙：
     *  - 亮屏（用户正在操作本机，或控制端正在点亮屏幕）
     *  - 中继侧有控制端在线（SYS_CTRL_ON 广播维护）
     *  - 直连监听(56786) / TS直连(56789) 有控制端连接
     *  - 摄像头 / 屏幕 / 麦克风任一路媒体流仍在推（会话进行中一律全速）
     * 只用于决定"发现类/状态类"周期任务的频率，不影响任何数据传输通路。
     */
    private fun isBusyNow(): Boolean {
        if (isScreenOn()) return true
        if (isControllerOnline) return true
        try {
            if (listener?.hasController() == true) return true
            if (tsDirectClients.values.any { it.isConnected() }) return true
            if (cn.ppps.forwarder.relay.CameraStreamManager.isStreaming()) return true
            if (cn.ppps.forwarder.relay.ScreenStreamManager.isStreaming()) return true
            if (cn.ppps.forwarder.relay.MicrophoneStreamManager.isStreaming()) return true
        } catch (e: Exception) {
            Log.w(TAG, "忙碌状态判断异常(按忙处理): ${e.message}")
            return true
        }
        return false
    }

    /** 屏幕是否点亮（查询失败按"亮"处理，宁可多耗电也不降频影响功能） */
    private fun isScreenOn(): Boolean {
        return try {
            val pm = getSystemService(PowerManager::class.java)
            pm == null || pm.isInteractive
        } catch (e: Exception) {
            true
        }
    }

    /** ★ 启动Tailscale直连客户端（主动连接手机控制端56789，中继关闭时仍可控制，多控制端各自独立连接） */
    private fun startTailscaleDirect(phoneIp: String, port: Int) {
        Log.i(TAG, "★ startTailscaleDirect: 连接手机控制端 $phoneIp:$port")
        // ★ 防止自连：本机IP（局域网/TS）直接忽略——扫描器可能把本机56789(控制端App同机运行)当成控制端，
        //   自连会占住 isDirectActive 使扫描器休眠，导致真实控制端(华为/PC)永不被发现
        if (isOwnIp(phoneIp)) {
            Log.i(TAG, "★ 忽略本机IP直连 $phoneIp（防止自连）")
            return
        }
        // ★ 多控制端：每台控制端独立连接（与PC被控端_zt_direct_clients一致），已连接的直接复用
        val existing = tsDirectClients[phoneIp]
        if (existing != null && existing.isConnected()) {
            Log.i(TAG, "★ 已有到 $phoneIp 的TS直连，复用")
            return
        }
        existing?.let {
            // ★ 移除旧通道的摄像头帧发送权
            cn.ppps.forwarder.relay.CameraStreamManager.removeSender(it)
            it.stop()
            tsDirectClients.remove(phoneIp)
        }
        val c = TailscaleDirectClient(
            host = phoneIp,
            port = port,
            onConnected = {
                Log.i(TAG, "★ TS直连已连接控制端 $phoneIp:$port")
            },
            onDisconnected = {
                Log.i(TAG, "TS直连已断开 $phoneIp:$port")
            },
            onCommand = { cmd, payload ->
                // 慢命令进线程池避免阻塞接收循环（与中继一致）；快命令内联执行
                submitCommand(cmd) {
                    try {
                        // ★ 2026-08-06：传入该控制端TS直连client作为sender（文件下载二进制推送回发该控制端）
                        // ★ 2026-09-04：改传解析型 TsDirectSender——下载线程全程持有 sender，而重连会换
                        //   client 对象，绑死旧对象会让进行中的下载静默停摆（见 TsDirectSender 注释）。
                        Log.i(TAG, "★ TS直连 handle命令: cmd=$cmd payloadLen=${payload.size} phoneIp=$phoneIp")
                        val tsSender = TsDirectSender(phoneIp)
                        val result = RelayServerHandler.handle(cmd, payload, RelayServerHandler.CHANNEL_TS, tsSender, peerKey = phoneIp)
                        if (result != null) {
                            // ★ 响应经来源通道回传（查map取该控制端最新连接）
                            Log.i(TAG, "★ TS直连 响应发送: respCmd=${result.first} respLen=${result.second.length} phoneIp=$phoneIp")
                            tsSender.send(result.first, result.second)
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
        tsDirectClients[phoneIp] = c
        // ★ 注册TS直连为摄像头帧发送通道（中继关闭直连控制时摄像头仍可用）
        cn.ppps.forwarder.relay.CameraStreamManager.addSender(c)
    }

    /** ★ 判断目标IP是否本机（仅自己App的Tailscale IP + 局域网IP），防止被控端自连自己的控制端App */
    private fun isOwnIp(target: String): Boolean {
        // ★ 2026-08-16 修复：Tailscale 本机IP用 getOwnTsIps()（只含自己App节点IP）。
        //   原实现遍历 NetworkInterface，会把同一手机上"控制端App的VPN接口IP"误判为本机，
        //   导致 startTailscaleDirect 忽略对控制端的直连。
        if (target in RelayServerHandler.getOwnTsIps()) return true
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

    /** 启动设备状态周期上报定时器：忙时每5秒，待机时每30秒发送 devstate0000（名称|锁屏|屏幕|电量|充电） */
    private fun startStateReport() {
        if (stateTimer != null) return
        stateTimer = Timer("DevStateReport", true)
        scheduleStateReport(2000L)
    }

    /**
     * ★ 2026-08-27 省电：状态上报改为"一次一排"（schedule 而非 scheduleAtFixedRate），
     *   好让每一轮都能根据当前忙/闲重新决定下一次间隔。
     *   依据：buildDeviceState() 每轮要做 KeyguardManager.isKeyguardLocked + PowerManager.isInteractive
     *   + 一次 ACTION_BATTERY_CHANGED 粘性广播查询，再向中继/直连监听/全部TS直连三路各发一帧；
     *   旧实现固定 5 秒一轮 = 每小时 720 次这种唤醒，而灭屏无人连接时这些状态变化极慢，
     *   改成 30 秒可去掉约 83% 的唤醒，控制端看到的电量/锁屏最多晚 30 秒（且控制端一连上就回到 5 秒）。
     */
    private fun scheduleStateReport(delayMs: Long) {
        val t = stateTimer ?: return
        try {
            t.schedule(object : TimerTask() {
                override fun run() {
                    try {
                        val state = buildDeviceState()
                        // ★ 设备状态上报：中继连接 + 直连监听（广播所有已接入控制端）+ TS直连（广播所有控制端）
                        client?.send(RelayCommands.CMD_DEV_STATE, state)
                        listener?.broadcast(RelayCommands.CMD_DEV_STATE, state)
                        for (c in tsDirectClients.values) {
                            c.send(RelayCommands.CMD_DEV_STATE, state)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "设备状态上报异常: ${e.message}")
                    }
                    scheduleStateReport(if (isBusyNow()) STATE_REPORT_BUSY_MS else STATE_REPORT_IDLE_MS)
                }
            }, delayMs)
        } catch (e: IllegalStateException) {
            // timer 已 cancel（服务停止），正常退出
        }
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
        // ★★★ 2026-08-29 尾部追加第6字段【稳定唯一设备ID】。前面5个字段（名称|锁屏|屏幕|电量|充电）
        //   的顺序与含义【完全不变】；旧控制端按索引只读前5段，多出的尾段被自然忽略 → 向后兼容。
        //   用途：控制端判定"这个 100.x 是不是本机被控端的另一入口"时不再依赖无唯一性的状态指纹。
        return "$name|${if (locked) 1 else 0}|${if (screenOn) 1 else 0}|$battery|$charging|${DeviceIdentity.uniqueDeviceId(this)}"
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        isRunning = false
        isConnected = false
        isControllerOnline = false
        // ★ 2026-08-25 停止VPN掉线恢复看门狗（服务停止后不再自动拉起VPN）
        cn.ppps.forwarder.tailscale.TailscaleManager.stopVpnWatchdog()
        stateTimer?.cancel()
        stateTimer = null
        client?.stop()
        client = null
        listener?.stop()
        // ★ 2026-09-04：CameraStreamManager 是进程级单例，服务销毁后它仍会留着这些通道
        //   （且通道闭包捕获了本服务实例）→ 只 add 不 remove 会让 senders 随重启单调增长。
        //   中继 client 由下面的 setClient(null) 负责摘除，这里补齐另外两类。
        listener?.let { cn.ppps.forwarder.relay.CameraStreamManager.removeSender(it) }
        listener = null
        // ★ 停止全部TS直连（多控制端）
        tsDirectClients.values.forEach {
            cn.ppps.forwarder.relay.CameraStreamManager.removeSender(it)
            it.stop()
        }
        tsDirectClients.clear()
        // ★ 停止TS直连扫描器
        tsScanner?.stop()
        tsScanner = null
        RelayServerHandler.tsDirectLauncher = null
        cn.ppps.forwarder.relay.CameraStreamManager.setClient(null)
        // ★ 2026-08-27 省电修复：麦克风此前从未在服务销毁时停止——MicrophoneStreamManager 持有
        //   无超时的 PARTIAL_WAKE_LOCK + 常驻 AudioRecord，服务被系统重建/重启后会继续录音并保持CPU不休眠。
        cn.ppps.forwarder.relay.MicrophoneStreamManager.stop()
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
