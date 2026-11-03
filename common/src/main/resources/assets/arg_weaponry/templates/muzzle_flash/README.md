# Muzzle-flash asset template

Everything a creator needs to author a muzzle flash for ARG Weaponry, plus the placeholder sheet the
mod ships as a working example.

| File | Purpose | Packaged into the mod jar |
|---|---|---|
| `muzzle_flash_placeholder.png` | An 8-frame flash sheet, and the layout reference for new sheets | **yes** |
| `muzzle_flash_template.psd` | Layered Photoshop template for authoring a new sheet | no (development file) |
| `README.md` | This document | no (development file) |

The same sheet is also copied into the bundled content pack, because a weapon definition resolves its
flash texture **inside its own pack**, not against the mod's own assets:

| Where | Identifier a definition can use |
|---|---|
| Bundled pack (the sheet the shipped shotgun uses) | `arg_pack:weapons/special/muzzle_flash/universal_flash` |
| Template folder (copy into your own pack) | `your_pack:weapons/special/muzzle_flash/muzzle_flash_placeholder.png` |

The build excludes `*.psd` and this README from both loader jars, so the template never ships as dead
weight while the placeholder stays loadable at runtime.

## Sheet layout

| Property | Value |
|---|---|
| Canvas | **2048 x 256 px** |
| Frame count | **8** |
| Grid | **rows = 1, columns = 8** |
| Frame (cell) size | **256 x 256 px** |
| Frame *n* occupies | `x = (n - 1) * 256 .. n * 256`, full height |
| Padding | at least **32 px** of fully transparent pixels on every side of every cell |
| Colour space | sRGB, 8 bits per channel, **RGBA** |
| Recommended FPS | **60** (8 frames = 133 ms, a typical muzzle flash) |

The placeholder matches the convention the reference implementation uses for its own flash sheets
(`assets/pointblank/textures/effect/flashes.png` is 1800 x 200, i.e. nine square 200 x 200 cells in one
row): **square cells, one row, no gap between cells**.

### Rules an artist must keep

* Every frame stays inside its own 256 x 256 cell. Nothing may cross a cell boundary, including faint
  glow or semi-transparent smoke: the texture is sampled with filtering, so a pixel that leaks into the
  neighbour cell shows up as a flash-shaped smudge on the wrong frame.
* Keep at least 32 px of fully transparent margin inside each cell. The placeholder keeps every frame
  inside a 192 px-wide circle centred in its cell.
* The flash must be **centred in its cell** and **centred consistently across frames**, because the quad
  is built around the bone's pivot: an off-centre sheet makes the flash drift while it plays.
* The background must be fully transparent. Never fill a cell with black: the flash is drawn additively,
  so black becomes visible as a dark square.
* Brightness belongs in the colour channels, shape in the alpha channel. The placeholder is stored
  unpremultiplied (`RGB` at full brightness, `A` as the shape) because the renderer multiplies colour by
  alpha in the shader; storing premultiplied colours would darken the flash twice.

## Layers in the template

`muzzle_flash_template.psd` is a genuine layered document (RGB + alpha, 8-bit):

```
00 frame grid (reference)     cell outlines, frame numbers, and the 32 px safe area
Frame 1 .. Frame 8            one group per frame, top layer first:
    4 glow                    wide, low-opacity halo (soft-blurred)
    3 outer flame             orange fringe, the irregular flame silhouette
    2 inner flame             yellow body of the flame
    1 flash core              white-hot centre
```

Every layer is full-canvas, so all eight frames share one registration: painting on `Frame 3 / 3 outer
flame` can never move `Frame 4`. Hide the `00 frame grid` layer before exporting.

## Using a sheet in a weapon definition

```json
"muzzle_flash": {
  "enabled": true,
  "bone": "muzzle_flash",
  "texture": "arg_pack:weapons/special/muzzle_flash/universal_flash.png",
  "size": 0.35,
  "duration_ms": 80,
  "sprites": {
    "rows": 1,
    "columns": 8,
    "fps": 200,
    "type": "random"
  }
}
```

* `sprites.rows` / `sprites.columns` describe the grid the sheet actually has. They are **required** for
  any sheet that is not a single square image: without them ARG falls back to deriving a horizontal strip
  from the texture's aspect ratio, which is only correct for square cells that fill the whole height.
* `sprites.fps` controls **only how fast the sprites advance**. 200 fps swaps the sprite every 5 ms.
* `duration_ms` controls **only how long the flash stays visible**, independently of the frame rate: the
  example above draws for 80 ms - sixteen sprites - and the same 80 ms at 30 fps would draw three. Every
  animation type ends at the duration: `random` keeps picking sprites, `loop` keeps wrapping, `static`
  keeps its one sprite, and `play_once` holds its final frame once its sequence is over.
  Omit it and the animation's own frames decide, which is what content packs written before the property
  get: `random` and `play_once` end when the sheet runs out, `loop` and `static` keep drawing.
  `duration_ms` must be a positive whole number of milliseconds (maximum 10000); an unusable value is
  reported and treated as if it were absent, so a typo can never make a flash invisible.
* `sprites.type` is one of `play_once` (the default), `looping`, `random`, `static`.
* `size` is the quad's edge length in blocks - not the sheet's or the cell's size.

## Creating another sheet

1. Copy `muzzle_flash_template.psd`.
2. Keep the canvas at 2048 x 256 (or scale all four values together and declare the same grid in JSON).
3. Compose each frame inside its cell and inside the marked safe area; keep the flash centred.
4. Delete the `00 frame grid` layer and export the whole canvas as PNG with transparency
   (PNG-24 with alpha, sRGB, no matte).
5. Reference the PNG from the weapon's `muzzle_flash` block as above.

`tools/muzzle_flash/` contains the generators that produced both files
(`generate_muzzle_flash.py` for the sheet, `build_muzzle_flash_template.py` for the PSD). They are
development tools and are not part of the build; the PSD builder needs `psd-tools`, the sheet generator
needs `numpy` and `Pillow`.
