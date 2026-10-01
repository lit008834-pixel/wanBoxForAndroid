# 双列卡片与应用图标选择改造

作者：雾晚

## 基线与范围

基线是 preview.4 的主分支提交 `8b98597df19249e786c4f4cf78366dd22f0b50c9`，本地原分支 `fix/manual-double-column` 的源码树与它一致。修改前只有原有 `.fixture/`、`.preview-artifacts/`、`tools/__pycache__/` 未跟踪目录，全部保留。

按用户截图澄清，删除设置页“修改应用图标”，而非工具页自定义桌面快捷方式功能。因此 `CustomIconFragment`、`CustomIconManager`、双文件 ZIP 导入、桌面快捷方式创建及自定义磁贴均保留原实现。删除 `AppIconDialog` 和它专用的布局、文案、设置入口与 `AppIconManager.set/loadIcon`。`AppIcon` 仅保留启动别名身份，`AppIconManager.current/init` 保留旧安装入口检查。Manifest、系统应用图标、电视入口、历史 activity-alias 均不变。

## 双列卡片

- `ProfileCardStyle` 在每次绑定时完整应用模式，切回单列恢复无限行、无省略、纵向排布、原 padding 与 layout weight/margin。
- 双列标题最多一行、中间省略，保留尾部辨识信息；完整文本通过无障碍与编辑/详情保留。
- 双列地址/流量最多一行，地址末尾省略；单列保留完整多行。
- 选中/连接使用紧凑 ✓/● 标记，原完整文字仍作为卡片无障碍状态描述；未选中不留标记行。
- 协议与状态按实际可用文本宽度横向合排，过窄时纵向；只调整文本排布，不改变双列列数。状态最多两行，保留原始文本，点击可查看完整测速/错误。
- 标题上下 padding 由 8dp 减至 2dp，详情底部由 4dp 减至 2dp，状态底部由 12dp 减至 4dp。保持 wrap_content、系统字体缩放、48dp 菜单触控、卡片间距。
- `UiLayoutPolicy.columns(true)` 保持两列；不改排序、拖拽、代理数据、测速算法、服务与数据库。

## 验证

新增资源契约测试与设备测量测试，覆盖英文/中文、浅色/深色、1×/2×字体、152/192/320dp 卡片宽度、长名称/IPv6、无结果/延迟/速度结果/错误，以及同一视图单双列反复切换还原。

本地没有连接 Android 设备（adb devices 为空），因此无法提供真实设备前后截图，也未进行真机视觉验收或 Root/VPN 联网测试。设备自动测量不能代替人工视觉验收。实际测试和 Lint 结果将记录在发布说明。

正式版 3.0.4 的版本码 1670 高于 preview.4 的 1665，沿用包名和签名；发布工作流以 preview.4 为覆盖升级验证基线。
