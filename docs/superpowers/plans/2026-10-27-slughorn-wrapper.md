# Slipstream Slughorn Wrapper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a broad, data-oriented Jolt wrapper for Slughorn and a Jolt GLFW example whose native data and rendered output match the upstream C++ example.

**Architecture:** A C++20 shared library exposes opaque handles and value-record C ABIs over Slughorn. `slipstream.ffi` binds it, `slipstream.core` provides safe fine-grained operations, and `slipstream.scene` compiles Hiccup-like data. A separate native GLFW backend consumes the same atlas/layer model and is driven by a Jolt example entry point.

**Tech Stack:** Jolt/Clojure, `jolt.ffi`, C++20, Slughorn, FreeType, OpenGL 3.3, GLFW 3.4, GLAD 0.1.36, CMake, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-27-slughorn-wrapper-design.md`

**Scope:** Core wrapper and reference-example requirements. The Glimmer requirements in the same design are implemented by `docs/superpowers/plans/2026-10-27-glimmer-backend.md` after this plan's Task 6 establishes the native GLFW layer.

## Global Constraints

- Keep upstream `/Users/yogthos/src/slughorn` unchanged; default `SLUGHORN_HOME` to `~/src/slughorn`.
- No JVM or Java interop assumptions; all native calls use `jolt.ffi`.
- No C++ exception may cross the C ABI. All borrowed pointers have documented owner lifetimes.
- Ordinary text decodes Unicode; only the compatibility example uses upstream byte-wise text behavior.
- The minimal renderer supports standard fills only, exactly like the upstream example.
- Do not commit generated libraries, build trees, captured frames, fonts, or dependency sources.
- Do not run git commits or other git mutations unless the user explicitly requests them.

## Review Focus

- Destroyed atlases/paths/canvases and canvases whose owner was destroyed: expect Clojure exceptions, never stale dereferences; test in Task 1 and Task 3.
- Exceptions from invalid paths, fonts, built-atlas mutation, and serialization: expect operation-rich `ex-info`; test in Tasks 2, 4, and 5.
- Composite cycles and misplaced root layers: expect normalization errors or explicit-root behavior, never recursion overflow; test in Task 5.
- Non-ASCII input: ordinary scene text uses codepoints while example parity preserves bytes; test both paths in Tasks 4 and 7.
- Borrowed texture/mesh/native renderer pointers: expect atlas lifetime to cover use and copied Clojure data to remain valid afterward; test in Tasks 2 and 6.
- Missing native dependencies and headless GUI runs: expect actionable build errors and explicit unverified visual status, never a silent skipped example; test in Tasks 6 and 7.

---

### Task 1: Native build, error boundary, and atlas lifecycle

**Files:**
- Create: `deps.edn`
- Create: `native/CMakeLists.txt`
- Create: `native/slipstream_shim.h`
- Create: `native/slipstream_internal.hpp`
- Create: `native/atlas.cpp`
- Create: `src/slipstream/ffi.clj`
- Create: `src/slipstream/core.clj`
- Create: `test/slipstream/core_test.clj`
- Create: `test/slipstream/test_runner.clj`
- Create: `.gitignore`

**Interfaces:**
- Produces C: `ss_last_error() -> const char*`, `ss_atlas_new(uint32_t width) -> void*`, `ss_atlas_destroy(void*) -> int`, `ss_atlas_build(void*) -> int`, `ss_atlas_is_built(void*) -> bool`.
- Produces Clojure: `(make-atlas & [{:keys [texture-width]}])`, `(destroy! atlas)`, `(build! atlas)`, `(built? atlas)`, `(with-atlas [a opts] & body)`.
- Atlas wrapper record: `Atlas [handle destroyed?]`, where `destroyed?` is an atom.

- [ ] **Step 1: Write failing lifecycle tests**
  Test creation/build, idempotent Clojure `destroy!`, post-destroy `build!` throwing `ex-info`, zero texture width surfacing a native error, and scoped cleanup on an exception.

- [ ] **Step 2: Run the tests and verify the expected failure**
  Run: `jolt test`
  Expected: FAIL because `native/libslipstream.dylib` and the bindings do not exist.

- [ ] **Step 3: Add the build and minimal native/Clojure implementation**
  Configure C++20 and FreeType through CMake; output the core library as `native/libslipstream.{dylib,so}`. Declare it in `:jolt/native`, make `jolt native` configure/build it, and make `jolt test` depend on `native`. Implement a thread-local error string and `ss_try` exception boundary. Safe Clojure calls check handles and sentinels before throwing `(ex-info ... {:op ... :native-error ...})`.

- [ ] **Step 4: Run lifecycle tests**
  Run: `jolt test`
  Expected: PASS; `jolt native` is incremental on a second invocation.

### Task 2: Shapes, layers, textures, and reference mesh data

**Files:**
- Modify: `native/slipstream_shim.h`
- Create: `native/shapes.cpp`
- Create: `native/mesh.cpp`
- Modify: `src/slipstream/ffi.clj`
- Modify: `src/slipstream/core.clj`
- Test: `test/slipstream/core_test.clj`

**Interfaces:**
- Produces C value layouts: `SSKey`, `SSColor`, `SSTransform`, `SSLayer`, `SSShape`, `SSQuad`, `SSTextureDescriptor`, `SSMeshDescriptor`.
- Produces Clojure: `(add-shape! atlas key curves opts)`, `(shape atlas key)`, `(add-composite! atlas key layers opts)`, `(composite atlas key)`, `(layers atlas root)`, `(texture atlas kind & [{:keys [copy?] :or {copy? true}}])`, `(mesh atlas root)`, `(write! atlas path)`, `(read-atlas path)`.
- Key representation: keyword/string names or `[:codepoint cp mask?]`; curves are six-float vectors `[x1 y1 x2 y2 x3 y3]`.
- Mesh result: `{:vertices float-array :indices short-array :stride 80 :attributes {...}}`.

- [ ] **Step 1: Write failing shape/texture/mesh tests**
  Add a rectangle with exact quadratic curves, build it, assert shape metrics/bands, add two layers, assert six indices and 24 vertices per layer, check the `0.01` expansion and 20-float stride, copy texture bytes before and after atlas destruction, and verify a missing shape returns `nil`.

- [ ] **Step 2: Run the new tests and verify missing-symbol failure**
  Run: `jolt test`
  Expected: FAIL on unbound shape/mesh functions.

- [ ] **Step 3: Implement value layouts and native operations**
  Mirror C records with `jolt.ffi/layout`. Collection returns use count/capacity buffers or owned iterators. Mesh generation ports the reference `buildMesh`, recursively flattens explicit composite roots in Clojure before calling the native shape-quad helper, rejects cycles, and preserves layer order and vertex layout.

- [ ] **Step 4: Implement upstream serialization entry points**
  Compile `serial.hpp` with its implementation macro and Slughorn's bundled JSON include path. `write!` and `read-atlas` expose upstream fidelity only; a read atlas has no implicit root.

- [ ] **Step 5: Run shape, mesh, and serialization tests**
  Run: `jolt test`
  Expected: PASS, including a `.slug` and `.slugb` round trip and native errors for writes before build.

### Task 3: Paths, Canvas paints, masks, and composites

**Files:**
- Modify: `native/slipstream_shim.h`
- Create: `native/path.cpp`
- Create: `native/canvas.cpp`
- Modify: `src/slipstream/ffi.clj`
- Modify: `src/slipstream/core.clj`
- Test: `test/slipstream/canvas_test.clj`

**Interfaces:**
- Produces: `(make-path)`, `(destroy! path)`, `(with-path [p] & body)`, `(path-command! p command)`, `(sample path t)`, `(arc-length path)`, `(stroke-path! path width opts)`.
- Produces: `(make-canvas atlas opts)`, `(with-canvas [c atlas opts] & body)`, `(canvas-command! c command)`, `(fill! c path-or-nil paint opts)`, `(stroke! c path-or-nil width paint opts)`, `(define-shape! c path-or-nil key opts)`, `(text! c text opts)`, `(finalize! c key)`, `(set-mask! c mask)`.
- Path command vectors use the DSL tags listed in the spec. Paint maps have `{:color [r g b a]}` or exactly one of `:gradient-id`/`:pattern-id`.

- [ ] **Step 1: Write failing Canvas coverage tests**
  Test every path verb, reusable explicit paths, transform save/restore, stroke join/cap options, fill/stroke/gradient/pattern commits, mask metadata, composite layer ordering, and path sampling. Verify destroying an atlas invalidates its canvas wrapper.

- [ ] **Step 2: Run tests and verify missing Canvas bindings fail**
  Run: `jolt test`
  Expected: FAIL on unbound path/canvas functions.

- [ ] **Step 3: Implement path and Canvas ABI/safe wrappers**
  Add opaque path/canvas handles, explicit-path and implicit-path commits, all command forwarding, gradient/pattern registration, masks, text, and finalization. Canvases are owned wrappers linked to their atlas; destruction is idempotent at the wrapper level and native calls validate null handles.

- [ ] **Step 4: Run Canvas and regression tests**
  Run: `jolt test`
  Expected: PASS, including native exception conversion for an empty fill and invalid paint options.

### Task 4: FreeType loading and text layout

**Files:**
- Modify: `native/slipstream_shim.h`
- Create: `native/freetype.cpp`
- Modify: `native/CMakeLists.txt`
- Modify: `src/slipstream/ffi.clj`
- Modify: `src/slipstream/core.clj`
- Test: `test/slipstream/text_test.clj`

**Interfaces:**
- Produces: `(load-font! atlas path codepoints opts) -> {:loaded n :metrics {...} :family ... :style ...}`, `(load-ascii-font! atlas path opts)`, `(load-all-font! atlas path opts)`.
- Produces: `(text-layers atlas text opts) -> vector<SSLayer-shaped maps>` and `(layout-text! canvas atlas text opts)`.
- Options include `:font-size`, `:position`, `:color`, `:mask`, `:uniform`, `:align`, `:anchor`, and byte-wise compatibility mode.

- [ ] **Step 1: Write failing font/text tests**
  Require a font from `SLIPSTREAM_TEST_FONT`; fail with an actionable message if absent. Assert glyph loading, nonempty advance/metrics, whitespace without geometry, Unicode codepoint layout, and the deliberate difference between Unicode and byte-wise compatibility layout for a non-ASCII string.

- [ ] **Step 2: Run tests and verify missing FreeType wrapper failure**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: FAIL on unbound font functions.

- [ ] **Step 3: Implement FreeType and layout wrappers**
  Compile the FreeType implementation macro and link FreeType. Use Slughorn's load functions and Canvas text APIs. The compatibility function converts each input byte to a key exactly like the C++ example; ordinary text uses Clojure codepoints.

- [ ] **Step 4: Run all tests with the test font**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: PASS.

### Task 5: Declarative scene normalizer and compiler

**Files:**
- Create: `src/slipstream/scene.clj`
- Modify: `src/slipstream/core.clj`
- Test: `test/slipstream/scene_test.clj`

**Interfaces:**
- Produces: `(normalize scene-data) -> normalized-data`, `(compile scene-data) -> Scene`, `(with-scene [s data] & body)`.
- `Scene [atlas data layers destroyed?]` delegates lifecycle/inspection operations to its atlas wrapper.
- Produces `(mesh scene-or-atlas root?)`, using the scene's root layers by default and requiring an explicit root after deserialization.

- [ ] **Step 1: Write failing normalization tests**
  Cover options/defaults, inferred font codepoints, forward references, every node tag, invalid arities/vector lengths/enums/paint combinations, duplicate IDs, missing references, and composite cycles. Assert errors contain `:scene-path` and `:node`.

- [ ] **Step 2: Write failing pure compilation tests**
  Compile a scene containing a font, reusable path, flat and gradient shapes, pattern, mask, group, and Unicode text. Assert definition order is invisible to callers, root layer order is stable, meshes flatten composites, and scene cleanup releases native resources.

- [ ] **Step 3: Run tests and verify compiler absence fails**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: FAIL on unresolved `normalize`/`compile`.

- [ ] **Step 4: Implement normalization and compilation**
  Normalize and validate the whole tree before native mutation. Realize definitions in dependency order through Tasks 2–4, then build once and retain top-level layers. Do not add a second scene serializer or a native batch scene interpreter.

- [ ] **Step 5: Run all wrapper tests**
  Run: `SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test`
  Expected: PASS.

### Task 6: Native GLFW backend and Jolt example

**Files:**
- Create: `native/glfw_renderer.cpp`
- Create: `src/slipstream/glfw.clj`
- Create: `example/slipstream/example/glfw.clj`
- Modify: `native/CMakeLists.txt`
- Modify: `deps.edn`

**Interfaces:**
- Produces C: `ss_glfw_run(void* atlas, const SSLayersView layers, const char* title, int curve_precision, const char* capture_path, int max_frames) -> int` and `ss_read_stdin_text() -> char*` with an explicit free function.
- Produces Clojure: `(run! scene {:title "slughorn" :capture-path nil :max-frames nil})`.
- Example command: `jolt run -m slipstream.example.glfw <font.ttf> [--curve-precision 16|32]`.

- [ ] **Step 1: Add failing renderer ABI/example smoke checks**
  Check argument validation, default and piped text selection, precision validation, and native symbol resolution. Expected failures must precede renderer implementation.

- [ ] **Step 2: Add reproducible GLFW/GLAD dependencies**
  Use the same GLFW 3.4 and GLAD 0.1.36 CMake `FetchContent` versions as upstream, or an explicitly configured local installation. Fail configuration with exact setup guidance if dependencies cannot be obtained; do not skip the example.

- [ ] **Step 3: Port the reference renderer**
  Copy the upstream shader source and behavior into `glfw_renderer.cpp`: texture formats, vertex attributes, 80-byte stride, matrix math, initial camera, controls, framebuffer resize, blending, cleanup, controls output, and OpenGL version output. The Jolt example owns font/text scene data and invokes the renderer with `:blocking`.

- [ ] **Step 4: Compile and run noninteractive example checks**
  Run: `jolt native && jolt run -m slipstream.example.glfw`
  Expected: exit 1 with upstream usage text.
  Run: `jolt run -m slipstream.example.glfw /path/to/font.ttf --curve-precision 8`
  Expected: exit 1 with the same invalid-precision text as upstream.

- [ ] **Step 5: Run a one-frame GUI smoke test**
  Run with the known font and test-only `--frames 1 --capture /tmp/slipstream-frame.ppm` options.
  Expected: clean exit, same controls/OpenGL output shape, nonempty 800x600 capture. If no display is available, record this as unverified rather than passing.

### Task 7: C++ parity oracle and final verification

**Files:**
- Modify: `native/CMakeLists.txt`
- Create: `native/parity_oracle.cpp`
- Create: `test/slipstream/parity_test.clj`

**Interfaces:**
- Produces native target `slipstream-parity-oracle`, which writes atlas descriptors/bytes and vertex/index bytes for the same font/text/precision as the Jolt path.
- Produces `jolt parity`, running all data and available visual comparisons.

- [ ] **Step 1: Implement the C++ data oracle**
  Reuse the upstream example's loading, direct byte layout, and `buildMesh` logic to dump deterministic binary outputs without changing upstream Slughorn.

- [ ] **Step 2: Compare native data byte-for-byte**
  Run both precisions and default/piped ASCII text. Expected: identical texture dimensions/formats/bytes, float vertex bytes, and indices. Any difference is a failing parity test, not a tolerance case.

- [ ] **Step 3: Compare rendered output and behavior**
  Build the upstream executable with identical dependencies. Capture its initial 800x600 frame and Slipstream's; expect byte-identical pixels on the same GPU/driver. Compare usage, invalid argument, controls output, piped text, projection reset/toggle, orbit, pan, zoom, and clean exit where GUI automation is available.

- [ ] **Step 4: Run the complete verification suite**
  Run: `jolt native && SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt test && SLIPSTREAM_TEST_FONT=/path/to/font.ttf jolt parity`
  Expected: all tests and data parity PASS; visual checks PASS or are explicitly reported unverified with the environmental reason.

- [ ] **Step 5: Inspect the final diff and generated-file hygiene**
  Confirm only source, tests, design/plan docs, and build configuration are tracked. Confirm no upstream files changed and no generated native/build/capture artifacts are included.
