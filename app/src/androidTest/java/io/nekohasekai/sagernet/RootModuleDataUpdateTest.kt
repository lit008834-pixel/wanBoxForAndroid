// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleDataUpdate
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real portable serializer and Room restore contract; no Root process required. @author 雾晚 */
class RootModuleDataUpdateTest {
    @Test fun threePoliciesPreserveOnlyRequestedSectionsAndDatabaseRestores() {
        val original = BackupRestore.parse(JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val proxy = ProxyEntity(id=9101,groupId=9102).putBean(SOCKSBean().apply {
            initializeDefaultValues();serverAddress="192.0.2.1";serverPort=1080
        })
        val source = BackupRestore.Plan(listOf(proxy),listOf(ProxyGroup(id=9102,name="fixture")),
            listOf(RuleEntity(id=9103,outbound=9101)),emptyList())
        val decoded = BackupRestore.parse(JSONObject(PortableBackup.encode(source).toString(Charsets.UTF_8)))
        assertSame(decoded,RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.KEEP_ALL))
        try {
            val nodes = RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.NODES_ONLY)
            BackupRestore.apply(nodes,true,true,true)
            assertEquals(9101L,SagerDatabase.proxyDao.getAll().single().id)
            assertEquals("fixture",SagerDatabase.groupDao.allGroups().single().name)
            assertTrue(SagerDatabase.rulesDao.allRules().isEmpty())
            assertTrue(io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all().isEmpty())
            BackupRestore.apply(RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.CLEAN),true,true,true)
            assertTrue(SagerDatabase.proxyDao.getAll().isEmpty())
            assertTrue(SagerDatabase.groupDao.allGroups().isEmpty())
        } finally { BackupRestore.apply(original,true,true,true) }
    }
}
