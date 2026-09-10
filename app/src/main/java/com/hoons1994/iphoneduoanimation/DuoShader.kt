package com.hoons1994.iphoneduoanimation

/**
 * Progressive fold/unfold focus effect driven by physical hinge progress.
 *
 * v4 uses a moving focus front instead of a uniform screen blur. The hinge
 * stays sharp longest and defocus grows toward the outer edges, then advances
 * inward as the active display approaches its hand-off state.
 */
object DuoShader {
    const val SOURCE = """
        uniform shader content;
        uniform float2 resolution;
        uniform float progress;
        uniform float opening;
        uniform float coverSurface;
        uniform float maxBlurPx;
        uniform float scaleDip;

        half4 blur17(float2 p, float radius) {
            float r1 = radius * 0.24;
            float r2 = radius * 0.52;
            float r3 = radius;

            float2 x1 = float2(r1, 0.0);
            float2 y1 = float2(0.0, r1);
            float2 x2 = float2(r2, 0.0);
            float2 y2 = float2(0.0, r2);
            float2 x3 = float2(r3, 0.0);
            float2 y3 = float2(0.0, r3);
            float2 d1 = float2(r2 * 0.7071, r2 * 0.7071);
            float2 d2 = float2(r2 * 0.7071, -r2 * 0.7071);

            half4 c = content.eval(p) * 7.0;
            c += content.eval(p + x1) * 2.0;
            c += content.eval(p - x1) * 2.0;
            c += content.eval(p + y1) * 2.0;
            c += content.eval(p - y1) * 2.0;
            c += content.eval(p + x2);
            c += content.eval(p - x2);
            c += content.eval(p + y2);
            c += content.eval(p - y2);
            c += content.eval(p + x3) * 0.45;
            c += content.eval(p - x3) * 0.45;
            c += content.eval(p + y3) * 0.45;
            c += content.eval(p - y3) * 0.45;
            c += content.eval(p + d1) * 0.8;
            c += content.eval(p - d1) * 0.8;
            c += content.eval(p + d2) * 0.8;
            c += content.eval(p - d2) * 0.8;
            return c / 23.0;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // The active displays use complementary hand-off curves.
            float amount = coverSurface > 0.5 ? t : (1.0 - t);
            amount = pow(clamp(amount, 0.0, 1.0), 0.90);

            // 0 at the physical hinge, 1 at either outer edge.
            float halfWidth = max(resolution.x * 0.5, 1.0);
            float hingeDistance = clamp(abs(p.x - halfWidth) / halfWidth, 0.0, 1.0);

            // A focus-loss front begins at the outer edge and travels inward.
            // At low amount only the far edge is affected. Near hand-off the
            // front reaches close to the hinge, but the hinge remains sharp.
            float frontCenter = 0.96 - (amount * 0.82);
            float front = smoothstep(frontCenter - 0.13, frontCenter + 0.13, hingeDistance);

            // Continuous edge falloff prevents a visible hard band boundary.
            float edgeFalloff = pow(hingeDistance, 0.72);
            float spatial = edgeFalloff * mix(0.18, 1.0, front);

            // Direction is deliberately subtle; the spatial front is the main cue.
            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.97, 1.03, travel);

            float blurDrive = amount * amount;
            float radius = maxBlurPx * blurDrive * spatial * directional;

            // Keep scale/focus breathing barely perceptible. Diagnostic builds
            // previously exaggerated this and read as a generic zoom blur.
            float scale = 1.0 - (scaleDip * amount);
            float2 center = resolution * 0.5;
            float2 q = (p - center) / max(scale, 0.97) + center;

            half4 blurred = blur17(q, radius);
            float dim = 1.0 - (0.025 * amount * spatial);
            return half4(blurred.rgb * half(dim), blurred.a);
        }
    """
}
