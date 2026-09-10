# iPhone Duo Animation

Android research project for recreating the progressive fold/unfold transition popularized by Apple's iPhone Duo.

## Goal

Build a Galaxy Fold-friendly prototype that maps the physical hinge angle to a GPU-driven transition instead of treating fold/unfold as a simple screen swap.

The first milestone focuses on a safe in-app proof of concept. System-wide replacement of Samsung SystemUI transitions is outside the permissions available to a normal third-party Android app, so system-level experiments are kept separate from the core renderer.

## Planned pipeline

`hinge angle -> normalized progress -> transition model -> AGSL RuntimeShader -> rendered frame`

The visual model combines:

- progressive spatial blur rather than uniform full-screen blur
- subtle scale/focus interpolation
- easing near the fully-open and fully-closed endpoints
- direction-aware behavior for opening and closing
- frame-rate-independent smoothing of noisy hinge sensor values

## Target

- Android 13+ for AGSL RuntimeShader
- Foldable devices exposing `Sensor.TYPE_HINGE_ANGLE`
- Initial validation target: Samsung Galaxy Z Fold class devices

## Development

Active work will live on `feature/duo-transition-prototype` until the first reproducible demo is ready.
