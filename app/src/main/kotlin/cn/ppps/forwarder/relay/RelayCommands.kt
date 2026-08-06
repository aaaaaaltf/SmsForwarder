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

    /** 中继视频流端口（屏幕预览推流/收流，本项目专用，原56783） */
    const val RELAY_VIDEO_PORT = 56788

    /** 控制端类型标识：手机控制端 */
    const val CTRL_TYPE_PHONE: Byte = 0x01

    /** 广播pc_id：中继系统消息 */
    const val BROADCAST_PC_ID: Int = 0xFFFFFFFF.toInt()

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

    // ==================== ZeroTier直连（中继关闭时被控端主动连接控制端56789） ====================
    /** ZeroTier直连请求（控制端→被控端，负载: 目标ZT IP|控制端ZT IP|端口） */
    const val CMD_ZT_DIRECT_CONNECT = "ztdirect0000"

    /** 手机控制端ZT直连监听端口（DirectHostServer） */
    const val ZT_DIRECT_PORT = 56789

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

    /** 远程WOL唤醒（负载: WolData JSON） */
    const val CMD_WOL = "sfwolsnd0000"

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
    const val RSP_WOL = "sfwolrsp0000"
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
