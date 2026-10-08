# Root 模块运行优化审查

作者：@author 雾晚

## 基线与来源

- wanBox：`v3.0.7-preview.10`，`03fb82ba5468391469d04ff952d2469f252486dc`。
- 合入的原 main：`2153fd82142087a602762f023a69a71016f561ee`；共同祖先 `1ea09c6aef379cecac09c7237cc088e9bffa44bb`。保留主线独立项目定位说明及 preview.7 记录。
- 参考：[NetProxy nightly 源码](https://github.com/Fanju6/NetProxy-Magisk/tree/8e2ff673c5342eb5402c186b56773e8b830271b6)，提交 `8e2ff673c5342eb5402c186b56773e8b830271b6`。标签可能移动，本次记录固定提交。
- NetProxy 使用不同的核心、Catalog 数据模型和服务 API。仅参考生命周期、按变化提交和有界工作原则，未复制其源码。wanBox 与 libcore 的 GPL 声明保留。

## 逐项取舍

|参考文件/机制|wanBox 当前路径与证据|处理与原因|
|---|---|---|
|`internal/worker/worker.go`：单个到期计时器、取消释放|`rootmodule/supervisor.go`：启动 ready 检查在连接后仍每 100ms 运行|修复：成功写入 connected 后停止并禁用两个启动计时器；退出、取消、模块禁用监督继续生效|
|`internal/module/config_transaction.go`：校验、事务、明确运行状态|`rootmodule/runtime.go`：相同配置的不同 expectedRevision 生成新目录并重启|修复：先验证冲突、取消、边界，再逐字段比较内容；相同内容直接返回真实状态；真实变化保留原校验、原子提交和失败回滚|
|`internal/logfile/logfile.go`：有界事件与写入|监督器每 5 秒保存相同 idle 统计；现有日志 40 条、读取上限 64KiB|修复相同统计写盘；保留已有日志上限、敏感错误脱敏和故障停止机制|
|生命周期锁、进程身份、有限重试|`process_unix.go`、`runtime.go`、`supervisor.go` 已有锁、PID/start/exe 校验、清理与最多三次重试|已有等效安全能力，不复制替换；补充真实子进程保持 PID 和取消回收回归|
|`internal/fetch/fetch.go`、`internal/subscription/update.go`：ETag/304、Catalog 增量|wanBox `RawUpdater.kt` 使用不同持久化、备份与订阅解析契约|暂缓：持久化验证器和 304 恢复需独立兼容设计。此次没有加入临时内存 ETag，也没有声称完成增量订阅|
|selector 快速切换|wanBox 配置生成按节点生成不同核心图及插件资源|暂缓：相同配置不重启已修复；不同节点仍经既有事务重载。不能用 API 名称推断所有节点已载入 selector|
|遥测、eBPF、第二透明代理路径|wanBox 保持 Root TUN + auto_redirect|不移植：非必要功能、平台和资源边界不同|

## 行为边界

`ExpectedRevision` 仍用于并发冲突检查；只有它不参与内容比较。配置 JSON 按字节比较，不宣称语义等价的重排也能跳过。规则资源、插件种类/二进制/配置/证书、运行策略、节点 ID/名称、自动启动、schema 均纳入比较。新 Snapshot 字段遗漏比较时，字段覆盖测试会失败。

启动探测停止后没有常驻 ready 检查；子进程 Wait、取消和已有 10 秒模块禁用检查仍在。真实状态查询仍检查 PID 和 ready 文件。统计刷新仍为至多每 5 秒一次；首次零流量、变化流量、故障清空保留，重复内容不重写。状态写入失败仍取消监督器，避免返回假成功。

保留 UI、安装器选择、两种模块包、模块 ID、包名、签名、版本、核心 pin、用户数据、Root TUN 与 auto_redirect。没有恢复 VPN，没有新增常驻 App/Worker、资源缓存或网络轮询，没有修改路由/DNS/协议默认值。

## 验证与构建

先加入回归测试，旧实现失败：相同内容生成不同 revision，Validate 被调用两次。修改后测试覆盖无操作提交、并发冲突、取消、资源变化、回滚指针、插件所有字段、统计去重/清空、启动超时和释放。Linux 测试使用真实子进程，但不等于 Android Root 网络验证。

构建仍使用现有 Preview 工作流及原签名校验、单测、Go race、模块打包和升级/安装器数据策略模拟器测试。新增 `publish_release=false` 验证入口：生成 APK 与带/不带管理器模块产物，跳过发布 job，不修改任何现有 Release。默认发布行为保持原样。

实际结果、Actions 与产物链接见本次交付。未获得在线 Root 设备时，不宣称省电、吞吐或长时间断流测试通过。静态确定的变化是取消连接后每秒 10 次启动探测，以及重复配置提交时的校验/写盘/重启；不把它换算成未经实测的电池百分比或内存收益。

本地执行记录：

|命令/检查|结果|
|---|---|
|`go -C rootmodule test ./...`、`go -C rootmodule vet ./...`|通过；Windows 不执行 Linux 条件编译的子进程/文件锁测试，该部分交给 Actions race 验证|
|`python -m unittest discover -s rootmodule -p 'test_*.py'`|7 项中 3 项通过、4 项 POSIX 脚本用例跳过；Actions Linux 执行完整用例|
|`python -m unittest discover -s tools/diagnostics -p test_sample_root_runtime.py`|3 项通过|
|`:app:tasks --all`|确认 Preview 单测、Debug assemble 与 lint 任务存在|
|`:app:testPreviewDebugUnitTest :app:assemblePreviewDebug :app:lintPreviewDebug`|单测与 APK 构建完成（缓存结果 225 项、0 失败）；lint 报 195 个既有 Android 源码错误，命令最终退出失败。相对 preview.10 的 app/libcore/版本配置没有修改，不通过忽略错误或新增 baseline 隐藏这些问题|
|NDK r27d / Android 31 ARM64 CLI 编译、实际 `pack.py` 打包|通过；使用已构建 preview.10 的真实 rootbox，本地 ARM64 PIE CLI 及官方打包器，产物不提交 Git|
|`git diff --check`|通过|
|ADB 设备清单|为空；未执行 Root 真机网络、Doze、吞吐及电池测试|

Preview Actions 是当前版本原有的构建/升级验证门禁；未使用旧 `verify-android.yml` 的已删除 v3.0.3 下载基线来判定本次变更。lint 结果作为独立已知问题报告，不冒充全部检查通过。

## 设备回归与度量

使用同一设备、网络、节点和配置，先运行 preview.10，再安装本次构建；安装时选保留全部数据。分别记录：

1. 首次连接、相同配置重复提交、实际节点切换、订阅更新、坏配置回滚：PID/状态/连接是否正确；相同配置核心 PID 应保持。
2. 停止、禁用、卸载、热更新及断网/恢复：进程回收与模块专属路由清理；不清空其他工具的防火墙表。
3. 熄屏、Doze、Wi-Fi/蜂窝切换、多轮下载/视频/UDP：掉线次数、吞吐中位数/范围、DNS 正确性。
4. 运行只读采样脚本，比较同条件下 RSS、线程、CPU ticks 与上下文切换；电池用系统统计进行独立长时对照。

```sh
python tools/diagnostics/sample_root_runtime.py --adb adb --serial DEVICE \
  --samples 60 --interval 10 --output before.jsonl
# 更新后同条件再次采样，使用 after.jsonl；不覆盖原测量。
```

脚本只执行状态查询与 `/proc` 读取，验证 PID 的启动时间；不收集节点名、配置、订阅、logcat 或密钥，不自动切换配置、重置电池统计或改变用户设备。
