# Slipstream Advanced Glimmer Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a fully incremental Glimmer backend that renders reactive Slughorn shapes, text, paints, masks, blends, SDF/MSDF, and custom effects without rebuilding unrelated native resources.

**Architecture:** Glimmer reconciles native widget records while Slipstream maps each drawable to independently owned CPU/GPU resources. Editable shapes and font/codepoint glyphs use per-node atlases. Jolt drives short native GLFW/OpenGL calls on the UI thread; advanced rendering uses per-context shader/resource caches and offscreen compositing.

**Tech Stack:** Jolt/Clojure, Glimmer, `jolt.ffi`, C++20, Slughorn, FreeType, OpenGL 3.3/FBO, GLFW 3.4, GLAD, osgSlug shader semantics at revision `b1928b4d7abce8bc47148c151c82ae614c05e11b`, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-27-slughorn-wrapper-design.md`

**Prerequisite:** `docs/superpowers/plans/2026-10-27-slughorn-wrapper.md`, especially its safe atlas/path/font APIs and native GLFW dependency setup.

## Global Constraints

- Do not change Glimmer, osgSlug, or upstream Slughorn; record the osgSlug reference revision in `native/OSGSLUG_REVISION`.
- Fully incremental means unrelated CPU atlas bytes and context GPU handles remain untouched by a node update.
- CPU caches may be process-wide; every GL texture/buffer/FBO/program belongs to exactly one context.
- Every advanced prop must render or throw a node-path error; no silent flat-fill fallback.
- Failed updates preserve the last good resources until error reporting, then unwind through `glimmer.core/run`.
- Use a resizable Y-up 2D orthographic projection; default bounds are `[0 aspect 0 1]`.
- Do not run git commits or other git mutations unless the user explicitly requests them.

## Review Focus

- Off-main-thread ratom changes and queued work after disposal: expect UI-thread execution or safe dropping, never use-after-free; test in Tasks 1 and 3.
- A changed glyph/paint/tile/SDF input: expect only dependent cache entries to invalidate; test in Task 2 and Task 4.
- Two simultaneous scenes: expect shared CPU data where legal but disjoint GL handles and independent teardown; test in Task 3.
- Invalid GLSL, missing SDF data, and impossible paint combinations: expect scene-path errors and normal lifecycle cleanup; test in Tasks 4–6.
- Partially covered Porter-Duff/Photoshop pixels and nested masks: expect coverage-correct offscreen compositing, not fixed-function edge artifacts; test in Task 5.
- Resizing and high-DPI framebuffers: expect updated aspect/viewport with unchanged authored coordinates and no atlas rebuild; test in Task 3.

---

### Task 1: Glimmer widget tree and resource protocol

**Files:**
- Modify: `deps.edn`
- Create: `src/slipstream/glimmer.clj`
- Create: `src/slipstream/glimmer_resources.clj`
- Test: `test/slipstream/glimmer_test.clj`
- Modify: `test/slipstream/test_runner.clj`

**Interfaces:**
- Produces backend registration on requiring `slipstream.glimmer`, implementing all required child functions plus `:reorder-child!`, `:dispose!`, `:schedule`, and `:run-lifecycle`.
- Produces resource protocol functions `(acquire! factory context cache-key props)`, `(release! resource)`, `(update-layers! resource props)`, `(dispose-tree! widget)`.
- Widget: atom containing `{:tag :props :children :context :resource :disposed?}`; native code never sees the reconciler's instance atoms.

- [ ] **Step 1: Add Glimmer and write failing headless backend tests**
  Use `jolt-lang/glimmer {:local/root "/Users/yogthos/src/jolt-lang/glimmer"}` and a fake resource factory. Test mount, prop patches, child insertion/removal/replacement, keyed reorder, nested disposal, failed mount, and Form-2 ratom rerender. Assert exact acquire/release calls and widget identity.

- [ ] **Step 2: Run tests and verify registration/protocol absence fails**
  Run: `jolt test`
  Expected: FAIL on unresolved backend/resource functions.

- [ ] **Step 3: Implement the reconciler backend and ownership protocol**
  Requiring the namespace registers the backend. Child mutations attach/detach resource ownership recursively. Geometry-affecting prop changes request a replacement; layer-only changes call `update-layers!`. No OpenGL work is required in this task.

- [ ] **Step 4: Run headless backend tests**
  Run: `jolt test`
  Expected: PASS, including a failed replacement leaving the old resource alive until error propagation and cleanup.

### Task 2: Per-node atlas, glyph, and dependency caches

**Files:**
- Modify: `src/slipstream/glimmer_resources.clj`
- Create: `src/slipstream/glimmer_compiler.clj`
- Test: `test/slipstream/glimmer_resources_test.clj`

**Interfaces:**
- Produces `(geometry-key node) -> immutable-key`, `(compile-shape node) -> CPUResource`, `(compile-glyph font codepoint opts) -> CPUResource`.
- `CPUResource` contains built atlas, root layer/mesh, cache key, and ref count; it contains no GL handles.
- Produces `(update-node! widget old-props new-props)`, returning only the changed resource references.

- [ ] **Step 1: Write failing incremental cache tests**
  Assert identical geometry shares CPU data; color/position/order changes compile nothing; path/stroke changes rebuild one shape; text edits acquire only added glyphs and release only removed glyphs; duplicate glyphs are ref-counted; gradient/pattern/SDF keys include every native-affecting input.

- [ ] **Step 2: Run cache tests and verify compiler absence fails**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: FAIL on unresolved per-node compiler functions.

- [ ] **Step 3: Implement independent atlas compilation and dependency invalidation**
  Build one atlas per editable shape and per font/codepoint glyph using the core APIs. Cache by complete immutable geometry/paint/font configuration. Compile replacements before swapping references and release replaced references exactly once.

- [ ] **Step 4: Verify byte stability for unrelated nodes**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: PASS; updating one node leaves every unrelated atlas's descriptor and bytes identical.

### Task 3: Multi-context OpenGL resources and Jolt-driven UI loop

**Files:**
- Create: `native/reactive_renderer.cpp`
- Create: `native/reactive_renderer.hpp`
- Create: `src/slipstream/glimmer_renderer.clj`
- Modify: `native/slipstream_shim.h`
- Modify: `native/CMakeLists.txt`
- Modify: `src/slipstream/glimmer.clj`
- Test: `test/slipstream/glimmer_renderer_test.clj`

**Interfaces:**
- Produces C: `ss_gl_context_create(opts)`, `ss_gl_context_destroy(ctx)`, `ss_gl_poll(ctx)`, `ss_gl_should_close(ctx)`, `ss_gl_upload(ctx, atlas, root) -> handle`, `ss_gl_release(ctx, handle)`, `ss_gl_render(ctx, commands, count)`, `ss_gl_swap(ctx)`.
- Produces Clojure: `(upload! context cpu-resource)`, `(render! context draw-tree)`, `(run-loop! context lifecycle)`.
- GL handles are scoped to their creating context and checked on every operation.

- [ ] **Step 1: Write failing context/lifecycle tests**
  Test two live contexts, rejection of cross-context handles, independent teardown, scheduled off-thread ratom changes executing on the UI thread, post-disposal queued work being dropped, resize/high-DPI viewport updates without compilation, and unmount-before-destroy ordering.

- [ ] **Step 2: Run tests and verify missing reactive renderer failure**
  Run: `jolt test`
  Expected: FAIL on unresolved context/renderer functions.

- [ ] **Step 3: Implement the multi-atlas standard-fill renderer**
  Port only the needed osgSlug coverage/attribute conventions, without OpenSceneGraph. Upload independent curve/band textures and mesh buffers per node. Implement default `[0 aspect 0 1]` Y-up orthographic projection and optional `:bounds [left right bottom top]`.

- [ ] **Step 4: Implement the Jolt-owned frame loop**
  Poll events, drain scheduled thunks on the UI thread, render, swap, and finally unmount/abandon before context destruction. Standard reactive rendering calls are short and nonblocking; no native callback into Jolt is needed.

- [ ] **Step 5: Run context tests and GUI smoke**
  Run: `jolt test` and a two-window one-frame smoke.
  Expected: headless tests PASS; GUI smoke produces two independent frames or is explicitly reported unverified with the display error.

### Task 4: Gradient and pattern rendering

**Files:**
- Create: `native/advanced_shaders.cpp`
- Create: `native/paint.cpp`
- Modify: `native/reactive_renderer.cpp`
- Modify: `src/slipstream/glimmer_renderer.clj`
- Test: `test/slipstream/glimmer_paint_test.clj`

**Interfaces:**
- Produces draw-command paint variants for flat color, linear/radial/sweep/affine-radial gradients, and repeat/reflect patterns, with transform, opacity, texture references, and effect parameters.
- Produces per-context `(program! context shader-key)` and `(paint-texture! context paint-resource)`.

- [ ] **Step 1: Add golden-frame tests for every paint mode**
  Cover all gradient types, stop changes, opacity, repeat/reflect patterns, tile changes, transforms, and malformed/unknown paint references. Assert only dependent paint textures/programs change.

- [ ] **Step 2: Port and integrate osgSlug paint semantics**
  Record revision `b1928b4d7abce8bc47148c151c82ae614c05e11b` in `native/OSGSLUG_REVISION`. Adapt `Atlas.shaders.cpp` paint behavior and `ShapeDrawable.cpp` attribute/uniform packing to the multi-atlas renderer. Do not add OpenSceneGraph.

- [ ] **Step 3: Run paint golden frames and regression suite**
  Run: `jolt test`
  Expected: all paint goldens PASS on the test GPU; invalid references report `:scene-path` and never use a flat-fill fallback.

### Task 5: Composite masks and coverage-correct blending

**Files:**
- Create: `native/compositor.cpp`
- Create: `native/compositor.hpp`
- Modify: `native/reactive_renderer.cpp`
- Modify: `src/slipstream/glimmer_renderer.clj`
- Test: `test/slipstream/glimmer_compositor_test.clj`

**Interfaces:**
- Produces offscreen group commands `{:begin-group mask?} ... {:end-group blend-mode}` and ping-pong destination management inside the native renderer.
- Supports procedural masks, SDF-tile masks, inversion, nested composites, every Slughorn `DrawMode`/`BlendMode`, and per-layer opacity.

- [ ] **Step 1: Add compositor golden and state-isolation tests**
  Cover each procedural mask, SDF-tile mask, inversion, nested groups, all Porter-Duff and Photoshop modes, partial-coverage edges, empty groups, framebuffer resize, and FBO restoration between two contexts.

- [ ] **Step 2: Implement offscreen composite rendering**
  Render composite children into context-owned targets, evaluate the whole-group mask, then composite into the parent destination. Resize targets without rebuilding atlases and restore the prior FBO/viewport on every exit path.

- [ ] **Step 3: Implement shader-side blend/composite passes**
  Use ping-pong destinations to apply coverage-correct Porter-Duff and Photoshop formulas. Do not depend on `GL_KHR_blend_equation_advanced` or fixed-function blending for the main path.

- [ ] **Step 4: Run compositor goldens**
  Run: `jolt test`
  Expected: all blend/mask goldens PASS, including antialiased edges and nested-group isolation.

### Task 6: SDF/MSDF, custom effects, and complete reactive verification

**Files:**
- Modify: `native/advanced_shaders.cpp`
- Modify: `native/reactive_renderer.cpp`
- Create: `src/slipstream/glimmer_effects.clj`
- Create: `example/slipstream/example/reactive.clj`
- Create: `test/slipstream/glimmer_effects_test.clj`

**Interfaces:**
- Produces effect data `{:id uint :param float :vertex-hook string? :fragment-hook string? :uniforms map?}`.
- Produces `(effect-program! context effect)` keyed by full source/interface per context; missing SDF data and invalid hook interfaces throw `ex-info` with `:scene-path`.

- [ ] **Step 1: Add SDF/effect golden and failure tests**
  Cover SDF and MSDF sampling, inside/outside effects, effect ID/parameter updates without atlas rebuilds, typed uniforms, vertex/fragment hooks, shader compile/link errors, missing SDF data, and per-context program ownership.

- [ ] **Step 2: Implement SDF/MSDF and custom shader hooks**
  Adapt the recorded osgSlug shader conventions. Compile a complete program interface for each custom effect and cache it per context. Reject malformed hooks rather than substituting standard fill.

- [ ] **Step 3: Build the live reactive example**
  The example changes text, position, color, path geometry, gradient stops, masks, blend mode, and effect parameters from ratoms and a queued REPL-style update while retaining one window. Include an `:auto-quit-ms` smoke mode and capture checkpoints.

- [ ] **Step 4: Run complete core and reactive verification**
  Run: `jolt native && SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test && SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt parity`
  Expected: wrapper tests, incremental/cache tests, all available golden frames, reference data parity, and example parity PASS. Any unavailable display/GPU feature is explicitly reported unverified, never silently skipped.

- [ ] **Step 5: Review final source and artifact hygiene**
  Confirm no Glimmer/osgSlug/Slughorn source tree changed, no generated libraries/build trees/captures are tracked, and the documented reactive API matches actual supported behavior.
