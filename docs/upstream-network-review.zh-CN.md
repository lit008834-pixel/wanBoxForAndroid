# 上游网络修复筛选记录

> **记录范围（历史）**：本文记录 2026-10-03 提交 `24cc4f428212318b98dc6720a5e92987a1d1cc0f`（包含于 `v3.0.5-preview.1`）时的筛选与验证；以下基线、候选差异、测试结果及发布授权均只描述当次状态，不代表当前版本或当前验证结果。截至 2026-10-06 的仓库快照为 `v3.0.7-preview.5`（`f2886b93a68a94ec4bf04cde73134e48c67e044d`），`nb4a.properties` 已列出 `PRE_VERSION_NAME=3.0.7-preview.5`、`SINGBOX_VERSION=v1.15.0-alpha.10`。当前源码仍有本文记录的业务拨号成功计数调用及 `dial_latency_test.go` 回归测试；本文其余历史比较未在此重新审计。

作者：@author 雾晚

## 基线与安全边界

- 起始分支 `fix/icon-picker-compact-cards`，HEAD `3755a482e7e9c6c443bb39278a817b6cc02b6162`。
- origin/main：`54bc513902c606093cb268ae299277b4ad651ca4`，仅包含该 HEAD 的合并记录，源码相同。
- 起始仅有未跟踪 `.fixture/`、`.preview-artifacts/`、`tools/__pycache__/`，保留不动。
- 实际起始 remote 只有 origin；新增指定 Own716 URL 为本地 upstream，未向其推送。
- 两个远端默认分支均为 main。浅克隆补齐后，共同祖先为 `55f0e6878fe35c192c40101ef300ad3a448d0296`。
- upstream/main：`e8b3cb9330c4b72147b3b5c98cf2abe3145ea748`。
- 工作分支 `fix/upstream-network-sync`，不整支合并或 cherry-pick。
- 核心保持 `v1.15.0-alpha.9`，官方源码对应 `132b38e9caaba1a1959354d518e54d2d08419afe`。应用 ID、签名、正式版本、用户路由/DNS 设置保持不变。

## 已移植

从 `8a90426add9fc34e86a9edea56898f8193742202` 仅移植 `nodeStats.recordDialSuccess` 及 TCP/UDP 两个成功调用点到 `libcore/protocol/loadbalance/outbound.go`。

业务连接建立耗时（尤其 UDP 本地套接字建立）不是健康 URL 探测耗时。旧代码把业务拨号时间混入同一 EMA，使低延迟策略被业务流量影响。新实现仅更新成功/失败计数与失败恢复；只有原健康探测更新延迟 EMA。不改变策略、超时、探测 URL、路由、连接包装及失败传播。VPN 和 Root TUN 均构建同一 libcore 实现，不添加模式特判。

回归测试 `dial_latency_test.go` 使用真实 TCP/UDP 拨号入口的本地测试替身，检查多次业务拨号不覆盖健康延迟、恢复失败计数、后续健康探测继续更新 EMA、失败传播以及 leastPing 排序。旧代码复现实验中，250ms EMA 被两种业务拨号降为约 4ms；新代码保留 250ms。

## 不适用 / 已有等效处理

| 上游提交 | 筛选结论 |
| --- | --- |
| `e8b3cb9` 删除 GSO | wanBox ConfigBuilder 与 SingBoxOptions 已无 gso；无需重复移植。 |
| `e9780be` 升级 alpha.9 / geoip schema | wanBox 已锁定 alpha.9，现有 geoip 已适配；不顺带调整 Go 依赖 pin。 |
| `3734f77` 策略组编辑崩溃 | 设置页面/数据库路径，不属于本次网络生命周期范围。 |
| `09dd83b` 数据库 import | 数据库范围外。 |
| `111a43d`、`af37c61` CI / `eecc80a` 密钥迁移 | 不改变 wanBox 已有发布及密钥策略。 |
| `5fcb41a`、`53aeb79` README | 无网络修复。 |

## 有风险，未移植

- `8a90426` 的固定 UDP timeout、sticky session/公共后缀哈希、零拷贝包装、活动计数补偿和重试时限调整：涉及策略和连接行为改变，缺少当前 VPN/Root TUN 真机因果证据；不批量移植。其零拷贝测试只断言标志，不能证明 Android 传输或清理安全。
- `e9780be` 将 gvisor 映射为 null：当前构建仍启用 with_gvisor；不根据上游注释改变用户栈选择。
- DefaultNetworkListener 唯一上游差异是 GlobalScope 换成 SupervisorJob scope；没有新增注销/退出生命周期，不能证明可修复注册、线程泄漏或竞态。保留完整 wanBox 实现，包括 processed.await、专用线程、500ms 防抖、耗时诊断及旧 Lost 网络匹配检查。新增源代码契约测试防止后续同步回退。
- GuardedProcessPool 上游每次 fatal 创建独立 scope；wanBox 已有可取消的 fatalScope 与 closeAndJoin。保留当前清理边界。
- BaseService/ProxyService/VpnService/ProxyInstance 上游缺少 wanBox 的 Root 委派、停止串行化、destroyRunner、同步 looper 注册与清理；不覆盖。上游配置日志还会恢复输出凭据。
- DNS、socket protect、接口传递、Manifest 网络权限没有上述候选带来的可独立验证修复；不引入新 DNS、轮询或权限。
- 上游 URLTest 改变地址/超时，HTTP 移除 HTTPS 和读取上限保护；保留 wanBox 的 URL、超时、FakeIP/Root 探测及安全边界。

## 验证范围

真实 Gradle 任务已从 `app:tasks --all` 确认，Debug 使用 ossDebug，预览发布使用 assemblePreviewRelease。

- Go：`go test ./protocol/loadbalance ./internal/... ./protocol/urltest`。
- CI 增加 `go test -race ./protocol/loadbalance`，避免新增测试仅留在本地。
- Android：`app:testOssDebugUnitTest app:assembleOssDebug`，新增 NetworkLifecyclePreservationTest 是源代码契约，不是设备切网测试。
- 完整原生库和 Root 可执行文件需要 CI 重建；本地 Android 构建使用既有 AAR，不能代替新 Go 实现的 Android 链接验证。
- `git diff --check`。
- 本地结果：Go 四个测试包通过；Android 124 项单元测试通过，ossDebug 构建通过。Windows `go test -race` 因未配置 CGO 编译器无法执行，改由 Linux CI 执行，不声明本地 race 通过。
- 没有在线设备；API 23/24–27/28–30/31+ 的实际注册、Wi-Fi↔蜂窝、飞行模式、DNS 恢复、VPN/Root TUN 启停及联网未真机验证。既有线程及 callback 生命周期问题不宣称已修复。
- 后续预览发布由本次请求末尾明确授权，仅向 origin 推送修复分支并运行验证/发布；不向 upstream 推送、不修改旧 Release。
