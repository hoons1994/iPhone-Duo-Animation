package com.hoons1994.iphoneduoanimation

/**
 * Surface-handoff renderer for the Galaxy Fold POC.
 *
 * The active physical display owns one snapshot at a time. One UI performs the
 * real cover/inner display handoff; this shader hides that switch with a focus
 * peak, a short see-through source bridge, and complementary geometry on the
 * outgoing/incoming surfaces.
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
        uniform float hingeAxisY;
        uniform float coverHingeFromEnd;

        float smoother(float edge0, float edge1, float x) {
            float t = clamp((x - edge0) / max(edge1 - edge0, 0.0001), 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        float sourceBridge(float t, float h) {
            float bridgeWindow = 0.10;
            float latch = 0.035;
            float coverBlend = 0.5 * smoother(h - bridgeWindow, h - latch, t);
            float innerBlend = 0.5 + 0.5 * smoother(h + latch, h + bridgeWindow, t);
            return coverSurface > 0.5 ? coverBlend : innerBlend;
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

        float coverScaleAt(float t, float h) {
            float coverApproach = smoother(h - 0.24, h, t);
            return mix(1.0, 0.992, coverApproach);
        }

        float innerScaleAt(float t, float h) {
            float innerSettle = smoother(h, h + 0.24, t);
            return mix(0.992, 1.0, innerSettle);
        }

        half4 sampleCover(float2 p, float t, float h) {
            float2 coord = aspectFillCoord(p, coverSize, coverScaleAt(t, h));
            return coverSnapshot.eval(coord);
        }

        half4 sampleInner(float2 p, float t, float h) {
            float2 coord = aspectFillCoord(p, innerSize, innerScaleAt(t, h));
            return innerSnapshot.eval(coord);
        }

        half4 sampleSurface(float2 p) {
            float t = clamp(progress, 0.0, 1.0);
            float h = clamp(handoffProgress, 0.2, 0.8);
            float bridge = sourceBridge(t, h);

            // Keep normal use cheap: outside the short bridge, read only the
            // snapshot that can actually contribute to the output pixel.
            if (bridge <= 0.001) {
                return sampleCover(p, t, h);
            }
            if (bridge >= 0.999) {
                return sampleInner(p, t, h);
            }

            half4 coverColor = sampleCover(p, t, h);
            half4 innerColor = sampleInner(p, t, h);
            return mix(coverColor, innerColor, half(bridge));
        }

        half4 blur9(float2 p, float radius) {
            // Most of normal phone use is outside the fold handoff. Avoid nine
            // redundant texture reads when the requested blur is effectively zero.
            if (radius < 0.75) {
                return sampleSurface(p);
            }

            // Nine-tap compact kernel. The early prototypes used wider extra
            // samples that looked like duplicated icons and cost more GPU work.
            float r1 = radius * 0.40;
            float d = radius * 0.52;

            half4 c = sampleSurface(p) * 4.8;
            c += sampleSurface(p + float2(r1, 0.0)) * 1.10;
            c += sampleSurface(p - float2(r1, 0.0)) * 1.10;
            c += sampleSurface(p + float2(0.0, r1)) * 1.10;
            c += sampleSurface(p - float2(0.0, r1)) * 1.10;
            c += sampleSurface(p + float2(d, d)) * 0.55;
            c += sampleSurface(p - float2(d, d)) * 0.55;
            c += sampleSurface(p + float2(d, -d)) * 0.55;
            c += sampleSurface(p - float2(d, -d)) * 0.55;
            return c / 11.40;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);
            float h = clamp(handoffProgress, 0.2, 0.8);
            float window = max(focusWindow, 0.05);

            // Surface-aware focus envelope. If the outgoing display remains
            // active past the expected handoff, keep it maximally masked rather
            // than letting the blur resolve before One UI actually switches.
            float coverFocus = smoother(h - window, h, t);
            float innerFocus = 1.0 - smoother(h, h + window, t);
            float focus = coverSurface > 0.5 ? coverFocus : innerFocus;

            // Track screen rotation explicitly. The inner hinge sits at the
            // center line; the cover hinge sits at one physical edge. Without
            // this, rotating the Fold 90/180 degrees puts the strongest blur on
            // the wrong axis/edge and immediately breaks the depth illusion.
            float crossPosition = hingeAxisY > 0.5 ? p.y : p.x;
            float crossSize = hingeAxisY > 0.5 ? resolution.y : resolution.x;
            float normalizedCross = clamp(crossPosition / max(crossSize, 1.0), 0.0, 1.0);

            float innerHingeDistance = abs(normalizedCross - 0.5) * 2.0;
            float coverHingeDistance = coverHingeFromEnd > 0.5
                ? (1.0 - normalizedCross)
                : normalizedCross;
            float bridge = sourceBridge(t, h);
            float hingeDistance = mix(
                clamp(coverHingeDistance, 0.0, 1.0),
                clamp(innerHingeDistance, 0.0, 1.0),
                bridge
            );

            float spatial = pow(smoother(0.04, 0.98, hingeDistance), 0.76);
            spatial = mix(0.08, 1.0, spatial);

            float travelPosition = hingeAxisY > 0.5
                ? (p.y / max(resolution.y, 1.0))
                : (p.x / max(resolution.x, 1.0));
            float travel = opening > 0.5 ? travelPosition : (1.0 - travelPosition);
            float directional = mix(0.98, 1.02, travel);

            float radius = maxBlurPx * focus * spatial * directional;
            half4 color = blur9(p, radius);

            // Tiny luminance dip makes the focus handoff read as depth without
            // turning the transition into an obvious fade-to-black.
            float dim = 1.0 - (0.022 * focus * spatial);
            return half4(color.rgb * half(dim), color.a);
        }
    """
}
