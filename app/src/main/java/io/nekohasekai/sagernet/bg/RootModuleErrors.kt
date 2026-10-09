// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.sagernet.R

/** Fixed error codes only: never render exception messages containing user data. @author 雾晚 */
object RootModuleErrors {
    internal fun resource(code: String): Int = when (code) {
        "install_data_update_pending", "data_update_pending" -> R.string.root_module_data_pending
        "stop_node_tests_before_update" -> R.string.root_module_data_test_busy
        "stop_subscription_updates_before_update", "subscription_cancel_timeout" -> R.string.root_module_data_subscription_busy
        "install_data_selection_changed", "install_data_selection_invalid" -> R.string.root_module_data_selection_error
        "installer_journal_corrupt" -> R.string.root_module_data_journal_corrupt
        else -> R.string.root_module_action_failed
    }

    fun message(context: Context, code: String): String {
        val safe = code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"
        return context.getString(resource(safe)) + " ($safe)"
    }

    fun message(context: Context, error: Exception): String {
        val code = RootModuleClient.safeError(error)
        if (error !is InstallerDataCommit.Failure || resource(code) != R.string.root_module_action_failed)
            return message(context, code)
        val stage = when (error.stage) {
            InstallerDataCommit.Stage.QUIESCE -> R.string.root_module_stage_quiesce
            InstallerDataCommit.Stage.BACKUP -> R.string.root_module_stage_backup
            InstallerDataCommit.Stage.JOURNAL -> R.string.root_module_stage_journal
            InstallerDataCommit.Stage.MODULE_PREPARE -> R.string.root_module_stage_prepare
            InstallerDataCommit.Stage.APP_RESTORE -> R.string.root_module_stage_restore
            InstallerDataCommit.Stage.MODULE_FINISH -> R.string.root_module_stage_finish
            InstallerDataCommit.Stage.ACKNOWLEDGE -> R.string.root_module_stage_acknowledge
            InstallerDataCommit.Stage.REFRESH -> R.string.root_module_stage_refresh
        }
        return context.getString(R.string.root_module_data_stage_failed, context.getString(stage), code)
    }
}
