#!/bin/bash
# 构建 libtailscale.aar（Windows/MSYS 环境适配，路径无空格）
set -e
export GIT_BASE="/c/Program Files/Git"
export PATH="$GIT_BASE/usr/bin:$GIT_BASE/bin:$GIT_BASE/mingw64/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
# ★ 加入 zip shim（Python zipfile 替代 Info-ZIP）
export PATH="/c/tailscale-android/tsbin:$PATH"
# ★ gomobile.exe 是原生 Windows 程序，需要在 Windows PATH 中找到 go.exe
export PATH="/c/Users/Administrator/.cache/tailscale-go/bin:$PATH"
# ★ javac 需要 JDK bin 在 PATH
export PATH="/e/Andorid Studio/jbr/bin:$PATH"
export ANDROID_HOME="C:/Users/Administrator/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export NDK_ROOT="$ANDROID_HOME/ndk/23.1.7779620"
export JAVA_HOME="E:/Andorid Studio/jbr"
export GOPROXY="https://goproxy.cn,direct"
export HOME="/c/Users/Administrator"
unset GOROOT

cd "/c/tailscale-android" || exit 1

echo "=== zip shim: $(which zip) ==="
echo "=== make tailscale.version ==="
make tailscale.version 2>&1 | tail -3

echo "=== make libtailscale ==="
make STRIP_TOOL="$NDK_ROOT/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objcopy.exe" libtailscale 2>&1 | tail -70

echo "=== result ==="
ls -la android/libs/libtailscale.aar 2>&1 || echo "AAR NOT CREATED"
