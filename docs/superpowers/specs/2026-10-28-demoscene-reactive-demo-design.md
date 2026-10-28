# Slipstream Demoscene Reactive Demo Design

## Intent

Create a polished, playful demo that proves Slipstream is useful for live, reactive graphics rather than static text rendering. It will combine procedural demoscene visuals with independently updated vector geometry, mouse interaction, and a rich in-process nREPL workflow. The application remains running until the user closes its native window.

The demo is both an example and an acceptance test: custom GLSL, reactive uniforms, per-node atlases, gradients/masks/effects, mouse input, and nREPL-driven redefinition must all work in the same long-lived process.

## Goals

- Blend animated sinusoidal wave fields into a true hash-based Voronoi pattern with smooth transitions and colorful palette movement.
- Add a constellation of reactive vector shapes with orbiting, pulsing, color, scale, and staged geometry changes.
- Respond to cursor position, mouse buttons, and wheel input without rebuilding atlases every frame.
- Keep running until the native window closes; support clean shutdown from nREPL too.
- Start a rich `jolt-lang/nrepl` server in the demo process so an editor can evaluate definitions and state changes live, without reload or remount.
- Add regression tests for any input, lifecycle, or nREPL bugs discovered and fix the library rather than working around them in the demo.
- Document how to run and develop the demo live.

## Non-goals and anti-goals

- Do not implement a general-purpose scene editor or GUI widget toolkit.
- Do not rebuild all geometry or replace the native window per animation frame.
- Do not require an external nREPL server process or editor-specific automation for normal use.
- Do not introduce a second reactive/rendering framework or bypass Slipstream's Glimmer backend.
- Do not hide demo-specific input or shutdown deficiencies by mutating global state in unsafe native code.
- Do not sacrifice existing exact parity or golden tests for visual complexity.

## Constraints

- Use the existing Slipstream Glimmer backend and custom effect API.
- The current reactive native renderer polls events but exposes no cursor/button/wheel snapshot and has no request-close operation; add small native query/control functions rather than callbacks into Jolt.
- nREPL evals normally execute on worker threads while GLFW must remain on the UI thread. Reactive state changes must use Glimmer's scheduling/terminal lifecycle correctly.
- Rich middleware comes from `jolt-lang/nrepl` using `nrepl.middleware/default-middleware` and `cider.nrepl/cider-middleware` in `deps.edn`.
- `jolt.nrepl/start` returns a stop function with bound-port metadata. The demo must start it before entering the UI loop and stop it during final teardown.
- Default interactive behavior runs indefinitely. Timed/capture options are test-only and do not alter that default.

## Approaches considered

### Shader-only backdrop

A full-screen fragment effect would provide the best procedural visuals with minimal native work, but would underuse the per-node reactive model. Rejected in favor of the user's hybrid choice.

### Vector-only scene

Many animated shapes would demonstrate resource updates but make true Voronoi math awkward and create avoidable atlas churn. Rejected.

### Hybrid procedural backdrop and reactive vector constellation — selected

One full-screen custom effect renders waves/Voronoi. Independent vector shapes provide orbit/pulse/color updates and staged geometry edits. This balances visual quality with meaningful proof of both uniform-level and per-node incremental behavior.

## Visual and interaction design

### Procedural backdrop

A single full-screen shape uses a custom fragment hook with:

- Layered sinusoidal/interference waves and radial flow.
- A deterministic 2D hash and nearest-feature-point Voronoi distance/cell coloring.
- A smooth transition parameter mixing wave and Voronoi fields rather than a hard cut.
- Mouse-warped coordinates and local attraction/repulsion.
- Time, resolution/aspect, transition, mouse, density, speed, and palette uniforms.
- A cyclic but tasteful palette with a dark background and luminous edges.

### Reactive vector constellation

A modest set of independent shapes (roughly a dozen, tuned during implementation) provides:

- Orbital positions and pulsing scales/colors updated through ratoms/draw data.
- Gradient/pattern fills and at least one SDF/effect element.
- Staged path changes only at explicit transition points, proving unrelated node resources remain stable.
- Mouse-following attraction with damping and button-driven burst/scatter behavior.

The vector layer remains legible over the shader background and includes a small text HUD with current mode, density/speed, and nREPL connection hints.

### Controls

- Cursor: warp the procedural field and attract the constellation.
- Left button/hold: boost energy and scatter/burst nodes.
- Right button or Space: toggle wave/Voronoi transition target.
- Wheel: adjust Voronoi density/field zoom within bounded values.
- `R`: reset animation and input state.
- Close button: exit and release all native/nREPL resources.

Optional keyboard behavior uses the same polled input snapshot; no native callbacks into Jolt are required.

## Native input and close API

Add context-scoped native functions:

- `ss_gl_input_snapshot(context, out)`: cursor position in window coordinates, button state bit mask, accumulated wheel delta, and key state bits needed by the demo. Wheel delta is consumed/reset by each snapshot so it is not applied repeatedly.
- `ss_gl_request_close(context)`: set the native window close flag, allowing `stop!` or nREPL to end the loop safely.
- Coordinate conversion helper in Clojure mapping window cursor coordinates into the current Y-up scene bounds, accounting for framebuffer/window differences.

Input snapshots are borrowed only during the call and contain no pointers. Invalid/destroyed contexts return native errors through the existing boundary.

## Reactive state and nREPL lifecycle

The demo namespace exposes:

- `state` — a `defonce` Glimmer ratom containing time, transition, mouse, density, speed, button state, and vector parameters.
- `app` — a redefinable component reading `state` and producing the hybrid scene.
- `start!` — idempotently starts rich nREPL, stores its stop function, and enters `ui/run` on the calling/UI thread.
- `stop!` — idempotently requests native close and calls the nREPL stop function after UI teardown.
- `-main` — requires a TTF/OTF font path for the HUD, parses optional port/capture/test options, and calls `start!` with production defaults. There is no bundled font or platform-font guessing; invocation matches the existing examples.

The nREPL server starts before `ui/run`, with rich middleware resolved through the project's dependency configuration. The default port is 7888; `0` requests an OS-assigned port. The actual port and editor hint are printed. The stop function is always called exactly once by application teardown; repeated `stop!` is harmless.

Redefinitions of `app`, palette functions, shader source vars, and update functions take effect through var derefs in the running process. State is not reset by evaluating definitions. Normal development does not call `ui/reload!` or restart the window.

## Bug-fix policy and nREPL friendliness

Any discovered input, close, scheduling, redefinition, native lifetime, or middleware issue gets a focused regression and a library fix. In particular:

- Off-thread nREPL updates must not run native code on the eval worker.
- Terminal teardown must drop late work and make repeated `stop!` safe.
- Close requests must unblock a running loop without destroying context before unmount.
- Middleware resolution/startup errors must surface before native UI creation when possible, with no leaked partial server/window.
- Documentation must distinguish evaluating/redefining from reloading or restarting.

## Testing and verification

Add focused native and Jolt tests:

- Cursor/button/wheel snapshot values and wheel-consumption semantics.
- Coordinate mapping into explicit/default bounds and high-DPI window/framebuffer differences.
- Request-close causing prompt clean loop exit and safe repeated shutdown.
- Rich nREPL startup, session eval, state update, function redefinition, and completion/descriptor availability.
- Idempotent `start!`/`stop!`, including startup failure and close during scheduled work.
- Exact golden frames for waves, Voronoi, transition midpoint, mouse-warped state, and vector stage changes.
- Resource counters proving uniform/orbit/color updates do not rebuild atlases and staged path updates touch only intended nodes.

Run the complete existing native build, full tests, C++ parity, and reactive goldens after changes. Run the demo interactively through the nREPL-driven workflow, verify mouse behavior and close, and capture representative frames. No claim of interactive parity will rest on compilation alone.

## Documentation

Add `docs/demoscene.md` covering controls, visual architecture, `start!`/`stop!`, nREPL startup/connection, useful live evaluations, and test/capture options. Link it from the docs home and README examples. Record any new input API in `docs/rendering.md` and `docs/ffi.md`.

## Builder decisions

- Exact palette, node count, timing curves, and aesthetic tuning are left to the implementer as long as tests remain deterministic when time is injected.
- Native test hooks may expose input injection and deterministic clock advancement, but production behavior must use real GLFW input/time.
- Generated captures, `.nrepl-port`, and cache directories are not committed.
