#!/system/bin/sh
# @author 雾晚
# SPDX-License-Identifier: GPL-3.0-or-later
# Sourced by customize.sh; the CLI owns bounded getevent readers, not OUTFD.
wanbox_volume_key() { "$MODPATH/bin/wanboxctl" __internal volume-key "$1"; }
wanbox_manager_installed() { /system/bin/cmd package path com.lit008834.pixel.wanboxforandroid >/dev/null 2>&1; }

wanbox_choose() {
  local timeout=10 index=1 count=0 option key
  WANBOX_CHOICE=1
  for option do
    count=$((count + 1))
    ui_print "  $count. $option"
  done
  ui_print '音量上：循环切换；音量下：确认。10 秒未操作采用第 1 项。'
  while :; do
    index=1
    for option do
      [ "$index" -ne "$WANBOX_CHOICE" ] || ui_print "当前选择：$option"
      index=$((index + 1))
    done
    key=$(wanbox_volume_key "$timeout") || return 1
    case "$key" in
      up) WANBOX_CHOICE=$((WANBOX_CHOICE % count + 1)); timeout=20 ;;
      down) return 0 ;;
      timeout)
        [ "$timeout" -eq 10 ] && return 0
        ui_print '切换后未确认，取消安装；用户数据未修改。'; return 1 ;;
      *) ui_print '无法读取音量键，取消安装；用户数据未修改。'; return 1 ;;
    esac
  done
}

wanbox_install_options() {
  local key apk_title data_title='保留全部数据' apk_result='跳过'
  WANBOX_DATA_MODE=preserve
  WANBOX_APK_MODE=skip
  ui_print '━━━━━━━━ wanBox 安装数据方式 ━━━━━━━━'
  wanbox_choose '保留全部数据（默认）' '仅保留节点和订阅，重置路由与设置' '全新安装，清除节点、订阅、路由与设置' || return 1
  case "$WANBOX_CHOICE" in
    2) WANBOX_DATA_MODE=nodes; data_title='仅保留节点和订阅' ;;
    3) WANBOX_DATA_MODE=fresh; data_title='全新安装' ;;
  esac
  if [ "$WANBOX_DATA_MODE" != preserve ]; then
    ui_print '将先保存完整备份，再处理 App 和模块数据。'
    ui_print '音量下：再次确认；音量上或 10 秒未操作：取消安装。'
    key=$(wanbox_volume_key 10) || return 1
    [ "$key" = down ] || { ui_print '未再次确认，数据未修改。'; return 1; }
    ui_print '此选择由配套新版管理 App 首次打开时安全执行；完成前暂停连接。'
  fi
  if [ -f "$MODPATH/manager.apk" ]; then
    ui_print '━━━━━━━━ 管理 APK ━━━━━━━━'
    apk_title='安装管理 APK（默认）'
    if wanbox_manager_installed; then
      apk_title='覆盖更新已有管理 APK（保留 App 数据，默认）'
    fi
    wanbox_choose "$apk_title" '跳过 APK，保留现有管理 App' || return 1
    [ "$WANBOX_CHOICE" -ne 1 ] || { WANBOX_APK_MODE=install; apk_result='安装或覆盖更新'; }
  else
    ui_print '此包未包含 APK，不安装或更新管理 App。'
    [ "$WANBOX_DATA_MODE" = preserve ] || ui_print '请手动更新配套管理 APK，并打开以完成所选数据方式。'
  fi
  ui_print "已确认：$data_title；APK：$apk_result"
}
