package cn.ppps.forwarder.relay

/**
 * ★★★ 2026-10-03【v2 协议版本/端口协商】被控端侧：协商结果 + 端口漂移自检。
 *
 * 背景（为什么需要它）：端口/命令字原先在三端各自维护、且没有任何一致性校验，出过两次真事故：
 *   ① 屏幕预览端口客户端写 56888、服务器只监听 56783 → 两端都 ECONNREFUSED → 黑屏，
 *      而现象只是"端口未开放"，与"服务器没开端口"长得一模一样，极难定位；
 *   ② 本端 RELAY_CONTROLLER_PORT 长期是 56787（服务器从不监听该端口的死值）。
 * 现在：真源 = protocol/relay_protocol.json（tools/gen_relay_protocol.py 生成/校验），
 *      服务器在**注册成功后**下发 protst（ver/caps/ports），本对象解析并**主动比对**本端常量，
 *      一旦不一致就在日志里直接点名，不必再靠翻源码猜。
 *
 * ★ 兼容性：本对象只做"读 + 记 + 日志"，不做任何会改变连接行为的动作；
 *   旧服务器不下发 protst → 各字段保持 0/空，功能与改造前完全一致。
 */
object RelayProtoState {

    /** 本端声明的协议版本（取自真源） */
    const val OWN_PROTO_VERSION = RelayProtocol.PROTO_VERSION

    /** 服务器协商到的协议版本（0 = 尚未收到，多为旧服务器） */
    @Volatile
    var serverProtoVersion: Int = 0
        private set

    /** 服务器 v2 声明的能力位（0 = 未知/旧服务器） */
    @Volatile
    var serverCaps: Int = 0
        private set

    /** 服务器下发的端口表原文 "NAME:port,NAME:port,..."（空 = 未收到） */
    @Volatile
    var serverPorts: String = ""
        private set

    /**
     * ★ 本端能力位。
     *   - CAP_SCREEN_JPEG：自研 JPEG/TCP 屏幕通道（一直都在，是高清模式失败后的回退通道）
     *   - CAP_SCREEN_WEBRTC：★ 2026-10-04 新增，屏幕可走 WebRTC 高清
     *     （`ScreenWebRtcCapturer` + MediaProjection，见 WebRtcSessionManager.screenMode）。
     *   ★ 这里声明的是"本版本支持的能力"，不是"此刻可用"——真正能不能开高清要看
     *     被控端是否已授权屏幕捕获（`ScreenStreamManager.isReady()`），未授权时
     *     会话启动会立刻 onError，控制端据此回退 JPEG 并提示授权。
     */
    fun ownCaps(): Int = RelayProtocol.CAP_SCREEN_JPEG or RelayProtocol.CAP_SCREEN_WEBRTC

    /** 注册帧尾部协商后缀：|proto=<n>|caps=<hex>（旧服务器会把它们并进设备名/忽略，无副作用） */
    fun regHint(): String = "|proto=" + OWN_PROTO_VERSION + "|caps=0x" + Integer.toHexString(ownCaps())

    /**
     * 处理服务器下发的协议状态，返回供日志打印的摘要。
     * 负载格式：ver=<n>|caps=<hex>|ports=NAME:port,NAME:port,...
     */
    fun acceptProtoState(payload: String): String {
        var ver = 0
        var caps = 0
        var ports = ""
        for (raw in payload.split("|")) {
            val p = raw.trim()
            when {
                p.startsWith("ver=") -> ver = p.substring(4).trim().toIntOrNull() ?: 0
                p.startsWith("caps=") -> caps = try {
                    Integer.parseInt(p.substring(5).trim().removePrefix("0x"), 16)
                } catch (_: Throwable) {
                    0
                }
                p.startsWith("ports=") -> ports = p.substring(6).trim()
            }
        }
        serverProtoVersion = ver
        serverCaps = caps
        serverPorts = ports

        val sb = StringBuilder()
        sb.append("ver=").append(ver).append("(本端=").append(OWN_PROTO_VERSION).append(")")
        sb.append(" caps=0x").append(Integer.toHexString(caps))
            .append("[").append(RelayProtocol.capsText(caps)).append("]")
        // ★ 端口漂移自检：这就是"56888 vs 56783 黑屏"那类事故的早期报警器
        checkPort(sb, ports, "VIDEO", RelayCommands.RELAY_VIDEO_PORT)
        checkPort(sb, ports, "PHONE_CONTROLLED", RelayCommands.RELAY_SERVER_PORT)
        checkPort(sb, ports, "MIC_DATA", RelayCommands.MIC_DATA_PORT)
        if (ver > 0 && ver != OWN_PROTO_VERSION) {
            sb.append("\n★ [版本差异] 服务器协议 v").append(ver).append(" ≠ 本端 v")
                .append(OWN_PROTO_VERSION).append("（按向后兼容继续，个别功能可能不可用）")
        }
        return sb.toString()
    }

    private fun checkPort(sb: StringBuilder, ports: String, name: String, localPort: Int) {
        val rp = portOf(ports, name)
        if (rp > 0 && rp != localPort) {
            sb.append("\n★ [端口漂移] 服务器 ").append(name).append("=").append(rp)
                .append(" 与本端 ").append(localPort)
                .append(" 不一致（改 protocol/relay_protocol.json 后运行 tools/gen_relay_protocol.py）")
        }
    }

    private fun portOf(ports: String, name: String): Int {
        for (kv in ports.split(",")) {
            val i = kv.indexOf(':')
            if (i > 0 && kv.substring(0, i).trim() == name) {
                return kv.substring(i + 1).trim().toIntOrNull() ?: 0
            }
        }
        return 0
    }
}
