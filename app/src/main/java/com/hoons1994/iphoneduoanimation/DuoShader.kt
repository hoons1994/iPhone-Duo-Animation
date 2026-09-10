package com.hoons1994.iphoneduoanimation

/**
 * Progressive fold/unfold focus effect driven by physical hinge progress.
 *
 * v5 keeps the physical hinge sharp while making the spatial falloff visible
 * across the useful fold range. Unlike the diagnostic v3 renderer there is no
 * global blur; unlike v4, the fold amount is not squared a second time.
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
            float r1 = radius * 0.22;
            float r2 = radius * 0.50;
            float r3 = radius;

            float2 x1 = float2(r1, 0.0);
            float2 y1 = float2(0.0, r1);
            float2 x2 = float2(r2, 0.0);
            float2 y2 = float2(0.0, r2);
            float2 x3 = float2(r3, 0.0);
            float2 y3 = float2(0.0, r3);
            float2 d1 = float2(r2 * 0.7071, r2 * 0.7071);
            float2 d2 = float2(r2 * 0.7071, -r2 * 0.7071);

            half4 c = content.eval(p) * 6.0;
            c += content.eval(p + x1) * 2.2;
            c += content.eval(p - x1) * 2.2;
            c += content.eval(p + y1) * 2.2;
            c += content.eval(p - y1) * 2.2;
            c += content.eval(p + x2) * 1.1;
            c += content.eval(p - x2) * 1.1;
            c += content.eval(p + y2) * 1.1;
            c += content.eval(p - y2) * 1.1;
            c += content.eval(p + x3) * 0.55;
            c += content.eval(p - x3) * 0.55;
            c += content.eval(p + y3) * 0.55;
            c += content.eval(p - y3) * 0.55;
            c += content.eval(p + d1) * 0.9;
            c += content.eval(p - d1) * 0.9;
            c += content.eval(p + d2) * 0.9;
            c += content.eval(p - d2) * 0.9;
            return c / 23.8;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // Complementary hand-off curves for the two active surfaces.
            float amount = coverSurface > 0.5 ? t : (1.0 - t);
            amount = pow(clamp(amount, 0.0, 1.0), 0.82);

            // 0 at the physical hinge, 1 at either outer edge.
            float halfWidth = max(resolution.x * 0.5, 1.0);
            float hingeDistance = clamp(abs(p.x - halfWidth) / halfWidth, 0.0, 1.0);

            // The loss-of-focus front moves from the outer edge toward the hinge.
            // It is intentionally broad: at ~50% fold, the outer half of each
            // panel is already visibly softer while the hinge remains crisp.
            float frontCenter = mix(1.02, 0.08, amount);
            float front = smoothstep(frontCenter - 0.20, frontCenter + 0.20, hingeDistance);

            // Smooth spatial ramp. This is always exactly zero at the hinge,
            // but no longer collapses to near-zero through most of the panel.
            float radial = pow(hingeDistance, 0.58);
            float spatial = radial * mix(0.42, 1.0, front);

            // Opening/closing direction only adds a slight asymmetric texture.
            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.96, 1.04, travel);

            // Do NOT square amount again. v4 did that here, causing a 48% UI
            // amount to become only ~23% before the spatial mask was applied.
            float blurDrive = pow(amount, 1.08);
            float radius = maxBlurPx * blurDrive * spatial * directional;

            // Tiny focus breathing, kept secondary to the spatial blur.
            float scale = 1.0 - (scaleDip * amount);
            float2 center = resolution * 0.5;
            float2 q = (p - center) / max(scale, 0.985) + center;

            half4 blurred = blur17(q, radius);

            // A very small luminance cue helps the moving front read without
            // turning the effect into a generic full-screen fade.
            float dim = 1.0 - (0.032 * amount * spatial);
            return half4(blurred.rgb * half(dim), blurred.a);
        }
    """
}
