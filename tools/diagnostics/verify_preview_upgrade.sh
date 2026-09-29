#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail

previous=previous/wanBoxForAndroid-3.0.2-x86_64.apk
prior_preview=previous/wanBoxForAndroid-3.0.3-preview.3-x86_64-release.apk
own=previous/OwnBox-2.9.1-x86_64-release.apk
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

# @author 雾晚: verify the old signed preview can update the formal release,
# then the new preview can update the old preview without uninstalling either app.
adb install -r "$prior_preview"
adb shell dumpsys package com.lit008834.pixel.wanboxforandroid | grep -m1 -F 'versionCode=1635 '
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
