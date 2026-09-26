# Live blur architecture and historical regression evidence

## Current 0.6.1 launcher renderer

`HomeActivity` now renders its current view tree through `LiveFoldLayout` and
Android's `RenderEffect` input. `LiveFoldEffects` supplies four live levels:
sharp, maximum source-space blur divided by nine, maximum divided by three, and
maximum. `DuoFoldModel.nativeBlurRadius()` converts these targets to Android's
native Gaussian radius with `(targetRadius - 0.5) / 0.57735`, clamped to 0.01.
Matching the reference binomial kernel's variance is an approximation; its
25-tap footprint, mip sampling, and our level interpolation are different filters.

`DuoFoldModel` maps cover bend to the hinge angle and inner bend to 180 degrees
minus that angle. Handoff calibration and direction do not alter the optical
pose. The eye is hinge-aligned, including on the cover. Projection stops at
87.3 degrees (`0.97 * 90` from the Android adaptation), while material motion
uses the full 90-degree bend envelope. `LiveFoldShader` sets radius from
`maxBlur * smoothstep(0, 1, bend / 90) * pow(edge, 1.35)`, with inputs clamped
to their valid ranges and `edge` measured on the pane from hinge to outer edge.
These material coordinates keep frost present when projected source coordinates
compress. The geometry draws on
[`iphone-duo/main.js`](https://github.com/chuspeeism/iphone-duo/blob/main/main.js);
the projection cap and material-space envelope follow
[`ClassicGlassShader.kt`](https://github.com/joeconsorti/duo-fold-live/blob/main/app/src/main/java/org/duofold/live/ClassicGlassShader.kt).

The shader projects each level through the same glass geometry, interpolates
adjacent levels, and adds weighted premultiplied colors. The fixed pane selects
the sharp branch. Resolved endpoints remove the effect, and hinge frame
callbacks stop when the angle follower settles. Configuration changes reuse
attached home icons and widget hosts and reflow them for the new viewport.

This path has no app bitmap capture, readback, cached mip generation, or idle
capture worker. Android still renders effect inputs and blur passes on the GPU.
The API mechanism follows the official
[AGSL RenderEffect guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)
and [RenderEffect reference](https://developer.android.com/reference/android/graphics/RenderEffect).

The live graph needs separate Android validation: current widget content during
motion, interpolation between blur levels, premultiplied composition, clear
endpoints, rotations, and both physical display handoffs. Device measurements
are also needed for frame pacing and GPU cost. The historical numbers below do
not validate this graph or establish that it runs faster than the snapshot path.
The 0.6.1 sensor path retains quality observations across monitor stop/start,
prioritizes observed intermediate-angle streams, guards stop-only jumps, and
bridges source or mode changes from the prior filtered pose. Home resume gates
the effect on a fresh real sample. These lifecycle and sensor behaviors require
their own checks in addition to shader pixels.

Tests have not been run for the 0.6.1 model, sensor, and reflow changes. Neither reference
fidelity nor Android GPU performance is verified for this revision.
`:app:assembleDebug` completed successfully for version 0.6.1 (version code 8)
on 2026-09-26. This compiles and packages the APK; AGSL compiles on the device at
runtime, so an APK build does not validate shader execution or animation quality.

## Historical 0.6.0 production shader check

For version 0.6.0, `tools/check_live_shader.py` compiled its `LiveFoldShader.SOURCE` with
desktop Skia and passed all 32 pixel states: four cover angles and four inner
angles across four rotations. The generated stripe input is processed into four
native Gaussian levels, and the production shader projects and weights each
level. The checks cover opacity preservation, fixed-pane sharpness, moving-pane
frost without mistaking black output for blur, and unchanged endpoint pixels.
That run's diagnostics were written under `build/live-shader-report/`. Those
32 passing states describe the earlier model and do not validate 0.6.1. The
fixture has been updated for the new angle mapping, eye ratios, material motion,
and native blur conversion, but it has not been run for this revision.

```powershell
python tools/check_live_shader.py build/live-shader-report
```

This requires `skia-python` and `numpy`. The check uses desktop Skia to supply
input levels; it does not execute Android's `RenderEffect` graph, view
invalidation, touch dispatch, or GPU frame timing. It also does not validate the
separate `LiquidGlassPanel` backdrop and control appearance.

## Android and geometry test scope

`LiveFoldRenderTest` exercises the actual live `RenderEffect` graph through
`HardwareRenderer`. Its cases cover moving-pane frost and fixed-pane sharpness
in all rotations, changing source content without replacing the effect,
rebuilding geometry without mutating an earlier effect, and removing the graph
at clear endpoints. These GPU tests remain unrun because no ADB device or
emulator was available.

`LiveFoldGeometryTest` covers the source coordinates used for touch mapping:
identity at clear endpoints, the fixed inner pane, continuity at the hinge,
rejection of rays that miss content, and rejection of touches on fully darkened
glass (`fullyDarkGlass_doesNotActivateHiddenContent`). Those checks
target pure geometry and cannot establish Android touch dispatch or fold
performance. The following build and test results belong to 0.6.0, before the
current changes: on 2026-09-26, `:app:testDebugUnitTest` passed all 56 JVM tests,
including the then-current five live geometry cases and hinge filter settling regressions.
`:app:assembleDebug` and `:app:assembleDebugAndroidTest` also completed. The latter
compiles the GPU tests; it does not execute them on a device.
That 0.6.0 clean build also passed `:app:lintDebug` with no errors. Three
non-blocking warnings remain: two Android test dependency update notices and
the existing backup configuration notice.

## Historical projected-distance regression

The old shader based blur and darkening on the projected screenshot coordinate.
Near 90 degrees, that coordinate collapses to the hinge. Increasing mip levels
could not fix this because the computed blur radius was almost zero.

The historical snapshot correction uses the pixel-space ray-plane geometry from `duo-open`,
with a 45-degree virtual tilt cap and a density-aware 320 mm eye distance. Blur
and attenuation follow the glass-to-plane gap while perspective lookup follows
the ray intersection. This replaces the fixed model dimensions and 90-degree
collapse that stretched launcher widgets. This remains in the legacy
`TransitionTuning`/snapshot path and does not describe the 0.6.1 live model.
It follows the physical model in
[Atomicx7's shader](https://github.com/Atomicx7/Duo-animation/blob/master/app/src/main/res/raw/duo_fold.agsl)
and its two-pane Android adaptation in
[`duo-open`](https://github.com/marcoazeem/duo-open).
The 320 mm eye distance follows Atomicx7's stated default and is close to the
30 cm viewing distance described by the SwiftUI/Core Motion port
[`DuoLikeAnimation`](https://github.com/elijah-semyonov/DuoLikeAnimation).
The 72 source-pixel blur ceiling follows the interactive projection study
[`chuspeeism/iphone-duo`](https://github.com/chuspeeism/iphone-duo); the Android
ports use different sample counts and coordinate scales, so this is a visual
calibration target rather than a direct shader-parameter copy.
The original regression used a nine-tap approximation. The legacy
`SnapshotTransitionShader` subsequently used a 5x5 binomial footprint with seven
cached mip levels. That shader remains available for diagnostics; it is no
longer the home launcher's rendering path.

## Reproduction and measured scope

The compiled application's actual shader string was exported and rendered with
desktop Skia 144.0.post2. Inputs were 512x512 alternating horizontal bands,
eight pixels per band. The moving sample was at 28% of the inner display width;
the fixed sample was at 75%. Contrast is standard deviation divided by mean
brightness, measured over the middle half of the image height. A fully sharp
black/white pattern has contrast 1.0. Normalization ensures that merely darkening
the pattern does not count as blur.

| Inner opening angle | Previous moving contrast | Corrected moving contrast | Corrected fixed contrast |
| --- | ---: | ---: | ---: |
| 90 degrees | 0.959 | 0.116 | 1.000 |
| 120 degrees | 0.738 | 0.089 | 1.000 |
| 150 degrees | 0.886 | 0.783 | 1.000 |
| 172 degrees | 1.000 | 1.000 | 1.000 |

Those values record the earlier nine-tap correction and remain useful as the
before/after evidence for the projected-distance bug. The pre-0.4 25-tap run
at half-size produced moving contrast 0.000 at 90 degrees, 0.050 at 120 degrees,
0.774 at 150 degrees, and 1.000 at 172 degrees; fixed-panel contrast remained
1.000. Those values use a 512x512 logical output. The standalone
runner also supports the application's half-size output and four rotations.
That earlier half-size regression passed all 32 rendered states across four rotations;
the 90-degree moving contrast was 0.112 and fixed contrast was 1.000.
The figures above predate the 0.4 ray-plane replacement and are not results for
the new geometry. This is a desktop pixel regression, not an Android GPU or physical Fold test.
It does not establish reference-video fidelity or improved device frame pacing.

## Repeatable legacy desktop check

This procedure exercises `SnapshotTransitionShader`. It does not export the
live Android `RenderEffect` graph or reproduce its native blur branches.

Use a separate Python environment with `skia-python==144.0.post2` and `numpy`.
Build `:app:bundleDebugClassesToCompileJar`, then run the Java source-file helper
with the resulting `classes.jar` and the build's `kotlin-stdlib` jar on its
classpath:

```powershell
java --class-path "$classesJar;$kotlinStdlibJar" tools/ExportShader.java build/transition.sksl
python tools/render_blur_diagnostics.py build/transition.sksl build/blur-report --verify
```

`$classesJar` points to
`app/build/intermediates/compile_app_classes_jar/debug/bundleDebugClassesToCompileJar/classes.jar`.
Set `$kotlinStdlibJar` to the matching Kotlin standard-library jar from Gradle's
dependency cache. The output directory contains rendered frames and
`metrics.json`; failed contrast, endpoint, or fixed-panel checks return exit 1.
Use `--buffer-scale 1` for the full-size measurement above (default is 2).

## Legacy Android regression coverage

`RuntimeShaderSmokeTest` draws the snapshot shader through `HardwareRenderer` into an
`ImageReader`, rather than a bitmap-backed Canvas. It checks moving-panel frost
at 90/120 degrees, the sharp fixed panel, resolved endpoints, both hinge axes,
and cover frost at 77.4/90 degrees. The actual half-size output is used for the
blur regressions. These instrumentation tests were compiled during the earlier
snapshot work; running them requires an Android device or emulator. They do not
cover `LiveFoldLayout` or the new live blur graph.
