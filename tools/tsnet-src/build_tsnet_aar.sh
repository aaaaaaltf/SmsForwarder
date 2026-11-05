#!/bin/bash
# 构建 tsnetpoc.aar（用户态 tsnet 打样，不依赖 Android VpnService）
# 复用 tailscale-android 自带的 Go 工具链与 gomobile/NDK 环境（与 build_aar.sh 一致）
set -e

export GIT_BASE="/c/Program Files/Git"
export PATH="$GIT_BASE/usr/bin:$GIT_BASE/bin:$GIT_BASE/mingw64/bin:/usr/local/bin:/usr/bin:/bin:$PATH"
export PATH="/g/python Study/python_version/tailscale-android/tsbin:$PATH"
export ANDROID_HOME="C:/Users/Administrator/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/23.1.7779620"
export NDK_ROOT="$ANDROID_NDK_HOME"
export JAVA_HOME="E:/Andorid Studio/jbr"
export GOPROXY="https://goproxy.cn,direct"
export HOME="/c/Users/Administrator"
unset GOROOT

cd "/g/python Study/python_version/tailscale-android" || exit 1

# tailscale 自带的 Go 工具链（go.toolchain.rev 指定的版本）
TOOLCHAIN="$HOME/.cache/tailscale-go"
export GOROOT="$TOOLCHAIN"
# gomobile 是 Go 程序，按 PATHEXT 查找 go.exe；把工具链 bin 放最前即可命中 Go 1.26，
# 不要命中系统 Go 1.20（无法编译 go 1.26 模块）。
export PATH="$TOOLCHAIN/bin:$HOME/go/bin:$PATH"

OUT="/g/python Study/python_version/tailscale-dist/tsnetpoc.aar"

echo "=== go version ==="
"$TOOLCHAIN/bin/go.exe" version
echo "=== gomobile/gobind ==="
./tool/go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

echo "=== gomobile bind ./tsnetpoc ==="
rm -f "$OUT"
"$HOME/go/bin/gomobile" bind -target android -androidapi 26 \
    -tags "$(./build-tags.sh) ts_omit_cachenetmap" \
    -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384" \
    -o "$OUT" ./tsnetpoc

echo "=== result ==="
ls -la "$OUT"
