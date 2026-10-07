// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.content.pm.ServiceInfo
import androidx.annotation.RequiresApi

/** Root/local proxy are not Android VPN integrations. @author 雾晚 */
object ForegroundServicePolicy {
    @RequiresApi(34)
    fun type(isVpn: Boolean): Int = if (isVpn) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
}
