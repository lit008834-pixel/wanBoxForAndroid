# wanBox 3.0.5 正式版

作者：@author 雾晚

- 修复手动与自动备份的 profiles/proxies 及版本标识不一致；统一 JSON 格式 2，移除新备份对 Android Parcel 的依赖，保留真实旧格式迁移读取。
- 修复 SAF 文件名缺失、地区化导出名称、分享 URI 授权和选择器重建后的导出；拒绝把日志当备份，ZIP/JSON 增加结构与大小校验。
- 保留配置、分组、规则和设置，导入先验证后原子恢复，失败/取消回滚；不更改 Room schema、备份范围或 WebDAV 凭据。
- 改善首次批量测速：最多 4 个临时测速核心，5 秒请求预算，失败后延迟 300ms 重试一次；使用 http://cp.cloudflare.com/generate_204，取消不写失败，已连接 VPN/Root 测速路径保持不变。
- 包含 v3.0.5-preview.2 的路由高级编辑、当前核心校验、旧规则/备份兼容，以及 main 的内置 YACD 修复。
- 沿用现有 wanBox 包名、签名和 sing-box v1.15.0-alpha.9；正式版本代码递增，可覆盖 preview.2 更新。

说明：实际用户所选 OWN *.log 与备份是否同一文件无法仅凭截图确认；请选择真正导出的 OwnBox_backup_*.json，不要修改日志后缀。新备份可移植，旧 Parcel 无法读取时需原版本迁移。两台手机/OEM 文件提供者、真实 WebDAV 和实际节点首次测速效果需人工验证。

设计、兼容范围与测试说明：docs/portable-backup-and-batch-test.zh-CN.md。
