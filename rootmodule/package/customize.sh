#!/system/bin/sh
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
if [ -f "$MODPATH/manager.apk" ]; then
  "$MODPATH/bin/wanboxctl" __internal install-manager || ui_print '管理 APK 未自动安装，请手动安装本版本发布的 APK'
fi
"$MODPATH/bin/wanboxctl" __internal schedule-install "$$" || abort '安装校验或后台更新任务启动失败，未替换当前模块；请检查模块状态'
ui_print 'wanBox：首次安装不接管网络，请在 App 中提交配置并连接'
ui_print '正在等待管理器完成安装，再后台应用新版本；请暂勿重启或修改配置'
ui_print '热更新成功后无需重启手机；连接中的核心会短暂重启'
ui_print '若后台更新未完成，将保留暂存包，按管理器标准流程在下次开机应用'
ui_print '默认保留全部数据；其他方式请先在 App 的模块更新数据页面处理'
