# iPhone Duo Animation

Android research project exploring a fixed-content-plane fold/unfold illusion
on Galaxy Fold-class hardware. This is an independent approximation, not an
Apple implementation or a replacement for One UI's system transitions.

## Current implementation: v12 projection lab

`manual angle or hinge sensor -> fixed-plane projection -> AGSL -> active app surface`

The renderer splits the inner display into stationary and moving halves. It
traces an eye ray through the tilted moving panel into a fixed content plane,
with depth-dependent local blur and shading. The stationary pane and hinge stay
unchanged; 180 degrees restores the original. The old blur/cross-fade handoff
engine is no longer used by MainActivity.

Cold launch intentionally shows a manual **120-degree inner scene** with a
shared numbered grid. No screenshot import or sensor is needed for this first
visual check. Use **자동 시연** for animation, **180°** for the resolved endpoint,
and **효과: 켜짐 / 원본** for A/B. Tap the image to hide or restore controls.
**센서 연결** switches to physical hinge input. Touching the angle controls
returns to manual mode so the sensor cannot overwrite the preview.

The app also includes left/right selection, cover/inner manual modes, user-picked
snapshots, optional Presentation, and bounded metadata-only CSV diagnostics.
Two independent screenshots are not automatically registered to each other.
An optional 240-ms visibility correction is separate from physical angle and
can be disabled. See [the v12 design and limitations](docs/projection-v12.md).

## Build and tests

- Android 13+; compile/target API 37, minimum API 33.
- JDK 17 and Gradle 9.6.0; AGP 9.4.0 with built-in Kotlin.
- Build: `gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- Device tests: `bash tools/run-device-tests.sh` with an Android device/emulator.
- Android-free geometry sweep: compile `FoldProjection.kt` and
  `tools/ProjectionCheck.kt` with kotlinc and run the resulting jar.

CI runs JVM tests, lint, app/instrumentation builds, and API 35 Android tests.
The Android tests exercise HardwareRenderer coordinate parity, fixed/moving
pixels, endpoint restoration, Activity controls and advancing auto-demo frames.
Reports and test images are retained as Actions artifacts. Passing these checks
is not proof of perceptual continuity or frame pacing on a real foldable.

## Scope

This is a foreground visual lab, not a launcher or accessibility overlay.
Actual Galaxy Fold panel activation, complete sensor delivery, screen-to-screen
continuity, physical rotation and full-resolution frame pacing still need
real-device validation. Presentation cannot force another panel to turn on.
No system-wide or 60/120-fps success is claimed.

The older [architecture notes](docs/architecture.md) describe the pre-v12
handoff experiment. Legacy calibration classes remain for history/regression;
use the v12 design document for the active pipeline.
