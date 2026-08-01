package cn.ppps.forwarder.relay

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 控制端中继客户端
 * 主动连接中继服务 56787 端口，连接成功后发送1字节类型标识(0x01=手机控制端)，
 * 支持多被控端(pc_id)复用同一连接。
 *
 * 发送帧: [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 * 接收帧: [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 * pc_id = 0xFFFFFFFF 时为中继系统广播（online000000 / discon000000）
 */
class RelayControllerClient(
    private val host: String,
    private val port: Int,
    private val listener: Listener,
) {
    interface Listener {
        /** 中继连接成功 */
        fun onConnected()

        /** 中继连接断开 */
        fun onDisconnected()

        /** 被控端上线：pc_id -> ip */
        fun onDeviceOnline(pcId: Int, ip: String)

        /** 被控端下线 */
        fun onDeviceOffline(pcId: Int)

        /** 收到被控端命令响应 */
        fun onCommand(pcId: Int, cmd: String, payload: ByteArray)
    }

    private val TAG = "RelayControllerClient"
    private val sendLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, PendingRequest>()

    /** ★ 专用发送线程：所有socket写操作必须在后台线程执行，禁止在主线程发送（否则抛 NetworkOnMainThreadException） */
    private val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RelayCtrlSend").apply { isDaemon = true }
    }

    /** 请求超时时间（毫秒） */
    private val REQUEST_TIMEOUT = 30000L

    private class PendingRequest(
        val callback: (String) -> Unit,
        val timeoutRunnable: Runnable,
    )

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
        thread = Thread({ connectLoop() }, "RelayControllerConnect").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pending.keys.forEach { key ->
            pending.remove(key)?.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        }
        pending.clear()
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
                Log.i(TAG, "已连接中继服务 $host:$port socket=${s.hashCode()}")
                // 发送类型标识：0x01 = 手机控制端
                try {
                    s.getOutputStream().write(byteArrayOf(RelayCommands.CTRL_TYPE_PHONE))
                    s.getOutputStream().flush()
                } catch (e: IOException) {
                    Log.w(TAG, "发送类型标识失败: ${e.message}")
                    throw e
                }
                mainHandler.post { listener.onConnected() }
                receiveLoop(s)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "连接中继失败: ${e.message}")
            }
            socket = null
            mainHandler.post { listener.onDisconnected() }
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
                    if (frame.size < 8) continue
                    val pcId = FrameCodec.readInt(frame, 4)
                    val body = frame.copyOfRange(8, frame.size)
                    val (cmd, payload) = RelayCommands.parse(body)
                    if (cmd.isEmpty()) continue
                    if (pcId == RelayCommands.BROADCAST_PC_ID) {
                        handleBroadcast(cmd, payload)
                    } else {
                        try {
                            listener.onCommand(pcId, cmd, payload)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理响应异常: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: IOException) {
            if (running) Log.w(TAG, "接收中断: ${e.message}")
        }
    }

    private fun handleBroadcast(cmd: String, payload: ByteArray) {
        // 中继服务端(python)构造广播时用 GBK 编码（payload.encode('gbk')），必须用 GBK 解码还原文本
        val text = String(payload, Charset.forName("GBK"))
        when (cmd) {
            RelayCommands.CMD_CLIENT_ONLINE -> {
                val pcId = RelayCommands.parseBroadcastPcId(text)
                val ip = text.substringAfter('|', "").trim()
                if (pcId >= 0) {
                    mainHandler.post { listener.onDeviceOnline(pcId, ip) }
                }
            }

            RelayCommands.CMD_CLIENT_DISCONNECT -> {
                val pcId = RelayCommands.parseBroadcastPcId(text)
                if (pcId >= 0) {
                    mainHandler.post { listener.onDeviceOffline(pcId) }
                }
            }
        }
    }

    /**
     * 发送命令到指定被控端（★ 网络写入在后台发送线程执行，禁止主线程直发）
     * @param pcId 被控端id
     * @param cmd 12字节命令前缀
     * @param payload 负载字节
     */
    fun send(pcId: Int, cmd: String, payload: ByteArray) {
        val s = socket ?: return
        val frame = FrameCodec.encodeWithPcId(pcId, cmd.toByteArray(Charsets.US_ASCII) + payload)
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
                    // ★ 发送失败说明连接已失效：关闭socket，isConnected()立即返回false，
                    //    connectLoop 检测到接收中断后自动重连，避免"以为已连接实际已断开"
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

    /**
     * 发送请求并注册响应回调
     * @param rspCmd 期望的响应命令前缀，用于匹配返回结果
     * @param onResult 收到匹配响应时回调（负载文本）
     * @param onError 超时或未连接时回调
     * @param timeoutMs 超时时间（毫秒），默认30秒
     */
    fun request(pcId: Int, cmd: String, payload: String, rspCmd: String, onResult: (String) -> Unit, onError: (String) -> Unit, timeoutMs: Long = REQUEST_TIMEOUT) {
        if (!isConnected()) {
            onError("未连接中继服务器")
            return
        }
        // 同一响应命令只保留最新请求
        pending.remove(rspCmd)?.timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            pending.remove(rspCmd)
            onError("请求超时，请检查被控端是否在线")
        }
        pending[rspCmd] = PendingRequest(onResult, timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
        send(pcId, cmd, payload.toByteArray(Charsets.UTF_8))
    }

    /** 由接收循环调用：匹配已注册的响应回调 */
    fun dispatchResponse(cmd: String, payload: ByteArray) {
        val req = pending.remove(cmd) ?: return
        mainHandler.removeCallbacks(req.timeoutRunnable)
        mainHandler.post {
            try {
                req.callback(String(payload, Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "响应回调异常: ${e.message}")
            }
        }
    }
}
