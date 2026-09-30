// @author 雾晚
package io.nekohasekai.sagernet.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Only immutable PendingIntents created by this app may reach this private receiver. */
class WidgetControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == OwnBoxWidgetHelper.ACTION_TOGGLE) {
            OwnBoxWidgetHelper.handleToggle(context)
        }
    }
}
