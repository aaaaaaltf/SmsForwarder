package cn.ppps.forwarder.utils

import cn.ppps.forwarder.relay.RelayCommands

/**
 * 远程控制中继配置
 */
class RelaySettings private constructor() {
    companion object {

        //中继服务器地址（硬编码106.12.48.88优先，可在界面修改）
        var relayHost: String by SharedPreference(SP_RELAY_HOST, RelayCommands.RELAY_HOST)

        //被控端连接中继的端口
        var relayServerPort: Int by SharedPreference(SP_RELAY_SERVER_PORT, RelayCommands.RELAY_SERVER_PORT)

        //控制端连接中继的端口
        var relayControllerPort: Int by SharedPreference(SP_RELAY_CONTROLLER_PORT, RelayCommands.RELAY_CONTROLLER_PORT)

        //是否启用被控端开机自启
        var enableServerAutorun: Boolean by SharedPreference(SP_RELAY_ENABLE_SERVER_AUTORUN, false)
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
