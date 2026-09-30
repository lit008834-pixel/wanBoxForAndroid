#!/usr/bin/env bash
# @author é›¾æ™š
set -euo pipefail
# @author ÎíÍí: preserve evidence even when the external probe fails.
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
  adb shell uiautomator dump /sdcard/audit-window.xml
  adb pull /sdcard/audit-window.xml probe/window.xml
  grep -q 'shortcut\|proxy connection\|ä»£ç†è¿žæŽ¥' probe/window.xml
  if adb shell dumpsys activity services "$package" | grep -E 'ServiceRecord.*(VpnService|RootTunService|ProxyService)'; then
    echo 'External caller changed service state without user confirmation' >&2
    exit 1
  fi
  adb shell input keyevent KEYCODE_BACK
done
echo 'Independent external APK could not invoke private activity or change proxy state without consent.'
