# wanBox 独立 Root TUN 模块（实验预览包）

作者：雾晚。协议版本：1。GPL-3.0-or-later；见 LICENSE、LIBCORE-LICENSE。

此包仅用于 Android 12+、arm64-v8a 的 Magisk/KernelSU 安装验证，尚未完成管理器真机验收。不得安装到其他 ABI。

1. 先备份 App 内节点、规则、设置；停止其他接管 TUN 的 Root 模块。
2. 在管理器中安装 ZIP。安装器先校验管理器 staging，退出后后台任务等待更新标记并应用代码；热更新成功无需重启手机，运行中的核心会短暂停止再启动。不要在这段等待期间重启或编辑配置。首次安装没有运行配置，不接管网络。
3. 打开配套管理 App，授权 Root，选择现有节点并连接。App 会验证并提交独立快照。
4. “自动连接”保留用户原值。打开后才允许下次启动自动运行最后有效快照。
5. Root 授权拒绝、模块未安装或被禁用时显示错误，不回退 Android VPN。

固定控制入口：`su -c '/data/adb/modules/wanbox/bin/wanboxctl status'`。
支持 `start`、`stop`、`restart`、`reload`、`module`、`logs`、`config validate/apply/rollback`、`autostart on/off`。
配置通过 stdin 提交；禁止把真实配置或 secret 放进命令行、报告或公开日志。

`example.snapshot.json` 是作者雾晚提供的空入站格式样例，安装器不会提交它，也不是可用代理节点。
代码位于 `/data/adb/modules/wanbox`，私有快照位于 `/data/adb/wanbox`，目录 0700、数据 0600。
升级和卸载保留私有配置恢复副本；卸载 App 也不删除模块配置。副本含敏感凭据，应妥善保管。

两个包：`wanbox-root-module-with-manager-arm64-v8a.zip` 包含原签名管理 APK并尝试覆盖安装；`wanbox-root-module-arm64-v8a.zip` 不含 APK，不修改已安装管理 App。系统拒绝 APK安装时手动安装同一 Release 的 APK，模块不会因此回滚。

安装器内选择三种数据方式：保留全部（默认）；仅保留节点和订阅（清除规则、其他设置及模块配置）；全新安装（清除节点、订阅、规则、设置及模块配置）。音量上循环切换，音量下确认。首次 10 秒未操作采用默认项，切换后 20 秒未确认取消安装；后两种方式还需再次按音量下确认，音量上或超时取消。按键读取失败取消安装，不修改用户数据。

带 APK 包可选择安装/覆盖更新或跳过；不带 APK 包不修改管理 App。App 内不再提供「模块更新数据」按钮。节点与订阅由 App 的 Room 数据库持有，安装器只记录已确认方式；全新/仅节点方式暂停连接，配套新版 App 首次打开时先保存完整备份，再完成模块归档和 Room 事务。没有更新配套 APK 时需手动更新再打开，中断后重新打开自动继续。内置资源与备份不删除，包名、签名、数据库 schema 不变。

App 完整备份在私有 `files/module-update-backups/OwnBox_backup_*.json`，可用 Root 文件管理器复制至 SAF 可选位置，再通过「工具 → 备份」恢复。模块归档在 `/data/adb/wanbox/archives/data-reset-*`，不自动清理。数据事务进行中拒绝重连和升级；仅安装选择待完成时允许更新模块代码，沿用原请求令牌和方式，不重置数据或启动旧配置。不同的数据选择需先打开配套管理 APK 完成原操作，不能绕过确认清数据；热更新与重启共用同一请求令牌，已完成请求不会再次处理新数据。

热更新要求管理器在固定 staging `/data/adb/modules_update/wanbox` 安装；拒绝把活动目录或其他路径当 staging。后台任务根据安装进程 PID/启动时间等待退出，确认活动目录 `update` 普通文件，再短暂等待管理器收尾；等待最多 60 秒，不占用生命周期锁。Magisk 删除的安装脚本/README 不属于运行时依赖；核心/CLI 等运行文件仍逐个验大小和 SHA256。热切换成功后移走暂存目录，避免重启重复应用。未完成时保留暂存包，由管理器按标准开机更新流程处理，并记录固定错误码。

新代码启动失败会尝试恢复旧代码及原快照；若旧核心清理超时则停止操作。管理器挂载、SELinux、热更新/回滚需真机验证，不保证零断流。

停止/禁用/卸载通过身份核对后的 SIGTERM 调用核心清理；不 flush 全局路由/防火墙。
若报 `cleanup_timeout_no_restart`，先停止操作并检查设备，不能强制重复启动制造双重路由。
SIGKILL、内核崩溃、SELinux/cgroup 限制下的残留路由恢复仍须真机验证。

核心 pin 为 SagerNet/sing-box v1.15.0-alpha.10；未升级核心。
模块监督器和配置事务由雾晚在 wanBox 中新写，只研究 NetProxy-Magisk 的架构模式，未复制其代码。
构建配方与完整源码见配套本地仓库 `rootmodule/`、`libcore/`。
本包不得作为已验证正式版传播；对外分发需同时提供对应源码及第三方许可材料。
