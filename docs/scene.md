# Declarative scene DSL

Namespaces: `slipstream.scene` and re-exports in `slipstream.core`.

```clojure
(require '[slipstream.scene :as scene]
         '[slipstream.core :as slug])
```

The primary operations are `(normalize data)`, `(compile data)`, and `(with-scene [s data] ...)`. Normalization validates the whole tree before native mutation; compilation realizes definitions in dependency order, builds one atlas, and returns a `Scene` owning it.

## Complete example

```clojure
(def data
  [:scene {:texture-width 512 :curve-precision 32}
   [:font {:id :body :path "/System/Library/Fonts/Geneva.ttf"}]
   [:path {:id :tile-path} [:rect 0 0 0.2 0.2]]
   [:define-shape {:id :tile :path :tile-path}]
   [:path {:id :background}
    [:rect 0 0 1 1]]
   [:linear-gradient {:id :sky :from [0 0] :to [1 0]}
    [0 [0.1 0.2 0.8 1]]
    [1 [0.2 0.8 1.0 1]]]
   [:pattern {:id :grid :tile :tile :cell [0 0 0.2 0.2]
              :repeat-x :reflect :repeat-y :repeat}]
   [:group {:at [0 0] :scale 1}
    [:shape {:id :background-shape :path :background :fill :sky}]
    [:shape {:path :background :fill :grid}]
    [:text {:font :body :at [0.1 -0.2] :size 0.5
            :align :left :anchor :baseline :color [1 1 1 1]}
     "Slipstream"]]])

(scene/with-scene [s data]
  (println (slug/shape s :background-shape))
  (println (select-keys (slug/mesh s) [:stride :attributes])))
```

## Scene node and options

A scene is `[:scene options? & children]`. Options are:

| Option | Default | Values |
|---|---:|---|
| `:texture-width` | `512` | Positive integer atlas width. |
| `:curve-precision` | `32` | `16` or `32`. |

Named definitions have keyword `:id`s and may appear before or after their references. Duplicate IDs, missing/wrong-type references, and definition cycles fail with `:scene-path` and `:node`.

Top-level `:shape`, `:text`, and `:layer` nodes are roots. Only unnamed top-level `:group`/`:composite` nodes are roots; named groups are definitions used through `:layer`.

## `:font`

```clojure
[:font {:id :body :path "/path/font.ttf"
        :codepoints [65 0x1f600] :uniform false :mask 0}]
```

Options:

- `:id` — required keyword.
- `:path` — required font path string.
- Codepoint mode: `:codepoints vector`, `:ascii true`, `:all true`, or omitted to infer from scene text. Modes are mutually exclusive and booleans must be actual booleans.
- `:uniform` — default `false`; use uniform glyph metrics.
- `:mask` — key namespace byte, default `0`.

Inferred codepoints are collected through nested groups. Ordinary text uses Unicode scalar values; compatibility text infers byte keys.

## `:path`

```clojure
[:path {:id :shape-path}
 [:move-to 0 0]
 [:line-to 1 0]
 [:quad-to 1 1 0 1]
 [:close]]
```

`:id` is required. Children are commands or `[:path other-path-id]` references. Commands:

| Command | Arguments |
|---|---|
| `:clear`, `:begin-path`, `:save`, `:restore`, `:reset-transform` | none |
| `:set-transform`, `:transform` | six matrix numbers |
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

Paths can contain other path references, including compound transformed paths assembled during realization.

## `:shape` and `:define-shape`

```clojure
[:shape {:id :outline :path :shape-path
         :stroke 0.02 :paint [1 1 1 1]
         :join :round :cap :round :miter-limit 4
         :origin :centered :scale 2}]
[:define-shape {:id :geometry-only :path :shape-path}]
```

Both require either `:path path-id` or inline command children. `:define-shape` registers geometry without a drawable layer. Common options:

- `:id` — optional; otherwise a stable generated key is used.
- `:fill` — flat RGBA or gradient/pattern definition ID; mutually exclusive with `:stroke`.
- `:stroke` — numeric width; use `:paint` or `:color` for its paint, not `:fill`.
- `:join` — `:miter`, `:bevel`, or `:round`.
- `:cap` — `:butt`, `:round`, or `:square`.
- `:miter-limit` — default `4`.
- `:origin` — `:default`, `:centered`, `[px py]`, or `{:type :default/:centered/:pivot/:custom :x n :y n}`.
- `:splits-x`, `:splits-y` — numeric split vectors, default `[]`.
- `:sdf` — boolean, default `false`; request SDF/MSDF baking.
- `:sdf-range` — default `0.1`.
- `:auto-metrics` — boolean, default `true`.
- `:clockwise` — boolean, default `false`; reverse stroke outline winding.
- Placement/layer options below.

Native normalization scale is always `1`; user `:scale` is layer placement only.

## `:text`

```clojure
[:text {:font :body :size 0.5 :at [0 0]
        :align :left :anchor :baseline
        :fill [1 1 1 1] :mask 0 :compatibility false}
 "Text"]
```

Exactly one string child is required. Options:

- `:font` — required font definition ID.
- `:size` — em-size, default `1`.
- `:at` or `:transform` — two/three-component placement, not both.
- `:color` or `:fill` — flat RGBA or advanced paint ID; mutually exclusive.
- `:align` — `:left`, `:center`, or `:right`.
- `:anchor` — `:baseline`, `:cap-center`, `:cap-top`, or `:x-center`.
- `:mask` — optional key namespace byte, otherwise the font mask.
- `:compatibility` — boolean, default `false`; when true, UTF-8 bytes become glyph keys to match the C++ example.

## `:group` and `:composite`

```clojure
[:group {:id :card :at [0 0] :scale 1
         :advance 0 :mask {:type :circle :params [0.5 0.5 0.4]}}
 child-node ...]
```

The options map may be omitted. `:group` and `:composite` share semantics. Options:

- `:id` — optional definition ID.
- `:advance` — composite layout advance, default `0`.
- `:mask` — optional mask map.
- Placement/layer options below.

Children can be shapes, define-shapes, text, groups/composites, and explicit layers. Ordered children become ordered layers.

## `:layer`

```clojure
[:layer {:key :card :at [1 2 0] :scale 1
         :color [1 1 1 1] :blend-mode :src-over}]
```

References a shape or composite by required keyword `:key` and accepts the placement/layer options below. It creates a new instance without redefining the target.

## Shared placement and layer options

Shapes, groups, and explicit layers accept:

- `:at` or `:transform` — `[x y]` or `[x y z]`; mutually exclusive.
- `:scale` — placement scale, default `1`.
- `:color` — for groups and explicit layers, straight RGBA with default `[0 0 0 1]`. For shapes, use the shape-specific paint rules: filled shapes accept `:fill`, while stroked shapes accept `:color` or `:paint`; standalone `:color` is invalid on a filled shape.
- `:effect-id` — unsigned 32-bit integer, default `0`.
- `:effect-param` — number, default `0`.
- `:gradient-id`, `:pattern-id` — unsigned integer native IDs, default `0`; normally use named paint references instead.
- `:bleed` — numeric content margin, default `0`.
- `:draw-mode` — `:visible`, `:hidden`, `:geometry`, or `:mask`.
- `:blend-mode` — any Porter-Duff/Photoshop keyword listed in [Core](core.md#layer-maps).

## Gradient definitions

Each gradient needs keyword `:id` and at least one `[offset [r g b a]]` stop.

```clojure
[:linear-gradient {:id :lg :from [0 0] :to [1 0]}
 [0 [1 0 0 1]] [1 [0 0 1 1]]]
[:radial-gradient {:id :rg :center [0.5 0.5]
                   :inner-radius 0 :outer-radius 0.5}
 [0 [1 1 1 1]] [1 [0 0 0 0]]]
[:sweep-gradient {:id :sg :center [0.5 0.5]
                  :start-angle 0 :end-angle 1}
 [0 [1 0 0 1]] [1 [0 1 0 1]]]
```

Angles use Slughorn's native conventions. Definitions use native em-space coordinates and are not implicitly fitted to each shape. The fine-grained API additionally supports affine-radial gradients.

## `:pattern`

```clojure
[:pattern {:id :checks :tile :tile-shape
           :cell [0 0 0.2 0.2]
           :transform [1 0 0 1 0 0]
           :repeat-x :repeat :repeat-y :reflect}]
```

Options:

- `:id` — required keyword.
- `:tile` — required shape/define-shape ID.
- `:cell` — required `[x y width height]` in tile em-space.
- `:transform` — six-component matrix, default identity.
- `:repeat-x`, `:repeat-y` — `:repeat` or `:reflect`.

Patterns accept no children.

## Masks

Group masks are maps with `:type`, up to six numeric `:params`, and boolean `:invert` (default `false`). Procedural types are `:circle`, `:rect`, `:capsule`, `:arc`, `:arc-band`, `:hexagon`, `:octagon`, and `:star`.

An SDF-tile mask requires a shape key:

```clojure
{:type :sdf-tile :key :mask-shape :params [0 0 1] :invert false}
```

The key must reference a shape/define-shape with baked SDF data. The reference compatibility renderer does not evaluate masks; the Glimmer renderer does.

## Normalization and errors

`normalize` returns canonical `{:tag :scene :options ... :children ...}` data. It checks node shapes, arities, numeric vectors, enums, booleans, Unicode scalar values, mutually exclusive options, duplicate IDs, reference types, and cycles. Errors are `ex-info` with `:scene-path` and `:node`.

`compile` realizes all named definitions—even unused ones available for inspection—then retains authored root order. Temporary paths and the atlas are cleaned up on every failure, with original errors preserved.

## Mesh and serialization roots

`slug/mesh` and `slug/layers` use compiled scene roots by default, recursively flattening composite references. After `read-atlas`, there is no implicit root:

```clojure
(slug/mesh loaded :card)
(slug/mesh loaded [{:key :card :transform [0 0 0]}])
```

Serialization uses upstream `.slug`/`.slugb` behavior, not a separate Slipstream scene schema. Do not expect it to preserve upstream-omitted scene/root/mask/layer metadata.
