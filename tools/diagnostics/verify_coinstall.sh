#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail

own_apk=apks/OwnBox-2.9.1-x86_64-release.apk
wan_apk=apks/wanBoxForAndroid-3.0.0.1-x86_64-release.apk
own_id=com.ownbox.app
wan_id=com.lit008834.pixel.wanboxforandroid

assert_installed() {
  adb shell pm list packages | tr -d '\r' | grep -Fx "package:$1"
}

assert_absent() {
  if adb shell pm list packages | tr -d '\r' | grep -Fx "package:$1"; then
    echo "Unexpectedly installed: $1" >&2
    exit 1
  fi
}

launch_and_check() {
  local output
  output=$(adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1)
  printf '%s\n' "$output"
  grep -q 'Events injected: 1' <<< "$output"
  sleep 3
  adb shell pidof "$1" >/dev/null
  adb shell am force-stop "$1"
}

adb install "$own_apk"
adb install "$wan_apk"
assert_installed "$own_id"
assert_installed "$wan_id"
launch_and_check "$own_id"
launch_and_check "$wan_id"

# Reinstall each exact release as an update; the other application must remain.
adb install -r "$own_apk"
assert_installed "$wan_id"
adb install -r "$wan_apk"
assert_installed "$own_id"

adb uninstall "$own_id"
assert_absent "$own_id"
assert_installed "$wan_id"
adb install "$own_apk"
assert_installed "$own_id"
assert_installed "$wan_id"

adb uninstall "$wan_id"
assert_absent "$wan_id"
assert_installed "$own_id"
echo 'Both releases install, launch, reinstall, and uninstall independently.'
