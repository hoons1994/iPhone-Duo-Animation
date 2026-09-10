package com.hoons1994.iphoneduoanimation

/**
 * First-pass approximation of the iPhone Duo fold transition.
 *
 * The key idea is that blur is spatially progressive: it is weakest at the
 * hinge and strongest toward the outer edges, while both endpoints remain
 * sharp. Physical hinge progress drives the shader directly.
 */
object DuoShader {
    const val SOURCE = """
        uniform shader content;
        uniform float2 resolution;
        uniform float progress;
        uniform float opening;
        uniform float maxBlurPx;
        uniform float scaleDip;

        half4 blur9(float2 p, float radius) {
            float2 x = float2(radius, 0.0);
            float2 y = float2(0.0, radius);
            float2 d1 = float2(radius * 0.7071, radius * 0.7071);
            float2 d2 = float2(radius * 0.7071, -radius * 0.7071);

            half4 c = content.eval(p) * 4.0;
            c += content.eval(p + x);
            c += content.eval(p - x);
            c += content.eval(p + y);
            c += content.eval(p - y);
            c += content.eval(p + d1);
            c += content.eval(p - d1);
            c += content.eval(p + d2);
            c += content.eval(p - d2);
            return c / 12.0;
        }

        half4 main(float2 p) {
            float t = clamp(progress, 0.0, 1.0);

            // Blur is zero when fully closed/open and peaks mid-transition.
            float transitionPeak = sin(t * 3.14159265);

            // 0 at the hinge, 1 at the outer left/right edge.
            float halfWidth = max(resolution.x * 0.5, 1.0);
            float hingeDistance = abs(p.x - halfWidth) / halfWidth;
            float spatial = smoothstep(0.04, 1.0, hingeDistance);

            // A tiny traveling bias makes opening and closing feel directional.
            float x01 = p.x / max(resolution.x, 1.0);
            float travel = opening > 0.5 ? x01 : (1.0 - x01);
            float directional = mix(0.92, 1.08, travel);

            float radius = maxBlurPx * transitionPeak * spatial * directional;

            // Very subtle focus/scale dip at the midpoint.
            float scale = 1.0 - (scaleDip * transitionPeak);
            float2 center = resolution * 0.5;
            float2 q = (p - center) / scale + center;

            return blur9(q, radius);
        }
    """
}
