// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import kotlin.concurrent.thread

object RootAccess {
    // This must run on a worker thread: a root manager may display a grant dialog.
    fun available(): Boolean = try {
        val process = ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start()
        var exitCode = -1
        val waiter = thread(isDaemon = true) { exitCode = process.waitFor() }
        waiter.join(15_000)
        if (waiter.isAlive) {
            process.destroy()
            false
        } else {
            exitCode == 0 && process.inputStream.bufferedReader().readText().trim() == "0"
        }
    } catch (_: Exception) {
        false
    }

    fun fallbackToVpn(context: Context, message: Int = R.string.root_unavailable_fallback) {
        DataStore.serviceMode = Key.MODE_VPN
        // @author 雾晚: DataStore listeners are process-local; notify the main process explicitly.
        context.sendBroadcast(Intent(Action.SERVICE_MODE_CHANGED).setPackage(context.packageName))
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            if (VpnService.prepare(context) == null) {
                SagerNet.startService()
            } else {
                context.startActivity(Intent(context, VpnRequestActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
    }
}
