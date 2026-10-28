# Live hybrid demoscene

`slipstream.example.demoscene` combines an animated procedural fragment shader with twelve independently reactive vector shapes and a Geneva-font HUD. It is both a visual example and a live-development environment: a rich in-process nREPL starts before the native window, and the same OpenGL context remains running while state, vars, and functions are redefined.

## Build and run

Build Slipstream first, then provide a TTF/OTF font explicitly:

```sh
jolt native
jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf
```

The default is an unlimited interactive session at `960x640`. The application prints its actual nREPL address before entering the UI loop:

```text
Demoscene nREPL: 127.0.0.1:7888 — connect your editor
```

CLI options:

| Option | Meaning |
|---|---|
| `<font.ttf>` | Required TTF/OTF path used by the HUD. There is no bundled font or platform-font guessing. |
| `--port N` | nREPL port, default `7888`. Use `0` to request an OS-assigned port; the printed address and `start!` result contain the actual port. |
| `--capture-dir PATH` | Write the first rendered frame and subsequent frames at two-second intervals as numbered RGB PPM files in PATH. |
| `--max-frames N` | Exit after N frames. Nil by default. |
| `--auto-quit-ms N` | Exit after N milliseconds. Nil by default. |
| `--profile showcase\|geometry\|text\|compositor\|shader\|all` | Select the workload; default `showcase`. |
| `--intensity N` | Bounded integer workload multiplier in `1..16`; default `1`. Invalid or mesh-unsafe work is rejected. |
| `--sdf true\|false` | Enable or disable the profile's optional SDF/MSDF work; unset by default. |
| `--warmup-ms N` | Nonnegative warm-up duration, default `2000`; initialization and warm-up are excluded from steady-state samples. |
| `--metrics true\|false` | Show the low-cadence HUD metrics line. Defaults to true for stress profiles and false for `showcase`. |
| `--summary true\|false` | Print one EDN summary after clean UI teardown. Defaults to true for stress profiles and false for `showcase`. Summary collection still runs when the HUD is hidden. |

For a bounded smoke run with representative captures and a summary:

```sh
jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf \
  --auto-quit-ms 10000 --capture-dir native/build/demoscene-captures \
  --summary true
```

A geometry stress comparison can use a shorter explicit warm-up:

```sh
jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf \
  --profile geometry --intensity 4 --sdf true --warmup-ms 1000 \
  --auto-quit-ms 5000 --summary true
```

GUI runs require a working display and OpenGL context. Building native code or loading namespaces is not visual verification.

## Controls

- **Move the pointer** to warp the procedural field immediately and attract the constellation through dt-based damping.
- **Left-click or hold** to boost burst energy fully. Releasing the button lets the burst decay; pressing again restores the boost.
- **Right-click or Space** toggles the orbital/constellation-to-grid transition on each new press. Holding either input does not repeatedly toggle, and pressing both together is one logical toggle.
- **Mouse wheel** changes procedural density, clamped to `0.25..4.0`.
- **R** resets animation and interaction state while preserving the current bounds, aspect ratio, nREPL port, and font.
- **Close the native window**, or evaluate `stop!` through nREPL, to shut down.

The compatibility `slipstream.example.glfw` controls are separate; in particular, its orbit-camera and Esc bindings are not the demoscene controls.

## Performance workloads

Profiles are opt-in except for the unchanged `showcase`. Intensity is always bounded to `1..16`; it scales authored work rather than continuously reallocating resources to simulate load.

| Profile | Workload and scaling |
|---|---|
| `showcase` | The original wave/Voronoi shader, twelve vectors, staged geometry, and HUD. Metrics and summaries are opt-in. |
| `geometry` | Per intensity, four unique and four shared keyed vector nodes, with 256 path segments per intensity in each path. Unique nodes pressure atlas/mesh resources; shared copies demonstrate content-cache reuse. Animation changes placement/color without path rebuilds, and `:sdf true` adds SDF/MSDF baking/effects. |
| `text` | Four long text lines per intensity using a repeated ASCII, punctuation, symbol, and non-ASCII corpus. Text content revises periodically while color/placement also change, exercising glyph reuse and targeted text updates. |
| `compositor` | Two nested masked groups per intensity, with gradient/pattern shapes and varying Porter-Duff/Photoshop blends. `:sdf true` selects SDF-tile outer masks. This stresses offscreen targets, masks, blending, and fill rate. |
| `shader` | One full-bounds procedural effect pass per intensity at high density. Animated uniforms increase fragment/ALU cost without adding unrelated native geometry. Framebuffer size strongly affects this profile. |
| `all` | Combines the bounded geometry, text, compositor, and shader work to expose interactions between allocation, draw submission, compositing, and fill rate. |

Use the same window size, bounds, intensity, SDF setting, warm-up, and approximate run duration when comparing changes. Stress profiles can be expensive by design; lower intensity or framebuffer size on slower hardware.

## Metrics, warm-up, and EDN summaries

Sampling happens on the UI thread after rendering. The first after-render callback records `:initialization-ms`, covering application/native startup and the cold first render. The collector then waits `:warmup-ms` (default 2000 ms) before retaining frame durations. Neither initialization nor warm-up is included in steady-state percentiles. A zero warm-up is useful for short smoke tests; longer runs should allow shader and atlas activity to settle.

The last 600 measured durations form a bounded window. The HUD updates at most four times per second and does not rewrite its text every frame. `metrics-snapshot` and the exit summary contain:

- `:profile`, `:intensity`, and effective `:options`, including SDF, warm-up, metrics, and summary settings;
- `:initialization-ms`, configured `:warmup-ms`, measured `:elapsed-ms`, and `:warming-up?`;
- `:sample-count`, `:last-frame-ms`, `:mean-frame-ms`, derived `:fps`, and `:frame-percentiles-ms` with `:p50`, `:p95`, and `:p99` in milliseconds;
- current `:framebuffer-size`, `:live-resources`, `:live-effects`, `:draw-commands`, `:geometry-commands`, and `:group-commands` (command counts are zero when the active backend does not supply them);
- native cumulative `:shader-compilations`, `:resource-uploads`, and `:paint-texture-uploads` under `:cumulative-counters`, plus post-warm-up/reset `:counter-deltas`.

Cumulative counters describe the native context's lifetime; deltas describe the current measurement interval. Live counts are instantaneous, not totals. A reset clears durations, the counter baseline, current counts, and HUD text while retaining the last native cumulative values.

After a clean window close or `stop!`, summary-enabled runs print exactly one EDN map after UI teardown. Repeated `stop!` calls do not duplicate it. Values below only illustrate the shape:

```clojure
{:profile :geometry
 :intensity 2
 :options {:profile :geometry :intensity 2 :sdf nil
           :warmup-ms 1000 :metrics true :summary true}
 :initialization-ms 184.2
 :warmup-ms 1000
 :elapsed-ms 4000.0
 :sample-count 118
 :last-frame-ms 16.8
 :mean-frame-ms 16.9
 :fps 59.2
 :frame-percentiles-ms {:p50 16.7 :p95 19.4 :p99 24.1}
 :framebuffer-size [1920 1280]
 :live-resources 35
 :live-effects 6
 :draw-commands 162
 :geometry-commands 162
 :group-commands 0
 :cumulative-counters {:shader-compilations 10
                       :resource-uploads 52
                       :paint-texture-uploads 7}
 :counter-deltas {:shader-compilations 0
                  :resource-uploads 0
                  :paint-texture-uploads 0}}
```

Summary generation/output failure is reported separately on stderr and never replaces an existing primary exception. With no primary error, a summary failure still fails the run and cleanup completes.

Frame rate is not a universal pass/fail metric. GPU model/driver, CPU, display refresh, framebuffer scale, window visibility, thermal state, and other running work all affect results. Compare p95/p99 and counter changes on the same hardware, not just mean FPS. Increased uploads or compilations can indicate resource churn; increased live resources can indicate retained workload resources; high frame cost with stable counters often points to draw, compositor, or fragment work.

## Visual and reactive architecture

The example is split into four namespaces:

- `slipstream.example.demoscene` owns application lifecycle, the rich nREPL server, native input polling, profile controls, metric sampling, summaries, and the Glimmer UI loop.
- `slipstream.example.demoscene-shader` owns visual state, palette and shader sources, deterministic state updates, staged vector definitions, and showcase scene generation.
- `slipstream.example.demoscene-profiles` validates profile configuration and generates bounded stress scenes.
- `slipstream.example.demoscene-metrics` owns the bounded collector, warm-up/reset behavior, percentiles, counter deltas, and low-cadence HUD text.

`demo/state` is a `defonce` Glimmer ratom. Its initial keys include animation `:time`, `:transition` and `:transition-target`, raw scene-space `:mouse`, damped `:attraction`, `:density`, `:speed`, `:burst`, button/key state, `:bounds`, `:aspect`, `:nrepl-port`, `:geometry-stage`, and per-vector overrides under `:vectors`. Active runs also carry `:profile`, `:intensity`, `:sdf`, and metrics display state.

`demo/app` reads that ratom and calls `profiles/profile-scene`, which delegates `showcase` to `visuals/scene-for-state`. The showcase scene contains:

- one full-bounds shape running the hybrid waves/Voronoi fragment effect;
- twelve keyed vector nodes with independent paths, fills, placement, SDF/MSDF, and effects;
- title, status, and controls HUD text. Status shows the selected waves/Voronoi mode, density, speed, and actual bound nREPL address. Its text changes only when one of those displayed values changes, so animation-only frames reuse the same glyph resources.

After every render, the UI thread reads a native input snapshot, maps the cursor into the complete scene bounds, and calls `visuals/update-state`. The first rendered frame uses a zero timestep; native startup and shader compilation are never included in simulation time. Uniform, color, damped attraction, and orbit changes update draw layers without rebuilding native geometry.

Automatic geometry stages cross explicit simulation-time thresholds: at `4.5s`, vectors `0` and `7` change paths; at `7.5s`, vectors `5` and `10` change paths. These changes are cumulative and rebuild only the intended vector resources. Manual per-vector path edits through nREPL have the same targeted behavior.

## Rich nREPL connection

The project requests both default nREPL middleware and CIDER middleware in `deps.edn`:

```clojure
:nrepl/middleware [nrepl.middleware/default-middleware
                   cider.nrepl/cider-middleware]
```

The demo passes the same middleware explicitly to `jolt.nrepl/start`. This supports stateful `eval`, `describe`, and completion (`complete`) operations in the running process. Start the demo before connecting the editor to `127.0.0.1:7888`, or use the actual port printed for `--port 0`.

Useful aliases for interactive evaluation:

```clojure
(require '[slipstream.example.demoscene :as demo]
         '[slipstream.example.demoscene-shader :as visuals])
```

Change reactive state directly; the existing window renders the resulting scene:

```clojure
(swap! demo/state assoc :density 2.25 :speed 0.5)
(swap! demo/state assoc :transition-target 1.0)
(swap! demo/state assoc-in [:vectors 3 :color] [1.0 0.25 0.1 1.0])
(select-keys @demo/state [:time :density :speed :transition])
```

Switch workloads in the same native window, inspect a plain-data snapshot, then reset before a comparison:

```clojure
(demo/load-profile! :geometry {:intensity 4 :sdf true})
(demo/metrics-snapshot)
;; Allow initialization and the configured warm-up to finish, then:
(demo/reset-metrics!)
(demo/metrics-snapshot)

(demo/load-profile! :text {:intensity 2 :sdf false})
(demo/reset-metrics!)

(demo/load-profile! :compositor {:intensity 2})
(demo/load-profile! :shader {:intensity 4})
(demo/load-profile! :all {:intensity 1})
(demo/load-profile! :showcase)
```

`load-profile!` validates before changing state, updates the existing ratom, and returns `{:profile ... :intensity ... :sdf ...}`. Omitted intensity defaults to `1`; omitted SDF is nil. Reactive reconciliation renders the new workload without recreating the window, nREPL server, or native context. Existing content caches remain available. Reset after each switch so percentiles and deltas do not mix workloads; native cumulative counters remain visible for cache/churn analysis. Snapshot keys and interpretation are described above.

These functions run ordinary Clojure/Jolt code on nREPL workers. Do not call native context operations directly from those workers; use `ui/on-gui` for GUI work.

Redefine the palette by evaluating forms in the visuals namespace, as an editor's namespace-aware evaluation normally does:

```clojure
(in-ns 'slipstream.example.demoscene-shader)

(def palette
  {:a [0.9 0.2 0.35]
   :b [0.1 0.4 0.3]
   :c [1.0 1.0 1.0]
   :d [0.0 0.16 0.34]})

;; Trigger a fresh reactive render after changing a non-ratom var.
(swap! slipstream.example.demoscene/state update :density + 0.001)
```

Function redefinition also uses the running vars. For example, evaluate once to double the simulation timestep while preserving input handling:

```clojure
(in-ns 'slipstream.example.demoscene-shader)

(defonce unscaled-update-state update-state)
(defn update-state [state dt input]
  (unscaled-update-state state (* 2.0 dt) input))
```

To experiment with the component itself, redefine `demo/app` while retaining both public arities:

```clojure
(in-ns 'slipstream.example.demoscene)

(defn app
  ([font-path] (app font-path (:aspect @state)))
  ([font-path aspect]
   (visuals/scene-for-state
     (assoc @state :font font-path :aspect aspect :density 1.5))))
```

Use unqualified `def`/`defn` in the target namespace. In Jolt, a namespace-qualified `def` form is not a substitute for namespace-aware evaluation and does not replace the intended var root.

Keep update functions pure and fast: `update-state` and component functions run on the UI thread. nREPL evaluations run on server worker threads; do not call native rendering or context operations directly from those workers. Use `glimmer.core/on-gui` for necessary GUI work. Once a context has torn down, scheduling is terminal and late work is dropped.

## `start!` and `stop!`

### `(start! & [options])`

Starts the nREPL server before native UI creation, stores one lifecycle record, and enters `glimmer.core/run` on the calling thread. It blocks until the UI loop exits, then stops the server exactly once and returns `{:port actual-port}`.

A nested or concurrent start while the lifecycle is active reuses the existing application and returns `{:port actual-port}` without opening another server or window. Startup failures clean up partial state; nREPL startup failure prevents UI creation.

Options:

- `:font-path` — required TTF/OTF path.
- `:port` — default `7888`; `0` selects an OS-assigned port.
- `:width`, `:height` — defaults `960`, `640`.
- `:bounds` — optional `[left right bottom top]`; when supplied, its aspect takes precedence over width/height. Arbitrary supported bounds (including nonzero/negative origins) drive the background, vector and HUD placement, shader mouse normalization, and native cursor mapping; they are not reduced to aspect alone.
- `:capture-dir` — optional directory for the demo's first-frame/two-second-interval PPM capture callback.
- `:max-frames`, `:auto-quit-ms` — optional UI-loop stop conditions; both default to nil.
- `:profile` — `:showcase`, `:geometry`, `:text`, `:compositor`, `:shader`, or `:all`; default `:showcase`.
- `:intensity` — integer `1..16`, default `1`; `:sdf` — true, false, or nil.
- `:warmup-ms` — nonnegative integer, default `2000`.
- `:metrics` — HUD toggle; defaults to true for stress profiles and false for `showcase`.
- `:summary` — clean-exit EDN toggle with the same profile-dependent default. A true summary collects samples even when `:metrics` is false.

Programmatic launch from a normal application entry point:

```clojure
(demo/start! {:font-path "/System/Library/Fonts/Geneva.ttf"
              :port 0
              :width 960
              :height 640})
```

Because `start!` blocks, do not evaluate the initial launch on an nREPL worker that you expect to remain responsive. The CLI is the usual entry point.

### `(stop!)`

Idempotently schedules native close with `ui/on-gui` and immediately returns nil. The server stays available until the UI has torn down; UI cleanup then stops it. Repeated calls are harmless, including before start and after teardown. From nREPL:

```clojure
(slipstream.example.demoscene/stop!)
```

The eval connection closes as application teardown completes. Wait for the original blocking `start!` call to return before starting another window.

## Redefine, reload, and restart

These operations are deliberately different:

- **Redefine** evaluates new var/function roots in the current process. `defonce` state is preserved, no window or GL context is recreated, and no `ui/reload!` is needed. A subsequent ratom change makes the reactive component observe non-ratom var changes.
- **Reload** loads namespace source again. It is not required for ordinary live edits and can overwrite redefined vars. `defonce` state may survive, but reloading is not a substitute for triggering a reactive render.
- **Restart** stops the UI and later calls `start!` again, creating a new native window/context and new GPU resources. Within one process, `defonce` state still survives; reset it explicitly with `visuals/initial-state` if desired. A new process gives the cleanest full reset.

Normal demoscene development uses redefinition plus state changes, not reload or restart.

## Automated verification and captures

Run the complete verification suite with the same explicit font:

```sh
jolt native
SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt test
SLIPSTREAM_TEST_FONT=/System/Library/Fonts/Geneva.ttf jolt parity
jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf \
  --auto-quit-ms 10000 --capture-dir native/build/demoscene-captures \
  --summary true
for profile in geometry text compositor shader all; do
  jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf \
    --profile "$profile" --intensity 1 --warmup-ms 250 \
    --auto-quit-ms 1500 --capture-dir "native/build/demoscene-$profile-captures"
done
```

Inspect each single EDN summary and representative PPM. Use nonzero measured sample counts for performance comparisons; a run shorter than its warm-up is only a rendering/lifecycle smoke.

The lifecycle suite includes a real hidden native window driven through `nrepl.core`. In one session it checks `describe` and completion, redefines `update-state`, changes ratom state, redefines `palette`, switches profiles and resets metrics, compares actual RGBA frames from the same GL context, and closes through nREPL. Native capture work is scheduled back onto the UI thread.

Visual tests cover exact waves, Voronoi, transition midpoint, mouse-warp, and vector-stage goldens, plus resource stability and targeted path rebuilding. `native/build/demoscene-captures`, `.nrepl-port`, native libraries, and other generated artifacts are not source files and must not be committed.
