package cn.ppps.forwarder.relay

import cn.ppps.forwarder.server.model.BaseResponse
import com.google.gson.Gson

/**
 * 控制端全局共享状态
 * 由 ClientFragment 创建并维护中继连接，各功能页面通过 RelayApi 发送命令。
 */
object RelayClientHolder {
    @Volatile
    var client: RelayControllerClient? = null

    /** 当前选中的被控端 pc_id */
    @Volatile
    var selectedPcId: Int = -1

    /** 在线被控端列表：pcId -> 设备备注/IP（仅主线程访问） */
    val devices: LinkedHashMap<Int, String> = LinkedHashMap()

    val gson = Gson()

    fun isReady(): Boolean {
        val c = client ?: return false
        return c.isConnected() && selectedPcId >= 0
    }
}

/**
 * 控制端命令请求辅助类
 * 各功能页面统一通过此入口向被控端发送请求，响应统一为 BaseResponse JSON。
 */
object RelayApi {
    private val TAG = "RelayApi"

    /**
     * 向被控端发送请求
     * @param cmd 请求命令
     * @param payload 请求负载（JSON字符串）
     * @param rspCmd 响应命令
     * @param onSuccess 成功回调（BaseResponse JSON字符串）
     * @param onError 失败回调（错误信息）
     */
    fun request(cmd: String, payload: String, rspCmd: String, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val holder = RelayClientHolder
        val c = holder.client
        if (c == null || !c.isConnected()) {
            onError("未连接中继服务器，请先在客户端页面连接")
            return
        }
        if (holder.selectedPcId < 0) {
            onError("请先选择被控端设备")
            return
        }
        // ★ 操作前连接检测：被控端不在线（中继已广播下线）则直接返回，避免用户一直等待远程结果
        if (!holder.devices.containsKey(holder.selectedPcId)) {
            onError("被控端未连接，请检查中继与被控端的连接状态")
            return
        }
        c.request(holder.selectedPcId, cmd, payload, rspCmd, onSuccess, { msg ->
            onError(msg)
        })
    }

    /**
     * 解析统一响应报文
     * @return BaseResponse，失败时 code != 200
     */
    fun parseResponse(json: String): BaseResponse<*>? {
        return try {
            RelayClientHolder.gson.fromJson(json, BaseResponse::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
