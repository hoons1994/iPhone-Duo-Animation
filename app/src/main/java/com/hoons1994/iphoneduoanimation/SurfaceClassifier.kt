package com.hoons1994.iphoneduoanimation

/** Classifies the active Fold surface from its orientation-independent aspect ratio. */
object SurfaceClassifier {
    const val COVER_ASPECT_THRESHOLD = 0.62f

    enum class Surface {
        COVER,
        INNER,
        UNKNOWN,
    }

    fun classify(width: Int, height: Int): Surface {
        if (width <= 0 || height <= 0) return Surface.UNKNOWN
        val shorter = minOf(width, height).toFloat()
        val longer = maxOf(width, height).toFloat()
        val ratio = shorter / longer
        return if (ratio < COVER_ASPECT_THRESHOLD) Surface.COVER else Surface.INNER
    }
}
