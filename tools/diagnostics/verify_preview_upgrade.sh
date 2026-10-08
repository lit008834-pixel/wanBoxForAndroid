#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail

previous=previous/wanBoxForAndroid-3.0.6-x86_64.apk
prior_preview=previous/wanBox-prior-preview.apk
prior_preview_code=${WANBOX_PRIOR_PREVIEW_CODE:?Verified prior fixture version is required}
own=previous/OwnBox-3.0.0-x86_64-release.apk
shopt -s nullglob
new_apks=(apks/wanBoxForAndroid-*-x86_64-release.apk)
test "${#new_apks[@]}" -eq 1
current=${new_apks[0]}
preview_name=$(sed -n 's/^PRE_VERSION_NAME=//p' nb4a.properties | tr -d '\r')
preview_code=$(sed -n 's/^PRE_VERSION_CODE=//p' nb4a.properties | tr -d '\r')
preview_code=$((preview_code * 5))

adb install "$previous"
adb install "$own"
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.lit008834.pixel.wanboxforandroid'
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.ownbox.app'

# @author 雾晚: independently validate formal -> new preview and prior preview -> new preview.
# Fresh baselines are installed only on this disposable emulator; OwnBox remains installed.
adb install -r "$current"
adb shell pm clear com.lit008834.pixel.wanboxforandroid
adb uninstall com.lit008834.pixel.wanboxforandroid
adb install "$prior_preview"
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F "versionCode=$prior_preview_code "
adb install -r "$current"
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F "versionCode=$preview_code "
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F "versionName=$preview_name"
adb shell pm list packages | tr -d '\r' | grep -Fx 'package:com.ownbox.app'

output=$(adb shell monkey -p com.lit008834.pixel.wanboxforandroid -c android.intent.category.LAUNCHER 1)
printf '%s\n' "$output"
grep -q 'Events injected: 1' <<< "$output"
sleep 3
adb shell pidof com.lit008834.pixel.wanboxforandroid >/dev/null
echo 'The new preview updated the installed previous preview while OwnBox remained installed.'
