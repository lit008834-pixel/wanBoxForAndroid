// @author 雾晚
package io.nekohasekai.sagernet

import android.content.pm.ServiceInfo
import io.nekohasekai.sagernet.bg.ForegroundServicePolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Type selection and merged-contract prerequisites, independent of Root privilege. @author 雾晚 */
class ForegroundServicePolicyTest {
    @Test fun modernTypesSeparateVpnAndOtherModesWhileCallerGuardsApi34() {
        // This JVM policy test reads constants; it does not call Android foreground-service APIs.
        val type = ForegroundServicePolicy::class.java.getMethod("type", Boolean::class.javaPrimitiveType)
        assertEquals(ServiceInfo::class.java.getField("FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED").getInt(null),
            type.invoke(ForegroundServicePolicy, true))
        assertEquals(ServiceInfo::class.java.getField("FOREGROUND_SERVICE_TYPE_SPECIAL_USE").getInt(null),
            type.invoke(ForegroundServicePolicy, false))
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/ServiceNotification.kt").readText()
        val show = source.substringAfter("private fun show()").substringBefore("fun destroy()")
        assertTrue(show.indexOf("if (Build.VERSION.SDK_INT >= 34)") < show.indexOf("ForegroundServicePolicy.type"))
        assertTrue(show.contains("startForeground(notificationId, builder.build())"))
    }
    @Test fun observerDoesNotRegisterVpnOrOwnForegroundRuntime() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.BIND_VPN_SERVICE"))
        assertFalse(manifest.contains("android.net.VpnService"))
        assertFalse(manifest.contains("sagernet.bg.ProxyService"))
        val root = manifest.substringAfter("sagernet.bg.RootTunService").substringBefore("/>")
        assertFalse(root.contains("foregroundServiceType"))
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootTunService.kt").readText()
        val destroy = source.substringAfter("override fun onDestroy()")
        assertFalse(destroy.contains("stopRunner")); assertFalse(destroy.contains("RootModuleClient.stop"))
        assertTrue(destroy.contains("data.serviceScope.cancel()"))
    }
}
