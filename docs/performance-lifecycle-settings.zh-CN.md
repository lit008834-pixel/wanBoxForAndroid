# 生命周期与设置整理记录

> 适用范围：本文记录提交 `bb28cfb`（2026-10-05）及其当时的验证，不是当前版本说明。下文的基线、工作分支、预览版本和测试结果均为当时快照；截至 2026-10-06，仓库基线已为 `origin/main`/`v3.0.7-preview.5`（`f2886b93a68a94ec4bf04cde73134e48c67e044d`），历史测试不能代表该版本的测试结果。本文记录的锁所有权实现和设置分类迁移仍可在当前源码中找到，具体现状以源码为准。

@author 雾晚

## 基线、证据与取舍

最新 origin/main 与正式版 v3.0.6：`9b117b34db4c83eae8550b8fce75564e1404a852`；附件 preview.3 快照已过时。工作分支 `opt/performance-lifecycle-settings`，保留原未跟踪目录。未发现 AGENTS.md；已读 Preview Notes 和稳定性、生命周期、安全、上游网络及 URLTest 生命周期记录。

维持 `com.lit008834.pixel.wanboxforandroid`、namespace、签名、sing-box v1.15.0-alpha.10、Gradle 8.10.2/JDK17/Go1.25.5、SDK35/min21、数据库和备份。沿用 main 已配置的下一预览版本 3.0.7-preview.1/342（APK1710），没有修改既有正式/预览 tag。

| 现状/候选 | 证据 | 风险与本次决定 |
| --- | --- | --- |
| 网络吞吐/拷贝/GSO/线程池 | 当前无在线设备或可控节点；延迟不等于吞吐，已有核心兼容和健康探测修复 | 无 before/after 因果证据，不改网络热路径、不升级依赖、不承诺吞吐或续航收益 |
| 服务电源锁 | Root/Proxy 直接赋新锁；VPN optional WiFi acquire 的 apply 抛错时句柄未赋字段；旧释放失败后清空句柄，无法再试 | 用小型所有权帮助类修复重复获取/部分获取失败/释放失败处理；不改变开启偏好、默认值或服务状态机 |
| 设置信息架构 | 原八个顶层分类，连接/后台控制散在高级，分片独占顶层 | 常用模式与测速前置，后台相关项归入连接；低频分片成为核心内独立可折叠分类，全部 key 保留 |
| 熄屏/Doze/网络监听 | 已有不暂停核心、串行停止、初始网络等待、专用线程、防抖和取消处理 | 保留原语义，无新的保活任务、权限、timer、默认网络或 TLS 改动 |

## 实施与所有权

`BaseService.Data/服务实例 → ServicePowerLocks → cpu lease [+ VPN wifi lease]`。

- 各服务仍只在 DataStore.acquireWakeLock 开启时获取。每把锁设置非引用计数，按 key 幂等获取；创建句柄先登记所有权，再调用 acquire，部分获取失败时立即释放并传播原始异常，清理失败附加 suppressed。
- releaseAll 独立尝试每个资源，一个释放失败不跳过其余资源；失败句柄保留到下一次清理。VPN 在既有 TUN 清理前尝试 WiFi 释放，Base 的核心关闭后清理再次覆盖所有残留锁；初始化重新获取前也统一释放旧句柄。
- optional WiFi 获取失败只记录异常类型，CPU 锁保持原有效行为。VPN TUN 关闭失败与 WiFi 释放失败均保留，不让后者被覆盖。
- 没有新增定时续锁、永久后台线程或自动重启。用户显式开启时锁以活动服务生命周期为边界，沿用原行为；关闭后/服务停止时清理。不可用的系统释放 API 不会被虚报为成功。
- 不改 start/stop/reload、Root su/pid/ready/stop/watchers、VPN FD/socket protect、网络回调、sleep/wake/Doze 或 performancePriorityMode 的运行参数。

## 设置与兼容

顶层顺序：模式/入站 → 连接观测 → 用户界面 → 连接与后台 → DNS → 核心 → 进阶。分片分类的 key `categoryFragment` 和自身展开状态保留，放在核心分类内，先展开核心再展开分片。

WakeLock、性能优先、切网/唤醒重置转入连接与后台；隐藏最近任务转入用户界面。所有 SettingsPreferenceFragment.findPreference、动态显示/依赖、验证器、重载/重启处理不改。没有改 Key、DataStore 默认、用户值或重置动作；重置仍确认且不清节点/路由。中英文摘要明确电量/内存成本、证书安全和原始配置风险，移除无测量依据的速度承诺。

以下是全部旧 key 清单（含 UI 分类 key），原属性快照为 `app/src/test/resources/settings-before-3.0.7.json`；新增契约逐项比较控件类型、key 和所有非文案属性，包括默认值和 dependency。

| key | 原分类 → 新分类 | 默认/依赖 | 处理边界 |
| --- | --- | --- | --- |
| `categoryUI` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `profileCardStyle` | categoryUI → categoryUI | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `showDirectSpeed` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `speedInterval` | categoryUI → categoryUI | `1000` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `profileTrafficStatistics` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `showGroupInNotification` | categoryUI → categoryUI | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `showLandingIp` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `alwaysShowAddress` | categoryUI → categoryUI | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `confirmProfileDelete` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `show_subscription_info_card` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `appTheme` | categoryUI → categoryUI | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `nightTheme` | categoryUI → categoryUI | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `appLanguage` | categoryUI → categoryUI | `` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `showBottomBar` | categoryUI → categoryUI | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `hapticFeedback` | categoryUI → categoryUI | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `showAllGroupsTab` | categoryUI → categoryUI | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryVPN` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `isAutoConnect` | categoryVPN → categoryVPN | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `proxyApps` | categoryVPN → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `bypassLan` | categoryVPN → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `bypassLanInCore` | categoryVPN → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `strictRoute` | categoryVPN → categoryVPN | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `meteredNetwork` | categoryVPN → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `tunImplementation` | categoryVPN → categoryVPN | `3` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `mtu` | categoryVPN → categoryVPN | `9000` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryMode` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `serviceMode` | categoryMode → categoryMode | `vpn` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `disableMixedInbound` | categoryMode → categoryMode | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `mixedPort` | categoryMode → categoryMode | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `mixedAuthConfig` | categoryMode → categoryMode | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `httpProxyBypass` | categoryMode → categoryMode | `` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `lanSharing` | categoryMode → categoryMode | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryCore` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `trafficSniffing` | categoryCore → categoryCore | `1` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `resolveDestination` | categoryCore → categoryCore | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `ipv6Mode` | categoryCore → categoryCore | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `dualNetworkAcceleration` | categoryCore → categoryCore | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `concurrentDial` | categoryCore → categoryCore | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `rulesProvider` | categoryCore → categoryCore | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `rulesGeositeUrl` | categoryCore → categoryCore | `https://github.com/SagerNet/sing-geosite/releases/latest/download/geosite.db` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `rulesGeoipUrl` | categoryCore → categoryCore | `https://github.com/SagerNet/sing-geoip/releases/latest/download/geoip.db` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `rulesUpdateInterval` | categoryCore → categoryCore | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryDNS` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `remoteDns` | categoryDNS → categoryDNS | `https://dns.google/dns-query` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `domain_strategy_for_remote` | categoryDNS → categoryDNS | `auto` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `directDns` | categoryDNS → categoryDNS | `https://223.5.5.5/dns-query` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `domain_strategy_for_direct` | categoryDNS → categoryDNS | `auto` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `domain_strategy_for_server` | categoryDNS → categoryDNS | `auto` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `enableDnsRouting` | categoryDNS → categoryDNS | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `enableFakeDns` | categoryDNS → categoryDNS | `true` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `dnsHosts` | categoryDNS → categoryDNS | `` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryFragment` | 顶层 → categoryCore | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `enableTLSFragment` | categoryFragment → categoryFragment | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `fragmentLength` | categoryFragment → categoryFragment | `100-200` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `fragmentInterval` | categoryFragment → categoryFragment | `10-20` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryObservatory` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `connectionTestURL` | categoryObservatory → categoryObservatory | `@string/default_connection_test_url` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `hideUnavailableProfiles` | categoryObservatory → categoryObservatory | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `speedTestMode` | categoryObservatory → categoryObservatory | `@string/default_speed_test_mode` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `speedTestTimeoutMs` | categoryObservatory → categoryObservatory | `@string/default_speed_test_timeout_ms` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `simpleDownloadURL` | categoryObservatory → categoryObservatory | `@string/default_simple_download_url` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `categoryAdvanced` | 顶层 → 顶层 | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `defaultSubscriptionUserAgent` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `enableClashAPI` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `networkChangeResetConnections` | categoryAdvanced → categoryVPN | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `wakeResetConnections` | categoryAdvanced → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `globalAllowInsecure` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `allowInsecureOnRequest` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `appTLSVersion` | categoryAdvanced → categoryAdvanced | `1.2` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `performancePriorityMode` | categoryAdvanced → categoryVPN | `false` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `acquireWakeLock` | categoryAdvanced → categoryVPN | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `hideFromRecentApps` | categoryAdvanced → categoryUI | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `logLevel` | categoryAdvanced → categoryAdvanced | `0` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `globalCustomConfig` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `clearCache` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |
| `resetSettings` | categoryAdvanced → categoryAdvanced | `原 DataStore 默认` / 无 XML 依赖 | 原验证/监听/重载不变 |

## 验证与测量边界

- `ServicePowerLocksTest`：真实帮助类+故障注入 fake 锁，重复获取/停止/重启、部分获取回滚、释放失败仍清理其他锁并可重试、原异常与清理异常、factory 失败、optional WiFi 失败保留 CPU、各服务接线及不暂停核心。
- `SettingsHierarchyContractTest`：所有旧 key、类型、默认、依赖、其它属性不变，分类嵌套正确。
- `SettingsHierarchyTest`：Android 实际 PreferenceInflater，嵌套分类展开/收起/重开及移动控件可找回。契约测试不是视觉或 OEM 行为验证。
- 实际 Gradle tasks 从 `app:tasks --all` 确认，使用 PreviewDebug UnitTest/assemble/AndroidTest 和 lintPreviewDebug。
- 初轮发现旧清理契约检查错误参数名，已保留原 error 命名并重新运行全部测试；没有删除断言/测试或改变 lint baseline。
- 本地 `adb devices` 无在线设备；Linux/Android CI 的结果与真实吞吐、Android Root、Doze/OEM 待机必须区分。

| 指标 | 修改前 | 修改后 | 证据级别 |
| --- | --- | --- | --- |
| 顶层分类 | 8 | 7 | 真实 XML 与 Android inflater |
| 重复获取同一锁 | 旧 Root/Proxy 会创建新句柄 | 一个所有者，重复调用不增加锁 | fake 锁单测，非手机功耗 |
| 释放失败句柄 | 清空 | 保留并可再次尝试；其它锁继续清理 | 故障注入单测 |
| 上传/下载吞吐、握手 | 未测 | 未测 | 不宣称改善 |
| CPU/PSS/RSS/GC/温度/功耗 | 未测 | 未测 | 不宣称改善 |

本地结果：PreviewDebug 180 项 JVM 单测 0 失败，应用/仪器 APK 构建成功；Manifest/TLS/迁移安全检查及 git diff --check 成功。lintPreviewDebug 已实际运行，返回失败：修改分支与 v3.0.6 基线均 143 项错误，按 issue ID/message/path 比较的指纹完全一致；新锁封装没有新增错误。未改 lint baseline、未隐藏新问题或将 lint 描述为通过。基线复测只在本任务的 ASCII 验证副本中临时替换/恢复源文件，没有改用户工作区。

修改文件还包括 AndroidPowerLockLease.kt；平台锁以类成员持有，由 acquire/release 方法管理，保持既有活动服务期间的锁超时策略。真正 Android 偏好页、完整原生构建及升级验证以 PR/发布 CI 实际结果为准。设备矩阵需使用固定节点/iperf3，VPN/Root 各测 WiFi/蜂窝、IPv4/IPv6、上下行、单/多流、亮/熄屏、冷/热连接，每场至少多轮记录中位数/p95、CPU/PSS/RSS/GC/温度和电量；再执行 Doze 进出、飞行模式、断网恢复和进程回收。不以 URLTest 数字充当吞吐基准。

修改文件：BaseService.kt、VpnService.kt、RootTunService.kt、ProxyService.kt、新 ServicePowerLocks.kt；global_preferences.xml、中英 strings.xml；上述三份测试和属性快照、本文及 PREVIEW_NOTES.md。核心/路由/数据/权限/签名不在本次 diff。
