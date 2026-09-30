#!/usr/bin/env bash
# @author 雾晚
set -euo pipefail
if [[ -n "${PROBE_TARGET:-}" ]]; then
  package=$PROBE_TARGET
else
  AAPT=$(find "${ANDROID_HOME}/build-tools" -name aapt | sort -V | tail -n 1)
  apk=$(find app/build/outputs/apk/oss/debug -name '*x86_64*.apk' | head -n 1)
  package=$("$AAPT" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
  test -n "$package"
fi
adb install -r probe/probe.apk
for quick in ui.QuickEnableShortcut ui.QuickDisableShortcut QuickToggleShortcut; do
  adb shell am force-stop "$package"
  adb logcat -c
  adb shell am start -n com.wanbox.auditprobe/.ControlProbe --es target "$package" --es quick "$quick"
  sleep 2
  adb logcat -d -s WanBoxAuditProbe:I | grep -q PRIVATE_ACTIVITY_REJECTED
  adb shell uiautomator dump /sdcard/audit-window.xml
  adb pull /sdcard/audit-window.xml probe/window.xml
  grep -q 'shortcut\|proxy connection\|代理连接' probe/window.xml
  if adb shell dumpsys activity services "$package" | grep -E 'ServiceRecord.*(VpnService|RootTunService|ProxyService)'; then
    echo 'External caller changed service state without user confirmation' >&2
    exit 1
  fi
  adb shell input keyevent KEYCODE_BACK
done
echo 'Independent external APK could not invoke private activity or change proxy state without consent.'
