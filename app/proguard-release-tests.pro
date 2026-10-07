# @author 雾晚
# Only -PwanboxReleaseTests uses this file. R8 and resource shrinking remain enabled.
# AGP removes shared dependencies from the test APK. Preserve their external public APIs
# and the precise app facades invoked by androidTest; do not keep the whole business package.
-keep,allowobfuscation class kotlin.** { public *; }
-keep class androidx.tracing.Trace { *; }
-keep,allowobfuscation class androidx.core.** { public *; }
-keep,allowobfuscation class androidx.appcompat.** { public *; }
-keep,allowobfuscation class androidx.preference.** { public *; }
-keep,allowobfuscation class androidx.room.** { public *; }
-keep,allowobfuscation class androidx.sqlite.** { public *; }
-keep,allowobfuscation class com.google.android.material.** { public *; }

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
