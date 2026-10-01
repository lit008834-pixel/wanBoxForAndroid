// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.text.TextUtils
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.updatePadding
import io.nekohasekai.sagernet.R

/** Fully reapplied at bind time, so recycled grid/list holders never inherit the other style. */
internal class ProfileCardStyle(private val card: View) {
    private val name = card.findViewById<TextView>(R.id.profile_name)
    private val address = card.findViewById<TextView>(R.id.profile_address)
    private val traffic = card.findViewById<TextView>(R.id.traffic_text)
    private val status = card.findViewById<TextView>(R.id.profile_status)
    private val type = card.findViewById<TextView>(R.id.profile_type)
    private val badge = card.findViewById<TextView>(R.id.profile_selection_label)
    private val titleArea = card.findViewById<LinearLayout>(R.id.profile_title_area)
    private val details = card.findViewById<LinearLayout>(R.id.profile_details_area)
    private val statusArea = card.findViewById<LinearLayout>(R.id.profile_status_area)
    private var compact = false
    private fun dp(value: Int) = (value * card.resources.displayMetrics.density + 0.5f).toInt()

    init { statusArea.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> arrangeStatus() } }

    fun apply(compact: Boolean) {
        this.compact = compact
        name.maxLines = if (compact) 1 else Int.MAX_VALUE
        name.ellipsize = if (compact) TextUtils.TruncateAt.MIDDLE else null
        address.maxLines = if (compact) 1 else Int.MAX_VALUE
        address.ellipsize = if (compact) TextUtils.TruncateAt.END else null
        traffic.maxLines = if (compact) 1 else Int.MAX_VALUE
        traffic.ellipsize = if (compact) TextUtils.TruncateAt.END else null
        status.maxLines = if (compact) 2 else Int.MAX_VALUE
        status.ellipsize = if (compact) TextUtils.TruncateAt.END else null
        badge.maxLines = if (compact) 1 else Int.MAX_VALUE
        titleArea.orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        titleArea.updatePadding(top = dp(if (compact) 2 else 8), bottom = dp(if (compact) 2 else 8))
        name.layoutParams = (name.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (compact) 0 else LinearLayout.LayoutParams.MATCH_PARENT
            weight = if (compact) 1f else 0f
        }
        badge.layoutParams = (badge.layoutParams as LinearLayout.LayoutParams).apply {
            width = if (compact) LinearLayout.LayoutParams.WRAP_CONTENT else LinearLayout.LayoutParams.MATCH_PARENT
            topMargin = dp(if (compact) 0 else 4)
            marginStart = dp(if (compact) 4 else 0)
        }
        details.updatePadding(bottom = dp(if (compact) 2 else 4))
        statusArea.updatePadding(bottom = dp(if (compact) 4 else 12))
        arrangeStatus()
    }

    // This changes text arrangement only, never the manually selected number of columns.
    private fun arrangeStatus() {
        val available = statusArea.width - statusArea.paddingLeft - statusArea.paddingRight
        val protocolWidth = type.paint.measureText(type.text.toString()) + type.paddingLeft + type.paddingRight
        val horizontal = compact && available >= protocolWidth + status.paint.measureText("123ms") + dp(8)
        val orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (statusArea.orientation != orientation) statusArea.orientation = orientation
        val lp = status.layoutParams as LinearLayout.LayoutParams
        val width = if (horizontal) 0 else LinearLayout.LayoutParams.MATCH_PARENT
        val weight = if (horizontal) 1f else 0f
        val start = dp(if (horizontal) 6 else 0)
        val top = dp(if (horizontal || compact) 0 else 4)
        if (lp.width != width || lp.weight != weight || lp.marginStart != start || lp.topMargin != top) {
            lp.width = width; lp.weight = weight; lp.marginStart = start; lp.topMargin = top
            status.layoutParams = lp
        }
    }
}
