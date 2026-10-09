package cn.ppps.forwarder.relay

/**
 * ★ 本文件由 tools/gen_relay_protocol.py 从 protocol/relay_protocol.json 生成，请勿手改。
 *
 * 真源: protocol/relay_protocol.json
 *
 * ★ 为什么要有这个文件：端口/命令字在三端各自维护、且没有任何一致性校验，
 *   曾出现本文件 RELAY_CONTROLLER_PORT=56787（服务器从不监听该端口，死值），
 *   以及 RELAY_VIDEO_PORT=56888 vs 服务器 56783 → 屏幕预览黑屏。
 */
object RelayProtocol {

    const val PROTO_VERSION = 2
    const val PROTO_VERSION_LEGACY = 1

    // ==================== ports ====================
    /**
     * PC被控端连接中继的端口。★ 取名陷阱：Java 侧把它叫 RELAY_SERVER_PORT（语义='PC被控端'而非'服务器'），而 Kotlin 侧同名的 RELAY_SERVER_PORT 
     * 指的是手机被控端(56786) —— 故两边都限定作用域，避免同名不同义互相误报。
     */
    const val PORT_PC_CONTROLLED = 56784
    /**
     * 控制端连接中继的命令通道端口（首字节类型标识 0x01=手机控制端）
     */
    const val PORT_CONTROLLER = 56782
    /**
     * 视频流端口（屏幕预览/摄像头），中继按 PUSHER/LISTENER 的 clientId 配对，PC 与手机可共用
     */
    const val PORT_VIDEO = 56783
    /**
     * 数据通道端口（大文件上传/下载，物理隔离控制通道）
     */
    const val PORT_DATA = 56785
    /**
     * 手机被控端连接中继 / 被控端直连监听端口
     */
    const val PORT_PHONE_CONTROLLED = 56786
    /**
     * Tailscale 直连监听端口（手机控制端 DirectHostServer）
     */
    const val PORT_TS_DIRECT = 56789
    /**
     * relay_control 物理启停服务端口（一问一答，无广播能力；服务器不监听）
     */
    const val PORT_RELAY_CONTROL = 56790
    /**
     * 麦克风数据通道端口（避开 relay_control 的 56790）
     */
    const val PORT_MIC_DATA = 56791

    // ==================== hosts ====================
    /**
     * 中继服务器公网地址（三端共用：手机控制端/手机被控端连接中继；同时是 PC 控制端的公网入口与 TURN 主机）。★ 2026-10-09 收编：此前该地址散在 3 种语言约 15 处常量里、无任何一致性校验，
     * 改一处即漏改多端。★ 别名全部限定作用域，避免与端口别名互相误报；SERVER_IP（config/network_config.py）刻意不登记——它是可被用户改成内网 IP 以进入内网模式的开关，
     * 不是中继地址语义。★ android_controller/::RELAY_HOST_IP 是同值第二份（P2 收敛为引用 RelayCommands.RELAY_HOST 后，本别名一并移除）
     * 。
     */
    const val RELAY_HOST = "106.12.48.88"
    /**
     * 内网模式标识 IP（= config/network_config.py 的 LAN_MODE_IP；SERVER_IP 等于此值时自动进入内网模式）。即 PC 控制端的内网地址，与中继地址语义不同，
     * 故作独立条目。
     */
    const val LAN_HOST = "47.247.40.7"

    // ==================== labels ====================
    /**
     * 手机被控端注册共享令牌（服务器校验后才登记，不裸开端口）
     */
    const val LABEL_PHONE_REG_TOKEN = "RCPH-2026-56786"
    /**
     * 中继视频/数据通道推流端认证标签（格式 PUSHER:<clientId>\n）
     */
    const val LABEL_PUSHER_TAG = "PUSHER:"
    /**
     * 中继视频/数据通道收流端认证标签（格式 LISTENER:<clientId>\n）
     */
    const val LABEL_LISTENER_TAG = "LISTENER:"
    /**
     * 中继数据通道(56785)认证标签前缀
     */
    const val LABEL_AUTH_TAG = "AUTH:"

    // ==================== caps ====================
    /**
     * 屏幕以自研 JPEG/TCP 帧推送（旧能力，所有版本都有，永远置位）
     */
    const val CAP_SCREEN_JPEG = 1
    /**
     * ★ 新增：屏幕可走 WebRTC（ScreenCapturerAndroid + VP8/H264）。只有宣告该位的被控端才会被控制端用 WebRTC 看屏；未宣告（旧 APK）继续走 JPEG。
     */
    const val CAP_SCREEN_WEBRTC = 2
    /**
     * ★ 2026-10-07 新增：被控端回程的**信号帧**（wrxanswer/wrxcand/wrxstatus/wrxhangup 等）带 `|cid=<n>`，中继据此**精确回投**（见 
     * core/relay_server.py 的 CID_TAGGED_CMDS）。★ 缺省（旧端/未宣告）= 不支持 ⇒ 中继退回 stream_owner/last_sender 兜底（多路同看时可能投错人）
     * ；因此**只有宣告该位的被控端才允许与之做多路同看**。
     */
    const val CAP_CID_ROUTE_ALL_SIGNALING = 3
    /**
     * ★ 2026-10-07 新增：摄像头采集**进程内共享**（modules/camera_shared.py：按 index 单例 + 引用计数，多路复用同一份 VideoCapture，每路各自缩放/编码）
     * 。★ 缺省（旧端）= 每个会话各开一份设备 ⇒ 多台控制端同看同一摄像头会互相抢设备、画面交替冻结；因此**只有宣告该位的被控端才能保证摄像头多路同看**。
     */
    const val CAP_CAM_SHARED_CAPTURE = 4

    // ==================== timeouts ====================
    /**
     * 控制端→服务器心跳间隔（服务器必须回帧，否则控制端 40s 读超时误判断开）
     */
    const val TIMEOUT_CTRL_HEARTBEAT_MS = 10000L
    /**
     * 控制端/手机被控端读超时（半开连接检测）
     */
    const val TIMEOUT_CTRL_READ_TIMEOUT_MS = 40000L
    /**
     * 手机被控端 accept 后必须在此时间内发注册帧（服务器 sock.settimeout(10)）
     */
    const val TIMEOUT_PHONE_REG_TIMEOUT_MS = 10000L
    /**
     * 服务器→手机被控端下行保活帧间隔（_phone_ping_loop 内 time.sleep(15)）
     */
    const val TIMEOUT_PHONE_DOWNLINK_PING_MS = 15000L
    /**
     * stream_owner 空闲超时（服务器为 STREAM_OWNER_IDLE_SECS=180.0 秒，单位不同，故不做别名校验）
     */
    const val TIMEOUT_STREAM_OWNER_IDLE_MS = 180000L

    // ==================== cmds ====================
    /**
     * 手机被控端→服务器: 注册（负载 令牌|设备名[|proto=<n>][|caps=<hex>]）
     */
    const val CMD_PHONE_REG = "regsms000000"
    /**
     * 服务器→所有控制端/被控端: 中继总闸当前状态（负载 on/off）
     */
    const val CMD_RELAY_STATE_SERVER = "relayst00000"
    /**
     * 手机控制端→服务器: 本机已把中继切成 on/off
     */
    const val CMD_RELAY_STATE_REPORT = "relayrp00000"
    /**
     * 服务器→报告方: relayrp 处理回执（ok|on / ok|off / reject|<raw>）
     */
    const val CMD_RELAY_STATE_ACK = "relayrpack00"
    /**
     * 启动屏幕推流（负载 host|port|FPS|色深|质量|clientId[|wantcaps=1]）。★ 第7段可选：控制端要求被控端在 rdack 里回带 proto/caps；老被控端按 
     * len(parts)>N 下标读取，多余段被忽略；新被控端不带该段时也回纯端口，保证老控制端（按纯数字解析）在 TS 直连(56788)下不被扩展负载打成解析失败
     */
    const val CMD_RD_START = "rdstrt000000"
    /**
     * 停止屏幕推流
     */
    const val CMD_RD_STOP = "rdstp0000000"
    /**
     * 服务器→手机被控端：注册 challenge（负载为 32 字符随机 hex）。★ 2026-10-04【C批次 challenge-response】客户端须以共享令牌为 HMAC-SHA256 
     * 密钥对该 challenge 签名，把签名 hex 放进注册帧第一段。此前注册帧明文携带共享令牌，抓一个包即可伪造注册；改为 challenge-response 后令牌永不上线、重放旧包无效。
     * ★ 该常量必须留在真源里：core/relay_server.py 的常量块是**由本文件生成**的，手写在服务器文件里的常量会被下次生成覆盖（2026-10-05 实测：生成后 CMD_REG_CHALLENGE_STR 
     * 变成未定义，手机被控端注册会直接 NameError）。
     */
    const val CMD_REG_CHALLENGE = "regchal00000"
    /**
     * 控制端→被控端：接收端 QoS 反馈（负载 recvFps|excessMs|lostFrames|lastSeq|wantFps|targetKbps）。★ 2026-10-05【借鉴 RustDesk 
     * TestDelay 探针反向链路】此前的自适应码率只用**发送端自己的写耗时**推断拥塞，看不到『对端解码/渲染不过来』这种拥塞（发送侧一切正常、画面照样卡）。本条把接收端实测值回传给被控端：recvFps=接收端每秒实际收到/渲染的帧数（RustDesk 
     * client/io_loop.rs:1380-1488 fps_control 的 auto_fps 同源语义）；excessMs=接收端估计的排队延迟（对应 RustDesk TestDelay 
     * 往返测得的 delay）；lostFrames 累计丢失；lastSeq=已确认收到的最大帧号（对应 RustDesk Misc.video_received 的帧级确认）；wantFps=接收端建议的帧率上限（对应 
     * RustDesk custom_fps/AutoAdjustFps）；targetKbps=被控端回显的当前目标码率（对应 RustDesk TestDelay.target_bitrate，便于诊断『为什么糊』）
     * 。被控端据此调整自身 fps 上限与质量档。
     */
    const val CMD_RD_QOS = "rdqos0000000"
    /**
     * 控制端→被控端：帧级送达确认（负载 lastSeq|recvFps）。★【借鉴 RustDesk video_ack_required / Misc.video_received】RustDesk 
     * 在 video_service.rs:975-987 等所有观看者 ack（try_wait_next 300ms×N，上限 3s）才推进编码循环，从而把『有人收不动』变成编码端主动减速。本项目的 
     * JPEG 推送是『发完即走』，不知道对端是否真的收到，弱网下容易『发出去了但没人看到』。控制端每约 1 秒聚合回一条（不做逐帧 ACK，避免刷屏），被控端记录『最慢观看者的确认帧号』作为 stall 
     * 判据之一。
     */
    const val CMD_RD_FRAME_ACK = "rdfrmack0000"
    /**
     * 被控端→控制端：权限**生效结果**回推（负载 granted=<hex>|applied=<hex>|denied=<hex>）。★【借鉴 RustDesk message.proto:628-642 
     * PermissionInfo】服务器用 protst 下发 perms 位图（服务器权威），被控端把它与**自身能力**求交集得到 applied，denied 记录被本机策略拒绝的位；控制端据此显示『允许但未生效』的差异，
     * 避免出现『界面写着可文件传输、实际被拒』这种无从知晓的状态。
     */
    const val CMD_PERM_ACK = "permack00000"
    /**
     * ★2026-10-04【多路同看·输入仲裁】控制端→被控端：请求取得/释放操作权。负载 1=取得（其余路自动只读）/0=放弃。★ 可选命令：老控制端不发时保持'单路默认可控'，与改造前行为一致
     */
    const val CMD_RD_CONTROL = "rdctrl000000"
    /**
     * ★2026-10-04 被控端→控制端：本路输入权限回执。负载 <0|1>|<路数>（1=可操作，0=只读观看者）
     */
    const val CMD_RD_CTRL_STATE = "rdcst0000000"
    /**
     * 屏幕推流启动确认：默认负载=纯数字端口（老控制端按纯数字解析，勿破坏）；仅当 rdstrt 带 wantcaps=1 时扩为 端口|proto=<n>|caps=<hex> —— 控制端必须按 
     * | 分割后再取端口，并可用 caps 直接决定屏幕通道(JPEG/WebRTC)；失败仍回 JSON 错误负载（status=error）
     */
    const val RSP_RD_START_ACK = "rdack0000000"
    /**
     * 控制端↔服务器 心跳/回执（服务器原样回一帧）
     */
    const val CMD_PING = "sfping000000"
    /**
     * 获取版本信息（PC 心跳/在线确认，服务器丢弃不转发）
     */
    const val CMD_GET_VERSION = "ver000000000"
    /**
     * 版本信息响应（负载 版本|主机|用户|admin|service[|proto=<n>][|caps=<hex>]）
     */
    const val CMD_VERSION_INFO = "ver100000000"
    /**
     * 中继→控制端: 被控端已上线（GBK 广播文案）
     */
    const val CMD_CLIENT_ONLINE = "online000000"
    /**
     * 中继→控制端: 被控端已断开
     */
    const val CMD_CLIENT_DISCONNECT = "discon000000"
    /**
     * 被控端设备状态上报（负载 名称|锁屏|屏幕|电量|充电）
     */
    const val CMD_DEV_STATE = "devstate0000"
    /**
     * Tailscale 直连请求（值不可修改，三端共用，历史 ZeroTier 命名）
     */
    const val CMD_TS_DIRECT_CONNECT = "ztdirect0000"
    /**
     * ★ 新增 v2: 服务器→客户端 协议状态推送（负载 ver=<n>|caps=<hex>|ports=<NAME:port,...>），注册成功后立即下发；旧客户端走未知命令分支静默忽略
     */
    const val CMD_PROTO_STATE = "protst000000"
    /**
     * 启动摄像头流（负载 摄像头索引）
     */
    const val CMD_CAMERA_STREAM_START = "vcdstr000000"
    /**
     * 停止摄像头流
     */
    const val CMD_CAMERA_STREAM_STOP = "vcdstp000000"
    /**
     * 摄像头视频帧（二进制负载 索引|流ID|JPEG）
     */
    const val CMD_CAMERA_STREAM_FRAME = "vcdfrm000000"
    /**
     * 摄像头状态报告（负载 索引|success/failed|详情）
     */
    const val CMD_CAMERA_STATUS_REPORT = "vcdsrp000000"
    /**
     * 启动麦克风采集推流
     */
    const val CMD_MIC_START = "sfmicstr0000"
    /**
     * 停止麦克风采集
     */
    const val CMD_MIC_STOP = "sfmicstp0000"
    /**
     * 麦克风音频帧（二进制 PCM 16bit/单声道/8000Hz，命令通道回退用）
     */
    const val CMD_MIC_FRAME = "sfmicfrm0000"
    /**
     * 麦克风数据通道就绪通知（负载 host|port）
     */
    const val CMD_MIC_DATA_READY = "sfmicdr00000"
    /**
     * 服务器→控制端: PC被控端正忙，拒绝新的流式操作请求（负载=原因文案：谁占着/什么操作/已挂多久）。★ 2026-10-09 收编入真源：此前该命令字只以行内字面量存在于 core/relay_server.py 
     * 两处发送点，与 Android 控制端 Commands.CMD_PC_BUSY 各自维护、无一致性校验；收编后中继发送侧改用生成常量 CMD_PC_BUSY_STR，消费侧 Commands.java 
     * 的 CMD_PC_BUSY 由别名扫描持续校验。
     */
    const val CMD_PC_BUSY = "busy00000000"

    // ==================== ice ====================
    /**
     * 自建 coturn TURN 地址（三端共用：手机控制端 WebRtcSession / 手机被控端 WebRtcSessionManager / PC 被控端 screen_webrtc）。★ 
     * 凭据类常量一旦两端写岔，WebRTC 会静默退化成打洞失败（表现为只传一帧），极难定位，故必须收进真源。
     */
    const val TURN_URL = "turn:106.12.48.88:3478"
    /**
     * TURN 用户名（须与 coturn 的 user= 配置一致）
     */
    const val TURN_USERNAME = "remote"
    /**
     * TURN 密码（须与 coturn 的 user= 配置一致）
     */
    const val TURN_PASSWORD = "NtSWZlU4IoW3SLqPKfva"

    /** 当前 v2 协议支持的屏幕能力全集 */
    const val CAP_ALL = CAP_SCREEN_JPEG or CAP_SCREEN_WEBRTC or CAP_CID_ROUTE_ALL_SIGNALING or CAP_CAM_SHARED_CAPTURE

    /** 能力位 -> 可读文本（日志用） */
    fun capsText(caps: Int): String {
        val names = ArrayList<String>()
        if (caps and 1 != 0) names.add("SCREEN_JPEG")
        if (caps and 2 != 0) names.add("SCREEN_WEBRTC")
        if (caps and 3 != 0) names.add("CID_ROUTE_ALL_SIGNALING")
        if (caps and 4 != 0) names.add("CAM_SHARED_CAPTURE")
        return if (names.isEmpty()) "0" else names.joinToString("+")
    }
}
