package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader

/** Built only when snapshots change, never inside the frame callback. */
internal class SnapshotMipmaps(private val source: Bitmap) {
    private val levels = buildList {
        var previous = source
        repeat(7) {
            previous = Bitmap.createScaledBitmap(
                previous, (previous.width / 2).coerceAtLeast(1),
                (previous.height / 2).coerceAtLeast(1), true,
            )
            add(previous)
        }
    }

    fun bind(shader: RuntimeShader, prefix: String) {
        shader.setInputShader("${prefix}Snapshot", sampler(source))
        levels.forEachIndexed { index, bitmap ->
            shader.setInputShader("${prefix}Mip${index + 1}", sampler(bitmap))
        }
        shader.setFloatUniform("${prefix}Size", source.width.toFloat(), source.height.toFloat())
    }

    private fun sampler(bitmap: Bitmap) =
        BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setFilterMode(BitmapShader.FILTER_MODE_LINEAR)
            setLocalMatrix(Matrix().apply {
                setScale(source.width.toFloat() / bitmap.width, source.height.toFloat() / bitmap.height)
            })
        }
}
