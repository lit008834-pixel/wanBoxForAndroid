// @author 雾晚: Alias identities are retained for existing installations.
package io.nekohasekai.sagernet


enum class AppIcon(
    val aliasClassName: String,
) {
    NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.NekoBoxPlus",
    ),
    LIGHT_MODE(
        "io.nekohasekai.sagernet.launcher.LightMode",
    ),
    DARK_MODE(
        "io.nekohasekai.sagernet.launcher.DarkMode",
    ),
    OLD_NEKOBOX_PLUS(
        "io.nekohasekai.sagernet.launcher.OldNekoBoxPlus",
    ),
    NEKOBOX(
        "io.nekohasekai.sagernet.launcher.NekoBox",
    ),
    MIDNIGHT(
        "io.nekohasekai.sagernet.launcher.Midnight",
    ),
    HEAVENS(
        "io.nekohasekai.sagernet.launcher.Heavens",
    ),
    BLACK_WHITE(
        "io.nekohasekai.sagernet.launcher.BlackWhite",
    ),
    TEXT(
        "io.nekohasekai.sagernet.launcher.Text",
    );

}
