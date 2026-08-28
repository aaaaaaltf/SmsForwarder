package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 被控端命令发送器统一接口
 * RelayServerClient（中继模式）与 RelayServerListener（Tailscale直连模式）均实现，
 * 供 CameraStreamManager 等模块透明使用。
 *
 * ★ 2026-08-06 下载稳定性修复：
 *   - 新增 sendSync 同步发送（带超时/结果），供文件下载等关键场景使用，
 *     避免异步队列积压导致"调用方以为发送成功但实际失败"的假活问题
 *   - 心跳包（CMD_DEV_STATE / CMD_PING）使用独立的心跳发送线程池 heartBeatExecutor，
 *     与大流量下载数据的 sendExecutor 物理隔离，防止NAT超时断连
 */
interface RelaySender {
    fun isConnected(): Boolean
    fun send(cmd: String, payload: ByteArray)
    fun send(cmd: String, payload: String)
    /** 同步发送：等待实际写入完成，返回是否成功；用于文件下载等需要可靠发送的场景 */
    fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long = 15000): Boolean
}

/**
 * 被控端中继客户端
 * 主动连接中继服务 56786 端口（本项目专用端口），
 * 接收控制端通过中继转发的命令并发送响应。
 *
 * 帧格式: [4字节大端长度][12字节命令][负载]
 */
class RelayServerClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onCommand: (cmd: String, payload: ByteArray) -> Unit,
    /**
     * ★ 2026-08-28 省电：是否处于"有人在用"状态（亮屏或有控制端连接/推流中）。
     *   仅用于决定【中继连不上时的重连间隔】，不影响任何已建立连接的收发。
     *   默认恒为 true → 未接入该判据时与改造前完全一致（固定 5 秒重连）。
     */
    private val isBusy: () -> Boolean = { true },
) : RelaySender {
    private val TAG = "RelayServerClient"
    private val sendLock = Any()

    /** ★ 省电：忙时（亮屏/有人在用）重连间隔，与改造前一致 */
    private val reconnectBusyMs = 5000L

    /** ★ 省电：待机（灭屏且无控制端连接）重连起始间隔 */
    private val reconnectIdleBaseMs = 5000L

    /** ★ 省电：待机重连间隔上限（指数退避到此封顶：5s→10s→20s） */
    private val reconnectIdleMaxMs = 20000L

    /**
     * 连续失败次数 → 下一次重连等待时间。
     * 省电依据：中继域名不可达时（直连模式/断网/中继服务下线），旧实现每 5 秒无条件发起一次
     * TCP connect（8 秒连接超时），等于每小时 720 次把 Wi-Fi/移动射频从低功耗里拉出来，
     * 是被控端待机时最主要的持续唤醒源之一（RelayServerClient.connectLoop:113）。
     * 灭屏无人时退避到 20 秒（唤醒量降到 1/4），亮屏或有人在用立即回到 5 秒原节奏。
     */
    private fun nextRetryDelayMs(failures: Int): Long {
        val busy = try { isBusy() } catch (_: Throwable) { true }
        if (busy) return reconnectBusyMs
        var delay = reconnectIdleBaseMs
        var i = 1
        while (i < failures && delay < reconnectIdleMaxMs) {
            delay *= 2
            i++
        }
        return minOf(delay, reconnectIdleMaxMs)
    }

    /** ★ 下载/普通数据发送线程池（单线程保证顺序） */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayServerSend").apply { isDaemon = true }
    }

    /** ★ 心跳专用发送线程池（独立通道，不被下载大流量堵队列） */
    private val heartBeatExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayServerHb").apply { isDaemon = true }
    }

    /** 判断是否为心跳类命令（设备状态/心跳ping），走独立发送通道 */
    private fun isHeartBeatCmd(cmd: String): Boolean {
        return cmd == RelayCommands.CMD_DEV_STATE || cmd == RelayCommands.RSP_PONG
    }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var running = false
    private var thread: Thread? = null

    override fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ connectLoop() }, "RelayServerConnect").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        sendExecutor.shutdownNow()
        heartBeatExecutor.shutdownNow()
    }

    private fun connectLoop() {
        // ★ 2026-08-28 省电：连续失败计数（用于退避 + 日志节流），成功连接后归零
        var consecutiveFailures = 0
        while (running) {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 8000)
                if (!running) {
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                    return
                }
                socket = s
                consecutiveFailures = 0
                Log.i(TAG, "已连接中继服务 $host:$port")
                onConnected()
                receiveLoop(s)
            } catch (e: Exception) {
                consecutiveFailures++
                // ★ 省电：连不上时旧实现每 5 秒写一条 WARN（且本项目的 Log 会落盘写文件），
                //   中继长期不可达时等于每天数万条磁盘写入。改为首次与每 4 次打印，间隔越长打印越少，
                //   排障信息仍在（首条完整保留）。
                if (running && (consecutiveFailures == 1 || consecutiveFailures % 4 == 0)) {
                    Log.w(TAG, "连接中继失败(第${consecutiveFailures}次): ${e.message}")
                }
            }
            socket = null
            if (running) onDisconnected()
            if (running) {
                try {
                    Thread.sleep(nextRetryDelayMs(consecutiveFailures))
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun receiveLoop(s: Socket) {
        val input = try {
            s.getInputStream()
        } catch (e: IOException) {
            return
        }
        val streamBuffer = StreamBuffer()
        val tmp = ByteArray(4096)
        try {
            while (running && !s.isClosed) {
                val n = input.read(tmp)
                if (n < 0) break
                streamBuffer.append(tmp.copyOf(n))
                while (true) {
                    val frame = streamBuffer.readFrame() ?: break
                    if (frame.size <= 4) continue
                    val (cmd, payload) = RelayCommands.parse(frame.copyOfRange(4, frame.size))
                    if (cmd.isNotEmpty()) {
                        try {
                            onCommand(cmd, payload)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理命令异常: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "接收中断: ${e.message}")
        }
    }

    /**
     * 发送命令响应（★ 网络写入在后台发送线程执行，禁止主线程直发）
     * @param cmd 12字节命令前缀
     * @param payload 负载（UTF-8）
     */
    override fun send(cmd: String, payload: ByteArray) {
        val s = socket ?: return
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        // ★ WebRTC/大数据命令额外打印帧长度
        if (cmd.startsWith("wrx") || frame.size > 1024) {
            Log.i(TAG, "★ [SEND FRAME] cmd=$cmd payloadBytes=${payload.size} frameBytes=${frame.size} socket=${s.isConnected && !s.isClosed}")
        }
        // ★ 心跳类命令走独立心跳线程池，不被下载大流量堵队列
        val executor = if (isHeartBeatCmd(cmd)) heartBeatExecutor else sendExecutor
        try {
            executor.execute {
                try {
                    synchronized(sendLock) {
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                        if (cmd.startsWith("wrx")) {
                            Log.i(TAG, "★ [SEND FRAME OK] cmd=$cmd payloadBytes=${payload.size} 已写入socket已flush ✓")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭socket触发自动重连")
                    // ★ 发送失败说明连接已失效：关闭socket，connectLoop 检测到接收中断后自动重连
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
            if (cmd.startsWith("wrx")) {
                Log.i(TAG, "★ [SEND FRAME SUBMITTED] cmd=$cmd 已提交到 sendExecutor 等待执行 (executor=${executor.javaClass.simpleName})")
            }
        } catch (e: Exception) {
            Log.w(TAG, "提交发送任务失败: ${e.message}")
        }
    }

    /**
     * ★ 同步发送：等待数据实际写入socket并flush完成，返回是否成功
     * 参考PC端微信_download_send_file_blocked的"同步发送+失败立即终止"模式，
     * 用于文件下载等分块数据可靠发送场景，避免"异步提交成功但实际发送失败"的假活问题。
     */
    override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean {
        val s = socket ?: return false
        if (!isConnected()) return false
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        val future = try {
            // ★ 心跳走心跳池，其他走普通发送池；但同步调用统一使用sendExecutor保证顺序
            sendExecutor.submit<Boolean> {
                try {
                    synchronized(sendLock) {
                        if (!isConnected()) return@submit false
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                    }
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "同步发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭socket")
                    try { s.close() } catch (_: Exception) {}
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "提交同步发送任务失败: ${e.message}")
            return false
        }
        return try {
            val t0 = System.currentTimeMillis()
            val r = future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            // ★ 调试：记录同步发送耗时（写入文件，定位发送端卡顿）
            Log.i(TAG, "[FS调试] sendSync 完成 cmd=$cmd 负载${payload.size} 耗时${System.currentTimeMillis() - t0}ms 结果=$r")
            r
        } catch (e: Exception) {
            Log.w(TAG, "同步发送超时/中断: ${e.javaClass.simpleName}: ${e.message}")
            try { future.cancel(true) } catch (_: Exception) {}
            false
        }
    }

    override fun send(cmd: String, payload: String) {
        send(cmd, payload.toByteArray(Charsets.UTF_8))
    }
}
