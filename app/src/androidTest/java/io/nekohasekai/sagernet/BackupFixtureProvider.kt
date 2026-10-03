// @author 雾晚
package io.nekohasekai.sagernet

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import io.nekohasekai.sagernet.database.*

/** Only exposes generated fictional data from the test APK; never product files. @author 雾晚 */
class BackupFixtureProvider : ContentProvider() {
    override fun onCreate()=true
    override fun getType(uri:Uri)="application/octet-stream"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
        if(uri.lastPathSegment=="query-fails")throw IllegalArgumentException("fixture query failure")
        return if(uri.lastPathSegment=="named") MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)).apply {
            addRow(arrayOf("fixture.JSON",null))
        } else MatrixCursor(arrayOf("unknown_column")).apply { addRow(arrayOf("opaque-document-id")) }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(uri.lastPathSegment=="denied")throw SecurityException("fixture access denied")
        val file=File(requireNotNull(context).cacheDir,"fictional-backup.json")
        file.writeBytes(PortableBackup.encode(BackupRestore.Plan(null,null,emptyList(),null)))
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=throw UnsupportedOperationException()
    override fun delete(uri:Uri,selection:String?,args:Array<out String>?)=throw UnsupportedOperationException()
    override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?)=throw UnsupportedOperationException()
}
