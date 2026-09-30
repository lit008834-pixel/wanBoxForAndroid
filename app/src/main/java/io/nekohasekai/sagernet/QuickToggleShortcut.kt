// @author 雾晚
/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore

@Suppress("DEPRECATION")
class QuickToggleShortcut : Activity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)
    private var profileId = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == Intent.ACTION_CREATE_SHORTCUT) {
            setResult(RESULT_OK, ShortcutManagerCompat.createShortcutResultIntent(this,
                ShortcutInfoCompat.Builder(this, "toggle")
                    .setIntent(Intent(this,
                        QuickToggleShortcut::class.java).setAction(Intent.ACTION_MAIN))
                    .setIcon(IconCompat.createWithResource(this,
                        R.drawable.ic_qu_shadowsocks_launcher))
                    .setShortLabel(getString(R.string.quick_toggle))
                    .build()))
            finish()
        } else {
            profileId = intent.getLongExtra("profile", -1L)
            confirmControl()
            if (Build.VERSION.SDK_INT >= 25) {
                getSystemService<ShortcutManager>()!!.reportShortcutUsed(if (profileId >= 0) "shortcut-profile-$profileId" else "toggle")
            }
        }
    }

    private fun confirmControl() {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(R.string.shortcut_control_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ -> connection.connect(this, this) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show().also { dialog ->
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
            }
    }

    override fun onServiceConnected(service: ISagerNetService) {
        if (profileId >= 0 && io.nekohasekai.sagernet.database.ProfileManager.getProfile(profileId) == null) {
            finish()
            return
        }
        val state = BaseService.State.values().getOrNull(service.state) ?: return
        when {
            state.canStop -> {
                if (profileId == DataStore.selectedProxy || profileId == -1L) {
                    SagerNet.stopService()
                } else {
                    DataStore.selectedProxy = profileId
                    SagerNet.reloadService()
                }
            }
            state == BaseService.State.Stopped -> {
                if (profileId >= 0L) DataStore.selectedProxy = profileId
                SagerNet.startService()
            }
        }
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
