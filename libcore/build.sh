#!/bin/bash

source ./env_java.sh || true
source ../buildScript/init/env_ndk.sh

BUILD=".build"

rm -rf $BUILD/android \
  $BUILD/java \
  $BUILD/javac-output \
  $BUILD/src

if [ -z "$GOPATH" ]; then
  GOPATH=$(go env GOPATH)
fi

# 在编译时锁定singbox侧依赖，带重试防御 sum.golang.org 瞬态网络抖动
for i in 1 2 3; do
  go mod tidy && break || {
    if [ "$i" -eq 3 ]; then
      echo ">> ERROR: go mod tidy failed after 3 attempts" >&2
      exit 1
    fi
    echo ">> go mod tidy transient network error, retrying ($i/3)..." >&2
    sleep 3
  }
done

# 官方 sing-box 的 constant.Version 默认为 "unknown"，需经 ldflags -X 在链接期注入；
# 版本号取自 nb4a.properties 的 SINGBOX_VERSION（与 get_source.sh 克隆的源码版本一致）
SINGBOX_VERSION=$(grep '^SINGBOX_VERSION=' ../nb4a.properties | head -n1 | cut -d'=' -f2 | tr -d '\r[:space:]')
if [ -z "$SINGBOX_VERSION" ]; then
  echo ">> ERROR: SINGBOX_VERSION not found in nb4a.properties" >&2
  exit 1
fi

export GOBIND=gobind-matsuri
"$GOPATH"/bin/gomobile-matsuri bind -v -androidapi 21 -cache "$(realpath $BUILD)" -trimpath -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION" -tags='with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' . || exit 1
rm -r libcore-sources.jar

proj=../app/libs
mkdir -p $proj
cp -f libcore.aar $proj
echo ">> install $(realpath $proj)/libcore.aar"

# @author 雾晚: package a separate PIE executable for genuine root TUN.
NDK_BIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
for target in "armeabi-v7a:arm:armv7a-linux-androideabi21-clang" \
              "arm64-v8a:arm64:aarch64-linux-android21-clang" \
              "x86:386:i686-linux-android21-clang" \
              "x86_64:amd64:x86_64-linux-android21-clang"; do
  IFS=: read -r abi arch compiler <<< "$target"
  output="../app/executableSo/$abi/librootbox.so"
  mkdir -p "$(dirname "$output")"
  CGO_ENABLED=1 GOOS=android GOARCH="$arch" CC="$NDK_BIN/$compiler" \
    go build -buildmode=pie -trimpath \
      -ldflags="-s -w -X github.com/sagernet/sing-box/constant.Version=$SINGBOX_VERSION" \
      -tags='rootmodule,with_conntrack,with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api' \
      -o "$output" ./cmd/rootbox || exit 1
  test -s "$output" || exit 1
done
