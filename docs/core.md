# Core and native resource API

Namespace: `slipstream.core`, conventionally aliased as `slug`.

```clojure
(require '[slipstream.core :as slug])
```

This page lists every public core operation. Most applications should start with [declarative scenes](scene.md) or [Glimmer](glimmer.md); these functions are for custom compilation, inspection, serialization, and renderer integration.

## Records and ownership

Public record types are `Atlas`, `Path`, `Canvas`, and `Scene`. Their fields are visible for integration, but constructors should be preferred over direct construction.

- `Atlas` owns a native atlas handle.
- `Path` owns a native path handle.
- `Canvas` owns a native canvas handle and borrows its atlas.
- `Scene` owns an atlas and retains normalized `:data` and root `:layers`. A deserialized scene has `nil` data/root layers.

`destroy!` works on all four types, is wrapper-idempotent, and returns the resource. `with-*` macros destroy on normal return and exceptions. `with-scene` takes ownership even when passed an existing `Scene`.

## Atlas lifecycle

### `(make-atlas [& [options]])`

Options:

| Option | Default | Meaning |
|---|---:|---|
| `:texture-width` | `512` | Native atlas texture width in texels; positive integer. |
| `:curve-precision` | `32` | Curve storage precision, `16` or `32`. |
| `:sdf-type` | `nil` | Enable/configure SDF output with `:sdf` or `:msdf` (`0`/`1` also accepted). |

Returns an `Atlas`; construction failure releases the native handle.

### `(destroy! resource)`

Destroys the resource's native handle on first call. Subsequent calls do nothing.

### `(build! atlas)`

Packs shapes into curve/band/SDF textures and returns the atlas. Native atlases freeze geometry after building.

### `(built? atlas)`

Returns native built state as a boolean and throws if the native query fails.

### `(with-atlas [[atlas options] & body])`

Constructs with `make-atlas`, evaluates body, and always destroys it.

## Shapes, layers, and composites

### `(add-shape! atlas key curves options)`

Adds raw quadratic curves, each `[x1 y1 x2 y2 x3 y3]`, and returns the atlas.

| Option | Default | Meaning |
|---|---:|---|
| `:auto-metrics` | `true` | Derive metrics from geometry; `false` uses supplied metrics. |
| `:bearing-x`, `:bearing-y` | `0` | Em-space bearings. |
| `:width`, `:height` | `0` | Em-space extents. |
| `:advance` | `0` | Em-space layout advance. |
| `:num-bands-x`, `:num-bands-y` | `0` | Explicit band counts; zero chooses automatically. |
| `:splits-x`, `:splits-y` | `[]` | Normalized interior split positions. |
| `:origin` | `{}` | `:default`, `:centered`, `[px py]`, or map `{:type :default/:centered/:pivot/:custom :x n :y n}`. |

### `(shape atlas key)`

Returns shape metadata or `nil` when absent. Fields include band texel coordinates/limits/scales/offsets, bearings, width, height, advance, origin coordinates/type, and origin data.

### Layer maps

Fine-grained layers accept:

| Key | Default | Meaning |
|---|---:|---|
| `:key` | required | Shape/composite key. |
| `:color` | `[0 0 0 1]` | Straight RGBA color. |
| `:transform` | `[0 0 0]` | `[x y z]`, `[x y]`, or `{:x n :y n :z n}`. |
| `:scale` | `1` | Layer placement scale. |
| `:effect-id` | `0` | Frontend-defined effect ID. |
| `:effect-param` | `0` | Frontend-defined numeric parameter. |
| `:gradient-id` | `0` | One-based atlas gradient ID; zero is flat color. |
| `:pattern-id` | `0` | One-based atlas pattern ID; zero is none. |
| `:bleed` | `0` | Extra em-space content margin. |
| `:draw-mode` | `:visible` | `:visible`, `:hidden`, `:geometry`, or `:mask`. |
| `:blend-mode` | `:src-over` | Porter-Duff or Photoshop mode listed below. |

Blend keywords are `:src-over`, `:src`, `:dst`, `:src-in`, `:dst-in`, `:src-out`, `:dst-out`, `:src-atop`, `:dst-atop`, `:xor`, `:clear`, `:dst-over`, `:multiply`, `:screen`, `:overlay`, `:darken`, `:lighten`, `:color-dodge`, `:color-burn`, `:hard-light`, `:soft-light`, `:difference`, and `:exclusion`.

### `(add-composite! atlas key layers options)`

Registers an ordered composite and returns the atlas. Options are `:advance` (default `0`) and `:mask` (default `nil`). See [Masks](#masks).

### `(composite atlas key)`

Returns `{:key ... :advance ... :mask ... :layers [...]}` or `nil` when absent.

### `(layers atlas & [root])`

Recursively flattens a composite key or explicit layer vector, composing parent transforms/scales and rejecting cycles. A compiled `Scene` defaults to its retained roots. Deserialized scenes require an explicit root. Missing shape keys produce no drawable layer.

### `(call-with-layers layers f)`

Marshals layers into temporary native storage and calls `(f layer-pointer count)`. Names and buffer memory remain valid only during the callback.

### `(call-with-native-layers scene callback)`

Flattens a built scene and calls `(callback atlas-handle layer-pointer count)` with borrowed native storage. Use for native renderer calls that finish before the callback returns.

## Textures and meshes

### `(texture atlas kind & [{:keys [copy?] :or {copy? true}}])`

`kind` is `:curve`, `:band`, or `:sdf`. Returns:

```clojure
{:bytes byte-array :byte-count n :width w :height h :depth d :format format}
```

Formats are `:rgba32f`, `:rgba16f`, `:rgba16ui`, `:rg16ui`, `:rgba8`, `:rgb32f`, and `:r32f`. With `{:copy? false}`, `:bytes` is a borrowed pointer valid only while the atlas lives.

### `(mesh atlas & [root])`

Builds a reference-style mesh using root layers (compiled scene roots by default). Returns:

```clojure
{:vertices float-array
 :indices short-array
 :stride 80
 :attributes {...}}
```

Each resolved layer emits four vertices/20 floats each and six indices. Attributes:

| Name | Location | Byte offset | Components |
|---|---:|---:|---:|
| `:position` | 0 | 0 | 3 floats |
| `:color` | 1 | 12 | 4 floats |
| `:em-coord` | 2 | 28 | 4 floats |
| `:band-transform` | 3 | 44 | 4 floats |
| `:shape-data` | 4 | 60 | 4 floats |
| `:effect-id` | 5 | 76 | 1 float |

The builder applies the reference `0.01` AA expansion. Meshes above 65,536 vertices are rejected with an operation-rich error rather than wrapping unsigned-short indices; split larger meshes.

## Serialization

### `(write! atlas path)`

Writes `.slug` JSON or `.slugb` binary based on extension and returns the atlas. The atlas must be built.

### `(read-atlas path)`

Reads either format into a built `Scene`. It has no implicit root; call `(mesh scene composite-key)` or `(mesh scene explicit-layers)`.

Serialization follows upstream fidelity. It is not a complete Slipstream scene format: upstream-omitted data such as top-level roots, some layer fields, and composite masks is not silently preserved.

## Paths and Canvas

### `(make-path)` / `(with-path [[path] & body])`

Creates/scopes a native `Path`.

### `(make-canvas atlas options)` / `(with-canvas [[canvas atlas options] & body])`

Creates/scopes a Canvas. Options:

- `:key-start` — auto-key counter, default `0x20000000`.
- `:auto-metrics` — default `true`; initial configuration failure destroys the Canvas.

### `(path-command! path command)` and `(canvas-command! canvas command)`

Supported shared commands:

| Command | Arguments |
|---|---|
| `:clear`, `:begin-path` | none |
| `:save`, `:restore` | none |
| `:reset-transform` | none |
| `:set-transform`, `:transform` | six matrix values |
| `:translate` | `tx ty` |
| `:rotate` | radians |
| `:scale` | `sx sy` |
| `:move-to`, `:line-to` | `x y` |
| `:quad-to` | `cx cy x y` |
| `:bezier-to` | `c1x c1y c2x c2y x y` |
| `:close` | none |
| `:rect` | `x y w h` |
| `:rounded-rect` | `x y w h r` |
| `:circle` | `cx cy r` |
| `:ellipse` | `cx cy rx ry` |
| `:arc` | `cx cy r start end` plus optional boolean CCW |
| `:arc-to` | `x1 y1 x2 y2 r` |
| `:add-path` | source `Path`, optional six-component matrix |

Canvas-only commands are `:begin-composite`, `[:set-advance n]`, `[:set-auto-metrics boolean]`, `[:set-sdf boolean range]`, `[:set-splits xs ys]`, and `:clear-splits`.

### `(sample path t)`

Returns `{:x ... :y ... :angle ... :arc-length ...}` at normalized arc length `t`.

### `(arc-length path)`

Returns total arc length.

### `(stroke-path! path width options)`

Expands the path into stroke geometry. Options: `:clockwise` (`false`), `:join` (`:miter`), `:cap` (`:butt`), and `:miter-limit` (`4`). Joins are `:miter/:bevel/:round`; caps are `:butt/:round/:square`.

## Canvas commits and text

### Paint maps

Fine-grained paint requires exactly one of:

```clojure
{:color [r g b a]}
{:gradient-id positive-id}
{:pattern-id positive-id}
```

### `(fill! canvas path paint opts)`

Commits implicit path (`nil`) or explicit `Path`, returning its layer. Options are `:key`, normalization `:scale` (default `1`), and `:origin`.

### `(stroke! canvas path width paint opts)`

Like `fill!`, plus `:join`, `:cap`, and `:miter-limit` stroke options.

### `(define-shape! canvas path key opts)`

Registers geometry without adding a layer; returns the Canvas. Options are normalization `:scale` and `:origin`.

### `(text! canvas text opts)`

Adds upstream Canvas text layers. Options: `:size` (`1`), `:at` (`[0 0]`), `:color` (`[0 0 0 1]`), `:metrics` (`{}`), `:anchor-y` (`:baseline`), `:align-x` (`:left`), and `:mask` (`0`). Anchors are `:baseline/:cap-center/:cap-top/:x-center`; alignments are `:left/:center/:right`.

### `(layout-text! canvas atlas text opts)`

Uses the real upstream `Canvas::text` path, including its SDF behavior and **byte-wise** text semantics. Options are `:font-size`, `:position`, `:color`, `:metrics`, `:align`, `:anchor`, and `:mask`.

### `(text-layers atlas text opts)`

Returns direct layer maps rather than mutating a Canvas. Options match `layout-text!`, plus `:compatibility`, `:byte-wise`, or `:byte-wise?` (default false). Ordinary input iterates Unicode codepoints; any true compatibility alias maps each UTF-8 byte to a glyph key for the reference example.

### `(finalize! canvas key)`

Registers the accumulated composite and resets it, returning the Canvas.

## Fonts

### `(load-font! atlas path codepoints opts)`

Loads explicit Unicode scalar codepoints and returns:

```clojure
{:loaded n
 :metrics {:units-per-em n :cap-height-ratio n :x-height-ratio n
           :ascender-ratio n :descender-ratio n}
 :family "..." :style "..."}
```

Options are `:uniform` (default `false`) and `:mask` (default `0`, byte `0..255`).

### `(load-ascii-font! atlas path opts)`

Loads codepoints 32–126 with the same options/result.

### `(load-all-font! atlas path opts)`

Loads every mapped codepoint with the same options/result.

## Masks

### `(set-mask! canvas mask)`

Sets composite mask and returns the Canvas. `nil` clears it.

Procedural mask maps use:

```clojure
{:type :circle :params [cx cy r] :invert false}
```

Types are `:circle`, `:rect`, `:capsule`, `:arc`, `:arc-band`, `:hexagon`, `:octagon`, and `:star`; `:params` contains at most six numbers with type-specific Slughorn semantics. `:sdf-tile` uses `{:type :sdf-tile :key shape-key :params [ox oy scale]}`, with key required. `{:type :sdf-path :range 0.1 :invert false}` asks Canvas to bake/use its current path as an SDF mask when SDF support is enabled.

`add-composite!` accepts the same non-`:sdf-path` mask data.

## Gradients and patterns

### `(add-gradient! atlas gradient)`

Returns a positive one-based gradient ID. Common options:

- `:stops` — nonempty `[[offset [r g b a]] ...]`.
- `:transform` — optional six-component matrix, required for affine-radial.
- `:type` — `:linear` by default.

By type:

| Type | Geometry options |
|---|---|
| `:linear` | `:from [x y]`, `:to [x y]` |
| `:radial` | `:center [x y]`, `:inner-radius` (`0`), `:outer-radius` (`1`) |
| `:sweep` | `:center`, `:start-angle` (`0`), `:end-angle` (`1`) |
| `:affine-radial` | `:center`, both radii, and inverse-affine `:transform` |

Coordinates are native atlas/em-space; definitions are not automatically rescaled per shape.

### `(add-pattern! atlas pattern)`

Returns a positive one-based pattern ID. Options:

- `:tile` — required shape key.
- `:cell` — required `[x y width height]` in tile em-space.
- `:transform` — six-component region-to-pattern matrix, default identity.
- `:repeat-x`, `:repeat-y` — `:repeat` or `:reflect`.

## Declarative compilation helpers

- `(normalize scene-data)` delegates to `slipstream.scene/normalize`.
- `(compile scene-data)` delegates to `slipstream.scene/compile`.
- `(ensure-scene value)` returns an existing `Scene` or compiles data.
- `(with-scene [[scene value] & body])` ensures and always destroys a scene.

See [Declarative scene DSL](scene.md) for the complete data vocabulary.
