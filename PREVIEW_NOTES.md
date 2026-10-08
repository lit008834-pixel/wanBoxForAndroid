# wanBox 3.0.7-preview.11 · 路由与后台刷新修复

作者：@author 雾晚

## 本次更新

- **路由规则应用修复**：保存、删除或排序规则后，从当前数据库重新生成模块配置，执行原有校验、原子应用与失败回滚；不再只重启旧快照。停止状态不会因编辑规则而自动连接。
- **返回前台刷新修复**：模块状态与网速读取不再等待 App 的网络回调。规则单独指定的节点与 endpoint 纳入实际流量计数。
- **落地 IP 查询修复**：缓存按节点与有效期隔离，退到后台取消查询和旧回调；本地代理请求可及时取消，失败时不退回直连并误报运营商 IP。IP 栏显示默认出口，独立应用路由应在目标 App 中检查新连接的出口。
- **移除通知栏通知与控制按钮**：取消运行、订阅更新、测速通知和通知权限弹窗，启动时清理旧版通知；应用内停止、切换、测速进度及订阅更新保留。
- **模块运行优化**：连接就绪后停止启动探测；相同内容的配置提交避免重复写盘、校验和重启，空闲统计避免重复写入。
- **管理 APK 精简**：排除仅供模块使用的重复独立核心，保留管理器 JNI、协议支持和模块核心，继续采用压缩原生库打包。该变更减少重复打包，不作为未经实测的耗电或吞吐提升宣称。

## 安装与更新

仅面向 **Android 12 及以上、arm64-v8a、已 Root 设备**。管理 APK 需要配套 Root 模块，不能单独作为 VPN 客户端连接。

1. 选择下列任一模块 ZIP，在 Magisk/KernelSU/APatch 管理器安装。音量上切换选项、音量下确认。
2. 默认保留全部数据；也可选择仅保留节点与订阅、全新安装。后两项会清除路由和其他设置，请先导出完整备份，并打开配套新版 App 完成安全数据处理。
3. 带 APK 模块支持安装/覆盖更新或跳过 APK；不带 APK 模块需手动覆盖安装新版管理 APK。覆盖安装沿用原签名，不先卸载 App。
4. 模块更新沿用原热更新流程；核心切换可能短暂断流，安装期间勿重复刷入。未完成热切换时按管理器标准重启流程应用。

保持包名 `com.lit008834.pixel.wanboxforandroid`、发布签名、用户数据库/备份格式和 sing-box `v1.15.0-alpha.10`。本版 APK `versionCode=1760`，模块 `versionCode=352`，可覆盖更新 preview.10。

## 下载文件

- `wanBoxForAndroid-3.0.7-preview.11-arm64-v8a-release.apk`：原签名管理 APK。
- `wanbox-root-module-with-manager-arm64-v8a.zip`：带管理 APK，可选择安装/覆盖更新或跳过。
- `wanbox-root-module-arm64-v8a.zip`：不带 APK。
- `SHA256SUMS`：以上文件的 SHA-256 校验和。

## 验证记录

本次代码本地 238 项 Android 单元测试、Preview Debug 构建与设备测试源码编译通过。发布流程在生成新版本后执行 Go 测试/race/vet、模块与 APK 校验、签名/版本检查、旧版覆盖安装、OwnBox 共存及 API 35 模拟器的数据与实际路由配置生成测试，全部通过后发布。

实现记录：[模块运行优化](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.11/docs/root-runtime-optimization.zh-CN.md)、[前台与路由修复](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.11/docs/foreground-routing-fixes.zh-CN.md)、[安装器选项](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.11/rootmodule/install-options.zh-CN.md)。
