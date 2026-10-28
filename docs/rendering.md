# Rendering and effects

This page covers the lower-level reactive rendering namespaces:

```clojure
(require '[slipstream.glimmer-renderer :as renderer]
         '[slipstream.glimmer-effects :as effects]
         '[slipstream.glimmer-compiler :as compiler]
         '[slipstream.glimmer-resources :as resources])
```

Most applications should use [Glimmer components](glimmer.md) instead. These APIs are for custom backends, native integration, instrumentation, and advanced effect authoring.

## GL contexts

`GLContext` fields are `handle`, `options`, `destroyed?`, `cache`, `work-queue`, `running?`, and `accepting-work?`. Use constructor/lifecycle functions rather than direct construction.

### `(create-context & [options])`

Creates a native window/context. Options:

- `:title` — default `"glimmer"`.
- `:width`, `:height` — defaults `400`, `300`.
- `:bounds` — optional numeric `[left right bottom top]`.
- `:visible` — default `true`.

### `(destroy! context)`

Stops scheduling, destroys the native context and all its GPU resources, releases CPU references, and is wrapper-idempotent.

### `(stop-scheduling! context)`

Atomically marks scheduling terminal, stops the run state, and clears queued callbacks. Late work is dropped.

### `(run-loop! context options)`

Runs the Jolt-owned UI loop. Options:

- `:draw-tree` — command vector or zero-arg function returning one.
- `:max-frames`, `:auto-quit-ms` — optional stop conditions.
- `:on-start` — called once before frames.
- `:after-render` — called after every render, before buffer swap.

Each frame polls, drains scheduled work on the UI thread, renders, invokes `after-render`, and swaps. Scheduling is terminal on every exit.

### Context operations

- `(poll! context)` — process native events.
- `(should-close? context)` — native close flag as boolean.
- `(swap-buffers! context)` — present the frame.
- `(schedule! context work)` — run immediately only before the loop starts; queue while running; drop after teardown.
- `(capture! context)` — return `{:width w :height h :pixels byte-array}` containing RGBA bytes. This is test/instrumentation API.

## GPU resources

### `(upload! context cpu-resource)`

Uploads a `CPUResource` atlas/root and returns a native resource handle. The atlas must be alive.

### `(release! context handle)`

Releases a native handle, rejecting stale or cross-context handles.

### `(acquire-resource! context cpu-resource)`

Acquires a context-cached GPU wrapper with reference counting. It returns a resource map usable by `native-handle`, `native-paint-handle`, and `glimmer-resources/release!`.

### `(native-handle resource)` / `(native-paint-handle resource)`

Return the native atlas resource handle and associated advanced-paint handle (zero when none).

### `(paint-texture! context native-resource-handle)`

Returns native advanced-paint texture/descriptive handle or zero. This takes the native handle from `upload!`/`native-handle`, not a `CPUResource`.

### `(program! context shader-key)`

Returns the context's built-in program. Currently only `:paint` is accepted; custom programs use `effect-program!`.

## Low-level draw commands

`(render! context draw-tree)` accepts a vector of geometry and group commands.

Geometry command keys:

```clojure
{:handle native-resource
 :paint native-paint-handle
 :effect native-effect-handle
 :transform [x y z]
 :scale 1
 :color [r g b a]
 :effect-id 0
 :effect-param 0
 :bleed 0
 :visible true
 :draw-mode :visible
 :blend-mode :src-over
 :scene-path [:shape]}
```

`:handle`, `:paint`, and `:effect` must belong to the context and agree with one another. Nonzero effect IDs require custom effect programs.

Group commands are:

```clojure
{:begin-group mask-or-nil
 :mask-handle sdf-mask-resource-or-zero
 :transform [x y z] :scale 1 :color [1 1 1 1]
 :scene-path [:group]}
;; child commands
{:end-group blend-mode :scene-path [:group]}
```

Mask maps support `:type`, `:params`, and `:invert`; types are `:sdf-tile`, `:circle`, `:rect`, `:capsule`, `:arc`, `:arc-band`, `:hexagon`, `:octagon`, and `:star`. Nested groups restore previous FBO/viewport state on success and exceptions.

## Reactive CPU compiler

`CPUResource` fields are `atlas`, `root`, `mesh`, `cache-key`, and `ref-count`; glyph resources also retain `metrics` and `shape`.

### `(geometry-key node)`

Returns the immutable native-affecting cache key. Layer-only fields and authored scene paths are excluded. Flat-color changes, including local named flat colors, are canonicalized away; all native geometry/paint/font inputs remain.

### `(compile-shape node)`

Builds/acquires one independent shape atlas resource. Node options match reactive shapes.

### `(compile-glyph font codepoint opts)`

Builds/acquires one glyph resource, preserving metrics and advanced paint. Codepoints must be Unicode scalar values.

### `(text-codepoints props)`

Returns Unicode codepoints or UTF-8 byte keys when `:compatibility`, `:byte-wise`, or `:byte-wise?` is true. Alias values must be boolean.

### `(validate-paint-references! node)`

Rejects raw reactive `:gradient-id`/`:pattern-id` with an authored scene path.

### `(update-node! widget old-props new-props & [acquire])`

Reconciles a widget resource, using `compile-shape`/`compile-glyph` by default. Returns `{:acquired [...] :released [...]}`. Acquisition rollback never destroys already committed replacements.

## Resource ownership

### `(acquire-cpu! cache-key build)`

Returns a process-wide cached `CPUResource`, incrementing its reference count or calling `build` once.

### `(acquire! factory context cache-key props)`

Creates a factory-backed resource wrapper. Factory supports `:create!`, `:release!`, and `:update-layers!` and is mainly useful for custom/headless backends.

### `(release! resource)`

Releases a CPU or factory/GPU wrapper exactly once; final CPU release destroys its atlas.

### `(update-layers! resource props)`

Calls a factory wrapper's layer-update function without changing geometry.

### `(dispose-tree! widget)`

Idempotently marks a widget disposed and releases descendants, shape/mask resources, and glyph resources.

## Custom effects

Namespace: `slipstream.glimmer-effects`.

### Effect maps

```clojure
{:id 7
 :param 1.0
 :sdf true
 :bleed 0.1
 :uniforms {:tint {:type :vec4 :value [1 1 1 1]}}
 :vertex-hook "..."
 :fragment-hook "..."}
```

- `:id` — required unsigned 32-bit integer.
- `:param` — numeric, default `0`.
- `:sdf` — boolean; true requires a baked tile.
- `:bleed` — nonnegative quad expansion, default `0`.
- `:uniforms` — optional map of typed uniform specs.
- `:vertex-hook`, `:fragment-hook` — optional GLSL strings.

### Hook signatures

Vertex:

```glsl
SlipstreamVertex slipstream_vertex(SlipstreamVertex input, float effectParam);
```

Fragment:

```glsl
vec4 slipstream_fragment(vec4 color, float fill, float sd,
                         vec2 emCoord, float effectParam);
```

Fragment hooks return **straight**, not premultiplied, color/alpha. Hooks can read `u_effectId`, `u_effectParam`, and custom `u_*` uniforms. SDF helpers include `slipstream_sdf_sample` and `slipstream_sdf_coverage`.

### Uniform types

| Family | Types | Component rules |
|---|---|---|
| Float | `:float`, `:vec2`, `:vec3`, `:vec4` | Numeric scalar/vector of matching length. |
| Signed int | `:int`, `:ivec2`, `:ivec3`, `:ivec4` | Integral, signed 32-bit range. |
| Unsigned int | `:uint`, `:uvec2`, `:uvec3`, `:uvec4` | Integral, `0..4294967295`. |
| Boolean | `:bool`, `:bvec2`, `:bvec3`, `:bvec4` | Actual boolean components. |
| Matrix | `:mat3`, `:mat4` | 9 or 16 numeric values. |

Scalar specs use `{:type :int :value 3}`; vectors use `{:type :ivec2 :value [1 2]}`. Names must match `[A-Za-z_][A-Za-z0-9_]*`; GLSL declarations receive a `u_` prefix. Integer/boolean values use exact 32-bit native transport rather than floats.

### `(normalize-effect effect)`

Validates and canonicalizes an effect, including sorted uniform declarations and complete cache keys. Most callers use `effect-program!` instead.

### `(effect-program! context effect)`

Returns normalized effect data with a native `:handle`. Linked programs are cached per context by complete hook sources, typed uniform interface, and SDF requirement; effect instances carry their own uniform values. Compilation/link errors include the authored scene path and never fall back to flat fill.

### Complete SDF effect example

```clojure
(def sdf-fragment
  "vec4 slipstream_fragment(vec4 color, float fill, float sd,
                            vec2 emCoord, float effectParam) {
     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
     return vec4(color.rgb * u_tint.rgb, color.a * coverage * effectParam);
   }")

[:shape {:path [[:circle 0.5 0.5 0.4]]
         :fill [0.2 0.8 1 1]
         :sdf true :sdf-type :msdf :sdf-range 0.15
         :effect {:id 7 :param 1.0 :sdf true :bleed 0.1
                  :uniforms {:tint {:type :vec4 :value [1 1 1 1]}}
                  :fragment-hook sdf-fragment}}]
```

Changing only `:param` updates draw data without relinking or rebuilding the atlas.
