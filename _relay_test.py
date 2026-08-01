# -*- coding: utf-8 -*-
"""测试云中继 106.12.48.88 的广播行为"""
import socket
import struct
import sys

HOST = "106.12.48.88"
PHONE_PORT = 56787  # 控制器端口（本项目专用）
PC_PORT = 56786     # 被控端端口（本项目专用）

def recv_frame(sock, timeout=5):
    """接收一帧 [4B长度][cmd+payload]，返回 (cmd, payload_bytes, raw)"""
    sock.settimeout(timeout)
    try:
        header = b""
        while len(header) < 4:
            chunk = sock.recv(4 - len(header))
            if not chunk:
                return None
            header += chunk
        total_len = struct.unpack(">I", header)[0]
        if total_len <= 0 or total_len > 1024 * 1024:
            return None
        body = b""
        while len(body) < total_len:
            chunk = sock.recv(total_len - len(body))
            if not chunk:
                return None
            body += chunk
        return body
    except socket.timeout:
        return None

print("=" * 60)
print("测试1: 连接控制器端口 56787，发送类型标识 0x01，观察广播")
try:
    s1 = socket.socket()
    s1.settimeout(8)
    s1.connect((HOST, PHONE_PORT))
    print("[OK] 已连接 56787")
    s1.sendall(b"\x01")  # 类型标识 0x01 = 手机控制端
    print("[OK] 已发送类型标识 0x01")
    for i in range(3):
        body = recv_frame(s1, 6)
        if body is None:
            print(f"[{i}] 无数据（超时）")
            break
        if len(body) >= 4:
            pc_id = struct.unpack(">I", body[:4])[0]
            cmd = body[4:16].decode("ascii", errors="ignore")
            payload = body[16:].decode("utf-8", errors="ignore")
            print(f"[{i}] pc_id=0x{pc_id:08X} cmd={cmd} payload={payload!r}")
        else:
            print(f"[{i}] 帧过短: {body!r}")
    s1.close()
except Exception as e:
    print(f"[FAIL] {e}")
    try:
        s1.close()
    except Exception:
        pass

print()
print("=" * 60)
print("测试2: 连接被控端端口 56786，观察连接是否被接受")
try:
    s2 = socket.socket()
    s2.settimeout(8)
    s2.connect((HOST, PC_PORT))
    print("[OK] 已连接 56786（被控端端口接受连接）")
    # 等3秒看是否有任何下发数据
    s2.settimeout(3)
    try:
        data = s2.recv(1024)
        print(f"[数据] 收到: {data!r}")
    except socket.timeout:
        print("[数据] 3秒内无下发数据（正常，中继不会主动向被控端发数据）")
    s2.close()
    print("[OK] 被控端连接正常")
except Exception as e:
    print(f"[FAIL] {e}")
    try:
        s2.close()
    except Exception:
        pass

print()
print("测试完成")
