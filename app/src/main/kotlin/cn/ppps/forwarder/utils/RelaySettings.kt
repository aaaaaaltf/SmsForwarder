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

        /**
         * ★ 直连模式开关（已弃用，2026-08-05起被控端固定中继云优先，中继不可达自动走ZT直连）
         * true=被控端监听56786接受控制端直连
         * false=传统中继模式（被控端主动连接中继56786）【当前默认，中继优先】
         */
        var directMode: Boolean by SharedPreference(SP_RELAY_DIRECT_MODE, false)
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
