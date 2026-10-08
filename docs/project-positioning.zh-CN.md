# wanBoxForAndroid 项目定位与发展说明

## 项目起源

wanBoxForAndroid 从 OwnBoxForAndroid 的早期代码基础发展而来。项目已有独立仓库、应用身份、代码维护、版本发布和功能路线；当前版本由 wanBoxForAndroid 仓库发布。它不是 OwnBoxForAndroid 的官方版本，也不代表 OwnBox 项目的立场。

本项目不以持续同步 OwnBox 上游为目标。上游代码或设计可作为历史来源和技术参考；是否吸收某项改动，应以 wanBox 当前架构、Root 设备场景、兼容性和安全审查为准。选择性参考不改变项目独立维护的事实。项目仍遵守仓库许可证和适用的第三方许可义务；“独立”不表示抹去历史代码来源或许可证要求。

## 产品定位

wanBox 的发展方向是**面向已 Root Android 设备的代理工具**，重点围绕 Root TUN、系统流量接管和模块化后台运行演进。保留现有 wanBox 的应用界面与用户使用习惯，不以重做 UI 为项目目标。

## 当前版本与目标方向

截至 `v3.0.7-preview.7`：

- 当前代码仍包含 VPN TUN 与 Root TUN 两种既有运行路径；具体最低 Android 版本、架构和功能范围以对应 GitHub Release 为准。
- 当前 Root TUN 由 Android App 服务负责启动和管理；**它尚不等同于可以独立安装、脱离 App 生命周期运行的 Magisk/KernelSU 模块**。
- 纯 Root 模块化是后续目标：计划由 Root 管理器启动模块侧服务，由受限控制命令管理代理核心；wanBox App 作为现有 UI 风格的配置和状态管理界面。
- 未来产品目标不再维护 VPN 运行模式或 VPN 回退路径。但在代码改造、真机验证并正式发布之前，不能把历史发行版描述成“已移除 VPN”或宣称模块已实现。

## 设计原则

1. **模块拥有代理生命周期**：配置有效后由模块独立启动、监督和停止核心；关闭或强行停止 App 不应使模块核心退出。
2. **App 只做管理**：复用现有页面与操作习惯，通过 Root 授权的受限接口读取状态、提交配置和控制服务；App 不需要作为常驻代理进程。
3. **配置与数据安全迁移**：将模块运行所需配置和资源与 App 生命周期解耦，升级时保留用户数据；新配置须校验并原子提交，失败时保留最后一份有效配置。
4. **生命周期可恢复**：模块安装、开机、停止、崩溃、升级、禁用和卸载都要有明确行为，并清理本模块创建的路由和网络规则。
5. **实测后再宣称兼容**：Magisk、KernelSU、不同 Android 版本、设备厂商和 ABI 的支持范围以真实构建与设备测试为依据。Doze 与厂商省电策略可能影响休眠期间行为，不承诺所有设备绝对不断网。

## 项目资料

- [wanBoxForAndroid 源码仓库](https://github.com/lit008834-pixel/wanBoxForAndroid)
- [当前发行版与逐版本说明](https://github.com/lit008834-pixel/wanBoxForAndroid/releases)
- 历史代码来源：[OwnBoxForAndroid](https://github.com/Own716/OwnBoxForAndroid)

项目定位会随实际实现和正式发布更新；计划内容不应替代具体版本的 Release Notes。
