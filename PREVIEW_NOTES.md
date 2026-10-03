# wanBox 3.0.5-preview.2

作者：@author 雾晚

## 本次更新

- 参考 Throne v2.0.1，改进路由规则编辑：常用项、可折叠高级项、多行输入、现有 geodata 规则集选择，以及动作/匹配字段控件。
- 增加地址/CIDR、端口/范围、规则集和动作校验；保存前由现有 sing-box 核心检查配置及 Go RE2 正则。全匹配规则须明确确认。
- 修正非路由动作携带 outbound、reject 方法字段、反转域名/IP 组合及多行 network 的配置生成问题。
- 保留规则 ID、排序、启用、出站、应用包名、自定义 JSON、导入和备份格式；数据库版本不变，不清除旧数据。
- 包含已合并的内置 YACD 首次首页落点与本地 API 失败提示修复。
- 应用包名、签名和 sing-box v1.15.0-alpha.9 不变，预览版本号递增，可更新已安装的 wanBox。

## 验证与限制

- 当前核心不支持 sniff 目标覆写，不提供无效开关。桌面进程/Wi-Fi 字段不自动迁移，原自定义 JSON 保留，保存时提示检查。
- 规则集选择使用 wanBox 已有 geodata 预设，不引入 Throne 的配置集/远程目录同步；远程 .srs 仍可多行填写 HTTPS 地址。
- VPN 应用匹配沿用既有实现；Root TUN 依赖 UID/套接字归属，真实命中效果需在对应 Android/内核验证。
- 本地无在线设备，未宣称真机联网及运行中 VPN/Root 服务重载通过；测试/构建和 CI 结果见仓库记录。
- 设计与验收步骤：docs/route-rule-settings.zh-CN.md。
