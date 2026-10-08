# wanBox 3.0.7-preview.7

作者：@author 雾晚

- 修复桌面应用图标退回 Android 默认图标：解除自适应图标前景的循环引用，恢复原有猫耳图标，保留启动入口及历史图标别名。
- 修复 VPN 跨进程 socket 保护接口接收的文件描述符副本未释放的问题；正常完成和回调异常均释放副本，拒绝不完整或多描述符请求，不关闭发送端原始 socket。
- 核验官方 sing-box v1.15.0-alpha.10 与 Android/Root 集成。核心版本保持不变；已包含的 GSO、UDP 和连接中断修复不重复移植，不宣称网速提升。
- 新增启动器资源循环回归测试、安装后图标加载/绘制测试与 SCM_RIGHTS 生命周期测试；保留现有网络监听、Root/VPN 分派、测速与路由行为。
- 预览版本 3.0.7-preview.7，Android versionCode=1740。最低 Android 12，公开 APK 为 arm64-v8a；四种原生 ABI 的编译流程保持。
- 包名、签名、正式版本、用户数据格式和核心依赖 pin 不变，可覆盖安装已有预览版。

审计依据与验证范围见 [alpha.10 集成审计](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.7/docs/alpha10-integration-audit.zh-CN.md)。
