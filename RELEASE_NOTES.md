# wanBox 3.0.6 正式版

作者：@author 雾晚

## 更新内容

- **应用分流修复：**修复包名和 UID 必须同时匹配、导致指定出站规则失效的问题；VPN 与 Root TUN 统一使用应用规则。包名暂不可解析时仍可使用有效 socket UID；共享 UID 保留全部关联包名，并支持在其他 Android 用户中识别已有包名规则。
- **规则语义保持：**同步修复应用规则的 DNS/FakeIP 分支，同时保留原有规则顺序、出站、反向条件和高级动作参数；不增加针对特定应用或域名的绕行规则。
- **网络与订阅处理：**隔离网络监听者异常，避免单个监听者影响其他监听者及后续通知；订阅强制 DNS 解析共享最多 5 个并发名额，取消更新后不应用迟到的解析结果，并保留既有解析路径、IP 偏好和 TLS 主机名。包含本版本前序预览中的备份、路由与仪表盘改进。

## 回归测试

- Kotlin 与 Go 测试覆盖应用包名/UID 匹配、共享 UID 与多用户包名映射、DNS/FakeIP 分支，以及出站、规则顺序和其他条件的保留。
- Android 配置生成器测试覆盖 VPN 与 Root TUN、FakeIP 开关下的规则目标与顺序；Go 测试覆盖应用身份匹配及 socket UID/包名处理。
- 网络生命周期与订阅解析测试覆盖监听者异常隔离、并发与取消、迟到解析结果处理，以及 IP 偏好和 TLS 主机名保留。

## 兼容性与升级

- 正式版 `versionName` 为 `3.0.6`；元数据 `VERSION_CODE=341`，Android `versionCode=1705`（构建时乘以 5）。核心版本为 sing-box `v1.15.0-alpha.10`。
- 保持应用包名 `com.lit008834.pixel.wanboxforandroid` 和既有签名；可覆盖更新现有 wanBox 预览版。
- Release 提供 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种架构 APK。

实现与回归记录：[应用分流修复记录](docs/app-outbound-routing.zh-CN.md)。
