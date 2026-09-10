# Architecture notes

## What we are recreating

The iPhone Duo transition appears to treat fold progress as a continuous visual state instead of waiting for a binary open/closed event. Public hands-on reporting describes content gradually coming into focus while the phone opens and closes. Early recreations also point to a spatially progressive blur: the effect is not equally strong at every pixel.

The Galaxy Fold prototype now uses the system's real cover/inner display handoff instead of trying to keep two full screenshots cross-faded on one surface:

```
physical hinge angle
      ↓
low-pass smoothing
      ↓
normalized progress (0..1)
      ↓
learned One UI display-handoff progress
      ↓
focus peak around that handoff
      ↓
spatial mask (hinge → outer edge)
      ↓
cover snapshot on cover display
      │
      └─ One UI switches active display ─→ inner snapshot on inner display
                                          ↓
                              focus resolves toward fully open
```

This avoids the obvious doubled icons produced by the earlier wide screenshot cross-fade. The physical display switch becomes part of the animation instead of something the app tries to fake.

## Adaptive handoff calibration

`TransitionTuning` contains pure, unit-tested transition math. The app begins with a conservative handoff estimate and watches for a real View size/aspect change between cover and inner surfaces while the hinge sensor is active. The observed hinge progress is folded into separate opening and closing estimates with a low-pass update, then persisted.

Separate opening/closing calibration matters because One UI can apply hysteresis: the angle at which the device moves from cover to inner does not have to be identical to the reverse transition.

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

Only the snapshot belonging to the active physical surface is rendered during real hinge operation. Geometry changes slightly as the surface approaches or leaves the handoff. Blur peaks at the learned handoff and remains spatial: the hinge region stays relatively sharp while the outer edge receives the strongest defocus.

An automatic preview mode can override the surface role on a single display so the complete cover-to-inner sequence can be inspected without repeatedly folding the device. This override is diagnostic only; sensor mode always follows the physical surface.

## Sensor path

`HingeAngleMonitor` reads `Sensor.TYPE_HINGE_ANGLE`, clamps the book-style fold range to 0–180°, applies an exponential low-pass filter, and derives opening/closing direction with a small deadband.

Sensor smoothing is kept outside the renderer so it can later be replaced with a predictive or velocity-aware filter without changing shader code.

## Presentation path

`DuoPresentationController` remains an optional experiment rather than the primary strategy. It enumerates both normal presentation displays and the API 37 built-in display category, including inactive built-in displays when the platform exposes them. If Samsung marks another built-in display as presentation-capable, the same snapshot renderer can run there.

On the tested Galaxy Fold configuration so far, only the current built-in logical display has been exposed to the app, so the primary non-root path is the active-display handoff described above.

## Integration tiers

A regular Android application does not own the system compositor or Samsung's fold/unfold transition and cannot simply replace SystemUI's screen-switch animation for every app.

1. **Active-display snapshot renderer** — current primary POC; uses One UI's own cover/inner handoff and applies our transition on the currently active surface.
2. **Presentation / multi-display experiment** — uses a second controlled internal display if Android/Samsung policy exposes one.
3. **Launcher implementation** — can make the home-screen fold transition feel native, but cannot replace transitions inside arbitrary apps.
4. **Snapshot / MediaProjection experiment** — can transform captured content, but introduces user consent, latency, secure-content exclusions, and privacy constraints.
5. **Accessibility overlay experiment** — useful for masks/fades, but still does not grant direct access to another app's live render surface.
6. **Root / SystemUI module** — closest to a true system-level replacement, but outside normal Play-distributable app capabilities.

The core transition engine remains independent of the integration layer so the calibrated shader/math can be reused by a launcher, MediaProjection prototype, or root/SystemUI implementation later.

## CI validation

GitHub Actions runs the pure transition unit tests before assembling each debug APK. The tests currently verify endpoint focus behavior, symmetry around the handoff, bounded adaptive calibration, and deterministic preview surface switching.

## Next calibration tasks

- Import matched cover and inner home-screen screenshots and calibrate their crop/geometry.
- Record the actual One UI opening and closing handoff angles over multiple cycles and validate the adaptive estimate.
- Measure blur strength versus distance from hinge against the iPhone Duo reference.
- Add velocity-aware damping so a fast snap-open does not visually lag behind the physical hinge.
- Investigate a launcher-hosted renderer after the in-app active-display handoff is visually convincing.
