package moe.matsuri.nb4a

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import moe.matsuri.nb4a.proxy.config.ConfigBean

// Settings for all protocols, built-in or plugin
object Protocols {

    // Deduplication

    class Deduplication(
        val bean: AbstractBean, val type: String
    ) {

        fun hash(): String {
            // @author 雾晚: Include every protocol/transport/authentication field.
            // Names are presentation only; sharing an endpoint is not a duplicate.
            // Kryo clone omits inactive/unknown transport fields; snapshot JSON instead.
            val tree = moe.matsuri.nb4a.utils.JavaUtil.gson.toJsonTree(bean).asJsonObject
            tree.addProperty("serverAddress", bean.serverAddress?.trim()?.lowercase(java.util.Locale.ROOT))
            tree.remove("name")
            val identity = type + ":" + tree.toString()
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }

        override fun hashCode(): Int {
            return hash().toByteArray().contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Deduplication

            return hash() == other.hash()
        }

    }

    // Display

    fun Context.getProtocolColor(type: Int): Int {
        return when (type) {
            ProxyEntity.TYPE_BALANCER -> android.graphics.Color.parseColor("#FF6F00")
            ProxyEntity.TYPE_CHAIN -> android.graphics.Color.parseColor("#7E57C2")
            ProxyEntity.TYPE_NEKO -> getColorAttr(android.R.attr.textColorPrimary)
            else -> getColorAttr(R.attr.accentOrTextSecondary)
        }
    }

    // Test

    fun genFriendlyMsg(msg: String): String {
        val msgL = msg.lowercase()
        return when {
            msgL.contains("timeout") || msgL.contains("deadline") -> {
                app.getString(R.string.connection_test_timeout_error)
            }

            msgL.contains("refused") || msgL.contains("closed pipe") -> {
                app.getString(R.string.connection_test_refused)
            }

            else -> msg
        }
    }

}
