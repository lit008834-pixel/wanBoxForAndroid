// @author 雾晚
/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <[Email0]>                          *
 *  Copyright (C) 2017 by Mygod Studio <[Email1]>  *
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

package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.getSystemService
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection

class QuickEnableShortcut : Activity(), SagerConnection.Callback {
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHORTCUT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        confirmControl()
        if (Build.VERSION.SDK_INT >= 25) {
            getSystemService<ShortcutManager>()!!.reportShortcutUsed("enable")
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
        val state = BaseService.State.values().getOrNull(service.state) ?: return
        if (state == BaseService.State.Stopped) {
            SagerNet.startService()
        }
        finish()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }
}
