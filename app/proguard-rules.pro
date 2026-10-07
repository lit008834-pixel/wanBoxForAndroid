# @author 雾晚
-repackageclasses ''
-allowaccessmodification

# @author 雾晚: preserve exchange/reflective fields, not every UI/service class.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keep class go.** { *; }
-keep class libcore.** { *; }
-keep class io.nekohasekai.sagernet.fmt.Serializable { *; }
-keep class io.nekohasekai.sagernet.fmt.AbstractBean { *; }
-keep class * extends io.nekohasekai.sagernet.fmt.AbstractBean { *; }
-keep class io.nekohasekai.sagernet.fmt.v2ray.VmessQRCode { *; }
-keep class moe.matsuri.nb4a.SingBoxOptions { *; }
-keep class moe.matsuri.nb4a.SingBoxOptions$* { *; }
-keep class io.nekohasekai.sagernet.database.ProxyEntity { *; }
-keep class io.nekohasekai.sagernet.database.ProxyGroup { *; }
-keep class io.nekohasekai.sagernet.database.RuleEntity { *; }
-keep class io.nekohasekai.sagernet.database.SubscriptionBean { *; }
-keep class io.nekohasekai.sagernet.database.preference.KeyValuePair { *; }
# Generated Room implementations are loaded by class name.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class **_Impl { *; }
# AIDL/Parcelable IPC and XML-inflated custom controls keep their external contract.
-keep class io.nekohasekai.sagernet.aidl.** { *; }
-keepclasseswithmembers,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
# Kryo is used through explicit serializers/ByteBufferInput, not arbitrary input-selected classes.
# Keep protocol bean fields above for PreferenceBinding and portable Gson backups.

# ini4j
-keep public class org.ini4j.spi.** { <init>(); }

# SnakeYaml
-keep class org.yaml.snakeyaml.** { *; }


-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
-dontwarn java.beans.Transient
-dontwarn java.beans.VetoableChangeListener
-dontwarn java.beans.VetoableChangeSupport
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
-dontwarn org.bouncycastle.jce.provider.BouncyCastleProvider
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn java.beans.PropertyVetoException
