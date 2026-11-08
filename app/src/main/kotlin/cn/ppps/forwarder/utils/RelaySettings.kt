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
         * ★★★ 2026-11-08【服务自恢复】用户是否在界面上主动停止过被控端服务。
         *   背景（真机实证）：覆盖安装会杀掉进程，而 RelayServerService 只由「开机自启」门控拉起；
         *   双机该开关都是 false + 无 MY_PACKAGE_REPLACED → 安装后服务永远不再运行，
         *   直接结果是 :56786 无监听、控制端直连全部 RST。
         *   因此进程启动时默认拉起服务（它是中继命令通道 + 开关状态同步的唯一载体），
         *   只有用户明确点过「停止服务」才尊重其意图不再自动拉起。
         */
        var userStoppedServer: Boolean by SharedPreference(SP_RELAY_USER_STOPPED_SERVER, false)

        // ==================== ★★★ 2026-11-08【权威开关状态持久化】 ====================
        //   背景：服务器 relayst / 同机控制端广播是唯一权威，但历史实现只把值放在内存里。
        //   被控端进程一旦重启（覆盖安装、后台被杀、手机重启），在"重新注册并收到 relayst 之前"
        //   会回落到"默认中继开" → 即使 user 开关是关的，直连任务也会被模式互斥停掉数秒到数分钟。
        //   这里把最后一次权威值落盘：无新鲜权威时沿用"用户上次同步到的开关状态"，
        //   仍从未收到过（全新安装）才按铁律默认中继开。
        //   ※ 持久化值只来自 relayst/同机广播，绝不由可达性写入。
        private var lastAuthorityRelayOn: Boolean by SharedPreference(SP_RELAY_LAST_AUTHORITY_ON, false)
        private var lastAuthorityKnown: Boolean by SharedPreference(SP_RELAY_LAST_AUTHORITY_KNOWN, false)

        /** 收到权威开关状态时调用（服务器 relayst / 同机控制端广播） */
        fun rememberAuthorityRelayState(on: Boolean, source: String) {
            try {
                lastAuthorityRelayOn = on
                lastAuthorityKnown = true
                Log.i("RelaySettings", "★ 权威开关状态已持久化: ${if (on) "中继开" else "中继关"}（$source）")
            } catch (t: Throwable) {
                Log.w("RelaySettings", "持久化权威开关状态失败: ${t.message}")
            }
        }

        /** 最后一次已知的用户开关状态；null = 从未收到过权威状态 */
        fun lastKnownAuthorityRelayState(): Boolean? =
            if (lastAuthorityKnown) lastAuthorityRelayOn else null

        /**
         * ★ 直连模式开关（已弃用，2026-08-05起被控端固定中继云优先，中继不可达自动走TS直连）
         * true=被控端监听56786接受控制端直连
         * false=传统中继模式（被控端主动连接中继56786）【当前默认，中继优先】
         */
        var directMode: Boolean by SharedPreference(SP_RELAY_DIRECT_MODE, false)
    }

    init {
        throw UnsupportedOperationException("u can't instantiate me...")
    }
}
