#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail
# @author 雾晚: preserve evidence even when the external probe fails.
trap 'adb logcat -d > probe/external-control-logcat.txt || true' EXIT
apk=$(find app/build/outputs/apk/oss/debug -name '*x86_64*.apk' | head -n 1)
test -s "$apk"
# Instrumentation runners may uninstall the target after their tests finish.
adb install -r "$apk"
if [[ -n "${PROBE_TARGET:-}" ]]; then
  package=$PROBE_TARGET
else
  AAPT=$(find "${ANDROID_HOME}/build-tools" -name aapt | sort -V | tail -n 1)
  package=$("$AAPT" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
  test -n "$package"
fi
adb shell pm list packages | tr -d '\r' | grep -Fx "package:$package"
adb install -r probe/probe.apk
# @author 雾晚: CI's Google launcher can show an unrelated ANR over the app.
# Hide system error overlays only on this disposable emulator; the assertions
# still require the actual target confirmation dialog and a stopped service.
adb shell settings put global hide_error_dialogs 1
adb shell wm size 720x1280
adb shell wm density 320
adb shell am force-stop com.google.android.apps.nexuslauncher || true
for quick in ui.QuickEnableShortcut ui.QuickDisableShortcut QuickToggleShortcut; do
  adb shell am force-stop "$package"
  adb logcat -c
  adb shell am start -n com.wanbox.auditprobe/.ControlProbe --es target "$package" --es quick "$quick"
  for attempt in $(seq 1 30); do
    if adb logcat -d -s WanBoxAuditProbe:I | grep -q PROBE_SENT; then break; fi
    sleep 1
  done
  adb logcat -d -s WanBoxAuditProbe:I | grep -q PROBE_SENT
  adb logcat -d -s WanBoxAuditProbe:I | grep -q PRIVATE_ACTIVITY_REJECTED
  for attempt in $(seq 1 10); do
    adb shell uiautomator dump /sdcard/audit-window.xml
    adb pull /sdcard/audit-window.xml probe/window.xml
    if grep -q 'shortcut\|proxy connection\|代理连接' probe/window.xml; then break; fi
    sleep 1
  done
  grep -q 'shortcut\|proxy connection\|代理连接' probe/window.xml
  if adb shell dumpsys activity services "$package" | grep -E 'ServiceRecord.*(VpnService|RootTunService|ProxyService)'; then
    echo 'External caller changed service state without user confirmation' >&2
    exit 1
  fi
  adb shell input keyevent KEYCODE_BACK
done
echo 'Independent external APK could not invoke private activity or change proxy state without consent.'
