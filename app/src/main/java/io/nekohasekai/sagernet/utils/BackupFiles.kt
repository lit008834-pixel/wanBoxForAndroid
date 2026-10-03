// @author 雾晚
package io.nekohasekai.sagernet.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.PortableBackup
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream

/** Bounded mobile backup IO contract. @author 雾晚 */
object BackupFiles {
    data class Metadata(val name: String?, val mime: String?, val provider: String?, val size: Long?)
    fun metadata(resolver: ContentResolver, uri: Uri): Metadata {
        var name: String? = null; var size: Long? = null
        try { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)?.takeIf { it.isNotBlank() }
                if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s).takeIf { it >= 0 }
            }
        } } catch (_: Exception) { /* Metadata is optional. Do not mistake a provider document ID for a name. */ }
        val mime = try { resolver.getType(uri) } catch (_: Exception) { null }
        return Metadata(name, mime, uri.authority, size)
    }
    fun fileName(date: Date = Date()) = "OwnBox_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)}.json"
    fun read(input: InputStream, name: String? = null): JSONObject {
        // A log is never treated as a backup merely because it contains JSON.
        require(name?.endsWith(".log", ignoreCase = true) != true) { "选择的是日志文件，请选择 OwnBox_backup_*.json 或备份 ZIP；不要修改日志后缀" }
        val raw = BoundedInput.read(input, BoundedInput.SOURCE_BYTES)
        val zip = raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4b.toByte() &&
            raw[2] == 3.toByte() && raw[3] == 4.toByte()
        require(name?.endsWith(".zip", true) != true || zip) { "文件后缀是 ZIP，但内容不是备份压缩包" }
        val bytes = if (zip) BoundedInput.backupZip(raw.inputStream()).toByteArray(Charsets.UTF_8) else raw
        return PortableBackup.document(bytes).also { BackupRestore.parse(it) }
    }
    fun archive(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("OwnBox_backup.json")); zip.write(bytes); zip.closeEntry() }
        out.toByteArray()
    }
}
