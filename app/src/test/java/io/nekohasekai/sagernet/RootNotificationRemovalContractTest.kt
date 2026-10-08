// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Root module notifications must not reappear through secondary App paths. @author 雾晚 */
class RootNotificationRemovalContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()

    @Test fun activeRootObserverDoesNotOwnAnAndroidForegroundService() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("bg.RootTunService"))
        assertFalse(manifest.contains("bg.ProxyService"))
        assertFalse(manifest.contains("bg.VpnService"))
        val observer = source("bg/RootTunService.kt")
        assertFalse(observer.contains("startForeground("))
        assertTrue(observer.contains("Module observer does not own a foreground service"))
    }

    @Test fun subscriptionAndTestingContinueWithoutTrayPresentations() {
        val worker = source("bg/SubscriptionUpdater.kt")
        assertTrue(worker.contains("GroupUpdater.executeUpdate(profile, false)"))
        assertTrue(worker.contains("enqueueUniquePeriodicWork("))
        assertFalse(worker.contains("NotificationCompat"))
        val screen = source("ui/ConfigurationFragment.kt")
        assertFalse(screen.contains("ConnectionTestNotification"))
        assertFalse(File("src/main/java/moe/matsuri/nb4a/ui/ConnectionTestNotification.kt").exists())
        assertTrue(screen.contains("speedTestDialog?.show()"))
    }

    @Test fun upgradeClearsLegacyTrayEntriesWithoutNotificationPermissionPrompt() {
        val app = source("SagerNet.kt")
        val clear = app.substringAfter("fun clearLegacyNotifications()").substringBefore("private fun moduleCommand")
        assertTrue(clear.contains("notification.cancelAll()"))
        assertTrue(clear.contains("notification.deleteNotificationChannel(channel)"))
        for (id in listOf("service-vpn", "service-proxy", "service-subscription", "connection-test")) {
            assertTrue(clear.contains("\"$id\""))
        }
        assertFalse(app.contains("createNotificationChannels("))
        assertFalse(source("ui/MainActivity.kt").contains("POST_NOTIFICATIONS"))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("POST_NOTIFICATIONS"))
    }
}
