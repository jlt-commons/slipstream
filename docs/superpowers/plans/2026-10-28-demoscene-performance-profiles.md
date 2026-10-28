# Demoscene Performance Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the demoscene with reproducible stress profiles, live performance/resource metrics, nREPL workload controls, and clean EDN summaries while preserving the existing showcase.

**Architecture:** A bounded Clojure metrics collector samples UI-thread frame/resource state after warm-up and updates the HUD at low cadence. Named scene profiles scale geometry, text, compositor, and shader workloads by positive intensity. Profile changes are reactive and preserve the native context.

**Tech Stack:** Jolt/Clojure, Glimmer ratoms/reconciler, native OpenGL/GLFW instrumentation, nREPL, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-10-28-demoscene-performance-profiles-design.md`

## Global Constraints

- Default `:showcase` behavior remains visually unchanged and unlimited; other profiles are opt-in.
- Positive bounded intensity only; reject mesh overflow/unbounded allocation rather than wrapping or hanging.
- Warm-up/initialization excluded from steady-state percentiles; no universal FPS threshold.
- Metrics/HUD updates must not create per-frame glyph or atlas churn.
- nREPL profile changes use normal scheduling and preserve one native context.
- Fix discovered correctness bugs with regressions; no speculative renderer optimization.
- Commit on main; do not commit summaries, captures, `.nrepl-port`, or caches.

## Review Focus

- Zero/negative/unknown profile/intensity and oversized geometry: expect actionable errors, not mesh overflow; test in Task 2.
- Cold shader/atlas initialization: expect separate timing, never mixed into measured samples; test in Task 1.
- Metrics text and profile changes: expect low-cadence updates and cache reuse, not per-frame churn; test in Tasks 1–2.
- Close/reset/double failure: expect one EDN summary, correct reset behavior, and primary error preservation; test in Task 3.
- Counter interpretation: expect cumulative counters separate from per-sample deltas and live draw/resource counts; test in Task 1.

---

### Task 1: Metrics collection and general frame-state API

**Files:**
- Create: `example/slipstream/example/demoscene_metrics.clj`
- Modify: `src/slipstream/glimmer_renderer.clj`
- Modify: `src/slipstream/ffi.clj`
- Modify: `native/reactive_renderer.cpp`
- Modify: `native/slipstream_shim.h`
- Create: `test/slipstream/demoscene_metrics_test.clj`
- Modify: `test/slipstream/test_runner.clj`

**Interfaces:**
- Produces `(frame-state context) -> {:viewport [...] :framebuffer-size [...] :window-size [...] :projection [...] :shader-compilations n :resource-uploads n :paint-texture-uploads n :live-resources n :live-effects n}`.
- Produces `(new-collector options)`, `(sample-frame! collector frame-state now)`, `(metrics-snapshot collector)`, `(reset-metrics! collector)`, `(hud-status collector)`.
- Warm-up defaults to 2000 ms; samples are bounded; HUD updates at most 4 Hz.

- [ ] **Step 1: Write failing metrics tests**
  Test percentile calculations, warm-up exclusion, bounded samples, cumulative-versus-delta counters, live resource/effect counts, low-cadence HUD, and reset behavior.

- [ ] **Step 2: Run tests and verify missing metrics failure**
  Expected: FAIL on unresolved collector/frame-state functions.

- [ ] **Step 3: Implement general frame state and collector**
  Extend existing frame-state layout with live counts if needed, preserving ABI/FFI docs. Collect UI-thread samples and plain EDN data without native callbacks.

- [ ] **Step 4: Run focused and full tests**
  Expected: metrics and existing tests pass.

- [ ] **Step 5: Commit**
  Commit with `feat: add demoscene performance metrics`.

### Task 2: Stress profiles and nREPL workload controls

**Files:**
- Create: `example/slipstream/example/demoscene_profiles.clj`
- Modify: `example/slipstream/example/demoscene_shader.clj`
- Modify: `example/slipstream/example/demoscene.clj`
- Create: `test/slipstream/demoscene_profiles_test.clj`
- Modify: `test/slipstream/test_runner.clj`

**Interfaces:**
- Produces profiles `:showcase`, `:geometry`, `:text`, `:compositor`, `:shader`, and `:all`, each with positive bounded `:intensity` and optional `:sdf`.
- Produces `(load-profile! profile & [{:keys [intensity sdf]}])`, `(profile-scene state)`, and CLI `--profile`, `--intensity`, `--sdf`, `--warmup-ms`, `--metrics`, `--summary`.
- Profile changes preserve one native context and do not reset nREPL or native caches unnecessarily.

- [ ] **Step 1: Write failing profile validation/content tests**
  Test every profile, bounded intensity, mesh-limit rejection, unique/shared geometry, long/repeated Unicode text, nested masks/blends, fullscreen shader work, combined `:all`, and unchanged showcase defaults.

- [ ] **Step 2: Run tests and verify profile absence fails**
  Expected: FAIL on missing profile functions/options.

- [ ] **Step 3: Implement profile scene generation and controls**
  Add bounded workloads, profile-aware HUD, CLI options, and `load-profile!` through ratoms. Keep metrics text updates low cadence and geometry updates targeted.

- [ ] **Step 4: Add short native smoke per profile**
  Verify actual rendering, counters, no stale handles/overflow, and cache reuse after profile changes. Record performance data without FPS assertions.

- [ ] **Step 5: Run focused and full tests**
  Expected: all profile and existing tests pass.

- [ ] **Step 6: Commit**
  Commit with `feat: add demoscene stress profiles`.

### Task 3: EDN summaries, live experiments, and documentation

**Files:**
- Modify: `example/slipstream/example/demoscene.clj`
- Modify: `docs/demoscene.md`
- Modify: `docs/README.md`
- Modify: `docs/rendering.md`
- Modify: `docs/ffi.md`
- Modify: `README.md`
- Modify: `test/slipstream/demoscene_lifecycle_test.clj`

**Interfaces:**
- Produces one EDN summary on clean exit with profile/intensity/options, initialization/warm-up durations, frame percentiles/sample count, live counts, and cumulative counters.
- Produces nREPL examples for workload switching, snapshots, and metric reset.

- [ ] **Step 1: Add failing summary/lifecycle tests**
  Verify EDN shape, clean-close summary once, reset behavior, and primary-error preservation when summary generation/output fails. Add an nREPL profile-change test proving one context and rendered workload change.

- [ ] **Step 2: Implement summary and fix lifecycle bugs**
  Integrate summary with existing cleanup without masking primary errors or breaking idempotent `stop!`.

- [ ] **Step 3: Write performance guide and API docs**
  Document workloads, metrics, warm-up, CLI/nREPL examples, and hardware-specific interpretation.

- [ ] **Step 4: Run complete verification**
  Run native build, full tests, parity, timed showcase, and short native smoke for every profile. Inspect EDN summaries and representative captures.

- [ ] **Step 5: Commit and inspect final hygiene**
  Commit with `feat: document and verify demoscene performance profiles`. Confirm no generated artifacts are tracked.
