# Slipstream API documentation

Slipstream is a data-oriented Jolt wrapper for Slughorn. It has four complementary entry points:

- **Declarative scenes** — Hiccup-like data compiled by `slipstream.scene` / `slipstream.core`.
- **Fine-grained native operations** — explicit atlases, paths, canvases, fonts, textures, meshes, and serialization.
- **The compatibility GLFW renderer** — the upstream standard-fill example, driven from Jolt.
- **Reactive Glimmer scenes** — reconciled components with incremental per-shape/per-glyph resources and advanced rendering.

## Reference pages

- [Core and native resource API](core.md) — every public `slipstream.core` operation and its options.
- [Declarative scene DSL](scene.md) — every scene primitive, option, reference rule, and compilation behavior.
- [Reactive Glimmer guide](glimmer.md) — reactive primitives, ratoms/components, incremental updates, masks, and lifecycle.
- [Rendering and effects](rendering.md) — low-level GL contexts, draw commands, GPU resources, and custom shader effects.
- [Raw FFI and commands](ffi.md) — raw `slipstream.ffi` bindings, ABI layouts, command-line entry points, and compatibility behavior.

## Installation and verification

Requirements are Jolt, a C++20 compiler/CMake, FreeType, and a Slughorn checkout. The build fetches GLFW, GLAD, msdfgen, and Glimmer. `nlohmann/json.hpp` comes from Slughorn's submodule or a system package.

```sh
export SLUGHORN_HOME="$HOME/src/slughorn"       # optional when already the default
export SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf
jolt -P
jolt native
jolt test
jolt parity
```

Normal dependency resolution uses the versioned Glimmer Git dependency. The `:dev` alias instead uses the developer-local checkout in `deps.edn`.

Run the examples:

```sh
jolt -m slipstream.example.glfw "$SLIPSTREAM_TEST_FONT"
jolt -m slipstream.example.reactive "$SLIPSTREAM_TEST_FONT" --auto-quit-ms 10000
```

The reference example accepts `--curve-precision 16|32`, `--frames N`, and `--capture PATH`. The reactive example accepts `--auto-quit-ms N` and `--capture-dir PATH`.

## Data conventions

### Keys

Atlas shapes and composites use named or codepoint keys:

```clojure
:logo                         ; named key
"logo"                        ; the same name in string form
[:codepoint 65]               ; U+0041
[:codepoint 0x1f600]          ; Unicode scalar value
[:codepoint 65 7]             ; U+0041 in font/key namespace 7
```

Masks are bytes `0..255`. With a nonzero mask, the real codepoint is limited to `0x1fffff`; unmasked keys accept the native unsigned 32-bit range. Public Unicode text/font APIs reject surrogate-range values.

### Colors, positions, and matrices

Colors are straight-alpha `[r g b a]` vectors, normally in `0..1`. Rendering converts them to premultiplied form internally. Positions are `[x y]` or `[x y z]`. Two-dimensional affine matrices are six numbers:

```clojure
[xx yx xy yy dx dy]
; x' = xx*x + xy*y + dx
; y' = yx*x + yy*y + dy
```

Reactive Glimmer rendering is Y-up 2D. Its default visible bounds are `[0 aspect 0 1]`; provide `[left right bottom top]` to change them. Resizing changes aspect/viewport, not authored coordinates or atlas contents.

### Scale versus normalization

Public shape `:scale` is **layer placement scale**. It does not normalize or rebuild geometry. Fine-grained Canvas commit options also expose a native normalization `:scale`; declarative/reactive compilers deliberately pass `1` for that internal value and retain the user's scale only on the draw layer.

### Errors

Validation and native failures throw `ex-info`. Declarative/reactive errors normally include `:scene-path` and often `:node`; native failures include `:op` and `:native-error`. Unsupported advanced features fail explicitly rather than silently rendering a flat fill.

## Ownership in one minute

Prefer scoped constructors:

```clojure
(require '[slipstream.core :as slug])

(slug/with-atlas [atlas {:texture-width 512}]
  (slug/with-path [path]
    (slug/path-command! path [:rect 0 0 1 1])
    (slug/with-canvas [canvas atlas {}]
      (slug/fill! canvas path {:color [1 1 1 1]} {:key :rect})
      (slug/build! atlas)
      (slug/mesh atlas [{:key :rect}]))))
```

`destroy!` is idempotent at the wrapper level. Operations on destroyed values throw. A `Canvas` borrows its atlas and must not outlive it. Texture data is copied by default; `{:copy? false}` returns an atlas-scoped borrowed pointer. Reactive CPU resources are reference-counted, while GL handles belong to exactly one OpenGL context.

## Which renderer should I use?

Use `slipstream.glfw` for the upstream orbit-camera, standard-fill compatibility example. Use `slipstream.glimmer` for live 2D interfaces, gradients, patterns, masks, advanced blends, SDF/MSDF, and custom effects. The compatibility renderer does not implement those advanced features.

The Glimmer renderer trades more textures/draw calls for fully incremental updates: each editable shape or glyph has an independent atlas. This is intentional and avoids rebuilding unrelated geometry after a small reactive change.
