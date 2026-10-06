# 四模块迭代：原生 View、路由与后台性能

@author 雾晚

> 2026-10-06 更新：用户取消高斯模糊。preview.5 移除窗口模糊、监听及强度设置，恢复原 Material 弹窗；最低版本按用户确认仍为 Android 12（API 31）。已有强度 KV 不再读取，升级不清理其他偏好。下文 UI 模糊实现与验证是 preview.4 历史记录，不能作为当前功能说明。路由、精简和后台优化设计仍待后续实施。

## 基线与首批范围

实际实现基线是 origin/main `b7795ab3653d80a8014ed1011bc7514ca03221f0`（preview.3），比指定 preview.2 新。保留已完成的输入法按钮移除及普通路由迁移，合并进入 preview.4。原用户工作区不覆盖。

当前：XML / ViewBinding / Fragment / Material Components 1.8.0，原基线 minSdk21，本次按用户最新要求提高到31、target/compileSdk35，JDK 17、Gradle 8.10.2，sing-box v1.15.0-alpha.10。没有 Compose 架构。正式构建已经 R8 + shrinkResources，不重复打开、不改变包名/签名/核心/Room/用户路由默认值。

本次按用户允许的分步开发实施**第一阶段：默认启用、仅强度调节的系统高斯弹窗背景模糊**。其余模块是下一阶段设计和源码差异清单，不能理解为已经全部实现。

| 模块 | 当前证据 | 本次 / 下一阶段 | 风险与验证 |
|---|---|---|---|
| UI 模糊 | UiChrome / GlassPalette 已有静态材质；bg_dialog 是语义 Surface；bg_dialog_glass 是固定深色旧资源 | 已新增0–25 高斯模糊强度（默认12，无关闭开关）；复用原 Material 内容与 dim；不将 Widget drawable 套到 App | API31+ 需系统支持；能力回调关闭时退回原外观；监听随窗口移除 |
| 路由增强 | RuleEntity + ConfigBuilder 生成 sing-box JSON；RouteRuleEditor 已校验 CIDR、端口、动作、规则集；当前 libcore 负责 RE2 校验 | 不新造第二套执行引擎。下一阶段先加解析回归再改域名输入；分组/白黑名单先定义数据与组合语义 | DNS 和流量规则必须一致；数据迁移/原规则顺序需单独审查；绝不悄悄丢弃无效原规则 |
| 代码精简 | Helpers.kt release 已开启 R8 / 资源压缩；多 ABI、JNI 与反射 keep 属于必要能力 | 已删除 ConfigurationFragment 未使用的 BottomSheetDialog import；其余依赖/语言/图片不凭搜索一次就删除 | 反射、Manifest、RemoteViews、JNI 和动态资源引用需跟踪；不删多语言、不盲改 keep |
| 耗电优化 | TrafficPollPolicy 已有后台 6/15/30秒策略；DefaultNetworkListener 有首次等待、专用线程、500ms 防抖 | 保留既有实现；新模糊无截图、timer、常驻动画/线程；下一阶段做持续连接电量与帧时间采样 | 不能把降低显示刷新当作暂停核心；不降低必要心跳、不增加保活权限 |

## 1. UI 模糊：本次真实代码

文件：`ui/DialogBlur.kt`、`ui/DialogBlurPolicy.kt`、`ui/UiChrome.kt`、`ui/ThemedActivity.kt`、`res/xml/global_preferences.xml`、`database/DataStore.kt`。

覆盖：主界面节点操作/测速弹窗、订阅管理弹窗、路由保存/校验/删除弹窗、规则集选择弹窗、设置/主题色弹窗，以及由 ThemedActivity 管理的 DialogFragment（包括继承 DialogFragment 的 Sheet）。普通全屏主页和设置页面保持清晰实体内容；并没有实现全屏实时背板模糊。

```kotlin
// @author 雾晚
// DialogBlur.Controller.update 的实际核心路径；省略周围生命周期代码见源文件。
val radius = DialogBlurPolicy.radiusPx(
    Build.VERSION.SDK_INT, DataStore.dialogBlurStrength, systemEnabled,
    UiChrome.reduceEffects(window.context),
    window.context.resources.displayMetrics.density
)
val attributes = window.attributes
attributes.blurBehindRadius = if (radius > 0) radius else originalRadius
attributes.flags = if (radius > 0)
    attributes.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
else (attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()) or
    (originalFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
window.attributes = attributes
```

设置：用户界面设置 → 高斯模糊强度；修改从下次弹窗打开生效。0 等于不模糊。强度表示 dp，转换为物理像素且上限100px，防止异常 density 超出预算。设置只增加一个 KV 偏好，无 Room schema 迁移。

窗口 attach 时注册 `addCrossWindowBlurEnabledListener`，detach 时移除并恢复原 blur flag/radius。无替换 onDismiss/onShow，保留调用方事件。关闭/0 时不注册系统监听。系统支持变化时更新原窗口；关闭能力后恢复原外观。

Android 10/11 及以下不再支持本软件：APK minSdk=31，由 Android 安装器拒绝安装/升级。没有兼容版本，不删除旧设备已有安装或数据。新功能仅以 API31+ 为支持范围。没有引入 BlurView 或截图降级，它们会增加采样、合成与滚动成本。Android12–15 也可能由设备、省电或设置关闭能力。按用户确认，仅限制系统版本；系统临时关闭模糊不阻止 App 正常运行。低内存、TalkBack 触摸探索、关闭动画时不开启效果。

没有给整个 View 设置 RenderEffect：那会模糊其自身文字/子树，并不自动模糊背后 sibling。没有改弹窗内容半透明，保留背景和文字对比度；本阶段视觉变化是弹窗后方景深，不能称为实时折射。窗口 API 依据：[Android WindowManager](https://developer.android.com/reference/android/view/WindowManager)、[LayoutParams blurBehindRadius](https://developer.android.com/reference/android/view/WindowManager.LayoutParams)。

## 2. 路由增强：已存在能力与下一阶段边界

执行链保持 `RouteSettingsActivity / RouteRulePreferences → RuleEntity → ConfigBuilder → sing-box`。**不存在可切换的 Kotlin 网络路由执行内核**；增强开关如后续添加，应控制输入规范化/验证，不另行匹配每个包。真正的域名/IP 匹配和缓存仍由当前 sing-box 处理。

实际代码参考：`route/RouteRuleEditor.kt`、`moe/matsuri/nb4a/SingBoxOptionsUtil.kt`、`fmt/ConfigBuilder.kt`。

```kotlin
// @author 雾晚
// 现有保存前校验路径的可复用调用，当前不改变用户规则。
val problems = RouteRuleEditor.problems(rule)
// 非空时 UI 显示每个字段与错误原因，保留未保存内容。
// 通过字段校验后还需当前 libcore 检查 validationConfig(rule)，不能只靠 Kotlin regex。
```

已证实待修差异：`makeSingBoxRule` 的 DNS/route 两条路径把裸 `*.example.com` 当成 domain_suffix 原值传入；核心不会因此自动获得 shell 通配符语义。两处还对 regexp 文本整体 lowercase，可能改变大小写敏感表达式。下一阶段应共享纯解析器，显式定义 `*.example.com` 为只匹配子域、`domain:example.com` 为现有后缀语义；保留 regexp 原样，并用 Go RE2 检查。尚未在本次修改该行为，不宣称域名/CIDR/DNS全部修好。

下一阶段测试先覆盖：完整域名、大小写/IDN、裸域与多层子域、example.com.evil、防通配符误匹配、IPv4 / IPv6 /32 /128 /0 与非法前缀；生成后的 route/DNS 语义一致；ID、顺序、启用与出站不变；全局模式和 FakeIP 例外不变。

规则组和白/黑名单不能单靠 UI 入口实现：需定义组顺序、组与规则的 enable 组合、OR/AND 与 invert 范围，备份新字段兼容和迁移回滚。现有 RouteRuleEditor.invertedGroup 已对完整 OR 谓词取反，不应逐子规则取反。下一阶段先给持久化方案再实施，避免清库或重排旧规则。

TXT/JSON 导入应使用 SAF ContentResolver，有输入大小和条目限制、结构签名、完整解析后的事务写入。无效项显示位置/原因，未经确认不自动删除旧规则。订阅里的节点和路由不是同一种对象，不把节点清洗器直接用于路由。命中日志只复用核心现有日志/API；不能承诺核心会输出数据库规则ID，更不能输出秘密或无限制后台详细日志。

## 3. 精简与构建：现有配置，无额外依赖

实际文件：`buildSrc/src/main/kotlin/Helpers.kt`、`app/build.gradle.kts`、现有 ProGuard 规则。

```kotlin
// @author 雾晚
// 当前 Helpers.kt 已配置；本次未重复修改。
getByName("release") {
    isMinifyEnabled = true
    isShrinkResources = true
}
```

实际 `nkmr_minify=0` 环境开关会同时关闭上述选项；发布按现有 CI 使用默认路径。保持 libcore.aar、Root 可执行文件、全部ABI及JNI/序列化 keep。正式签名从 CI 私密配置获取，Debug 不能覆盖正式签名安装。

下一阶段做同变体/ABI 的 APK payload 比较，再用 lint/dependency usage 和运行入口交叉确认冗余；动态插件、反射与 native 字符串引用不能仅凭 rg 判未使用。本次只删除一个未使用 import，未承诺 APK 明显缩小，也未删除任何语言。

## 4. 耗电与调试监控：复用已有策略

实际文件：`bg/proto/TrafficPollPolicy.kt`、`TrafficLooper.kt`、`utils/DefaultNetworkListener.kt`、`bg/ServicePowerLocks.kt`（具体所有权使用以源码为准）。

```kotlin
// @author 雾晚
// 当前 TrafficPollPolicy.ready 的实际机制，避免缺 core 时忙循环。
if (initialized) return true
delay(if (foreground) foregroundIntervalMs.coerceAtLeast(1000L) else 3000L)
return false
```

已有后台采样：非交互通常30秒，后台通知通常6秒，性能优先配置保留用户选择。只影响流量显示采样，不延迟 sing-box 本身拨号/路由。取消由协程所有者传递。网络监听已有 processed.await、专用 HandlerThread、防抖、旧 onLost 判断；不新增轮询，也不能未经证据关闭守护线程。

下一阶段调试监控方案（尚未添加）：仅 debug 构建和用户手动开始时用 Choreographer.FrameCallback 收集帧间隔；Memory 使用 Debug.MemoryInfo 一次性或低频采样；onStop 移除 frame callback 与取消采样 Job。记录帧时间分位数/内存，而不是只报一个 FPS。release 没有驻留监控。

Android10–15 前台服务、VPN授权与通知类型维持已有 Manifest，不以新增宽泛权限或常驻任务规避系统限制。MIUI/HyperOS 等厂商可能额外限制后台；HarmonyOS 只有兼容 Android API 的环境才适用，本项目不能保证原生鸿蒙运行或强制绕过后台策略。

## 测试清单与实际执行记录

- 自动：DialogBlurPolicyTest 覆盖 API21/23/29/30 fallback、31–35 支持、默认强度12/0/关闭系统模糊/减少效果、密度/强度上下界、设置资源契约和窗口监听释放契约。
- 自动 Android：DialogBlurTest 在实际窗口中覆盖强度0/12、按钮与背景保留、dismiss清理、重新show能力判定。它验证 API/生命周期，不代表 GPU画质或续航达标。
- 原单测/仪器测试保留：路由/备份迁移、VPN/Root 配置、单双列、静态材质字体矩阵等。
- 构建：真实任务已从 app:tasks --all 确认；执行 `app:testPreviewDebugUnitTest app:assemblePreviewDebug app:assembleOssDebugAndroidTest`，CI 另外完整 OSS/Preview 单测、API35仪器测试与发布签名构建。具体最终结果以本次交付记录为准。
- `git diff --check` 与安全契约检查；lint结果如有失败只按真实报告记录，不通过删保护制造通过。
- 人工待验：Android10/11拒绝安装、API31–35 支持/关闭模糊、省电切换、浅深黑白/自定义主题、1.0/1.3/2.0字体、横屏/分屏、嵌套弹窗/Sheet、TalkBack、窗口销毁；Perfetto 在滚动及弹窗开启前后比较帧时间，持续 VPN/Root 同节点/同网络熄屏对照至少1小时电量。
- 不将源码契约、模拟器能力判定或静态截图称作 Root 真机网络/续航验证。

### 本地执行结果（2026-10-05）

- 最终强度调节版：`app:testPreviewDebugUnitTest app:assemblePreviewDebug app:assembleOssDebugAndroidTest` 成功，197项单测0失败。
- AAPT 检查 Debug APK：versionName=3.0.7-preview.4，versionCode=1725，minSdk=31，targetSdk=35。Debug ID 含原有 .debug 后缀；预览Release仍用独立正式应用ID。
- `git diff --check` 与 `tools/check_android_security.py` 成功。
- 初次 `app:lintPreviewDebug --offline` 失败，149项错误。最低版本改为31后在2026-10-06重跑，移除模糊模块两处 ObsoleteSdkInt 分支后，最终全项目报告204项错误，首项为 ServiceNotification 的 MissingPermission；DialogBlur / DialogBlurPolicy 已无诊断。未关闭 lint 规则或改变依赖来掩盖结果。
- CI run 37338290681 的OSS/Preview各197项单测、API35全部21项仪器测试与外部控制检查通过；模糊低版本分支移除后，本地再次执行Preview全部197项单测与Debug构建成功。正式签名/升级验证以本次最终发布流程记录为准。
