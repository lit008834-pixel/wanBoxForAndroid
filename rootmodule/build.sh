#!/bin/bash
# @author 雾晚
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_NDK_HOME:?NDK required for Android PIE}"
host=${NDK_HOST_TAG:-linux-x86_64}
compiler="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host/bin/aarch64-linux-android31-clang"
mkdir -p build/arm64-v8a
CGO_ENABLED=1 GOOS=android GOARCH=arm64 CC="$compiler" go build -buildmode=pie -trimpath -ldflags='-s -w' -o build/arm64-v8a/wanboxctl ./cmd/wanboxctl
python3 pack.py --abi arm64-v8a --core ../app/executableSo/arm64-v8a/librootbox.so --cli build/arm64-v8a/wanboxctl --output build/wanbox-root-module-arm64-v8a.zip
