# wanBox 3.0.3 正式版

作者：雾晚

## 测速与 Root 模式

- 统一节点列表和 Root 底栏测速口径：一次请求到响应头计时，包含建连和 TLS，取消旧版预热后取低值的做法。
- Root 测速采用标准本地代理 HTTP 客户端，连接、认证和 TLS 共用总超时，避免多阶段分别等待超时。
- Root 默认测试目标直接进入当前代理，跳过额外嗅探、提前解析与分流绕行；自定义其他目标仍遵循用户规则。
- 去除强制地址族策略导致的额外提前 DNS 解析，保留用户主动开启的目标解析设置。
- 默认测试地址改为 gstatic generate_204，手动测速默认总超时 3000ms，组测试默认间隔 300 秒。
- 保留 Root 首次连接、切换节点和回退 VPN 的生命周期修复。测速失败不作为 Root 权限失效处理。

## DNS 与订阅

- Fake-IP 增加 localhost、lan/local 及 NTP/time 排除规则。
- 订阅更新自动过滤套餐到期、剩余流量、官网、防失联、通知等条目，清理 Emoji 装饰。
- 完全相同配置按协议全参数 SHA-256 去重，保留相同地址但认证、TLS、传输参数不同的有效节点。
- 新安装本地代理默认端口 7890；升级保留现有端口和自定义测速地址。
- 提供本地代理 / TUN Fake-IP JSON 模板、中文设置指南及离线订阅清洗脚本。

## 升级与限制

- 包名保持 com.lit008834.pixel.wanboxforandroid，签名保持不变，versionCode 为 1645，可覆盖 3.0.3-preview.4。
- 模板中的节点需要填写真实服务器与凭据；多路复用仅在服务器支持时启用。
- 完整握手与旧版预热延迟不能直接比较；本次不通过减去握手时间降低显示数字，不保证真实网络的 2000ms 延迟必然降低。
- 发布前检查配置语法、测速超时、订阅清洗、Android 构建、APK 身份/签名及模拟器覆盖升级。Root 实际网络延迟与不同 Root 管理器兼容性仍需真机反馈。
- [配置与中文指南](https://github.com/lit008834-pixel/wanBoxForAndroid/tree/v3.0.3/docs/config)
- [离线订阅清洗脚本](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.3/tools/clean_subscription.py)
