#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail
build_tools=$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)
android_jar="$ANDROID_HOME/platforms/android-35/android.jar"
mkdir -p probe/classes probe/dex
javac --release 8 -cp "$android_jar" -d probe/classes tools/security-probe/*.java
"$build_tools/d8" --lib "$android_jar" --output probe/dex probe/classes/com/wanbox/auditprobe/*.class
"$build_tools/aapt" package -f -M tools/security-probe/AndroidManifest.xml -I "$android_jar" -F probe/probe-unsigned.apk
(cd probe/dex && zip ../probe-unsigned.apk classes.dex)
keytool -genkeypair -keystore probe/probe.keystore -storepass android -keypass android -alias probe -dname 'CN=WanBox independent audit probe' -keyalg RSA -keysize 2048 -validity 2
"$build_tools/apksigner" sign --ks probe/probe.keystore --ks-pass pass:android --out probe/probe.apk probe/probe-unsigned.apk
