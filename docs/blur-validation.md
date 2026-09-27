# Live blur architecture and historical regression evidence

## Current 0.6.7 scope

Version 0.6.7 (version code 14) replaces the six-level Gaussian blend with one
live shader using the 32-point Vogel-disk sample pattern. The disk radius now
retains its original units. The prior graph treated it as Gaussian sigma,
spreading detail about twice as far. The new graph builds one RuntimeShader
effect per pose; sample grain and GPU frame cost still need measurement on a
Fold display.

The 0.6.6 active-range mapping, smooth hinge strip, and gap-based ray projection
remain. Sensor frames establish their clock from the first observed vsync
interval instead of assuming 60 Hz. Handoff calibration uses the sensor event's
measurement time. On resume, the first draw waits for a current-session sensor
sample, then uses a visible-surface endpoint fallback after 120 ms if no fresh
reading arrives.

The final `:app:assembleDebug` completed successfully for 0.6.7 (version code 14)
on 2026-09-27. No tests, runtime AGSL checks, or physical-device checks have been
run. The desktop fixture was rewired to the live disk shader but has not been
run. Android AGSL compilation, GPU cost, sample grain, frame pacing, touch
behavior, and visual fidelity remain unverified.

## Historical 0.6.6 scope

Version 0.6.6 (version code 13) changes the optical model after the finer blur
levels in 0.6.5 did not resolve the user's stretching complaint. Each display's
active hinge range now maps through cubic smoothstep to 0–45 degrees of optical
tilt, with a smooth strip beside the hinge and gap-based frost. Calibration is
held during the active gesture, the second angle follower is removed, and normal
home resumes reuse attached views. The up-to-six Gaussian levels, source-space
blur ceiling, and rectangular app drawer remain. The removed `1/J` gain remains absent.

The final `:app:assembleDebug` completed successfully for 0.6.6 (version code 13)
on 2026-09-26 in 28 seconds, covering APK compilation and packaging, including
the package archive/uninstall lifecycle fixes.
No tests, runtime AGSL checks, or physical-device checks were run. The desktop
fixture has been synchronized to the new angle mapping, curved strip, gap frost,
eye distance, and uniforms; editing that fixture is not a test result.
Android GPU cost, frame pacing, touch behavior, and physical reference fidelity
remain unverified. Compilation does not establish smoother animation or lower
rendering cost.

## Historical 0.6.6 fold renderer

`HomeActivity` now renders its current view tree through `LiveFoldLayout` and
Android's `RenderEffect` input. For maximum source-space radius `R`,
`DuoFoldModel.blurLevels()` starts from
`[0, fine, sqrt(fine * coarse), coarse, R/3, R]`, with `coarse = R/9` and
`fine = min(1, coarse/3)`. Positive stops are clamped to the approximately
0.505774-pixel minimum effective native sigma and deduplicated. This produces
up to six live levels: the sharp source plus at most five native Gaussian levels.
`DuoFoldModel.nativeBlurRadius()` converts the positive targets using
`(targetRadius - 0.5) / 0.57735`, clamped to 0.01. These levels remain a native
Gaussian approximation. They do not reproduce the current optical references'
Vogel-disk/Metal filter, or the browser/Classic 25-tap footprint with mip sampling.

For physical angle `h` and latched handoff `H` (default 98 degrees),
`DuoFoldModel` uses cover phase `clamp((h - 6) / (H - 6), 0, 1)` and inner
phase `clamp((172 - h) / (172 - H), 0, 1)`. Tilt is
`45 * phase^2 * (3 - 2 * phase)` degrees. The active-range approach follows
Android's
[`duo-open/DuoShader.kt`](https://github.com/marcoazeem/duo-open/blob/main/app/src/main/java/com/duoopen/fold/DuoShader.kt),
with Duo Home's handoff calibration and easing. A reversal retains the adopted
handoff; pending calibration is adopted only at the relevant clear endpoint or
while rendering is inactive. The 45-degree mapping is an Android adaptation,
not a limit imposed by the Swift source.

The moving pane has a smooth hinge strip of width
`F = max(1, paneExtent * 0.35 / 7.89935)`, about 4.43% of one pane. Inside it,
`u = clamp(distance / F, 0, 1)` and `B = F * u^3 * (1 - 0.5 * u)`; beyond it,
`B = distance - F/2`. Glass distance is `distance - (1 - cos(tilt)) * B`, and
gap is `B * sin(tilt)`. The width is inspired by the flexible region in
[`iphone-duo/main.js`](https://github.com/chuspeeism/iphone-duo/blob/main/main.js),
but this strip is not its exact Hermite mesh bend. Both CPU touch mapping and
AGSL use the same strip formula.

The eye is aligned with the display center, including on the cover, at
`max(320 * pixelsPerMm, 2 * paneExtent)` pixels. Frost is now
`min(maxBlurPx, 0.12 * gap)` and darkening is `0.015 * 6 / pixelsPerMm`, applied
as `max(1 - darkening * radius, 0)` to RGB. The previous scaled blur ceiling
remains; no additional inverse-projection gain is used. The ray/gap model is
informed by
[`Atomicx7/duo_fold.agsl`](https://github.com/Atomicx7/Duo-animation/blob/master/app/src/main/res/raw/duo_fold.agsl),
[`DuoLikeAnimation/DuoFold.metal`](https://github.com/elijah-semyonov/DuoLikeAnimation/blob/main/DuoLikeAnimation/Shaders/DuoFold.metal),
and [`FoldEffect.swift`](https://github.com/elijah-semyonov/DuoLikeAnimation/blob/main/DuoLikeAnimation/FoldEffect.swift).

Every branch receives its neighboring radii through a `radiusStops` uniform and
computes a triangular weight in variance space. On normal Fold viewports the
first positive stop is one source pixel, so moving-pane requests of one pixel
or more have zero unblurred contribution. Very small outputs use their clamped,
deduplicated stops. This interpolation remains our approximation, not an exact
reference filter. Branch pruning now uses the largest gap-derived radius at
the moving pane's outer edge.

The shader projects each level through the same glass geometry, interpolates
adjacent levels, and adds weighted premultiplied colors. The fixed pane selects
the sharp branch. Resolved endpoints remove the effect. `HingeAngleMonitor`
owns filtering and vsync settling; `LiveFoldLayout` coalesces changes into one
pre-draw commit and has no second 16 ms follower or independent idle loop.
Normal home resumes reuse attached icons and widget hosts. Configuration
changes reflow them for the new viewport; package/provider changes trigger
refreshes as needed.

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
The sensor path retains quality observations across monitor stop/start,
prioritizes observed intermediate-angle streams, guards stop-only jumps, and
bridges source or mode changes from the prior filtered pose. Home resume gates
the effect on a fresh real sample. These lifecycle and sensor behaviors require
their own checks in addition to shader pixels. In 0.6.2, fine-filter settling
keeps the adaptive time constant selected by the latest real sensor update,
instead of switching each settling frame to the slow 55 ms response. This is
intended to remove alternating fast/slow convergence between sensor events.

The 0.6.2 Dock also has explicit management, replacement, ordering, and move-to-home
actions. Shader diagnostics do not exercise these interactions, their persisted
layout, or preservation of displaced folders and shortcuts.

No current test result establishes those interaction or lifecycle behaviors.

## Historical 0.6.5 blur refinement and build

Version 0.6.5 added the two small-radius levels after the `58640.jpg` report,
but retained the direct physical-angle mapping, rigid 87.3-degree projection,
hinge-aligned eye, and `R * motion * edge^1.35` frost. The user still reported
unnatural stretching. Those optical choices have been replaced in 0.6.6;
the finer blur levels remain. This history does not establish that either
revision matches the reference on hardware.

`:app:assembleDebug` completed for 0.6.5 (version code 12) on 2026-09-26 in
32 seconds. No tests or physical-device checks were run for that revision.
This historical build is not verification of the 0.6.6 rendering changes.

## Historical 0.6.4 sharp-source mixture and build

The 0.6.4 graph blended `[0, R/9, R/3, R]` by variance. For `R = 72` and wanted
sigma 3, its first pair was 0 and 8, giving the sharp source weight
`1 - 3^2 / 8^2 = 0.859375`. This arithmetic explains how variance matching can
retain a sharp letter stroke, which strong projection can stretch into a long
line; it is not a new pixel or device measurement. The finer 0.6.5 stops target
that mixture. They do not restore the removed projection gain.

`:app:assembleDebug` completed for 0.6.4 (version code 11) on 2026-09-26 in
36 seconds. It covered APK compilation and packaging; no tests or physical-device
checks were run for that revision. This historical build does not validate 0.6.6.

## Historical 0.6.3 menus and build

Version 0.6.3 (version code 10) introduced `GlassActionOverlay` for shortcut,
Dock, folder, widget, app-drawer, and wallpaper action panels. It kept the
0.6.2 fold renderer. `:app:assembleDebug` completed on 2026-09-26, covering APK
compilation and packaging. No tests or physical-device checks were run for its
menus and input changes. That build predates the 0.6.4 renderer and drawer changes.

## Removed 0.6.2/0.6.3 projection gain

Those revisions multiplied the material radius by
`max(1, (depth / eyeDistance)^2 / max(cos(angle), 0.001))`, then clamped it to `R`.
The rationale was to obscure readable stripes magnified near edge-on projection.
The native source blur was already stretched by that projection, so the extra
gain applied magnification twice and produced excessive near-hinge blur. This
Duo Home addition was not present in the inspected `main.js` or
`ClassicGlassShader` radius formulas and was removed in 0.6.4. No passing pixel
or hardware result is recorded for that gain.

## Historical 0.6.2 build

`:app:assembleDebug` completed successfully for 0.6.2 (version code 9) on
2026-09-26. No ADB device was attached. This result covers APK compilation and
packaging; the AGSL shader is compiled at runtime and device interaction,
animation quality, and frame pacing remain unverified. It predates the 0.6.3
menu changes and is not a build result for the current revision.

## Historical 0.6.1 build

`:app:assembleDebug` completed successfully for version 0.6.1 (version code 8)
on 2026-09-26. This compiles and packages the APK; AGSL compiles on the device at
runtime, so an APK build does not validate shader execution or animation quality.
That build predates the 0.6.2 changes and is not a build result for this revision.

## Historical 0.6.0 production shader check

For version 0.6.0, `tools/check_live_shader.py` compiled its `LiveFoldShader.SOURCE` with
desktop Skia and passed all 32 pixel states: four cover angles and four inner
angles across four rotations. The generated stripe input is processed into four
native Gaussian levels, and the production shader projects and weights each
level. The checks cover opacity preservation, fixed-pane sharpness, moving-pane
frost without mistaking black output for blur, and unchanged endpoint pixels.
That run's diagnostics were written under `build/live-shader-report/`. Those
32 passing states describe the earlier model and do not validate the current renderer.
The fixture has been rewired for the current active-range mapping, density-aware
eye distance, curved hinge strip, gap-derived frost, and direct Vogel-disk shader,
but it has not been run for this revision.

```powershell
python tools/check_live_shader.py build/live-shader-report
```

This requires `skia-python` and `numpy`. The check uses desktop Skia to supply
input levels; it does not execute Android's `RenderEffect` graph, view
invalidation, touch dispatch, or GPU frame timing. It also does not validate the
separate `LiquidGlassPanel` backdrop, `GlassActionOverlay` menus, or control appearance.

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
collapse that stretched launcher widgets. These measurements belong to the
legacy `TransitionTuning`/snapshot path. Version 0.6.6 reuses the active-range
and gap principles in a live graph with cubic easing and a curved hinge strip;
that does not make the old snapshot measurements results for the current graph.
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
