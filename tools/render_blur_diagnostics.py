"""Render the exported production shader with desktop Skia.

Requires skia-python and numpy. This checks shader pixels, not Android GPU
compatibility or frame pacing. See docs/blur-validation.md for invocation.
"""
import argparse
import json
import math
from pathlib import Path

import skia

SIZE = 512
SAMPLING = skia.SamplingOptions(skia.FilterMode.kLinear)


def stripe_mips(axis_y):
    surface = skia.Surface(SIZE, SIZE)
    canvas = surface.getCanvas()
    canvas.clear(skia.ColorWHITE)
    paint = skia.Paint(Color=skia.ColorBLACK)
    for position in range(0, SIZE, 16):
        rect = (skia.Rect.MakeXYWH(position, 0, 8, SIZE) if axis_y
                else skia.Rect.MakeXYWH(0, position, SIZE, 8))
        canvas.drawRect(rect, paint)
    levels = [surface.makeImageSnapshot()]
    for _ in range(7):
        previous = levels[-1]
        w, h = max(1, previous.width() // 2), max(1, previous.height() // 2)
        surface = skia.Surface(w, h)
        surface.getCanvas().drawImageRect(previous, skia.Rect.MakeWH(w, h), SAMPLING)
        levels.append(surface.makeImageSnapshot())
    return levels


def smoothstep(value):
    value = max(0.0, min(1.0, value))
    return value * value * (3 - 2 * value)


def render(effect, levels, angle, cover, rotation, buffer_scale):
    builder = skia.RuntimeShaderBuilder(effect)
    for prefix in ("cover", "inner"):
        for index, image in enumerate(levels):
            name = prefix + ("Snapshot" if index == 0 else "Mip" + str(index))
            matrix = skia.Matrix.Scale(SIZE / image.width(), SIZE / image.height())
            builder.setChild(name, image.makeShader(
                skia.TileMode.kClamp, skia.TileMode.kClamp, SAMPLING, matrix))
        builder.setUniform(prefix + "Size", skia.V2(SIZE, SIZE))
    handoff = 98.0
    if cover:
        motion = max(0.0, min(1.0, (angle - 6.0) / (handoff - 6.0)))
    else:
        motion = max(0.0, min(1.0, (172.0 - angle) / (172.0 - handoff)))
    radians = motion * math.pi / 4
    values = {
        "coverSurface": float(cover), "motionAmount": motion,
        "foldCos": math.cos(radians), "foldSin": math.sin(radians),
        "maxBlurPx": 72 * SIZE / (774 if cover else 1600),
        "eyeDistancePx": 450 * 6,
        "blurSpread": 0.12,
        "darkening": 0.015,
        "hingeAxisY": float(rotation in (90, 270)),
        "coverHingeFromEnd": float(rotation in (90, 180)),
    }
    for name, value in values.items():
        builder.setUniform(name, float(value))
    builder.setUniform("resolution", skia.V2(SIZE, SIZE))
    surface = skia.Surface(SIZE // buffer_scale, SIZE // buffer_scale)
    canvas = surface.getCanvas()
    canvas.scale(1 / buffer_scale, 1 / buffer_scale)
    canvas.drawRect(skia.Rect.MakeWH(SIZE, SIZE), skia.Paint(Shader=builder.makeShader()))
    return surface.makeImageSnapshot()


def band_stats(image, axis_fraction, rotation):
    pixels = image.toarray()
    size = pixels.shape[0]
    if rotation in (90, 180):
        axis_fraction = 1 - axis_fraction
    axis = int(size * axis_fraction)
    across = slice(size // 4, 3 * size // 4)
    strip = pixels[axis, across, :3] if rotation in (90, 270) else pixels[across, axis, :3]
    samples = strip.astype(float).mean(axis=1)
    mean = float(samples.mean())
    return {"mean": mean, "contrast": float(samples.std() / max(1, mean))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("shader", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--verify", action="store_true",
                        help="Check blur, fixed-panel sharpness, endpoints, and four rotations.")
    parser.add_argument("--buffer-scale", type=int, choices=(1, 2), default=2)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    effect = skia.RuntimeEffect.MakeForShader(args.shader.read_text(encoding="utf-8"))
    pyramids = {False: stripe_mips(False), True: stripe_mips(True)}
    report = {}
    failures = []
    rotations = (0, 90, 180, 270) if args.verify else (0,)
    for rotation in rotations:
        for cover, angles in ((True, (0, 45, 77.4, 90)), (False, (90, 120, 150, 172))):
            for angle in angles:
                name = f"{'cover' if cover else 'inner'}-{angle}-rot{rotation}"
                image = render(effect, pyramids[rotation in (90, 270)],
                               angle, cover, rotation, args.buffer_scale)
                moving = band_stats(image, .45 if cover else .28, rotation)
                fixed = band_stats(image, .75, rotation)
                report[name] = {"moving": moving, "fixed": fixed}
                if rotation == 0:
                    image.save(str(args.output / f"{name}.png"), skia.kPNG)
                frosted = angle in ((77.4, 90) if cover else (90, 120))
                resolved = angle == (0 if cover else 172)
                if frosted and (moving["mean"] <= 10 or moving["contrast"] >= .35):
                    failures.append(f"{name}: missing frost or blackout: {moving}")
                if resolved and moving["contrast"] <= .85:
                    failures.append(f"{name}: endpoint still blurred: {moving}")
                if not cover and fixed["contrast"] <= .85:
                    failures.append(f"{name}: fixed panel blurred: {fixed}")
    report["verification"] = {"checked": args.verify, "failures": failures}
    (args.output / "metrics.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"frames": len(report) - 1, "verified": args.verify,
                      "failures": failures, "report": str(args.output / "metrics.json")}, indent=2))
    if args.verify and failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
