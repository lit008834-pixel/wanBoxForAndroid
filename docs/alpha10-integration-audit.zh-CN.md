# 官方 alpha.10 与 wanBox 集成审计（2026-10-08）

作者：@author 雾晚

## 基线与证据

- wanBox 开始时工作树干净；origin/main 为 `c7d4ab51af2de9f0ac68d0ccf45fef8f8199929b`，最近预览版 `v3.0.7-preview.6`。修复分支 `fix/launcher-alpha10-audit`。
- origin 为用户 wanBox 仓库，upstream 为 OwnBox，只读参考；本次没有合并参考仓库。
- `nb4a.properties` 固定 `SINGBOX_VERSION=v1.15.0-alpha.10`；`libcore/go.mod` replace 指向同级 `../../sing-box`。
- [官方目标 tag](https://github.com/SagerNet/sing-box/releases/tag/v1.15.0-alpha.10) 的提交为 `c992297988288565a24a6d36e2cf4d77cb835fcd`。本地同级源码原先为干净的 alpha.9 detached checkout；检查无改动后切换到目标 tag，remote/HEAD/tag 现已一致。这不代表此前 CI 发布包用了 alpha.9：构建脚本会重新取得并验证官方 tag。
- alpha.9 提交 `132b38e9caaba1a1959354d518e54d2d08419afe`；两 tag 共同祖先 `4537a1ac0023a5fc7816dc64915c67ad5253b6c1`；左右独有提交数 `40/59`。历史已分叉，不能把 compare 全部条目称作 alpha.10 新增。
- [官方 Changelog](https://sing-box.sagernet.org/changelog/) 和 Release 只有概括说明，以下是目标 tag 可达的真实提交依据，不是 alpha.10 独有功能清单。

## 筛选表

| 项目与官方依据 | wanBox 现状 | 处理 |
| --- | --- | --- |
| [GSO 检查 0efa6449](https://github.com/SagerNet/sing-box/commit/0efa6449429b4ffa1fd1eafa6920d264884f7966) | 核心包含修复；ConfigBuilder 与 SingBoxOptions 不再写废弃 `gso`；核心运行时 GSO 判断与旧 JSON 字段是不同问题 | 无需重复移植；保留现有配置测试 |
| [UDP 分片 d41e1745](https://github.com/SagerNet/sing-box/commit/d41e1745e6d0fabdc8083556f98309461535f622)、[零校验和 309434d3](https://github.com/SagerNet/sing-box/commit/309434d3ca49e9a4b4c6fe5e9b2c3c84a8522ab5) | 目标源码和依赖 pin 已包含；零校验和提交实际更新依赖 | 不复制实现、不升级依赖、不关闭 UDP；没有设备报文性能结论 |
| [连接中断锁 d2cf9747](https://github.com/SagerNet/sing-box/commit/d2cf9747d7689b4b4a3e199a1bb9bacd0fecf768)、[空闲连接 fd9a076d](https://github.com/SagerNet/sing-box/commit/fd9a076d252b6e868383bc23bc1a4fe44ce981f3) | 官方组在锁外关闭连接；wanBox 保留自己的 URLTest/loadbalance 注册、启动/停止/取消测试及独立测试 core | 保留，不覆盖；增加已有 URLTest race 检查 |
| [Android auto-redirect 1d0a5de2](https://github.com/SagerNet/sing-box/commit/1d0a5de293465a405f08e62843595c61a4a42f10) | 目标核心已包含路由地址集合空集处理；VPN 使用 Android FD，Root 使用已有独立进程/路由机制 | 不强制启用新 auto-redirect 路径，不改默认路由 |
| 系统 DNS、接口缓存 | `platform_box.go` 提供系统 DNS 地址；`dns_android.go` 走 Android 网络句柄；DefaultNetworkListener 等首次处理、专用线程、500ms 防抖和旧 onLost 保护；interface_monitor 按实际接口变化更新 | 保留本地等价行为；未发现足以修改的设备复现依据 |
| [协议输入校验 2ac9c920](https://github.com/SagerNet/sing-box/commit/2ac9c920a9a89753b00c236fe27c7bd575717ecd) | 目标核心已包含；wanBox 配置/订阅/备份边界不变 | 不复制桌面/服务端无关功能 |
| wanBox VPN protect 桥接 | `protect.go` 接收 SCM_RIGHTS 后只关 Unix 连接，没有关内核复制给接收端的 FD | 最小修复：同步回调借用副本，所有退出路径释放；限定单 FD、处理截断。不改变发送端 ownership、保护重试或 Root 路由 |
| wanBox 应用图标 | `mipmap/wanbox_launcher` → `drawable/wanbox_launcher_art` 别名 → 同一 mipmap；API26+ 选中 adaptive XML 后成环 | 将完全相同的原图移到独立 drawable PNG，基础 mipmap 用 bitmap XML 引用它，删除成环别名；不重复存放 PNG，不改默认图案/Manifest/磁贴 |

核心已同步，无需重复移植。实际代码改动集中于 wanBox 的资源引用和接收端文件描述符生命周期。

## 行为、风险及边界

- FD 清理发生在同步 `VpnService.protect` 回调及 ack 后；异常时也释放。SCM_RIGHTS 发送端 socket 保持有效。测试用真实 Unix socket 和虚构数据，不记录用户配置。
- 仍保留已有并行 handler、协议 ACK 和监听生命周期。本次不增加后台轮询、不调整超时/GC，不宣称已解决所有异常系统杀进程场景。
- BaseService 继续由 ConnectedUrlTest 分派：VPN 使用当前 app/service core；RootTunService 校验连接、ready 和进程后经本地 mixed inbound 探测，禁用 mixed 时使用 TestInstance 回退。URL、单位、超时和结果展示不变。
- icon.png 桌面自定义、tile.png 磁贴、启动器别名和系统应用图标是不同资源；本次仅恢复正常启动器前景，保留磁贴及快捷方式逻辑。
- 四 ABI 原生编译与 Root native artifact 检查不变；沿用已有公开 ARM64-only APK 策略，不把内部 x86_64 模拟器包公开。minSdk=31、applicationId、签名和正式版元数据不变；只递增获授权发布的预览版本。

## 验证记录

1. 修改前运行 `app:testOssDebugUnitTest --tests '*LauncherResourceTest'`：失败，断言直接给出上述资源循环；不是凭截图猜测。
2. 本地确认 `app:tasks --all`；OSS/Preview 各 212 项单测通过，`app:assembleOssDebug app:assemblePreviewDebug app:assembleOssDebugAndroidTest` 通过。
3. 本地 `go test ./internal/...` 首次因未生成 go.sum 无法解析依赖；`go test -mod=mod ./internal/...` 补全模块解析后三个包通过。go.sum 为现有构建流程生成的忽略文件，不提交。
4. CI 按 verify-android.yml 运行 `./run lib core`、完整带标签 libcore 测试、internal/URLTest/loadbalance、相应 race、日志/TUN/SCM_RIGHTS 测试、两份配置的官方核心校验、四 ABI native artifact 检查。
5. 安装后 LauncherIconTest 在 Debug 与 R8 Release 的 API35 模拟器上加载 PackageManager 图标与日夜资源，核对原始前景像素并绘制，防止仅 XML 能编译却实际回退。既有 UI/数据库/安全/快捷方式测试继续运行。
6. `git diff --check` 与最终 APK 包名/versionCode/证书/ABI 校验；发布需通过保留的正式版及预览版签名覆盖安装检查。
7. 官方源码 `go test ./option ./common/sniff ./dns/transport` 三包通过，官方源码工作树未修改。`app:lintPreviewDebug` 最终为 180 项已有项目错误，第一项为 AssetsActivity MissingSuperCall；本次修复中发现的新增重复 PNG 已消除，保留一份原始图案，未屏蔽检查或设置忽略错误。

本机当前无 ADB 设备，Windows 未配置 Linux 子系统；Linux/Android 专用测试由 CI 执行。MIUI 桌面缓存刷新、Root 真机、UDP checksum/分片抓包、屏幕关闭后的唤醒锁、吞吐/DNS/空闲 CPU/电量基准未实测。无性能数字或吞吐提升承诺；用户可覆盖安装后检查原桌面入口，桌面若保留旧缓存需等待系统刷新或重新添加入口。
