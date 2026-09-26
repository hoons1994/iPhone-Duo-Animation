package com.hoons1994.iphoneduoanimation

import android.graphics.BlendMode
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader

/** Each branch samples one live Gaussian level, projects it, and contributes its weight. */
internal object LiveFoldShader {
    const val SOURCE = """
        uniform shader content;
        uniform float2 resolution;
        uniform float coverSurface;
        uniform float foldCos;
        uniform float foldSin;
        uniform float eyeDistancePx;
        uniform float maxBlurPx;
        uniform float hingeFlexPx;
        uniform float blurSpread;
        uniform float darkening;
        uniform float hingeAxisY;
        uniform float hingeFromEnd;
        uniform float level;
        uniform float3 radiusStops;

        float levelWeight(float radius) {
            // Neighboring native Gaussian levels are blended by variance.
            // The first positive stop is <= 1 source pixel, so larger radii
            // contain no sharp delta that could turn text into long streaks.
            float r = radius * radius;
            float3 variance = radiusStops * radiusStops;
            if (level < 0.5) {
                return 1.0 - clamp(r / max(variance.z, 0.000001), 0.0, 1.0);
            }
            float rising = clamp((r - variance.x) /
                max(variance.y - variance.x, 0.000001), 0.0, 1.0);
            // The last level repeats its own radius as its upper stop.
            if (radiusStops.z <= radiusStops.y) return rising;
            float falling = 1.0 - clamp((r - variance.y) /
                max(variance.z - variance.y, 0.000001), 0.0, 1.0);
            return min(rising, falling);
        }

        half4 main(float2 p) {
            float axisExtent = mix(resolution.x, resolution.y, hingeAxisY);
            float acrossExtent = mix(resolution.y, resolution.x, hingeAxisY);
            float rawAxis = mix(p.x, p.y, hingeAxisY);
            float axis = mix(rawAxis, axisExtent - rawAxis, hingeFromEnd);
            float across = mix(p.y, p.x, hingeAxisY);

            // Only the unblurred branch contributes the fixed inner pane.
            if (coverSurface < 0.5 && axis >= axisExtent * 0.5) {
                return level < 0.5 ? content.eval(p) : half4(0.0);
            }

            float hinge = coverSurface > 0.5 ? 0.0 : axisExtent * 0.5;
            float distance = coverSurface > 0.5 ? axis : hinge - axis;
            // Match LiveFoldGeometry: the hinge joins the stationary pane with
            // an identity tangent, then smoothly reaches the optical tilt.
            float u = clamp(distance / hingeFlexPx, 0.0, 1.0);
            float bentDistance = distance < hingeFlexPx ?
                hingeFlexPx * u * u * u * (1.0 - 0.5 * u) : distance - hingeFlexPx * 0.5;
            float glassDistance = distance - (1.0 - foldCos) * bentDistance;
            float glassAxis = coverSurface > 0.5 ? glassDistance : hinge - glassDistance;
            float gap = max(0.0, bentDistance * foldSin);
            float depth = eyeDistancePx - gap;
            if (depth <= 0.001) {
                return level < 0.5 ? half4(0.0, 0.0, 0.0, 1.0) : half4(0.0);
            }
            // DuoLikeAnimation's separation-based frost. No inverse projection
            // gain: both radius and shadow resolve continuously at the hinge.
            float radius = min(maxBlurPx, blurSpread * gap);
            half weight = half(levelWeight(radius));
            if (weight <= 0.0) return half4(0.0);

            float eyeAxis = axisExtent * 0.5;
            float perspective = eyeDistancePx / depth;
            float hitAxis = eyeAxis + (glassAxis - eyeAxis) * perspective;
            float hitAcross = acrossExtent * 0.5 + (across - acrossExtent * 0.5) * perspective;
            float rawHit = mix(hitAxis, axisExtent - hitAxis, hingeFromEnd);
            float2 source = hingeAxisY > 0.5 ? float2(hitAcross, rawHit) : float2(rawHit, hitAcross);

            // Deliberate black falloff outside the fixed UI plane. CLAMP on the
            // native blur prevents unintended transparent edges in the source.
            float footprint = max(0.5, radius * 0.75);
            float2 coverage = smoothstep(float2(-footprint), float2(footprint), source) *
                (1.0 - smoothstep(resolution - footprint, resolution + footprint, source));
            half4 color = content.eval(clamp(source, float2(0.5), resolution - 0.5));
            float shade = max(1.0 - darkening * radius, 0.0);
            half attenuation = half(shade * coverage.x * coverage.y);
            return half4(color.rgb * attenuation, color.a) * weight;
        }
    """
}

/** GPU-only filter graph. No Bitmap, readback, capture worker, or cached home image. */
internal class LiveFoldEffects {
    private val shaders = Array(DuoFoldModel.MAX_BLUR_LEVEL_COUNT) { RuntimeShader(LiveFoldShader.SOURCE) }
    private var cachedBlurRadius = -1f
    private var radii = FloatArray(0)
    private var blurs = arrayOfNulls<RenderEffect>(0)

    fun create(geometry: LiveFoldGeometry): RenderEffect {
        if (cachedBlurRadius != geometry.maxBlurPx) {
            cachedBlurRadius = geometry.maxBlurPx
            radii = DuoFoldModel.blurLevels(cachedBlurRadius)
            blurs = Array(radii.size) { index -> if (index == 0) null else blur(radii[index]) }
        }
        var combined: RenderEffect? = null
        // The glass-to-UI gap, hence the frost radius, grows towards the edge.
        val reachableRadius = geometry.maxFrostRadiusPx
        radii.forEachIndexed { index, radius ->
            // A branch that is transparent everywhere must not run its native
            // blur pass, especially during the long, almost-clear fold tail.
            val lowerRadius = radii[(index - 1).coerceAtLeast(0)]
            if (index > 0 && reachableRadius <= lowerRadius) return@forEachIndexed
            val shader = shaders[index]
            shader.setFloatUniform("resolution", geometry.width.toFloat(), geometry.height.toFloat())
            shader.setFloatUniform("coverSurface", if (geometry.cover) 1f else 0f)
            shader.setFloatUniform("foldCos", geometry.foldCos)
            shader.setFloatUniform("foldSin", geometry.foldSin)
            shader.setFloatUniform("eyeDistancePx", geometry.eyeDistancePx)
            shader.setFloatUniform("maxBlurPx", geometry.maxBlurPx)
            shader.setFloatUniform("hingeFlexPx", geometry.hingeFlexPx)
            shader.setFloatUniform("blurSpread", DuoFoldModel.BLUR_SPREAD)
            shader.setFloatUniform("darkening", geometry.darkening)
            shader.setFloatUniform("hingeAxisY", if (geometry.axisY) 1f else 0f)
            shader.setFloatUniform("hingeFromEnd", if (geometry.hingeFromEnd) 1f else 0f)
            shader.setFloatUniform("level", index.toFloat())
            shader.setFloatUniform("radiusStops", lowerRadius, radius,
                radii[(index + 1).coerceAtMost(radii.lastIndex)])
            // RenderEffect snapshots the shader builder. Recreate the effect
            // after changing uniforms; reusing it would freeze the old angle.
            val projection = RenderEffect.createRuntimeShaderEffect(shader, "content")
            val branch = blurs[index]?.let { RenderEffect.createChainEffect(projection, it) }
                ?: projection
            combined = combined?.let { RenderEffect.createBlendModeEffect(it, branch, BlendMode.PLUS) }
                ?: branch
        }
        return requireNotNull(combined)
    }

    private fun blur(radius: Float): RenderEffect {
        val nativeRadius = DuoFoldModel.nativeBlurRadius(radius)
        return RenderEffect.createBlurEffect(nativeRadius, nativeRadius, Shader.TileMode.CLAMP)
    }
}
