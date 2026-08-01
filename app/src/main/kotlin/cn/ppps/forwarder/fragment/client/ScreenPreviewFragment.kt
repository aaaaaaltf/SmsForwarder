package cn.ppps.forwarder.fragment.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientScreenPreviewBinding
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 控制端 - 被控端屏幕预览（远程桌面）
 *
 * 1. 向被控端发送 rdstrt000000 启动屏幕推流（payload: 0|0|FPS|24|质量|pcId）
 * 2. 被控端以 PUSHER:pcId 推流到中继 56783 端口
 * 3. 本界面连接中继 56783 发送 LISTENER:pcId 配对，接收 [4字节大端JPEG长度][JPEG] 帧实时显示
 */
@Suppress("PrivatePropertyName")
@Page(name = "屏幕预览")
class ScreenPreviewFragment : BaseFragment<FragmentClientScreenPreviewBinding?>() {

    private val TAG: String = ScreenPreviewFragment::class.java.simpleName
    private val uiHandler = Handler(Looper.getMainLooper())

    private var pcId = -1
    @Volatile
    private var running = false
    private var videoSocket: Socket? = null
    private var connectThread: Thread? = null
    private var currentBitmap: Bitmap? = null

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientScreenPreviewBinding {
        return FragmentClientScreenPreviewBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_screen_preview)
        return titleBar
    }

    override fun initViews() {
        pcId = RelayClientHolder.selectedPcId
        if (pcId < 0) {
            XToastUtils.error(R.string.relay_need_connect_and_select)
            popToBack()
            return
        }
        binding!!.btnScreenClose.setOnClickListener { popToBack() }
        binding!!.btnScreenScreenshot.setOnClickListener { saveScreenshot() }
        binding!!.tvScreenStatus.text = String.format(getString(R.string.screen_preview_starting), pcId)
        startRemoteDesktop()
    }

    /** 发送启动命令并连接中继视频流端口 */
    private fun startRemoteDesktop() {
        val client = RelayClientHolder.client
        if (client == null || !client.isConnected()) {
            XToastUtils.error(R.string.relay_need_connect_and_select)
            popToBack()
            return
        }
        // 发送 rdstrt: 0|0|FPS|24|质量|pcId
        val payload = "0|0|15|24|50|$pcId"
        client.send(pcId, RelayCommands.CMD_RD_START, payload.toByteArray(Charsets.UTF_8))

        running = true
        connectThread = Thread({ connectLoop() }, "ScreenPreviewConnect").apply { isDaemon = true }.also { it.start() }
    }

    /** 连接循环：连接中继56788 → 发LISTENER认证 → 收流 */
    private fun connectLoop() {
        var retry = 0
        while (running && retry < 5) {
            var sock: Socket? = null
            try {
                sock = Socket()
                sock.tcpNoDelay = true
                sock.connect(InetSocketAddress(RelaySettings.relayHost, RelayCommands.RELAY_VIDEO_PORT), 8000)
                if (!running) {
                    try {
                        sock.close()
                    } catch (_: IOException) {
                    }
                    return
                }
                videoSocket = sock
                val out: OutputStream = sock.getOutputStream()
                // LISTENER 认证标签与 pcId 配对
                out.write("LISTENER:$pcId\n".toByteArray(Charsets.UTF_8))
                out.flush()
                uiHandler.post { binding?.tvScreenStatus?.setText(String.format(getString(R.string.screen_preview_connected), pcId)) }
                receiveFrames(sock)
                return
            } catch (e: Exception) {
                if (!running) return
                try {
                    sock?.close()
                } catch (_: IOException) {
                }
                retry++
                if (retry >= 5) {
                    val err = e.message
                    uiHandler.post { binding?.tvScreenStatus?.setText(getString(R.string.screen_preview_connect_failed) + err) }
                } else {
                    try {
                        Thread.sleep(2000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }
    }

    /** 接收 [4字节大端长度][JPEG] 帧 */
    private fun receiveFrames(sock: Socket) {
        try {
            val input: InputStream = sock.getInputStream()
            val header = ByteArray(4)
            while (running && !sock.isClosed) {
                if (readFully(input, header, 4) < 4) break
                // 4字节大端帧长度
                val len0 = header[0].toInt() and 0xFF
                val len1 = header[1].toInt() and 0xFF
                val len2 = header[2].toInt() and 0xFF
                val len3 = header[3].toInt() and 0xFF
                val frameLen = (len0 shl 24) or (len1 shl 16) or (len2 shl 8) or len3
                if (frameLen <= 0 || frameLen > 16 * 1024 * 1024) break
                val jpeg: ByteArray = ByteArray(frameLen)
                if (readFully(input, jpeg, frameLen) < frameLen) break
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                if (bmp != null) {
                    uiHandler.post { showFrame(bmp) }
                }
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "屏幕流中断: ${e.message}")
        } finally {
            uiHandler.post { binding?.tvScreenStatus?.setText(R.string.screen_preview_stopped) }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) return total
            total += n
        }
        return total
    }

    private fun showFrame(bmp: Bitmap) {
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = bmp
        binding?.ivScreenPreview?.setImageBitmap(bmp)
        binding?.tvScreenStatus?.setText(String.format(getString(R.string.screen_preview_live), pcId))
    }

    /** 截图保存当前屏幕预览画面，保存后自动打开 */
    private fun saveScreenshot() {
        val bmp = currentBitmap
        if (bmp == null || bmp.isRecycled) {
            XToastUtils.toast("当前无画面，无法截图")
            return
        }
        if (cn.ppps.forwarder.utils.ScreenshotUtils.saveAndOpen(requireContext(), bmp)) {
            XToastUtils.success(R.string.screenshot_saved)
        } else {
            XToastUtils.error("截图保存失败")
        }
    }

    override fun onDestroy() {
        running = false
        // 发送停止命令
        try {
            val client = RelayClientHolder.client
            if (client != null && client.isConnected() && pcId >= 0) {
                client.send(pcId, RelayCommands.CMD_RD_STOP, ByteArray(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送停止命令失败: ${e.message}")
        }
        try {
            videoSocket?.close()
        } catch (_: Exception) {
        }
        videoSocket = null
        try {
            connectThread?.join(2000)
        } catch (_: Exception) {
        }
        connectThread = null
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        super.onDestroy()
    }
}
