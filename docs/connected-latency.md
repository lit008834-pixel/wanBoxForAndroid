# 已连接服务测速修复

作者：雾晚

## 对照

2026-10-01 核对：仅 origin，URL 为 https://github.com/lit008834-pixel/wanBoxForAndroid.git。显式从该地址获取 main，提交为 `b320dbb88346d87ff9364f1b5389472f2deb7324`。原分支 fix/audit-security-lifecycle 的 `41c0f3393536b99cce431c5b4d2bb586dc6756e7` 与 main 文件树完全一致。独立分支 fix/root-vpn-latency 保留了原未跟踪文件与 stash。

对照 BaseService、RootTunService、VpnService、ProxyUrlProbe、MainActivity、StatsBar、ConfigurationFragment、UrlTest 和 TestInstance；没有同步其他项目，没有修改内核、主题、图标或签名。

## 根因与修复

main 已有 Root 分支，但从可为空的 data.proxy.service 判断。现在改用 Data.service，Root 请求不读取 proxy.box。原就绪条件仅 rootProcess 非空，没有检查进程退出，也未在探测后验证同一进程与连接实例；现在前后复查，无效结果返回 0。

main 的 StatsBar 有随机 ±2ms 显示、失败保留旧成功结果，缺少连接身份和代次检查。用户明确选择移除随机值并在失败时清空。LatencyProbeState 管理真实结果、身份、代次和唯一在途请求，绑定服务/模式/节点/地址/超时。断开、切换、取消或销毁时旧结果不能覆盖新结果。保留原 400ms 成功结果防抖。

## 调用链

- VPN：StatsBar.testConnection → MainActivity.urlTest（捕获当前 Binder）→ BaseService.Binder.urlTest → ConnectedUrlTest → 当前 proxy.box → Libcore.urlTest。自定义 URL 调用 urlTestCustomUrl → Libcore.urlTestFull。VpnService 与核心位于 :bg 进程，通过核心默认 outbound 探测。
- Root：相同 UI/Binder 入口 → 按 Data.service 委托 RootTunService.urlTest → 前后就绪检查 → ProxyUrlProbe.measure → 127.0.0.1 现有 mixed inbound → root core。服务位于 :bg，核心由 su -c exec librootbox.so 独立运行。
- mixed 禁用：保留指定 main 的 TestInstance(profile, URL, timeout).doTest 回退，使用临时核心；配置初始化可能增加额外等待时间。
- 批量节点/分组：ConfigurationFragment → UrlTest（节点/分组地址选择）→ TestInstance，创建临时核心；本次未修改此路径。

两种模式沿用 DataStore.connectionTestURL/connectionTestTimeout，毫秒单位，正整数成功，0/异常失败。ProxyUrlProbe 保留总 callTimeout（1–30000ms）、禁重定向、代理认证最多一次、非 2xx 失败及响应/连接池/执行器清理。

## 验证命令与限制

本机 gradlew.bat app:tasks --all 实际失败：JAVA_HOME 未设置，PATH 无 Java；本机没有 adb。仓库 CI 使用 Java 17 与 Android SDK 执行：

```sh
./gradlew app:tasks --all
./gradlew app:assembleOssDebug
./gradlew app:testOssDebugUnitTest app:assembleOssDebugAndroidTest app:assembleOssRelease
```

预览工作流运行 app:assemblePreviewRelease，检查四个 ABI 包名/版本码/原签名；临时 API35 模拟器验证正式版 3.0.3 和上一预览 3.0.4-preview.1 覆盖更新，并保留 OwnBox。最终是否通过以工作流实际结果为准。

ConnectedUrlTestTest 验证 VPN 活跃核心、Root 不读 box、默认/自定义参数、未连接、失败和进程退出；LatencyProbeStateTest 验证真实结果、失败/0、断开/切换、旧代次、并发占用与防抖；ConnectedLatencyWiringTest 补充 Android 入口接线；ProxyUrlProbeTest 通过真实 socket 验证 HTTP absolute-form、HTTPS CONNECT、认证终止、总超时、非 2xx/重定向失败和连接清理。

未做 Root/VPN 真机同节点、同 URL/超时重复联网测速，不将自动测试称为真实 Root 网络验证。建议真机分别连续测五次，覆盖切换、断开、失败、超时与重复点击。取消只丢弃结果，阻塞 Binder 请求可能继续至原超时，期间不会叠加新请求。
