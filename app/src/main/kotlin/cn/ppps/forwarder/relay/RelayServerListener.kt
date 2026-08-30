package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 被控端直连监听器（Tailscale 直连模式，无需中继云服务，2026-08-04 移植自PC被控端直连逻辑）
 *
 * 监听 0.0.0.0:56786，接受手机控制端直接 TCP 连接。
 * ★ 2026-08-05 多连接改造：acceptLoop 不再阻塞（原实现 receiveLoop 阻塞在 accept 循环内，
 *   单个控制端连接占住后，其他控制端（TS/局域网）连接只完成 TCP 握手、永不 accept，命令无响应）。
 *   现在每连接独立线程处理，命令响应按来源连接回发（sendTo），设备状态/视频帧单播给最新连接（send）。
 * 帧格式与 RelayServerClient 完全一致：
 *   [4字节大端长度][12字节命令][负载]
 */
class RelayServerListener(
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onCommand: (connId: Long, cmd: String, payload: ByteArray) -> Unit,
    /** ★ 新控制端连接接入即回调（connId）：用于主动向这条新连接发一次凭证 HELLO，
     *  否则它会被其它连接占着的节流窗口永久抢不到发送机会。 */
    private val onNewConnection: ((connId: Long) -> Unit)? = null,
) : RelaySender {
    private val TAG = "RelayServerListener"
    private val sendLock = Any()
    /** 发送失败日志限流（10秒内只打一次，避免断连时大量刷屏） */
    private var lastSendErrorLog = 0L

    /** ★ 下载/普通数据发送线程池（单线程保证顺序） */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelaySrvSend").apply { isDaemon = true }
    }

    /** ★ 心跳专用发送线程池（独立通道，不被下载大流量堵队列） */
    private val heartBeatExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelaySrvHb").apply { isDaemon = true }
    }

    /** 判断是否为心跳类命令（设备状态/心跳ping），走独立发送通道 */
    private fun isHeartBeatCmd(cmd: String): Boolean {
        return cmd == RelayCommands.CMD_DEV_STATE || cmd == RelayCommands.RSP_PONG
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    /** ★ 多连接：connId → Socket（每连接独立线程处理）；internal供RelayServerService动态解析connId使用 */
    internal val connections = ConcurrentHashMap<Long, Socket>()
    private val connIdGen = AtomicLong(0)

    @Volatile
    private var running = false
    private var acceptThread: Thread? = null

    override fun isConnected(): Boolean {
        return connections.values.any { s ->
            s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
        }
    }

    /** 是否有控制端已接入 */
    fun hasController(): Boolean = isConnected()

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread({ acceptLoop() }, "RelayServerAccept").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        connections.values.forEach {
            try {
                it.close()
            } catch (_: Exception) {
            }
        }
        connections.clear()
        sendExecutor.shutdownNow()
        heartBeatExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            Log.i(TAG, "被控端已监听端口 $port（Tailscale直连模式），等待控制端接入...")
            while (running && !ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept 中断: ${e.message}")
                    break
                }
                s.tcpNoDelay = true
                val id = connIdGen.incrementAndGet()
                connections[id] = s
                Log.i(TAG, "控制端已接入#$id: ${s.inetAddress.hostAddress}:$port")
                onConnected()
                // ★ 每连接独立线程处理，acceptLoop 立即返回继续接受新连接（多控制端并发）
                Thread({ handleConnection(id, s) }, "RelaySrvConn-$id").apply {
                    isDaemon = true
                    start()
                }
                // ★ 新对端接入 → 立刻给它一次主动申请凭证下发的机会（异常绝不打断接受循环）
                runCatching { onNewConnection?.invoke(id) }
                    .onFailure { Log.w(TAG, "新连接回调异常#$id: ${it.javaClass.simpleName}") }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "监听异常: ${e.message}")
        } finally {
            running = false
        }
    }

    private fun handleConnection(id: Long, s: Socket) {
        val input = try {
            s.getInputStream()
        } catch (e: IOException) {
            connections.remove(id)
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
                            onCommand(id, cmd, payload)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理命令异常: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "接收中断#$id: ${e.message}")
        } finally {
            connections.remove(id)
            try {
                s.close()
            } catch (_: Exception) {
            }
            if (running) onDisconnected()
        }
    }

    /** 发送给最新接入的连接（视频帧/心跳等单播用途，预览控制端通常为最新连接） */
    override fun send(cmd: String, payload: ByteArray) {
        val s = latestConnection() ?: return
        enqueueSend(s, cmd, payload)
    }

    /** ★ 按来源连接回发命令响应（多控制端时保证响应发回正确的控制端） */
    fun sendTo(connId: Long, cmd: String, payload: ByteArray) {
        val s = connections[connId] ?: return
        enqueueSend(s, cmd, payload)
    }

    /** ★ 同步发送（按connId指定连接），等待实际写入完成返回是否成功，供文件下载可靠发送使用 */
    fun sendToSync(connId: Long, cmd: String, payload: ByteArray, timeoutMs: Long = 15000): Boolean {
        val s = connections[connId] ?: return false
        if (s.isClosed || !s.isConnected) return false
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        val future = try {
            sendExecutor.submit<Boolean> {
                try {
                    synchronized(sendLock) {
                        if (s.isClosed || !s.isConnected) return@submit false
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                    }
                    true
                } catch (e: Exception) {
                    val now = System.currentTimeMillis()
                    if (now - lastSendErrorLog > 10000) {
                        lastSendErrorLog = now
                        Log.w(TAG, "同步发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭连接")
                    }
                    try { s.close() } catch (_: Exception) {}
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "提交同步发送任务失败: ${e.message}")
            return false
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "同步发送超时/中断: ${e.javaClass.simpleName}: ${e.message}")
            try { future.cancel(true) } catch (_: Exception) {}
            false
        }
    }

    fun sendTo(connId: Long, cmd: String, payload: String) {
        sendTo(connId, cmd, payload.toByteArray(Charsets.UTF_8))
    }

    /** ★ 广播给所有已接入控制端（设备状态上报等）：心跳走独立池，避免下载堵队列 */
    fun broadcast(cmd: String, payload: ByteArray) {
        for (s in connections.values) {
            enqueueSend(s, cmd, payload)
        }
    }

    fun broadcast(cmd: String, payload: String) {
        broadcast(cmd, payload.toByteArray(Charsets.UTF_8))
    }

    private fun latestConnection(): Socket? {
        var best: Socket? = null
        var bestId = Long.MIN_VALUE
        for ((id, s) in connections) {
            if (id > bestId && s.isConnected && !s.isClosed) {
                bestId = id
                best = s
            }
        }
        return best
    }

    private fun enqueueSend(s: Socket, cmd: String, payload: ByteArray) {
        if (s.isClosed || !s.isConnected) return
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        // ★ 心跳类命令走独立心跳线程池，不被下载大流量堵队列
        val executor = if (isHeartBeatCmd(cmd)) heartBeatExecutor else sendExecutor
        try {
            executor.execute {
                // ★ 发送前校验：连接可能已被关闭（断连后积压任务对旧socket发送会报错）
                if (s.isClosed || !s.isConnected) return@execute
                try {
                    synchronized(sendLock) {
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                    }
                } catch (e: Exception) {
                    // ★ 日志限流：断连瞬间大量积压任务会连续报错，10秒内仅记录一次
                    val now = System.currentTimeMillis()
                    if (now - lastSendErrorLog > 10000) {
                        lastSendErrorLog = now
                        Log.w(TAG, "发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭连接")
                    }
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "提交发送任务失败: ${e.message}")
        }
    }

    override fun send(cmd: String, payload: String) {
        send(cmd, payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * ★ 同步发送：等待数据实际写入socket并flush完成，返回是否成功
     * 对Listener来说，sendSync用于无connId场景（一般不会用到，直连下载都用sendToSync），
     * 这里复用latestConnection的同步发送逻辑。
     */
    override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean {
        val s = latestConnection() ?: return false
        if (s.isClosed || !s.isConnected) return false
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        val future = try {
            sendExecutor.submit<Boolean> {
                try {
                    synchronized(sendLock) {
                        if (s.isClosed || !s.isConnected) return@submit false
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                    }
                    true
                } catch (e: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    false
                }
            }
        } catch (e: Exception) {
            return false
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            try { future.cancel(true) } catch (_: Exception) {}
            false
        }
    }
}
