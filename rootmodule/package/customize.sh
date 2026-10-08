# @author 雾晚
SKIPMOUNT=true
[ "$BOOTMODE" = true ] || abort '请在 Magisk 或 KernelSU 管理器中安装'
[ "$ARCH" = arm64 ] || abort '此模块包只包含 arm64-v8a，请勿安装到其他架构'
[ "$API" -ge 31 ] || abort '需要 Android 12 或更高版本'
if [ -x /data/adb/modules/wanbox/bin/wanboxctl ]; then
  /data/adb/modules/wanbox/bin/wanboxctl stop || abort '旧核心未完成清理，停止升级'
fi
set_perm_recursive "$MODPATH" 0 0 0755 0600
set_perm "$MODPATH/bin/wanboxctl" 0 0 0700
set_perm "$MODPATH/bin/rootbox" 0 0 0700
set_perm "$MODPATH/service.sh" 0 0 0700
set_perm "$MODPATH/uninstall.sh" 0 0 0700
ui_print 'wanBox：首次安装不接管网络，请在 App 中提交配置并连接'
ui_print '配置保存在 /data/adb/wanbox，模块升级和卸载均保留恢复副本'
