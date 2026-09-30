// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** An error screen that does not depend on DataStore or an open Room database. */
class DatabaseRecoveryActivity : ComponentActivity() {
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)!!.use { output ->
                    ZipOutputStream(output).use { zip ->
                        for (name in listOf(Key.DB_PROFILE, Key.DB_PUBLIC)) {
                            for (suffix in listOf("", "-wal", "-shm", "-journal")) {
                                val file = getDatabasePath(name + suffix)
                                if (!file.isFile) continue
                                zip.putNextEntry(ZipEntry(name + suffix))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DatabaseRecoveryActivity, R.string.action_export_msg, Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DatabaseRecoveryActivity, error.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding * 2, padding, padding)
            addView(TextView(context).apply {
                setText(R.string.database_recovery_notice)
                textSize = 18f
            })
            addView(TextView(context).apply {
                text = SagerNet.databaseFailure?.javaClass?.simpleName ?: ""
            })
            addView(Button(context).apply {
                setText(R.string.database_recovery_export)
                setOnClickListener { export.launch("wanBox-database-diagnostics.zip") }
            })
        }
        setContentView(layout)
    }
}
