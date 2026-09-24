package com.hoons1994.iphoneduoanimation

/** Pure aspect-ratio checks for imported cover/inner screenshots. */
object SnapshotGeometry {

    enum class Role {
        COVER,
        INNER,
    }

    enum class Assessment {
        MATCH,
        LOOKS_SWAPPED,
        AMBIGUOUS,
        INVALID,
    }

    fun assess(role: Role, width: Int, height: Int): Assessment {
        if (width <= 0 || height <= 0) return Assessment.INVALID

        val shorter = minOf(width, height).toFloat()
        val longer = maxOf(width, height).toFloat()
        val ratio = shorter / longer

        val observed = when {
            ratio <= SurfaceClassifier.COVER_ENTER_MAX_RATIO -> Role.COVER
            ratio >= SurfaceClassifier.INNER_ENTER_MIN_RATIO -> Role.INNER
            else -> return Assessment.AMBIGUOUS
        }

        return if (observed == role) Assessment.MATCH else Assessment.LOOKS_SWAPPED
    }
}
