package com.hoons1994.iphoneduoanimation

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeShaderSmokeTest {

    @Test
    fun snapshotTransitionShader_compiles_andAcceptsAllInputs() {
        val runtimeShader = RuntimeShader(SnapshotTransitionShader.SOURCE)
        val cover = Bitmap.createBitmap(8, 16, Bitmap.Config.ARGB_8888)
        val inner = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)

        runtimeShader.setInputShader(
            "coverSnapshot",
            BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setInputShader(
            "innerSnapshot",
            BitmapShader(inner, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
        )
        runtimeShader.setFloatUniform("coverSize", 8f, 16f)
        runtimeShader.setFloatUniform("innerSize", 16f, 12f)
        runtimeShader.setFloatUniform("resolution", 1080f, 2400f)
        runtimeShader.setFloatUniform("progress", 0.43f)
        runtimeShader.setFloatUniform("opening", 1f)
        runtimeShader.setFloatUniform("coverSurface", 1f)
        runtimeShader.setFloatUniform("handoffProgress", 0.43f)
        runtimeShader.setFloatUniform("focusWindow", TransitionTuning.FOCUS_HALF_WINDOW)
        runtimeShader.setFloatUniform("maxBlurPx", 32f)

        assertNotNull(runtimeShader)
    }
}
