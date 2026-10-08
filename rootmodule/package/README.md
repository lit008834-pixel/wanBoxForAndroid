# wanBox 独立 Root TUN 模块（实验预览包）

作者：雾晚。协议版本：1。GPL-3.0-or-later；见 LICENSE、LIBCORE-LICENSE。

此包仅用于 Android 12+、arm64-v8a 的 Magisk/KernelSU 安装验证，尚未完成管理器真机验收。不得安装到其他 ABI。

1. 先备份 App 内节点、规则、设置；停止其他接管 TUN 的 Root 模块。
2. 在管理器中安装 ZIP 并重启。首次安装没有运行配置，不接管网络。
3. 打开配套管理 App，授权 Root，选择现有节点并连接。App 会验证并提交独立快照。
4. “自动连接”保留用户原值。打开后才允许下次启动自动运行最后有效快照。
5. Root 授权拒绝、模块未安装或被禁用时显示错误，不回退 Android VPN。

固定控制入口：`su -c '/data/adb/modules/wanbox/bin/wanboxctl status'`。
支持 `start`、`stop`、`restart`、`reload`、`module`、`logs`、`config validate/apply/rollback`、`autostart on/off`。
配置通过 stdin 提交；禁止把真实配置或 secret 放进命令行、报告或公开日志。

`example.snapshot.json` 是作者雾晚提供的空入站格式样例，安装器不会提交它，也不是可用代理节点。
代码位于 `/data/adb/modules/wanbox`，私有快照位于 `/data/adb/wanbox`，目录 0700、数据 0600。
升级和卸载保留私有配置恢复副本；卸载 App 也不删除模块配置。副本含敏感凭据，应妥善保管。

停止/禁用/卸载通过身份核对后的 SIGTERM 调用核心清理；不 flush 全局路由/防火墙。
若报 `cleanup_timeout_no_restart`，先停止操作并检查设备，不能强制重复启动制造双重路由。
SIGKILL、内核崩溃、SELinux/cgroup 限制下的残留路由恢复仍须真机验证。

核心 pin 为 SagerNet/sing-box v1.15.0-alpha.10；未升级核心。
模块监督器和配置事务由雾晚在 wanBox 中新写，只研究 NetProxy-Magisk 的架构模式，未复制其代码。
构建配方与完整源码见配套本地仓库 `rootmodule/`、`libcore/`。
本包不得作为已验证正式版传播；对外分发需同时提供对应源码及第三方许可材料。
