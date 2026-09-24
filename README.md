# iPhone Duo Animation

Android research project for recreating the progressive fold/unfold transition demonstrated by Apple's iPhone Duo on Samsung Galaxy Fold-class hardware.

## Goal

Build a Galaxy Fold-friendly prototype that treats physical hinge angle as a continuous visual state instead of a binary screen swap. The renderer combines the real One UI cover/inner handoff with a calibrated GPU transition so the display switch is masked by the same blur/focus motion.

A normal third-party Android app cannot replace Samsung SystemUI's fold animation for arbitrary apps. This repository therefore separates the reusable transition engine from integration experiments such as controlled snapshots, `Presentation`, a future launcher implementation, or privileged/root approaches.

## Current pipeline

`hinge sensor -> timestamp-aware filter -> normalized progress -> learned One UI handoff -> AGSL RuntimeShader -> active display`

The current prototype includes:

- progressive spatial blur: relatively sharp near the hinge, stronger toward the outer edge;
- a short blurred source bridge so cover and inner screenshots meet on the same visual state at the physical handoff;
- separate opening and closing handoff calibration with rolling-median outlier rejection;
- persisted calibration history/confidence across process restarts;
- adaptive hinge filtering with a bounded visual lag and unbatched low-latency sensor requests;
- rotation-aware hinge axis/edge mapping for the shader;
- a one-screen automatic preview and an unobstructed full-screen preview;
- an optional internal-display `Presentation` experiment when the platform exposes another presentation-capable built-in display.

## Target

- Android 13+ (`RuntimeShader` / AGSL)
- Foldable devices exposing `Sensor.TYPE_HINGE_ANGLE`
- Primary device family: Samsung Galaxy Z Fold
- Current build configuration: compile/target API 37, min API 33

## Validation

GitHub Actions gates changes with JVM unit tests, Android Lint, debug APK assembly, and an Android-emulator instrumentation test that compiles and renders the real AGSL shader with both snapshot inputs.

The remaining device-only validation is the part CI cannot emulate: Samsung's actual cover/inner display switch timing, hinge hardware behavior, and perceptual continuity on a physical Fold.

## Development

Active integration work lives on `feature/presentation-snapshot-poc`. Builds are intentionally not treated as user-ready merely because they compile; the branch is being hardened until the physical Fold test can answer meaningful visual questions rather than basic correctness issues.

See [`docs/architecture.md`](docs/architecture.md) for design details and platform limitations.
