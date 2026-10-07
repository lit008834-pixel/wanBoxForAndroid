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
    @Test fun rootAndLocalProxyDeclareSpecialUseWithoutChangingVpnAuthorization() {
        val namespace = "http://schemas.android.com/apk/res/android"
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = manifest.getElementsByTagName("service")
        fun service(name: String) = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(namespace, "name").endsWith(".$name") }
        for (name in listOf("RootTunService", "ProxyService")) {
            val element = service(name)
            assertEquals("specialUse", element.getAttributeNS(namespace, "foregroundServiceType"))
            val property = element.getElementsByTagName("property").item(0) as org.w3c.dom.Element
            assertEquals("android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", property.getAttributeNS(namespace, "name"))
            assertTrue(property.getAttributeNS(namespace, "value").isNotBlank())
        }
        assertEquals("systemExempted", service("VpnService").getAttributeNS(namespace, "foregroundServiceType"))
        assertEquals("android.permission.BIND_VPN_SERVICE", service("VpnService").getAttributeNS(namespace, "permission"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        val notification = File("src/main/java/io/nekohasekai/sagernet/bg/ServiceNotification.kt").readText()
        assertTrue(notification.contains("ForegroundServicePolicy.type(service is VpnService)"))
    }
}
