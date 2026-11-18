# Print filter spec (replaces the ColorMatrix + vignette + grain in ArtTreatment.java)

Reference implementation: `filter_ref.py` (same folder). All values below are what that file does.
Colour values are display-encoded 0..1 unless shown as 0..255.

## Settings
- **Surface:** `Paper` (matte rag, default) or `Canvas`.
- **Level:** Off / Light / Moderate / Strong = strength `s` = 0, 0.25, 0.5, 1.0 (unchanged from Settings.java).

## Pass 1: tone (once per slide, baked into the photo bitmap)
Parameters from `s`:

| name | formula | Light | Moderate | Strong |
|---|---|---|---|---|
| saturation `sat` | 1 - 0.20 s | 0.95 | 0.90 | 0.80 |
| paper white `W` | 1 - 0.18 s | 0.955 | 0.91 | 0.82 |
| paper colour `P` (R, G, B) | W, W(1 - 0.02 s), W(1 - 0.07 s) | .955 .950 .938 | .910 .901 .878 | .820 .804 .763 |
| ink black `k` (fraction of paper) | 0.10 s | 0.025 | 0.05 | 0.10 |
| roll-off `c` | 0.12 s | 0.03 | 0.06 | 0.12 |

Steps, per pixel:
1. Saturation: same as `ColorMatrix.setSaturation(sat)` (weights 0.213, 0.715, 0.072). Round and clamp to 0..255.
2. Per-channel lookup table, 256 entries each for R, G, B. For `x = i / 255`:
   - `f(x) = x - c * sin(2*pi*x) / (2*pi)`  (soft toe and shoulder, mid-tone slope 1 + c)
   - `lut_ch[i] = round(255 * P_ch * (k + (1 - k) * f(x)))`

Result: nothing is brighter than the paper, nothing darker than ink on paper, and mid-tone contrast is
kept (Strong: mid slope about 0.83 against 0.59 in the current filter, which is why the current one looks grey).

Android: build the three `int[256]` tables when the level changes. When a slide's bitmap is decoded,
`getPixels` → saturation in integer maths → table lookup → `setPixels`. Do it on the loading thread.
For the live preview in Settings, re-run on the current slide's unfiltered bitmap (keep it until the next slide).
Matrix-only fallback if a bake is not possible somewhere: drop `f` (use `f(x) = x`), i.e. after `setSaturation`
post-concat scale `P_ch * (1 - k)` and offset `255 * P_ch * k` per channel.

## Pass 2: surface texture (drawn once over the composed frame, after the cross-fade layers)
Two 512 x 512 grey tiles, generated once in code, `BitmapShader` with `REPEAT`, anchored to the screen
origin (not to the photo, so it does not move with the drift). Draw with `PorterDuff.Mode.OVERLAY`
and paint alpha as below. Both tiles: mean 128, standard deviation 25.5 (0.10), clamp 0..255.
Normalise as `v = 128 + 25.5 * (h - mean(h)) / std(h)`.

"Wrap-bilinear upscale by f" below means: sample at `(i + 0.5) / f - 0.5`, bilinear, indices wrap modulo the small size.

**Paper tile** (`Random` seed 7):
- `n1` = Gaussian noise, 512 x 512.
- `n2` = Gaussian noise 256 x 256, wrap-bilinear upscaled x2, divided by its own std.
- `n8` = Gaussian noise 64 x 64, wrap-bilinear upscaled x8, divided by its own std.
- `h = 1.0 * n1 + 0.8 * n2 + 0.12 * n8`

**Canvas tile** (seed 11), plain weave, 2 px threads, 4 px repeat, lit from the top-left:
- For pixel (x, y): `warpOnTop = ((x/2 + y/2) % 2 == 0)` (integer division), `px = x % 2`, `py = y % 2`.
- Bump value by `[py][px]`: warp on top `[[1.0, -0.4], [0.6, -1.0]]`; weft on top `[[1.0, 0.6], [-0.4, -1.0]]`.
- `m` = Gaussian noise 64 x 64, wrap-bilinear upscaled x8, divided by its std (uneven threads).
- `h = bump * clamp(1 + 0.45 * m, 0.3, 1.9) + 0.60 * gaussian()`

| overlay alpha | formula | Light | Moderate | Strong |
|---|---|---|---|---|
| Paper | 0.08 + 0.08 s | 0.10 | 0.12 | 0.16 |
| Canvas | 0.10 + 0.10 s | 0.125 | 0.15 | 0.20 |

Where to draw:
- Surface = Paper: paper tile over the whole 1920 x 1080 frame (photos and the paper-tone gaps).
- Surface = Canvas: canvas tile over each photo rect at the Canvas alpha; paper tile over the gaps at the Paper alpha.

OVERLAY fades to nothing at pure black and pure white and is strongest in mid-tones, so the mean brightness does not change.

## Pass 3: gallery light (replaces the radial vignette)
One `LinearGradient` over the whole frame, top to bottom, black with alpha:
0 from y = 0 to y = 0.20 H, then linear to `0.07 * s` at y = H (Light 1.75%, Moderate 3.5%, Strong 7%).
Normal SRC_OVER. No corner darkening.

## Removed
- Radial vignette (reads as a lens effect).
- 5% alpha-blended grey grain (it only greys the picture; OVERLAY texture replaces it).
- Mid-grey contrast reduction and the 0.84 gain (replaced by paper white / ink black end points).

## Cross-fade
Draw passes 2 and 3 once, on top of the blended old + new slide, so the texture stays still for the
whole 4 seconds. Do not bake texture into the slide bitmaps.

## Sizing note
One logical pixel is 2 x 2 panel pixels, about 1 mm on the 85" screen, roughly the finest detail the eye
resolves from 3 m. Paper tooth is per-pixel on purpose; canvas threads are 2 logical px (about 2 mm, a medium-coarse canvas).
Do not scale the tiles.
