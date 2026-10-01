# wanBox UI 优化与验收记录

@author 雾晚

## 范围与基准

- 修复分支：`fix/ui-consistency`，本轮 UI 修改完成时保留本地；用户随后明确授权提交并发布 3.0.4-preview.3。
- 工作分支起点：`41a320bd81403d18dfbb1ff7b697a35687138579`。
- 2026-10-01 核对远端 main：`8c4d8522b946fc1a1a8d77c64508815f3ea57c30`，与起点源码树相同，对应 `v3.0.4-preview.2`。
- 只读参考：ThroneForAndroid `v2.0.1`，commit `f67f1bb4b2da3e5d89d5975810e399ab5e554ab8`。
- 保留既有 View/XML、AndroidX/Material 架构，没有新增依赖，也没有复制参考项目的代码或图标。

## 参考取舍与实施顺序

| 参考项目内容 | 取舍 | wanBox 实现 |
| --- | --- | --- |
| 主界面与节点/分组卡片 | 借鉴信息层级，保留 wanBox 双列和卡片风格设置 | 名称、选中/连接标签、地址/流量、协议与测速结果分层；结果允许多行 |
| TestPanelController / layout_test_panel | 借鉴操作区域和状态提示，本次不移植整套面板 | 扩大刷新和菜单区域至 48dp；空列表提示；保留现有测试入口与结果 |
| LatencyHistogramView / SegmentedProgressView | 本次不做 | 它们依赖 TestSessionClient/TestSpec/TestingProgress；不接入另一套算法或虚构进度 |
| SettingsScreenFragment 与备份表单 | 借鉴统一间距、按钮层级 | 设置列表获得剩余屏幕高度；备份导出/上传为主按钮，导入/恢复等为次按钮 |
| JSON 编辑器 | 本次不做 | 保留现有编辑与校验流程，不引入编辑器、schema 或解析逻辑 |
| 顶部与 StatsBar 玻璃感 | 使用轻量半透明回退，本次不加入真实背景模糊 | 统一主题表面色，245/255 透明度；低内存、省电、TalkBack 或关闭系统动画时使用实色 |

顺序：先节点卡片和自适应布局，再顶部/底部色彩及点击区域，再订阅展示和设置/备份表单，最后测试与构建。已有图标、品牌色、底色模式保持使用项目资源。

## 实现与保护措施

- `UiLayoutPolicy` 按列表实际可用宽度与字体缩放计算列数，双列偏好保留，不写回设置。每列最小宽度为 `168dp × max(fontScale, 1)`。
- 节点名称和多行状态不再被单行或省略号截断。原来的 MarqueeTextView 构造器会强制单行，因此该布局改用普通 TextView；其他页面的跑马灯不变。
- 已选中/已连接提供独立文字标签及无障碍状态描述，不只依赖描边颜色。列表允许子菜单获得焦点。
- StatsBar 去除缩小到 8–10sp 的自动字号。刷新图标保留 24dp 图形，点击区域扩大为 48dp；状态更新使用 polite 无障碍播报。
- 节点列表底部留白按 StatsBar 实际高度、FAB 留白及系统底部 inset 更新。对应监听器和空列表观察器随视图销毁解绑。
- 半透明仅用于顶部和底栏表面，不截图、不创建实时模糊、不模糊文字和列表。底栏只修改 backgroundTintList，保留 Material FAB 凹槽和滚动行为。
- `UiChrome` 从主题属性取文字色；对比度不足时回退到黑/白，保留额外余量给轻微透明效果。系统无公开通用的厂商“降低透明度”接口，本次回退依据低内存、省电、TalkBack 和系统动画设置；无法保证识别各 OEM 的独立透明度开关。
- 订阅资产信息的到期时间、节点数量与更新时间改用中英文字符串/数量资源，不改变订阅数据、去重或日期解析。
- 备份卡片父容器不抢占按钮焦点；复选框至少 48dp；次按钮可换行；WebDAV 页面弹出菜单支持日夜主题。订阅表单内容按实际高度滚动。
- 未修改 Manifest、数据库、签名、包名、版本、核心、Root/VPN 服务、网络安全、备份恢复逻辑、磁贴/启动器图标或发布工作流。
- 单独比较确认 StatsBar 除视觉着色函数和对应 imports 以外的代码与起点一致，批量测速完整代码区域也一致。没有更改随机值、缓存、刷新、防并发、生命周期、generation 或测试 URL/超时。

## 修改文件与回滚范围

只需还原本次修改文件，删除本次新增文件；不要清空工作区或原有 `.fixture`、`.preview-artifacts`、`tools/__pycache__`。

- Kotlin：`ConfigurationFragment.kt`、`ToolbarFragment.kt`、`StatsBar.kt`。
- 新增：`ui/UiChrome.kt`、`ui/UiLayoutPolicy.kt`。
- 布局：`layout_profile.xml`、`layout_profile_list.xml`、`layout_main.xml`、`layout_appbar.xml`、`layout_config_settings.xml`、`layout_group_item.xml`、`layout_edit_group.xml`、`layout_backup.xml`、`layout_webdav_settings.xml`。
- 资源：默认及 `values-zh-rCN/strings.xml`，新增 `values/ui.xml`。
- 测试：`UiLayoutPolicyTest.kt`、`UiLayoutResourcesTest.kt`、`UiLayoutAccessibilityTest.kt`。
- 本记录：`docs/ui-consistency.zh-CN.md`。

## 验证命令与记录

项目路径包含中文，Windows AIDL 首次构建报 `MalformedInputException`；测试 worker 也曾因中文缓存路径报 `GradleWorkerMain` 类加载失败。已使用英文路径临时快照与缓存别名完成验证，仓库构建配置保持不变。所有修改的代码/资源与最终构建快照逐文件一致。

```text
gradlew.bat app:tasks --all -Pandroid.overridePathCheck=true
gradlew.bat app:testOssDebugUnitTest app:assembleOssDebug app:assembleOssDebugAndroidTest app:lintOssDebug --offline --continue --max-workers=2 --console=plain
gradlew.bat app:testOssDebugUnitTest --offline --init-script C:/Users/Public/Documents/Codex/wanbox-ui-tools-20261001/force-resource-tests.gradle --max-workers=2 --console=plain
python tools/check_android_security.py
git diff --check
```

| 检查 | 实际结果 |
| --- | --- |
| Gradle 任务核对 | 成功，确认项目提供 OssDebug、PreviewRelease 等任务 |
| 全部单元测试 | 122 项通过，0 失败、0 错误、0 跳过，包含新增 10 项；最终资源更新后再次实际执行，避免资源契约测试沿用 Gradle 缓存 |
| app:assembleOssDebug | 成功，产出 arm64-v8a、armeabi-v7a、x86、x86_64 四种 Debug APK |
| app:assembleOssDebugAndroidTest | 成功，设备测试 APK 编译完成；未安装、未运行设备测试 |
| app:lintOssDebug | **未通过：149 errors**，仓库 warningsAsErrors=true。所有报错的源位置都能对应基线保留代码/资源；未把这当成“Lint 通过” |
| Manifest/迁移/TLS 安全检查 | 通过 |
| XML 解析、git diff --check | 通过 |
| 保护代码对比 | StatsBar 测速/生命周期代码及完整批量测速区域未变；MainActivity、服务、备份逻辑、Manifest、版本和构建配置未变 |

Lint 首项是基线 `AssetsActivity.kt` 的 `onBackPressed` 缺少父方法调用。另有基线的重复查找 ID、缩进、翻译格式、权限命名和可访问性等问题。本次引入的中文 plurals 多余 `one` 数量项已修复，状态区原有嵌套权重已改为 ConstraintLayout。没有运行完整基线 Lint 来比较总数；“基线保留问题”的依据是最终 XML 报告的全部 149 个源位置逐项核对基线内容，不能据此宣称仓库全局 Lint 已达标。

最初在线 Lint 遇到 Maven 版本查询不可达，最终改为离线模式完成检查。离线模式不验证远端依赖是否有新版本。只在验证环境使用初始化脚本强制重跑测试，没有修改仓库的 Gradle、依赖或签名配置。Debug 使用项目原有调试签名；没有构建或发布新的正式/预览版本。

本地结果路径：

- 快照：`C:/Users/Public/Documents/Codex/wanbox-ui-check-20261001`。
- 单元测试报告：`app/build/reports/tests/testOssDebugUnitTest/index.html`。
- Lint 报告：`app/build/reports/lint-results-ossDebug.html`，以及同目录的 XML/TXT。
- APK：`app/build/outputs/apk/oss/debug/wanBoxForAndroid-3.0.3-arm64-v8a-debug.apk`（其余架构同目录）。这是未发布的 Debug 构建，不是新预览版本。
- 最终检查日志：`C:/Users/Public/Documents/Codex/wanbox-ui-tools-20261001/gradle-ui-completed-checks.log`。
- 最终实际单测日志：同目录 `gradle-ui-unit-final.log`。

新增单元测试覆盖窄屏、大字体、单列偏好、无效测量、底部 inset 和实色回退；资源测试覆盖多行文字、48dp 点击区域、设置视口、备份按钮层级。

新增设备测试覆盖中文/英文、浅色/深色、1×/2×字体下节点名称和三行测速结果不截断、菜单点击尺寸和表面文字对比度。**新增测试不等于已在设备运行。**

## 未实测矩阵与截图复现

当前 ADB 没有连接设备，不生成或冒充真机截图。以下项目需要设备/模拟器人工验收：

| 场景 | 操作与预期 |
| --- | --- |
| API 21 / API 35 | 用对应设备安装测试 APK，查看主页、设置、分组表单、备份页；旧 API 使用相同半透明/实色路径，无私有模糊 API |
| 浅色 / 深色 / 白黑灰底色 / 自定义色 | 切换外观后重建界面；检查顶部、底栏、菜单、主次按钮和协议卡片文字可辨 |
| 中英文 / 长名称 | 设置语言，导入长名称节点；选中节点后完整名称、协议、连接标签和多行结果均可读 |
| 320dp / 360dp / 横屏 | 切换双列偏好并旋转或调整窗口；不足最小卡片宽度时变为单列，偏好仍保留；返回宽屏恢复双列 |
| 字体 1× / 1.3× / 2× | 调整系统字体；状态栏不主动缩小字体、按钮可触达，列表最后一行能滚到遮挡区域上方 |
| 手势 / 三键导航 / 刘海 | 打开顶部与底部界面，检查 system bar inset、FAB 与状态栏，不覆盖最后节点或备份按钮 |
| 断开 / 连接中 / 连接成功 / 失败 | 观察原有服务状态和真实测速结果；确认没有旧延迟假装新结果、空列表有提示 |
| VPN / Root TUN | 用同一节点、同一 URL/超时测试，并在模式切换后重测；此项本轮没有设备实测 |
| TalkBack / 快速滚动 / 省电 | 检查卡片状态、更多/刷新按钮朗读与焦点；TalkBack/省电使用实色；滚动时不做背景捕获或模糊 |
| 导入 / 导出 / WebDAV | 按钮仍进入原有流程；检查运行中、成功、失败和取消反馈，恢复确认保留 |

截图建议按 `API-主题-语言-字体-宽度-页面.png` 命名，至少保存主页、长名节点卡片、设置页和备份页四组截图。只有在以上矩阵完成后才能声称视觉验收或设备回归通过。
