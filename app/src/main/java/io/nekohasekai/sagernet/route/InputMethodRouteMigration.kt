// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.utils.CurrentInputMethod

/** One-time, idempotent conversion of preview.3's preference, without a Room schema change. @author 雾晚 */
object InputMethodRouteMigration {
    fun migrate() {
        val enabled = DataStore.configurationStore.getBoolean(Key.INPUT_METHOD_DIRECT) ?: return
        val context = SagerNet.application
        val identity = CurrentInputMethod.identity(context) ?: return
        migrate(identity, context.getString(R.string.input_method_direct), enabled)
    }

    internal fun migrate(identity: InputMethodDirectPolicy.Identity, name: String, enabled: Boolean) {
        // DB lock serializes main/:bg calls. Clear the old preference only after commit;
        // a crash between databases is safely retried against the equivalent existing rule.
        SagerDatabase.instance.runInTransaction {
            val dao = SagerDatabase.rulesDao
            val rules = dao.allRules()
            if (rules.none { InputMethodDirectPolicy.equivalent(it, identity) }) {
                val order = rules.minOfOrNull { it.userOrder } ?: 1L
                dao.createRule(InputMethodDirectPolicy.rule(identity, name, enabled).apply {
                    userOrder = if (order == Long.MIN_VALUE) order else order - 1
                })
            }
        }
        DataStore.configurationStore.remove(Key.INPUT_METHOD_DIRECT)
    }
}
