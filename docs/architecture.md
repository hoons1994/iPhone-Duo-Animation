# Duo Home launcher architecture

## Product structure

`MainActivity` is the install/setup entry point. It requests `RoleManager.ROLE_HOME`
through Android's system picker and reports whether Duo Home currently owns that
role. `HomeActivity` is the `MAIN` / `HOME` / `DEFAULT` activity launched by the
system when the user presses Home. Setup also offers an explicit `HomeActivity`
preview before the role is selected. Picker and preview actions prevent repeated
launches, unavailable role pickers fall back to the system home settings, and
the sensor status uses the same public/vendor discovery as the launcher.

The app deliberately has its own three-page workspace. It draws a bundled
wallpaper or a selected photo, restores pinned launcher activities, hosts Android
widgets with `AppWidgetHost`, and offers a searchable app drawer with an icon grid. Shortcut
entries store a page number; widget entries store a page and requested height.
Home folders store a title and their app components in private preferences.
These settings migrate the earlier single-page formats on load. An existing
launcher's icon and widget layout is not accessible as a transferable launcher
database and is not copied.

The search pill opens the app drawer inside the home view tree, so the drawer
participates in the live fold effect. In 0.6.4, its `LiquidGlassPanel` has zero
outer corner radius and fills the viewport as a rectangle. App, widget, and wallpaper creation actions
appear in edit mode. Wallpaper selection uses `ACTION_OPEN_DOCUMENT` and a
persisted grant for the chosen URI; decoding is bounded and performed off the
UI thread. The user can restore the default background. This requires access
only to the selected document, with no broad storage permission.

In normal home mode, long-pressing an app or folder opens its glass action menu.
After entering home edit mode, a long press starts dragging. A short drop
reorders the item; pausing over an app creates a folder or adds it to an existing
folder. Folder glass panels use an icon grid and support opening, extracting,
removing, renaming, or ungrouping apps. The four-slot Dock is shared across pages and accepts apps from the app
drawer or by drag-and-drop. Widgets can be moved between pages, removed, and
given a height preset. The launcher passes the chosen dimensions to the widget
provider and sizes its host frame to match; individual providers can still
choose how to respond to those options.

Dock items expose a glass management menu on a normal long press or an
edit-mode tap. The menu supports app replacement, one-position left/right
ordering, and moving an entry to a home page. A full Dock offers a target-slot
picker when another app is added. Replacement keeps the displaced entry: it
takes the incoming pinned app's previous location, or goes to the current home
page if the incoming app was not pinned. Choosing another Dock app swaps the
two slots. Folder entries preserve their contents when moved or displaced and
also offer rename and ungroup actions. These edits persist in the existing
shortcut/folder preferences. In 0.6.3, left/right ordering uses two compact
controls within the panel, and the full-Dock replacement picker displays the
slot choices with icons.

The interaction model takes cues from the separate workspace, drag-and-drop,
folder, app-drawer, and widget areas in [AOSP Launcher3](https://android.googlesource.com/platform/packages/apps/Launcher3/+/f6ba9499de/src/com/android/launcher3/).
The app-grid and Dock presentation also follows common launcher patterns
documented in [Lawnchair's release notes](https://github.com/lawnchairlauncher/lawnchair/releases/tag/v15.0.0-beta1).

## Glass controls and content

`LiquidGlassPanel` supplies the search pill, Dock, and drawer with a blurred
backdrop aligned to the home wallpaper. The backdrop reuses the wallpaper source,
while foreground icons and text are drawn sharply. Tint and a subtle rim define
the panel boundary; smaller controls use a matching rim and tint treatment.
This does not sample or capture other apps or the screen, and it does not blur
arbitrary sibling launcher views behind the panel.

Version 0.6.3 introduces `GlassActionOverlay` for launcher-owned menus and compact
panels: shortcut actions, Dock management, folder contents and renaming, widget
size/page/removal actions, app-drawer item actions, and wallpaper actions.
Each overlay is attached to `homeContent`, reuses `LiquidGlassPanel`, and offers
icon-labelled actions with separators, a dismissing scrim, and an explicit close
control. Placement follows an available item anchor; unanchored content uses a
compact panel centered horizontally above the bottom safe area. The panel's scroll area is bounded by system bars,
display cutouts, and the keyboard.

`HomeActivity` tracks open panels in a menu stack. Back dismisses the top panel;
Home and activity teardown dismiss the stack and cancel queued menu actions.
Viewport changes reflow the open panel while retaining its content. A selected action runs after its panel closes,
avoiding overlap when it opens another panel or the app drawer. The overlay
saves surrounding views' accessibility importance, hides those siblings while
open, and restores their values and eligible focus when dismissed. These
behaviors are implemented but have not been verified on a device.

The system default-home picker, widget picker/binding/configuration screens,
document picker, and app-info screen stay under Android or provider control.
They are not replaced by `GlassActionOverlay`.

The setup screen uses readable solid cards, an adaptive Duo app icon, mint
accents, and capsule ripple buttons. Its content respects system bar and cutout
insets and limits line width on the inner display. The separation between glass
controls and ordinary content takes cues from Apple's
[Materials guidelines](https://developer.apple.com/design/human-interface-guidelines/materials).
These are Android graphics and view implementations inspired by the iPhone
appearance; they do not invoke Apple's Liquid Glass framework and have not been
validated as a pixel-identical reproduction.

## Home rendering and fold effect

`HomeActivity` places the wallpaper and launcher views inside `LiveFoldLayout`,
a `FrameLayout` that applies a live `RenderEffect`. Android provides that view's
current `RenderNode` contents, including ordinary child views, as the shader
input. Launcher redraws and widget updates can therefore reach the folded pane
without waiting for an app-managed snapshot refresh.

Version 0.6.5 refines the live blur levels while retaining the material radius,
fold timing, projection geometry, sensors, and maximum outer-edge blur from 0.6.4.
The renderer uses `DuoFoldModel`, independently of the legacy
`TransitionTuning` snapshot model. Given hinge angle `h`, cover bend is `h` and
inner bend is `180 - h`. Neither a learned display-switch angle nor a change of
opening/closing direction changes this mapping. `LiveFoldGeometry` shares the
resulting projection with touch hit testing. The eye is aligned with the hinge
on both surfaces, including the cover's hinge-side edge. Eye distance scales
with the active axis extent using ratios derived from the scene dimensions in
[`iphone-duo/main.js`](https://github.com/chuspeeism/iphone-duo/blob/main/main.js),
replacing the live renderer's earlier fixed millimeter calibration.

Only the projection angle is capped at 87.3 degrees, corresponding to the
`0.97 * 90` limit in
[`ClassicGlassShader.kt`](https://github.com/joeconsorti/duo-fold-live/blob/main/app/src/main/java/org/duofold/live/ClassicGlassShader.kt).
The material envelope still reaches one at a 90-degree bend. The shader
computes `motion = smoothstep(0, 1, clamp(bend / 90, 0, 1))` and scales frost
by `motion * pow(edge, 1.35)`, where `edge` is distance from the hinge normalized
by moving-pane width. Darkening uses a similar outward envelope. Keeping these
values in pane coordinates prevents the historical loss of frost when texture
coordinates compress near edge-on projection. This follows the Classic Android
adaptation; the browser study in `main.js` measures its gradient in projected
`sourceUV` coordinates, so the two implementations are not identical.

The current radius is `maxBlurPx * motion * pow(edge, 1.35)`. The extra
`max(1, 1 / J)` projection-magnification gain from 0.6.2/0.6.3 was removed in
0.6.4. Source-space blur already expands through the projection; multiplying
its radius again added excess near-hinge blur. That extra gain was a Duo Home
adaptation and is absent from the inspected reference formulas.

`DuoFoldModel.blurLevels()` supplies up to six source-space levels. With maximum
radius `R`, it defines `coarse = R/9` and `fine = min(1, coarse/3)`, then uses
`[0, fine, sqrt(fine * coarse), coarse, R/3, R]`. Positive levels respect the
native filter's minimum effective sigma of approximately 0.505774 source pixels;
equivalent levels are deduplicated, so very small viewports may use fewer than
six. `LiveFoldEffects` converts these targets to native radius with
`max(0.01, (targetRadius - 0.5) / 0.57735)`.

Each shader branch receives its lower, current, and upper radius through
`radiusStops`, then computes adjacent-level weights in squared-radius space.
This keeps the requested second moment while narrowing the interval that mixes
in an unblurred source. On normal Fold dimensions, the first positive level is
one source pixel; requests at or above that level have zero sharp-branch weight
on the moving pane. The fixed inner pane always uses the sharp branch.
Unreachable levels are pruned using the unchanged `R * motion` bound, and the
remaining contributions are added as weighted premultiplied colors.

The earlier four-level graph could retain a strong sharp component despite
matching the requested variance: for `R = 72` and requested sigma 3, mixing 0
and `R/9 = 8` assigns 85.9% to the sharp source. Projection near the angle cap can
stretch those letter strokes into long strips. The two finer levels address
this source mixture without restoring the removed magnification gain. Native
Gaussian mixtures still differ from the references' 25-tap binomial kernel and
mip sampling. Up to two extra blur passes can increase GPU work; their visual
result and device frame pacing have not been measured.

The launcher path does not call `View.draw()` into a bitmap, read pixels back
to the CPU, generate cached mipmaps, or schedule idle captures. Android still
uses GPU render targets for the effect and its blur passes. This change removes
the app's frozen-frame lifecycle; it does not remove the cost of rendering and
filtering the launcher.

`HingeAngleMonitor` ranks observed sensor streams before advertised resolution:
a stream with intermediate readings outranks an unknown or stop-only stream.
Quality observations survive stop/start on the same monitor, while registration,
sample freshness, and filter state reset. Repeated stationary endpoint readings
alone do not mark a sensor as stepped. A first observed jump between distinct
0/90/180-degree stops enables the coarse follower immediately. Source or
fine/coarse-mode changes bridge from the previous filtered pose; ordinary fine
sensor samples continue through the existing fine filter.

The filtered angle advances the glass model through `Choreographer`. On resume,
`HomeActivity` keeps fold rendering inactive until the first real sensor sample
installs a fresh pose, avoiding reuse of the angle from before stop. Synthetic
settling frames cannot open this gate. `HandoffCalibrator` still records
opening and closing display-switch observations, but the current optical model
does not use those estimates to stretch or shorten its angle range.
`LiveFoldLayout` updates shader uniforms on display
frames while its short angle follower is moving. Once it settles, the fold frame
loop stops; normal view invalidation still updates live content. At the clear
endpoints, the layout removes its `RenderEffect` entirely.

When the viewport changes, `HomeActivity` reflows the attached icon views and
widget hosts. A pre-draw pass updates page and grid widths, restores the selected
page after layout settles, and then updates hosted widget size options. It does
not reload icons or reinflate widget hosts in `onConfigurationChanged`. Wallpaper
decode and geometry refresh remain responsive to the new viewport. This reduces
avoidable work at handoff; device frame pacing has not been measured.

Touch coordinates follow the displayed glass projection. During Android's
system drag-and-drop, the fold effect temporarily clears so its drag shadow and
drop targets share untransformed coordinates, then resumes at the current angle.
Fine sensors finish converging to their last real sample even when an on-change
sensor stops emitting. In 0.6.2, these settling frames retain the adaptive time
constant from the latest sensor update. This keeps their response consistent
with the real samples, including during fast motion. Settling frames do not
refresh calibration age or the raw sample's velocity baseline.

The input mechanism is documented in Android's
[AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)
and [RenderEffect reference](https://developer.android.com/reference/android/graphics/RenderEffect).
`SnapshotTransitionView`, `SnapshotTransitionShader`, and `SnapshotMipmaps`
remain as legacy diagnostic code; `HomeActivity` does not use them for its fold
effect.

```text
wallpaper + launcher views → live RenderNode input → blur branches → AGSL projection
hinge events → filtered angle → frame follower → physical bend, frost, and projection
display geometry → view reflow + display classification (independent of optical angle)
```

## Runtime boundaries

- No accessibility service, `MediaProjection`, broad storage permission, network access,
  or synthetic touch injection is required for the launcher transition.
- The effect receives only Duo Home's view contents. It cannot transform One UI,
  another application, or the system lockscreen.
- The live shader uses Android's rendering input without app-managed screen
  capture or bitmap readback. Separately composited surfaces are outside the
  ordinary child-view rendering path.
- Launcher preferences contain shortcut component names, page numbers, folder
  labels and contents, widget IDs, requested widget heights, and an optional
  selected wallpaper URI; they contain no screenshots.
- Widget sizing uses fixed height presets rather than a drag handle. Provider
  compatibility and actual rendered dimensions need device validation.
- Notification badges and configurable launcher gestures are not implemented.
  Dock contents and ordering are editable, while its capacity remains four slots.

## Device validation still required

The previous 0.6.0 implementation passed 56 JVM tests and 32 desktop Skia pixel
states across four rotations. Versions 0.6.1–0.6.4 later completed APK builds.
These are historical results. The 0.6.5 APK build completed on 2026-09-26;
tests and device interaction checks have not been run for its finer blur levels
and branch wiring.
Desktop checks cannot
execute Android's `RenderEffect` graph, validate glass-panel appearance, or
measure device performance. Android GPU instrumentation remains unrun; no ADB
device or emulator was available during the earlier checks.
AGSL is compiled at runtime, so the APK build does not validate its GPU execution.

APK compilation does not establish widget-provider compatibility, physical
display handoff timing, blur composition, or GPU frame pacing. Native live blur
uses additional render passes, so its performance must be measured on the target
device; removing capture is not evidence of higher frame rates.
Those require selecting Duo Home on a book-style Fold and exercising both cover
and inner screens while folding. The reference effect has not been verified as
pixel-identical on a physical device.
