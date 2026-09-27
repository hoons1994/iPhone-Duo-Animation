# Duo Home · iPhone Duo Animation

An Android launcher prototype for the folding frosted-glass transition inspired by
the iPhone Duo animation studies. The effect follows the physical hinge and
renders Duo Home's current views directly through Android's graphics pipeline.

## Use

1. Install `app/build/outputs/apk/debug/app-debug.apk`.
2. Open **Duo Home** and select **Duo Home을 기본 홈 앱으로 선택**. Android asks
   you to confirm the default home app.
3. Tap the **앱 검색** pill to open the app drawer. Search or browse its icon
   grid, tap an app to open it, or long-press for actions to pin it, add it to
   the Dock, or open app info. The drawer is a full-viewport rectangle with no
   rounded outer corners. It is part of the home view tree and follows the same
   fold effect.
4. Tap **편집** to show **홈에 앱 추가**, **위젯 추가**, and **배경화면**.
   App selection stays open while you pin several apps. Choose a photo for the
   home wallpaper through Android's document picker, or restore Duo's default
   background. The selected photo is remembered without broad photo-library
   permission. Tap **완료** to finish editing.
5. Swipe sideways between the three home pages. A normal long press on an app
   or folder opens its glass action menu. Choose **홈 화면 편집**, then long-press
   and drag the item to move it; pause over another app to make a folder. Tap a
   folder to open its glass panel, then use **편집** to rename or unpack it.
   Long-press a widget to open its glass menu for page, size, and removal actions.
6. Long-press a Dock item in normal mode, or tap it while editing, to open its glass menu.
   Use **다른 앱으로 교체**, **왼쪽으로 이동**, **오른쪽으로 이동**, or
   **독에서 빼기 · 현재 홈으로**. All four occupied slots can still be replaced.
   Replacing an item preserves it on a home page; choosing another Dock app
   swaps their positions. Folders retain their contents when moved out of the Dock.

Duo Home owns its wallpaper-backed home screen, shortcut list, widget host, and
app drawer. One UI Home's icon arrangement and widgets cannot be imported, so
place those again in Duo Home. Choose the previous launcher in Android's Home app
settings to switch back.

## How it works

```text
Duo Home views → live RenderEffect blur levels → AGSL glass projection
physical hinge → filtered angle → eased optical pose → pre-draw commit
```

- `HomeActivity` is declared as an Android `HOME` activity. The setup activity
  requests the system's `ROLE_HOME` choice; it does not silently replace the
  current launcher.
- The home activity renders the current wallpaper, pinned launcher activities,
  and `AppWidgetHost` views. Shortcut and widget IDs are stored in app-private
  preferences.
- `LiveFoldLayout` applies a `RenderEffect` to the launcher view tree. Android
  supplies the current view and child contents, so clock and widget updates can
  remain visible while folding. There is no app-managed bitmap capture, readback,
  idle capture timer, or mipmap worker in the home rendering path.
- Version 0.6.7 keeps the hinge mapping and smooth optical pose from 0.6.6, then
  replaces its blur renderer with one live 32-tap Vogel disk pass. The disk
  radius is now used as a disk radius; the previous Gaussian renderer treated
  it as sigma and spread the blur about twice as far. Its fixed tap pattern
  avoids relocating the sample set as the hinge moves. On-screen grain and GPU
  frame cost still need a Fold-device check.
- Version 0.6.6 maps each display's visible hinge range to an optical tilt of
  0–45 degrees, with cubic smoothstep easing. The cover rises from clear at
  6 degrees to maximum tilt at the learned handoff; the inner pane resolves
  from that handoff to clear at 172 degrees. The default handoff is 98 degrees.
  Its calibration is held through a gesture, including direction reversals,
  and a new value is adopted only at a clear endpoint or while rendering is inactive.
- A smooth strip beside the hinge, about 4.43% of one pane's width, joins the
  fixed and moving geometry. Frost now follows the glass-to-interface gap:
  `min(maxBlur, 0.12 * gap)`. Perspective uses an eye at the display center,
  320 mm away, with a minimum distance of twice the pane width. This replaces
  the earlier rigid 87.3-degree projection and material-edge frost envelope.
- The first visible draw waits for a current-session hinge sample, with a 120 ms
  endpoint fallback if no fresh reading arrives. Handoff calibration uses the
  sensor event's measurement time instead of its delivery time.
- `HingeAngleMonitor` owns angle filtering and settling. `LiveFoldLayout`
  commits the latest result once before drawing, without a second 16 ms follower.
  Resolved endpoints remove the effect. Normal activity resumes and display
  configuration changes reuse attached icons and widget hosts; package/provider
  changes trigger refreshes as needed.
- Sensor selection favors streams observed reporting intermediate angles.
  Learned sensor quality survives the monitor's stop/start cycle; stop-only
  jumps and source changes are eased from the previous filtered pose. Resuming
  the home screen waits for a fresh sensor sample before showing the fold effect.
  Between fine-sensor samples, settling continues with the latest adaptive
  response time so sampling and display frames follow the same response.
- No accessibility service, screen-capture permission, or gesture injection is
  used.

## Glass controls

The search pill, Dock, and app drawer use `LiquidGlassPanel`: a blurred,
wallpaper-aligned backdrop beneath crisp icons and text, with translucent tint
and a light rim. Small controls use a matching tint and rim. The setup screen
keeps solid, readable cards and capsule actions.
The 0.6.4 drawer fills the viewport with a rectangular, unrounded boundary; its glass
backdrop and internal controls remain part of the launcher.

In 0.6.3, `GlassActionOverlay` gives app shortcuts, Dock editing, folders,
widget settings, app-drawer actions, and wallpaper actions the same glass menu
style. Menus open near the selected item when space permits, with icons,
separators, a close button, and scrolling for longer content. Dock ordering
appears as left/right controls, and the full-Dock replacement picker shows its
four slots. Folder contents and renaming also use the glass panel.

Android's default-home selection, widget picker and permission/configuration
screens, photo document picker, and app-info screen remain system/provider UI.

The material treatment is inspired by Apple's
[Materials guidelines](https://developer.apple.com/design/human-interface-guidelines/materials).
This is an Android implementation of an iPhone-inspired appearance; it does not
use Apple's Liquid Glass framework or establish pixel-identical rendering.
The panels reuse the launcher's wallpaper source without capturing the screen.

## Requirements and current limits

- Android 13 or later; min API 33, compile/target API 37.
- A book-style foldable exposing `Sensor.TYPE_HINGE_ANGLE` or a compatible
  vendor hinge sensor.
- Select Duo Home as the system home app before using its home screen.
- Duo Home has three home pages, searchable app browsing, home-screen folders,
  icon reordering, an editable four-slot Dock, and widget placement and height presets.
  It cannot import another launcher's layout and does not yet provide
  notification badges or configurable launcher gesture settings. Some widget
  providers may ignore the requested size options.
- The effect covers Duo Home's content. It does not transform the system
  lockscreen or other apps.
- Live rendering still needs GPU layers and blur passes. Real display handoff,
  widget updates during folding, and frame pacing need evaluation on a Fold
  device; removing bitmap capture does not establish a performance improvement.
- Version 0.6.0 passed 56 JVM tests and 32 desktop shader pixel states. Those are
  historical results. Version 0.6.7's debug APK build completed successfully;
  no tests, runtime AGSL checks, or device interaction checks have been run for
  its shader and lifecycle changes. Android GPU performance and physical-device
  fidelity remain unverified. See
  [`docs/blur-validation.md`](docs/blur-validation.md).

The transition work references [`Atomicx7/Duo-animation`](https://github.com/Atomicx7/Duo-animation),
[`chuspeeism/iphone-duo`](https://github.com/chuspeeism/iphone-duo),
[`joeconsorti/duo-fold-live`](https://github.com/joeconsorti/duo-fold-live), and
the other repositories listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
The live view input follows Android's
[AGSL RenderEffect API](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl).
The current angle-range mapping draws on Android's
[`duo-open/DuoShader.kt`](https://github.com/marcoazeem/duo-open/blob/main/app/src/main/java/com/duoopen/fold/DuoShader.kt).
Gap-based optics draw on
[`Atomicx7/duo_fold.agsl`](https://github.com/Atomicx7/Duo-animation/blob/master/app/src/main/res/raw/duo_fold.agsl)
and DuoLikeAnimation's
[`DuoFold.metal`](https://github.com/elijah-semyonov/DuoLikeAnimation/blob/main/DuoLikeAnimation/Shaders/DuoFold.metal)
and [`FoldEffect.swift`](https://github.com/elijah-semyonov/DuoLikeAnimation/blob/main/DuoLikeAnimation/FoldEffect.swift).
The 45-degree range is an Android mapping choice, not a cap imposed by that
Swift implementation. The hinge-strip width is inspired by
[`iphone-duo/main.js`](https://github.com/chuspeeism/iphone-duo/blob/main/main.js);
Duo Home's smooth strip does not reproduce its Hermite mesh deformation exactly.

Architecture notes: [`docs/architecture.md`](docs/architecture.md).
