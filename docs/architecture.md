# Architecture notes

## What we are recreating

The iPhone Duo transition treats fold progress as a continuous visual state instead of waiting for a binary open/closed event. Public hands-on reporting describes a gradual blur/focus transition and a "seeing through" feeling from the outer display into the larger display. Apple also says the Duo's two displays share the same aspect ratio so content can scale proportionally across the transition. That last point is important: Galaxy Z Fold cover and inner displays do **not** share an aspect ratio, so a convincing Android recreation needs an explicit continuity bridge rather than a simple resize.

The current Galaxy Fold prototype uses the system's real cover/inner display handoff, but briefly bridges the two matched snapshots while the image is already strongly defocused:

```
physical hinge angle
      ↓
time-aware low-latency smoothing
      ↓
normalized progress (0..1)
      ↓
learned One UI display-handoff progress
      ↓
focus peak around that handoff
      ↓
spatial mask (hinge → outer edge)
      ↓
cover snapshot ──→ short blurred 50/50 source bridge
                          │
                          └─ One UI switches active display
                                      ↓
                              inner snapshot resolves
```

The source bridge has a small 50/50 latch window around the learned handoff. During that window, the outgoing cover surface and incoming inner surface render the same snapshot mixture. This removes the average-color/large-shape pop that still remained when the renderer switched directly between two different screenshots, while keeping the blend short enough to avoid the obvious doubled-icon look of the early wide cross-fade POC.

## Adaptive handoff calibration

`TransitionTuning` contains pure, unit-tested transition math. The app begins with a conservative handoff estimate and watches for a real View size/aspect change between cover and inner surfaces while the hinge sensor is active. The observed **raw** hinge progress is folded into separate opening and closing estimates with a robust rolling median and a low-pass update.

Separate opening/closing calibration matters because One UI can apply hysteresis: the angle at which the device moves from cover to inner does not have to be identical to the reverse transition.

Recent accepted calibration samples and counts are now persisted together with the estimated handoff. Restarting the process therefore preserves both confidence and outlier rejection instead of temporarily trusting the first few post-restart relayouts.

## Renderer

`SnapshotTransitionView` owns an AGSL `RuntimeShader` with two bitmap inputs:

- `coverSnapshot`
- `innerSnapshot`
- `progress`
- `opening`
- `coverSurface`
- `handoffProgress`
- `focusWindow`
- `maxBlurPx`
- `resolution`

Geometry changes slightly as the surfaces approach or leave the handoff. Blur peaks at the learned handoff and remains spatial: the hinge region stays relatively sharp while the outer edge receives the strongest defocus. The blur field itself follows the same short source bridge so its spatial pattern does not jump at the physical display switch.

Outside the handoff, the shader skips the multi-tap blur and performs a single sample. Around the handoff it uses a compact normalized nine-tap kernel. The two physical surfaces meet at the same small scale dip, avoiding a hidden scale discontinuity behind the blur.

An automatic preview mode can override the surface role on a single display so the complete cover-to-inner sequence can be inspected without repeatedly folding the device. Full-screen preview also hides system bars so the transition can be judged without diagnostic UI obscuring it.

## Sensor path

`HingeAngleMonitor` reads `Sensor.TYPE_HINGE_ANGLE` and requests roughly 120 Hz, unbatched delivery when the sensor/driver supports it. The request may be clamped by the hardware, so `HingeSignalFilter` uses sensor timestamps rather than assuming a fixed event rate.

The filter combines angular velocity and current tracking error to choose a time constant. Slow movement receives stronger smoothing to hide sensor jitter; fast movement catches up aggressively. A residual-lag bound prevents a delayed or dropped sample from leaving the visual state more than about five degrees behind the physical hinge.

Raw hinge angle remains separate from filtered render progress. Rendering uses the filtered path, while handoff calibration uses raw angle so learned switch points do not inherit filter latency.

## First-frame behavior

A fresh launch seeds progress from the active display shape before the first hinge event arrives: cover starts from the closed endpoint and inner starts from the open endpoint. This prevents the unfolded display from briefly rendering the high-blur handoff state while the hinge sensor is still starting. Saved activity state still wins during ordinary recreation/configuration changes.

## Presentation path

`DuoPresentationController` remains an optional experiment rather than the primary strategy. Android 16/API 36 and newer can mark built-in internal displays with `Display.FLAG_PRESENTATION`; the controller enumerates the presentation category and built-in display category and only attempts a `Presentation` on another display that the platform marks presentation-capable.

Platform policy can still reject an internal presentation if the application's focused task/display relationship is not permitted. On Galaxy Fold devices where Samsung exposes only the current logical built-in display, the primary non-root path remains the active-display handoff described above.

## Integration tiers

A regular Android application does not own the system compositor or Samsung's fold/unfold transition and cannot simply replace SystemUI's screen-switch animation for every app.

1. **Active-display snapshot renderer** — current primary POC; uses One UI's own cover/inner handoff and applies our transition on the currently active surface.
2. **Presentation / multi-display experiment** — uses a second controlled internal display if Android/Samsung policy exposes one with presentation capability.
3. **Launcher implementation** — can make the home-screen fold transition feel native, but cannot replace transitions inside arbitrary apps.
4. **Snapshot / MediaProjection experiment** — can transform captured content, but introduces user consent, latency, secure-content exclusions, and privacy constraints.
5. **Accessibility overlay experiment** — useful for masks/fades, but still does not grant direct access to another app's live render surface.
6. **Root / SystemUI module** — closest to a true system-level replacement, but outside normal Play-distributable app capabilities.

The core transition engine remains independent of the integration layer so the calibrated shader/math can be reused by a launcher, MediaProjection prototype, or root/SystemUI implementation later.

## CI validation

GitHub Actions now gates the branch with:

- pure JVM unit tests for transition math, continuity, surface classification, calibration, and hinge filtering;
- Android Lint;
- debug APK assembly;
- an Android emulator instrumentation smoke test that creates the real `RuntimeShader`, binds both `BitmapShader` inputs, and sets every uniform.

The emulator cannot reproduce Samsung's physical cover/inner switch or hinge hardware, but it catches AGSL syntax/input-binding failures before a build is considered suitable for device testing.

## Next calibration tasks

- Import closely matched cover and inner home-screen screenshots and calibrate crop/geometry for the Galaxy Z Fold aspect-ratio mismatch.
- Record actual One UI opening and closing handoff angles over multiple cycles and verify the persisted adaptive estimates.
- Compare blur strength and spatial falloff against reference footage at several fold angles, not only the midpoint.
- Evaluate a right-side continuity anchor on the inner display; community recreations suggest keeping outer-screen content in the same viewer-relative region reduces the perceived icon jump.
- Investigate a launcher-hosted renderer after the in-app active-display handoff is visually convincing.
