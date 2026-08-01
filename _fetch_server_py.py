# -*- coding: utf-8 -*-
"""临时脚本：从中继服务器下载 relay_server.py 到本地查看"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("106.12.48.88", username="root", password="ftl620203A", timeout=15)

sftp = ssh.open_sftp()
sftp.get("/root/relay_server.py", r"g:\python Study\SmsForwarder-main\_relay_server_remote.py")
sftp.close()
print("下载完成")
ssh.close()
