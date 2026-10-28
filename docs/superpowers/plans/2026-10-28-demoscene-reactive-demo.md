# Slipstream Demoscene Reactive Demo Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a hybrid procedural/vector demoscene with mouse interaction and a rich in-process nREPL workflow that runs until native close and supports live redefinition without reload.

**Architecture:** One custom full-screen shader produces animated waves/Voronoi while independent Glimmer vector nodes orbit, pulse, and change geometry in stages. Native GLFW input snapshots drive ratoms on the UI thread. The demo starts rich nREPL before `ui/run`, with idempotent lifecycle and close support.

**Tech Stack:** Jolt/Clojure, Glimmer ratoms/reconciler, `jolt-lang/nrepl` v0.4.0, `jolt.nrepl`, C++20, GLFW, OpenGL custom effects, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-28-demoscene-reactive-demo-design.md`

## Global Constraints

- Default behavior runs until the user closes the window; timed/capture options are test-only.
- No window reload/remount for normal nREPL development; redefinitions use var derefs and persistent state.
- Native calls run on the UI thread; nREPL worker updates use Glimmer scheduling and terminal teardown.
- Do not rebuild all geometry per frame; staged path edits affect only intended nodes.
- Use rich middleware `nrepl.middleware/default-middleware` and `cider.nrepl/cider-middleware` from `jolt-lang/nrepl` v0.4.0 (`16558b920864e5302a5c1433a78f977bbb64a9d1`).
- A TTF/OTF path is required for the HUD; no bundled font or platform guessing.
- Fix discovered library bugs with focused regressions; preserve all existing parity/goldens.
- Commit directly on main as authorized; do not commit generated captures, `.nrepl-port`, or caches.

## Review Focus

- Repeated wheel snapshots: expect delta consumed exactly once, never repeated; test in Task 1.
- High-DPI cursor coordinates and Y-up bounds: expect correct scene mapping, not framebuffer/window confusion; test in Task 1.
- nREPL close during eval/scheduled work: expect prompt unmount and idempotent stop with no late native call; test in Tasks 2 and 4.
- Shader/mouse animation frames: expect visual/uniform changes without atlas churn; test in Task 3.
- Rich editor operations and redefinition: expect sessions, completion, and new definitions in the same window; test in Task 4.
- Startup failure and repeated `stop!`: expect original error and no partial server/window leaks; test in Task 2.

---

### Task 1: Native input snapshots, coordinate mapping, and close

**Files:**
- Modify: `native/slipstream_shim.h`
- Modify: `native/reactive_renderer.cpp`
- Modify: `native/reactive_renderer.hpp`
- Modify: `src/slipstream/ffi.clj`
- Modify: `src/slipstream/glimmer_renderer.clj`
- Create: `test/slipstream/glimmer_input_test.clj`
- Modify: `test/slipstream/test_runner.clj`

**Interfaces:**
- Produces C layout `SSGLInput {double x,y,scroll_x,scroll_y; uint8_t left,right,middle,space,r;}`.
- Produces C: `ss_gl_input_snapshot(void*, SSGLInput*) -> int`, `ss_gl_request_close(void*) -> int`; test-only `ss_gl_test_set_input(void*, const SSGLInput*) -> int`.
- Produces Clojure: `(input-snapshot context) -> {:x n :y n :scroll-x n :scroll-y n :left bool :right bool :middle bool :space bool :r bool}`, `(request-close! context) -> nil`, `(cursor->bounds context bounds) -> [x y]`.

- [ ] **Step 1: Write failing native input/close tests**
  Cover cursor/button states, scroll consumed once, injected key state, request-close loop exit, repeated close safety, and high-DPI/Y-up mapping for default and explicit bounds.

- [ ] **Step 2: Run tests and verify missing binding failure**
  Run: `SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt test`
  Expected: FAIL on unresolved input functions.

- [ ] **Step 3: Implement native snapshots and close**
  Store input in the native context through GLFW callbacks, consume accumulated scroll per snapshot, and set close with `glfwSetWindowShouldClose`. No callbacks into Jolt. Add matching layout/FFI and bounds conversion.

- [ ] **Step 4: Run input tests and full regression suite**
  Expected: all input and existing tests pass.

- [ ] **Step 5: Commit**
  Commit with `feat: add reactive input and close controls`.

### Task 2: Rich nREPL dependency and demo lifecycle

**Files:**
- Modify: `deps.edn`
- Create: `example/slipstream/example/demoscene.clj`
- Create: `test/slipstream/demoscene_lifecycle_test.clj`
- Modify: `test/slipstream/test_runner.clj`
- Modify: `.gitignore`

**Interfaces:**
- Produces `(start! & [{:keys [font-path port width height bounds capture-dir max-frames auto-quit-ms]}])`, `(stop!)`, `state`, `app`, and `-main`.
- `start!` starts rich nREPL before UI creation, stores the stop fn, and calls `ui/run` on the caller thread. Default port is 7888; `0` uses OS assignment.
- `stop!` requests close and stops nREPL exactly once, safe before start and on repeat.

- [ ] **Step 1: Add rich dependency and failing lifecycle tests**
  Use `jolt-lang/nrepl` v0.4.0 at the pinned SHA and both middleware entries. Test idempotent start/stop, port metadata, startup failure cleanup, close during scheduled work, and production default having no frame/time limit.

- [ ] **Step 2: Run lifecycle tests and verify absence fails**
  Expected: FAIL on missing demo namespace/lifecycle.

- [ ] **Step 3: Implement lifecycle and CLI**
  Add persistent state, idempotent start/stop, rich server startup, native close handling, and required-font CLI with optional port/capture/test options. Start the server before UI creation and stop it in final teardown.

- [ ] **Step 4: Run lifecycle and rich nREPL integration tests**
  Expected: rich server starts, session eval works, and lifecycle tests pass.

- [ ] **Step 5: Commit**
  Commit with `feat: add nREPL-friendly demoscene lifecycle`.

### Task 3: Hybrid wave/Voronoi shader and vector constellation

**Files:**
- Create: `example/slipstream/example/demoscene_shader.clj`
- Modify: `example/slipstream/example/demoscene.clj`
- Create: `test/slipstream/demoscene_visual_test.clj`
- Modify: `test/slipstream/test_runner.clj`

**Interfaces:**
- Produces redefinable shader source/palette/update vars and `(scene-for-state state) -> Glimmer hiccup`.
- Uniforms include time, transition, mouse, density, speed, aspect, and palette controls.
- Deterministic clock/transition injection supports golden captures without changing production defaults.

- [ ] **Step 1: Write failing deterministic visual/resource tests**
  Require distinct golden frames for waves, Voronoi, midpoint, mouse warp, and vector stage changes. Assert orbit/color/uniform updates leave atlas/upload counters stable and staged path edits touch only intended nodes.

- [ ] **Step 2: Run tests and verify visuals are absent**
  Expected: FAIL on missing scene/shader functions.

- [ ] **Step 3: Implement the procedural shader**
  Add layered sine/interference fields, deterministic hash Voronoi, smooth blending, mouse warping, palette movement, and a dark/luminous full-screen effect.

- [ ] **Step 4: Add the vector constellation and controls**
  Add roughly twelve independent shapes with gradient/pattern/SDF effects, orbital/pulsing behavior, cursor attraction, button burst, transition toggle, wheel density, and reset. Include a small HUD using the required font.

- [ ] **Step 5: Run visual tests and capture representative frames**
  Expected: all exact goldens pass; interactive frame inspection confirms legibility and no per-frame atlas churn.

- [ ] **Step 6: Commit**
  Commit with `feat: add hybrid demoscene visuals`.

### Task 4: Live nREPL verification and documentation

**Files:**
- Create: `docs/demoscene.md`
- Modify: `docs/README.md`
- Modify: `docs/rendering.md`
- Modify: `docs/ffi.md`
- Modify: `README.md`
- Modify: `test/slipstream/demoscene_lifecycle_test.clj`

**Interfaces:**
- Documents live connection, controls, lifecycle, useful evaluations, and capture options.
- Produces an automated live-redefinition check using the rich nREPL client and one running window.

- [ ] **Step 1: Add the live workflow test**
  Start the demo with rich nREPL, evaluate a state change and a palette/update-function redefinition through `nrepl.core`, and verify the same context/window renders the result without reload. Check describe/completion availability and clean close from nREPL.

- [ ] **Step 2: Fix discovered nREPL or lifecycle bugs**
  Add focused regressions and fix the library, not only the demo. Preserve worker/UI-thread separation and terminal scheduling.

- [ ] **Step 3: Write the complete demo guide and API additions**
  Include controls, architecture, `start!`/`stop!`, rich middleware setup, useful evaluations, test/capture options, and the distinction between redefine, reload, and restart.

- [ ] **Step 4: Run complete verification**
  Run: `jolt native && SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt test && SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt parity && jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf --auto-quit-ms 10000 --capture-dir native/build/demoscene-captures`
  Expected: all tests/parity/goldens and live nREPL workflow pass; representative frames exist.

- [ ] **Step 5: Commit and inspect final hygiene**
  Commit with `feat: verify and document live demoscene development`. Confirm no `.nrepl-port`, captures, caches, or generated native files are tracked.
