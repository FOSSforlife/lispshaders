# Under the hood

You don't need this page to use the app. It's for the curious, for
contributors, and for anyone who wants to trust the tool by understanding
it.

## The map

```
src/lispshaders/
  compiler.cljs    the Clojure-dialect compiler: reader, type inference,
                   inlining, note keywords, and codegen for BOTH targets
                   (GLSL and Elementary signal graphs)
  prelude.cljs     GLSL helper library + shader and drone signature
                   tables + the data behind the in-app reference tab
  audio_lib.cljs   the drone runtime library: every DSL op as an
                   Elementary graph builder, with GLSL-style broadcasting
  audio.cljs       the drone engine: WebRenderer worklet, layer mixing,
                   mute/solo gates, live meters, FFT analysis
  webgl.cljs       WebGL2 runtime: fullscreen triangle, pausable clock,
                   hot shader swap, uniform uploads
  editor.cljs      CodeMirror 6 wrapper (Clojure mode, one-dark theme)
  examples.cljs    the gallery
  core.cljs        Reagent UI wiring it all together

test/lispshaders/
  compiler_test.cljs   the test suite (runs in Node, no browser)
  dump.cljs            writes every example's GLSL to out/glsl/
```

Stack: [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
builds ClojureScript; [Reagent](https://reagent-project.github.io/) (React)
renders the UI; [Elementary Audio](https://www.elementary.audio/) runs the
drone graphs; CodeMirror 6 is the editor. The dev server (`npm run dev`,
port 8021) hot-reloads code without losing the running shader.

## The compile pipeline

Every keystroke (debounced to ~4×/second) runs the whole pipeline:

```
editor text
  └─ Clojure reader (the real one — cljs.reader)
       └─ collect top level: defns, defs, defsounds
            ├─ compile `image`  ──► GLSL (WebGL2)      ─► hot-swap shader
            ├─ compile `image`  ──► GLSL (Shadertoy)   ─► export tab
            └─ compile drones   ──► JS graph builders  ─► drone engine
```

Because the source is read with an actual Clojure reader, the "DSL" is
genuinely Clojure syntax — the compiler works on real forms, and things
like `->` are implemented by rewriting forms before compilation, exactly
like macros.

### One compiler, two targets

Every expression compiles to the same intermediate shape:

```clojure
{:stmts ["float d = length(p);"]   ; statement lines emitted so far
 :code  "(d * 2.0)"                ; the expression itself
 :type  :float}                    ; its inferred type
```

All the interesting work — type inference, `iter` state threading,
inlining, error checking — happens once, on that shape. Only the
last-mile spelling differs by target (a `:lang` flag in the compiler
context):

| Construct     | GLSL says           | The drone target says            |
| ------------- | ------------------- | -------------------------------- |
| declaration   | `vec3 c = ...;`     | `let c = ...;`                   |
| `(+ a b)`     | `(a + b)`           | `add(a, b)`                      |
| `(:xy v)`     | `v.xy`              | `sw(v, "xy")`                    |
| `(< a b)`     | `(a < b)`           | `lt(a, b)` — a 0/1 gate _signal_ |
| `(if c t e)`  | `(c ? t : e)`       | `select(c, t, e)` — a crossfade  |
| `iter` header | `for (int i = ...)` | `for (let i = ...)`              |

The deep difference is _when the code runs_. GLSL executes per pixel;
the drone target's JS executes **once, at compile time**, and every op
builds Elementary graph nodes instead of computing numbers — `add(a, b)`
returns a node that will add two signals forever. `audio_lib.cljs`
provides that library with two invariants: vectors are arrays with
GLSL-style scalar↔vector broadcasting (a `vec2` is a stereo pair), and
plain numbers fold eagerly (`(* 2.0 3.0)` is `6` at build time; a value
only becomes a node once a signal touches it). An `iter` loop therefore
_unrolls into graph structure_ — eight iterations, eight oscillators.

Ops with no signal-rate meaning (`hash1` of a signal, `atan`, matrices)
throw friendly build-time errors instead.

### Why inlining is the load-bearing decision

User `defn`s never become GLSL functions; every call site expands the
body with arguments bound (through temporaries unless the argument is
trivially cheap, so nothing is evaluated twice). That single choice buys:

- **Zero type annotations.** Types flow bottom-up from literals and
  builtin signatures; since each call is expanded in place, parameters
  take their types from that call's actual arguments. One helper is
  effectively generic across floats and vectors.
- **No recursion** — which GLSL forbids anyway. A self-call would expand
  forever; a depth counter (32) turns that into a friendly error.
- **Readable output.** The GLSL tab shows your math where you used it,
  with your names.

### Defs that fail politely

Top-level `def`s are compiled per target, in order. One that references
something the target doesn't have (`mouse` in a drone; `(lfo ...)` in the
shader) is _skipped and remembered_ rather than failing the program; the
stored error surfaces only if that target actually references the name.
This is the mechanism that lets one buffer serve both projections —
shader-side and drone-side helpers coexist without ceremony.

## The drone engine

Compiled drone bodies become real functions via `js/Function` — the JS
equivalent of `eval`, safe here because the code was generated by our own
compiler, not typed by a third party. Calling one builds a graph; it
plays nothing by itself, which is why the test suite can construct and
inspect drone graphs in plain Node.

**Rendering is reconciliation.** The engine evaluates every `defsound`,
mixes the layers, and hands the final stereo pair to Elementary's
`WebRenderer`, which runs the graph in an AudioWorklet. The crucial
property: rendering a new graph **diffs it against the playing one** —
unchanged subgraphs keep their state (delay lines, filter memories,
oscillator phases), so an edit morphs the running sound instead of
restarting it. This is the exact audio analog of hot-swapping the
fragment shader while the clock keeps ticking.

**Mute/solo are graph values, not connections.** Each layer is multiplied
by a keyed `const` node smoothed over ~80 ms; toggling mute re-renders
with a new value, which Elementary applies as a property update — a fade,
never a click, and the muted layer keeps running silently so unmuting
lands in the same place. Each layer also passes through an `el.meter`
node, whose peak events drive the level bars in the drones tab.

**The master is a saturator.** The layer sum is scaled by a smoothed
master gain (which starts at zero in a fresh worklet — the ~1 s fade-in
on first start is free) and pushed through `tanh`, so an over-stacked mix
compresses warmly instead of clipping. Stopping the engine suspends the
AudioContext: the graph — and the drone-side `time` signal — freezes in
place and resumes exactly where it stopped.

**Analysis → uniforms.** A native `AnalyserNode` taps the output; each
rendered frame its FFT is condensed into bass/mid/treble/level (dB mapped
to 0..1, smoothed with a decaying max so swells are instant and releases
gentle) and uploaded as the `u_audio` vec4 uniform. The shader never
touches audio APIs; it just reads four numbers that happen to describe
the sound.

## The WebGL side

Rendering is the standard modern minimal setup: a single triangle large
enough to cover the screen (generated in the vertex shader from
`gl_VertexID` — no vertex buffers at all), with the entire image produced
by the fragment shader. Recompiling swaps the fragment program in place;
the clock, mouse state, and canvas survive, which is what makes editing
feel continuous. The clock is pausable and rewindable independently of
compilation.

## Testing without a browser

`npm test` compiles the suite with shadow-cljs and runs it in Node. It
covers: every gallery example compiling for both GLSL targets _and_
building its drone graphs for real, codegen details (swizzles, shadowing
suffixes, `iter`'s parallel `_next` assignments, int→float casts, pow
splatting, `select`/gate emission), the helpful-error catalog, number
folding in the drone library, and Overtone note-keyword parsing —
possible without a browser because graph construction is pure. For
eyeballing GLSL, `npx shadow-cljs compile dump && node out/dump.js`
writes every example's output to `out/glsl/`.
