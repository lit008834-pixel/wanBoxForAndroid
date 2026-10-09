# wanBox 3.0.7-preview.12 · 连续编辑合并与通知设置清理

作者：@author 雾晚

## 本次更新

- **连续编辑合并重载**：规则保存、删除、排序及节点/设置的被动重载共用 400ms 合并窗口；最多保留一次待提交操作，提交中继续编辑不取消当前事务，完成后应用最后状态。
- **停止状态不自启**：停止时仅生成、校验和原子保存新配置，不启动核心；显式连接与停止保持原操作入口。
- **保留统一快照去重**：沿用模块端完整内容比较、SHA-256 generation、版本冲突保护与失败回滚，重复配置不再次重启核心。
- **删除截图中的通知设置**：移除“显示直连的速度”和“速度通知更新间隔”及对应帮助说明；保留流量统计开关和历史设置值，不清除用户备份数据。旧通知仍在启动时清理，主题重建不重复清理。
- **IP 查询与统计边界收紧**：新查询退休旧票据，切换后切回同一节点也不接受旧结果；使用单调时间控制 IP 查询预算。流量采样拒绝无效时间，补齐重复 tag、负值和溢出验证。
- **规则顺序与安装说明**：显式域名/IP/应用等规则优先于只有规则集条件的兜底规则，各层保留用户排序；保留 DNS/防回环安全规则与全局模式。突出说明“仅保留节点和订阅”会清除路由及其他设置。

## 安装与更新

仅面向 **Android 12 及以上、arm64-v8a、已 Root 设备**。管理 APK 需要配套 Root 模块。

1. 选择带 APK 或不带 APK 模块 ZIP，在 Magisk/KernelSU/APatch 安装。音量上切换，音量下确认。
2. **保留全部数据为默认。仅保留节点和订阅会清除路由规则及其他设置；全新安装还会清除节点和订阅。**需要保留自定义规则请选择保留全部。
3. 带 APK 模块可安装/覆盖更新或跳过管理器；不带 APK 模块请手动覆盖安装新版 APK。保留原签名，不先卸载旧 App。
4. 模块沿用原热更新和安全数据处理流程；发生实际核心切换时可能短暂断流。

包名 `com.lit008834.pixel.wanboxforandroid`、原发布签名、数据库/备份格式及 sing-box `v1.15.0-alpha.10` 不变。本版 APK `versionCode=1765`，模块 `versionCode=353`。

## 下载文件

- `wanBoxForAndroid-3.0.7-preview.12-arm64-v8a-release.apk`：管理 APK。
- `wanbox-root-module-with-manager-arm64-v8a.zip`：带管理 APK 模块。
- `wanbox-root-module-arm64-v8a.zip`：不带 APK 模块。
- `SHA256SUMS`：附件校验和。

## 验证记录

本地 247 项 Android 单元测试、Preview Debug 构建、设备测试代码编译，以及 Go 模块测试/vet、流量统计测试通过。发布流程继续执行 Linux race、完整 libcore 测试、模块/签名/ABI 校验、旧版覆盖升级、OwnBox 共存及 API 35 模拟器的真实 Room/规则配置生成检查，通过后发布。

实现与边界：[连续编辑与精简审查](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.12/docs/preview12-efficiency.zh-CN.md)、[安装器选项](https://github.com/lit008834-pixel/wanBoxForAndroid/blob/v3.0.7-preview.12/rootmodule/install-options.zh-CN.md)。
