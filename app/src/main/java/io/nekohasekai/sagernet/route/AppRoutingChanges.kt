// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.Key

/** Persisted policy edits use the existing bounded passive-apply queue. @author 雾晚 */
internal object AppRoutingChanges {
    fun onSaved(key: String, requestReload: () -> Unit) {
        if (key in setOf(Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL)) requestReload()
    }
}
