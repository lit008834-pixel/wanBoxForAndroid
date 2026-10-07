# wanBoxForAndroid 应用标识迁移

wanBoxForAndroid 的正式版和预览版使用 `com.lit008834.pixel.wanboxforandroid`。`com.ownbox.app` 是 OwnBox 的旧应用标识。Android 将它们视为两个独立应用，可以分别安装、启动和卸载。

安装新版 wanBoxForAndroid 不会覆盖或卸载已安装的 OwnBox，也不会自动继承 OwnBox 的私有应用数据或设置。需要迁移时，可通过应用内备份与恢复功能按需选择配置、规则或设置；导入会覆盖所选类别中的现有数据。手动备份包含节点密码和密钥，应妥善保管；迁移完成前不要清除旧应用数据。

新标识后续更新必须继续使用同一个 applicationId、现有发布签名证书，并按版本策略递增 `versionCode`。仅更改显示名称或 `versionName` 不会改变 Android 的应用身份。

构建标识由 `nb4a.properties` 的 `PACKAGE_NAME` 提供，并由 Gradle 检查。Manifest 中应用专用权限和 Provider authority 使用 `${applicationId}`；小组件 action 在代码中由 `BuildConfig.APPLICATION_ID` 派生。`res/xml/shortcuts.xml` 的目标包名使用固定字符串，因为 Android 快捷方式的 intent 不支持字符串资源。构建工作流会检查快捷方式源码中的目标包名，以及最终 APK 的应用标识和 Manifest 组件。
