# wanBox 3.0.4 正式版

作者：雾晚

## 本次更新

- 移除设置页“修改应用图标”入口、选择弹窗、预览布局及切换图标代码。
- 保留现有系统应用图标、启动与电视入口、历史启动别名。工具页的自定义快捷方式、图标包和 Quick Settings 磁贴功能均保持原实现。
- 仅双列节点卡片紧凑化：标题中间省略、地址与流量单行，协议与状态在宽度允许时同排显示；状态最多两行，点击可查看完整错误或测速结果。
- 选中/连接状态采用紧凑标记，保留完整无障碍状态说明。卡片减少纵向留白，菜单仍为 48dp 触控区域。
- 切回单列恢复完整多行文字与原间距。继续保留手动双列选择，不按字体或宽度自动退为单列。
- 保留 preview.4 的其他界面及真实延迟显示修复。本次没有修改内核、代理/VPN/Root 服务、排序、拖拽、数据库或签名配置。

## 安装与验证

- 版本码为 **1670**，高于 preview.4 的 **1665**；沿用 `com.lit008834.pixel.wanboxforandroid` 和原签名，可覆盖升级。OwnBox 仍可独立安装。
- 本地 `app:testOssDebugUnitTest`：122 项通过；`app:assembleOssDebug` 与 `app:assembleOssDebugAndroidTest` 成功。
- `app:lintOssDebug` 未通过：149 项报错，首项为 AssetsActivity 的 MissingSuperCall。本次未处理这些历史 Lint 问题，不能声称全局 Lint 通过。
- 本地无 Android 设备，未提供真机修改前后截图；真机视觉及 Root/VPN 联网回归未执行。仓库设备自动测量不能代替人工验收。
- 四架构签名构建及模拟器升级检查通过后发布；文件完整性校验值见 SHA256SUMS。

具体改动及边界见 `docs/compact-cards-and-icon-picker.zh-CN.md`。
