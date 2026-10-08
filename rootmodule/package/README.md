# wanBox 独立 Root TUN 模块（实验预览包）

作者：雾晚。协议版本：1。GPL-3.0-or-later；见 LICENSE、LIBCORE-LICENSE。

此包仅用于 Android 12+、arm64-v8a 的 Magisk/KernelSU 安装验证，尚未完成管理器真机验收。不得安装到其他 ABI。

1. 先备份 App 内节点、规则、设置；停止其他接管 TUN 的 Root 模块。
2. 在管理器中安装 ZIP。安装器从管理器 staging 校验并直接激活代码，无需重启手机；运行中的核心会短暂停止再启动。首次安装没有运行配置，不接管网络。
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

默认保留全部数据。更新前在 App「设置 → 模式与入站设置 → 模块更新数据」选择三种方式：保留全部；全新安装（清除节点、订阅、规则、设置及模块配置）；仅保留节点和订阅（清除规则、其他设置及模块配置）。后两项是更新前立即执行的准备操作，确认后停止代理、保存完整 App 备份和 Root 私有归档，管理 App 重新启动，之后再安装 ZIP。内置资源与备份不删除，包名、签名、数据库 schema 不变。

App 备份可从同一页面分享，再通过「工具 → 备份」恢复。模块归档在 `/data/adb/wanbox/archives/data-reset-*`，不自动清理。处理中断时重新进入此页面继续；模块在事务未完成时拒绝重连和升级，不能绕过确认清数据。旧版 App 没有此入口时先覆盖安装本版本管理 APK；不带 APK包需要自行更新配套管理 App。

热更新要求管理器在独立 staging（例如 `/data/adb/modules_update/wanbox`）安装；拒绝把活动目录当 staging。新代码启动失败会尝试恢复旧代码及原快照；若旧核心清理超时则停止操作。管理器挂载、SELinux、热更新/回滚需真机验证，不保证零断流。

停止/禁用/卸载通过身份核对后的 SIGTERM 调用核心清理；不 flush 全局路由/防火墙。
若报 `cleanup_timeout_no_restart`，先停止操作并检查设备，不能强制重复启动制造双重路由。
SIGKILL、内核崩溃、SELinux/cgroup 限制下的残留路由恢复仍须真机验证。

核心 pin 为 SagerNet/sing-box v1.15.0-alpha.10；未升级核心。
模块监督器和配置事务由雾晚在 wanBox 中新写，只研究 NetProxy-Magisk 的架构模式，未复制其代码。
构建配方与完整源码见配套本地仓库 `rootmodule/`、`libcore/`。
本包不得作为已验证正式版传播；对外分发需同时提供对应源码及第三方许可材料。
