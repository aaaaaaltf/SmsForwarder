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
    /** 发送失败日志限流（10秒内只打一次，避免断连时大量刷屏） */
    private var lastSendErrorLog = 0L

    /**
     * ★ 2026-09-04 每条控制端连接一条独立发送通道（数据池 + 心跳池 + 本连接锁）。
     *
     * 原实现所有连接共用一个 sendExecutor 和一个全局 sendLock，后果：
     *   1) 任一连接卡写（对端掉线留下的半开socket会阻塞到TCP超时才报错）就把其它连接的
     *      写出全部排在后面 —— 正在进行的文件下载/摄像头帧被队头阻塞；
     *   2) sendToSync 超时用 future.cancel(true) 中断"此刻正在执行的那条写入"，而那往往
     *      属于另一台设备的任务，其 catch 里 close 掉的是那台设备的socket。
     * 于是"新控制端接入"或"某控制端断开"会把无关设备正在跑的下载/预览一起打断。
     * 现在按连接隔离：阻塞与取消都只影响本连接。
     */
    private inner class ConnChannel(val connId: Long, val socket: Socket) {
        val sendLock = Any()
        val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RelaySrvSend-$connId").apply { isDaemon = true }
        }
        val heartBeatExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "RelaySrvHb-$connId").apply { isDaemon = true }
        }

        fun shutdown() {
            // 只停本连接的发送线程；socket由 handleConnection 的 finally 负责关闭
            sendExecutor.shutdownNow()
            heartBeatExecutor.shutdownNow()
        }

        private fun alive(): Boolean = !socket.isClosed && socket.isConnected

        private fun encode(cmd: String, payload: ByteArray): ByteArray =
            FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)

        /** 异步写入本连接（心跳走独立线程池，不被下载大流量堵队列） */
        fun submit(cmd: String, payload: ByteArray) {
            if (!alive()) return
            val frame = encode(cmd, payload)
            val executor = if (isHeartBeatCmd(cmd)) heartBeatExecutor else sendExecutor
            try {
                executor.execute {
                    if (!alive()) return@execute
                    try {
                        synchronized(sendLock) {
                            val out = socket.getOutputStream()
                            out.write(frame)
                            out.flush()
                        }
                    } catch (e: Exception) {
                        // 日志限流：断连瞬间积压任务会连续报错，10秒内仅记录一次
                        logSendError("发送失败#$connId，关闭本连接", e)
                        closeQuietly()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "提交发送任务失败#$connId: ${e.message}")
            }
        }

        /**
         * 同步写入本连接，等待实际flush完成。
         * ★ 超时只关闭【本连接】并放弃任务：绝不 cancel(true) —— 中断会打断此刻正在执行的写入，
         *   让另一条在途数据带着 SocketException 失败并 close 掉一个本来健康的socket。
         */
        fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean {
            if (!alive()) return false
            val frame = encode(cmd, payload)
            val future = try {
                sendExecutor.submit<Boolean> {
                    try {
                        synchronized(sendLock) {
                            if (!alive()) return@submit false
                            val out = socket.getOutputStream()
                            out.write(frame)
                            out.flush()
                        }
                        true
                    } catch (e: Exception) {
                        logSendError("同步发送失败#$connId，关闭本连接", e)
                        closeQuietly()
                        false
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "提交同步发送任务失败#$connId: ${e.message}")
                return false
            }
            return try {
                future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "同步发送超时#$connId: ${e.javaClass.simpleName}: ${e.message}，关闭本连接")
                try {
                    future.cancel(false)
                } catch (_: Exception) {
                }
                closeQuietly()
                false
            }
        }

        private fun closeQuietly() {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    /** connId → 该连接的发送通道（与 connections 同步创建/销毁） */
    private val channels = ConcurrentHashMap<Long, ConnChannel>()

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
        for (ch in channels.values) {
            try {
                ch.shutdown()
            } catch (_: Exception) {
            }
        }
        channels.clear()
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            // ★ 1 秒读超时：让接受循环能周期性回看 running/isClosed，停止时不必等下一个连接
            ss.soTimeout = 1000
            serverSocket = ss
            Log.i(TAG, "被控端已监听端口 $port（Tailscale直连模式），等待控制端接入...")
            while (running && !ss.isClosed) {
                val s = try {
                    ss.accept()
                } catch (e: java.net.SocketTimeoutException) {
                    // 只是这一轮没接人，绝不能退出接受循环
                    continue
                } catch (e: IOException) {
                    // ★ 以前任何 IOException 都 break + running=false → 监听器永久死亡，而
                    //   startRelay() 因 isRunning 仍为 true 不会重启：控制端 connect() 成功但
                    //   永远无人 accept（与控制端 DirectHostServer 修过的事故同源）。
                    //   现在：socket 已关或主动停止才退出；瞬时错误记日志后继续接受。
                    if (running && !ss.isClosed) {
                        Log.w(TAG, "accept 瞬时异常，继续接受: ${e.message}")
                        continue
                    }
                    if (running) Log.w(TAG, "accept 中断: ${e.message}")
                    break
                }
                s.tcpNoDelay = true
                val id = connIdGen.incrementAndGet()
                connections[id] = s
                channels[id] = ConnChannel(id, s)
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
            channels.remove(id)?.shutdown()
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
            channels.remove(id)?.shutdown()
            try {
                s.close()
            } catch (_: Exception) {
            }
            if (running) onDisconnected()
        }
    }

    /** 发送给最新接入的连接（视频帧/心跳等单播用途，预览控制端通常为最新连接） */
    override fun send(cmd: String, payload: ByteArray) {
        latestChannel()?.submit(cmd, payload)
    }

    /** ★ 按来源连接回发命令响应（多控制端时保证响应发回正确的控制端） */
    fun sendTo(connId: Long, cmd: String, payload: ByteArray) {
        // 通道随连接销毁：拿不到就是已经断开，静默丢弃
        channels[connId]?.submit(cmd, payload)
    }

    /** ★ 同步发送（按connId指定连接），等待实际写入完成返回是否成功，供文件下载可靠发送使用 */
    fun sendToSync(connId: Long, cmd: String, payload: ByteArray, timeoutMs: Long = 15000): Boolean {
        val ch = channels[connId] ?: return false
        return ch.sendSync(cmd, payload, timeoutMs)
    }

    fun sendTo(connId: Long, cmd: String, payload: String) {
        sendTo(connId, cmd, payload.toByteArray(Charsets.UTF_8))
    }

    /** ★ 广播给所有已接入控制端（设备状态上报等）：每条连接各走自己的发送通道 */
    fun broadcast(cmd: String, payload: ByteArray) {
        for (ch in channels.values) {
            ch.submit(cmd, payload)
        }
    }

    fun broadcast(cmd: String, payload: String) {
        broadcast(cmd, payload.toByteArray(Charsets.UTF_8))
    }

    private fun latestChannel(): ConnChannel? {
        var best: ConnChannel? = null
        var bestId = Long.MIN_VALUE
        for ((id, ch) in channels) {
            if (id > bestId && ch.socket.isConnected && !ch.socket.isClosed) {
                bestId = id
                best = ch
            }
        }
        return best
    }

    private fun logSendError(prefix: String, e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastSendErrorLog > 10000) {
            lastSendErrorLog = now
            Log.w(TAG, "$prefix: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    override fun send(cmd: String, payload: String) {
        send(cmd, payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * ★ 同步发送（无connId场景）：一般用不到，直连下载都走 sendToSync。
     * 复用最新连接的同步发送，超时只关本连接。
     */
    override fun sendSync(cmd: String, payload: ByteArray, timeoutMs: Long): Boolean {
        val ch = latestChannel() ?: return false
        return ch.sendSync(cmd, payload, timeoutMs)
    }
}
