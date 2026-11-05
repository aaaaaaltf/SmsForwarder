#!/bin/bash
# 测试 tool/go 是否直接使用已解压工具链（不重新下载）
export GIT_BASE="/c/Program Files/Git"
export PATH="$GIT_BASE/usr/bin:$GIT_BASE/bin:$GIT_BASE/mingw64/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export ANDROID_HOME="C:/Users/Administrator/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_ROOT="$ANDROID_HOME/ndk/23.1.7779620"
export JAVA_HOME="E:/Andorid Studio/jbr"
export GOPROXY="https://goproxy.cn,direct"
export HOME="/c/Users/Administrator"
unset GOROOT
cd "/g/python Study/python_version/tailscale-android" || exit 1

LOG="$HOME/.cache/ts_go_test.log"
: > "$LOG"

echo "=== check marker ===" >> "$LOG"
toolchain="$HOME/.cache/tailscale-go"
ls -la "$HOME/.cache/" | grep tailscale >> "$LOG" 2>&1
echo "marker content: $(<"${toolchain}.extracted")" >> "$LOG" 2>&1
echo "REV content: $(<"${tsandroid:-/g/python Study/python_version/tailscale-android}/go.toolchain.rev")" >> "$LOG" 2>&1

echo "=== run ./tool/go version ===" >> "$LOG"
./tool/go version >> "$LOG" 2>&1
echo "exit=$?" >> "$LOG"
