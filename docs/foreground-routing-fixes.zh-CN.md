# 返回前台、出口 IP 与路由重载修复

作者：@author 雾晚

## 代码证据与修复

1. `RouteSettingsActivity` 和 `RouteFragment` 保存/删除/排序后调用 `SagerNet.restartService()`；纯模块版本将其改为 CLI `restart`，只重启已经保存在模块中的旧快照。因此 Room 中的新路由并没有提交。现在重新读取节点、规则、设置，生成 `RootModuleSnapshot`，执行原有校验、原子应用和失败回滚；运行状态以模块 `status` 为准，停止时不擅自启动。规则编辑串行等待已有变更操作完成，避免 `module_busy` 丢掉最后一次编辑。
2. Root watchdog 原来只订阅 `proxy` 和 `bypass` 两个出站的流量；单独被规则指定的节点 tag 不在其中。现在按实际配置收集代理出站、直连出站和 endpoint 的唯一 tag，分别累加实际计数；使用同一个官方 ConnectionTracker，路由选定的出口只计一次，不为 detour 的每个中转重复增加速率。计数区分 uplink/downlink，零值与整数溢出有测试。
3. UI 观察器此前先等待 Android 网络监听启动，再读取模块状态。模块自身拥有物理网络监测；App 的网络监听迟到或注册失败不应阻塞模块状态与速度。现在两个任务同属绑定观察器的取消范围，立即读取状态，保留原 2 秒前台刷新；解绑后均取消，不添加后台轮询、不重启核心、不增加 wake lock。
4. `StatsBar.refreshLandingIp()` 曾直接返回缓存，绕过 Manager 的 60 秒 TTL；同步 Go HTTP 请求的 60 秒超时也不会因外层协程 2.5 秒超时立即中断。现在用单调时间、节点绑定和请求代次校验缓存；进入后台取消并清空该 UI 查询，返回前台从模块状态重新刷新。
5. IP 请求改用可取消的异步 OkHttp，经本地 mixed HTTP proxy，认证仅发往本地代理，单次调用 2.8 秒总预算、响应限 64 KiB；查询已有源与解析逻辑保留，选出结果后取消其余请求。代理失败不再退回本机直连并误报运营商出口。关闭 mixed 入站时明确返回查询不可用，不调用不存在的 App 进程 core。取消不伪装成功；旧节点或旧界面请求不得写回缓存；失败不一直显示“查询中”。

上述改动针对观察状态、查询生命周期和配置提交的明确缺口。没有改变 Android 系统冻结策略，不能保证所有厂商设备的真实网络恢复时间；也不能仅凭 UI 更新声称菲律宾节点已经实际传输成功。IP 栏显示默认出口，某个 App 的独立路由出口应在该 App 内检查。全局模式原有优先级、完整自定义 JSON、包名/UID 匹配方式不变。

## 通知栏移除

按用户确认，移除的是 wanBox 通知栏通知及其控制按钮。当前纯 Root 版本由模块持有核心，App 绑定观察器不调用 `startForeground()`；没有通过隐藏系统强制的前台服务通知规避 Android 规则。移除订阅更新与测速的通知创建/发送逻辑、测速通知辅助类、Android 13 通知权限申请及 Manifest 权限。App 启动和配置变化时取消本 App 旧通知并删除四个旧通知频道，清除旧版的停止/切换/重置按钮。保持应用内控制、测速进度、订阅 WorkManager 更新和模块守护逻辑。未注册的旧 ProxyService/ServiceNotification 不作为当前运行入口，不新增常驻服务。

`RootNotificationRemovalContractTest` 覆盖当前服务注册边界、辅助通知入口移除、订阅任务保留、旧通知清理与权限弹窗移除。

## 自动化验证项目

- `RootRouteReloadContractTest`：路由改动重新构建和应用、使用模块真实状态、后台取消查询且不自动测速。
- `RootModuleObservationTest`：网络回调长期等待/注册异常均不挡首次状态；取消后无继续采样，监听任务释放。
- `LandingIpCacheTest`：60 秒过期、不同节点隔离、旧请求无法覆盖新结果。
- `LandingIpHttpTest`：真实 loopback proxy 请求格式、不可本地解析的虚构域名、总超时、非成功状态、后台取消关闭 socket。
- `libcore/root_traffic_test.go`：规则独立节点、endpoint 和直连分类、去重、真实方向/计数、时间和溢出边界。
- `RootModuleSnapshotTest.savedRuleTargetChangesReachActualPortableSnapshot`：实际 Room + ConfigBuilder + Snapshot 生产链，虚构节点的规则出站更改反映在 JSON，禁用规则后移除，最后恢复原数据；不启动 TUN。

本地 `:app:testPreviewDebugUnitTest`（238 项、0 失败/错误/跳过）、`:app:assemblePreviewDebug` 与 `:app:compilePreviewDebugAndroidTestKotlin` 均实际通过；Go 统计辅助函数测试与 vet 实际通过。Windows 全 libcore 带 WireGuard 测试遇到平台依赖 `wintun` 的 go.sum 缺失，没有修改依赖来绕过，Linux 完整验证交由 Actions。设备联网、长时熄屏与厂商冻结恢复尚需真机验证，不把源码契约或无 Root 模拟器当作此验证。

## 固定升级基线

运行期间 GitHub 的 `v3.0.7-preview.2` Release 被移除，原下载门禁返回 404。保持正式版 3.0.6 覆盖安装测试及 OwnBox 共存测试；旧预览附件不可用时，使用已成功的固定 Actions `37780199705` / commit `8b8f8d6c01286476fc72b243e2a2a0d75158b630` 内部签名 x86_64 preview.10 APK。校验运行结果、HEAD 和 APK SHA-256 `905a0c07b6241368bbf4f1fe18f6021af5d8609c5e6eb4c20a3ced5fc363aab1` 后测试覆盖更新，不删除门禁，不重发 Release。归档过期则门禁明确失败。

## 真机复核

安装时保留全部数据；使用同一网络、节点与配置：连接后后台/熄屏 30 分钟及数小时，再返回确认状态/速率自动刷新；分别测试 Wi-Fi/蜂窝切换。保存某个 App 到菲律宾节点的规则后，确认该 App 新建连接的出口，测试启用、禁用、修改目标、删除和排序；原有连接可能需在目标 App 中重新建立。同时确认全局模式、默认节点 IP 查询、mixed 认证、关闭 mixed、失败与快速返回后台均不误报旧成功。
