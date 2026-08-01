package cn.ppps.forwarder.relay

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.provider.ContactsContract
import android.util.Log
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
     * @return (响应命令, 响应负载JSON)，未知命令返回 null
     */
    fun handle(cmd: String, payload: ByteArray): Pair<String, String>? {
        val payloadText = String(payload, Charsets.UTF_8)
        return try {
            when (cmd) {
                RelayCommands.CMD_GET_CONFIG -> RelayCommands.RSP_CONFIG to success(handleConfig())

                RelayCommands.CMD_PING -> {
                    // ★ 心跳探测：响应 pong（同时保持中继链路活跃，防止NAT超时断连）
                    RelayCommands.RSP_PONG to "pong"
                }

                RelayCommands.CMD_BATTERY -> {
                    if (!HttpServerUtils.enableApiBatteryQuery) return RelayCommands.RSP_BATTERY to error("服务端已禁用该功能")
                    RelayCommands.RSP_BATTERY to success(handleBattery())
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
                    val ok = ScreenStreamManager.startStream(RelaySettings.relayHost, clientId, fps, quality)
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
