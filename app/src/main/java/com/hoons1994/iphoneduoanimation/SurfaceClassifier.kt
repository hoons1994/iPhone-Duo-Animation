package com.hoons1994.iphoneduoanimation

/**
 * Classifies the active Fold surface from its orientation-independent aspect ratio.
 *
 * Fold handoff can briefly report intermediate bounds while One UI relayouts the task.
 * Use a generous dead band so those transient sizes do not look like extra physical
 * cover/inner switches and poison handoff calibration.
 */
object SurfaceClassifier {
    const val COVER_ENTER_MAX_RATIO = 0.56f
    const val INNER_ENTER_MIN_RATIO = 0.68f
    private const val INITIAL_SPLIT_RATIO = 0.62f

    enum class Surface {
        COVER,
        INNER,
        UNKNOWN,
    }

    fun classify(
        width: Int,
        height: Int,
        previous: Surface = Surface.UNKNOWN,
    ): Surface {
        if (width <= 0 || height <= 0) return Surface.UNKNOWN
        val shorter = minOf(width, height).toFloat()
        val longer = maxOf(width, height).toFloat()
        val ratio = shorter / longer

        return when {
            ratio <= COVER_ENTER_MAX_RATIO -> Surface.COVER
            ratio >= INNER_ENTER_MIN_RATIO -> Surface.INNER
            previous != Surface.UNKNOWN -> previous
            ratio < INITIAL_SPLIT_RATIO -> Surface.COVER
            else -> Surface.INNER
        }
    }
}
