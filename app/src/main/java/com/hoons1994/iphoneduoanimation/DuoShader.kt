package com.hoons1994.iphoneduoanimation

/**
 * Progressive fold/unfold focus effect driven by physical hinge progress.
 *
 * The active surface matters: the inner display should lose focus as the
 * device closes, while the cover display should lose focus as the device
 * opens. This gives the two surfaces complementary transition curves instead
 * of a bell curve that barely changes around 90 degrees.
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
            float r1 = radius * 0.28;
            float r2 = radius * 0.62;
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
            c += content.eval(p + x1) * 2.0;
            c += content.eval(p - x1) * 2.0;
            c += content.eval(p + y1) * 2.0;
            c += content.eval(p - y1) * 2.0;
            c += content.eval(p + x2);
            c += content.eval(p - x2);
            c += content.eval(p + y2);
            c += content.eval(p - y2);
            c += content.eval(p + x3) * 0.65;
            c += content.eval(p - x3) * 0.65;
            c += content.eval(p + y3) * 0.65;
            c += content.eval(p - y3) * 0.65;
            c += content.eval(p + d1);
            c += content.eval(p - d1);
            c += content.eval(p + d2);
            c += content.eval(p - d2);
            return c / 22.6;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // Complementary monotonic curves:
            // inner: open (180°) = sharp, closing = progressively blurred
            // cover: closed (0°) = sharp, opening = progressively blurred
            float transitionAmount = coverSurface > 0.5 ? t : (1.0 - t);

            // Slight ease keeps the endpoint crisp while preserving a large
            // visible range during hand-driven folding.
            transitionAmount = pow(clamp(transitionAmount, 0.0, 1.0), 0.82);

            float halfWidth = max(resolution.x * 0.5, 1.0);
            float hingeDistance = abs(p.x - halfWidth) / halfWidth;
            float edgeProgress = smoothstep(0.02, 0.96, hingeDistance);
            float spatial = mix(0.22, 1.0, edgeProgress);

            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.88, 1.12, travel);
            float radius = maxBlurPx * transitionAmount * spatial * directional;

            float scale = 1.0 - (scaleDip * transitionAmount);
            float2 center = resolution * 0.5;
            float2 q = (p - center) / max(scale, 0.9) + center;

            half4 blurred = blur17(q, radius);
            float dim = 1.0 - (0.08 * transitionAmount * spatial);
            return half4(blurred.rgb * half(dim), blurred.a);
        }
    """
}
