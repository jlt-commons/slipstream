# Demoscene Performance Profiles Design

## Intent

Make the demoscene useful both as a feature showcase and as a practical way to expose Slipstream performance problems. Users should be able to reproduce geometry-, text-, paint-, compositor-, and shader-heavy workloads, watch the cost live, change intensity through nREPL, and compare results without conflating startup/warm-up with steady-state behavior.

## Goals

- Preserve the existing unlimited interactive showcase as the default `:showcase` profile.
- Add reproducible `:geometry`, `:text`, `:compositor`, `:shader`, and `:all` stress profiles with a positive integer `:intensity` multiplier.
- Display useful live frame and resource metrics without rebuilding text atlases every frame.
- Allow profile/intensity changes through CLI and nREPL in the same native window.
- Separate initialization/warm-up costs from measured steady-state samples and print an EDN summary on clean exit.
- Add regression tests for profiles, metrics, lifecycle, and docs; fix performance-related correctness bugs discovered during stress runs.
- Preserve existing exact parity, visual goldens, and normal showcase behavior.

## Non-goals and anti-goals

- Do not optimize the renderer speculatively in this task. First make costs visible and reproducible; proven correctness bugs are still fixed.
- Do not report a universal FPS target as a pass/fail result. Performance is hardware/driver/workload dependent.
- Do not change production defaults into a benchmark or continuously rebuild native resources to simulate load.
- Do not include startup shader/atlas compilation in steady-state frame percentiles.
- Do not turn frame-time metrics into per-frame glyph/native churn.
- Do not commit generated benchmark summaries, captures, or cache directories.

## Constraints

- Existing frame-state counters already expose cumulative shader compilations, resource uploads, and paint-texture uploads. Frame timing and live command/resource counts need Clojure-side collection and, where native counters are insufficient, small context read APIs.
- Metrics collection runs on the UI thread after rendering. EDN summary output happens after UI teardown and must not mask primary errors.
- Stress profiles can be expensive by design. They must remain bounded by explicit positive intensity and must not allocate unbounded memory or bypass the existing 65,536-vertex mesh limit.
- nREPL profile changes execute through normal reactive scheduling and preserve the same native context. Current content caches remain valid.
- Exact visual goldens remain deterministic; performance metrics are asserted structurally, not by timing values.

## Approaches considered

### Single combined stress scene

One heavy scene would be easy to implement but would not isolate which subsystem is slow. Rejected because the goal includes diagnosing performance issues.

### Continuous rotating benchmark

Automatic workload rotation is useful for soak testing but changes the normal demo and makes interactive comparisons harder. Rejected as the primary model; users can approximate it later through nREPL if desired.

### Profiles with adjustable intensity — selected

Named workloads isolate costs while `:all` combines them. A bounded intensity multiplier scales node/text/effect counts without changing profile identity. This provides reproducible experiments and leaves the current showcase intact.

## Workloads

### `:showcase`

Current wave/Voronoi shader, twelve vector nodes, automatic geometry stages, and HUD. Default behavior is unchanged except for a lightweight optional metrics line.

### `:geometry`

Scales independently keyed vector nodes with unique and shared geometry:

- A base set of unique paths/fills to create per-node atlas/texture pressure.
- Shared copies to demonstrate content-cache behavior.
- Stage-driven path/color/orbit changes to compare rebuild versus draw-only costs.
- A `:sdf` toggle for SDF/MSDF baking pressure where supported.

Intensity scales unique node count in bounded increments. The profile validates against mesh vertex limits and reports when a configuration cannot run rather than wrapping indices or allocating indefinitely.

### `:text`

Exercises glyph acquisition, shared glyph reuse, layout, and text updates:

- A long alphabet/punctuation/non-ASCII corpus with many distinct glyphs.
- Repeated text to demonstrate shared glyph resources.
- Periodic content/color/placement updates to compare glyph churn against draw-only changes.

Intensity scales corpus lines/length within bounded memory and glyph counts.

### `:compositor`

Exercises gradients, patterns, nested masks, blend modes, and fullscreen ping-pong cost:

- Multiple groups with procedural/SDF masks and nested composition.
- Porter-Duff and Photoshop blend variants.
- Resizable/high-fill-rate workloads and an optional SDF-mask toggle.

Intensity scales offscreen groups/draw nodes while keeping nesting depth and target count bounded.

### `:shader`

Keeps geometry small and raises procedural fragment cost:

- Fullscreen wave/Voronoi shader with maximum density and mouse warp.
- Configurable framebuffer size and an optional high-cost Voronoi neighborhood variant if needed to expose ALU limits.
- Animated uniforms only, avoiding native rebuilds as the workload changes.

Intensity scales procedural iterations or fullscreen passes without adding unrelated geometry.

### `:all`

Combines bounded geometry, text, compositor, and shader work in one scene to reveal interaction between CPU/native allocation and GPU fill rate. Each subsystem has its own safe upper bound.

## Metrics and reporting

The metrics collector maintains a bounded sample window after an explicit warm-up:

- Wall-clock frame duration in milliseconds, FPS, sample count, and p50/p95/p99 frame duration.
- Current draw-command count, geometry/group command counts, and live native resource/effect counts where available.
- Cumulative resource uploads, paint-texture uploads, and shader compilations.
- Current profile, intensity, framebuffer dimensions, and relevant toggles.
- Initialization duration and warm-up duration reported separately from measured samples.

The HUD shows a compact line updated at a low fixed cadence (for example 4 Hz), not every frame. Full samples are for benchmarking only and do not change scene rendering.

On clean exit, print one EDN map containing profile/intensity, options, initialization/warm-up durations, sample metrics, and final cumulative counters. `stop!` and window close use the same reporting path. Errors preserve the primary exception; summary failure is reported separately and never replaces it.

## CLI and nREPL API

Add CLI options:

- `--profile showcase|geometry|text|compositor|shader|all`
- `--intensity N` (positive integer, default 1)
- `--sdf true|false` (optional stress toggle)
- `--warmup-ms N` (default 2000)
- `--metrics true|false` (default true; the HUD metrics line can be hidden)
- `--summary true|false` (default true for stress profiles; optional showcase summary)

Existing font/port/size/capture/frame options remain unchanged.

Add redefinable public functions:

- `(load-profile! profile & [{:keys [intensity sdf]}])` — validates and reactively updates profile configuration without recreating the window.
- `(metrics-snapshot)` — returns current warm-up/sample/counter state as plain data.
- `(reset-metrics!)` — clears measured samples/counters for a new comparison while retaining cumulative native counters separately.

Document useful nREPL experiments for each subsystem and how to compare runs.

## Native/API changes

Prefer existing `ss_gl_test_frame_state` counters despite the test-oriented name; promote a general `renderer/frame-state` wrapper for metrics. If live resource/effect counts cannot be derived from existing wrappers, add context-scoped native count fields to that layout rather than a second event API.

Do not add native timing callbacks into Jolt. Timing/sample collection remains Clojure-side on the UI thread. Any new fields preserve ABI layout and raw FFI documentation.

## Testing and verification

Add focused tests for:

- Profile and intensity validation, including zero/negative/unknown values and vertex-limit rejection.
- Scene content per profile and stable showcase behavior.
- Warm-up exclusion, bounded samples, percentile calculations, and counter deltas.
- Low-cadence HUD updates and no per-frame text/native churn.
- Profile changes through nREPL with one context and valid cache reuse.
- EDN summary shape, clean-close behavior, repeated reset, and primary-error preservation.
- A short smoke run per profile with actual native rendering, counters, and no mesh overflow or stale handles.

Run all existing tests/parity/goldens, the timed showcase, and each stress profile. Capture representative metrics output and investigate any correctness issue found. Performance numbers are recorded for diagnosis, not asserted as universal thresholds.

## Documentation

Update `docs/demoscene.md` with workload descriptions, metrics meaning, CLI/nREPL examples, warm-up guidance, and limitations. Link performance usage from the docs home. Document any general frame-state API in `docs/rendering.md` and `docs/ffi.md`.

## Builder decisions

- Exact corpus, node scaling increments, HUD cadence, and visual presentation of stress nodes are left to the implementer within stated bounds.
- Do not add a benchmark results file unless requested; EDN stdout is sufficient.
- Existing performance bottlenecks are measured and documented, not rewritten speculatively in this task.
