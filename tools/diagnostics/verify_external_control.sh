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
# @author 雾晚: the disposable Google APIs image's setup wizard can hang and
# cover the target with its ANR dialog. Disable only that unrelated package;
# keep Android error dialogs enabled so target crashes remain visible.
if adb shell pm list packages | tr -d '\r' | grep -Fxq 'package:com.google.android.googlesdksetup'; then
  adb shell am force-stop com.google.android.googlesdksetup
  adb shell pm disable-user --user 0 com.google.android.googlesdksetup
fi
adb shell wm size 720x1280
adb shell wm density 320
adb shell am force-stop com.google.android.apps.nexuslauncher || true
for quick in ui.QuickEnableShortcut ui.QuickDisableShortcut QuickToggleShortcut; do
  adb shell am force-stop "$package"
  adb shell am force-stop com.wanbox.auditprobe
  adb logcat -c
  adb shell am start -n com.wanbox.auditprobe/.ControlProbe --es target "$package" --es quick "$quick"
  for attempt in $(seq 1 30); do
    if adb logcat -d -s WanBoxAuditProbe:I | grep -q PROBE_SENT; then break; fi
    sleep 1
  done
  adb logcat -d -s WanBoxAuditProbe:I | grep -q PROBE_SENT
  adb logcat -d -s WanBoxAuditProbe:I | grep -q PRIVATE_ACTIVITY_REJECTED
  adb logcat -d -s WanBoxAuditProbe:I | grep -q QUICK_ACTIVITY_REJECTED
  if adb shell dumpsys activity services "$package" | grep -E 'ServiceRecord.*(VpnService|RootTunService|ProxyService)'; then
    echo 'External caller changed service state through a private control' >&2
    exit 1
  fi
  adb shell input keyevent KEYCODE_BACK
done
echo 'Independent external APK could not invoke private controls or change proxy state.'

# @author 雾晚: only this disposable emulator gives the independent probe the HOME role.
# Prove real published shortcuts can launch private controls through LauncherApps,
# rather than treating an ordinary same-UID startActivity as launcher verification.
# The static shortcut resource targets the shipping ID, not Debug's .debug suffix.
release_apk=$(find app/build/outputs/apk/oss/release -name '*x86_64*.apk' | head -n 1)
test -s "$release_apk"
adb install -r "$release_apk"
AAPT=$(find "${ANDROID_HOME}/build-tools" -name aapt | sort -V | tail -n 1)
package=$("$AAPT" dump badging "$release_apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
test -n "$package"
original_home=$(adb shell cmd role get-role-holders --user 0 android.app.role.HOME | tr -d '\r')
cleanup() {
  adb logcat -d > probe/external-control-logcat.txt || true
  adb shell cmd role remove-role-holder --user 0 android.app.role.HOME com.wanbox.auditprobe || true
  if [[ -n "$original_home" ]]; then
    adb shell cmd role add-role-holder --user 0 android.app.role.HOME "$original_home" || true
  fi
}
trap cleanup EXIT
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.wanbox.auditprobe
for shortcut in enable disable toggle; do
  adb shell am force-stop "$package"
  adb shell am force-stop com.wanbox.auditprobe
  adb logcat -c
  adb shell am start -n com.wanbox.auditprobe/.ShortcutLaunchProbe --es target "$package" --es shortcut "$shortcut"
  for attempt in $(seq 1 20); do
    if adb logcat -d -s WanBoxAuditProbe:I | grep -q "LAUNCHER_SHORTCUT_STARTED $shortcut"; then break; fi
    sleep 1
  done
  adb logcat -d -s WanBoxAuditProbe:I | grep -q "LAUNCHER_SHORTCUT_STARTED $shortcut"
  sleep 2
  if adb logcat -d -s AndroidRuntime:E | grep -q 'FATAL EXCEPTION'; then
    echo "Shortcut $shortcut crashed during platform launch" >&2
    exit 1
  fi
done
echo 'LauncherApps accepted all three published private system shortcuts without an app confirmation.'
