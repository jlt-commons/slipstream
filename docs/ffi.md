# Raw FFI, CLI, and compatibility reference

Namespace: `slipstream.ffi`. This is the untyped-by-convenience boundary for integrators. Prefer [Core](core.md), [Glimmer](glimmer.md), and [Rendering](rendering.md); raw calls require exact C lifetimes, layouts, and sentinel checks.

```clojure
(require '[slipstream.ffi :as f]
         '[jolt.ffi :as ffi])
```

`last-error` returns the current thread's native error string. Every failing raw call must be checked immediately. Native entry points prevent C++ exceptions from crossing the ABI.

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

Use `ffi/layout-size`, `ffi/read-field`, and `ffi/write-field` with these descriptors rather than duplicating byte offsets.

## Atlas and serialization bindings

| Binding | C symbol | Purpose |
|---|---|---|
| `atlas-new` | `ss_atlas_new` | Construct atlas with width/curve precision. |
| `atlas-set-sdf` | `ss_atlas_set_sdf` | Configure SDF type. |
| `atlas-destroy` | `ss_atlas_destroy` | Destroy owned atlas. |
| `atlas-build` | `ss_atlas_build` | Pack atlas. |
| `atlas-built?` | `ss_atlas_is_built` | Query built state. |
| `atlas-add-shape` | `ss_atlas_add_shape` | Add quadratic shape/metrics/splits/origin. |
| `atlas-shape` | `ss_atlas_get_shape` | Read shape into output layout. |
| `atlas-add-composite` | `ss_atlas_add_composite` | Add ordered composite/mask. |
| `atlas-composite` | `ss_atlas_get_composite` | Query composite/advance. |
| `atlas-composite-layers` | `ss_atlas_get_composite_layers` | Count/capacity layer read. |
| `atlas-composite-mask` | `ss_atlas_get_composite_mask` | Read mask. |
| `atlas-texture` | `ss_atlas_texture` | Read curve/band/SDF descriptor. |
| `shape-quad` | `ss_shape_quad` | Compute scaled quad. |
| `mesh-new` | `ss_mesh_new` | Build owned reference mesh. |
| `mesh-descriptor` | `ss_mesh_descriptor` | Read mesh descriptor. |
| `mesh-destroy` | `ss_mesh_destroy` | Destroy owned mesh. |
| `atlas-write` | `ss_atlas_write` | Write `.slug`/`.slugb`. |
| `atlas-read` | `ss_atlas_read` | Read owned built atlas. |

## Font, path, and Canvas bindings

- `font-load` → `ss_font_load`: explicit/ASCII/all loading and result output.
- `text-layout` → `ss_text_layout`: direct text layer buffer layout.
- `path-new`/`path-destroy` → `ss_path_new`/`ss_path_destroy`.
- `path-command` → `ss_path_command`; `path-add` → `ss_path_add`.
- `path-sample` → `ss_path_sample`; `path-arc-length` → `ss_path_arc_length`.
- `path-stroke` → `ss_path_stroke`.
- `atlas-add-gradient` → `ss_atlas_add_gradient`; `atlas-add-pattern` → `ss_atlas_add_pattern`.
- `canvas-new`/`canvas-destroy` → `ss_canvas_new`/`ss_canvas_destroy`.
- `canvas-command` → `ss_canvas_command`; `canvas-add-path` → `ss_canvas_add_path`.
- `canvas-set-splits` → `ss_canvas_set_splits`.
- `canvas-fill`, `canvas-stroke`, `canvas-define-shape`, `canvas-text`, `canvas-finalize` → corresponding `ss_canvas_*` symbols.
- `canvas-set-mask` and `canvas-mask-path` → `ss_canvas_set_mask`/`ss_canvas_mask_path`.

Canvas commit outputs may contain borrowed named-key storage. Copy data while it is valid; core wrappers already handle this.

## Reactive GL bindings

- `gl-context-create`/`gl-context-destroy` → `ss_gl_context_create`/`ss_gl_context_destroy`.
- `gl-poll`, `gl-should-close`, `gl-swap` → event/close/present operations.
- `gl-program` → `ss_gl_program`; built-in `"paint"` program.
- `gl-effect-program` → `ss_gl_effect_program`; compile/cache custom effect.
- `gl-upload`, `gl-paint-texture`, `gl-release` → context-owned resource operations.
- `gl-render` → `ss_gl_render`; `gl-error-command` → `ss_gl_error_command` for exact failing index.
- `gl-test-resize`, `gl-test-bind-nondefault`, `gl-test-restore-default`, `gl-test-fail-next`, `gl-test-frame-state`, `gl-test-capture` → test-only native instrumentation.

All GL handles are context-owned. Passing a handle from another context is an error, not a share operation.

## Compatibility GLFW bindings

- `glfw-run` → `ss_glfw_run`, marked `:blocking`; accepts atlas, layers view, title, curve precision, optional capture path, and max frames.
- `read-stdin-text` → `ss_read_stdin_text`, marked `:blocking`; returns owned C string or nil for interactive stdin.
- `free-stdin-text` → `ss_free_stdin_text`.

Use `slipstream.glfw/run!` and `read-stdin-text` to get automatic scene/native-buffer/string cleanup.

## `slipstream.glfw` API

### `(run! scene options)`

Compiles if necessary, scopes native scene/layer data, and runs the reference renderer. Options:

- `:title` — default `"slughorn"`.
- `:capture-path` — optional PPM output.
- `:max-frames` — optional positive integer; nil runs interactively.

It returns `nil` after clean exit and throws on native failure. The renderer supports standard fill only, actual atlas widths, and the upstream orbit/pan/zoom/projection/reset controls.

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

## Compatibility versus advanced rendering

The compatibility renderer intentionally mirrors the upstream standard-fill shader. It does not render gradients, patterns, masks, advanced blends, SDF effects, or custom effects. The Glimmer renderer implements those features with per-node atlases, offscreen compositing, and exact context ownership.
