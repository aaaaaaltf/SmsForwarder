package cn.ppps.forwarder.relay

import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
) {
    private val TAG = "RelayServerClient"
    private val sendLock = Any()

    /** ★ 专用发送线程：所有socket写操作必须在后台线程执行，禁止主线程直发 */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayServerSend").apply { isDaemon = true }
    }

    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun isConnected(): Boolean {
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
    }

    private fun connectLoop() {
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
                Log.i(TAG, "已连接中继服务 $host:$port")
                onConnected()
                receiveLoop(s)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "连接中继失败: ${e.message}")
            }
            socket = null
            if (running) onDisconnected()
            if (running) {
                try {
                    Thread.sleep(5000)
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
    fun send(cmd: String, payload: ByteArray) {
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
                    Log.w(TAG, "发送失败: ${e.javaClass.simpleName}: ${e.message}，关闭socket触发自动重连")
                    // ★ 发送失败说明连接已失效：关闭socket，connectLoop 检测到接收中断后自动重连
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

    fun send(cmd: String, payload: String) {
        send(cmd, payload.toByteArray(Charsets.UTF_8))
    }
}
