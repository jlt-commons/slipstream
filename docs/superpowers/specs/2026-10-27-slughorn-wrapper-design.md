# Slipstream: a data-oriented Jolt wrapper for Slughorn

## Intent

Slipstream will make Slughorn usable from Jolt through idiomatic Clojure data. A scene should be something a caller can construct, inspect, transform, and compile without mirroring C++ object lifetime or calling conventions. The reference GLFW example must be a real Jolt entry point and reproduce the behavior of `~/src/slughorn/example/slughorn-example-glfw.cpp`, not a simplified text-only demonstration.

The primary programming model is pure scene data compiled into native resources. The selected implementation style is a fine-grained C ABI: Clojure orchestrates atlas, path, canvas, layer, and serialization operations, with native calls kept small and explicit.

## Goals

- Provide Hiccup-inspired scene data for text, reusable paths, shapes, fills, strokes, groups/composites, gradients, patterns, and masks.
- Cover Slughorn atlas construction, FreeType glyph loading, Canvas path authoring, layer metadata, shape/metrics inspection, texture access, mesh generation, and `.slug`/`.slugb` serialization.
- Make the pure `scene-data -> compiled-scene` pipeline the normal API while retaining explicit native handles for advanced or incremental use.
- Use Jolt's established `deps.edn` `:jolt/native` loading and task-based native build model, following `ents`.
- Supply a Jolt equivalent of `example/slughorn-example-glfw.cpp`, including default and piped text, curve precision selection, shader behavior, camera controls, and cleanup.
- Test both Clojure-level behavior and native output parity.

## Non-goals and anti-goals

- Do not reimplement Slughorn geometry, packing, text layout, or serialization algorithms in Clojure.
- Do not expose C++ structs, exceptions, or manual struct ABI details to ordinary callers.
- Do not make the reference example a separate C++ program that merely embeds or launches Jolt; scene definition and program entry must be Jolt code.
- Do not claim that the minimal reference renderer supports every feature the wrapper can author. Its shader remains the upstream standard-fill shader. Gradient, pattern, mask, blend, SDF, and advanced effect rendering require other frontends.
- Cairo, Skia, Blend2D, NanoSVG, and COLR emoji adapter bindings are outside this pass. Core shapes, Canvas, FreeType, masks, composites, and serialization are in scope.
- Runtime SDF/MSDF baking is optional and only exposed when Slughorn is built with its required msdfgen support. Reading serialized atlases that already contain SDF data remains supported.

## Constraints

- Jolt has no JVM; all native calls use `jolt.ffi` and a flat C ABI.
- Slughorn requires C++20. Its public C++ objects cannot cross the FFI boundary directly.
- Foreign memory and object lifetimes are explicit. Every native entry point must prevent C++ exceptions from crossing into Jolt.
- The upstream example is interactive OpenGL 3.3 code. Equivalent output requires equivalent mesh layout, texture upload, shader source, projection math, event behavior, and default view—not merely the same word on screen.
- Native builds default to `SLUGHORN_HOME=~/src/slughorn`. FreeType is discovered with `pkg-config`. GLFW and GLAD are required only for the example/renderer build and may be discovered from a local installation or built through the native dependency task.
- The current machine has Jolt, Clang, and FreeType available. GLFW was not initially visible through `pkg-config`, so the build must give a clear actionable error or provide a reproducible local dependency build rather than silently omitting the example.

## Approaches considered

### Batch native scene compiler

Jolt would normalize the scene into a few flat buffers and C++ would interpret the whole scene. This minimizes FFI calls but was not selected. It would put scene orchestration and error context in C++, contrary to the chosen fine-grained wrapper direction.

### Fine-grained C ABI with a pure Clojure front end — selected

Opaque handles and scalar/buffer C functions wrap Slughorn operations. Raw bindings live in `slipstream.ffi`; safe handle functions and the declarative compiler live above them. This keeps scene semantics and validation in Clojure and supports incremental use, at the cost of a larger ABI and more calls during compilation.

### Native EDN/data parser

C++ would parse and compile a serialized scene in one call. This was rejected because it introduces a second schema/parser and weakens REPL-oriented validation and error reporting.

## Public API

### Declarative scene data

A scene is a vector whose first element is `:scene`, whose optional second element is an options map, and whose remaining elements are definitions and drawable nodes. Definitions are referenced by keyword IDs. A representative scene is:

```clojure
[:scene {:texture-width 512 :curve-precision 32}
 [:font {:id :body :path "/path/to/font.ttf"}]
 [:linear-gradient {:id :sky :from [0 0] :to [1 0]}
  [0 [1 0 0 1]]
  [1 [0 0 1 1]]]
 [:path {:id :triangle}
  [:move-to 0.5 0.9]
  [:line-to 0.9 0.1]
  [:line-to 0.1 0.1]
  [:close]]
 [:group {:at [0 0] :scale 1}
  [:shape {:path :triangle :fill :sky}]
  [:text {:font :body :at [0 -0.2] :size 1
          :color [1 1 1 1]}
   "slughorn"]]]
```

The concrete normalizer will support:

- `:font` with explicit codepoints, ASCII, all mapped glyphs, or codepoints inferred from scene text; options for key masks and uniform metrics.
- `:path` with `:move-to`, `:line-to`, `:quad-to`, `:bezier-to`, `:close`, `:rect`, `:rounded-rect`, `:circle`, `:ellipse`, `:arc`, and `:arc-to`; nested transform nodes and reusable path references.
- `:shape` and geometry-only `:define-shape`, with flat, gradient, or pattern paint; stroke width/join/cap/miter limit; scale; origin; band splits; and optional SDF request.
- `:text` with size, baseline position, color/paint, alignment/anchor, and font metrics. Ordinary text nodes decode UTF-8 into Unicode codepoints. The reference example deliberately uses the upstream example's byte-wise layout path so its behavior matches exactly, including for non-ASCII piped input.
- `:group`/`:composite` with ordered children, advance, transforms, and optional mask. Explicit `:layer` data can address a shape or composite and set color, XYZ transform, scale, effect ID/parameter, gradient/pattern ID, bleed, draw mode, and blend mode.
- `:linear-gradient`, `:radial-gradient`, `:sweep-gradient`, and `:pattern` definitions using ordinary vectors and maps.

Gradient and pattern definitions use native atlas/em-space coordinates and map one-to-one to native registrations. Reusing a definition does not implicitly rescale it to each shape's bounds. Callers wanting Canvas-style per-shape coordinates define separate paints or apply explicit transforms.

Normalization validates node shape, required IDs/references, numeric vector lengths, enum values, and mutually exclusive paint options before native mutation. Errors use `ex-info` with the scene path and offending node.

### Compilation and lifecycle

```clojure
(require '[slipstream.core :as slug])

(slug/with-scene [scene (slug/compile scene-data)]
  (slug/shape scene :triangle)
  (slug/texture scene :curve)
  (slug/mesh scene))
```

`compile` creates an atlas, realizes definitions in dependency order, builds the atlas, and returns a `Scene` record containing its native atlas handle, normalized scene data, and top-level layers. Top-level drawable nodes form the scene root; named composites are reusable definitions rather than additional implicit roots. `with-scene` guarantees destruction. `destroy!` is idempotent at the Clojure wrapper level; operations on a destroyed scene throw instead of dereferencing stale native memory.

Inspection functions return ordinary maps and vectors for shapes, composites, layers, bounds, font metrics, and packing statistics. Texture access returns a descriptor map with width, height, depth, format, and a byte-array copy by default. A documented borrowed-pointer view is available for renderer code that needs zero-copy access and can honor the atlas lifetime.

`mesh` returns interleaved vertex float data, unsigned-short indices, and layout metadata matching the reference example. It recursively flattens composite references into geometry-shape layers in authored order, composing placement transforms; missing shape keys are skipped like the reference builder, and cycles are rejected during normalization. Masks remain renderer metadata and do not change mesh geometry. It uses Slughorn's `computeQuad` results and preserves the example's `0.01` AA expansion and vertex order. `mesh` also accepts an explicit layer vector or root composite for consumers rendering something other than the compiled scene root.

Serialization functions are `write!`, `read`, and format-specific variants where useful. Reading returns a built `Scene` with the same lifecycle and inspection API. Upstream `.slug`/`.slugb` fidelity is the contract: Slipstream does not invent a parallel scene format or silently promise to preserve fields the upstream serializer omits, including top-level roots, layer scale/bleed, or composite masks. Since serialized atlases can contain multiple composites and have no designated root, a read scene has no implicit mesh root; callers choose a composite/layers explicitly.

### Fine-grained API

`slipstream.core` also exposes safe constructors and operations for native atlases, paths, and canvases. These mirror important Slughorn operations without attempting to hide their mutation. Scoped macros cover atlas/path/canvas ownership. Canvases are borrowed handles associated with an atlas and do not outlive it.

`slipstream.ffi` contains only `defcfn` declarations and ABI constants. It is public for integrators but carries no convenience logic.

## Native ABI and implementation

`native/slipstream_shim.h` and `.cpp` define `extern "C"` functions prefixed `ss_`:

- Atlas create/destroy/build/configure, shape add/get, composite add/get, gradient/pattern registration, texture descriptors, packing stats, and serialization.
- Path create/destroy/copy/clear, transform stack, all authoring verbs, stroke expansion, sampling, and polyline access.
- Canvas create/destroy, implicit-path forwarding, explicit-path commits, text and text-on-path, composite finalization, masks, and layer access.
- FreeType loading and metrics.
- Reference mesh construction.
- Error access and native library/version information.

Keys cross as an explicit kind plus codepoint/mask or UTF-8 name. Small value records such as color, transform, shape, layer, texture descriptor, and sample use documented C layouts mirrored with `jolt.ffi/layout`. Functions returning collections use either caller-provided buffers with count/capacity or opaque iterators with explicit destruction. No native function returns a pointer to temporary storage.

All C++ calls are wrapped in an exception boundary. Failures return a sentinel and store a thread-local error string; safe Clojure wrappers check the result and throw `ex-info` containing the operation and native message. Clojure validation handles avoid predictable null/built-state misuse where practical.

The native library compiles Slughorn's `slughorn.cpp`, FreeType implementation, and serialization implementation into `native/libslipstream.dylib` or `.so`. `deps.edn` declares this under `:jolt/native`. The `native` task rebuilds when sources or dependency configuration change; `test` depends on it.

## Reference GLFW example

`example/slipstream/example/glfw.clj` provides `-main`. Its scene is data and uses the same default word, font argument, optional `--curve-precision 16|32`, stdin replacement text, and byte-wise text layout as the C++ example. This compatibility path intentionally treats each incoming byte as a glyph key rather than applying Unicode decoding.

The native renderer is a separate backend behind a small C ABI and a `slipstream.glfw` namespace. It ports the upstream shader strings, GL helpers, matrix/vector math, vertex layout, GLFW callbacks, camera state, and render loop. Jolt calls the renderer as a blocking FFI operation because it waits in the UI loop. GLFW callbacks remain native, avoiding unnecessary callbacks into Jolt and preserving exact interactive behavior.

The example prints the same controls and OpenGL version information, supports the same input behavior and key/mouse controls, and releases GL and native resources on exit. Additional test-only controls may request a fixed number of frames and a framebuffer capture; they do not alter default behavior.

## Glimmer reactive backend

Slipstream will include a real Glimmer backend in `slipstream.glimmer`, registered by requiring that namespace. Applications use `glimmer.ratom`, `glimmer.core/run`, Form-1/Form-2 components, keyed reconciliation, and REPL updates normally:

```clojure
(ns reactive-example
  (:require [glimmer.ratom :as r]
            [glimmer.core :as ui]
            [slipstream.glimmer]))

(defn app []
  (let [label (r/atom "slughorn")]
    (fn []
      [:scene {:font "/path/to/font.ttf" :width 800 :height 600}
       [:text {:text @label :at [0 0] :size 1
               :color [1 1 1 1]}]])))

(ui/run app)
```

The backend vocabulary uses `:scene` as the native window/container, `:group`/`:composite` for ordered composition, and drawable nodes including `:shape`, `:path`, and `:text`. Text content is the `:text` prop so Glimmer's primitive-child conversion does not create an unintended label widget. The complete declarative paint and metadata vocabulary is reactive: flat color, gradients, patterns, masks, draw/blend modes, SDF/MSDF, and effects. The backend supports ordinary Glimmer child operations, including keyed reorder, rather than flattening components into an opaque scene string.

### Selected update model

The selected model is fully incremental. Rebuilding a complete shared atlas on every change was rejected because it makes routine animation unnecessarily expensive. Rebuilding atlas chunks was also rejected because a single-node edit would still rebuild unrelated shapes.

Slughorn atlases freeze at `build()`, so independent geometry replacement cannot be implemented by mutating one packed shared atlas. The Glimmer backend therefore uses **per-draw-node atlases**. Each editable shape has its own small atlas and GPU curve/band textures. Each distinct font/codepoint glyph has its own cached atlas as well. The multi-atlas Glimmer renderer binds the appropriate texture pair for each draw node. This is separate from the single-atlas reference renderer and does not change reference-example output.

Widget identity and native resources follow Glimmer reconciliation:

- Changing transform, color, visibility, effect parameters, paint metadata, or child order updates layer/draw data only when those changes do not alter native geometry or texture contents.
- Changing a path or stroke rebuilds only that shape node's atlas, mesh, and GPU textures, then atomically replaces its resources on the UI thread.
- Changing text updates the glyph sequence, acquiring/releasing individual glyph resources. Identical glyphs share content-addressed cache entries with reference counts; a changed string does not rebuild unaffected glyphs.
- Changing gradient stops, pattern tiles, or SDF inputs rebuilds only the dependent paint/node textures. Identical path/font geometry can share cache entries, but mutable layer props never belong to the geometry cache key.
- Removing, replacing, or unmounting widgets releases their resource references. Failed mounts and partially built replacements clean up without disturbing the last good frame.

Per-node atlases trade more GPU textures and draw calls for true incremental updates. CPU atlas/mesh caches are process-local and keyed by full native-affecting data, not by reactive-cell identity. GPU texture, buffer, framebuffer, and shader-program caches are scoped to one OpenGL context. Multiple live scenes may share CPU data but never share context-owned handles; tearing down one context cannot release another context's resources.

### Full advanced Glimmer renderer

The Glimmer renderer must actually render the advanced vocabulary, not merely preserve it as metadata. It will use Slughorn's companion [osgSlug frontend](https://github.com/AlphaPixel/osgSlug) as the shader/attribute reference, without taking an OpenSceneGraph dependency. Its initial shader baseline is osgSlug `src/Atlas.shaders.cpp` and `src/Drawable/ShapeDrawable.cpp`; compatibility code is ported into Slipstream and tracked against a recorded upstream revision.

The renderer has these responsibilities:

- Standard Slug coverage with the same band/curve texture conventions as the reference example.
- Flat, linear/radial/sweep/affine-radial gradient, and repeated/reflected pattern paint, including paint transforms and opacity.
- Composite-local offscreen rendering so a procedural or SDF-tile mask applies to the composite as a whole, with inversion and nested groups. Mask placement and scale follow Slughorn's em-space contract.
- SDF/MSDF sampling and coverage effects when the corresponding native data is available.
- Coverage-correct Porter-Duff and Photoshop-style compositing through shader-side ping-pong/offscreen destinations rather than relying only on fixed-function blending. This avoids the antialiased-edge limitations documented by osgSlug and removes the need for `GL_KHR_blend_equation_advanced` on the main path.
- Effect ID/parameter propagation plus an explicit custom-effect node/program API. An effect supplies GLSL stage hooks and typed uniform data; shader programs are cached per context by their complete source and interface. Unsupported or malformed effect interfaces fail compilation with the node path instead of silently rendering a flat fill.

The Glimmer scene uses a conventional 2D orthographic projection with Y-up world/em coordinates. The default visible rectangle is configurable with `:bounds [left right bottom top]`; when absent it is `[0 aspect 0 1]`. Resize changes aspect and framebuffer dimensions without rebuilding geometry, and does not implicitly rescale authored scene coordinates. The reference example's orbit camera is not part of the Glimmer backend.

A failed incremental build keeps the previous resources only long enough to report the error without a partial frame. The error then propagates through `ui/run`, which unwinds subscriptions and native resources normally. Applications that need a persistent last-good-frame UI can catch errors around their own state updates and avoid committing invalid scene data.

### UI loop and lifecycle

The Glimmer backend implements `:create!`, `:apply-props!`, all child mutation/reorder operations, `:dispose!`, `:schedule`, and `:run-lifecycle`. Jolt drives the GLFW loop through short native calls for event polling, rendering, swap, and teardown instead of entering a blocking native run loop. While the loop runs, `glimmer.backend/loop-running?` is true and scheduled reactive work executes on the same UI thread before the next frame.

`:run-lifecycle` creates the native scene/window, mounts the root, and calls `:unmount!` before destroying native resources. On abnormal native destruction it calls `:abandon!` and releases remaining native ownership without touching dead widgets. No reactive callback may run after its widget or scene has been disposed.

`deps.edn` includes Glimmer as a dependency, using the local `/Users/yogthos/src/jolt-lang/glimmer` checkout during development. No changes to Glimmer are required.

### Reactive verification

Headless backend tests use a fake native resource factory to verify reconciler behavior, exact acquire/release counts, content-cache hits, layer-only updates, per-shape rebuilds, per-glyph text updates, paint/SDF dependency invalidation, keyed reorder, failed-mount cleanup, error unwinding, per-context GPU ownership, and unmount scheduling. Native multi-atlas tests verify that changing one node leaves every unrelated node's atlas/texture bytes and GPU handles untouched.

Renderer tests cover every paint mode, procedural and SDF masks, nested composites, blend modes, SDF/MSDF effects, custom shader effects, resize behavior, and multiple live OpenGL contexts. Golden-frame comparisons are required for each supported feature. A GUI smoke example updates text, color, position, path geometry, gradient stops, and masks from ratoms and from a queued REPL-style update. It verifies that the same window remains open and only the affected native resources change. Absence of a display must be reported as an explicit unverified GUI check, not a skipped success.

## Testing and verification

Add Jolt tests and native build/test tasks covering:

- Data normalization, references, defaults, and useful validation errors.
- Atlas/path/canvas lifecycle and post-destroy errors.
- FreeType loading and direct text layout using a known local font supplied by the test invocation/environment.
- Path geometry, fills, strokes, transforms, gradients, patterns, masks, composites, and layer metadata.
- Texture/shape inspection, mesh counts/layout, and serialization round trips.
- Native exception conversion and invalid input behavior.

For example parity, build the upstream C++ example and the Slipstream example against the same Slughorn checkout, FreeType, GLFW, GLAD, and font. Compare:

- Atlas texture descriptors and bytes for identical ASCII input and curve precision.
- Vertex/index buffers generated by the same layers.
- Usage/error output and the controls text.
- A captured initial frame at the same 800x600 framebuffer size, with byte-identical output expected on the same GPU/driver or a narrowly documented pixel tolerance if a driver difference makes that necessary.
- A scripted smoke sequence for projection toggle/reset, orbit, pan, zoom, piped text, and clean exit where the environment permits GUI automation.

If a display or GUI automation is unavailable, report that limitation explicitly; successful compilation alone is not sufficient to claim visual parity.

## Builder decisions

- Namespace and file naming details, exact private helper decomposition, and buffer iteration mechanics are left to the implementer.
- Use tests in the style of `ents` and Jolt's own toolchain; no JVM/Clojure dependencies are needed.
- Do not commit generated shared libraries, captured frames, or dependency build trees.
- Keep upstream Slughorn unchanged. Native compatibility code belongs in Slipstream.
