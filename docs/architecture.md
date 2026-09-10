# Architecture notes

## What we are recreating

The iPhone Duo transition appears to treat fold progress as a continuous visual state instead of waiting for a binary open/closed event. Public hands-on reporting describes content gradually coming into focus while the phone opens and closes. Early recreations also point to a spatially progressive blur: the effect is not equally strong at every pixel.

Our first approximation therefore uses this model:

```
physical hinge angle
      ↓
low-pass smoothing
      ↓
normalized progress (0..1)
      ↓
transition peak = sin(progress × π)
      ↓
spatial mask (hinge → outer edge)
      ↓
AGSL blur + subtle focus/scale
```

The sine-shaped peak is deliberate: both fully closed and fully open endpoints should be sharp, while the strongest defocus occurs between them.

## Renderer

`DuoShader` is intentionally parameterized. The shader currently exposes:

- `progress`: normalized hinge progress
- `opening`: direction hint
- `maxBlurPx`: peak blur strength
- `scaleDip`: midpoint scale/focus adjustment
- `resolution`: current rendering surface dimensions

The shader uses a lightweight 9-tap blur. This is not expected to be the final kernel; it is chosen so the first device test can answer the more important question: whether the spatial blur distribution and hinge coupling feel correct.

## Sensor path

`HingeAngleMonitor` reads `Sensor.TYPE_HINGE_ANGLE`, clamps the book-style fold range to 0–180°, applies an exponential low-pass filter, and derives opening/closing direction with a small deadband.

Sensor smoothing is kept outside the renderer so we can later replace it with a predictive or velocity-aware filter without changing shader code.

## Why the first build is an in-app demo

A regular Android application does not own the system compositor or Samsung's fold/unfold transition. It cannot simply replace SystemUI's screen-switch animation for every app.

There are several increasingly invasive paths we can investigate after the renderer looks right:

1. **In-app renderer** — highest fidelity and lowest risk; validates the visual model.
2. **Presentation / multi-display experiment** — render controlled surfaces on available internal displays where Android/Samsung policy permits it.
3. **Launcher implementation** — can make the home-screen fold transition feel native, but cannot replace transitions inside arbitrary apps.
4. **Snapshot / MediaProjection experiment** — can transform captured content, but introduces user consent, latency, secure-content exclusions, and privacy constraints.
5. **Accessibility overlay experiment** — useful for masks/fades, but still does not grant direct access to another app's live render surface.
6. **Root / SystemUI module** — closest to a true system-level replacement, but outside normal Play-distributable app capabilities.

The project should keep the core transition engine independent of whichever integration path proves viable.

## Immediate calibration tasks

- Record the reference animation at high frame rate from several public hands-on clips.
- Measure blur strength versus distance from hinge at several fold angles.
- Check whether the blur maximum is actually at ~90° or shifted toward one endpoint.
- Measure any simultaneous scale, opacity, translation, or layout morph.
- Tune opening and closing separately if their curves differ.
- Test sensor latency and event cadence on a Samsung Galaxy Z Fold device.
