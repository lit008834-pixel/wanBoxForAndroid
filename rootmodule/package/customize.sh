# @author 雾晚
SKIPMOUNT=true
[ "$BOOTMODE" = true ] || abort '请在 Magisk 或 KernelSU 管理器中安装'
[ "$ARCH" = arm64 ] || abort '此模块包只包含 arm64-v8a，请勿安装到其他架构'
[ "$API" -ge 31 ] || abort '需要 Android 12 或更高版本'
set_perm_recursive "$MODPATH" 0 0 0755 0600
set_perm "$MODPATH/bin/wanboxctl" 0 0 0700
set_perm "$MODPATH/bin/rootbox" 0 0 0700
set_perm "$MODPATH/service.sh" 0 0 0700
set_perm "$MODPATH/uninstall.sh" 0 0 0700
"$MODPATH/bin/wanboxctl" __internal activate || abort '校验或热更新失败，旧模块和数据未被删除；请查看模块状态'
if [ -f "$MODPATH/manager.apk" ]; then
  "$MODPATH/bin/wanboxctl" __internal install-manager || ui_print '管理 APK 未自动安装，请手动安装本版本发布的 APK'
fi
ui_print 'wanBox：首次安装不接管网络，请在 App 中提交配置并连接'
ui_print '已直接启用模块代码，无需重启手机；连接中的核心会短暂重启'
ui_print '默认保留全部数据；其他方式请先在 App 的模块更新数据页面处理'
