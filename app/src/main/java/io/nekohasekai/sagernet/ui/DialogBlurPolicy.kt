// @author 雾晚
package io.nekohasekai.sagernet.ui

/** Bounded, user-controlled window blur; Android/OEM capability is an explicit input. @author 雾晚 */
internal object DialogBlurPolicy {
    const val DEFAULT_STRENGTH = 12
    const val MAX_STRENGTH = 25

    fun radiusPx(api: Int, strength: Int, systemEnabled: Boolean,
                 reducedEffects: Boolean, density: Float): Int {
        if (api < 31 || !systemEnabled || reducedEffects ||
            !density.isFinite() || density <= 0f) return 0
        // Cap device pixels as well as dp, including unusual density overrides.
        return (strength.coerceIn(0, MAX_STRENGTH) * density).toInt().coerceIn(0, 100)
    }
}
