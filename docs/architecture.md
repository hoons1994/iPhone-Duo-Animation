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
participates in the live fold effect. App, widget, and wallpaper creation actions
appear in edit mode. Wallpaper selection uses `ACTION_OPEN_DOCUMENT` and a
persisted grant for the chosen URI; decoding is bounded and performed off the
UI thread. The user can restore the default background. This requires access
only to the selected document, with no broad storage permission.

Long-pressing an app starts a drag. A short drop reorders it; pausing over an app
creates a folder or adds the app to an existing folder. Folder dialogs use an
icon grid and support opening, extracting, removing, renaming, or ungrouping
apps. The four-slot Dock is shared across pages and accepts apps from the app
drawer or by drag-and-drop. Widgets can be moved between pages, removed, and
given a height preset. The launcher passes the chosen dimensions to the widget
provider and sizes its host frame to match; individual providers can still
choose how to respond to those options.

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

`LiveFoldEffects` builds four input branches: sharp content and native Gaussian
blurs at one ninth, one third, and the full maximum blur radius. `LiveFoldShader`
uses the local glass-to-plane gap to weight adjacent levels. The branches are
combined by adding their weighted premultiplied colors. The fixed inner pane
uses the sharp branch; the moving pane uses ray-plane projection, interpolated
frost, and distance-based darkening. The optical model follows the stationary
interface plane and moving glass described by
[`Atomicx7/Duo-animation`](https://github.com/Atomicx7/Duo-animation).

The launcher path does not call `View.draw()` into a bitmap, read pixels back
to the CPU, generate cached mipmaps, or schedule idle captures. Android still
uses GPU render targets for the effect and its blur passes. This change removes
the app's frozen-frame lifecycle; it does not remove the cost of rendering and
filtering the launcher.

`HingeAngleMonitor` selects the finest usable public or vendor hinge sensor. The
filtered angle advances the glass model through `Choreographer`; stepped sensors
are eased between their reported stops. `HandoffCalibrator` keeps separate
opening and closing estimates when the activity observes a recent physical
display geometry change. `LiveFoldLayout` updates shader uniforms on display
frames while its short angle follower is moving. Once it settles, the fold frame
loop stops; normal view invalidation still updates live content. At the clear
endpoints, the layout removes its `RenderEffect` entirely.

Touch coordinates follow the displayed glass projection. During Android's
system drag-and-drop, the fold effect temporarily clears so its drag shadow and
drop targets share untransformed coordinates, then resumes at the current angle.
Fine sensors finish converging to their last real sample even when an on-change
sensor stops emitting. Those settling frames do not refresh calibration age.

The input mechanism is documented in Android's
[AGSL guide](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl)
and [RenderEffect reference](https://developer.android.com/reference/android/graphics/RenderEffect).
`SnapshotTransitionView`, `SnapshotTransitionShader`, and `SnapshotMipmaps`
remain as legacy diagnostic code; `HomeActivity` does not use them for its fold
effect.

```text
wallpaper + launcher views → live RenderNode input → blur branches → AGSL projection
hinge events → filtered angle → frame follower → glass, blur, and handoff
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
- Notification badges, configurable launcher gestures, and Dock layout
  customization are not implemented; the Dock currently has four fixed slots.

## Device validation still required

The production fold shader compiled and passed 32 desktop Skia pixel states
across four rotations. That check covers projection, blur weights, opacity,
fixed-pane sharpness, and clear endpoints with generated inputs. It does not
execute Android's `RenderEffect` graph, validate glass-panel appearance, or
measure performance. Android GPU instrumentation remains unrun because no ADB
device or emulator was available.

APK compilation does not establish widget-provider compatibility, physical
display handoff timing, blur composition, or GPU frame pacing. Native live blur
uses additional render passes, so its performance must be measured on the target
device; removing capture is not evidence of higher frame rates.
Those require selecting Duo Home on a book-style Fold and exercising both cover
and inner screens while folding. The reference effect has not been verified as
pixel-identical on a physical device.
