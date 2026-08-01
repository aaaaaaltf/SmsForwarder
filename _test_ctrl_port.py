# -*- coding: utf-8 -*-
"""测试：从PC连接中继56787端口，模拟手机控制端发送帧，观察服务器行为"""
import socket
import struct
import time

HOST = "106.12.48.88"
PORT = 56787

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(10)
try:
    s.connect((HOST, PORT))
    print("已连接", HOST, PORT)
    # 发送类型标识 0x01 = 手机控制端
    s.sendall(b'\x01')
    print("已发送类型标识 0x01")

    time.sleep(1)
    # 尝试接收（服务器应发送在线列表广播）
    try:
        data = s.recv(4096)
        print("收到服务器数据:", len(data), "字节", data[:50])
    except socket.timeout:
        print("10秒内未收到服务器数据")

    # 发送一个 ping 帧: [4字节总长度][4字节pc_id][12字节cmd][payload]
    cmd = b"sfping000000"
    pc_id = 7  # 被控端 #7
    payload = b""
    inner = cmd + payload
    total = 4 + len(inner)
    frame = struct.pack(">I", total) + struct.pack(">I", pc_id) + inner
    s.sendall(frame)
    print("已发送ping帧到pc_id=7")

    time.sleep(3)
    try:
        data = s.recv(4096)
        print("收到响应:", len(data), "字节", data[:60])
    except socket.timeout:
        print("3秒内未收到响应")

    # 连接保持测试：每5秒发一次ping，观察是否断开
    for i in range(3):
        time.sleep(5)
        try:
            s.sendall(frame)
            print(f"[{i+1}] ping发送成功")
        except Exception as e:
            print(f"[{i+1}] ping发送失败: {e}")
            break
        try:
            s.settimeout(2)
            data = s.recv(4096)
            print(f"[{i+1}] 收到: {len(data)}字节")
        except socket.timeout:
            print(f"[{i+1}] 无响应(超时)")
        except Exception as e:
            print(f"[{i+1}] 接收异常: {e}")
            break
except Exception as e:
    print("连接/发送异常:", type(e).__name__, e)
finally:
    s.close()
    print("测试结束")
