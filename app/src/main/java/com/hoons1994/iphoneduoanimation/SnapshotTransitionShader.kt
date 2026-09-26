package com.hoons1994.iphoneduoanimation

/**
 * Hinge-driven frosted-glass effect used by the reference animation.
 *
 * The moving pane follows the pixel-space eye/glass/plane model used by
 * Atomicx7/Duo-animation and duo-open. The physical hinge range is compressed
 * into a bounded virtual glass tilt so the view shifts gently without the
 * 90-degree collapse that stretches launcher widgets across the display.
 */
object SnapshotTransitionShader {
    private fun mipSource(prefix: String): String = buildString {
        append("uniform shader ${prefix}Snapshot;\n")
        for (level in 1..7) append("uniform shader ${prefix}Mip$level;\n")
        append("half3 ${prefix}Level(float2 p, float lod) {\n")
        append("if (lod <= 0.0) return ${prefix}Snapshot.eval(p).rgb;\n")
        for (level in 0..6) {
            val low = if (level == 0) "${prefix}Snapshot" else "${prefix}Mip$level"
            val high = "${prefix}Mip${level + 1}"
            append("if (lod < ${level + 1}.0) return mix($low.eval(p).rgb, " +
                "$high.eval(p).rgb, half(lod - $level.0));\n")
        }
        append("return ${prefix}Mip7.eval(p).rgb;\n}\n")
    }

    val SOURCE = mipSource("cover") + mipSource("inner") + """
        uniform float2 coverSize;
        uniform float2 innerSize;
        uniform float2 resolution;
        uniform float coverSurface;
        uniform float motionAmount;
        uniform float foldCos;
        uniform float foldSin;
        uniform float maxBlurPx;
        uniform float eyeDistancePx;
        uniform float blurSpread;
        uniform float darkening;
        uniform float hingeAxisY;
        uniform float coverHingeFromEnd;
        uniform float overlayMode;
        uniform float4 overlayContentBounds;

        float2 outputUv(float2 p) {
            return p / max(resolution, float2(1.0));
        }

        float2 aspectFillUv(float2 uv, float2 imageSize) {
            float outputAspect = resolution.x / max(resolution.y, 1.0);
            float imageAspect = imageSize.x / max(imageSize.y, 1.0);
            if (imageAspect > outputAspect) {
                float visibleX = outputAspect / imageAspect;
                uv.x = (uv.x - 0.5) * visibleX + 0.5;
            } else {
                float visibleY = imageAspect / outputAspect;
                uv.y = (uv.y - 0.5) * visibleY + 0.5;
            }
            return uv;
        }

        half3 sampleScene(float2 uv, float lod) {
            if (coverSurface > 0.5) {
                return coverLevel(aspectFillUv(uv, coverSize) * coverSize, lod);
            }
            return innerLevel(aspectFillUv(uv, innerSize) * innerSize, lod);
        }

        half3 coveredSample(float2 uv, float2 footprint, float lod) {
            float2 coverage = smoothstep(-footprint, footprint, uv) *
                (1.0 - smoothstep(float2(1.0) - footprint, float2(1.0) + footprint, uv));
            return sampleScene(clamp(uv, float2(0.0), float2(1.0)), lod) *
                half(coverage.x * coverage.y);
        }

        half4 main(float2 p) {
            if (overlayMode > 0.5 && (p.x < overlayContentBounds.x ||
                p.y < overlayContentBounds.y || p.x >= overlayContentBounds.z ||
                p.y >= overlayContentBounds.w)) return half4(0.0);
            float2 uv = outputUv(p);
            float rawAxis = mix(uv.x, uv.y, hingeAxisY);
            float axis = mix(rawAxis, 1.0 - rawAxis, coverHingeFromEnd);
            float across = mix(uv.y, uv.x, hingeAxisY);

            // The right/fixed half of the inner display never passes through
            // frosted glass in the reference. Sample it without additional blur.
            if (coverSurface < 0.5 && axis >= 0.5) {
                if (overlayMode > 0.5) return half4(0.0);
                return half4(sampleScene(uv, 0.0), 1.0);
            }

            // These values are calculated once per frame on the CPU. Keeping
            // trigonometry and easing out of millions of fragment invocations is
            // essential on a high-resolution foldable display.
            float motion = clamp(motionAmount, 0.0, 1.0);

            if (motion < 0.0001) {
                if (overlayMode > 0.5) return half4(0.0);
                return half4(sampleScene(uv, 0.0), 1.0);
            }

            // duo-open's ray-plane projection in logical display pixels. The
            // inner pane is hinged at the center; the cover is one pane hinged
            // at its spine edge and viewed from the display center.
            float axisExtent = mix(resolution.x, resolution.y, hingeAxisY);
            float acrossExtent = mix(resolution.y, resolution.x, hingeAxisY);
            float axisPx = axis * axisExtent;
            float acrossPx = across * acrossExtent;
            float hingePx = coverSurface > 0.5 ? 0.0 : axisExtent * 0.5;
            float distanceFromHinge = coverSurface > 0.5
                ? axisPx
                : hingePx - axisPx;
            float glassAxis = coverSurface > 0.5
                ? distanceFromHinge * foldCos
                : hingePx - distanceFromHinge * foldCos;
            float gap = max(0.0, distanceFromHinge * foldSin);
            float eyeAxis = coverSurface > 0.5 ? axisExtent * 0.5 : hingePx;
            float eyeAcross = acrossExtent * 0.5;
            float depth = eyeDistancePx - gap;
            if (depth <= 0.001) return half4(0.0, 0.0, 0.0, 1.0);
            float perspective = eyeDistancePx / depth;
            float hitAxis = eyeAxis + (glassAxis - eyeAxis) * perspective;
            float hitAcross = eyeAcross + (acrossPx - eyeAcross) * perspective;
            float rawHitAxis = mix(hitAxis, axisExtent - hitAxis, coverHingeFromEnd);
            float2 sourceUv = hingeAxisY > 0.5
                ? float2(hitAcross / max(resolution.x, 1.0),
                    rawHitAxis / max(resolution.y, 1.0))
                : float2(rawHitAxis / max(resolution.x, 1.0),
                    hitAcross / max(resolution.y, 1.0));

            // Gap-proportional frost from duo-open, capped at the 72-source-px
            // radius used by duo-fold-live so the cached mip chain stays bounded.
            float radius = min(maxBlurPx, blurSpread * gap);
            float2 footprint = max(
                float2(0.5) / max(resolution, float2(1.0)),
                float2(radius * 0.75) / max(resolution, float2(1.0))
            );

            float2 sceneSize = coverSurface > 0.5 ? coverSize : innerSize;
            float pixelsPerOutputPixel = min(sceneSize.x / resolution.x, sceneSize.y / resolution.y);
            float lod = clamp(log2(max(1.0, radius * pixelsPerOutputPixel)), 0.0, 7.0);
            half3 color = half3(0.0);
            if (radius < 0.5) {
                color = coveredSample(sourceUv, footprint, 0.0);
            } else {
                // Exact 5x5 binomial footprint used by the current Duo ports.
                // Half-size output plus cached mip levels keep the 25 taps viable.
                for (int y = -2; y <= 2; y++) {
                    for (int x = -2; x <= 2; x++) {
                        float wx = x == 0 ? 6.0 : (abs(float(x)) == 1.0 ? 4.0 : 1.0);
                        float wy = y == 0 ? 6.0 : (abs(float(y)) == 1.0 ? 4.0 : 1.0);
                        float2 sampleUv = sourceUv +
                            float2(float(x), float(y)) * radius /
                            max(resolution, float2(1.0));
                        color += coveredSample(sampleUv, footprint, lod) *
                            half(wx * wy / 256.0);
                    }
                }
            }

            float attenuation = max(1.0 - darkening * radius, 0.0);
            // Resolve back to the actual launcher at the endpoint. Premultiplied
            // alpha also leaves the fixed panel and system bars genuinely live.
            half alpha = overlayMode > 0.5 ? half(smoothstep(0.0, 0.02, motion)) : half(1.0);
            return half4(color * half(attenuation) * alpha, alpha);
        }
    """
}
