// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.utils.BackupFiles
import io.nekohasekai.sagernet.utils.BoundedInput
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

/** Portable backup contracts with fictional data only. @author 雾晚 */
class PortableBackupTest {
    private fun sample(): BackupRestore.Plan {
        val p = ProxyEntity(id=92, groupId=91, tx=123, speedTestMode="download").putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress="192.0.2.1"; serverPort=1080; name="虚构节点"; username="fake"; password="fictional"
        })
        return BackupRestore.Plan(listOf(p), listOf(ProxyGroup(id=91, name="虚构分组", isSelector=true, frontProxy=92)),
            listOf(RuleEntity(id=93, enabled=true, outbound=92, config="{\"invert\":true}", packages=setOf("com.example.fake"))),
            listOf(KeyValuePair("fictional.setting").put("中文设置"), KeyValuePair("fictional.long").put(1234567890123L)))
    }
    @Test fun portableObjectsPreserveAllSectionsWithoutParcel() {
        val bytes=PortableBackup.encode(sample())
        val root=BackupFiles.read(bytes.inputStream(), "TRANSFER.JSON")
        assertEquals(2,root.getInt("schemaVersion")); assertEquals(PortableBackup.FORMAT,root.getString("format"))
        assertTrue(root.getJSONArray("profiles").get(0) is JSONObject)
        val parsed=BackupRestore.parse(root)
        assertEquals("虚构节点",parsed.profiles!!.single().requireBean().name)
        assertEquals("fictional",parsed.profiles.single().socksBean!!.password)
        assertEquals(123L,parsed.profiles.single().tx)
        assertTrue(parsed.groups!!.single().isSelector); assertEquals(92L,parsed.groups.single().frontProxy)
        assertEquals(sample().rules,parsed.rules)
        assertEquals("中文设置",parsed.settings!![0].string); assertEquals(1234567890123L,parsed.settings[1].long)
    }
    private fun reject(block:()->Unit) { try { block(); fail("invalid backup accepted") } catch (_:Exception) {} }
    @Test fun schemaAndReferencesAreCheckedBeforeWrites() {
        val root=PortableBackup.document(PortableBackup.encode(sample()))
        root.getJSONArray("profiles").getJSONObject(0).put("groupId",999)
        reject { BackupRestore.parse(root) }
        reject { BackupRestore.parse(JSONObject().put("version",1)) }
        reject { BackupRestore.parse(JSONObject().put("format",PortableBackup.FORMAT).put("schemaVersion",999)) }
        reject { PortableBackup.strictBase64("not base64!") }
        val conflicting=JSONObject().put("version",1).put("profiles",org.json.JSONArray()).put("proxies",org.json.JSONArray())
        reject { BackupRestore.parse(conflicting) }
    }
    @Test fun logsAndDisguisedZipCannotBypassFormatChecks() {
        val bytes=PortableBackup.encode(sample())
        reject { BackupFiles.read(bytes.inputStream(), "OWN 7130087655019816740.LOG") }
        reject { BackupFiles.read("2026 INFO log message".byteInputStream(),null) }
        reject { BackupFiles.read(bytes.inputStream(), "backup.zip") }
        assertEquals(2,BackupFiles.read(bytes.inputStream(),null).getInt("schemaVersion"))
        assertEquals(2,BackupFiles.read(BackupFiles.archive(bytes).inputStream(),"backup.ZIP").getInt("schemaVersion"))
    }
    private fun archive(vararg entries:Pair<String,ByteArray>)=ByteArrayOutputStream().apply {
        ZipOutputStream(this).use { zip -> entries.forEach { (name,data)->zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry() } }
    }.toByteArray()
    @Test fun zipRejectsTraversalUnexpectedAndMultipleEntries() {
        val bytes=PortableBackup.encode(sample())
        for(name in listOf("../backup.json","/backup.json","folder/backup.json","C:backup.json","folder\\backup.json","readme.txt"))
            reject { BackupFiles.read(archive(name to bytes).inputStream(),null) }
        reject { BackupFiles.read(archive().inputStream(),"empty.zip") }
        reject { BackupFiles.read(archive("a.json" to bytes,"b.json" to bytes).inputStream(),null) }
        reject { BackupFiles.read(archive("a.json" to "{}".toByteArray()).inputStream(),null) }
        reject { BackupFiles.read(archive("../" to byteArrayOf(),"a.json" to bytes).inputStream(),null) }
    }
    @Test fun strictJsonRejectsDuplicateTrailingDeepAndOversizedData() {
        for(text in listOf("{\"version\":1,\"version\":2}","{} {}","{\"a\":"+"[".repeat(60)+"0"+"]".repeat(60)+"}"))
            reject { PortableBackup.document(text.toByteArray()) }
        reject { PortableBackup.document(ByteArray(BoundedInput.JSON_BYTES+1)) }
        reject { PortableBackup.document(byteArrayOf(0xc3.toByte(),0x28)) }
    }
    @Test fun fileNameIsAsciiInEveryLocale() {
        val previous=Locale.getDefault()
        try { for(locale in listOf(Locale.CHINA,Locale.US,Locale("ar"))) {
            Locale.setDefault(locale)
            assertTrue(BackupFiles.fileName(Date(0)).matches(Regex("OwnBox_backup_[0-9]{8}_[0-9]{6}\\.json")))
        } } finally { Locale.setDefault(previous) }
    }
}
