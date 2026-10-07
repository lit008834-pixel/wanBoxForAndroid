# ARM64 精简、后台稳定与 TCP RTT

作者：@author 雾晚

## P0：体积与前台服务

公开构建只输出 arm64-v8a。Helpers.kt 使用现有 ABI split，不能同时设置相同的 ndk.abiFilters：

```kotlin
// @author 雾晚
splits.abi {
    reset()
    isEnable = true
    isUniversalApk = false
    include(wanboxAbi) // 公开默认 arm64-v8a
}
isMinifyEnabled = true
isShrinkResources = true
resourceConfigurations += listOf("en-rUS", "zh-rCN", "zh-rHK", "zh-rTW")
packaging.jniLibs.useLegacyPackaging = true
```

用户确认保留压缩打包。false 会把原生库不压缩地存入直接下载 APK，不是“启用压缩”；Root 还需要安装器提取后的 nativeLibraryDir/librootbox.so。保留原流程与签名。

上一预览版 ARM64 实测 43,473,912 字节。压缩后的 libgojni.so 为 14,711,417 字节，librootbox.so 为 14,452,280 字节，合计已超过 22MB。保留两个核心及全部协议时不能承诺 22MB。实际文件不是文案中假定的 libsing-box.so。用 tools/apk_payload_report.py 比较真实 ZIP 压缩大小。

三张重复图片经 SHA-256 确认完全相同，改用 alias，保留资源名、像素与启动器入口。JSON 编辑器、Kryo、SnakeYAML 有业务引用，保留以避免破坏导入和历史数据。

R8 去掉所有业务类一律 keep 与 dontobfuscate，精确保护 JNI、AIDL、Parcelable、Room、协议 Bean 与 Gson 模型；完整配置见 app/proguard-rules.pro：

```proguard
# @author 雾晚
-keep class go.** { *; }
-keep class libcore.** { *; }
-keep class * extends io.nekohasekai.sagernet.fmt.AbstractBean { *; }
-keep class moe.matsuri.nb4a.SingBoxOptions$* { *; }
-keep class io.nekohasekai.sagernet.aidl.** { *; }
-keep class **_Impl { *; }
```

移除假定 Kotlin 空值检查无副作用的规则。ReleaseSerializationTest 在实际 R8 Release 上验证 VMess 分享、Kryo、JSON 备份、反射字段与核心配置属性。

Release 仪器测试先发现共享 androidx.tracing.Trace、再发现 kotlin.LazyKt 被内联删除，测试进程启动失败；AGP 从测试 APK 去重移除了这些应用共享依赖。内部 -PwanboxReleaseTests 构建增加 proguard-release-tests.pro，保留测试使用的共享库公开 API 和明确的应用测试入口，业务代码仍执行 R8，协议字段保护与公开版本一致。公开 APK 不使用这些测试规则；发布升级安装验证另行运行公开构建规则生成的模拟器包。

ServiceNotification 在核心初始化前同步提升前台服务，失败交给既有 stopRunner 清理；通知权限变化只阻止普通更新，不跳过必须的 startForeground。Android 拒绝后台启动时给出提示，没有无界重试。

现有 Manifest 声明保持，例如：

```xml
<!-- @author 雾晚：现有声明示例，未新增权限 -->
<service android:name=".bg.VpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="systemExempted" />
```

前台服务不能保证应用不会被系统或用户强制停止，本次没有新增常驻唤醒锁。

## P1：降低无效后台工作

通知初始刷新状态取真实屏幕状态，连接中收到熄屏也立即停止速度通知刷新；核心与已有低频后台采样保持。

自动订阅使用 UPDATE，不再先 cancel 再重新入队；每个订阅按自己的间隔计算到期，不把等待压到一分钟。最短周期 15 分钟，联网且电量不低执行非紧急自动更新；手动更新不受此约束。Future 随调用者取消，任务通知在 finally 清理。

```kotlin
// @author 雾晚
Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .build()
```

WebDAV 复用不可变 OkHttpClient、最多 10 个空闲连接、5 分钟保活，HTTPS/禁止重定向不变。代理健康检测不共享它的池，避免测到复用连接的假低延迟。

## P2：真实节点 TCP RTT

完整工具：bg/proto/TcpRttProbe.kt；Android 适配：TcpPing.kt。limitedParallelism(4) 配合 Semaphore(4) 限制整个悬挂请求并发。DNS 在计时前完成，使用可取消 DnsResolver；数字 IPv4/IPv6 直接转换。缓存按网络 handle + 主机隔离，最多 128 项、60 秒有效。成功历史限量保存，连接超时 3–8 秒；整个请求含 DNS 最多 8 秒。

```kotlin
// @author 雾晚：实际测量顺序
socket.tcpNoDelay = true
bind(socket) // VPN protect + 物理网络绑定；失败传播
val start = clock()
socket.connect(InetSocketAddress(address, port), timeout)
val rtt = TimeUnit.NANOSECONDS.toMillis(clock() - start).coerceAtLeast(1)
```

每次新建并关闭 Socket，取消主动关闭；不复用已建立的连接测 RTT。Java Socket 没有本任务需要的公开 Fast Open 开关，未调用私有接口或盲目为全部协议打开 TFO。

批量取消不会写成失败，失败清除旧延迟。UDP/QUIC 及没有 TCP 端点的配置跳过，不改变有效性与旧通道结果。状态栏继续走 VPN active core / RootTunService 的代理 HTTP 检测。TCP RTT 只表示服务器端口建连，不证明认证、TLS 或通道正常；显示真实结果，不保证几十毫秒。

新建分组示例（保存的显式设置优先）：

```json
{
  "type": "urltest",
  "tag": "fixture-auto",
  "outbounds": ["fixture-node"],
  "url": "http://connectivitycheck.gstatic.com/generate_204",
  "interval": "600s",
  "tolerance": 100,
  "interrupt_exist_connections": false,
  "idle_timeout": "30m"
}
```

这是核心无凭据的 204 检测，不放宽 WebView、订阅或 WebDAV 明文策略。保留状态栏 URL、自定义 URL、核心、栈与协议；未关闭 UDP、强制修改 keepalive 或增加 TPROXY/eBPF。

## 验证与回滚

### 桌面快捷方式直接执行

按用户追加要求，开关、启用、停用和指定节点快捷方式不再显示应用内确认弹窗。三个控制 Activity 改为 `android:exported="false"`，保留既有 ShortcutManager 发布的 ID/Intent、节点存在性检查、服务状态判断与 Binder 释放；VPN 系统授权和 Root 权限检查不变。Android 系统的 ShortcutService 以发布者身份启动快捷方式，目标 Activity 无需导出，普通外部 Intent 则由系统拒绝。未使用可伪造的 referrer 或 Intent extra 判断调用者。

依据：[AOSP LauncherAppsService 的 startShortcutIntentsAsPublisher](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-dev/services/core/java/com/android/server/pm/LauncherAppsService.java)。最低 Android 12，保留系统静态及固定快捷方式；第三方直接拼接 Activity Intent 的旧式自动化入口不属于系统快捷方式。

新增 ShortcutControlContractTest 检查私有边界、直接连接和授权/节点行为；独立测试 APK 验证外部直接调用被拒，再仅在临时模拟器取得 HOME 角色，通过公开 LauncherApps.startShortcut 启动三个实际发布的快捷方式，检查服务回调已执行且无需应用确认。测试结束恢复模拟器原 HOME 角色。此测试不替代带真实节点的 VPN/Root 连接验证。

- 分支 perf/arm64-background；用户另一工作树保持原样。
- 本地列出真实任务后运行 testPreviewDebugUnitTest、assemblePreviewDebug、assemblePreviewDebugAndroidTest 和 git diff --check。
- CI 跑 OSS/Preview 单测、ARM64 R8 Release、内部 x86_64 Debug/Release 仪器测试与升级安装；内部模拟器包不进入公开附件。
- verify_apk_abi.py 检查唯一 ABI、两个核心、压缩与 ARM64 ELF；发布流程检查包名与证书。
- TCP 测试包含虚构 DNS/Socket 与本地回环，验证 DNS 不计时、缓存失效、真实失败、取消、保护失败与并发。
- 调度测试覆盖各订阅到期、15 分钟下限、溢出与 Future 取消。
- ReleaseSerializationTest 配合现有备份、Room、UI 和安全测试验证 R8。
- ARM64 Root/VPN 长时间待机、Doze、切网和电量实测需要真实设备，模拟器与源码检查不能替代。
- 没有数据库迁移；回滚代码即可恢复测试、调度与界面行为。
- 本地 lintPreviewDebug 实际运行未通过：182 个错误，首项为 AssetsActivity 的既有 MissingSuperCall；未通过禁用检查制造成功。

官方依据：[JNI 打包](https://developer.android.com/reference/tools/gradle-api/8.3/com/android/build/api/variant/JniLibsApkPackaging)、[DnsResolver](https://developer.android.com/reference/android/net/DnsResolver)、[周期任务 UPDATE](https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy)、[前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)。
