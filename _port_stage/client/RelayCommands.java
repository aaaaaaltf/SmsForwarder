package com.example.remoteconsole.client;

import java.nio.charset.StandardCharsets;

/**
 * 中继协议命令常量（与 SmsForwarder 项目、python_version 中继服务兼容）
 * - 被控端连接中继 56784(PC)/56786(手机) 端口：帧格式 [4字节大端长度][12字节命令][负载]
 * - 控制端连接中继 56782(PC)/56787(手机) 端口（首字节类型标识0x01=手机控制端）：
 *   帧格式 [4字节大端总长度][4字节大端pc_id][12字节命令][负载]
 * - 广播 pc_id = 0xFFFFFFFF（online000000 / discon000000，负载为中继GBK编码）
 *
 * ★ 摄像头帧负载格式（SmsForwarder 被控端 v2）："索引|流ID|" + JPEG
 *   流ID用于控制端过滤中继缓冲的旧流残留帧（重开/切换后不显示旧视频）
 */
public class RelayCommands {

    public static final int CMD_PREFIX_LEN = 12;
    public static final String RELAY_HOST = "106.12.48.88";
    public static final int RELAY_SERVER_PORT = 56784;
    public static final int RELAY_CONTROLLER_PORT = 56782;
    public static final int RELAY_VIDEO_PORT = 56783;  // 中继视频流端口(PC屏幕预览)
    public static final int RELAY_PHONE_VIDEO_PORT = 56788;  // ★ 中继视频流端口(手机被控端屏幕预览)
    public static final byte CTRL_TYPE_PHONE = 0x01;
    public static final int BROADCAST_PC_ID = 0xFFFFFFFF;

    // ==================== 控制端 → 被控端（请求命令） ====================
    public static final String CMD_GET_CONFIG = "sfconfig0000";
    public static final String CMD_BATTERY = "sfbatqry0000";
    public static final String CMD_SMS_QUERY = "sfsmsqry0000";
    public static final String CMD_CALL_QUERY = "sfcallqry000";
    public static final String CMD_CONTACT_QUERY = "sfconqry0000";
    public static final String CMD_CONTACT_ADD = "sfconadd0000";
    public static final String CMD_LOCATION = "sflocqry0000";
    public static final String CMD_WOL = "sfwolsnd0000";

    // ==================== 远程桌面（屏幕预览） ====================
    // 负载格式: "0|0|FPS|24|质量|clientId"（clientId = 被控端pc_id，用于视频流端口配对）
    public static final String CMD_RD_START = "rdstrt000000";
    public static final String CMD_RD_STOP = "rdstp0000000";
    public static final String RSP_RD_START_ACK = "rdack0000000";

    // ==================== 摄像头（图像仅推送给控制端，不在被控端屏幕显示） ====================
    public static final String CMD_CAMERA_STREAM_START = "vcdstr000000";  // 负载=摄像头索引
    public static final String CMD_CAMERA_STREAM_STOP = "vcdstp000000";   // 负载=摄像头索引
    public static final String CMD_CAMERA_STATUS_REPORT = "vcdsrp000000"; // 负载=索引|success/failed|详情
    public static final String CMD_CAMERA_STREAM_FRAME = "vcdfrm000000";  // 二进制负载=索引|流ID|JPEG

    // ==================== 被控端 → 控制端（响应命令） ====================
    public static final String RSP_CONFIG = "sfcfgrsp0000";
    public static final String RSP_BATTERY = "sfbatrsp0000";
    public static final String RSP_SMS_QUERY = "sfsmsrsp0000";
    public static final String RSP_CALL_QUERY = "sfcallrsp000";
    public static final String RSP_CONTACT_QUERY = "sfconqrsp000";
    public static final String RSP_CONTACT_ADD = "sfcadrsp0000";
    public static final String RSP_LOCATION = "sflocrsp0000";
    public static final String RSP_WOL = "sfwolrsp0000";
    public static final String RSP_ERROR = "sferrrsp0000";

    // ==================== 中继系统广播（控制端接收） ====================
    public static final String CMD_CLIENT_ONLINE = "online000000";
    public static final String CMD_CLIENT_DISCONNECT = "discon000000";

    /**
     * 解析帧命令前缀
     * @return [0]=命令前缀 [1]=负载文本(UTF-8)
     */
    public static String[] parse(byte[] data) {
        if (data.length < CMD_PREFIX_LEN) return new String[]{""};
        String prefix = new String(data, 0, CMD_PREFIX_LEN, StandardCharsets.US_ASCII);
        String payload = new String(data, CMD_PREFIX_LEN, data.length - CMD_PREFIX_LEN, StandardCharsets.UTF_8);
        return new String[]{prefix, payload};
    }

    /**
     * 解析中继广播消息的pc_id（"PC被控端#N已上线|IP"），兼容中文乱码场景
     * @return pcId 或 -1
     */
    public static int parseBroadcastPcId(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\d+)").matcher(text);
        if (!m.find()) return -1;
        try {
            return Integer.parseInt(m.group(1));
        } catch (Exception e) {
            return -1;
        }
    }
}
