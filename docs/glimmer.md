# Reactive Glimmer guide

Namespace: `slipstream.glimmer`. Requiring it registers the `:slipstream` backend with Glimmer; applications use `glimmer.core` and `glimmer.ratom` normally.

```clojure
(require '[glimmer.core :as ui]
         '[glimmer.ratom :as r]
         '[slipstream.glimmer :as sg])
```

Slipstream supplies native widgets, OpenGL resources, a 2D renderer, and a Jolt-owned UI loop. Glimmer supplies components, reactive cells, keyed reconciliation, and hot reload.

## A reactive component

```clojure
(def state
  (r/atom {:label "Reactive"
           :color [1 1 1 1]
           :path [[:rect 0 0 0.5 0.5]]}))

(defn app []
  (let [{:keys [label color path]} @state]
    [:scene {}
     [:group {}
      [:shape {:path path
               :fill {:type :linear :from [0 0] :to [0.5 0.5]
                      :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]}}]
      [:text {:font "/System/Library/Fonts/Geneva.ttf"
              :text label :at [0.05 0.75 0] :size 0.15
              :align :left :anchor :baseline :color color}]]]))

(ui/run app
        :title "Reactive Slipstream"
        :width 640 :height 480 :bounds [0 1 0 1])
```

Changing `state` re-renders only affected components/resources. The same native window remains open.

## Reactive primitives

### `:scene`

The native root/window. `ui/run` accepts Glimmer options plus Slipstream options:

| Option | Default | Meaning |
|---|---:|---|
| `:title` | `"glimmer"` | Window title. |
| `:width`, `:height` | `400`, `300` | Window size in points. |
| `:bounds` | `[0 aspect 0 1]` | Y-up `[left right bottom top]`. |
| `:visible` | `true` | Initial window visibility. |
| `:max-frames` | `nil` | Stop after a positive frame count. |
| `:auto-quit-ms` | `nil` | Stop after this many milliseconds. |
| `:on-start` | `nil` | Zero-arg callback before the first frame. |
| `:after-render` | `nil` | Zero-arg callback after each render, before swap. |

Zero-sized/minimized framebuffers skip rendering and resume on resize without rebuilding resources.

### `:shape` and `:path`

These are equivalent drawable primitives. Geometry is inline command data:

```clojure
[:shape {:path [[:rect 0 0 1 1]]
         :fill [0.2 0.6 1 1]
         :scale 1 :at [0 0 0]}]
```

Common geometry options:

- `:path` — command vector such as `[[:rect ...]]`, `[[:circle ...]]`, or `[[:move-to ...] ...]`.
- `:fill` — flat RGBA, local paint keyword, gradient map, or pattern map.
- `:stroke` — width; use `:paint`/`:color` for stroke paint.
- `:join`, `:cap`, `:miter-limit` — stroke controls, default `:miter`, `:butt`, `4`.
- `:origin` — shape origin/pivot.
- `:splits-x`, `:splits-y` — band splits.
- `:auto-metrics`, `:clockwise` — booleans controlling metrics/winding.
- `:sdf`, `:sdf-type`, `:sdf-range` — bake SDF or MSDF and set range.
- `:texture-width`, `:curve-precision` — per-resource atlas configuration.

See [declarative path commands](scene.md#path) for command syntax. Reactive shape compilation does not reference the declarative scene's global IDs; use local data and `:paints` for named local definitions.

### `:text`

Text content is the **`:text` property**, not a bare string child:

```clojure
[:text {:font "/path/font.ttf"
        :text "Live text"
        :size 0.2 :at [0 0.8 0]
        :align :center :anchor :cap-center
        :fill [1 1 1 1]}]
```

Options:

- `:font` — path string or map `{:path "..." :uniform false :mask 0}`.
- `:text` — string converted to Unicode codepoints.
- `:size` — em-size, default `1`.
- `:at` or `:transform` — `[x y]` or `[x y z]`.
- `:scale` — additional placement scale; text combines `:size` and `:scale`.
- `:align` — `:left`, `:center`, or `:right` using measured advances.
- `:anchor` — `:baseline`, `:cap-center`, `:cap-top`, or `:x-center` using retained font metrics.
- `:color`, `:fill`, or `:paint` — flat or advanced paint; named local paint uses `:paints`.
- `:compatibility`, `:byte-wise`, `:byte-wise?` — boolean aliases for reference byte-wise glyph keys; ordinary text is Unicode.
- `:mask` — font key namespace byte.
- `:texture-width`, `:curve-precision`, `:uniform` — per-glyph atlas/font options.

Missing glyphs and invalid Unicode scalar values produce authored-path errors instead of nil advance failures.

### `:group` and `:composite`

Groups order children and can apply masks and offscreen blending:

```clojure
[:group {:at [0.1 0.1 0] :scale 1
         :mask {:type :circle :params [0.5 0.5 0.4]}
         :blend-mode :multiply}
 child ...]
```

`:scene`, `:group`, and `:composite` are containers. Group transforms/scales compose through descendants. Group opacity multiplies child color as appropriate, while whole-group opacity/mask is applied during offscreen composition.

## Common layer options

Shapes, text, groups, and composites support these layer-oriented props without normally rebuilding geometry. The root `:scene` is configured through the `ui/run` options above rather than general layer placement/color/blend props:

- `:at` / `:transform` — placement.
- `:scale` — layer placement scale.
- `:color` — straight RGBA.
- `:visible` — `false` hides; `:visibility :hidden` also hides.
- `:opacity` — multiplier, default `1`.
- `:blend-mode` — any supported Porter-Duff/Photoshop keyword; a non-default group blend triggers offscreen composition.
- `:scene-path` — authored diagnostic path, retained in errors.

Shape/text draw commands additionally support `:draw-mode` (`:visible`, `:hidden`, `:geometry`, or `:mask`), `:bleed`, and custom `:effect`, `:effect-id`, and `:effect-param`. Groups do not consume effect fields or non-visibility draw modes; put those props on the drawable child instead.

Raw reactive `:gradient-id` and `:pattern-id` are rejected: atlas-local integers are not context-owned resource references. Use inline paint or `:paints` instead. A nonzero effect ID without a custom effect program is also an error.

## Paint data

### Flat and named paint

```clojure
:fill [1 0 0 1]
:fill :main
:paints {:main [1 0 0 1]}
```

Changing a flat color, including a named flat color, is layer-only and does not recompile geometry.

### Gradients

```clojure
:fill {:type :linear :from [0 0] :to [1 0]
       :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]}
```

Reactive maps support `:linear`, `:radial`, `:sweep`, and `:affine-radial` using the geometry options in [Core](core.md#gradients-and-patterns). Stop changes rebuild only dependent paint textures, not unrelated geometry/programs.

### Patterns

```clojure
:fill {:type :pattern :tile :tile-node
       :tile-path [[:rect 0 0 0.2 0.2]]
       :cell [0 0 0.2 0.2]
       :transform [1 0 0 1 0 0]
       :repeat-x :repeat :repeat-y :reflect}
```

Reactive patterns need local `:tile` and `:tile-path`; the tile is built into the same per-node atlas. Repeat modes are `:repeat` and `:reflect` independently per axis.

## Masks and SDF tiles

Procedural masks use the same maps as declarative scenes. An SDF-tile mask instead supplies path geometry and owns that resource through its group:

```clojure
[:group {:mask {:type :sdf-tile :params [0 0 1]
                :path [[:rect 0 0 0.5 1]]
                :sdf true :sdf-type :msdf :sdf-range 0.15}}
 [:shape {:path [[:rect 0 0 1 1]] :fill [0 1 0 1]}]]
```

`:tile-path` is an alias for mask `:path`. Mask changes transactionally replace only the mask resource. Nested groups, inversion, and SDF effects use offscreen rendering.

## What updates incrementally?

| Change | Native work |
|---|---|
| Position, scale, color, opacity, visibility, order | Update draw/layer data only. |
| Effect ID/parameter or flat paint | No atlas rebuild; effect uniforms/instances update. |
| Path or stroke geometry | Rebuild only that shape's atlas/mesh/GPU textures. |
| Text string | Acquire added glyphs, release removed glyphs; unchanged occurrences stay shared. |
| Gradient stops or pattern tile | Rebuild only dependent paint/node textures. |
| SDF inputs or mask path | Rebuild only dependent SDF/mask resources. |
| Resize, including zero size | Update viewport/projection; no atlas rebuild. |

CPU atlas/mesh resources are content-cached and reference-counted. GPU resources are cached per OpenGL context and never shared across contexts. Replacement is transactional: old resources remain until the new one is ready or error unwinding begins.

## Components and reactive state

Glimmer Form-1 components rerun on every render. Form-2 components return a render function, so local ratoms persist:

```clojure
(defn counter []
  (let [n (r/atom 0)]
    (fn []
      [:text {:font "/System/Library/Fonts/Geneva.ttf"
              :text (str "Count: " @n)
              :size 0.2 :at [0 0.8 0]
              :color [1 1 1 1]}])))
```

Use `r/cursor` for a writable lens and `r/reaction` for derived state. A component subscribes only to cells read by its latest render. Child matching is positional unless every child has `:key`; keyed insert/remove/reorder preserves widget identity and resources.

Top-level `defonce` ratoms survive `(ui/reload!)`. Component-local state follows normal Glimmer mount/unmount rules.

## Scheduling and teardown

Reactive updates from other threads are marshalled to the UI thread. Use `ui/on-gui` when explicitly touching widgets or scheduling native work:

```clojure
(ui/on-gui #(swap! state assoc :label "Updated from REPL"))
```

Before loop termination, scheduling becomes terminal: queued/late work is dropped before unmount and context destruction. Do not call native resources after their context is destroyed.

`(sg/current-context)` returns the running native context or `nil` headlessly. Most applications do not need it; it is useful for capture/instrumentation such as `slipstream.glimmer-renderer/capture!`.

## Advanced effects

Attach `:effect` to a reactive shape. See [Rendering and effects](rendering.md#custom-effects) for hook signatures, uniforms, SDF behavior, and examples. Programs are cached by complete source/interface per context; changing `:param` does not rebuild geometry or relink the program.

## Lifecycle and limitations

Removing/replacing/unmounting widgets releases shape, glyph, paint, and SDF-mask references. Each editable shape/glyph has an independent atlas, so very large scenes use more textures/draw calls than a single shared atlas. The offscreen compositor favors correctness and currently uses fullscreen passes; profile before optimizing.
