package com.hoons1994.iphoneduoanimation

/** Keep projection constants/formula in sync with FoldProjection; GPU tests check parity. */
object SnapshotTransitionShader {
    const val SOURCE = """
        uniform shader coverSnapshot;
        uniform shader innerSnapshot;
        uniform float2 coverSize;
        uniform float2 innerSize;
        uniform float2 resolution;
        uniform float tiltRadians;
        uniform float coverSurface;
        uniform float hingeAxisY;
        uniform float coverHingeFromEnd;
        uniform float movingFromEnd;
        uniform float linkedScene;
        uniform float maxBlurPx;
        uniform float shadeStrength;

        float2 imageCoord(float2 p, float2 imageSize) {
            float scale = max(resolution.x / imageSize.x, resolution.y / imageSize.y);
            return (p - resolution * 0.5) / scale + imageSize * 0.5;
        }

        half4 sourceAt(float2 p) {
            // A shared atlas has explicit correspondences. Imported independent
            // screenshots do not: never cross-fade unregistered icons together.
            if (linkedScene > 0.5) {
                float2 uv = p / resolution;
                if (coverSurface > 0.5) {
                    float offset = movingFromEnd > 0.5 ? 0.0 : 0.5;
                    if (hingeAxisY > 0.5) uv.y = uv.y * 0.5 + offset;
                    else uv.x = uv.x * 0.5 + offset;
                }
                if (any(lessThan(uv, float2(0.0))) || any(greaterThan(uv, float2(1.0)))) {
                    return half4(0.025, 0.035, 0.055, 1.0);
                }
                return innerSnapshot.eval(uv * innerSize);
            }
            float2 size = coverSurface > 0.5 ? coverSize : innerSize;
            float2 coord = imageCoord(p, size);
            // Do not smear the last row of icons into the newly exposed area.
            if (any(lessThan(coord, float2(0.0))) || any(greaterThan(coord, size))) {
                return half4(0.025, 0.035, 0.055, 1.0);
            }
            if (coverSurface > 0.5) return coverSnapshot.eval(coord);
            return innerSnapshot.eval(coord);
        }

        half4 softSample(float2 p, float radius) {
            if (radius < 0.5) return sourceAt(p);
            float r = radius * 0.42;
            half4 c = sourceAt(p) * 4.0;
            c += sourceAt(p + float2(r, 0.0)) * 2.0;
            c += sourceAt(p - float2(r, 0.0)) * 2.0;
            c += sourceAt(p + float2(0.0, r)) * 2.0;
            c += sourceAt(p - float2(0.0, r)) * 2.0;
            c += sourceAt(p + float2(r, r));
            c += sourceAt(p - float2(r, r));
            c += sourceAt(p + float2(r, -r));
            c += sourceAt(p - float2(r, -r));
            return c / 16.0;
        }

        half4 main(float2 p) {
            float cross = hingeAxisY > 0.5 ? p.y : p.x;
            float along = hingeAxisY > 0.5 ? p.x : p.y;
            float crossSize = hingeAxisY > 0.5 ? resolution.y : resolution.x;
            float alongSize = hingeAxisY > 0.5 ? resolution.x : resolution.y;
            float hinge = crossSize * 0.5;
            float direction = movingFromEnd > 0.5 ? 1.0 : -1.0;
            float panel = crossSize * 0.5;
            if (coverSurface > 0.5) {
                hinge = coverHingeFromEnd > 0.5 ? crossSize : 0.0;
                direction = coverHingeFromEnd > 0.5 ? -1.0 : 1.0;
                panel = crossSize;
            }
            float d = (cross - hinge) * direction;
            // The fixed pane and hinge are exact identity, including blur/shade.
            if (d <= 0.0 || tiltRadians <= 0.00001) return sourceAt(p);
            float theta = clamp(tiltRadians, 0.0, 1.30899694);
            float depth = clamp(d / max(panel, 1.0), 0.0, 1.0) * sin(theta);
            float denominator = 1.0 - depth / 3.2;
            float mappedCross = hinge + direction * d * cos(theta) / denominator;
            float mappedAlong = alongSize * 0.5 + (along - alongSize * 0.5) / denominator;
            float2 q = hingeAxisY > 0.5
                ? float2(mappedAlong, mappedCross) : float2(mappedCross, mappedAlong);
            half4 color = softSample(q, maxBlurPx * depth * depth);
            return half4(color.rgb * half(1.0 - shadeStrength * depth), color.a);
        }
    """
}
