package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import kotlin.math.roundToInt

internal data class HomeDisplayGeometry(
    val width: Int,
    val height: Int,
    val rotation: Int,
) {
    val isCover: Boolean
        get() = SurfaceClassifier.classify(width, height) == SurfaceClassifier.Surface.COVER
}

/** Created off the main thread, then bound without generating bitmaps on a hinge frame. */
internal class CapturedHomeFrame(
    val geometry: HomeDisplayGeometry,
    val mipmaps: SnapshotMipmaps,
    val capturedAt: Long,
) {
    companion object {
        fun fromRenderedBitmap(
            rendered: Bitmap,
            geometry: HomeDisplayGeometry,
            capturedAt: Long,
        ): CapturedHomeFrame? {
            if (rendered.width <= 0 || rendered.height <= 0) {
                rendered.recycle()
                return null
            }
            val scale = minOf(1f, 1600f / maxOf(rendered.width, rendered.height))
            val bitmap = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    rendered,
                    (rendered.width * scale).roundToInt().coerceAtLeast(1),
                    (rendered.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                ).also { rendered.recycle() }
            } else rendered
            return CapturedHomeFrame(geometry, SnapshotMipmaps(bitmap), capturedAt)
        }
    }
}
