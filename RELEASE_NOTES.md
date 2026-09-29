<!-- @author 雾晚 -->
# wanBox v3.0.2

本版修复选择“浅灰”底色后，主题高亮和颜色标记仍显示亮蓝色的问题。浅灰模式的按钮、图标、选中状态和对话框现在使用一致的灰色系；蓝色预设仍可单独选择。

- 内核：sing-box v1.15.0-alpha.9。
- 保持独立包名 `com.lit008834.pixel.wanboxforandroid` 和相同签名证书。正式版 Android 版本号 1620，高于上一预览版的 1615，可直接覆盖升级。
- 可与包名为 `com.ownbox.app` 的 OwnBox 2.9.1 共存。
- 提供 arm64-v8a、armeabi-v7a、x86 和 x86_64 四个独立的正式版 APK。
- `SHA256SUMS` 列出每个 APK 的 SHA-256 哈希。下载后可运行 `sha256sum --check SHA256SUMS` 核对文件。
- 签名证书 SHA-256：`AEC62C7003FA515534817250D3CD0DC705E19F4CD578A7A9A1D22519596EA598`。
