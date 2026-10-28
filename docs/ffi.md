# Raw FFI, CLI, and compatibility reference

Namespace: `slipstream.ffi`. This is the untyped-by-convenience boundary for integrators. Prefer [Core](core.md), [Glimmer](glimmer.md), and [Rendering](rendering.md); raw calls require exact C lifetimes, layouts, and sentinel checks.

```clojure
(require '[slipstream.ffi :as f]
         '[jolt.ffi :as ffi])
```

`last-error` returns the current thread's native error string. Every failing raw call must be checked immediately. Native entry points prevent C++ exceptions from crossing the ABI.

## Calling and return conventions

The tables below show Jolt argument types and native return types. Unless noted otherwise:

- `:int32` operations return `0` for success and nonzero for failure. Queries documented as found/status operations return `1` for found/success, `0` for absent, and negative for failure.
- Pointer-returning constructors return a nonzero owned handle or `0`/nil for failure. Owned atlas/path/canvas/mesh/context/resource handles must be released with their matching destroy/release call.
- `atlas-built?` and `gl-should-close` return booleans; `false` is a legitimate answer, so inspect `last-error` when in doubt.
- Gradient/pattern creation returns a positive one-based ID or negative for failure.
- Strings returned as pointers have operation-specific borrowed or owned lifetimes. Copy borrowed strings before the next native call; call the documented free function for owned strings.

Count/capacity protocols:

- `atlas-composite-layers` first accepts `nil`/zero buffer and capacity to write the required count, then a buffer of at least that many `layer-layout` records and the capacity. It returns `1` for success, `0` for absent, and negative for failure.
- `text-layout` similarly performs a count query followed by a layer-buffer write; it returns `0` for success and nonzero for failure.
- Canvas fill/stroke writes a layer on success. A returned named-key string is valid until the next fill/stroke commit or canvas destruction.
- `gl-paint-texture` returns zero when a resource has no advanced paint; this is not itself an error, so check `last-error` to distinguish failure.
- `gl-error-command` returns the failing zero-based command index or a negative value when no command was reached.

A checked raw lifecycle call looks like:

```clojure
(let [atlas (f/atlas-new 512 32)]
  (when (or (nil? atlas) (zero? atlas))
    (throw (ex-info "atlas-new failed" {:error (f/last-error)})))
  (try
    (when-not (zero? (f/atlas-build atlas))
      (throw (ex-info "atlas-build failed" {:error (f/last-error)})))
    (finally
      (when-not (zero? (f/atlas-destroy atlas))
        (throw (ex-info "atlas-destroy failed" {:error (f/last-error)}))))))
```

## ABI layouts

Public layout vars:

- `key-layout` — type, codepoint, name pointer.
- `color-layout` — four floats.
- `transform-layout` — three floats.
- `layer-layout` — key, color, transform, scale, effect/paint IDs, effect parameter, bleed, draw/blend modes.
- `shape-layout` — band fields, metrics, origin fields.
- `quad-layout` — `x0 y0 x1 y1`.
- `texture-layout` — bytes pointer/count, width/height/depth/format.
- `mesh-layout` — vertex/index pointers and counts.
- `path-sample-layout` — position, angle, arc length.
- `gradient-stop-layout` — offset and color.
- `mask-layout` — key, six params, type/invert/has-key bytes.
- `font-metrics-layout` — units-per-em and cap/x/ascender/descender ratios.
- `font-load-layout` — loaded count, metrics, family/style pointers.
- `layers-view-layout` — data pointer and count.
- `gl-context-options-layout` — title, dimensions, bounds, visibility.
- `gl-draw-command-layout` — resource/paint/effect handles and full draw/mask state.
- `gl-uniform-layout` — name, type/components, separate float/int/uint arrays of 16.
- `gl-frame-state-layout` — viewport/FBO/sizes/projection and instrumentation counters.
- `gl-input-layout` — cursor x/y, scroll x/y deltas, left/right/middle buttons, Space, and R.
- `gl-cursor-layout` — native window-coordinate cursor x/y doubles.

Use `ffi/layout-size`, `ffi/read-field`, and `ffi/write-field` with these descriptors rather than duplicating byte offsets.

## Atlas and serialization bindings

| Binding and argument types | Return | C symbol / contract |
|---|---|---|
| `(atlas-new width precision)` — `:uint32 :int32` | `:pointer` | `ss_atlas_new`; owned atlas. |
| `(atlas-set-sdf atlas type)` — `:pointer :uint32` | `:int32` | `ss_atlas_set_sdf`. |
| `(atlas-destroy atlas)` — `:pointer` | `:int32` | `ss_atlas_destroy`. |
| `(atlas-build atlas)` — `:pointer` | `:int32` | `ss_atlas_build`. |
| `(atlas-built? atlas)` — `:pointer` | `:bool` | `ss_atlas_is_built`. |
| `(atlas-add-shape atlas key curves count auto-metrics bx by width height advance bands-x bands-y splits-x nx splits-y ny origin-type ox oy)` — pointer, pointer, pointer, `:uint64 :bool`, five `:float`, two `:int32`, pointer `:uint64`, pointer `:uint64`, `:uint32`, two `:float` | `:int32` | `ss_atlas_add_shape`. |
| `(atlas-shape atlas key out)` — three pointers | `:int32` | `ss_atlas_get_shape`; `1` found, `0` absent, negative error. |
| `(atlas-add-composite atlas key layers count advance mask)` — three pointers, `:uint64 :float :pointer` | `:int32` | `ss_atlas_add_composite`. |
| `(atlas-composite atlas key advance-out)` — three pointers | `:int32` | `ss_atlas_get_composite`; found/absent/error status. |
| `(atlas-composite-layers atlas key buffer capacity count-out)` — three pointers, `:uint64 :pointer` | `:int32` | `ss_atlas_get_composite_layers`; two-call count/capacity protocol. |
| `(atlas-composite-mask atlas key out)` — three pointers | `:int32` | `ss_atlas_get_composite_mask`; `1` success. |
| `(atlas-texture atlas kind out)` — two pointers, `:uint32` | `:int32` | `ss_atlas_texture`. |
| `(shape-quad shape transform scale out)` — `:pointer :pointer :float :pointer` | `:int32` | `ss_shape_quad`. |
| `(mesh-new atlas layers count)` — two pointers, `:uint64` | `:pointer` | `ss_mesh_new`; owned mesh. |
| `(mesh-descriptor mesh out)` — two pointers | `:int32` | `ss_mesh_descriptor`. |
| `(mesh-destroy mesh)` — `:pointer` | `:int32` | `ss_mesh_destroy`. |
| `(atlas-write atlas path)` — `:pointer :string` | `:int32` | `ss_atlas_write`. |
| `(atlas-read path)` — `:string` | `:pointer` | `ss_atlas_read`; owned built atlas. |

## Font, path, and Canvas bindings

| Binding and argument types | Return | C symbol / contract |
|---|---|---|
| `(font-load atlas path codepoints count mode uniform mask out)` — pointer, string, pointer, `:uint64 :uint32 :bool :uint8 :pointer` | `:int32` | `ss_font_load`; mode `0` explicit, `1` ASCII, `2` all. |
| `(text-layout atlas codepoints count font-size x y color metrics anchor align mask layers capacity count-out)` — two pointers, `:uint64`, three `:float`, two pointers, two `:uint32 :uint8 :pointer :uint64 :pointer` | `:int32` | `ss_text_layout`; two-call count/capacity protocol. |
| `(path-new)` | `:pointer` | `ss_path_new`; owned path. |
| `(path-destroy path)` — `:pointer` | `:int32` | `ss_path_destroy`. |
| `(path-command path id values count)` — two pointers, `:uint32 :pointer :uint64` | `:int32` | `ss_path_command`. |
| `(path-add target source matrix)` — three pointers | `:int32` | `ss_path_add`; null matrix means identity. |
| `(path-sample path t out)` — two pointers, `:float` | `:int32` | `ss_path_sample`. |
| `(path-arc-length path out)` — two pointers | `:int32` | `ss_path_arc_length`. |
| `(path-stroke path width clockwise join cap miter-limit)` — pointer, `:float :bool :uint32 :uint32 :float` | `:int32` | `ss_path_stroke`. |
| `(atlas-add-gradient atlas type geometry geometry-count stops stop-count transform)` — pointer, `:uint32`, two pointers, two `:uint64`, pointer | `:int64` | `ss_atlas_add_gradient`; positive ID/negative error. |
| `(atlas-add-pattern atlas tile cell transform repeat-x repeat-y)` — four pointers, two `:uint32` | `:int64` | `ss_atlas_add_pattern`; positive ID/negative error. |
| `(canvas-new atlas key-start)` — pointer, `:uint32` | `:pointer` | `ss_canvas_new`; owned Canvas borrowing atlas. |
| `(canvas-destroy canvas)` — `:pointer` | `:int32` | `ss_canvas_destroy`. |
| `(canvas-command canvas id values count)` — two pointers, `:uint32 :pointer :uint64` | `:int32` | `ss_canvas_command`. |
| `(canvas-add-path target source matrix)` — three pointers | `:int32` | `ss_canvas_add_path`. |
| `(canvas-set-splits canvas xs nx ys ny)` — `:pointer :pointer :uint64 :pointer :uint64` | `:int32` | `ss_canvas_set_splits`. |
| `(canvas-fill canvas path key color scale origin-type ox oy gradient-id pattern-id out)` — three pointers, by-value color, `:float :uint32`, two `:float`, two `:uint32`, pointer | `:int32` | `ss_canvas_fill`; writes layer. |
| `(canvas-stroke canvas path key width color scale origin-type ox oy join cap miter-limit gradient-id pattern-id out)` — three pointers, `:float`, by-value color, `:float :uint32`, two `:float`, three `:uint32 :float`, two `:uint32`, pointer | `:int32` | `ss_canvas_stroke`; writes layer. |
| `(canvas-define-shape canvas path key scale origin-type ox oy)` — three pointers, `:float :uint32`, two `:float` | `:int32` | `ss_canvas_define_shape`. |
| `(canvas-text canvas text size x y color metrics anchor align mask)` — pointer, string, three `:float`, by-value color, pointer, two `:uint32 :uint8` | `:int32` | `ss_canvas_text`; upstream byte-wise text. |
| `(canvas-finalize canvas key)` — two pointers | `:int32` | `ss_canvas_finalize`. |
| `(canvas-set-mask canvas mask)` — two pointers | `:int32` | `ss_canvas_set_mask`; null clears mask. |
| `(canvas-mask-path canvas range invert out)` — pointer, `:float :bool :pointer` | `:int32` | `ss_canvas_mask_path`; writes mask. |

Canvas commit outputs may contain borrowed named-key storage. Copy data while it is valid; core wrappers already handle this.

## Reactive GL bindings

| Binding and argument types | Return | C symbol / contract |
|---|---|---|
| `(gl-context-create options)` — `:pointer` | `:pointer` | `ss_gl_context_create`; owned context. |
| `(gl-context-destroy context)` — `:pointer` | `:int32` | `ss_gl_context_destroy`. |
| `(gl-poll context)` / `(gl-swap context)` — `:pointer` | `:int32` | `ss_gl_poll` / `ss_gl_swap`. |
| `(gl-should-close context)` — `:pointer` | `:bool` | `ss_gl_should_close`. |
| `(gl-input-snapshot context out)` — two `:pointer` | `:int32` | `ss_gl_input_snapshot`; fills `gl-input-layout`, consuming accumulated wheel deltas. |
| `(gl-cursor-snapshot context out)` — two `:pointer` | `:int32` | `ss_gl_cursor_snapshot`; fills `gl-cursor-layout` without consuming input deltas. |
| `(gl-request-close context)` — `:pointer` | `:int32` | `ss_gl_request_close`; sets the native close flag for orderly loop teardown. |
| `(gl-program context key)` — `:pointer :string` | `:pointer` | `ss_gl_program`; key `"paint"`. |
| `(gl-effect-program context program-key effect-key declarations vertex-hook fragment-hook requires-sdf uniforms count)` — six pointers, `:uint8 :pointer :uint64` | `:pointer` | `ss_gl_effect_program`; context-owned effect instance. |
| `(gl-upload context atlas root-layer)` — three pointers | `:pointer` | `ss_gl_upload`; context-owned resource. |
| `(gl-paint-texture context resource)` — two pointers | `:pointer` | `ss_gl_paint_texture`; zero means no advanced paint. |
| `(gl-release context resource)` — two pointers | `:int32` | `ss_gl_release`. |
| `(gl-render context commands count)` — two pointers, `:uint64` | `:int32` | `ss_gl_render`. |
| `(gl-error-command context)` — `:pointer` | `:int64` | `ss_gl_error_command`; failing index or negative. |
| `(gl-test-resize context width height)` — pointer, two `:int32` | `:int32` | Test-only `ss_gl_test_resize`. |
| `(gl-test-bind-nondefault context x y width height)` — pointer, four `:int32` | `:int32` | Test-only `ss_gl_test_bind_nondefault`. |
| `(gl-test-restore-default context)` — `:pointer` | `:int32` | Test-only `ss_gl_test_restore_default`. |
| `(gl-test-fail-next context point)` — `:pointer :uint32` | `:int32` | Test-only `ss_gl_test_fail_next`. |
| `(gl-test-frame-state context out)` — two pointers | `:int32` | Test-only `ss_gl_test_frame_state`. |
| `(gl-test-capture context pixels byte-count)` — two pointers, `:uint64` | `:int32` | Test-only `ss_gl_test_capture`. |

All GL handles are context-owned. Passing a handle from another context is an error, not a share operation.

## Compatibility GLFW bindings

| Binding and argument types | Return | C symbol / contract |
|---|---|---|
| `(glfw-run atlas layers-view title curve-precision capture-path max-frames)` — pointer, by-value layers view, pointer, `:int32 :pointer :int32` | `:int32` | `ss_glfw_run`, marked `:blocking`; zero success, nonzero failure. |
| `(read-stdin-text)` | `:pointer` | `ss_read_stdin_text`, marked `:blocking`; owned C string or nil for interactive stdin. |
| `(free-stdin-text pointer)` — `:pointer` | `:void` | `ss_free_stdin_text`; releases the owned string. |

Use `slipstream.glfw/run!` and `read-stdin-text` to get automatic scene/native-buffer/string cleanup.

## `slipstream.glfw` API

### `(run! scene options)`

Compiles if necessary, scopes native scene/layer data, and runs the reference renderer. Options:

- `:title` — default `"slughorn"`.
- `:capture-path` — optional PPM output.
- `:max-frames` — optional positive integer; nil runs interactively.

It returns `nil` after clean exit and throws on native failure. **Ownership of the scene transfers to `run!`**: it is destroyed on every exit, so do not reuse a caller-supplied `Scene` afterward. The renderer supports standard fill only, actual atlas widths, and the upstream orbit/pan/zoom/projection/reset controls.

### `(read-stdin-text)`

Returns piped text or `nil` when stdin is interactive. Native string memory is always released.

## Reference example CLI

```sh
jolt -m slipstream.example.glfw <font.ttf> [--curve-precision 16|32]
                                      [--frames N] [--capture PATH]
```

Default text is `slughorn`; nonempty piped stdin replaces it after trimming trailing CR/LF. Compatibility layout maps each UTF-8 byte to a glyph key, skips missing/zero-area glyphs, advances missing glyphs by `0.5`, and uses each present glyph's advance. Whitespace-only input fails with `No geometry produced` before opening a window.

Controls: left drag orbits, right drag pans, wheel zooms, `P` toggles perspective/orthographic, `R` resets, and `Esc` quits.

## Reactive example CLI

```sh
jolt -m slipstream.example.reactive <font.ttf> [--auto-quit-ms N] [--capture-dir PATH]
```

Timed mode runs the full reactive checkpoint/golden sequence. Use `--auto-quit-ms 10000` for the established complete run. The example demonstrates text, placement/color, geometry, gradient, mask, blend, SDF effect parameter, and queued REPL updates in one window.

## Live demoscene CLI

```sh
jolt -m slipstream.example.demoscene <font.ttf> [--port N] [--capture-dir PATH]
                                               [--max-frames N] [--auto-quit-ms N]
```

The demoscene uses the reactive GL input/close bindings above and starts rich default/CIDER nREPL middleware before native UI creation. Its default port is `7888`; `0` selects an OS-assigned port. With no frame/time limit it runs until native close or `slipstream.example.demoscene/stop!`. See the [demoscene guide](demoscene.md) for controls, `start!`/`stop!`, live evaluation, and captures.

## Compatibility versus advanced rendering

The compatibility renderer intentionally mirrors the upstream standard-fill shader. It does not render gradients, patterns, masks, advanced blends, SDF effects, or custom effects. The Glimmer renderer implements those features with per-node atlases, offscreen compositing, and exact context ownership.
