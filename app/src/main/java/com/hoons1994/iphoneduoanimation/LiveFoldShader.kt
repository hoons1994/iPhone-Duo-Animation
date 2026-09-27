package com.hoons1994.iphoneduoanimation

import android.graphics.RenderEffect
import android.graphics.RuntimeShader

/** One live pass: ray projection and a finite, spatially stable Vogel-disk scatter kernel. */
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

        // Use the actual footprint at the edge, rather than clamping bright
        // pixels into the missing part of the kernel. Keep the full denominator:
        // light outside the UI plane is black, just as in DuoFold.metal.
        half3 samplePlane(float2 p) {
            if (p.x < 0.0 || p.y < 0.0 || p.x >= resolution.x || p.y >= resolution.y) {
                return half3(0.0);
            }
            // The input is premultiplied. Dropping alpha composites over black.
            return content.eval(p).rgb;
        }

        half4 main(float2 p) {
            float axisExtent = mix(resolution.x, resolution.y, hingeAxisY);
            float acrossExtent = mix(resolution.y, resolution.x, hingeAxisY);
            float rawAxis = mix(p.x, p.y, hingeAxisY);
            float axis = mix(rawAxis, axisExtent - rawAxis, hingeFromEnd);
            float across = mix(p.y, p.x, hingeAxisY);

            // The stationary pane needs neither projection nor scatter taps.
            if (coverSurface < 0.5 && axis >= axisExtent * 0.5) {
                return content.eval(p);
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
                return half4(0.0, 0.0, 0.0, 1.0);
            }
            // DuoLikeAnimation's separation-based frost. No inverse projection
            // gain: both radius and shadow resolve continuously at the hinge.
            float radius = min(maxBlurPx, blurSpread * gap);
            float shade = max(1.0 - darkening * radius, 0.0);
            if (shade <= 0.0) return half4(0.0, 0.0, 0.0, 1.0);

            float eyeAxis = axisExtent * 0.5;
            float perspective = eyeDistancePx / depth;
            float hitAxis = eyeAxis + (glassAxis - eyeAxis) * perspective;
            float hitAcross = acrossExtent * 0.5 + (across - acrossExtent * 0.5) * perspective;
            float rawHit = mix(hitAxis, axisExtent - hitAxis, hingeFromEnd);
            float2 source = hingeAxisY > 0.5 ? float2(hitAcross, rawHit) : float2(rawHit, hitAcross);

            if (source.x < -radius || source.y < -radius ||
                source.x > resolution.x + radius || source.y > resolution.y + radius) {
                return half4(0.0, 0.0, 0.0, 1.0);
            }
            if (radius <= 0.01) return half4(samplePlane(source) * half(shade), 1.0);

            // A radius is a disk radius, not Gaussian sigma. The old graph used
            // sigma = radius, giving four times the disk's per-axis variance.
            // Keep all 32 locations for every nontrivial radius. Changing the
            // tap count relocates the entire kernel as the hinge moves.
            float rotation = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453) * 6.28318530718;
            float2 direction = float2(cos(rotation), sin(rotation));
            const float2 goldenStep = float2(-0.737368878, 0.675490294);
            float3 sum = float3(0.0);
            for (int i = 0; i < 32; i++) {
                float fi = float(i);
                float2 offset = radius * sqrt((fi + 0.5) / 32.0) * direction;
                sum += float3(samplePlane(source + offset));
                direction = float2(
                    direction.x * goldenStep.x - direction.y * goldenStep.y,
                    direction.x * goldenStep.y + direction.y * goldenStep.x);
            }
            half3 scattered = half3(sum * (1.0 / 32.0));
            // Resolve the subpixel tail continuously instead of switching from
            // a sharp sample to a different kernel at the original 0.5px cutoff.
            if (radius < 0.5) {
                scattered = mix(samplePlane(source), scattered, half(smoothstep(0.01, 0.5, radius)));
            }
            return half4(scattered * half(shade), 1.0);
        }
    """
}

/** One GPU effect per pose; source content remains live without any bitmap capture. */
internal class LiveFoldEffects {
    private val shader = RuntimeShader(LiveFoldShader.SOURCE)
    private var configuredGeometry: LiveFoldGeometry? = null

    fun create(geometry: LiveFoldGeometry): RenderEffect {
        val previous = configuredGeometry
        if (previous == null || previous.width != geometry.width || previous.height != geometry.height ||
            previous.cover != geometry.cover || previous.rotation != geometry.rotation ||
            previous.pixelsPerMm != geometry.pixelsPerMm) {
            shader.setFloatUniform("resolution", geometry.width.toFloat(), geometry.height.toFloat())
            shader.setFloatUniform("coverSurface", if (geometry.cover) 1f else 0f)
            shader.setFloatUniform("eyeDistancePx", geometry.eyeDistancePx)
            shader.setFloatUniform("maxBlurPx", geometry.maxBlurPx)
            shader.setFloatUniform("hingeFlexPx", geometry.hingeFlexPx)
            shader.setFloatUniform("blurSpread", DuoFoldModel.BLUR_SPREAD)
            shader.setFloatUniform("darkening", geometry.darkening)
            shader.setFloatUniform("hingeAxisY", if (geometry.axisY) 1f else 0f)
            shader.setFloatUniform("hingeFromEnd", if (geometry.hingeFromEnd) 1f else 0f)
        }
        shader.setFloatUniform("foldCos", geometry.foldCos)
        shader.setFloatUniform("foldSin", geometry.foldSin)
        configuredGeometry = geometry
        // RenderEffect snapshots uniforms. Recreate this single effect after
        // updating the pose; retaining the earlier effect would freeze its angle.
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }
}
