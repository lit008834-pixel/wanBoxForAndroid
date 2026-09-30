# wanBox 本地代理与 Fake-IP 模板

作者：雾晚（@author 雾晚）

## 文件与填写方法

- `local-proxy.json`：sing-box 本地 mixed 代理，127.0.0.1:7890，同时接受 SOCKS/HTTP，没有 TUN、Root 重定向。
- `tun-fakeip.json`：独立核心的 TUN + Fake-IP 结构参考；Android wanBox 通常应在设置中启用 VPN 和 FakeDNS，由客户端生成平台 TUN 配置。

JSON 标准不允许注释，关键字段在本文注释。模板中的 Trojan 节点是占位示例：
必须把 proxy.example.com、密码、TLS server_name 换成真实服务器配置，并同步 DNS 直连排除的服务器域名。
你的 VLESS/Reality 节点应使用导出的完整 outbound 替换 node，保留 tag=node；不要把 Trojan 参数照搬给 VLESS。
模板没有包含真实凭据，未经填写无法连通。模板使用 sing-box 1.15 字段，不是 Mihomo YAML。

## 关键字段注释

- `dns-direct`：223.5.5.5 UDP，专用于直连域名及代理服务器引导解析。该流量按设计直连，非加密；需要加密可换 HTTPS 223.5.5.5 或 TLS dot.pub，并配置独立 IP 引导解析器。
- `dns-proxy`：1.1.1.1 DoH，显式 detour=proxy；节点域名只用 dns-direct 解析，避免“解析节点需要先连接节点”的循环。
- `dns-fake`：198.18.0.0/15，仅 TUN 入站 A 查询使用。localhost、lan/local、NTP/time 和直连域名在前置 DNS 规则中排除。
- 模板固定 IPv4；启用 IPv6 时需同时配置真实 IPv6 路由与 Fake-IP IPv6 地址，不能仅增加 AAAA 规则。
- `.cn` 只是最小国内域名示例，不等于完整国内分流库。现有 wanBox 规则继续使用其维护的规则集。
- `tcp_fast_open=true`：仅示例 TCP 节点启用，生效取决于系统及服务端。出现兼容问题时关闭。
- `auto`：每 300 秒进行组测速，切换容差 50ms；默认仍手动选择 node，可在 proxy 选择器切到 auto。添加多个节点时同步更新 auto.outbounds。
- `multiplex.enabled=false`：默认兼容未知服务端。确认服务端支持 sing-box h2mux 后可改 true；VLESS Vision、QUIC 协议或其他不支持的传输不要强开。它不保证降低所有节点延迟。
- 不设置全局强制 resolve 或 Root auto_redirect。测速先匹配当前代理，避免先执行一次不必要的 DNS 解析。
- Fake-IP 不是 DNS 加密。系统私人 DNS、应用内 DoH 和不走代理的应用不会自动被普通 mixed 端口接管，不能声称所有设备流量均无 DNS 泄漏。

## wanBox 设置

1. 先停止连接。在“模式与入站设置 → 运行模式”选“代理”可关闭 Root 接管，仅提供本地端口；其他应用必须明确使用 127.0.0.1:7890。不要同时运行另一套 Root 转发。
2. 需要全设备代理时选择“VPN”，在 DNS 设置启用 FakeDNS。Android VPN 图标属于系统行为。Root 模式仍可选择，不会因为测速失败自动切到 VPN。
3. 关闭“局域网共享”，取消“禁用混合入站”，代理端口设为 7890。升级会保留旧端口（例如 2080），不会悄悄改掉已配置的端口。
4. 延迟测试 URL 设为 https://www.gstatic.com/generate_204，超时 3000ms；组 URL 测试间隔 300 秒。自定义旧地址保留，用户需手动切换。HTTP cp.cloudflare.com/generate_204 可用于诊断，但它不测 TLS。
5. 不建议为测速开启 TLS 分片或强制提前解析目标；只有网络实际需要时再启用。
6. 重新更新订阅，通知条目会过滤、名称会清理，完全相同配置按全部协议参数去重。同一 IP/端口但不同密码、Reality short ID、SNI、传输参数的节点保留。

## 延迟读数

本次统一为一次请求从开始到响应头的时间，包含解析、建连及 TLS，整次请求受超时限制。
旧版节点列表显示预热后的延迟，Root 底栏显示完整握手时间，不能直接比较。
更新后列表数值可能升高，这是口径统一，不是为了显示好看而减去握手时间。
Root 默认测试 URL 通过本地 mixed 入站强制进入当前代理；自定义其他 URL 遵循现有路由规则。
无混合入站时使用独立节点测试，不代表当前 Root 转发路径的端到端时延。
网络、节点负载或 TLS 分片造成的真实 2000ms 不会被修改成虚假的低值。

wanBox 手动测速默认总超时为 3000ms，内置自动组探测也限制为 3 秒。
sing-box 的 urltest JSON 没有通用 timeout 字段，不要添加不支持的字段。
模板用于独立官方核心时，自动组遵循该核心自身的超时；间隔为 300 秒。

## 订阅离线清洗

```sh
python tools/clean_subscription.py input.json cleaned.json
```

支持 sing-box outbounds / Mihomo proxies 的 JSON；YAML 先用可信工具转 JSON，原文件保留。
脚本更新组与路由引用，删除后出现空组或无效默认引用时会报错，不会静默改为直连。
不接受覆盖原文件或已有输出文件；不联网，不输出密码或订阅链接。

## 官方字段参考

- https://sing-box.sagernet.org/configuration/dns/server/fakeip/
- https://sing-box.sagernet.org/configuration/dns/rule/
- https://sing-box.sagernet.org/configuration/shared/multiplex/
- https://sing-box.sagernet.org/configuration/shared/dial/
- https://sing-box.sagernet.org/configuration/outbound/urltest/
