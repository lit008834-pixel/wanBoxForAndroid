# NetProxy 安装与运行方式对照

作者：雾晚。本次基线为 wanBox `v3.0.7-preview.8` / `62f567d170fb829b1703fe50abd45afa63e773fe`。`v3.0.7-preview.9` 的安装流程修复在分支 `fix/module-installer-handoff-20261008` 实施，发布由预览版签名构建和升级验证流程完成。

2026-10-08 实时核验 [NetProxy nightly](https://github.com/Fanju6/NetProxy-Magisk/releases/tag/nightly) 的 tag、main、Release target 都指向 `e079e674c09d14a47700cca5f37cb58a9f512208`（v8.3.0，build 1036）。只读研究 GPL-3.0 项目的架构/测试思路，未复制实现。

| 项目 | NetProxy 证据 | wanBox 处理 |
|---|---|---|
| 安装退出后热切换 | [customize.sh](https://github.com/Fanju6/NetProxy-Magisk/blob/e079e674c09d14a47700cca5f37cb58a9f512208/src/module/customize.sh) 的 schedule/apply_hot_update | 修复同步 activate 的管理器写入竞态，改为有限后台任务 |
| 管理器最后写入 | [Magisk 安装器](https://github.com/topjohnwu/Magisk/blob/master/scripts/util_functions.sh) 的 install_module：补写 update/module.prop，删除 customize.sh/README.md | 等安装进程退出及普通文件标记后再激活；只允许这两个安装期文件被管理器移除，不放宽运行文件校验 |
| 生命周期 | [service.sh](https://github.com/Fanju6/NetProxy-Magisk/blob/e079e674c09d14a47700cca5f37cb58a9f512208/src/module/service.sh) → CLI boot；[lifecycle.go](https://github.com/Fanju6/NetProxy-Magisk/blob/e079e674c09d14a47700cca5f37cb58a9f512208/src/native/netproxy/internal/module/lifecycle.go) | wanBox 已有同类模块 boot → 独立 supervisor → rootbox，保留 |
| 数据/管理 APK 选择 | [安装指南](https://github.com/Fanju6/NetProxy-Magisk/blob/e079e674c09d14a47700cca5f37cb58a9f512208/docs/guide/installation.md) | 保留用户指定的两个 ZIP 和原 App 三种数据准备方式；不复制 Catalog，不从安装器操作 App SQLite |
| eBPF、Worker、遥测、Compose UI | 参考项目自有运行与管理架构 | 不移植；保留 wanBox Root TUN、原 View UI 和原数据格式 |

## 行为与失败边界

- `customize.sh` 保持短小，只做权限、可选 APK 安装、安排任务。任务继承固定 root/cgroup 处理和 setsid，stdin/stdout/stderr 与安装器分离。
- 等待期间不锁住模块，不停服；超时、取消、错误标记或安装包被替换均不进行代码切换。stage 仍由管理器持有。
- 真实更新仍使用已有配置校验、固定文件白名单、大小/哈希/ARM64 PIE 检查、停止/切换/重启/失败回滚。只恢复更新前正在运行的核心。
- 成功后原子移走管理器 staging，防止下次开机再次覆盖热更新代码。清理失败给出固定错误并保留诊断，不删除 `/data/adb/wanbox` 或其他模块。
- 未完成热更新时保留管理器暂存目录；标准开机更新是管理器行为，不等于这次热更新成功。不新增 Recovery/APatch 支持声明。

## 无效入口清理

删除设置 XML 中六项无效入口及绑定/帮助条目：`acquireWakeLock`、`wakeResetConnections`、`networkChangeResetConnections`、`meteredNetwork`、`showGroupInNotification`、`httpProxyBypass`。前三项之前被固定禁用；后三项分别属于 Android VPN/常驻代理通知/系统 HTTP 代理，当前模块控制链不使用。

DataStore 键及历史值、备份格式不删除；不改用户现有存储。保留自动连接、TUN stack/MTU、分应用、DNS/路由、混合入站、节点测速、磁贴、数据更新等实际可用入口，不改主题/卡片/导航布局。

## 验证

回归覆盖：安装进程未退出、标记未写入、标记收尾时消失、取消/超时、目录/符号链接假标记、管理器最后写入/删除安装文件、成功移走 staging、被替换的暂存包、丢失核心、旧代码和持久快照保留；设置契约覆盖六项入口消失而历史键仍存在。

命令：`go -C rootmodule test -count=1 ./...`、`go -C rootmodule vet ./...`、`python -m unittest discover -s rootmodule -p test_pack.py`、`:app:testPreviewDebugUnitTest :app:assemblePreviewDebug`、`git diff --check`。测试结果见本次交付；Windows 单测和模拟管理器文件操作不替代 Magisk/KernelSU 真机。新增后台安装任务、SELinux/cgroup、管理器挂载和真实断流恢复仍需 Root 设备验证。
