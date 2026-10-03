# 路由规则编辑适配

作者：@author 雾晚

## 源码基线

- wanBox main：`ff4f79bbf83515ea524ce779c5a1c25b9de2b616`；开始时本地为 `fix/local-yacd-entry` / `bf7bc156bb1763a66cdd75f0ea138292a6f7ab7e`，没有已修改的跟踪文件，原有未跟踪文件保留。
- 最新预览发布：`v3.0.5-preview.1`；正式发布：`v3.0.4`。
- ThroneForAndroid `v2.0.1`：`f67f1bb4b2da3e5d89d5975810e399ab5e554ab8`。检查其 RouteRuleActivity、RouteRuleChecks、RouteRule、RuleSets、RuleSetCatalog、RuleSetPickerActivity、RouteFragment、XML 及许可。
- wanBox 核心不变：`v1.15.0-alpha.9`，实际源码 `option/rule.go`、`option/rule_action.go`；未采用 Throne 核心、出站 ID 或包名。

## 差异与选择

| 能力 | wanBox 原实现 | 本次适配 |
|---|---|---|
| 规则 ID、顺序、启用、出站、应用 | Room rules + ProfileManager | 保留原路径 |
| 多行匹配、动作、折叠高级项 | 基本匹配 + 自定义 JSON | 增加多行编辑及 JSON 字段绑定 |
| 高级匹配 | 可手写 config，但没有字段控件 | domain/suffix/keyword/regex、私有 IP、IP 版本、inbound、invert 等控件 |
| 端口/源端口/CIDR | 有字段，无严格校验 | 数值、范围和 CIDR 校验；保留旧存储字段 |
| 非路由动作 | 生成器仍附加 outbound | sniff/resolve/reject/hijack-dns/route-options 按核心映射 |
| reject 方法 | Throne 模型使用 reject_method | 当前核心是 method，不复制错误字段名 |
| 规则集选择 | 前缀 URL / geosite / geoip | 使用已有 geodata 预设；远程 HTTPS .srs 多行输入，未知 tag 拦截 |
| 配置集 | 全局 rules | 不引入 RouteProfile，无需迁移架构 |
| sniff 目标覆写 | 当前内核已移除 | 不提供入口；旧 JSON 不删除，保存时提示 |
| process、Wi-Fi | Android/Root 权限与核心平台桥不同 | 不提供假开关；保留原 JSON，编辑保存需明确处理 |

## 保存与兼容

`RouteRulePreferences` → `DataStore.serverConfig` / 现有 route 偏好 → `RuleEntity` 副本 → 字段与全匹配风险检查 → 当前 libcore 的未启动临时实例校验 → 原 ProfileManager 保存 → 原活动服务重载。

高级字段直接写入已有 `config` JSON，仅显式改动的字段被更新。未知键、其他匹配及旧原始配置不被初始化覆盖。高级列表按行解析，正则中的逗号不会被切开。最终生成器仍使用原有配置合并机制；当前核心校验负责拒绝未知 schema、Go RE2 不兼容正则和动作冲突。

**Room 仍为版本 10；RuleEntity、schema 和 Parcelable 布局未改。** BackupRestore 版本 1 的固定 Parcel 布局和备份恢复事务保持不变，故不新增数据库迁移。新测试覆盖既有 9→10 迁移的所有规则列，以及旧/新 config 的原格式往返。无清库、无 destructive migration、无一次性数据改写。含当前核心不支持字段的旧 config 保留原值，但不会在编辑保存时静默放行。旧版客户端恢复包含高级字段的备份时，数据仍在 config 中；其旧生成器可能不正确处理非路由动作，因此不建议降级运行此类规则。

默认系统规则、启用筛选、排序、全局模式、final、既有普通规则的派生 DNS 和真实出站 ID 映射保持原路径。新增范围限制或反转匹配不能安全投影成 DNS 匹配时，沿用现有 DNS 默认策略，避免生成比路由更宽泛的直连/拒绝 DNS 规则。域名/IP 的旧 OR 拆分在非反转时不变，反转时变为对完整 OR 谓词取反一次。

VPN 应用匹配复用当前包名/UID 桥；Root TUN 复用既有 UID/socket 所属信息，不改变 root 进程。普通本地代理无法可靠识别源应用，保存带应用匹配的规则时提示切换 VPN/Root 模式。Root 实际归属识别依赖 Android/内核和流量类型，配置生成测试不能证明每台 root 手机都能命中。

## 验证与人工步骤

- 本地任务：`:app:testPreviewDebugUnitTest`、`:app:compilePreviewDebugKotlin`、`:app:assemblePreviewDebug`、`:app:assemblePreviewDebugAndroidTest`；具体真实结果随交付报告列出。
- 仓库现有 CI 使用 OSS Debug 执行完整单测和 API 35 instrumentation；新增 native-core 校验、旧数据迁移/备份及双模式配置生成测试进入该套件。
- 本地无在线设备。真机联网、Wi-Fi/蜂窝切换、活动 VPN/Root 服务重载和大字体编辑交互不宣称实测。
- 人工：创建域名/地址/应用/规则集规则；编辑高级动作；尝试错误 CIDR、端口、RE2 lookbehind；确认不能保存且旧记录不变；取消规则集弹窗不改设置；返回/关闭触发未保存提示；旋转后继续编辑；切换 VPN/Root 后核对匹配；活动连接中保存并确认重载；备份恢复后核对 ID、顺序、config 和启用状态。

## 许可

两项目采用 GPL-3.0。本次独立实现，参考 Throne v2.0.1 的字段组织和交互，不复制其 RouteProfile/RouteRule 整文件；既有版权和许可不变，新增代码标注作者雾晚。参考来源：https://github.com/throneproj/ThroneForAndroid/tree/v2.0.1。
