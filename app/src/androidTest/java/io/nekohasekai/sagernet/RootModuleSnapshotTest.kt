// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real App producer without root authorization or starting TUN. @author 雾晚 */
class RootModuleSnapshotTest {
    @Test fun actualGeneratorExportsPortableRootSnapshotAndPreservesDatabase() = runBlocking {
        val settings = PublicDatabase.kvPairDao.all()
        val rules = SagerDatabase.rulesDao.allRules()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080 }
        val instance = ProxyInstance(ProxyEntity(id = 99001, groupId = 99002).putBean(bean))
        try {
            DataStore.globalCustomConfig = ""
            DataStore.enableClashAPI = false
            DataStore.serviceMode = "vpn" // Historical preference normalizes to Root, no VPN authorization.
            instance.init()
            val snapshot = RootModuleSnapshot(instance, "").build()
            assertEquals(1, snapshot["schemaVersion"].asInt)
            assertEquals(99001L, snapshot["profileId"].asLong)
            assertEquals(Key.MODE_ROOT, DataStore.serviceMode)
            assertTrue(snapshot["files"].isJsonObject)
            assertTrue(snapshot["plugins"].isJsonArray)
            val config = snapshot["config"].toString()
            assertFalse(config.contains("/data/user/")); assertFalse(config.contains("/data/data/"))
            assertTrue(snapshot["config"].asJsonObject["inbounds"].asJsonArray.any {
                it.asJsonObject["type"]?.asString == "tun" && it.asJsonObject["auto_route"].asBoolean
            })
            assertEquals(rules, SagerDatabase.rulesDao.allRules())
        } finally {
            instance.close()
            BackupRestore.apply(BackupRestore.Plan(null, null, null, settings), false, false, true)
        }
    }
}
