# Third-party notices

The current and legacy projected-glass implementations were adapted from or
informed by the following MIT-licensed projects. These notices record source
attribution, not verified visual equivalence.

- `chuspeeism/iphone-duo` — https://github.com/chuspeeism/iphone-duo
  Copyright (c) 2026 jadon7
  The 0.6.1 live `DuoFoldModel` and `LiveFoldGeometry` use scene-scale eye
  ratios and hinge-anchored projection informed by
  [`main.js`](https://github.com/chuspeeism/iphone-duo/blob/main/main.js).
  The source-space blur scale and binomial kernel inform the native Gaussian
  approximation. Apple model and UI assets from the reference are not bundled.
  In 0.6.6 the scene eye ratios are replaced by density-based optics. The relative
  width of its flexible hinge informs Duo Home's smooth one-sided hinge strip;
  this strip uses a smoothstep integral, not the original Hermite mesh.
- `joeconsorti/duo-fold-live` — https://github.com/joeconsorti/duo-fold-live
  Copyright (c) 2026 bunkaich
  The 0.6.1 live model adapts the projection cap (`0.97 * 90 = 87.3` degrees),
  smoothstep motion, and material-space `pow(edge, 1.35)` frost/darkening from
  [`ClassicGlassShader.kt`](https://github.com/joeconsorti/duo-fold-live/blob/main/app/src/main/java/org/duofold/live/ClassicGlassShader.kt).
  The live path uses native Gaussian levels rather than the reference's exact
  5x5 binomial and mip filter. Its radius conversion approximates that kernel's
  variance; it does not claim identical filtering.
  Version 0.6.6 replaces this near-edge-on projection and edge-power frost model
  while retaining the Gaussian level infrastructure and source-space blur limit.
  The legacy half-resolution SurfaceView hardware-buffer and frame scheduling approach
  was adapted from `app/src/main/java/org/duofold/live/DuoGlass.kt` at commit
  `d10c7ef550424eb2182f3592ef580264f6d63e4f`.
  Its adjacent-mip interpolation approach also informed the legacy cached mip pyramid
  used by `SnapshotMipmaps` and `SnapshotTransitionShader` (seven levels and
  the weighted 5x5 kernel).
- `marcoazeem/duo-open` — https://github.com/marcoazeem/duo-open
  Copyright (c) 2026 marcoazeem
  The legacy snapshot pixel-space eye/glass/plane intersection, 45-degree virtual tilt limit,
  density-aware 450 mm eye distance, gap-based blur/darkening, and 45 ms overlay
  follower were adapted from commit
  `8632ff61cd0a7780e97646f567456194cc6f6b21`.
  Later snapshot tuning changed eye distance and follower values. These earlier
  parameters do not govern the 0.6.1 live launcher model. In 0.6.6,
  [`DuoShader.kt`](https://github.com/marcoazeem/duo-open/blob/main/app/src/main/java/com/duoopen/fold/DuoShader.kt)
  informs the visible-hinge-range to 45-degree optical tilt mapping. Duo Home
  uses its learned handoff, adds smoothstep endpoints, and uses a 320 mm eye distance.
- `elijah-semyonov/DuoLikeAnimation` — https://github.com/elijah-semyonov/DuoLikeAnimation
  Copyright (c) 2026 Elijah Semyonov
  Version 0.6.6 draws its fixed UI plane, glass-to-plane gap frost, density-normalized
  darkening, and 320 mm eye distance from
  [`DuoFold.metal`](https://github.com/elijah-semyonov/DuoLikeAnimation/blob/main/DuoLikeAnimation/Shaders/DuoFold.metal)
  and `FoldEffect.swift`. The Gaussian-level filter differs from its Vogel disk
  sampling. The Swift implementation uses device attitude; Duo Home's 45-degree
  physical hinge mapping comes from the Android adaptations.

The following projects were also studied to compare Android hinge mapping,
two-pane geometry, and AGSL blur behavior:

- `Vyom-2007/DuoFoldWallpaper` — https://github.com/Vyom-2007/DuoFoldWallpaper
- `Atomicx7/Duo-animation` — https://github.com/Atomicx7/Duo-animation
  Its fixed-interface-plane optical model is the source model used by the
  `duo-open` two-pane adaptation. Its `duo_fold.agsl` also informed the 0.6.6
  gap-based live optics and comparison of the Android 45-degree tilt bound.
- `narayann7/duo_animation` — https://github.com/narayann7/duo_animation

## MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notices and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
