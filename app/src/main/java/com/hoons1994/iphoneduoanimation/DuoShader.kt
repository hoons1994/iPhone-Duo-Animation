package com.hoons1994.iphoneduoanimation

/**
 * Progressive fold/unfold focus effect driven by physical hinge progress.
 *
 * This revision intentionally makes the effect more visible on-device so we
 * can calibrate it from real foldable testing before dialing it back toward
 * the final reference look.
 */
object DuoShader {
    const val SOURCE = """
        uniform shader content;
        uniform float2 resolution;
        uniform float progress;
        uniform float opening;
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

            // Broaden the middle of the curve so the effect is visible during
            // a real hand-driven fold instead of only at exactly 90 degrees.
            float rawPeak = max(sin(t * 3.14159265), 0.0);
            float transitionPeak = pow(rawPeak, 0.68);

            float halfWidth = max(resolution.x * 0.5, 1.0);
            float hingeDistance = abs(p.x - halfWidth) / halfWidth;

            // Keep the progressive character, but retain 22% of the effect at
            // the hinge for this diagnostic build so motion is unmistakable.
            float edgeProgress = smoothstep(0.02, 0.96, hingeDistance);
            float spatial = mix(0.22, 1.0, edgeProgress);

            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.88, 1.12, travel);
            float radius = maxBlurPx * transitionPeak * spatial * directional;

            float scale = 1.0 - (scaleDip * transitionPeak);
            float2 center = resolution * 0.5;
            float2 q = (p - center) / max(scale, 0.9) + center;

            half4 blurred = blur17(q, radius);

            // Temporary, subtle focus-loss cue. This will be tuned down after
            // we verify the fold path on real hardware.
            float dim = 1.0 - (0.08 * transitionPeak * spatial);
            return half4(blurred.rgb * half(dim), blurred.a);
        }
    """
}
