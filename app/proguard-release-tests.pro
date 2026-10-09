# @author 雾晚
# Only -PwanboxReleaseTests uses this file. R8 and resource shrinking remain enabled.
# AGP removes shared dependencies from the test APK. Preserve their external public APIs
# and the precise app facades invoked by androidTest; do not keep the whole business package.
-keep,allowobfuscation class kotlin.** { public *; }
# @author 雾晚: separate instrumentation APK calls these shared coroutine facades.
-keep,allowobfuscation class kotlinx.coroutines.** { public *; protected *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.RootModuleDataUpdate { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.RootModuleSnapshot { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.proto.ProxyInstance { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.bg.proto.BoxInstance { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.DomesticRoutingPreset { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.DomesticRoutingPreset$* { public *; }
-keep class androidx.tracing.Trace { *; }
-keep,allowobfuscation class androidx.core.** { public *; }
-keep,allowobfuscation class androidx.appcompat.** { public *; }
-keep,allowobfuscation class androidx.preference.** { public *; }
-keep,allowobfuscation class androidx.room.** { public *; }
-keep,allowobfuscation class androidx.arch.core.** { public *; protected *; }
-keep,allowobfuscation class androidx.sqlite.** { public *; }
-keep,allowobfuscation class com.google.android.material.** { public *; }
# @author 雾晚 Shared APIs invoked only by migration/scanner/serialization tests.
# Room migration-bundle adapters in the separate test APK also use TypeToken/tree/stream APIs.
-keep,allowobfuscation class com.google.gson.** { public *; protected *; }
-keep,allowobfuscation class com.google.zxing.qrcode.QRCodeWriter { public *; }
-keep,allowobfuscation class com.google.zxing.common.BitMatrix { public *; }
-keep,allowobfuscation class com.google.zxing.Result { public *; }
-keep,allowobfuscation class com.king.zxing.util.CodeUtils { public *; }
-keep,allowobfuscation class okhttp3.OkHttpClient {
    public boolean followRedirects();
}
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.v2ray.V2RayFmtKt {
    public static java.lang.String toV2rayN(io.nekohasekai.sagernet.fmt.v2ray.VMessBean);
    public static io.nekohasekai.sagernet.fmt.v2ray.VMessBean parseV2RayN(java.lang.String);
}

-keep,allowobfuscation class io.nekohasekai.sagernet.database.BackupRestore { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.BackupRestore$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.PortableBackup { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.PortableBackup$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.DataStore { public *; }
-keep class io.nekohasekai.sagernet.database.SagerDatabase$Companion { public *; }
-keep class io.nekohasekai.sagernet.database.preference.PublicDatabase$Companion { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.KryoConverters { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.ConfigBuilderKt {
    public static *** buildConfig(...);
    public static *** buildConfig$default(...);
}
-keep,allowobfuscation class io.nekohasekai.sagernet.fmt.ConfigBuildResult { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.RouteRuleEditor { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.RouteRuleEditor$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodDirectPolicy { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodDirectPolicy$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.route.InputMethodRouteMigration { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.UiChrome { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.UiLayoutPolicy { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.GlassPalette { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.GlassPalette$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.ui.ProfileCardStyle { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.Theme { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupHelper { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupFiles { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BackupFiles$* { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.BoundedImageDecoder { public *; }
-keep,allowobfuscation class io.nekohasekai.sagernet.utils.SecureNetwork { public *; }
-keep,allowobfuscation class moe.matsuri.nb4a.ui.ExpandablePreferenceCategory { public *; }
