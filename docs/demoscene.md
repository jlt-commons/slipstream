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

For a bounded smoke run with representative captures:

```sh
jolt -m slipstream.example.demoscene /System/Library/Fonts/Geneva.ttf \
  --auto-quit-ms 10000 --capture-dir native/build/demoscene-captures
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

## Visual and reactive architecture

The example is split into two namespaces:

- `slipstream.example.demoscene` owns application lifecycle, the rich nREPL server, native input polling, and the Glimmer UI loop.
- `slipstream.example.demoscene-shader` owns visual state, palette and shader sources, deterministic state updates, staged vector definitions, and scene generation.

`demo/state` is a `defonce` Glimmer ratom. Its initial keys include animation `:time`, `:transition` and `:transition-target`, raw scene-space `:mouse`, damped `:attraction`, `:density`, `:speed`, `:burst`, button/key state, `:bounds`, `:aspect`, `:nrepl-port`, `:geometry-stage`, and per-vector overrides under `:vectors`.

`demo/app` reads that ratom and calls `visuals/scene-for-state`. The scene contains:

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
  --auto-quit-ms 10000 --capture-dir native/build/demoscene-captures
```

The lifecycle suite includes a real hidden native window driven through `nrepl.core`. In one session it checks `describe` and completion, redefines `update-state`, changes ratom state, redefines `palette`, compares actual RGBA frames from the same GL context, and closes through nREPL. Native capture work is scheduled back onto the UI thread.

Visual tests cover exact waves, Voronoi, transition midpoint, mouse-warp, and vector-stage goldens, plus resource stability and targeted path rebuilding. `native/build/demoscene-captures`, `.nrepl-port`, native libraries, and other generated artifacts are not source files and must not be committed.
