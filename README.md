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
   the Dock, or open app info. The drawer is part of the home view tree and
   follows the same fold effect.
4. Tap **편집** to show **홈에 앱 추가**, **위젯 추가**, and **배경화면**.
   App selection stays open while you pin several apps. Choose a photo for the
   home wallpaper through Android's document picker, or restore Duo's default
   background. The selected photo is remembered without broad photo-library
   permission. Tap **완료** to finish editing.
5. Swipe sideways between the three home pages. Long-press and drag an app to
   move it; pause over another app to make a folder. Tap a folder to open it,
   then use **편집** to rename or unpack it. The four-slot Dock stays visible
   across pages. Long-press a widget to move it, choose a height preset, or
   remove it.

Duo Home owns its wallpaper-backed home screen, shortcut list, widget host, and
app drawer. One UI Home's icon arrangement and widgets cannot be imported, so
place those again in Duo Home. Choose the previous launcher in Android's Home app
settings to switch back.

## How it works

```text
Duo Home views → live RenderEffect blur levels → AGSL glass projection
physical hinge → filtered angle → per-frame transition
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
- Four live levels—sharp plus three native Gaussian blurs—are interpolated by
  the local glass-to-plane distance. An AGSL shader projects the current content
  through the tilted glass and applies the corresponding frost and darkening.
- The hinge angle drives the optical model and handoff tuning. A short frame
  follower bridges sensor updates. Resolved endpoints remove the effect, and
  fold frame callbacks stop once the angle settles.
- No accessibility service, screen-capture permission, or gesture injection is
  used.

## Glass controls

The search pill, Dock, and app drawer use `LiquidGlassPanel`: a blurred,
wallpaper-aligned backdrop beneath crisp icons and text, with translucent tint
and a light rim. Small controls use a matching tint and rim. The setup screen
keeps solid, readable cards and capsule actions.

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
  icon reordering, and a four-slot Dock with widget placement and height presets.
  It cannot import another launcher's layout and does not yet provide
  notification badges or configurable launcher gesture settings. Some widget
  providers may ignore the requested size options.
- The effect covers Duo Home's content. It does not transform the system
  lockscreen or other apps.
- Live rendering still needs GPU layers and blur passes. Real display handoff,
  widget updates during folding, and frame pacing need evaluation on a Fold
  device; removing bitmap capture does not establish a performance improvement.
- The production fold shader compiled and passed 32 desktop Skia pixel states
  across four rotations using `tools/check_live_shader.py`. Android GPU tests
  remain unrun: no ADB device or emulator was available. See
  [`docs/blur-validation.md`](docs/blur-validation.md) for the scope of this check.

The transition work references [`Atomicx7/Duo-animation`](https://github.com/Atomicx7/Duo-animation),
[`chuspeeism/iphone-duo`](https://github.com/chuspeeism/iphone-duo),
[`joeconsorti/duo-fold-live`](https://github.com/joeconsorti/duo-fold-live), and
the other repositories listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
The live view input follows Android's
[AGSL RenderEffect API](https://developer.android.com/develop/ui/views/graphics/agsl/using-agsl).

Architecture notes: [`docs/architecture.md`](docs/architecture.md).
