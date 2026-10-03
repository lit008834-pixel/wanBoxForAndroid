// @author 雾晚
package io.nekohasekai.sagernet

import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.utils.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.json.JSONArray
import android.os.Parcel

/** Actual generator, legacy records and atomic restore integration. @author 雾晚 */
class PortableBackupCompatibilityTest {
    @Test fun contentResolverMissingNamesOctetStreamAndDeniedReadAreHandled() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val resolver=context.contentResolver
        for(path in listOf("named","nameless","query-fails")) {
            val uri=android.net.Uri.parse("content://${context.packageName}.backup.fixture/$path")
            val metadata=BackupFiles.metadata(resolver,uri)
            assertEquals("application/octet-stream",metadata.mime)
            if(path=="named")assertEquals("fixture.JSON",metadata.name) else assertNull(metadata.name)
            val root=resolver.openInputStream(uri)!!.use { BackupFiles.read(it,metadata.name) }
            assertEquals(2,root.getInt("schemaVersion"))
        }
        val denied=android.net.Uri.parse("content://${context.packageName}.backup.fixture/denied")
        try { resolver.openInputStream(denied);fail("denied provider was read") } catch(_:SecurityException) {}
    }
    private fun snapshot() = BackupRestore.Plan(SagerDatabase.proxyDao.getAll(),SagerDatabase.groupDao.allGroups(),
        SagerDatabase.rulesDao.allRules(),PublicDatabase.kvPairDao.all())
    private fun sample() = BackupRestore.Plan(
        listOf(ProxyEntity(id=812,groupId=811,tx=22).putBean(SOCKSBean().apply {
            initializeDefaultValues();serverAddress="192.0.2.2";name="虚构节点";username="fictional";password="test-only"
        })),listOf(ProxyGroup(id=811,name="跨设备虚构分组",isSelector=true,frontProxy=812)),
        listOf(RuleEntity(id=813,enabled=true,outbound=812,domains="example.invalid",config="{\"invert\":true}")),
        listOf(KeyValuePair("fixture.text").put("中文虚构值")))
    @Test fun actualExportImportsIntoEmptyDestinationWithoutSourceDatabase() {
        val original=snapshot()
        try {
            BackupRestore.apply(sample(),true,true,true)
            val bytes=BackupHelper.doBackup()
            val root=BackupFiles.read(bytes.inputStream(),BackupFiles.fileName())
            assertEquals(PortableBackup.FORMAT,root.getString("format"));assertEquals(2,root.getInt("schemaVersion"))
            BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),emptyList()),true,true,true)
            BackupRestore.apply(BackupRestore.parse(root),true,true,true)
            val restored=snapshot()
            assertEquals(1,restored.profiles!!.size);assertEquals(1,restored.groups!!.size);assertEquals(sample().rules,restored.rules)
            assertEquals("test-only",restored.profiles.single().socksBean!!.password)
            assertEquals(812L,restored.groups.single().frontProxy);assertTrue(restored.groups.single().isSelector)
            assertEquals("中文虚构值",PublicDatabase.kvPairDao["fixture.text"]!!.string)
            val settingsOnly=BackupFiles.read(BackupHelper.doBackup(false,false,true).inputStream())
            assertFalse(settingsOnly.has("profiles"));assertFalse(settingsOnly.has("rules"));assertTrue(settingsOnly.has("settings"))
        } finally { BackupRestore.apply(original,true,true,true) }
    }
    private fun parcel(value:android.os.Parcelable):String {
        val p=Parcel.obtain()
        return try { value.writeToParcel(p,0);android.util.Base64.encodeToString(p.marshall(),android.util.Base64.NO_WRAP) }
        finally { p.recycle() }
    }
    @Test fun historicalManualAndAutomaticExportsAreUnambiguous() {
        val plan=sample()
        for(automatic in listOf(false,true)) {
            val root=JSONObject().put(if(automatic)"proxies" else "profiles",JSONArray().put(parcel(plan.profiles!!.single())))
                .put("groups",JSONArray().put(parcel(plan.groups!!.single())))
                .put("rules",JSONArray().put(parcel(plan.rules!!.single())))
                .put("settings",JSONArray().put(parcel(plan.settings!!.single())))
            if(!automatic)root.put("version",1)
            val migrated=BackupRestore.parse(BackupFiles.read(root.toString().byteInputStream(),"old.JSON"))
            assertEquals(812L,migrated.profiles!!.single().id)
            assertEquals("test-only",migrated.profiles.single().socksBean!!.password)
            assertEquals(plan.rules,migrated.rules)
        }
    }
    @Test fun cancelledOrFailedRestoreKeepsAllDatabasesIntact() {
        val original=snapshot()
        try {
            BackupRestore.apply(sample(),true,true,true)
            val before=snapshot();var calls=0
            try { BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),emptyList()),true,true,true) {
                if(++calls==4) throw kotlinx.coroutines.CancellationException("fixture cancellation")
            };fail("cancelled import committed") } catch(_:kotlinx.coroutines.CancellationException) {}
            val after=snapshot()
            assertEquals(before.profiles!!.map { it.id },after.profiles!!.map { it.id });assertEquals(before.rules,after.rules)
            assertEquals(before.groups!!.map { it.id },after.groups!!.map { it.id })
            assertArrayEquals(before.settings!!.single().value,after.settings!!.single().value)
            calls=0
            try { BackupRestore.apply(BackupRestore.Plan(emptyList(),emptyList(),emptyList(),listOf(KeyValuePair("fixture.text").put("new"))),true,true,true) {
                if(++calls==6)throw android.database.sqlite.SQLiteException("fixture late database failure")
            };fail("failed import committed") } catch(_:android.database.sqlite.SQLiteException) {}
            assertEquals(before.rules,SagerDatabase.rulesDao.allRules())
            assertEquals(before.profiles.map { it.id },SagerDatabase.proxyDao.getAll().map { it.id })
            assertArrayEquals(before.settings.single().value,PublicDatabase.kvPairDao["fixture.text"]!!.value)
            try { BackupFiles.read("{broken}".byteInputStream(),"bad.json");fail() } catch(_:Exception) {}
            assertEquals(before.rules,SagerDatabase.rulesDao.allRules())
        } finally { BackupRestore.apply(original,true,true,true) }
    }
}
