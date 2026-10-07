# 应用指定出站修复与回归记录

@author 雾晚

## 文档范围与当前状态（截至 2026-10-06）

本文是 `fix/app-outbound-routing` 修复及当时验证的历史记录，基于 `v3.0.6-preview.3`，不是当前版本的功能说明。修复提交 `f39d9a5` 和后续测试提交 `4c7fd4a` 已包含在当前 `origin/main`（`v3.0.7-preview.5`，`f2886b93a68a94ec4bf04cde73134e48c67e044d`）的提交历史中。下文“合并前”验收要求记录的是撰写时的门槛；本文件未记载相应的合并后 CI、正式打包或真机验收结果，不能据此推断这些检查现已通过。

## 基线及根因

- 工作分支 `fix/app-outbound-routing`，基线 main/`v3.0.6-preview.3`：`993ac25e58203d8cc7a4eb6746e5f07a41a6215f`。原未跟踪文件保留，没有整仓同步 OwnBox/Throne。
- 核心保持 `v1.15.0-alpha.10`（官方源码 `c992297988288565a24a6d36e2cf4d77cb835fcd`）；包名、签名、数据库、保存的 RuleEntity 和路由顺序不变。
- RouteSettingsActivity 保存真实包名到 RuleEntity.packages；ConfigBuilder 根据 PackageCache 查 UID，原生成器将 package_name 和 user_id 放在同一默认规则。当前核心将这两项按 AND 匹配。真实核心测试已证明：socket UID 正确但包名为空时旧规则不命中。这是可确定的源码问题，不是凭网络延迟猜测。
- NativeInterface 原来从共享 UID 的 HashSet 只取一个包名；PackageCache 刷新期间先清空 UID 表再逐项填充。两者均可能造成识别缺失。负 UID 也曾被当作 android 包名。

## 两种模式的实际链路

| 环节 | VPN | Root TUN |
| --- | --- | --- |
| 保存与配置 | 相同包名/出站 ID，通过原 tagMap 选择出站 | 同左 |
| 流量入口 | Android VpnService TUN，原 socket protect 路径 | 独立 su/librootbox 核心，原 auto_route/auto_redirect |
| socket 身份 | API 29+ ConnectivityManager.getConnectionOwnerUid；旧版本 procfs | 固定核心的 Linux socket_diag/Netlink；Android 包名管理器读取 packages.xml/ABX |
| 匹配 | package_name OR 有效 user_id，再结合其他原条件 | 同一规则；包名映射不可用时，已解析 UID 仍可匹配 |

没有添加 iptables owner 标记、端口透明代理、ChatGPT 例外或域名绕行，没有更改 Root/VPN 启停或网络默认值。Root 的当前核心已有 socket 查询能力，因此修复配置和身份桥接，不另造 UID 查询/转发系统。

## 修改方式与边界

1. AppRouteIdentity 仅在生成配置时将已有双身份条件改成逻辑 OR。其他匹配条件复制到两个分支，invert 和动作仅留外层，未解析 UID 的包名规则仍保持包名匹配，不能变成全匹配。
2. 所有当前核心动作字段保留；普通路由完成原有出站合法性校验后才转换。DNS/FakeIP 的已有应用规则采用同一转换，不扩大端口/IP 类规则的 DNS 范围。全局模式原有优先级保持。
3. UID 缓存完整构造后原子发布，关联包名全部返回，通过现有 String 桥接传输，Go 端拆分并去重；没有改 AAR 接口签名。
4. 次级用户查不到完整 UID 时按 Android appId 读取已有包名映射；socket 的完整 UID 不改写。没有推测 isolated UID 所属应用，也不为未知身份生成错误包名。
5. 共享 UID 本身无法按 UID 区分同 UID 的应用，Root/Android 能力并不保证逐包严格隔离；系统包可见性、isolated process、OEM socket_diag/SELinux 限制仍需设备验证。原始自定义核心 JSON 保持原义，不自动重写用户自定义格式。
6. 诊断只记录模式、规则/出站 ID、计数及 UID。包名缺失最多每核心 30 秒记录一次，不输出地址、订阅、密码或 secret。未增加定时线程/轮询。

示例（均为虚构数据，选中出站不依赖域名）：

```json
{"type":"logical","mode":"or","rules":[{"package_name":["example.chat"]},{"user_id":[10123]}],"outbound":"fixture-node"}
```

## 自动化验证

- Kotlin `AppRouteIdentityTest`：实际序列化器输出与共享 JSON fixture 一致；出站/高级动作/反向/UDP/IPv6/端口条件保持、DNS FakeIP 范围、未知包名、嵌套条件、共享 UID/次级用户和旧快照不变。
- Go `internal/appowner`：使用固定 sing-box 真正的 NewRule/Match 复现旧 AND 失败；检查包名或 UID 命中、共享 UID、未知/非选中应用不命中、TCP/UDP 和出站不变。
- Go Linux `TestRootSocketOwner`：同版本核心 socket_diag 对真实本机 TCP/UDP IPv4/IPv6 socket 查询拥有者；这不是 Android Root 真机实测或真实 QUIC 会话。
- Go Linux `TestPlatformOwnerKeepsUidAndEveryPackage`：模拟 Android 桥接，不丢共享包名、不因包名失败丢有效 UID、负 UID 返回失败。
- Android `RouteRuleCompatibilityTest.realGeneratorKeepsOrderTargetsAndFieldsInVpnAndRootTun`：实际数据库/配置生成器，分别以 VPN/Root、FakeIP 开/关构建，选中另一节点的应用规则必须引用对应 server/port，旧顺序/拒绝/禁用/全局规则保持。测试恢复原数据。不是物理网络出口验证。

本地已执行：

- `gradlew.bat --offline app:testPreviewDebugUnitTest app:assemblePreviewDebug app:assemblePreviewDebugAndroidTest`：成功，172 个 JVM 测试，0 失败；最新仪器测试再次编译成功。本地 AAR 为上一已发布版本，最终 CI 必须重新构建 Go/AAR/Root 可执行文件。
- `go test -modfile=../.fixture/alpha10.mod ./internal/... ./protocol/urltest ./protocol/loadbalance`（libcore 目录）：五包成功。Windows 不运行 Linux 专属测试，由 Linux CI 执行。
- `git diff --check`：成功。
- 当前 `adb devices` 无在线设备；没有声称手机上 ChatGPT/菲律宾出口或 Root 切网已经验证。

合并前需确认 Verify Android CI 的 Go、OssDebug/PreviewDebug 单测、API 35 仪器测试和安全检查成功。正式发布流水线重新打包四 ABI，验证包名/签名/版本、模拟器覆盖安装和 SHA256；实际结果附后。

## 可复现设备验收

同一规则与节点，在 VPN 和 Root 分别运行：选中应用 TCP、UDP/QUIC、IPv4/IPv6、FakeIP 开关、仅应用规则和附加域名规则；确认服务器出口/连接日志真实命中选定 tag。未选中应用走既有默认出站。断开/重连、切换模式、Wi-Fi/蜂窝和安装/卸载应用后复核缓存；共享 UID/工作资料/isolated process 单列记录，不通过增加域名或假造结果代替验证。
