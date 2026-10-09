# wanBox 3.0.7-preview.14 · 节点切换与国内外分流

作者：@author 雾晚

## 本次更新

- **安装失败恢复**：上次“仅保留节点/全新安装”尚未由 App 完成时，允许覆盖更新模块代码，沿用原选择和请求令牌；不重复清数据、不提前启动。数据事务进行中仍拒绝更新，冲突选择需先打开配套管理 APK 完成原操作。

- **模块版本同步修复**：修正 preview.13 模块包内遗留的 preview.12 标记。现在模块版本从 APK 同源配置自动生成，带/不带管理器两种包均在发布前校验版本及身份。已有 preview.13 可直接保留数据覆盖升级。

- **手动切换立即提交**：切换节点绕过连续编辑的合并窗口，不再被旧界面状态拦截。快速连续点击以最后选择为准，同一节点支持重新提交；进行中的原子应用完整结束后继续应用新选择。
- **运行节点与选择分离**：模块确认实际运行的节点；旧状态回报不覆盖新选择。切换期间拒绝把旧节点的落地 IP 和通道延迟显示为新节点结果。
- **分应用自动应用**：名单、白/黑名单模式及开关保存后进入现有 400ms 合并队列，不再需要手动点应用。停止时只保存配置，不自动连接。
- **国内外分流预设**：在“路由 → 预设”选择“国内域名/IP 直连，其余走代理”。确认后启用规则模式和 DNS 分流，复用现有中国域名/IP 数据；已有指定应用、域名或节点规则仍优先。重复应用不会复制规则。
- **规则和 DNS 顺序修复**：旧式纯 geosite/geoip 规则按宽泛兜底处理，保留层内排序；地理 IP 匹配前按当前 DNS 策略解析，使用官方核心支持的有界 resolve 动作。

## 安装与更新

仅面向 **Android 12 及以上、arm64-v8a、已 Root 设备**。管理 APK 需要配套 Root 模块。

1. 在 Magisk/KernelSU/APatch 安装带或不带 APK 的模块 ZIP；音量上切换，音量下确认。
2. **默认保留全部。仅保留节点和订阅会清除路由规则及其他设置；全新安装还会清除节点和订阅。**
3. 带 APK 包支持覆盖更新或跳过管理器；不带 APK 包请手动覆盖安装新版 APK。不要先卸载旧 App。
4. 遇到旧版 `install_data_update_pending`，安装本版后打开配套管理 APK，让其完成原来的数据处理；不要卸载 App 或删除数据目录。
5. 节点切换仍需生成并原子应用完整配置，实际核心切换可能短暂断流；并非保证零耗时切换。

包名 `com.lit008834.pixel.wanboxforandroid`、原签名、数据库/备份格式及 sing-box `v1.15.0-alpha.10` 不变。APK `versionCode=1775`，模块 `versionCode=355`。

## 下载文件

- `wanBoxForAndroid-3.0.7-preview.14-arm64-v8a-release.apk`：管理 APK。
- `wanbox-root-module-with-manager-arm64-v8a.zip`：带管理 APK 模块。
- `wanbox-root-module-arm64-v8a.zip`：不带 APK 模块。
- `SHA256SUMS`：附件校验和。

## 验证与实现

发布流程在 Android 单元测试、Go/race、签名/ABI、覆盖升级、Room/配置生成的模拟器测试通过后才发布。未修改核心版本或引入参考项目私有 eBPF、DNS group、match_only 字段，不以规则移植宣称吞吐提升。

[实现、来源和边界](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.14/docs/preview13-routing-stability.zh-CN.md)。
