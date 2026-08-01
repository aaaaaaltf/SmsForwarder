package cn.ppps.forwarder.fragment.client

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentClientCameraPreviewBinding
import cn.ppps.forwarder.relay.RelayClientHolder
import cn.ppps.forwarder.relay.RelayCommands
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar

/**
 * 控制端 - 被控端摄像头预览
 *
 * 1. 向被控端发送 vcdstr000000 启动摄像头流（被控端仅采集推流，不在其屏幕显示）
 * 2. 通过 ClientFragment 转发的 vcdfrm000000 二进制帧（负载: 索引|JPEG）实时显示
 * 3. 退出时发送 vcdstp000000 停止
 */
@Suppress("PrivatePropertyName")
@Page(name = "摄像头预览")
class CameraPreviewFragment : BaseFragment<FragmentClientCameraPreviewBinding?>() {

    private val TAG: String = CameraPreviewFragment::class.java.simpleName
    private val uiHandler = Handler(Looper.getMainLooper())

    private var pcId = -1
    private var currentBitmap: Bitmap? = null

    /** 当前摄像头索引：0=后置，1=前置 */
    private var cameraIndex = 0

    /** 切换/停止状态机：NONE / SWITCHING(已发STOP) / STARTING(已发START) / CLOSING */
    private var actionState = ACTION_NONE

    /** 切换目标摄像头索引 */
    private var pendingSwitchIndex = 0

    /** 切换超时兜底：防止被控端无响应时永久锁定切换按钮 */
    private var switchTimeoutRunnable: Runnable? = null

    /** 画面实时时钟 */
    private val timeRunnable = object : Runnable {
        override fun run() {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            binding?.tvCameraTime?.text = time
            uiHandler.postDelayed(this, 1000)
        }
    }

    /** 帧回调接口 */
    interface FrameListener {
        fun onCameraFrame(cameraIndex: Int, streamId: Long, jpeg: ByteArray)
        fun onCameraStatus(cameraIndex: Int, success: Boolean, detail: String)
    }

    companion object {
        const val ACTION_NONE = 0
        const val ACTION_SWITCH_STOPPING = 1   // 已发 STOP，等待被控端确认停止
        const val ACTION_SWITCH_STARTING = 2   // 已发 START(新索引)，等待新视频流

        /** ★ 上次已消费的流ID：跨页面实例保留，用于过滤中继缓冲的旧流残留帧（重开/切换后显示旧视频） */
        @Volatile
        private var lastStreamId = 0L

        @Volatile
        private var listener: FrameListener? = null

        /** 由 ClientFragment 收到 vcdfrm000000 帧时调用（后台线程） */
        fun dispatchCameraFrame(pcId: Int, payload: ByteArray) {
            val l = listener ?: return
            // 解析 "摄像头索引|流ID|" 前缀（兼容旧格式 "索引|JPEG"）
            var sep1 = -1
            var sep2 = -1
            for (i in payload.indices) {
                if (payload[i].toInt() == '|'.code) {
                    if (sep1 < 0) sep1 = i else { sep2 = i; break }
                }
            }
            var cameraIndex = 0
            var streamId = 0L
            val jpeg: ByteArray
            if (sep2 > 0) {
                try {
                    cameraIndex = String(payload, 0, sep1, Charsets.UTF_8).trim().toInt()
                } catch (e: Exception) {
                    cameraIndex = 0
                }
                try {
                    streamId = String(payload, sep1 + 1, sep2 - sep1 - 1, Charsets.UTF_8).trim().toLong()
                } catch (e: Exception) {
                    streamId = 0L
                }
                jpeg = payload.copyOfRange(sep2 + 1, payload.size)
            } else if (sep1 > 0) {
                try {
                    cameraIndex = String(payload, 0, sep1, Charsets.UTF_8).trim().toInt()
                } catch (e: Exception) {
                    cameraIndex = 0
                }
                jpeg = payload.copyOfRange(sep1 + 1, payload.size)
            } else {
                jpeg = payload
            }
            l.onCameraFrame(cameraIndex, streamId, jpeg)
        }

        /** 摄像头状态报告（文本负载: 索引|success/failed|详情） */
        fun dispatchCameraStatus(payload: String) {
            val l = listener ?: return
            val parts = payload.split("|", limit = 3)
            var index = 0
            var ok = false
            if (parts.size >= 2) {
                try {
                    index = parts[0].trim().toInt()
                } catch (e: Exception) {
                    index = 0
                }
                ok = parts[1].trim().equals("success", ignoreCase = true)
            }
            val detail = if (parts.size >= 3) parts[2] else ""
            l.onCameraStatus(index, ok, detail)
        }
    }

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentClientCameraPreviewBinding {
        return FragmentClientCameraPreviewBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        val titleBar = super.initTitle()!!.setImmersive(false)
        titleBar.setTitle(R.string.api_camera)
        return titleBar
    }

    override fun initViews() {
        pcId = RelayClientHolder.selectedPcId
        if (pcId < 0) {
            XToastUtils.error(R.string.relay_need_connect_and_select)
            popToBack()
            return
        }
        binding!!.btnCameraClose.setOnClickListener { popToBack() }
        binding!!.tvCameraStatus.text = String.format(getString(R.string.camera_preview_starting), pcId)
        // 画面实时时间
        uiHandler.post(timeRunnable)
        // 切换摄像头（前置/后置）
        binding!!.btnCameraSwitch.setOnClickListener {
            if (actionState == ACTION_NONE) switchCamera() else XToastUtils.toast(getString(R.string.camera_preview_switching))
        }
        // 截图保存
        binding!!.btnCameraScreenshot.setOnClickListener { saveScreenshot() }
        listener = object : FrameListener {
            override fun onCameraFrame(cameraIndex: Int, streamId: Long, jpeg: ByteArray) {
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
                uiHandler.post {
                    // ★ 丢弃旧流残留帧：streamId 不大于上次已消费的流ID → 中继缓冲的旧帧，直接丢弃
                    //   解决"重开/切换后显示关闭前的旧视频"
                    if (streamId <= lastStreamId) {
                        if (!bmp.isRecycled) bmp.recycle()
                        return@post
                    }
                    lastStreamId = streamId
                    currentBitmap?.let { if (!it.isRecycled) it.recycle() }
                    currentBitmap = bmp
                    binding?.ivCameraPreview?.setImageBitmap(bmp)
                    binding?.tvCameraTag?.text = cameraName(cameraIndex)
                    // ★ 新视频流已更新：解除切换锁定，并显示当前摄像头名称
                    if (actionState == ACTION_SWITCH_STARTING) {
                        if (cameraIndex != pendingSwitchIndex) {
                            // 帧索引与切换目标不符（旧流残留帧）：继续锁定，不解除
                            return@post
                        }
                        actionState = ACTION_NONE
                        switchTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
                        this@CameraPreviewFragment.cameraIndex = cameraIndex
                        binding?.btnCameraSwitch?.isEnabled = true
                        binding?.tvCameraStatus?.setText(
                            String.format(getString(R.string.camera_preview_current), cameraName(cameraIndex))
                        )
                    } else if (actionState == ACTION_NONE) {
                        binding?.tvCameraStatus?.setText(
                            String.format(getString(R.string.camera_preview_current), cameraName(cameraIndex))
                        )
                    }
                }
            }

            override fun onCameraStatus(cameraIndex: Int, success: Boolean, detail: String) {
                uiHandler.post {
                    when (actionState) {
                        ACTION_SWITCH_STOPPING -> {
                            // ★ 被控端确认已停止 → 立即发送新索引启动命令
                            actionState = ACTION_SWITCH_STARTING
                            val client = RelayClientHolder.client
                            if (client != null && client.isConnected()) {
                                client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, pendingSwitchIndex.toString().toByteArray(Charsets.UTF_8))
                            } else {
                                actionState = ACTION_NONE
                                binding?.btnCameraSwitch?.isEnabled = true
                                binding?.tvCameraStatus?.setText(getString(R.string.camera_preview_failed) + "中继已断开")
                            }
                        }

                        ACTION_SWITCH_STARTING -> {
                            // ★ 启动响应成功：保持锁定，等待新视频流更新后再解锁（用户要求：视频流未更新前禁止再次切换）
                            if (success) {
                                binding?.tvCameraStatus?.setText(
                                    String.format(getString(R.string.camera_preview_switching_to), cameraName(pendingSwitchIndex))
                                )
                            } else {
                                // 启动失败：解除锁定并提示
                                actionState = ACTION_NONE
                                switchTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
                                binding?.btnCameraSwitch?.isEnabled = true
                                binding?.tvCameraStatus?.setText(getString(R.string.camera_preview_failed) + detail)
                            }
                        }

                        else -> {
                            if (success && detail.contains("已启动")) {
                                binding?.tvCameraStatus?.setText(
                                    String.format(getString(R.string.camera_preview_current), cameraName(cameraIndex))
                                )
                            } else if (!success) {
                                binding?.tvCameraStatus?.setText(getString(R.string.camera_preview_failed) + detail)
                            }
                        }
                    }
                }
            }
        }
        startCameraStream()
    }

    /** 摄像头索引 → 名称 */
    private fun cameraName(index: Int): String =
        if (index == 0) getString(R.string.camera_preview_back) else getString(R.string.camera_preview_front)

    /** 发送启动摄像头流命令（负载=摄像头索引0） */
    private fun startCameraStream() {
        val client = RelayClientHolder.client
        if (client == null || !client.isConnected()) {
            XToastUtils.error(R.string.relay_need_connect_and_select)
            popToBack()
            return
        }
        // ★ 清空旧画面：避免重新打开时残留上一次关闭前的画面
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        binding?.ivCameraPreview?.setImageBitmap(null)
        binding?.tvCameraTag?.text = cameraName(cameraIndex)
        binding!!.tvCameraStatus.text = String.format(getString(R.string.camera_preview_starting), pcId)
        client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, cameraIndex.toString().toByteArray(Charsets.UTF_8))
        // ★ 启动超时兜底：10秒内未收到任何帧/状态则提示失败并解除（连接可能已失效）
        switchTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        switchTimeoutRunnable = Runnable {
            if (actionState == ACTION_NONE && currentBitmap == null) {
                binding?.tvCameraStatus?.setText(getString(R.string.camera_preview_failed) + "未收到视频流，请检查被控端连接后重试")
            }
        }
        uiHandler.postDelayed(switchTimeoutRunnable!!, 10000)
    }

    /** 切换摄像头（0后置 ↔ 1前置）：
     * 先通过命令通道通知被控端立即停止当前视频流，等被控端确认停止后，再启动新索引。
     * 切换期间禁止再次切换，直到新视频流更新才解除。 */
    private fun switchCamera() {
        val client = RelayClientHolder.client
        if (client == null || !client.isConnected()) {
            XToastUtils.error(R.string.relay_need_connect_and_select)
            return
        }
        val newIndex = 1 - cameraIndex
        pendingSwitchIndex = newIndex
        actionState = ACTION_SWITCH_STOPPING
        binding!!.btnCameraSwitch.isEnabled = false
        binding!!.tvCameraStatus.text =
            String.format(getString(R.string.camera_preview_switching_to), cameraName(newIndex))
        // 清空旧画面，避免切换期间显示旧流
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        binding?.ivCameraPreview?.setImageBitmap(null)
        // ★ 切换超时兜底（8秒）：优先自动补发新索引START（应对STOP确认丢失），其次解除锁定
        switchTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        val timeoutTask = object : Runnable {
            override fun run() {
                if (actionState == ACTION_SWITCH_STOPPING) {
                    // STOP确认丢失（被控端可能已停止）：直接补发新索引START，被控端会启动新摄像头
                    actionState = ACTION_SWITCH_STARTING
                    val client = RelayClientHolder.client
                    if (client != null && client.isConnected()) {
                        client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_START, pendingSwitchIndex.toString().toByteArray(Charsets.UTF_8))
                        binding?.tvCameraStatus?.setText(
                            String.format(getString(R.string.camera_preview_switching_to), cameraName(pendingSwitchIndex))
                        )
                        uiHandler.postDelayed(this, 8000)
                        return
                    }
                }
                if (actionState != ACTION_NONE) {
                    actionState = ACTION_NONE
                    binding?.btnCameraSwitch?.isEnabled = true
                    binding?.tvCameraStatus?.setText(getString(R.string.camera_preview_failed) + "切换超时，请重试")
                }
            }
        }
        switchTimeoutRunnable = timeoutTask
        uiHandler.postDelayed(timeoutTask, 8000)
        // ★ 命令通道立即通知被控端停止发送当前视频流
        client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_STOP, cameraIndex.toString().toByteArray(Charsets.UTF_8))
    }

    /** 截图保存当前预览画面，保存后自动打开 */
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
        // ★ 通过命令通道通知被控端停止发送视频流（携带当前摄像头索引）
        try {
            val client = RelayClientHolder.client
            if (client != null && client.isConnected() && pcId >= 0) {
                client.send(pcId, RelayCommands.CMD_CAMERA_STREAM_STOP, cameraIndex.toString().toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "发送停止命令失败: ${e.message}")
        }
        listener = null
        uiHandler.removeCallbacks(timeRunnable)
        switchTimeoutRunnable?.let { uiHandler.removeCallbacks(it) }
        switchTimeoutRunnable = null
        currentBitmap?.let { if (!it.isRecycled) it.recycle() }
        currentBitmap = null
        super.onDestroy()
    }
}
