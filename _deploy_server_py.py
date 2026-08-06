# -*- coding: utf-8 -*-
"""规范部署：更新 /opt/relay_server/relay_server.py 并 systemctl 重启（服务器唯一权威实例）"""
import paramiko
import time
import socket

HOST = "106.12.48.88"
LOCAL = r"g:\python Study\python_version\SmsForwarder-main\_relay_server_remote.py"


def exec_with_timeout(ssh, cmd, timeout=15):
    """执行远程命令并读取全部输出，带超时保护（防止通道挂死）"""
    _, out, _ = ssh.exec_command(cmd, timeout=timeout)
    chan = out.channel
    chan.settimeout(timeout)
    stdout = b""
    while True:
        try:
            data = chan.recv(65536)
            if not data:
                break
            stdout += data
        except socket.timeout:
            break
        except Exception:
            break
    return stdout.decode("utf-8", "ignore")


ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect(HOST, username="root", password="ftl620203A", timeout=15, banner_timeout=15, auth_timeout=15)
print("SSH连接成功", flush=True)

# 1. 上传到权威路径 /opt/relay_server/relay_server.py（systemd 服务使用的副本）
sftp = ssh.open_sftp()
sftp.put(LOCAL, "/opt/relay_server/relay_server.py")
try:
    sftp.put(LOCAL, "/root/relay_server.py")  # 同步备份副本，避免遗留脚本引用旧版
except Exception as e:
    print("root副本更新失败(忽略):", e, flush=True)
sftp.close()
print("上传完成 -> /opt/relay_server/relay_server.py", flush=True)

# 2. 停止所有手动启动的中继实例（避免双实例争抢端口）
out = exec_with_timeout(ssh, r"pkill -f 'relay_server\.py'; echo done")
print("pkill 手动实例:", out.strip(), flush=True)
time.sleep(2)

# 3. 通过 systemd 重启（自动拉起、开机自启）
out = exec_with_timeout(ssh, "systemctl restart relay-server; echo rc=$?")
print("systemctl restart:", out.strip(), flush=True)
time.sleep(4)

# 4. 确认服务状态与端口监听
out = exec_with_timeout(ssh, "systemctl is-active relay-server")
print("服务状态:", out.strip(), flush=True)
out = exec_with_timeout(ssh, "ss -tlnp | grep -E '5678[2-8]'")
print("端口监听:", flush=True)
print(out.strip(), flush=True)

# 5. 日志尾部
out = exec_with_timeout(ssh, "tail -n 8 /var/log/relay_server/relay_server.log")
print("日志:", flush=True)
print(out.strip(), flush=True)

ssh.close()
print("部署完成", flush=True)
