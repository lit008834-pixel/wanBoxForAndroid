#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail

preview=previous/wanBoxForAndroid-3.0.4-preview.4-x86_64-release.apk
own=previous/OwnBox-2.9.1-x86_64-release.apk
shopt -s nullglob
new_apks=(apks/wanBoxForAndroid-*-x86_64.apk)
test "${#new_apks[@]}" -eq 1
current=${new_apks[0]}
formal_name=$(sed -n 's/^VERSION_NAME=//p' nb4a.properties | tr -d '\r')
formal_code=$(sed -n 's/^VERSION_CODE=//p' nb4a.properties | tr -d '\r')
formal_code=$((formal_code * 5))

adb install "$preview"
adb install "$own"
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.lit008834.pixel.wanboxforandroid'
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.ownbox.app'

# Keep the installed wanBox package and data while replacing the preview with the formal build.
adb install -r "$current"
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F "versionCode=$formal_code "
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F "versionName=$formal_name"
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.ownbox.app'

output=$(adb shell monkey -p com.lit008834.pixel.wanboxforandroid -c android.intent.category.LAUNCHER 1)
printf '%s\n' "$output"
grep -q 'Events injected: 1' <<< "$output"
sleep 3
adb shell pidof com.lit008834.pixel.wanboxforandroid >/dev/null
echo 'The formal release updated the installed preview while OwnBox remained installed.'
