package cn.ppps.forwarder.relay

/**
 * 远程控制中继协议命令常量
 * 所有命令前缀固定12字节ASCII，与中继服务(106.12.48.88)的帧协议兼容：
 * - 被控端连接中继 56786 端口：帧格式 [4字节大端长度][12字节命令][负载]
 * - 控制端连接中继 56787 端口（首字节类型标识0x01=手机控制端）：
 *   帧格式 [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 *
 * 本项目（SmsForwarder 手机端）仅占用 56786/56787/56788 三个端口，
 * 56781~56785 留给其他项目（如PC被控端）使用。
 * 命令前缀以 sf 开头，避免与中继服务的 python 命令冲突（防止被中继特殊路由）。
 */
object RelayCommands {

    /** 命令前缀长度 */
    const val CMD_PREFIX_LEN = 12

    /** 中继服务器地址（硬编码优先） */
    const val RELAY_HOST = "106.12.48.88"

    /** 被控端连接中继的端口（本项目专用，原56784） */
    const val RELAY_SERVER_PORT = 56786

    /** 控制端连接中继的端口（本项目专用，原56782） */
    const val RELAY_CONTROLLER_PORT = 56787

    /** 中继视频流端口（屏幕预览推流/收流，本项目专用，原56783 → 56888与服务器一致） */
    const val RELAY_VIDEO_PORT = 56888

    /** 控制端类型标识：手机控制端 */
    const val CTRL_TYPE_PHONE: Byte = 0x01

    /** 广播pc_id：中继系统消息 */
    const val BROADCAST_PC_ID: Int = 0xFFFFFFFF.toInt()

    // ==================== 2026-09-23 手机被控端注册与中继状态联动 ====================

    /** 连上中继56786后发送的注册命令（服务器校验令牌后才登记，防裸端口） */
    const val CMD_PHONE_REG = "regsms000000"

    /** 注册共享令牌（须与中继服务器 relay_server.py 的 PHONE_REG_TOKEN 一致） */
    const val RELAY_REG_TOKEN = "RCPH-2026-56786"

    /** 服务器→手机被控端：中继总闸当前状态（负载 on/off）。收到即联动开/关VPN（被动响应） */
    const val CMD_RELAY_STATE_SERVER = "relayst00000"

    // ==================== 屏幕预览（远程桌面，被控端屏幕推流到中继56788） ====================
    /** 启动屏幕推流（负载: host|port|FPS|色深|质量|clientId，clientId=被控端pc_id用于56788配对） */
    const val CMD_RD_START = "rdstrt000000"

    /** 停止屏幕推流 */
    const val CMD_RD_STOP = "rdstp0000000"

    /** 屏幕推流启动确认（负载: 推流端口） */
    const val RSP_RD_START_ACK = "rdack0000000"

    // ==================== 远程触摸操控（控制端→被控端，负载: 归一化坐标 "x|y" 0.0~1.0） ====================
    /** 触摸按下 */
    const val CMD_RD_MOUSE_DOWN = "md0000000000"

    /** 触摸移动 */
    const val CMD_RD_MOUSE_MOVE = "mm0000000000"

    /** 触摸抬起（被控端根据拖动距离判定点击或滑动） */
    const val CMD_RD_MOUSE_UP = "mu0000000000"

    /** 双击 */
    const val CMD_RD_MOUSE_DBL = "mdb000000000"

    /** 滚轮（负载: 增量数字） */
    const val CMD_RD_MOUSE_WHEEL = "mw0000000000"

    // ==================== 点亮/熄灭屏幕（控制端→被控端） ====================
    /** 点亮被控端屏幕并解锁（无密码锁屏），负载可为空 */
    const val CMD_WAKEUP_SCREEN = "wakeup000000"

    /** 熄灭屏幕（亮屏时执行全局锁屏动作，屏幕立即熄灭），负载可为空 */
    const val CMD_SCREEN_OFF = "scroff000000"

    /** 熄屏/点亮屏幕执行结果响应（负载: 1|success|成功信息 或 0|failed|失败原因） */
    const val RSP_SCREEN_CTRL = "sfscreen0000"

    // ==================== Tailscale直连（中继关闭时被控端主动连接控制端56789） ====================
    /**
     * Tailscale直连请求（控制端→被控端，负载: 目标Tailscale IP|控制端Tailscale IP|端口）
     * ★ 值 "ztdirect0000" 为历史命名（ZeroTier 时代），实际表示 Tailscale 直连；
     *   它是三端（Java控制端 / Kotlin手机被控端 / Python PC端）共用的线上协议命令字，值不可修改。
     */
    const val CMD_TS_DIRECT_CONNECT = "ztdirect0000"

    /** 手机控制端TS直连监听端口（DirectHostServer） */
    const val TS_DIRECT_PORT = 56789

    /** PC协议版本查询（控制端用于确认被控端在线，兼容PC被控端协议） */
    const val CMD_GET_VERSION = "ver000000000"

    /** 版本信息响应（负载: 版本|设备名|用户|isAdmin|isService，ASCII内容GBK/UTF-8解码一致） */
    const val CMD_VERSION_INFO = "ver100000000"

    // ==================== 摄像头（被控端摄像头推流，图像仅推送到控制端，不显示在被控端屏幕） ====================
    /** 启动摄像头流（负载: 摄像头索引） */
    const val CMD_CAMERA_STREAM_START = "vcdstr000000"

    /** 停止摄像头流（负载: 摄像头索引） */
    const val CMD_CAMERA_STREAM_STOP = "vcdstp000000"

    /** 摄像头状态报告（负载: 索引|success/failed|详情） */
    const val CMD_CAMERA_STATUS_REPORT = "vcdsrp000000"

    /** 摄像头视频帧（二进制负载: 索引|JPEG） */
    const val CMD_CAMERA_STREAM_FRAME = "vcdfrm000000"

    // ==================== 被控端麦克风音频流（控制端免提播放） ====================
    // ★ 2026-08-10 修复：所有命令严格12字符（与CMD_PREFIX_LEN=12一致），否则parse只取前12字符前缀导致匹配失败！
    /** 启动麦克风采集并推流，负载空 */
    const val CMD_MIC_START = "sfmicstr0000"
    /** 停止麦克风采集，负载空 */
    const val CMD_MIC_STOP = "sfmicstp0000"
    /** 麦克风音频帧（二进制=PCM 16bit/单声道/8000Hz）——命令通道回退用 */
    const val CMD_MIC_FRAME = "sfmicfrm0000"
    /** ★ 2026-08-09 麦克风数据通道端口（独立于命令通道56786和视频流56788，避开relay_control的56790） */
    const val MIC_DATA_PORT = 56791
    /** ★ 2026-08-09 麦克风数据通道就绪通知（负载: host|port），控制端收到后连接数据通道 */
    const val CMD_MIC_DATA_READY = "sfmicdr00000"

    // ==================== 控制端 → 被控端（请求命令） ====================
    /** 心跳探测（控制端每5秒发送，用于连接保活与在线检测；负载可为空） */
    const val CMD_PING = "sfping000000"

    /** 查询被控端配置/设备信息 */
    const val CMD_GET_CONFIG = "sfconfig0000"

    /** 查询电量 */
    const val CMD_BATTERY = "sfbatqry0000"

    /** 查询短信（负载: SmsQueryData JSON） */
    const val CMD_SMS_QUERY = "sfsmsqry0000"

    /** 查询通话记录（负载: CallQueryData JSON） */
    const val CMD_CALL_QUERY = "sfcallqry000"

    /** 查询联系人（负载: ContactQueryData JSON） */
    const val CMD_CONTACT_QUERY = "sfconqry0000"

    /** 新增联系人（负载: ContactInfo JSON） */
    const val CMD_CONTACT_ADD = "sfconadd0000"

    /** 查询定位 */
    const val CMD_LOCATION = "sflocqry0000"

    /** 一键换新机-拉取配置（负载: "pull" 或 CloneInfo JSON） */
    const val CMD_CLONE_PULL = "sfclone00000"

    /** 一键换新机-推送配置（负载: "push|" + CloneInfo JSON） */
    const val CMD_CLONE_PUSH = "sfclone10000"

    // ==================== 文件系统（控制端→被控端，2026-08-06新增） ====================
    /** 列目录（负载: 目录路径；空负载=默认根目录 Download 父目录 /storage/emulated/0） */
    const val CMD_FS_LIST = "sflsdir00000"

    /** 删除文件/目录（负载: 路径） */
    const val CMD_FS_DELETE = "sfdel0000000"

    /** 下载文件（负载: 路径，被控端先回 RSP_FS_GET 再分块推送 CMD_FS_DATA → CMD_FS_DONE） */
    const val CMD_FS_GET = "sfget0000000"

    /** 列目录响应（负载: JSON数组 [{name,type,size,mtime,path},...]） */
    const val RSP_FS_LIST = "sflsrsp00000"

    /** 删除响应（负载: 1|success|信息 或 0|failed|原因） */
    const val RSP_FS_DELETE = "sfdelrsp0000"

    /** 下载响应（负载: 大小|文件名|状态，状态=ok/not_found/error） */
    const val RSP_FS_GET = "sfgetrsp0000"

    /** 下载数据分块（二进制负载，被控端→控制端） */
    const val CMD_FS_DATA = "sfdata000000"

    /** 下载完成（被控端→控制端） */
    const val CMD_FS_DONE = "sfdone000000"

    /** ★ 取消下载（控制端→被控端，通过专用命令通道发送，被控端立即停止发送数据） */
    const val CMD_FS_CANCEL = "sfcancel0000"

    /** ★ 取消下载确认（被控端→控制端，负载: 1|stopped|已停止 或 0|failed|原因） */
    const val RSP_FS_CANCEL = "sfcancelrsp0"

    /** ★ 数据块接收确认（控制端→被控端，负载: 空；被控端每块发送后等待此ACK，参照PC微信分块确认） */
    const val CMD_FS_ACK = "sfack0000000"

    // ==================== ★ 文件上传（2026-09-07新增，控制端→被控端） ====================
    /** 上传请求（负载: "目标目录|文件名|文件大小"），被控端回 RSP_FS_UPREADY 确认后开始分块上传 */
    const val CMD_FS_UPLOAD = "sfupload0000"

    /** 上传就绪响应（负载: "ok|起始偏移" 续传，或 "ok|0" 全新上传） */
    const val RSP_FS_UPREADY = "sfupready000"

    /** 上传数据分块（二进制负载: 4字节大端块序号 + 128KB文件数据，控制端→被控端） */
    const val CMD_FS_UPDATA = "sfupdata0000"

    /** 上传数据块确认（负载: 块序号，被控端→控制端，控制端等待此ACK后才发下一块） */
    const val RSP_FS_UPACK = "sfupack00000"

    /** 上传完成（控制端→被控端，通知所有数据已发送完毕） */
    const val CMD_FS_UPDONE = "sfupdone0000"

    /** 上传结果（被控端→控制端，负载: "1|success|信息" 或 "0|failed|原因"） */
    const val RSP_FS_UPRST = "sfuprst00000"

    // ==================== ★ 文件属性（2026-09-07新增，控制端→被控端） ====================
    /** 获取文件/目录属性（负载: 路径），被控端回 RSP_FS_STAT 返回Windows风格属性JSON */
    const val CMD_FS_STAT = "sfstat000000"

    /** 文件属性响应（负载: JSON {name,type,path,size,mtime,hidden,readonly,dirCount,fileCount,...}） */
    const val RSP_FS_STAT = "sfstatrsp000"

    // ==================== ★ 新建文件夹（2026-09-21新增，控制端→被控端） ====================
    /** 新建文件夹（负载: 待创建文件夹的完整路径），被控端回 RSP_FS_MKDIR */
    const val CMD_FS_MKDIR = "sfmkdir00000"

    /** 新建文件夹响应（负载: "1|success|信息" 或 "0|failed|原因"，原因含"已存在"表示重名冲突） */
    const val RSP_FS_MKDIR = "sfmkdirrsp00"

    // ==================== ★ WebRTC 信令命令（2026-08-10新增，复用现有命令通道传SDP/ICE） ★ ====================
    // ★ 摄像头预览 + 麦克风同步的WebRTC模式：控制端发送CMD_WEBRTC_OFFER(而非分开的vcdstr/sfmicstr)，
    //   被控端回ANSWER+ICE+CANDIDATES，两端建立PeerConnection后音视频由WebRTC传输（VP8/H264+Opus+AEC/NS/AGC+抗抖动）
    /** 控制端 → 被控端：WebRTC OFFER SDP（负载: 摄像头索引|SDP_BASE64） */
    const val CMD_WEBRTC_OFFER    = "wrxoffer0000"
    /** 被控端 → 控制端：WebRTC ANSWER SDP（负载: SDP_BASE64） */
    const val CMD_WEBRTC_ANSWER   = "wrxanswer000"
    /** 双向：WebRTC ICE Candidate（负载: 方向(OFFERER/ANSWERER)|SDP_MID|SDP_MLINE_INDEX|CANDIDATE_SDP_BASE64） */
    const val CMD_WEBRTC_CANDIDATE = "wrxcand00000"
    /** 双向：WebRTC 结束/挂断（负载可空） */
    const val CMD_WEBRTC_HANGUP   = "wrxhangup000"
    /** 被控端 → 控制端：WebRTC 状态报告（负载: creating_offer|creating_answer|connecting|connected|failed|closed|reason） */
    const val CMD_WEBRTC_STATUS   = "wrxstatus000"

    // ==================== 被控端 → 控制端（响应命令） ====================
    const val RSP_CONFIG = "sfcfgrsp0000"
    /** 心跳探测响应 */
    const val RSP_PONG = "sfpong000000"
    const val RSP_BATTERY = "sfbatrsp0000"
    const val RSP_SMS_QUERY = "sfsmsrsp0000"
    const val RSP_CALL_QUERY = "sfcallrsp000"
    const val RSP_CONTACT_QUERY = "sfconqrsp000"
    const val RSP_CONTACT_ADD = "sfcadrsp0000"
    const val RSP_LOCATION = "sflocrsp0000"
    const val RSP_CLONE = "sfclonersp00"
    const val RSP_ERROR = "sferrrsp0000"

    // ==================== 中继系统广播（控制端接收） ====================
    /** PC/被控端上线通知：负载 "PC被控端#N已上线|IP" */
    const val CMD_CLIENT_ONLINE = "online000000"

    /** PC/被控端下线通知：负载 "PC被控端#N已断开" */
    const val CMD_CLIENT_DISCONNECT = "discon000000"

    // ==================== 中继系统状态广播（中继→被控端，本项目手机端） ====================
    /** 系统状态广播：负载 ctrlon/ctrloff 表示手机控制端上线/离线 */
    const val CMD_SYS_STATUS = "sfsys0000000"

    /** 系统状态：手机控制端已连接中继 */
    const val SYS_CTRL_ON = "ctrlon"

    /** 系统状态：手机控制端已断开中继 */
    const val SYS_CTRL_OFF = "ctrloff"

    // ==================== 被控端设备状态上报（被控端→中继→控制端） ====================
    /** 设备状态上报：负载 "名称|锁屏|屏幕|电量|充电"（被控端每5秒发送，中继广播给所有手机控制端） */
    const val CMD_DEV_STATE = "devstate0000"

    /** ★★★ 2026-08-17 方案C：反向查询设备状态（控制端→被控端，触发立即回 CMD_DEV_STATE）
     *   用于手机远程模块"在线被控端列表"主动探活心跳。中继识别此命令不更新 last_sender，
     *   避免覆盖下载路由导致手机文件下载卡死。被控端收到后立即采集设备状态并回 CMD_DEV_STATE。 */
    const val CMD_GET_DEV_STATE = "sfgetst00000"

    /**
     * ★★★ 2026-08-29 修复#1 新增（纯新增命令字，未改动任何既有命令字面值）：
     *   查询【本机被控端的实时 tailnet 成员 IP 列表】。
     *   存在理由：同机双 App 场景下控制端会 SKIP_OWN_BACKEND（复用被控端节点、避免双节点），
     *   于是控制端进程里 app==null、自己没有任何 localapi 可用 → getOnlineMemberIps() 恒空，
     *   华为控制端只能发现"历史名单里的设备"。被控端进程持有 libtailscale 后端，
     *   控制端经 127.0.0.1:56786 向它要一份成员列表即可（两个 App 的 SP 相互隔离，
     *   所以既有的 tailscale_status_cache 跨 App 读不到）。
     *   请求：CMD_GET_TS_MEMBERS（空负载）
     *   响应：RSP_TS_MEMBERS，负载 = 逗号分隔的成员 IPv4（已排除本机 Self）
     *   PC 被控端不认识该命令 → 返回错误/无响应，控制端自动回落原有探测源（向后兼容）。
     */
    const val CMD_GET_TS_MEMBERS = "tsmemget0000"
    const val RSP_TS_MEMBERS = "tsmemrsp0000"

    /**
     * ★★★ 2026-08-29 新增：Tailscale OAuth 凭证加密下发（与 PC 端 protocol/commands.py 同名命令字）
     *
     *  CMD_TAILSCALE_CRED_HELLO  被控端→控制端：申请"一次加密凭证下发"。
     *      负载 = {"v":2,"pk":<本次临时X25519公钥b64>,"n":<helloNonce>,"ts":<秒>}
     *      ★ 负载里不含任何凭证，只有公钥与随机 salt。
     *  CMD_TAILSCALE_CRED        控制端→被控端：回一帧 v2 密文（X25519 ECDH + AES-256-GCM）。
     *      负载 = {"v":2,"h":<回带的helloNonce>,"pk":<对端公钥>,"n":<IV>,"ts":<秒>,"ct":<密文>}
     *      被控端解密后用 Android Keystore 包裹落盘（见 tailscale/TailscaleCredGuard.kt）。
     *
     *  这两个值是 PC(Python) / 手机被控端(Kotlin) / 手机控制端(Java) 三端共用的线上协议命令字，
     *  ★ 字面值不可修改。协议只认 v=2：解不开一律拒绝，**绝不回落成"按明文 v1 解析"**。
     */
    const val CMD_TAILSCALE_CRED = "tscred000000"
    const val CMD_TAILSCALE_CRED_HELLO = "tsckey000000"

    /**
     * 解析帧命令前缀
     * @return (命令前缀, 负载字节)
     */
    fun parse(data: ByteArray): Pair<String, ByteArray> {
        if (data.size < CMD_PREFIX_LEN) return "" to data
        val prefix = String(data, 0, CMD_PREFIX_LEN, Charsets.US_ASCII)
        val payload = data.copyOfRange(CMD_PREFIX_LEN, data.size)
        return prefix to payload
    }

    /**
     * 解析中继广播消息的pc_id（"PC被控端#N已上线|IP" / "PC被控端#N已断开"）
     * 使用"#数字"模式匹配，兼容中文正常或编码乱码两种情况。
     * @return pcId 或 -1 解析失败
     */
    fun parseBroadcastPcId(text: String): Int {
        val regex = Regex("#(\\d+)")
        val match = regex.find(text) ?: return -1
        return match.groupValues[1].toIntOrNull() ?: -1
    }
}
