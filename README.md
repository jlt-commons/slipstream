# Slipstream

Slipstream is a data-oriented Jolt wrapper for [Slughorn](https://github.com/AlphaPixel/slughorn). It provides a declarative scene compiler, the reference GLFW renderer, and an incremental [Glimmer](https://github.com/jolt-lang/glimmer) backend with gradients, patterns, masks, compositing, SDF/MSDF, and custom shader effects.

See the [complete API documentation](docs/README.md) for every primitive, option, lifecycle rule, and example.

## Requirements and dependency setup

- Jolt 0.8.23 or a compatible newer release.
- A Slughorn checkout containing `slughorn/slughorn.cpp` and its example.
- CMake, a C++20 compiler, `pkg-config`, and FreeType.
- GLFW, GLAD, msdfgen, and Glimmer are fetched by the build/dependency tasks. The default dependency is Glimmer `v0.1.7`; no sibling Glimmer checkout is required.
- `nlohmann/json.hpp`, either from Slughorn's `ext/json` submodule or a system package (`nlohmann-json3-dev` on Debian/Ubuntu, `nlohmann-json` on Homebrew).

Set `SLUGHORN_HOME` when Slughorn is not at `~/src/slughorn`:

```sh
export SLUGHORN_HOME="$HOME/src/slughorn"
```

Resolve dependencies and build the native library:

```sh
jolt -P
jolt native
```

For development against a local Glimmer checkout, the `:dev` alias overrides the published coordinate:

```sh
jolt -A:dev:test
```

The checkout path in that alias is developer-local configuration; normal builds and tests use the versioned Git dependency.

## Fonts and test commands

Tests that load fonts require `SLIPSTREAM_TEST_FONT`. On macOS, for example:

```sh
export SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf
```

Run all required verification tasks:

```sh
jolt native
SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt test
SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt parity
```

`jolt test` runs unit, native integration, exact golden, and parity-contract tests. `jolt parity` compares Slipstream's atlas, mesh, output, and reference-frame behavior with the upstream Slughorn example.

Run the reference, reactive, and live demoscene examples:

```sh
jolt -m slipstream.example.glfw "$SLIPSTREAM_TEST_FONT"
jolt -m slipstream.example.reactive "$SLIPSTREAM_TEST_FONT" --auto-quit-ms 10000
jolt -m slipstream.example.demoscene "$SLIPSTREAM_TEST_FONT"
jolt -m slipstream.example.demoscene "$SLIPSTREAM_TEST_FONT" \
  --profile all --intensity 1 --warmup-ms 1000 --auto-quit-ms 5000
```

The reactive example's timed mode performs its golden/smoke checkpoint sequence. Add `--capture-dir native/build/reactive-captures` to write inspection images.

The demoscene starts a rich nREPL on port `7888` and runs until its native window closes. Connect an editor to change reactive state, redefine palette/update functions, switch bounded performance workloads with `load-profile!`, inspect/reset metrics, and evaluate `slipstream.example.demoscene/stop!` without restarting the window. Stress profiles enable HUD metrics and one clean-exit EDN summary by default; the original showcase keeps both opt-in. See the [complete demoscene and performance guide](docs/demoscene.md) for workload scaling, warm-up/counter interpretation, CLI options, lifecycle, useful evaluations, and captures.

GUI checks require a working display/OpenGL context; dependency resolution or compilation alone is not visual verification.

## Declarative scenes

```clojure
(require '[slipstream.core :as slug])

(def scene-data
  [:scene {:texture-width 512 :curve-precision 32}
   [:font {:id :body :path "/System/Library/Fonts/Geneva.ttf"}]
   [:path {:id :rect} [:rect 0 0 1 1]]
   [:linear-gradient {:id :sky :from [0 0] :to [1 0]}
    [0 [0.1 0.2 0.8 1]]
    [1 [0.2 0.8 1.0 1]]]
   [:shape {:id :background :path :rect :fill :sky}]
   [:text {:font :body :at [0.1 -0.2] :size 0.5
           :align :left :anchor :baseline :color [1 1 1 1]}
    "Slipstream"]])

(slug/with-scene [scene scene-data]
  (println (slug/shape scene :background))
  (println (select-keys (slug/mesh scene) [:stride :attributes])))
```

`with-scene` compiles data when necessary and always destroys the native atlas. `compile`, `normalize`, `read-atlas`, shape/composite inspection, texture access, mesh generation, and serialization are also available through `slipstream.core`.

Shape `:scale` is layer placement, not geometry normalization: it changes draw/mesh placement without rebuilding the shape. Deserialized atlases have no implicit root; pass a composite key or explicit layers to `mesh`.

Run the compatibility renderer with:

```clojure
(require '[slipstream.glfw :as glfw])
(glfw/run! scene-data {:max-frames 60})
```

## Reactive Glimmer scenes

Text content is the `:text` property. Reactive placement, color, visibility, opacity, scale, blend/draw mode, and effect parameters update layer data without rebuilding geometry. Path/stroke changes rebuild only their shape; text and advanced-paint changes acquire only affected glyph resources.

```clojure
(require '[glimmer.core :as ui]
         '[glimmer.ratom :as r]
         '[slipstream.glimmer])

(def state
  (r/atom {:label "Reactive"
           :color [1 1 1 1]
           :path [[:rect 0 0 0.5 0.5]]}))

(defn app []
  (let [{:keys [label color path]} @state]
    [:scene {}
     [:group {}
      [:shape {:path path :fill {:type :linear :from [0 0] :to [0.5 0.5]
                                 :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]}}]
      [:text {:font "/System/Library/Fonts/Geneva.ttf"
              :text label :at [0.05 0.75 0] :size 0.15
              :align :left :anchor :baseline :color color}]]]))

(ui/run app :width 640 :height 480 :bounds [0 1 0 1])
```

Use `ui/on-gui` for updates originating outside the UI thread. Once a scene loop terminates, scheduling is terminal and late work is dropped. A zero-sized/minimized framebuffer skips drawing and resumes on resize without rebuilding GPU resources.

An SDF-tile mask owns its shape resource through its group:

```clojure
[:group {:mask {:type :sdf-tile :params [0 0 1]
                :path [[:rect 0 0 0.5 1]] :sdf true}}
 [:shape {:path [[:rect 0 0 1 1]] :fill [0 1 0 1]}]]
```

Raw reactive `:gradient-id` and `:pattern-id` values are deliberately rejected. Atlas-local integer IDs are not context-owned resource references; use inline/named paint data instead.

## Custom effects

Reactive shapes accept an `:effect` map with an unsigned 32-bit `:id`, numeric `:param`, optional GLSL hooks, optional SDF input, and typed uniforms. Programs are cached per OpenGL context by complete source and uniform interface. Invalid interfaces fail with the authored scene path.

```clojure
[:shape {:path [[:circle 0.5 0.5 0.4]]
         :fill [0.2 0.8 1 1]
         :sdf true :sdf-type :msdf :sdf-range 0.15
         :effect {:id 7 :param 1.0 :sdf true :bleed 0.1
                  :uniforms {:tint {:type :vec4 :value [1 1 1 1]}}
                  :fragment-hook
                  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
                     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
                     return vec4(color.rgb * u_tint.rgb, color.a * coverage * effectParam);
                   }"}}]
```

Supported uniform types include scalar/vector float, signed and unsigned integer, boolean, `mat3`, and `mat4` forms. See `slipstream.glimmer-effects` for the complete type table.

## Ownership and lifecycle

- Declarative `Scene`, `Atlas`, `Path`, and scoped `Canvas` values own or borrow native handles as documented by their constructors. Prefer `with-scene`, `with-atlas`, `with-path`, and `with-canvas`.
- `destroy!` is idempotent at the wrapper level. Using a destroyed resource throws instead of dereferencing stale native memory.
- Reactive CPU resources are reference-counted and content-cached. GPU textures, buffers, effects, and framebuffers are scoped to one GL context and are never shared across contexts.
- Removing or unmounting widgets releases shape, glyph, paint, and SDF-mask references. Failed replacements preserve the last good resource until error unwinding.
- Do not retain borrowed texture pointers beyond their atlas lifetime. Texture access copies bytes by default.

## Renderer limitations

The `slipstream.glfw` compatibility renderer intentionally implements upstream standard-fill Slug coverage only. It supports actual atlas texture widths, but does not render gradients, patterns, masks, advanced blend modes, SDF effects, or custom effects. Use the Glimmer renderer for those features.

The Glimmer backend uses per-shape/per-glyph atlases and an offscreen compositor, trading additional textures and draw calls for incremental updates. It is a 2D Y-up renderer, not the reference example's orbit-camera demo. Runtime SDF/MSDF availability follows the native msdfgen build.

No broad compositor performance rewrite is included in this fix wave. Profiling scratch-framebuffer allocation, fullscreen passes, and draw-call costs remains future work; optimization should be driven by captured profiles while preserving exact goldens.
