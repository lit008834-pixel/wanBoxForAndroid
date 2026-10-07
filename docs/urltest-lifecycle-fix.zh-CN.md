# 自动测速组与负载均衡组启动修复

@author 雾晚

> **记录范围与当前状态：**这是 2026-10-04 合并的 URLTest/LoadBalance 生命周期故障修复记录（PR #31，已包含于 `v3.0.6-preview.2`），不是当前功能总览。截至仓库基准 `f2886b9`（`v3.0.7-preview.5`），NB4A 元数据仍指定 `sing-box v1.15.0-alpha.10`，当前 `libcore` 仍包含本文所述生命周期实现及回归测试。下文回归、备份及本地验证数据记录的是当时的证据，不表示它们已在当前版本重新运行，也不构成真机或 Root 联网验证结论。

## 证据与根因

两份本地日志均出现 URLTestGroup.Touch 空指针、URLTest.DialContext 与 net/http Transport 后台拨号调用栈，随后代理进程 SIGABRT。外层测速函数的 recover 无法捕获另一个 goroutine 的 panic。

现有自定义 URLTest/LoadBalance 使用 Start() error；固定的官方 sing-box v1.15.0-alpha.10 要求 Start(stage, scope)。出站管理器只启动实现 adapter.Lifecycle 的对象，因此组对象虽然被创建，却未初始化成员。负载均衡也存在相同问题。

## 修改

- 两个自定义出站显式实现 adapter.Lifecycle（编译期断言）。在 Start 阶段解析成员，在 Started 阶段启动原有检查；使用 Scope.Context 并提前注册关闭回调，支持启动失败回滚。
- 未就绪/已关闭时，拨号、UDP 与组测速返回错误；不依靠吞 panic。
- 关闭 URLTestGroup 不再依赖是否曾创建 ticker；关闭通道、取消监听并中断组连接。Touch 在同一锁内检查启动状态，避免关闭后重建定时器。
- 保留原有测速 URL、时间单位、选择和失败切换策略、VPN/Root TUN 分派与备份范围。

## 回归

新增 protocol/urltest/lifecycle_test.go，使用实际出站管理器与本地虚构 HTTP 204 服务，覆盖 URLTest/LoadBalance 启动前、完整分阶段启动后的 HTTP 请求、关闭后拒绝请求、重复关闭以及缺失成员的启动回滚。全部测试使用虚构数据。

本地备份为 schemaVersion=2；196 个配置、7 个分组、10 条规则、75 项设置。非空自定义规则 JSON、设置 Base64 和配置组引用通过格式检查。这不等于真机恢复/联网通过，也没有根据历史 status=3 将节点判定为永久无效。

没有可用 Root 真机；不能宣称 Root 联网回归已通过。以 CI 和本地测试实际结果为准。
