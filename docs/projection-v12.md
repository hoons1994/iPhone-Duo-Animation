# v12: fixed-plane projection lab

This is an independent optical approximation. It is not Apple's implementation,
not a One UI replacement, and not yet a verified Galaxy Fold display transition.

## Changes

The active renderer no longer depends on the legacy focus window. It traces a
ray from an eye above the hinge through a tilted panel to a fixed content plane.
The stationary inner half and hinge keep identity coordinates. Perspective,
depth-dependent local blur, and shading apply only to the moving half. The inner
tilt remains `clamp(180 - hingeAngle, 0, 75)` up to full opening. The cover tilt
uses a separate bounded departure model. The 75-degree cap prevents attempting
to display a back-facing panel, and the 3.2-panel eye distance is a tunable model
assumption, not a measured device or viewer parameter.

The reference scene is a shared atlas with numbered colored targets and fine
lines. Its cover crop has explicit correspondences to the inner scene. Two
independently imported screenshots still have no feature registration: they are
rendered on their own surfaces without the old 50/50 ghosted cross-fade.

## Controls

Cold launch opens a manual 120-degree inner-panel demonstration. This is
intentional: no sensor, screen switch, screenshot import or permission is needed
to establish that the renderer is visible. There are 90/120/150/180 presets,
continuous manual scrubbing, an automatic reversible demo, original/effect A/B,
left/right moving-side selection, cover/inner manual override, optional sensor
mode, screenshot import, experimental Presentation and metadata trace export.
Manual interaction disconnects the sensor, so it cannot overwrite the preview.
Left/right labels refer to the natural device orientation. Rotation transforms
the hinge axis and side. Shared atlas coordinates are a diagnostic convention,
not automatic registration of differently shaped physical displays.

Sensor registration spans onStart/onStop rather than onResume/onPause. It is
still a foreground Activity, not a service. onStop stops sampling/animation and
cancels visibility compensation. A device can suspend delivery while off; this
change does not claim to override that behavior.

## Visibility compensation

An observed size-based physical cover/inner classification change can arm a
240-ms, maximum 28-degree residual tilt. The timer starts at an app draw while
the display reports ON, not at the earlier surface event. It only raises a
smaller physical tilt and never reduces a larger one. It is disabled in manual
mode and can be switched off in sensor mode. Cold launch does not trigger it.
This is explicitly synthetic timing compensation, not a physical hinge reading.
The first-draw timestamp is NOT proof of the first panel scanout. Surface
classification is still a size/aspect heuristic; unusual windows and devices
need actual foldable display metadata before production use.

## Verification

`FoldProjectionTest` covers endpoints, stationary pane/hinge, measurable
projection, late angles, axis/mirror symmetry, invalid input, finite travel,
cold startup, and reveal timing/cancellation. `tools/ProjectionCheck.kt` is an
additional Android-free sweep runnable with kotlinc and java.

`RuntimeShaderSmokeTest` now uses HardwareRenderer/RenderNode/ImageReader, checks
CPU/AGSL coordinate parity for both axes/sides and cover edges, cross-surface
atlas coordinates, changed vs unchanged patterned pixels, endpoint restoration,
and Activity cold startup. It writes 90/120/150/175/180-degree render frames.
CI preserves XML, logs and available frame files even on failure. These checks
must pass in a real run; their existence is not evidence that they passed.

Remaining real-device gates: exact panel-on/first-visible timing, complete raw
hinge delivery, slow/fast opening, mid-travel stop/reversal, rotation, locking,
second-display availability, full-resolution GPU frame pacing, and visual
comparison against a reference video. No 60/120-fps or system-wide success claim
is justified by the offscreen tests.

## Privacy and scope

No overlay/accessibility service, screen capture permission, network permission,
or automatic screenshot collection has been added. The bounded in-memory trace
holds angle/display/lifecycle/render metadata, at most 4096 rows; CSV export is
user initiated through the document picker. Imported screenshots are user
selected. Legacy tuning/calibration classes remain only for regression/history;
the new MainActivity does not use the old automatic handoff calibrator.

Android API references used during implementation:
- https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl
- https://developer.android.com/develop/ui/views/graphics/agsl/agsl-quick-reference
- https://developer.android.com/guide/topics/renderscript/migrate
