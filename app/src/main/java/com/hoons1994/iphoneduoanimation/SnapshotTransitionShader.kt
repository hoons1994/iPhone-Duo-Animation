package com.hoons1994.iphoneduoanimation

/**
 * Reddit-style proof of concept: render two captured display snapshots as
 * shader inputs and interpolate them directly from the physical hinge angle.
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
        uniform float maxBlurPx;

        float2 aspectFillCoord(float2 p, float2 imageSize, float scale) {
            float2 uv = float2(
                p.x / max(resolution.x, 1.0),
                p.y / max(resolution.y, 1.0)
            );

            uv = (uv - float2(0.5)) / max(scale, 0.80) + float2(0.5);

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

        half4 sampleTransition(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // Keep both snapshots fully visible for as little time as possible.
            // The blur peak around the midpoint hides the geometry mismatch;
            // a wide cross-fade creates obvious doubled icons on real screens.
            float handoff = smoothstep(0.42, 0.58, t);

            // Geometry correction is intentionally modest. Large scale
            // differences read as a zoom rather than a display hand-off.
            float coverScale = mix(1.0, 0.985, handoff);
            float innerScale = mix(1.015, 1.0, handoff);

            float2 coverCoord = aspectFillCoord(p, coverSize, coverScale);
            float2 innerCoord = aspectFillCoord(p, innerSize, innerScale);

            half4 coverColor = coverSnapshot.eval(coverCoord);
            half4 innerColor = innerSnapshot.eval(innerCoord);
            return coverColor * half(1.0 - handoff) + innerColor * half(handoff);
        }

        half4 blur9(float2 p, float radius) {
            float r1 = radius * 0.42;
            float r2 = radius;
            float d = radius * 0.7071;

            half4 c = sampleTransition(p) * 4.0;
            c += sampleTransition(p + float2(r1, 0.0));
            c += sampleTransition(p - float2(r1, 0.0));
            c += sampleTransition(p + float2(0.0, r1));
            c += sampleTransition(p - float2(0.0, r1));
            c += sampleTransition(p + float2(d, d)) * 0.75;
            c += sampleTransition(p - float2(d, d)) * 0.75;
            c += sampleTransition(p + float2(d, -d)) * 0.75;
            c += sampleTransition(p - float2(d, -d)) * 0.75;
            c += sampleTransition(p + float2(r2, 0.0)) * 0.45;
            c += sampleTransition(p - float2(r2, 0.0)) * 0.45;
            return c / 11.9;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // Concentrate focus loss closer to the actual hand-off instead of
            // keeping the entire 0..1 fold range soft.
            float midpointDistance = abs(t - 0.5) * 2.0;
            float handoffPeak = 1.0 - smoothstep(0.0, 0.78, midpointDistance);
            handoffPeak = pow(max(handoffPeak, 0.0), 0.72);

            // Inner display: hinge is in the center. Cover display: on a
            // book-style Galaxy Fold in portrait, the hinge is the left edge.
            float innerHingeDistance = abs(p.x - resolution.x * 0.5) /
                max(resolution.x * 0.5, 1.0);
            float coverHingeDistance = p.x / max(resolution.x, 1.0);
            float hingeDistance = coverSurface > 0.5
                ? clamp(coverHingeDistance, 0.0, 1.0)
                : clamp(innerHingeDistance, 0.0, 1.0);

            // Progressive focus: nearly sharp at the hinge and increasingly
            // blurred toward the physical outer edge(s).
            float spatial = smoothstep(0.06, 0.98, hingeDistance);
            spatial = pow(spatial, 0.76);

            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.96, 1.04, travel);

            float radius = maxBlurPx * handoffPeak * spatial * directional;
            return blur9(p, radius);
        }
    """
}
