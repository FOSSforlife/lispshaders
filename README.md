# lispshaders

A Shadertoy-style sandbox where the shading language is a Clojure dialect.
You write s-expressions in the left pane; a ClojureScript compiler turns them
into GLSL ES 3.0 and hot-swaps the fragment shader on a WebGL2 canvas as you
type — time keeps flowing, so editing feels like sculpting a running program.

It's a drone sandbox too, built on one symmetry: **a shader describes what
every pixel is; a drone describes what the signal is**. A `defsound` is
written in the same language and compiled by the same compiler — not to
GLSL, but to an [Elementary Audio](https://www.elementary.audio/) signal
graph. Because Elementary diffs a re-rendered graph against the playing
one, editing a drone morphs the sound live without ever restarting it —
the exact feel of hot-swapping a shader. An analyser feeds
`bass`/`mid`/`treble`/`level` back into your shader — one program, two
projections, visuals that hear.

## Running it

```sh
npm install
npm run dev      # then open http://localhost:8021
```

- `npm test` — compiler unit tests (Node, no browser needed)
- `npm run build` — optimized release build into `public/js`
- `npx shadow-cljs compile dump && node out/dump.js` — write every example's
  compiled GLSL to `out/glsl/` for inspection

## Documentation

A full guide — beginner-friendly language tutorial, drone-design docs,
a cookbook walking every gallery example, and compiler internals — lives
in `docs/`, built with [Material for MkDocs](https://squidfunk.github.io/mkdocs-material/):

```sh
pip install mkdocs-material   # or: uvx --with mkdocs-material mkdocs serve
mkdocs serve                  # then open http://127.0.0.1:8000
```

## The language

Your shader is a set of top-level `def`/`defn` forms. The entry point is
`image`, called once per pixel; return a color (`vec3`, `vec4`, or a float
for grayscale, components 0..1):

```clojure
(defn image [uv]
  (vec3 (:x uv) (:y uv) (+ 0.5 (* 0.5 (sin time)))))
```

Everything is expression-oriented and statically typed under the hood — types
are inferred from literals, constructors, and builtin signatures, so you never
annotate anything. User `defn`s are inlined at call sites (GPUs can't recurse
anyway), which is what makes the inference work.

Highlights (the full list lives in the app's **reference** tab):

- **Inputs** — `uv` (0..1), `uvc` (centered, aspect-correct — usually what you
  want), `time`, `mouse`, `resolution`, `pi`, `tau`.
- **Vectors** — `(vec3 1 0 0)`, scalar splat `(vec3 0.5)`, keyword swizzles
  `(:xy v)` / `(:zyx v)` / `(:rgb v)`.
- **Flow** — `(if c a b)` and `(cond ... :else e)` compile to ternaries (both
  branches run — that's GPU life). `->` and `->>` thread like home.
- **Loops** — `iter` is the loop/recur of the GPU:

  ```clojure
  ;; one state var: body's value becomes the next t; iter's value is final t
  (iter [i 80] [t 0.0]
    (+ t (scene (+ ro (* rd t)))))

  ;; several state vars: body returns [new-a new-b]; optional result expr last
  (iter [i 96] [z (vec2 0.0) n 0.0]
    [(step-z z) (count-n n)]
    n)
  ```

- **Creative kit** — `noise`, `fbm`, `palette` (IQ cosine palettes),
  `hsv->rgb`, `rot2`, `smin`, and `sd-sphere`/`sd-box`/`sd-torus` for
  raymarching, all baked into every shader's prelude.

## Drones

No notes, no beats, no timeline — the program _is_ a sound, continuously,
the way a shader _is_ an image. Each `defsound` is one layer of a single
soundscape:

```clojure
;; a slow-breathing bass bed: detuned saws through a tide-swept filter
(defsound bed
  (let [f :a1                              ; note keywords are Hz — :a1 is 55.0
        fat (+ (saw f) (saw (* f 1.006)))]
    (->> fat
         (lowpass (+ 220.0 (* 500.0 (lfo 0.05))) 1.4)
         (drive 0.25)
         (* 0.3)
         (space 0.35))))

;; six shimmering partials, each with its own hash-picked detune
(defsound shimmer
  (->> (iter [i 6] [sum 0.0]
         (let [n (+ 1.0 i)]
           (+ sum (* 0.1 (osc (* :a3 n (+ 1.0 (* 0.002 (hash1 n)))))))))
       (echo 700.0 0.45)
       (pan 0.65)))
```

- A drone body returns a `float` (mono signal) or `vec2` (stereo pair).
  Oscillators (`osc` `saw` `square` `tri` `noise` `pink`), filters
  (`lowpass`/`highpass`/`bandpass`), `drive`, `echo`, `pan`, and `space`
  are built in; `lfo` and `wander` are the slow modulators that make a
  drone breathe.
- **◉ start drone** boots the engine (one user gesture, as browsers
  require); after that, every edit morphs the running sound in place.
- Every layer gets a card in the **drones** tab with a live level meter
  and mute/solo — sculpt the mix layer by layer.
- Note keywords are **Overtone-compatible** (`:c4` `:a#3` `:eb2`) and
  compile to plain frequencies, usable anywhere a number fits — in drones
  _and_ in shaders. `(midi->hz m)` works in both too.
- Layers sum through a master tanh saturator, so a hot mix compresses
  warmly instead of clipping.
- The shader hears the mix live: `bass` `mid` `treble` `level` (or the
  whole `vec4 audio`). The Shadertoy export maps these to `iChannel0`
  audio-texture reads.
- `def`s and `defn`s are shared between targets: one constant can tune a
  drone _and_ draw its geometry. A program with drones but no `image`
  gets a built-in bass/mid/treble meter as its visual.
- No recording/export — like visuals, screen-record your performances.

## Exporting

The **GLSL** tab shows exactly what your code compiled to (it's deliberately
readable — your names survive). The **Shadertoy export** tab re-targets the
same shader to Shadertoy's `mainImage`/`iTime`/`iMouse` conventions; paste it
straight into a new shader on shadertoy.com.

## How it's put together

```
src/lispshaders/
  compiler.cljs   the Clojure-dialect compiler (reader, type inference,
                  inlining, codegen for GLSL and Elementary targets)
  prelude.cljs    GLSL helpers + drone builtin signatures + reference data
  audio_lib.cljs  the drone runtime library: DSL ops as Elementary
                  graph builders, with GLSL-style vector broadcasting
  audio.cljs      the drone engine: WebRenderer, layer mixing, mute/solo,
                  live meters, FFT analysis for the shader
  examples.cljs   the gallery, each example teaching one technique
  webgl.cljs      WebGL2 runtime: fullscreen triangle, pausable clock,
                  hot shader swap, mouse/fps tracking
  editor.cljs     CodeMirror 6 wrapper (Clojure mode, one-dark theme)
  core.cljs       Reagent UI wiring it all together
```

Your work-in-progress is autosaved to `localStorage` on every compile, so a
refresh won't eat it. There's no other persistence yet — it's a sandbox.
