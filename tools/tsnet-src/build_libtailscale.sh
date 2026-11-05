#!/bin/bash
# 安装 gomobile/gobind
set -x
export GIT_BASE="/c/Program Files/Git"
export PATH="$GIT_BASE/usr/bin:$GIT_BASE/bin:$GIT_BASE/mingw64/bin:/usr/local/bin:/usr/bin:/bin:$PATH"

cd "/g/python Study/python_version/tailscale-android" || exit 1

export ANDROID_HOME="C:/Users/Administrator/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_ROOT="$ANDROID_HOME/ndk/23.1.7779620"
export JAVA_HOME="E:/Andorid Studio/jbr"
export GOPROXY="https://goproxy.cn,direct"
export HOME="/c/Users/Administrator"
unset GOROOT

mkdir -p android/build/go/bin

echo "=== install gomobile ==="
./tool/go install golang.org/x/mobile/cmd/gomobile 2>&1 | tail -20
echo "gomobile exit: $?"
echo "=== install gobind ==="
./tool/go install golang.org/x/mobile/cmd/gobind 2>&1 | tail -20
echo "gobind exit: $?"
ls -la android/build/go/bin/ 2>&1
