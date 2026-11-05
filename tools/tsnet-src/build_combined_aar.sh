#!/bin/bash
# 构建「单 Go 运行时」的合并 AAR：libtailscale + tsnetpoc
#
# 为什么必须合并：
#   gomobile bind 生成的两个 AAR 都包含 go/Seq.class 与 jni/<abi>/libgojni.so，
#   同进程无法同时加载两个同名 JNI 库（第二个会绑到第一个的 .so，方法号对不上）。
#   把两个 Go 包放进同一次 bind，只保留一份 go.Seq / libgojni.so，
#   被控端才能「保留原有 libtailscale Java API 编译期可用」的同时使用 tsnet 用户态通道。
#
# 用法：./build_combined_aar.sh [target]
#   ★ 2026-11-05 用户要求：以后打包一律仅构建 arm64 → target 默认 android/arm64，
#   只有明确需要别的 ABI 时才手动传 android/arm 等值。
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

TOOLCHAIN="$HOME/.cache/tailscale-go"
export GOROOT="$TOOLCHAIN"
export PATH="$TOOLCHAIN/bin:$HOME/go/bin:$PATH"

TARGET="${1:-android/arm64}"
OUT="/g/python Study/python_version/tailscale-dist/tailscale-tsnet.aar"

echo "=== go version ==="
"$TOOLCHAIN/bin/go.exe" version
echo "=== gomobile/gobind ==="
./tool/go install golang.org/x/mobile/cmd/gomobile golang.org/x/mobile/cmd/gobind

# ★ 版本戳：tailscale 的 version.CheckFormat() 会在运行期 panic
#   （真机实测：panic: malformed version.Long value "1.103.0-ERR-BuildInfo"），
#   必须和官方 Makefile 一样把 long/short/gitCommit 戳进链接参数。
#   格式要求：<x.y.z>-t<hex≥6>-g<hex≥6>（见 version.isValidLongWithTwoRepos）。
if [[ -s tailscale.version ]] && ./version-ldflags.sh >/dev/null 2>&1; then
    VERLAGS="$(./version-ldflags.sh)"
else
    echo "=== tailscale.version 不可用（无 git 仓库/生成失败），使用自造合法版本戳 ==="
    VERLAGS="-X tailscale.com/version.longStamp=1.103.0-t0000000001-g0000000001 \
-X tailscale.com/version.shortStamp=1.103.0 \
-X tailscale.com/version.gitCommitStamp=0000000001 \
-X tailscale.com/version.extraGitCommitStamp=0000000001"
fi
echo "=== version ldflags ==="
echo "$VERLAGS"

echo "=== gomobile bind (libtailscale + tsnetpoc) target=$TARGET ==="
rm -f "$OUT"
"$HOME/go/bin/gomobile" bind -target "$TARGET" -androidapi 26 \
    -tags "$(./build-tags.sh) ts_omit_cachenetmap" \
    -ldflags "-linkmode=external -extldflags=-Wl,-z,max-page-size=16384 $VERLAGS" \
    -o "$OUT" ./libtailscale ./tsnetpoc

echo "=== result ==="
ls -la "$OUT"
