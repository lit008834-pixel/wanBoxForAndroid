# wanBox 3.0.7-preview.8 · 纯 Root 模块实验预览版

作者：雾晚

## 本次变更

- 将 Root TUN 运行时移到独立 Magisk/KernelSU 模块。原 Android 界面继续管理节点、订阅、规则和设置，连接/停止按钮改为控制模块。
- 移除 Android VPN 模式、授权页面与 Root 失败回退。没有 Root 或模块未就绪时明确提示错误。
- 配置和必要资源提交到模块私有持久快照；增加配置校验、并发版本冲突保护、原子切换、上一版回滚与有限崩溃重试。
- App 重开查询模块真实状态；隐藏页面不再持续轮询，不用 App 常驻前台服务持有代理核心。
- 保留原界面外观、桌面图标、磁贴、快捷方式以及现有数据库、备份和路由规则格式。核心仍为 sing-box v1.15.0-alpha.10。
- 预览版 versionCode=1745，公开 APK 和模块 ZIP 均为 arm64-v8a，最低 Android 12。App 包名和原发布签名不变。

## 安装与升级

这是架构切换的实验预览版。**请先导出备份。仅安装 APK 不能连接，必须安装配套 Root 模块 ZIP。**

1. 停止已有代理以及其他接管 TUN 的 Root 模块。
2. 用 Magisk/KernelSU 管理器安装 `wanbox-root-module-arm64-v8a.zip` 并重启。
3. 覆盖安装配套 APK，打开原节点页面，允许 Root 并连接。
4. “自动连接”保留原设置；首次安装没有有效配置不会接管网络。

模块数据保存在 `/data/adb/wanbox`，升级/卸载保留恢复副本。App 关闭后模块按设计独立运行；卸载 App 不等于停止模块，请先显式停止或禁用模块。

## 验证范围

本地管理端 218 项单元测试、Debug 构建、仪器测试编译及 ARM64 原生编译通过；模块 Go 主机测试、打包测试通过。Linux 生命周期/race、发布签名和覆盖安装按本次 Actions 实际结果执行。

Magisk/KernelSU 真机安装、自启、force-stop、网络切换/Doze、异常退出后的路由恢复尚未实测，不能当作全设备稳定性结论。Lint 尚有 174 项仓库既有问题，本次新增路径提示已处理。SIGKILL/内核异常和其他 TUN 模块冲突仍需设备验证。

完整实现、配置契约与验收清单见 [Root 模块实施说明](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.8/rootmodule/README.zh-CN.md)。
