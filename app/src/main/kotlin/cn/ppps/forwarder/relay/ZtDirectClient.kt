package cn.ppps.forwarder.relay

import cn.ppps.forwarder.utils.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * ★ ZeroTier直连客户端（被控端侧，2026-08-04新增）
 *
 * 主动连接手机控制端的 ZT IP:56789（DirectHostServer，参照PC被控端↔PC控制端逻辑）。
 * 用于中继服务关闭时，被控端仍可通过 ZeroTier 直连接收控制端命令。
 *
 * 帧格式与中继一致: [4字节大端长度][12字节命令][负载]
 * 命令处理复用 RelayServerHandler，响应通过本直连通道回传。
 */
class ZtDirectClient(
    private val host: String,
    private val port: Int,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onCommand: (cmd: String, payload: ByteArray) -> Unit,
) : RelaySender {
    private val TAG = "ZtDirectClient"
    private val sendLock = Any()

    /** ★ 专用发送线程：所有socket写操作必须在后台线程执行，禁止主线程直发 */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZtDirectSend").apply { isDaemon = true }
    }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** ★ 最多重连次数：触发后保持一段时间窗口（每5秒1次），连接建立后重置 */
    private val maxReconnect = 20

    override fun isConnected(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed && !s.isInputShutdown && !s.isOutputShutdown
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ connectLoop() }, "ZtDirectConnect").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        sendExecutor.shutdownNow()
    }

    private fun connectLoop() {
        var retries = 0
        while (running && retries < maxReconnect) {
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
                retries = 0
                Log.i(TAG, "★ ZT直连成功: $host:$port")
                onConnected()
                receiveLoop(s)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "ZT直连失败(${e.message})，剩余重试=${maxReconnect - retries - 1}")
            }
            socket = null
            if (running) onDisconnected()
            retries++
            if (running && retries < maxReconnect) {
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                }
            }
        }
        if (running) Log.w(TAG, "ZT直连停止重试（已达最大次数）")
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
            if (running) Log.w(TAG, "ZT直连接收中断: ${e.message}")
        }
    }

    /**
     * 发送命令响应（网络写入在后台发送线程执行）
     * @param cmd 12字节命令前缀
     * @param payload 负载（UTF-8）
     */
    override fun send(cmd: String, payload: ByteArray) {
        val s = socket ?: return
        val frame = FrameCodec.encode(cmd.toByteArray(Charsets.US_ASCII) + payload)
        try {
            sendExecutor.execute {
                try {
                    synchronized(sendLock) {
                        val out = s.getOutputStream()
                        out.write(frame)
                        out.flush()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ZT直连发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭socket触发重连")
                    try {
                        s.close()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "提交ZT直连发送任务失败: ${e.message}")
        }
    }

    override fun send(cmd: String, payload: String) {
        send(cmd, payload.toByteArray(Charsets.UTF_8))
    }
}
