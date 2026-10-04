# 参考项目筛选与稳定性改进（2026-10-04）

作者：@author 雾晚

## 基线与边界

- 目标仓库 origin：`https://github.com/lit008834-pixel/wanBoxForAndroid.git`；upstream：`https://github.com/Own716/OwnBoxForAndroid.git`。两个默认分支均为 main，push URL 各自相同；本任务没有 push。
- 起始本地分支 `fix/urltest-lifecycle-crash`、HEAD `e0776a4`，没有已跟踪文件的未提交改动；已有 `.fixture/`、`.preview-artifacts/`、`tools/__pycache__/` 保留。
- 显式更新 origin/main 后，从 `533ac6d2df78b05da5d2dcc5314fe9d7539b8640` 创建本地分支 `fix/reference-stability-review`。它是上述修复的合并提交；没有将用户文件 reset、stash 或覆盖。
- OwnBox main：`406257c7c0c23966021ad2a64a382ada533fb59a`；与 wanBox 共同祖先：`55f0e6878fe35c192c40101ef300ad3a448d0296`。
- Throne main：`3b29800c463a3bc7daac4951a4dd28b02a3558ba`，只读获取目标文件；没有更改其他仓库工作树。
- 检查了祖先目录和项目子目录中的 AGENTS.md，没有发现；阅读了 README、构建配置、CI、已有安全/生命周期、备份/测速和上游筛选记录。README 的旧核心描述不能代替实际 pin。
- 实际版本：正式元数据 `3.0.5/337`，Preview `3.0.6-preview.2/339`；applicationId `com.lit008834.pixel.wanboxforandroid`，namespace `io.nekohasekai.sagernet`。签名、版本与包名未修改。
- 核心保持 `v1.15.0-alpha.10`，官方核心对应 `c992297988288565a24a6d36e2cf4d77cb835fcd`；Gradle 8.10.2、JDK 17、Go 1.25.5、compile/target SDK 35、min SDK 21。现有架构为 Kotlin/Fragment/Preference/XML、Room 和 Go bridge，不改为 Compose 或引入新依赖。

## 功能/质量对照与取舍

| 候选 | 参考源码证据 | wanBox 当前状态 | 收益与 Root/VPN 适用性 | 风险/验证 | 结论 |
| --- | --- | --- | --- | --- | --- |
| 网络观察者异常隔离 | OwnBox/Throne 的 DefaultNetworkListener 同样直接遍历回调；Throne SubscriptionQueue 对报告观察者分别捕获异常 | Put/Update/Lost 中一个回调异常可终止共享 actor，其他 box 不再收到网络事件 | 减少切网通知被单个失效观察者截断；保留当前两种服务的网络记账 | 小；故障回调、后续事件、取消异常、观察者列表变化及接线契约 | 做：本地独立实现，不复制参考文件 |
| 订阅 DNS 任务归属及总并发限制 | Throne SubscriptionClient/SubscriptionQueue 使用有所有者的任务/队列；与 wanBox 的直接更新架构不同 | forceResolve 使用 GlobalScope 和每次创建的五线程池，取消父任务后子任务仍可写结果，多组更新各自占池 | 取消后阻止迟到结果写入，跨订阅共享五个解析名额；保留 VPN/FakeDNS 与 Root 原解析路径 | 小；真实协程并发、取消、阻塞 DNS 迟到、失败继续、SNI/IP 偏好测试 | 做：只改现有 DNS 阶段 |
| 网络线程/注册与风暴处理 | 两个参考项目的监听器没有提供比本地更完整的退出保证；Throne 版本没有本地 500ms 防抖 | 已有 processed.await、专用 HandlerThread、旧 Lost 匹配、API fallback 和诊断 | 覆盖会退回既有首次连接/切网修复 | 高；需多 API 真机 | 保留 wanBox，不整文件替换 |
| 批量测速和活动服务延迟 | OwnBox 的组测速仍有不同清理路径；Throne CoreUrlTest 使用另一套 core/TestRequest 桥接 | 已有 NodeTestRunner 全局四名额、取消和临时 core 清理；活动服务区分 VPN core/Root mixed 探测 | 重复实现无额外明确收益，跨桥接移植风险大 | 高；涉及核心、真实指标及两种服务 | 不做 |
| 仪表盘自动接入 | OwnBox WebviewFragment 对远程面板设置控制器及 secret/query | 本地 YACD 已有局部初始化、鉴权与远程边界保护 | 不能以零配置换取远程泄露本地 secret | 高；凭据边界 | 不移植 |
| 备份、订阅报告、JSON/路由架构 | 本地已有便携备份、输入上限、安全审计和回归；Throne 的订阅报告/队列属不同 IPC 模型 | 不是空白功能，最近已有格式/生命周期修复 | 数据格式及业务流程重构超出本次小改收益 | 高；迁移、备份、Room、核心映射 | 暂缓，不根据 README 宣称更优 |
| 通知、双网络/策略扩展 | OwnBox 近期通知、负载均衡及核心提交覆盖多个模块 | 本地已选择性处理业务拨号与健康探测延迟的区分 | 没有本次可复现证据支持改变通知/调度/网络默认值 | 中至高；需要因果证据和设备回归 | 暂缓 |

以上引用的是所列 commit 的实际源码，不是功能清单。两参考仓库均有 GPL-3.0 声明。本次没有直接复制源码，没有删除现有版权；只借鉴异常隔离与任务所有权原则，并用 wanBox 现有协程架构独立实现。历史已移植提交见 `upstream-network-review.zh-CN.md`，本次没有重新 cherry-pick。

只读源码依据：[OwnBox 网络监听](https://github.com/Own716/OwnBoxForAndroid/blob/406257c7c0c23966021ad2a64a382ada533fb59a/app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt)、[OwnBox WebView](https://github.com/Own716/OwnBoxForAndroid/blob/406257c7c0c23966021ad2a64a382ada533fb59a/app/src/main/java/io/nekohasekai/sagernet/ui/WebviewFragment.kt)、[Throne 订阅队列](https://github.com/throneproj/ThroneForAndroid/blob/3b29800c463a3bc7daac4951a4dd28b02a3558ba/app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionQueue.kt)、[Throne 订阅客户端](https://github.com/throneproj/ThroneForAndroid/blob/3b29800c463a3bc7daac4951a4dd28b02a3558ba/app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionClient.kt)、[Throne 核心测速](https://github.com/throneproj/ThroneForAndroid/blob/3b29800c463a3bc7daac4951a4dd28b02a3558ba/app/src/main/java/io/nekohasekai/sagernet/bg/proto/CoreUrlTest.kt)。

## 实现与调用链

### 网络通知

`DefaultNetworkListener` 的 Put/Update/Lost → `NetworkObserverDispatcher.dispatch` → 注册的应用/服务/NativeInterface 观察者。

新分发器先拍摄观察者列表快照，对每个同步观察者捕获 Exception、交给现有 Logs.w 诊断，继续其他观察者和后续事件。已退役观察者抛出的 CancellationException 不代表共享 actor 应取消。致命 Error 不被吞掉。Start 首次回调/失败回滚、Stop、Get、注册/fallback、专用线程、500ms 防抖和旧 Lost 判断保持原样；不宣称修复所有 actor 错误或新增线程退出机制。

VPN 的 in-process core 仍通过 `moe/matsuri/nb4a/NativeInterface.kt` 同步注册/注销默认接口监视器；`BaseService` 的默认网络记账仍服务于既有服务流程。Root 的独立 su 核心进程及其 Go 监视器没有替换或修改。本次不是把 Root 原生接口监视器改为应用内监视器。

### 订阅强制解析

`RawUpdater.doUpdate` → `GroupUpdater.forceResolve` → `SubscriptionResolutionRunner.run` → 现有 DNS API → `rewriteAddress` → 后续原有导入流程。

- 每次 DNS 子任务归属调用方 coroutineScope；进程内共享 IO.limitedParallelism(5) 和 Semaphore(5)，名额覆盖完整解析生命周期，跨订阅也不超过五个。
- 解析前、解析返回后及进度更新前检查取消；CancellationException 重新抛出，普通单节点失败继续处理其他节点；withPermit 自动释放名额。不创建/遗留每次更新专用线程池。
- Android/系统阻塞 DNS 不能保证立即被取消。父任务仍等待其返回，但返回后取消检查阻止写入迟到结果；没有用缩短系统超时或后台脱离任务伪造即时取消。
- 对 VPN + FakeDNS + 已启动服务，仍走底层 Network.getAllByName；Root/其他情况仍走 InetAddress.getAllByName。读取底层网络时取单次局部快照，避免两次读取之间变成 null。没有改解析服务器、路由、DNS 默认或权限。
- 保留 Naive 和 IP 字面量跳过、IPv4/IPv6 偏好及原/显式 SNI；进度最大值仅统计真正需要解析的节点，失败日志只记录异常类别，不带节点地址/解析器报错正文。
- 本次没有改变整个订阅更新事务、组更新保留集合或 UI 确认流程；不宣称整个订阅生命周期的所有取消路径已被审计完毕。

## 修改文件

- `app/src/main/java/io/nekohasekai/sagernet/utils/DefaultNetworkListener.kt`
- `app/src/main/java/io/nekohasekai/sagernet/utils/NetworkObserverDispatcher.kt`（新增）
- `app/src/main/java/io/nekohasekai/sagernet/group/GroupUpdater.kt`
- `app/src/main/java/io/nekohasekai/sagernet/group/SubscriptionResolutionRunner.kt`（新增）
- `app/src/test/java/io/nekohasekai/sagernet/NetworkLifecyclePreservationTest.kt`
- `app/src/test/java/io/nekohasekai/sagernet/utils/NetworkObserverDispatcherTest.kt`（新增）
- `app/src/test/java/io/nekohasekai/sagernet/group/SubscriptionResolutionRunnerTest.kt`（新增）
- `app/src/test/java/io/nekohasekai/sagernet/group/SubscriptionAddressRewriteTest.kt`（新增）
- 本文。

## 验证记录

使用独立 ASCII 验证目录 `C:/Users/Public/Documents/Codex/wanbox-reference-review-20261004`，避开现有 Windows 中文路径的 AIDL 工具问题。只复制已跟踪源码及本次新增测试/实现，不复制用户备份或签名密钥；复用与基线匹配的既有 CI 原生 AAR/Root 二进制，没有触发远端构建。

- `./gradlew.bat app:tasks --all`：成功，先确认 Preview Debug 实际任务。
- 在修复前，抽取旧分发/GlobalScope 逻辑后运行两类回归测试：6 项中 5 项失败，包含回调异常、取消后写入及跨更新并发。随后改为上述实现，未删除失败断言。
- 首次完整运行 `./gradlew.bat app:testPreviewDebugUnitTest app:compilePreviewDebugKotlin app:assemblePreviewDebug app:lintPreviewDebug`：单测 164 项通过，编译及四架构 Debug APK 生成成功；lint 的在线依赖版本查询连续报告 Maven Central 连接失败，停止该次运行，没有宣称 lint 通过。
- 补齐生产接线契约和 ExperimentalCoroutinesApi 显式 opt-in 后，最终运行 `./gradlew.bat --offline app:testPreviewDebugUnitTest app:compilePreviewDebugKotlin app:assemblePreviewDebug app:lintPreviewDebug`：单测 165 项，失败/错误/跳过均为 0；Kotlin 编译与 assemblePreviewDebug 通过，四个 ABI 为 armeabi-v7a、arm64-v8a、x86、x86_64。
- 同次离线 lint **失败**，143 errors、0 warnings；所有报告位置均不在本次修改文件。首项为 `ui/AssetsActivity.kt:415` 的 MissingSuperCall，另含既有资源、API 和依赖检查问题。没有新增 baseline、关闭检查、改低严重等级或修改无关代码。整体 Gradle 命令因此退出失败，不能把单测/assemble 成功表述为所有质量检查通过。离线模式无法检查远端最新依赖版本。
- `go test -modfile=../.fixture/alpha10.mod ./internal/... ./protocol/urltest ./protocol/loadbalance`：4 个测试包通过（缓存结果）；没有修改 Go 源码。本地 modfile 仅指向同 pin 的官方 checkout，不更改仓库 go.mod/go.sum。
- `git diff --check`：通过；没有应用 ID、签名、版本、核心/依赖 pin、Manifest、Room/备份 schema、语言或 UI 资源改动。

验证目录保存 `tasks-reference-review.log`、`baseline-reference-review.log`、`verification-reference-review.log`、`final-reference-review.log` 和 `app/build/reports/lint-results-previewDebug.xml`。构建只产出本地 Debug 包，不是正式/预览发布。源码新加四组测试共 9 项，并扩展一项既有网络生命周期契约；源代码契约不替代真实 Android API 行为测试。

## 设备限制与人工回归

adb 检测到一台 API 36（Android 16）设备在线，但没有执行安装、真实联网、Root 授权、网络切换或仪器测试。新增测试是 JVM 行为测试及接线契约，不等同于真机网络验证。

建议在测试机分别使用 VPN/Root TUN 验证：

1. 同一虚构/测试订阅开启既有强制域名解析，确认地址替换保留 TLS SNI；IPv4/IPv6 用户偏好各测试一次。
2. 两个分组同时更新，更新中取消/退出页面；确认迟到 DNS 不写回，后续更新仍可执行；检查系统 DNS 返回慢时取消等待表现。
3. 首次连接、反复启停、Wi-Fi↔蜂窝、旧 Wi-Fi Lost 晚于新蜂窝 Available、飞行模式、短暂无互联网、DNS 失败/恢复；验证观察者错误不会停止其他通知。
4. 补测 API 23、24–27、28–30、31+ 的真实 callback 注册/fallback；本地契约只确认相应源码分支仍在，不能证明每个 Android/OEM 行为通过。

回滚只需撤回本次列出的实现/接线/测试和本文，不涉及用户数据库或偏好迁移。未提交、推送、创建 PR/Release、上传 APK 或向参考仓库写入。
