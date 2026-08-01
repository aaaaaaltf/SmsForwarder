# -*- coding: utf-8 -*-
"""临时脚本：上传修复后的 relay_server.py 到服务器并重启（带超时，健壮版）"""
import paramiko
import time

HOST = "106.12.48.88"
LOCAL = r"g:\python Study\SmsForwarder-main\_relay_server_remote.py"

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username="root", password="ftl620203A", timeout=15, banner_timeout=15, auth_timeout=15)
print("SSH连接成功")

# 1. 上传
sftp = ssh.open_sftp()
sftp.put(LOCAL, "/root/relay_server.py")
sftp.close()
print("上传完成")

# 2. 停止旧进程（只杀 relay_server.py）
_, out, _ = ssh.exec_command("pkill -f relay_server.py; echo done")
print("pkill:", out.read().decode().strip())
time.sleep(2)

# 3. 重启（setsid 完全脱离会话，防止通道问题）
_, out, err = ssh.exec_command("cd /root && setsid nohup python3 relay_server.py > /tmp/relay_server.out 2>&1 < /dev/null & echo started")
print("启动:", out.read().decode().strip(), err.read().decode().strip())
time.sleep(3)

# 4. 确认端口监听
_, out, _ = ssh.exec_command("ss -tlnp | grep -E '5678[678]'")
print("端口监听:")
print(out.read().decode())

# 5. 日志尾部
_, out, _ = ssh.exec_command("tail -n 12 /var/log/relay_server/relay_server.log")
print("日志:")
print(out.read().decode())

ssh.close()
print("部署完成")
