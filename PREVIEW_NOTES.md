# wanBox 3.0.7-preview.5

作者：@author 雾晚

## 更新内容

- 取消高斯模糊，弹窗恢复原有 Material 效果；移除窗口模糊监听、模糊强度设置及相关实现。
- 保留已有主题与静态材质样式。

## 回归测试

- 更新设置层级契约测试，检查移除模糊强度项后设置项键集合与基线一致。

## 兼容性与升级

- 预览版 `versionName` 为 `3.0.7-preview.5`；元数据 `PRE_VERSION_CODE=346`，Android `versionCode=1730`（构建时乘以 5）。
- 最低系统版本为 Android 12（API 31）；保持应用包名 `com.lit008834.pixel.wanboxforandroid`、既有签名及 sing-box `v1.15.0-alpha.10`。
- 可覆盖安装上一 wanBox 预览版，用户配置保持不变。Release 提供 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四种架构 APK，并附 `SHA256SUMS`。
