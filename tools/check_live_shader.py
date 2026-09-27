"""Desktop Skia pixel check of the production live shader's projection and disk blur.

This compiles the actual shader string and supplies the live source image.
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


def render(effect, angle, cover, rotation):
    source = source_image(rotation in (1, 3))
    # Match the default calibrated visible ranges and optical pose. This fixture
    # uses the reference density, not a measured device's physical display DPI.
    phase = (angle - 6) / (98 - 6) if cover else (172 - angle) / (172 - 98)
    phase = max(0, min(1, phase))
    tilt = 45 * phase * phase * (3 - 2 * phase)
    if tilt <= 0.02:
        return source
    radians = tilt * math.pi / 180
    pane_extent = SIZE if cover else SIZE / 2
    pixels_per_mm = 6
    surface = skia.Surface(SIZE, SIZE)
    surface.getCanvas().clear(skia.ColorTRANSPARENT)
    builder = skia.RuntimeShaderBuilder(effect)
    builder.setChild("content", source.makeShader(skia.TileMode.kClamp,
                     skia.TileMode.kClamp, SAMPLING))
    builder.setUniform("resolution", skia.V2(SIZE, SIZE))
    uniforms = dict(coverSurface=float(cover), foldCos=math.cos(radians),
                    foldSin=math.sin(radians),
                    eyeDistancePx=max(320 * pixels_per_mm, pane_extent * 2),
                    maxBlurPx=72 * SIZE / (774 if cover else 1600),
                    hingeFlexPx=max(1, pane_extent * .35 / 7.89935),
                    blurSpread=.12, darkening=.015 * 6 / pixels_per_mm,
                    hingeAxisY=float(rotation in (1, 3)),
                    hingeFromEnd=float(rotation in (1, 2)))
    for key, value in uniforms.items():
        builder.setUniform(key, float(value))
    surface.getCanvas().drawRect(skia.Rect.MakeWH(SIZE, SIZE),
        skia.Paint(Shader=builder.makeShader()))
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
