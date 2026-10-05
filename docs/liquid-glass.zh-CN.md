# App 静态玻璃材质试点

作者：@author 雾晚

## 基线与范围

origin/main 与 v3.0.7-preview.1 均为 7e41d95f05b5bc37ebc68b4ee9e9dffd659f231f。实际架构为 XML/ViewBinding、Fragment、Material Components 和 RecyclerView；minSdk 21，compile/targetSdk 35。没有 Compose 或新增 UI 依赖。

原工作区 audit/ownbox-2.9.21-gso 的未提交测试、审查文档及未跟踪文件保留原样。本次在独立工作树、feat/liquid-glass-chrome 分支实施，没有 stash/reset/清理原工作区。

| 现有页面/资源 | 试点方案 | API/性能/可访问性边界 |
| --- | --- | --- |
| ToolbarFragment / ConfigurationFragment 已通过 UiChrome 统一承托面 | 同一入口改为主题表面色和 4% 主色渐变、1dp 细边框 | 无新增层级、动画、采样或 listener；API 21 可用 |
| layout_profile_list 订阅资产卡 | 仅内容承托面新增同样材质；保留 MaterialCardView 本体 | 圆角沿用 card_corner_radius；原卡片 stroke/elevation/点击不变；正文/次要文字有对比度回退 |
| ColorPickerPreference 预设和 HEX 弹窗 | MaterialAlertDialogBuilder 使用同一材质，沿用 dialog_corner_radius | 保留窗口 inset/dim、输入、取消/保存语义；无窗口 blur 或持久窗口引用 |
| 节点卡、Preference 行、StatsBar、节点多选页 | 保留原实体样式和布局 | RecyclerView 不加入材质/blur；BottomAppBar 的 FAB 凹口不被矩形背景覆盖；固定底栏不在本次范围 |
| bg_widget_glass / liquid / round / capsule | 本次保持不动，未套入 App 列表 | RemoteViews 静态资源，低透明白色 widget 渐变不适合作为 App 正文背板 |
| bg_dialog_glass | 当前只有 activity_node_select_dialog.xml 引用，未找到代码使用该布局 | 不把未使用资源当已实现的 App 弹窗入口；不改该闲置布局 |

bg_widget_capsule_liquid 还被 activity_lan_sharing.xml 复用；本次未改它，避免同时影响 RemoteViews 和共享页面。未修改 MainActivity 导航、ProfileSelectActivity、ProfileCardStyle、UiLayoutPolicy、主题/日夜/XML 颜色资源、桌面 Widget 或 launcher 图标。

## 材质、回退与对比度

- 工具栏复用 UiChrome.surface 的既有底色解释；卡片/弹窗取真实 colorSurface，保留与页面底色之间的层级。主色读取实际主题属性，自定义颜色从现有 Theme.customPrimaryColor 派生，Monet 使用当前主题属性。
- GlassPalette 为纯 Kotlin 数值计算，不依赖 Android 图像 API。上端为 Surface，下端仅掺入 4% primary；边框为 textColorPrimary 派生的弱描边，不使用浅色专属白色盖板。
- 标准承托面 alpha 245/255，与原 chromeAlpha 相同。对最黑/最白下层进行 WCAG 对比度约束；两端不能为黑/白文字提供至少 4.5:1 时，回退实体 Surface。中间色自定义表面不强行透明。
- 纯黑和纯白保持完全不透明、不染主色。低内存、省电、TalkBack 触摸探索或系统关闭动画时，沿用已有 UiChrome.reduceEffects 条件，生成实体表面；无动画承担信息表达。
- 半透明候选正文颜色先与表面合成；必要时选择黑/白文字。订阅卡正文和摘要显式使用此策略。图标、选中/连接/错误文字、焦点和点击监听保留原实现；不靠颜色代替状态。
- 每个目标 View/弹窗拥有独立 GradientDrawable，没有 Activity/Window 缓存。只在既有初始化/主题重建路径生成，无绘制循环、blur、RenderEffect、PixelCopy、实时折射或屏幕截图背景。
- MaterialCardView 本体、ripple、边框和 elevation 没有替换；内容 padding、文本顺序、单双列和触控区域不变。普通实体列表不增加过度绘制。

## 验证

实际 Gradle task 已从 app:tasks --all 确认，使用 JDK 17、SDK 35 和项目固定 Gradle 8.10.2；本地 libcore.aar/四 ABI root 可执行来自既有构建缓存，发布 CI 仍执行实际原生构建和签名检查。

```text
gradlew.bat --offline app:tasks --all
gradlew.bat --offline app:testPreviewDebugUnitTest app:assemblePreviewDebug app:assemblePreviewDebugAndroidTest app:lintPreviewDebug
python tools/check_android_security.py
git diff --check
```

- JVM 测试新增 GlassPaletteTest 四项：灰度表面全范围/多主色/正文和摘要候选，最亮最暗及饱和彩色底层，纯黑/白和减少效果回退，主题重建独立颜色。184 项单测 0 失败。
- Android 应用/仪器 APK 编译成功；首次新工作树缺 libcore.aar 的构建失败已通过正确复制现有生成物解决，不以失败结果冒充通过。
- lintPreviewDebug 实际失败：与 7e41d95 的基线均 133 项错误，按 issue ID/message/path 比较没有新增错误。不修改 lint baseline 或禁用规则。基线复测仅临时还原本任务工作树的改动文件，finally 精确恢复；原用户工作区未动。
- 新增 GlassUiTest，Android 实际 inflate/render 订阅卡和节点卡，检查点击/语义/文本和几何不变；覆盖日间/夜间/纯黑，1.0x/1.3x/2.0x，320/412dp，160/320dpi。保存修改前后的 72 张 PNG。
- “before”是原 UiChrome 平面承托和原订阅卡，“after”是新材质。图内数据全部为虚构公开样例，渲染在彩色底层上；这是 Android 原生组件渲染对照，不是完整 App 导航、真机屏幕截图或帧率测量。
- 另一个仪器测试检查浅色/深色/纯白/浅灰/红色及 API31+ Monet 的可读文字、独立 drawable 和对话框圆角；不注入或改用户主题偏好。
- CI 在现有 API35 模拟器中运行并保存图像，报告以实际 CI 结果为准；本地没有在线设备。API21 运行、真实 OEM 动态壁纸、TalkBack 手势/焦点、弹窗显示关闭和 RecyclerView 帧率/掉帧尚需设备验证，不将数值测试描述为这些验证已通过。
- 对照图使用 AGP 的 additionalTestOutputDir，由测试插件在卸载 Debug APK 前收集；CI 校验恰好 72 张 PNG，避免依赖正式包名或已被卸载应用的外部文件目录。首次 CI 的 19 项仪器测试已通过，但后续硬编码正式包名的图像收集失败；此处已改为插件收集路径，并保留失败证据。

人工验证：相同节点数/布局下比较日/夜、纯黑/白、自定义颜色和 Monet；主题切换重建/语言切换，320dp 和大屏，1.0x/1.3x/2.0x；工具栏打开关闭/搜索/菜单，订阅卡点击，颜色弹窗取消/HEX 保存；快速滚动节点，记录相同设备的帧时/掉帧，确认关闭页面没有持久窗口或视图引用。API21 和 API31+ 都应得到同类静态效果，绝不依赖设备 blur 能力。

## 发布边界

用户最后明确要求发布预览版。为避免覆盖已有 v3.0.7-preview.1 标签/附件，只递增 PRE_VERSION_NAME=3.0.7-preview.2、PRE_VERSION_CODE=343（最终 APK versionCode 1715）。正式版本、签名、applicationId、flavor、核心 pin、DataStore key/default、Room、路由、VPN/Root、通知和 Manifest 权限均不改。更新说明使用中文，不宣称吞吐/续航改善或实时模糊。
