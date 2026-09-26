"""Desktop Skia pixel check of the production live shader's projection/level weights.

This compiles the actual shader string and supplies native Gaussian source levels.
It cannot validate Android RenderEffect graph wiring, View invalidation, or GPU timing.
Requires skia-python and numpy; writes diagnostics under the supplied output folder.
"""
import argparse
import json
import math
import re
from pathlib import Path

import numpy as np
import skia

SIZE = 384
SAMPLING = skia.SamplingOptions(skia.FilterMode.kLinear)


def source_image(axis_y):
    surface = skia.Surface(SIZE, SIZE)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorWHITE)
    paint = skia.Paint(Color=skia.ColorBLACK)
    for start in range(0, SIZE, 16):
        rect = (skia.Rect.MakeXYWH(start, 0, 8, SIZE) if axis_y else
                skia.Rect.MakeXYWH(0, start, SIZE, 8))
        canvas.drawRect(rect, paint)
    return surface.makeImageSnapshot()


def blur_image(source, radius):
    if radius == 0:
        return source
    # Android libs/hwui/utils/Blur.h convertRadiusToSigma (RenderEffect JNI).
    native_radius = max(0.01, (radius - 0.5) / 0.57735)
    sigma = native_radius * 0.57735 + 0.5
    effect = skia.ImageFilters.Blur(sigma, sigma, skia.TileMode.kClamp)
    surface = skia.Surface(SIZE, SIZE)
    surface.getCanvas().drawImage(source, 0, 0, SAMPLING, skia.Paint(ImageFilter=effect))
    return surface.makeImageSnapshot()


def render(effect, angle, cover, rotation):
    source = source_image(rotation in (1, 3))
    bend = angle if cover else 180 - angle
    phase = max(0, min(1, bend / 90))
    motion = phase * phase * (3 - 2 * phase)
    if motion < 0.0001:
        return source
    radians = min(87.3, max(0, bend)) * math.pi / 180
    radius = 72 * SIZE / (774 if cover else 1600)
    surface = skia.Surface(SIZE, SIZE)
    surface.getCanvas().clear(skia.ColorTRANSPARENT)
    # Mirror DuoFoldModel.blurLevels, including the fine source-pixel levels.
    coarse = radius / 9
    fine = min(1, coarse / 3)
    radii = (0, fine, math.sqrt(fine * coarse), coarse, radius / 3, radius)
    minimum_sigma = 0.57735 * 0.01 + 0.5
    radii = tuple(dict.fromkeys(0 if value == 0 else max(value, minimum_sigma)
                               for value in radii))
    for level, blur_radius in enumerate(radii):
        builder = skia.RuntimeShaderBuilder(effect)
        image = blur_image(source, blur_radius)
        builder.setChild("content", image.makeShader(skia.TileMode.kClamp,
                         skia.TileMode.kClamp, SAMPLING))
        builder.setUniform("resolution", skia.V2(SIZE, SIZE))
        builder.setUniform("radiusStops", skia.V3(radii[max(0, level - 1)],
                           blur_radius, radii[min(len(radii) - 1, level + 1)]))
        uniforms = dict(coverSurface=float(cover), foldCos=math.cos(radians),
                        foldSin=math.sin(radians),
                        eyeDistancePx=SIZE * ((40 - .825538) / 7.73936 if cover
                                              else (40 - .24948) / 15.7987),
                        maxBlurPx=radius, motionAmount=motion,
                        hingeAxisY=float(rotation in (1, 3)),
                        hingeFromEnd=float(rotation in (1, 2)), level=level)
        for key, value in uniforms.items():
            builder.setUniform(key, float(value))
        surface.getCanvas().drawRect(skia.Rect.MakeWH(SIZE, SIZE),
            skia.Paint(Shader=builder.makeShader(), BlendMode=skia.BlendMode.kPlus))
    return surface.makeImageSnapshot()


def stats(pixels, axis, rotation):
    coordinate = int((1 - axis if rotation in (1, 2) else axis) * SIZE)
    across = slice(SIZE // 4, SIZE * 3 // 4)
    band = (pixels[coordinate, across, :3] if rotation in (1, 3) else
            pixels[across, coordinate, :3]).astype(float).mean(axis=1)
    mean = float(band.mean())
    return dict(mean=mean, contrast=float(band.std() / max(mean, 1)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    kotlin = (root / "app/src/main/java/com/hoons1994/iphoneduoanimation/LiveFoldShader.kt").read_text()
    source = re.search(r'const val SOURCE = """(.*?)"""', kotlin, re.S).group(1)
    effect = skia.RuntimeEffect.MakeForShader(source)
    args.output.mkdir(parents=True, exist_ok=True)
    report, failures = {}, []
    for rotation in range(4):
        for cover, angles in ((True, (0, 45, 77, 98)), (False, (98, 120, 160, 180))):
            for angle in angles:
                name = f"{'cover' if cover else 'inner'}-{angle}-r{rotation}"
                image = render(effect, angle, cover, rotation)
                pixels = image.toarray()
                moving = stats(pixels, 0.45 if cover else 0.2, rotation)
                fixed = stats(pixels, 0.75, rotation)
                report[name] = dict(moving=moving, fixed=fixed,
                    minimum_alpha=int(pixels[..., 3].min()))
                if int(pixels[..., 3].min()) < 253:
                    failures.append(f"{name}: weights lost opacity")
                if not cover and fixed["contrast"] < .9:
                    failures.append(f"{name}: fixed pane blurred")
                if (cover and angle >= 77) or (not cover and angle <= 120):
                    if moving["contrast"] > .6 or moving["mean"] < 25:
                        failures.append(f"{name}: moving pane frost/brightness invalid: {moving}")
                if angle in (0, 180) and not np.array_equal(pixels,
                        source_image(rotation in (1, 3)).toarray()):
                    failures.append(f"{name}: endpoint changed pixels")
                image.save(str(args.output / f"{name}.png"))
    (args.output / "metrics.json").write_text(json.dumps(report, indent=2))
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"PASS: production shader compiled; {len(report)} desktop pixel states passed. "
          "Android GPU/RenderEffect execution remains unverified.")


if __name__ == "__main__":
    main()
