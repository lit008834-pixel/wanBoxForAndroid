# OwnBox 2.9.21 选择性审查与运行期开销修复

作者：@author 雾晚

## 基线与范围

- wanBox：`v3.0.7-preview.2` / main `efa48e4e40bc7f7e440c5c4f3ede59f10c78dd00`。
- OwnBox：`v2.9.21-preview` 的提交 `ecde2a3437193e3928f62303c9002bb4c2235656`（标签对象 `d01abfb548bf6de040e24d25c89c163bf751f235`）。
- 共同祖先：`55f0e6878fe35c192c40101ef300ad3a448d0296`。两端整体有 367 个文件差异，不能整仓合并。
- `origin` 为 wanBox，`upstream` 为 OwnBox，默认分支均为 main。参考仓库只读。原工作区的路由测试及未提交文件保留，改动位于独立 `opt/telemetry-runtime` 分支。
- 保留原包名、签名、正式版本线、`v1.15.0-alpha.10` 核心 pin、原生 XML/ViewBinding 架构和用户配置。只递增本次预览版本。

## 差异分类与取舍

| 分类 | 源码证据 | 处理 |
| --- | --- | --- |
| A 新功能 | `bb2536a` 仪表盘、`0e7e2c5` 通知变化 | wanBox 已有仪表盘及通知；不扩大本次范围 |
| B Bug | `f1ac989` 与官方 libbox 报告 TUN 本机地址；wanBox `MyInterfaceAddress()` 返回 nil | 最小补齐每个实例的实际 IPv4/IPv6 地址，修复接口列表漏报时本地流量无法识别、按应用规则无法匹配的问题 |
| C TUN 兼容 | `ecde2a3` 仅删除 ConfigBuilder 的 GSO 赋值及 Inbound 模型字段 | wanBox 已不存在该字段，补真实生成器回归断言，不重复修改配置 |
| D 格式 | `8a90426` 数据库/备份变化 | wanBox 已有迁移与稳定 JSON 备份；不更换数据格式 |
| E UI | `4a2836f`、`99854a5` 主题及图标 | 不迁入，保留 wanBox 材质、主题、品牌和图标 |
| F 运行开销 | 两端 TrafficLooper 都有未初始化 continue 空转、主动 GC | 在 wanBox 本地修复；不照搬上游相同缺陷 |
| G 等效功能 | `f1ac989` 应用身份 OR；wanBox AppRouteIdentity、共享 UID 全包名及测试 | 保留更完整的 wanBox 实现，不恢复成只取共享 UID 的第一个包名 |
| H 冲突 | OwnBox 版本/包名、没有 wanBox 的独立 Root TUN | 不迁入版本元数据、发行文案或 Root/VPN 生命周期；不批量 cherry-pick |

移植依据：OwnBox `f1ac989191c1f405d32ab19e2bb1054c16a95ab1` 的 `platform_box.go` 及固定核心的 `experimental/libbox/service.go`。采用同一地址报告契约，地址收集函数在 wanBox 单独实现与测试。保留项目 GPL-3.0 许可及原文件来源声明。

## 实际改动

1. 核心未初始化时，流量采样协程进入可取消的等待，避免 tight loop；禁用采样的偏好保持禁用。
2. 保持现有前台用户间隔、后台/非交互采样间隔，只抽取纯策略方便验证；不降低拨号、转发或测速并发。
3. 负载均衡成员集合在采样器初始化时计算一次；每轮仍读取真实计数、更新流量并在停止时持久化。
4. 删除页面退出、屏幕关闭、Doze/性能切换和测试实例关闭时主动 GC / FreeOSMemory。仍关闭 core、socket、scope、回调与资源，保留 Go 自动 GC 与现有内存配置；兼容的 ForceGc 导出 ABI 保留但没有自动调用者。
5. 保留 SCREEN_OFF 显式分支，不能落入 stopRunner；保留现有唤醒/重置选项和电源锁所有权。Root 独立进程的 1 秒 watchdog 与停止文件检查不改。
6. 单条超大日志不能绕过缓冲上限；使用切片保留末尾，检查 Stat/Truncate/Seek 错误。0 对应设置页已有 50 KiB 默认值，disabled 仍不写盘。未新增日志队列。不同 Android 进程共用文件，进程间同时写入仍可能短暂超限，本次不声称跨进程严格磁盘上限。
7. 向核心报告当前 TUN 配置的 host 地址，不报告整个子网，不复用其他实例的地址。Root standalone 核心与 VPN 授权/保护逻辑保持原样。

## 搜狗语音输入反馈

用户报告长时间使用后搜狗系统输入法提示语音网络不好，VPN 和 Root 均出现。没有本次故障日志、真实规则/网络记录或在线用户设备，不能把某一个源码缺陷认定为两种模式语音故障的唯一根因。

本次 TUN 地址补齐解决 VPN 按应用识别的已证实缺口，Root 的独立核心不经过该桥接。用户已明确同意增加“当前输入法直连”兼容选项，默认关闭，位于“设置 → 连接与后台”。开启/关闭后在设置持久化完成时重启现有服务，重新生成配置；没有额外常驻轮询或输入法监听线程。

每次生成实际 VPN/Root 配置时从系统 DEFAULT_INPUT_METHOD 解析当前输入法，通过 PackageManager 获取完整 Android UID；未知包、系统 UID、隔离进程和应用自身 UID 不生成规则，不猜搜狗 OEM 包名、不把所有系统应用豁免。包名或完整 UID 二选一匹配，复用已有 AppRouteIdentity：Root 通过原生 UID 匹配，VPN 通过现有 Android owner 桥接匹配。共享 UID 会使同 UID 应用一起直连；选项影响输入法全部联网请求，不能只区分语音。输入法委托给不同 UID 的语音服务不在该规则范围内，需真实故障记录后再评估。

保留流量进入 TUN，不在 VPN Builder 增加 disallowedApplication，也不向 Root TUN 加 exclude_uid。这避免系统 DNS 获得 Fake-IP 后输入法因绕过 TUN 而无法访问该地址。规则在既有嗅探、DNS 劫持与 IP 家族保护后、用户/全局代理规则前，使用既有 direct 出站及用户配置的 dns-direct；可识别的输入法 DNS 不复用旧缓存，未知系统 DNS 仍沿用已有 Fake-IP 处理。IPv6 策略、默认 DNS/路由、其他应用规则和系统代理设置不变。

测速、导出、纯本地代理模式不注入此设备身份；完整自定义 JSON 配置原样保留，用户自己覆盖 route/dns 的自定义配置也以原有配置合并规则为准。没有改变备份格式或数据库 schema，新开关由已有设置备份路径保存。切换默认输入法后需重连，未识别身份时设置页明确提示不生成直连规则。没有改变麦克风权限。

验收时在同一网络/节点分别记录：刚连接、熄屏再唤醒、Wi-Fi/蜂窝切换、30–60 分钟后语音输入；比较普通联网应用与输入法，检查实际应用规则、DNS 与流量命中。日志只需失败类型和时间，不公开节点、订阅、录音或凭据。

## 验证

- 用户确认直连选项后补充 4 项单元回归，本地 Preview 单测 194 项、Preview Debug 与 Oss AndroidTest APK 编译通过。输入法配置仪器测试覆盖 VPN/Root、Fake-IP/全局模式组合、UID、优先级、禁用/未知身份/测试/导出/纯代理分支；实际设备执行以本次 CI 结果为准。
- 红灯回归：修复前运行期开销契约 2 项失败；日志超大输入回归失败。
- `app:tasks --all` 确认真实 Preview/Oss Gradle 任务。
- `app:testPreviewDebugUnitTest`、`app:assemblePreviewDebug`、`app:assembleOssDebugAndroidTest` 本地通过；Android 测试 APK 编译不代表设备执行。
- 最终本地 Preview 单测 190 项通过。`app:lintPreviewDebug` 失败，仍为已有 133 项错误，未扩大范围去修改不相关页面；不把单测/编译通过说成 lint 通过。
- Go 本地 `go test libcore/log.go libcore/log_test.go`、`go test libcore/tun_source.go libcore/tun_source_test.go` 通过。Windows append-only 文件不能 Truncate，日志测试在 Windows 使用可读写句柄；Linux/Android 测试仍使用生产 O_APPEND 标志，CI 另加 race 检查。
- 真实 ConfigBuilder 仪器回归覆盖 VPN/Root、FakeIP 开关的 TUN 必需字段及无 GSO；CI 使用本次源码重新构建 AAR/Root 四 ABI 后执行，不以旧缓存替代核心验证。
- 未量测用户设备 RSS、后台电耗、滚动帧率或 Root 真机语音长连接效果，不宣称百分比节省或语音真机故障已复现。
