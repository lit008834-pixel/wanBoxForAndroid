# preview.13：节点切换与国内外分流

作者：@author 雾晚

## 审查基线

- wanBox preview.12/main：`8f967cfefbf06784beff147d25090c27d8cc1acb`。
- NetProxy v8.4.1 实际提交：`8e2ff673c5342eb5402c186b56773e8b830271b6`（annotated tag 对象 `4a9530d2f5004e9bf39509327e669e4f8abba3a3`）。
- 官方 sing-box alpha.10：`c992297988288565a24a6d36e2cf4d77cb835fcd`，本次不升级。

参考：[NetProxy 默认配置](https://github.com/Fanju6/NetProxy-Magisk/blob/v8.4.1/src/module/config/singbox/config.json)、[分应用页面](https://github.com/Fanju6/NetProxy-Magisk/blob/v8.4.1/src/android/app/src/main/java/com/fanjv/netproxy/feature/apps/presentation/AppsViewModel.kt)、[发布](https://github.com/Fanju6/NetProxy-Magisk/releases/tag/v8.4.1)。按实际源码审查行为，未复制整个配置或上游核心。新实现沿用本项目 GPL-3.0-or-later。

| 候选 | wanBox 现状/处理 | 原因 |
| --- | --- | --- |
| 原生切网监测 | 保留官方核心原生接口监听及 auto_detect_interface | 不增加 App 后台轮询、唤醒锁或每次切网强制重启 |
| 快照去重与失败回滚 | 已有，保留 | 使用模块内容比较、generation、expectedRevision 与原子应用 |
| 分应用保存自动应用 | 实施，复用 400ms 队列 | 参考保存后合并提交的交互；旧实现仍需手动应用 |
| 国内域名/IP 直连兜底 | 实施为可选预设 | 复用 wanBox 数据、规则模型及官方字段 |
| 私有 eBPF、DNS group、resolve.match_only | 不移植 | NetProxy 使用 reF1nd 定制核心，wanBox 官方 alpha.10 不提供同等字段 |
| 双网络、Wi-Fi 临时模式、多用户 UID 策略 | 暂缓 | 权限和产品语义不同，没有当前可复现缺口，不扩大本次范围 |

## 节点切换根因和修复

原节点点击依赖 UI 缓存的服务状态、每行后台协程和 tryLock，并共用 400ms 连续编辑窗口。快速点击可能不按点击顺序持久化；模块处于 Connected 时换节点又不触发状态变化回报，旧回报可能覆盖新选择。

主列表和小组件节点选择弹窗共用同一入口，不提前把所选节点写成实际运行节点。现在 UI 把点击按顺序交给应用生命周期拥有的 `ProfileSelection` 有界队列；保存新选择后立即唤醒 `CoalescedReload`。停止状态仍仅保存，不启动。提交中不取消事务；后续点击只保留最后待提交选择。包括切回原节点、同一节点重试。规则/设置连续编辑继续用 400ms 合并。

`RootTunService` 同时观察运行 profileId，Connected → Connected 的节点变化也通知界面。`MainActivity` 的 Root 回报只更新 currentProfile，不回写 selectedProxy。IP 查询和连接检测在运行节点与选择不一致时失败/退休旧结果，不能把旧节点数据显示给新选择。实际切换仍经过配置生成、模块验证及核心重启，不能保证零断流；失败沿用模块回滚和原有错误提示。

## 规则与 DNS

预设由两个 enabled/direct 规则组成：`geosite:cn`、`geoip:cn`。这是域名集合与 CIDR 数据，不是对 IP 字符串做后缀比较。原有等价纯直连规则保留 ID、名称、顺序，仅启用；缺项才新增。两条规则在 Room 事务内保存，重复应用不重复。确认后将全局模式切为规则模式并启用现有 DNS routing；完整自定义配置入口提示自行编辑，不静默覆盖。

系统 DNS/防回环规则 → 显式应用/域名/IP/有范围规则及自定义 JSON → 纯规则集或纯 geosite/geoip 兜底 → 原有 final。各层内保持原排序，不改数据库 userOrder。中国域名按 DNS 路由走现有直连解析器，其余沿用远程解析器；显式应用/域名 DNS 规则优先。纯地理 IP 兜底前加入 `resolve` / `timeout=3s`，只加入一次，使用现有 DNS 选择，不强制公共 DNS，不使用私有 match_only。这可能为此前未匹配的域名增加解析工作，不作为性能提升承诺。

用户已有明确的代理/直连/阻断规则依旧优先，因此“国内直连、其余代理”是兜底，不覆盖全部自定义规则。全局分应用绕过名单中的应用仍不进入核心规则。正常 final 继续使用所选节点；全局模式原有语义保留。

## 测试

- Kotlin：保存监听、400ms 合并、立即切换、取消/失败恢复、连续点击返回原节点、旧回报保护、规则优先级、预设等价/重复检测和真实序列化 fixture。
- Go：由当前官方核心解析 Kotlin 导出 fixture，验证域名根/子域名/非后缀及 IPv4/IPv6 CIDR 内外；`TestDomesticResolveConfig` 验证 resolve/timeout schema 和 box 生命周期。
- Android API 35 CI：真实 Room 保存预设两次、保留旧规则字段，生成 FakeIP 开/关和全局模式配置，检查应用覆盖、CN DNS、resolve 顺序及模块快照。
- 发布 CI 另检查旧版覆盖升级、OwnBox 共存、签名和 ARM64 两种模块。

本地 Windows 的完整 libcore 配置测试被 wireguard-go 的 Windows wintun go.sum 依赖阻塞，未为此改依赖 pin；交由既有 Linux CI 执行。上述源码/模拟器测试不等于 Root 真机长期切网、吞吐或功耗测试，不提供未测数字。

人工验收：连接时依次点 A → B → A，最后选择与模块运行节点应相同；单点 B 无需等编辑合并窗口；失败后重选 B 可重试；停止时点节点/改名单不自启。路由预设重复应用不重复，指定应用代理规则优先，国内站直连、其余走所选节点；完整自定义配置不被修改。

本地执行结果：260 项 Android 单元测试、`:app:assemblePreviewDebug`、`:app:compilePreviewDebugAndroidTestKotlin` 通过；`go -C rootmodule test ./...`、`go -C rootmodule vet ./...`、`go -C libcore test ./internal/appowner`、安全契约检查及 `git diff --check` 通过。Python 模块测试 7 项中 3 项通过、4 项 Linux/安装环境测试跳过；Linux 发布 CI 执行这些检查。
