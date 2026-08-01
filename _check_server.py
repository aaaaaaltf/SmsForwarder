# -*- coding: utf-8 -*-
"""查看服务器日志最新"""
import paramiko

ssh = paramiko.SSHClient()
ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
ssh.connect("106.12.48.88", username="root", password="ftl620203A", timeout=15)
_, out, _ = ssh.exec_command("tail -n 30 /var/log/relay_server/relay_server.log", timeout=20)
print(out.read().decode())
ssh.close()
