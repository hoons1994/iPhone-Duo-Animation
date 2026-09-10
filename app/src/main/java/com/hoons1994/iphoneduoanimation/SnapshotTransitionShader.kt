package com.hoons1994.iphoneduoanimation

/**
 * Surface-handoff renderer for the Galaxy Fold POC.
 *
 * Unlike the earlier cross-fade prototype, the active physical display owns
 * one snapshot at a time. One UI performs the real cover/inner display handoff;
 * this shader hides that switch with a focus peak and complementary geometry.
 */
object SnapshotTransitionShader {
    const val SOURCE = """
        uniform shader coverSnapshot;
        uniform shader innerSnapshot;
        uniform float2 coverSize;
        uniform float2 innerSize;
        uniform float2 resolution;
        uniform float progress;
        uniform float opening;
        uniform float coverSurface;
        uniform float handoffProgress;
        uniform float focusWindow;
        uniform float maxBlurPx;

        float smoother(float edge0, float edge1, float x) {
            float t = clamp((x - edge0) / max(edge1 - edge0, 0.0001), 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        float2 aspectFillCoord(float2 p, float2 imageSize, float scale) {
            float2 uv = float2(
                p.x / max(resolution.x, 1.0),
                p.y / max(resolution.y, 1.0)
            );
            uv = (uv - float2(0.5)) / max(scale, 0.92) + float2(0.5);

            float outputAspect = resolution.x / max(resolution.y, 1.0);
            float imageAspect = imageSize.x / max(imageSize.y, 1.0);

            if (imageAspect > outputAspect) {
                float visibleX = outputAspect / imageAspect;
                uv.x = (uv.x - 0.5) * visibleX + 0.5;
            } else {
                float visibleY = imageAspect / outputAspect;
                uv.y = (uv.y - 0.5) * visibleY + 0.5;
            }
            return uv * imageSize;
        }

        half4 sampleSurface(float2 p) {
            float t = clamp(progress, 0.0, 1.0);
            float h = clamp(handoffProgress, 0.2, 0.8);

            // Cover approaches the handoff with a tiny contraction. The inner
            // display appears slightly expanded at the handoff and settles as
            // the device reaches the fully open endpoint. Reversing the hinge
            // naturally reverses the geometry.
            float coverApproach = smoother(h - 0.28, h, t);
            float innerSettle = smoother(h, h + 0.30, t);
            float coverScale = mix(1.0, 0.986, coverApproach);
            float innerScale = mix(1.014, 1.0, innerSettle);

            float2 coverCoord = aspectFillCoord(p, coverSize, coverScale);
            float2 innerCoord = aspectFillCoord(p, innerSize, innerScale);

            return coverSurface > 0.5
                ? coverSnapshot.eval(coverCoord)
                : innerSnapshot.eval(innerCoord);
        }

        half4 blur9(float2 p, float radius) {
            // Small-radius weighted kernel: enough to hide the display switch
            // without producing the duplicated-icon trails seen in v6/v7.
            float r1 = radius * 0.38;
            float r2 = radius * 0.78;
            float d = radius * 0.54;

            half4 c = sampleSurface(p) * 5.0;
            c += sampleSurface(p + float2(r1, 0.0)) * 1.2;
            c += sampleSurface(p - float2(r1, 0.0)) * 1.2;
            c += sampleSurface(p + float2(0.0, r1)) * 1.2;
            c += sampleSurface(p - float2(0.0, r1)) * 1.2;
            c += sampleSurface(p + float2(d, d)) * 0.55;
            c += sampleSurface(p - float2(d, d)) * 0.55;
            c += sampleSurface(p + float2(d, -d)) * 0.55;
            c += sampleSurface(p - float2(d, -d)) * 0.55;
            c += sampleSurface(p + float2(r2, 0.0)) * 0.35;
            c += sampleSurface(p - float2(r2, 0.0)) * 0.35;
            return c / 12.6;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);
            float h = clamp(handoffProgress, 0.2, 0.8);
            float window = max(focusWindow, 0.05);

            // Focus loss peaks exactly where One UI changes active displays,
            // then resolves in either direction. This peak can be calibrated
            // from the actual angle at which the View changes cover/inner size.
            float distanceFromHandoff = abs(t - h);
            float focus = 1.0 - smoother(0.0, window, distanceFromHandoff);

            // Cover hinge is on the long inner edge; inner display hinge is the
            // vertical center line. Keep the hinge region relatively sharp and
            // increase blur toward the outer edge(s), matching the reference cue.
            float innerHingeDistance = abs(p.x - resolution.x * 0.5) /
                max(resolution.x * 0.5, 1.0);
            float coverHingeDistance = p.x / max(resolution.x, 1.0);
            float hingeDistance = coverSurface > 0.5
                ? clamp(coverHingeDistance, 0.0, 1.0)
                : clamp(innerHingeDistance, 0.0, 1.0);

            float spatial = pow(smoother(0.03, 0.98, hingeDistance), 0.72);
            spatial = mix(0.10, 1.0, spatial);

            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.97, 1.03, travel);

            float radius = maxBlurPx * focus * spatial * directional;
            half4 color = blur9(p, radius);
            float dim = 1.0 - (0.035 * focus * spatial);
            return half4(color.rgb * half(dim), color.a);
        }
    """
}
