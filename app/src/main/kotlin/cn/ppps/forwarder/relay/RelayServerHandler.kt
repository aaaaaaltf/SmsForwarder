package cn.ppps.forwarder.relay

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.provider.ContactsContract
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.BatteryInfo
import cn.ppps.forwarder.entity.CallInfo
import cn.ppps.forwarder.entity.CloneInfo
import cn.ppps.forwarder.entity.ContactInfo
import cn.ppps.forwarder.entity.LocationInfo
import cn.ppps.forwarder.server.model.BaseResponse
import cn.ppps.forwarder.server.model.CallQueryData
import cn.ppps.forwarder.server.model.ConfigData
import cn.ppps.forwarder.server.model.ContactQueryData
import cn.ppps.forwarder.server.model.SmsQueryData
import cn.ppps.forwarder.server.model.WolData
import cn.ppps.forwarder.utils.AppUtils
import cn.ppps.forwarder.utils.BatteryUtils
import cn.ppps.forwarder.utils.HttpServerUtils
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.RelaySettings
import cn.ppps.forwarder.utils.SettingUtils
import com.xuexiang.xutil.XUtil
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale

/**
 * 被控端命令处理器
 * 根据12字节命令前缀分发到对应功能，返回统一 BaseResponse JSON 报文。
 */
object RelayServerHandler {
    private const val TAG = "RelayServerHandler"

    /** ★ 命令来源通道常量：供ScreenStreamManager等按通道决定推流模式 */
    const val CHANNEL_RELAY = 0    // 命令经中继连接到达（被控端→中继56786）
    const val CHANNEL_DIRECT = 1   // 命令经直连监听(56786)到达（控制端ZT/局域网直连）
    const val CHANNEL_ZT = 2       // 命令经ZT直连(56789)到达（被控端主动连接控制端）

    /** ★ ZT直连启动器（由RelayServerService设置，触发后主动连接控制端56789） */
    var ztDirectLauncher: ((phoneIp: String, port: Int) -> Unit)? = null

    /** ★ 本机ZeroTier IP缓存（60秒） */
    private var ownZtIpsCache: Set<String>? = null
    private var ownZtIpsCacheTime = 0L

    /** 获取本机ZeroTier IP（172.2x网段，与PC被控端逻辑一致） */
    fun getOwnZtIps(): Set<String> {
        val now = System.currentTimeMillis()
        if (ownZtIpsCache != null && now - ownZtIpsCacheTime < 60000) return ownZtIpsCache!!
        val ips = LinkedHashSet<String>()
        try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).forEach { ni ->
                java.util.Collections.list(ni.inetAddresses).forEach { addr ->
                    val ip = addr.hostAddress ?: return@forEach
                    if (!addr.isLoopbackAddress && ip.startsWith("172.2")) {
                        ips.add(ip)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取本机ZT IP失败: ${e.message}")
        }
        ownZtIpsCache = ips
        ownZtIpsCacheTime = now
        return ips
    }

    private fun success(data: Any?): String {
        val resp = BaseResponse<Any?>(
            code = 200,
            msg = "success",
            data = data,
            timestamp = System.currentTimeMillis(),
            sign = "",
        )
        return RelayClientHolder.gson.toJson(resp)
    }

    private fun error(msg: String): String {
        val resp = BaseResponse<Any?>(
            code = 500,
            msg = msg,
            data = null,
            timestamp = System.currentTimeMillis(),
            sign = "",
        )
        return RelayClientHolder.gson.toJson(resp)
    }

    /**
     * 处理收到的命令
     * @param channel 命令来源通道：CHANNEL_RELAY / CHANNEL_DIRECT / CHANNEL_ZT（供屏幕推流模式选择）
     * @param sender 命令来源通道的发送器（★ 2026-08-06新增：文件下载等二进制推送用，由RelayServerService传入）
     * @return (响应命令, 响应负载JSON)，未知命令返回 null
     */
    fun handle(cmd: String, payload: ByteArray, channel: Int = CHANNEL_RELAY,
               sender: RelaySender? = null): Pair<String, String>? {
        val payloadText = String(payload, Charsets.UTF_8)
        return try {
            when (cmd) {
                RelayCommands.CMD_GET_CONFIG -> RelayCommands.RSP_CONFIG to success(handleConfig())

                // ★ ZeroTier直连请求（中继在线时触发）：负载 "目标ZT IP|控制端ZT IP|端口"
                RelayCommands.CMD_ZT_DIRECT_CONNECT -> {
                    val parts = payloadText.split("|")
                    if (parts.size >= 3) {
                        val targetIp = parts[0].trim()
                        val phoneIp = parts[1].trim()
                        val port = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: RelayCommands.ZT_DIRECT_PORT
                        if (targetIp in getOwnZtIps()) {
                            Log.i(TAG, "★ 收到ZT直连请求，目标匹配本机，主动连接控制端 $phoneIp:$port")
                            ztDirectLauncher?.invoke(phoneIp, port)
                        } else {
                            Log.i(TAG, "ZT直连目标 $targetIp 不是本机 (本机: ${getOwnZtIps()})，忽略")
                        }
                    }
                    null  // 直连触发无需响应
                }

                RelayCommands.CMD_PING -> {
                    // ★ 心跳探测：响应 pong（同时保持中继链路活跃，防止NAT超时断连）
                    RelayCommands.RSP_PONG to "pong"
                }

                RelayCommands.CMD_BATTERY -> {
                    if (!HttpServerUtils.enableApiBatteryQuery) return RelayCommands.RSP_BATTERY to error("服务端已禁用该功能")
                    RelayCommands.RSP_BATTERY to success(handleBattery())
                }

                // ==================== 文件系统（2026-08-06新增） ====================
                RelayCommands.CMD_FS_LIST -> {
                    RelayCommands.RSP_FS_LIST to handleFsList(payloadText)
                }

                RelayCommands.CMD_FS_DELETE -> {
                    RelayCommands.RSP_FS_DELETE to handleFsDelete(payloadText)
                }

                RelayCommands.CMD_FS_GET -> {
                    // ★ 下载：启动后台线程推送（RSP_FS_GET → CMD_FS_DATA分块 → CMD_FS_DONE），此处不返回
                    startFsDownload(payloadText, sender)
                    null
                }

                RelayCommands.CMD_SMS_QUERY -> {
                    if (!HttpServerUtils.enableApiSmsQuery) return RelayCommands.RSP_SMS_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, SmsQueryData::class.java) ?: SmsQueryData()
                    val list = PhoneUtils.getSmsInfoList(data.type, data.pageSize, (data.pageNum - 1) * data.pageSize, data.keyword)
                    RelayCommands.RSP_SMS_QUERY to success(list)
                }

                RelayCommands.CMD_CALL_QUERY -> {
                    if (!HttpServerUtils.enableApiCallQuery) return RelayCommands.RSP_CALL_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, CallQueryData::class.java) ?: CallQueryData()
                    val list = PhoneUtils.getCallInfoList(data.type, data.pageSize, (data.pageNum - 1) * data.pageSize, data.phoneNumber)
                    RelayCommands.RSP_CALL_QUERY to success(list)
                }

                RelayCommands.CMD_CONTACT_QUERY -> {
                    if (!HttpServerUtils.enableApiContactQuery) return RelayCommands.RSP_CONTACT_QUERY to error("服务端已禁用该功能")
                    val data = parseData(payloadText, ContactQueryData::class.java) ?: ContactQueryData()
                    val list = PhoneUtils.getContactInfoList(data.pageSize, (data.pageNum - 1) * data.pageSize, data.phoneNumber, data.name)
                    RelayCommands.RSP_CONTACT_QUERY to success(list)
                }

                RelayCommands.CMD_CONTACT_ADD -> {
                    if (!HttpServerUtils.enableApiContactAdd) return RelayCommands.RSP_CONTACT_ADD to error("服务端已禁用该功能")
                    val data = parseData(payloadText, ContactInfo::class.java)
                    if (data == null || data.name.isNullOrEmpty() || data.phoneNumber.isNullOrEmpty()) {
                        RelayCommands.RSP_CONTACT_ADD to error("姓名或号码为空")
                    } else {
                        handleContactAdd(data)
                        RelayCommands.RSP_CONTACT_ADD to success("success")
                    }
                }

                RelayCommands.CMD_LOCATION -> {
                    if (!HttpServerUtils.enableApiLocation) return RelayCommands.RSP_LOCATION to error("服务端已禁用该功能")
                    RelayCommands.RSP_LOCATION to success(handleLocation())
                }

                RelayCommands.CMD_WOL -> {
                    if (!HttpServerUtils.enableApiWol) return RelayCommands.RSP_WOL to error("服务端已禁用该功能")
                    val data = parseData(payloadText, WolData::class.java)
                    if (data == null || data.mac.isNullOrEmpty()) {
                        RelayCommands.RSP_WOL to error("mac地址为空")
                    } else {
                        wakeOnLAN(data.mac, data.ip, if (data.port > 0) data.port else 9)
                        RelayCommands.RSP_WOL to success("success")
                    }
                }

                RelayCommands.CMD_CLONE_PULL -> {
                    if (!HttpServerUtils.enableApiClone) return RelayCommands.RSP_CLONE to error("服务端已禁用该功能")
                    val cloneInfo = parseData(payloadText, CloneInfo::class.java)
                    if (cloneInfo != null && cloneInfo.versionCode > 0) {
                        HttpServerUtils.compareVersion(cloneInfo)
                    }
                    RelayCommands.RSP_CLONE to success(HttpServerUtils.exportSettings())
                }

                RelayCommands.CMD_CLONE_PUSH -> {
                    if (!HttpServerUtils.enableApiClone) return RelayCommands.RSP_CLONE to error("服务端已禁用该功能")
                    val json = payloadText.removePrefix("push|")
                    val cloneInfo = parseData(json, CloneInfo::class.java)
                    if (cloneInfo == null) {
                        RelayCommands.RSP_CLONE to error("推送数据无效")
                    } else {
                        HttpServerUtils.compareVersion(cloneInfo)
                        val ok = HttpServerUtils.restoreSettings(cloneInfo)
                        RelayCommands.RSP_CLONE to if (ok) success("success") else error("还原设置失败")
                    }
                }

                RelayCommands.CMD_RD_START -> {
                    // 屏幕预览（远程桌面）：负载格式 host|port|FPS|色深|质量|clientId
                    val parts = payloadText.split("|")
                    val fps = parts.getOrNull(2)?.toIntOrNull() ?: 15
                    val quality = parts.getOrNull(4)?.toIntOrNull() ?: 50
                    val clientId = parts.getOrNull(5)?.toIntOrNull() ?: -1
                    // ★ 按命令来源通道决定推流模式（中继→PUSHER；直连/ZT→监听56788）
                    val ok = ScreenStreamManager.startStream(RelaySettings.relayHost, clientId, fps, quality, channel)
                    if (ok) {
                        RelayCommands.RSP_RD_START_ACK to RelayCommands.RELAY_VIDEO_PORT.toString()
                    } else {
                        RelayCommands.RSP_RD_START_ACK to error("屏幕捕获未授权，请先在被控端开启屏幕预览授权")
                    }
                }

                RelayCommands.CMD_RD_STOP -> {
                    ScreenStreamManager.stop()
                    RelayCommands.RSP_RD_START_ACK to success("success")
                }

                // ==================== 远程触摸操控（无障碍手势注入） ====================
                RelayCommands.CMD_RD_MOUSE_DOWN -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchDown(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_MOVE -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchMove(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_UP -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        TouchControlService.instance?.touchUp(nx, ny)
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_DBL -> {
                    touchUnavailable() ?: run {
                        val (nx, ny) = parseCoords(payloadText)
                        val svc = TouchControlService.instance
                        if (svc != null) {
                            val metrics = App.context.resources.displayMetrics
                            svc.tap(nx * metrics.widthPixels, ny * metrics.heightPixels)
                            Thread.sleep(50)
                            svc.tap(nx * metrics.widthPixels, ny * metrics.heightPixels)
                        }
                        null
                    }
                }

                RelayCommands.CMD_RD_MOUSE_WHEEL -> {
                    touchUnavailable() ?: run {
                        val delta = payloadText.trim().toIntOrNull() ?: 0
                        val svc = TouchControlService.instance
                        if (svc != null) {
                            val metrics = App.context.resources.displayMetrics
                            val cx = metrics.widthPixels / 2f
                            val cy = metrics.heightPixels / 2f
                            val dy = delta * 200
                            svc.swipe(cx, cy, cx, cy - dy, 300)
                        }
                        null
                    }
                }

                // ==================== 点亮屏幕解锁 ====================
                RelayCommands.CMD_WAKEUP_SCREEN -> {
                    // ★ 改用悬浮窗方案：MIUI/Android10+ 拦截后台启动Activity，悬浮窗可正常点亮
                    val ok = ScreenLighter.lightUp(App.context)
                    if (ok) {
                        Log.i(TAG, "已执行点亮屏幕")
                        // ★ 返回执行结果给控制端，让控制端显示成功/失败
                        RelayCommands.RSP_SCREEN_CTRL to "1|success|点亮屏幕成功"
                    } else {
                        RelayCommands.RSP_ERROR to error("被控端未授予悬浮窗权限（显示在其他应用上层），无法点亮屏幕，请在系统设置中开启")
                    }
                }

                // ==================== 熄灭屏幕 ====================
                RelayCommands.CMD_SCREEN_OFF -> {
                    // ★ 通过无障碍服务执行全局锁屏动作熄灭屏幕（等同电源键锁屏）
                    val svc = TouchControlService.instance
                    if (svc == null) {
                        RelayCommands.RSP_ERROR to error("被控端未开启远程触摸（无障碍）服务，请在系统设置-无障碍中开启后重试")
                    } else {
                        val ok = svc.screenOff()
                        if (ok) {
                            Log.i(TAG, "已执行熄屏")
                            // ★ 返回执行结果给控制端
                            RelayCommands.RSP_SCREEN_CTRL to "1|success|熄屏成功"
                        } else {
                            RelayCommands.RSP_ERROR to error("熄屏失败：系统版本过低或无障碍服务异常")
                        }
                    }
                }

                // ==================== 版本确认（PC协议兼容，控制端用于确认被控端在线） ====================
                RelayCommands.CMD_GET_VERSION -> {
                    val versionName = try { AppUtils.getAppVersionName() } catch (_: Exception) { "1.0" }
                    val deviceName = try {
                        (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
                    } catch (_: Exception) { "Android" }
                    // 负载格式与PC被控端一致: 版本|设备名|用户|isAdmin|isService（纯ASCII，编码安全）
                    RelayCommands.CMD_VERSION_INFO to "$versionName|$deviceName|Android|0|0"
                }

                RelayCommands.CMD_CAMERA_STREAM_START -> {
                    if (!HttpServerUtils.enableApiCamera) return RelayCommands.CMD_CAMERA_STATUS_REPORT to error("0|failed|服务端已禁用摄像头")
                    val index = payloadText.toIntOrNull() ?: 0
                    val ok = CameraStreamManager.start(index)
                    RelayCommands.CMD_CAMERA_STATUS_REPORT to if (ok) "$index|success|摄像头流已启动" else "$index|failed|${CameraStreamManager.lastError()}"
                }

                RelayCommands.CMD_CAMERA_STREAM_STOP -> {
                    CameraStreamManager.stop()
                    RelayCommands.CMD_CAMERA_STATUS_REPORT to "0|success|摄像头流已停止"
                }

                RelayCommands.CMD_SYS_STATUS -> {
                    // ★ 中继系统状态广播：更新手机控制端连接状态（不响应）
                    when (payloadText.trim()) {
                        RelayCommands.SYS_CTRL_ON -> cn.ppps.forwarder.service.RelayServerService.isControllerOnline = true
                        RelayCommands.SYS_CTRL_OFF -> cn.ppps.forwarder.service.RelayServerService.isControllerOnline = false
                    }
                    Log.i(TAG, "中继系统状态: $payloadText -> 控制端在线=${cn.ppps.forwarder.service.RelayServerService.isControllerOnline}")
                    null
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理命令异常: $cmd ${e.message}")
            RelayCommands.RSP_ERROR to error(e.message ?: "未知错误")
        }
    }

    private fun <T> parseData(json: String, clazz: Class<T>): T? {
        if (json.isEmpty()) return null
        return try {
            RelayClientHolder.gson.fromJson(json, clazz)
        } catch (e: Exception) {
            null
        }
    }

    /** 解析归一化坐标负载 "x|y"（0.0~1.0），非法返回 (0,0) */
    private fun parseCoords(text: String): Pair<Float, Float> {
        val parts = text.trim().split("|")
        if (parts.size < 2) return 0f to 0f
        val x = parts[0].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        val y = parts[1].toFloatOrNull()?.coerceIn(0f, 1f) ?: 0f
        return x to y
    }

    /**
     * 触摸服务可用性检查：无障碍服务未开启时返回错误响应（明确原因提示控制端）
     * @return null=服务可用；非null=错误响应(命令, JSON)
     */
    private fun touchUnavailable(): Pair<String, String>? {
        return if (TouchControlService.instance == null) {
            RelayCommands.RSP_ERROR to error("被控端未开启远程触摸（无障碍）服务，请在系统设置-无障碍中开启后重试")
        } else {
            null
        }
    }

    private fun handleConfig(): ConfigData {
        // 获取卡槽信息
        if (App.SimInfoList.isEmpty()) {
            App.SimInfoList = PhoneUtils.getSimMultiInfo()
        }
        return ConfigData(
            HttpServerUtils.enableApiClone,
            false,
            HttpServerUtils.enableApiSmsQuery,
            HttpServerUtils.enableApiCallQuery,
            HttpServerUtils.enableApiContactQuery,
            HttpServerUtils.enableApiContactAdd,
            HttpServerUtils.enableApiBatteryQuery,
            HttpServerUtils.enableApiWol,
            HttpServerUtils.enableApiLocation,
            SettingUtils.extraDeviceMark,
            SettingUtils.extraSim1,
            SettingUtils.extraSim2,
            App.SimInfoList,
            AppUtils.getAppVersionCode(),
            AppUtils.getAppVersionName(),
        )
    }

    private fun handleBattery(): BatteryInfo {
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? = App.context.registerReceiver(null, intentFilter)
        return BatteryUtils.getBatteryInfo(intent)
    }

    // ==================== 文件系统（2026-08-06新增） ====================

    /** 默认根目录：/storage/emulated/0（Download目录的父目录） */
    private val FS_DEFAULT_ROOT = "/storage/emulated/0"

    /** 图库目录：根目录顶部"图库"条目点击后直接进入的目录（手机相机相册，与系统相册一致） */
    private val FS_GALLERY_DIR = "/storage/emulated/0/DCIM/Camera"

    /**
     * 过滤系统/隐藏/无权限目录：隐藏目录(.开头)、Android系统目录、不可读目录
     * 根目录下不显示这些目录，避免用户误操作或看到无意义的系统目录
     */
    private fun isFsVisibleDir(f: File): Boolean {
        if (f.isHidden) return false
        if (f.name == "Android") return false
        return f.canRead()
    }

    /**
     * 列目录：负载=目录路径（空=默认根 /storage/emulated/0）
     * 根目录列表最前面插入"图库"特殊条目（点击直接进入图库目录）
     * @return JSON数组字符串 [{name,type,size,mtime,path},...]，type=D目录/F文件
     */
    private fun handleFsList(path: String): String {
        val dir = if (path.trim().isEmpty()) FS_DEFAULT_ROOT else path.trim()
        val file = File(dir)
        if (!file.exists() || !file.isDirectory) {
            return "[]"
        }
        val list = ArrayList<Map<String, Any>>()
        // ★ 根目录最上面加"图库"目录，点击直接进入图库目录
        if (file.absolutePath == FS_DEFAULT_ROOT) {
            list.add(linkedMapOf(
                "name" to "图库",
                "type" to "D",
                "size" to 0L,
                "mtime" to 0L,
                "path" to FS_GALLERY_DIR
            ))
        }
        try {
            val children = file.listFiles() ?: return fsJson(list)
            // 目录在前、文件在后，各自按名称排序（目录优先，与PC文件管理器一致）
            // 目录需过滤系统/隐藏/无权限目录
            children.filter { it.isDirectory && isFsVisibleDir(it) }.sortedBy { it.name.lowercase() }.forEach {
                list.add(linkedMapOf(
                    "name" to it.name,
                    "type" to "D",
                    "size" to 0L,
                    "mtime" to it.lastModified(),
                    "path" to it.absolutePath
                ))
            }
            children.filter { it.isFile }.sortedBy { it.name.lowercase() }.forEach {
                list.add(linkedMapOf(
                    "name" to it.name,
                    "type" to "F",
                    "size" to it.length(),
                    "mtime" to it.lastModified(),
                    "path" to it.absolutePath
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "列目录异常: $dir ${e.message}")
        }
        return fsJson(list)
    }

    private fun fsJson(list: List<Map<String, Any>>): String {
        return try {
            RelayClientHolder.gson.toJson(list)
        } catch (e: Exception) {
            "[]"
        }
    }

    /**
     * 删除文件/目录（目录递归删除）
     * @return "1|success|信息" 或 "0|failed|原因"
     */
    private fun handleFsDelete(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "0|failed|路径为空"
        val f = File(p)
        if (!f.exists()) return "0|failed|文件或目录不存在"
        if (p == FS_DEFAULT_ROOT || p == FS_GALLERY_DIR || p == "/") {
            return "0|failed|系统目录不允许删除"
        }
        return try {
            if (deleteRecursively(f)) "1|success|删除成功" else "0|failed|删除失败"
        } catch (e: Exception) {
            Log.w(TAG, "删除失败: $p ${e.message}")
            "0|failed|${e.message ?: "删除失败"}"
        }
    }

    private fun deleteRecursively(f: File): Boolean {
        return if (f.isDirectory) {
            val children = f.listFiles() ?: return f.delete()
            for (c in children) {
                if (!deleteRecursively(c)) return false
            }
            f.delete()
        } else {
            f.delete()
        }
    }

    /**
     * 启动文件下载（后台线程）：RSP_FS_GET(大小|文件名|状态) → CMD_FS_DATA分块(128KB) → CMD_FS_DONE
     * 通过命令来源通道sender（中继client/直连listener/ZT直连）推送数据。
     */
    private fun startFsDownload(path: String, sender: RelaySender?) {
        if (sender == null) {
            Log.w(TAG, "文件下载失败: sender为空，无法推送数据")
            return
        }
        val p = path.trim()
        val f = File(p)
        if (!f.exists()) {
            sender.send(RelayCommands.RSP_FS_GET, "0||not_found")
            return
        }
        if (f.isDirectory) {
            sender.send(RelayCommands.RSP_FS_GET, "0||error")
            return
        }
        Thread({
            try {
                val total = f.length()
                // ★ 等待连接就绪后再发送元信息（连接断开时等待重连，最多30秒）
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载取消: 连接未恢复 $p")
                    return@Thread
                }
                sender.send(RelayCommands.RSP_FS_GET, "$total|${f.name}|ok")
                val buf = ByteArray(128 * 1024)
                var sentBytes = 0L
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        // ★ 每块发送前检查连接状态：断线时等待重连，避免数据丢失
                        if (!sender.isConnected()) {
                            Log.w(TAG, "文件下载: 连接中断，等待重连... (已发送 $sentBytes/$total)")
                            if (!waitForConnection(sender, 30000)) {
                                Log.w(TAG, "文件下载中止: 重连超时 $p (已发送 $sentBytes/$total)")
                                return@Thread
                            }
                        }
                        sender.send(RelayCommands.CMD_FS_DATA, chunk)
                        sentBytes += n
                        // ★ 流控：每块间隔20ms，防止发送队列积压过多分块导致内存暴涨
                        Thread.sleep(20)
                    }
                }
                // ★ 等待连接就绪后再发送完成标记
                if (!waitForConnection(sender, 30000)) {
                    Log.w(TAG, "文件下载完成但连接断开，无法发送完成标记: $p")
                    return@Thread
                }
                sender.send(RelayCommands.CMD_FS_DONE, "")
                Log.i(TAG, "fs download done: $p ($total bytes)")
            } catch (e: Exception) {
                Log.w(TAG, "文件下载异常: $p ${e.message}")
                try {
                    sender.send(RelayCommands.RSP_FS_GET, "0||error")
                } catch (ignored: Exception) {
                }
            }
        }, "FsDownload").apply { isDaemon = true }.start()
    }

    /** ★ 等待sender连接就绪（用于文件下载断线重连），返回是否在超时内恢复 */
    private fun waitForConnection(sender: RelaySender?, timeoutMs: Long): Boolean {
        if (sender == null) return false
        if (sender.isConnected()) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return false
            }
            if (sender.isConnected()) return true
        }
        return false
    }

    private fun handleLocation(): LocationInfo {
        return HttpServerUtils.apiLocationCache
    }

    private fun handleContactAdd(contactData: ContactInfo) {
        // 创建一个空的ContentValues
        val values = ContentValues()
        // 首先向RawContacts.CONTENT_URI执行一个空值插入，目的是获取系统返回的rawContactId
        val rawcontacturi = XUtil.getContentResolver().insert(ContactsContract.RawContacts.CONTENT_URI, values)
        val rawcontactid = ContentUris.parseId(rawcontacturi!!)

        // 插入姓名数据
        values.clear()
        values.put(ContactsContract.Data.RAW_CONTACT_ID, rawcontactid)
        values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
        values.put(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contactData.name)
        XUtil.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values)

        // 插入电话数据
        for (phoneNumber in contactData.phoneNumber.split(";")) {
            values.clear()
            values.put(ContactsContract.Data.RAW_CONTACT_ID, rawcontactid)
            values.put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            values.put(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
            values.put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            XUtil.getContentResolver().insert(ContactsContract.Data.CONTENT_URI, values)
        }
    }

    private fun wakeOnLAN(macAddress: String, broadcastAddress: String? = null, port: Int = 9) {
        try {
            val macBytes = macAddress.replace("-", ":").split(":").map { it.uppercase(Locale.getDefault()).toInt(16).toByte() }.toByteArray()
            val magicPacket = ByteArray(102)

            // 首先添加6个0xFF字节
            for (i in 0 until 6) {
                magicPacket[i] = 0xFF.toByte()
            }

            // 之后添加16次MAC地址
            for (i in 6 until magicPacket.size step macBytes.size) {
                macBytes.copyInto(magicPacket, i, 0, macBytes.size)
            }

            val broadcastIP = if (broadcastAddress != null) {
                InetAddress.getByName(broadcastAddress)
            } else {
                InetAddress.getByName("255.255.255.255")
            }

            // 创建 UDP 数据包
            val packet = DatagramPacket(magicPacket, magicPacket.size, broadcastIP, port)

            // 发送数据包
            val socket = DatagramSocket()
            socket.send(packet)
            socket.close()
            Log.d(TAG, "WOL packet sent successfully.")
        } catch (e: Exception) {
            Log.d(TAG, "Error sending WOL packet: ${e.message}")
        }
    }
}
