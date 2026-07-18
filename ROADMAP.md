# lispshaders — upcoming feature checklist

A living wishlist. Everything here is measured against the project's core
convictions, so they're worth stating up front:

- **You describe what a thing *is*, not the steps to make it.** Every feature
  should preserve the purity — a shader is `image(uv)`, a drone is a signal.
- **One language, two projections.** The same compiler already emits GLSL and
  an Elementary graph. New power should ideally fall out of *that symmetry*,
  not bolt a third unrelated system onto the side.
- **Live morphing is the feel.** Editing changes a *running* program in place —
  time never stops, sound never restarts. Anything that forces a "stop / rebuild
  / press play" step is working against the grain.
- **Small code, big result.** Features earn their place by raising the ratio of
  emergent richness to lines typed — a new *word*, rarely a new *panel*.
- **Deliberately out of scope** (per prior decisions): persisting *programs*
  anywhere but the browser (localStorage autosave, URL permalinks). Uploaded
  media assets live in the browser too (IndexedDB — see §8), keeping the same
  nothing-leaves-your-machine stance. *(Audio/video recording was previously
  out of scope but is now back in — see §7.)*

Checklist is grouped by theme. `[ ]` = not started. Rough effort in *(S/M/L)*.

---

## 1. Deepen the language (the highest-leverage area)

The DSL is the soul of the project; every word added here multiplies across
both projections.

- [ ] **`iter` accumulation helpers** *(S)* — `sum`/`product`/`max`/`min` folds
  as sugar over the current explicit-state `iter`. Raymarch and FBM loops are
  the same shape 90% of the time; naming that shape shrinks the common case to
  one line without hiding the general form.
- [ ] **Local `defn` via `letfn`-style binding** *(M)* — right now helpers must
  be top-level. Allowing inline named helpers (still inlined at call sites, still
  no recursion) keeps a whole thought in one screen — directly serving "small
  code, big image."
- [ ] **Compile-time constant folding surfaced to the user** *(S)* — a hover /
  reference note showing that `(* 2.0 pi)` became a literal. Teaches the "it's
  just math" mental model and demystifies the inliner.
- [ ] **Destructuring in `let` and `defn` params** *(M)* — `(let [[x y] uv] …)`
  reads more like the math than `(:x uv)`/`(:y uv)`. Pure sugar over swizzles;
  no runtime cost.
- [ ] **User-defined operators as pipeline stages** *(M)* — encourage the
  `(-> space warp scene glow tint)` idiom by making it trivial to publish a
  named stage. Composition *is* the creativity here (per *Everything is a
  pipeline*); lower the cost of naming a stage.
- [ ] **Better type-error messages with a suggested fix** *(M)* — "expected
  vec2, got float — did you mean `(vec2 x)`?" Inference is a headline feature;
  its errors should teach, not scold.

## 2. Lean into the shader↔drone symmetry (the project's signature idea)

This is what makes lispshaders *lispshaders*. The richest features live here.

- [ ] **Shared `def`s that compile to both targets, visibly** *(M)* — the engine
  already skips defs that only work in one projection. Make that a *feature*: a
  gutter marker showing "this binding drives both the image and the sound," so
  users deliberately author cross-modal values. One LFO shaping a filter *and* a
  hue is the whole thesis in one line.
- [ ] **`level`/`bass`/`mid`/`treble` preview meters in the editor gutter** *(S)*
  — you can already feed audio into visuals; showing those globals live next to
  the code closes the loop between the two projections.
- [ ] **Visual "signal scope" for drones** *(M)* — render any drone sub-expression
  as an oscilloscope/spectrogram *using the shader engine itself*. The audio graph
  drawn by the thing it's a sibling to — symmetry demonstrating symmetry.
- [ ] **Cross-projection `sync` primitive** *(M)* — a shared phasor both the image
  and the drone can read, so a visual pulse and an audible one are provably the
  same clock. Keeps everything pure (it's just another input like `time`).

## 3. Make exploration faster (the tweak→see→repeat loop)

The app recompiles per keystroke because *play* is the point. Sharpen the play.

- [ ] **Inline number scrubbing** *(M)* — click-drag any numeric literal to nudge
  it and watch the running program morph. The single biggest amplifier of the
  existing hot-swap feel; turns every constant into a knob without adding a UI.
- [ ] **`(range …)` / parameter sweeps as a preview strip** *(L)* — pick a literal,
  see a contact-sheet of N variations across a range. Literalizes "a parameterized
  family of images" from *Build a system, then be surprised by it*.
- [x] **Rewind 10s button** *(S)* — `↩ -10s` in the toolbar next to `⟲ t=0`:
  jump back and rewatch the moment that just went by, playing or paused.
  *(Shipped 2026-07-18.)*
- [ ] **Freeze-frame diffing** *(S)* — pause (already exists), fork the frozen slice,
  edit, and A/B against the frozen original. Leans on the "animation is a 4D object,
  a frame is a slice" model already in the docs.
- [ ] **Deterministic seed pinning** *(S)* — lock `hash`/`noise` seeds so an
  accident is reproducible. The docs promise "accidents are never mysteries";
  make that literally true with one toggle.
- [ ] **Keyboard-driven example remixing** *(S)* — jump between gallery examples
  without losing your edited buffer, to graft techniques. The docs literally say
  "go break the gallery examples"; make breaking them frictionless.

## 4. Onboarding & legibility (grow the audience without dumbing down)

Per the global instruction that code should be understandable "by a wide
audience at a glance" — extend that to the *tool*, not just the source.

- [ ] **Progressive "reveal the GLSL/JS" pane** *(S)* — the `dump` target already
  exists offline; surface it live and read-only. Nothing teaches "it's just math"
  like watching the lisp become the C.
- [ ] **Hoverable prelude docs** *(S)* — every builtin (`smin`, `palette`,
  `sd-torus`, `space`, `wander`…) with a one-line "what it is" on hover, sourced
  from the same reference tab. Low effort, high discoverability.
- [ ] **A guided "first shader in 5 words" interactive path** *(M)* — the `length
  uvc` → circle → move-the-space progression from *Thinking in shaders*, but
  clickable. The best doc is one you edit.
- [ ] **Shareable permalinks via URL-encoded buffer** *(M)* — no server storage
  (respects the no-persistence stance), just the program in the URL. Sharing a
  living program, not a screenshot, is exactly on-brand.

## 5. Creative kit expansion (more vocabulary, same philosophy)

New builtins are pure words in the prelude — they compound with everything.

- [ ] **More SDF primitives + operators** *(M)* — `sd-line`, `sd-hex`, `sd-round`,
  `op-onion`, `op-twist`. Shapes are "values you do arithmetic on"; give the
  algebra more elements.
- [ ] **Domain-repetition helpers** *(S)* — named `tile`/`mirror`/`polar-repeat`
  over the raw `fract`/`abs` tricks from *Move the space*. Makes "infinity is free"
  a vocabulary item, not a folk technique.
- [ ] **More drone builtins toward physical-modeling** *(M)* — `pluck`, `bow`,
  `resonator` (Karplus-Strong-ish) on Elementary. Extends "a drone is a shader for
  your ears" toward richer timbres while staying continuous (no notes/beats — that
  boundary stays).
- [ ] **Palette gallery / IQ cosine palette picker** *(S)* — visual four-knob
  editor that emits `palette` args back into the buffer. `palette` is already
  framed as a "color-scheme factory"; give people the factory floor.

## 6. Robustness & craft (quietly essential)

- [ ] **Graceful compile-error overlay that keeps the last good frame running**
  *(S)* — never blank the canvas on a typo; the running program should survive an
  in-progress edit. Core to the "time never stops" promise.
- [ ] **Golden-image / golden-graph regression tests** *(M)* — extend the existing
  pure-graph tests to snapshot rendered output and audio graph structure, so the
  compiler can evolve without silent visual/sonic regressions.
- [ ] **Mobile / touch viewer mode** *(M)* — read-and-tweak on a phone (scrub
  numbers, no keyboard needed). Widens the audience; pairs naturally with URL
  permalinks.
- [ ] **WebGPU compute-shader exploration** *(L)* — a research spike: could the
  same DSL target a compute pass for feedback/particle systems *without* breaking
  the pure `image(uv)` contract for the main path? Big, speculative, philosophy-
  testing — hence last.

## 7. Recording & capture (newly in scope)

Previously excluded in favor of "screen-record it yourself." Now a first-class
goal — but built the project's way: capture should record a *program running*,
deterministically, not scrape pixels off a canvas after the fact.

- [ ] **Deterministic offline video render** *(L)* — because a shader is a pure
  function of `(uv, time)`, a frame is reproducible from its timestamp alone.
  Render frame-by-frame at a fixed timestep to an exact framerate (no dropped
  frames, no realtime jitter), muxing via `WebCodecs` → MP4/WebM. This is the
  philosophy paying off: *purity is what makes clean capture possible at all.*
- [ ] **Offline audio bounce of the drone graph** *(M)* — Elementary can render a
  signal graph offline faster than realtime into a buffer; encode to WAV/Opus.
  The graph is already pure and diffable, so a bounce is just "evaluate the same
  graph without the speakers." Pairs with the video render for a synced A/V export.
- [ ] **Synced A/V capture** *(M)* — drive the offline video and offline audio from
  the *same* clock (see the §2 `sync` phasor), so the exported file has frame-exact
  audio-visual alignment — something a live screen-record can never guarantee.
- [x] **Realtime `MediaRecorder` fallback** *(S)* — a one-button "record what I'm
  seeing/hearing right now" for quick shares, capturing canvas + `WebAudio`
  destination. Lower fidelity than the offline path, but zero setup; good default.
  *(Shipped 2026-07-18: `⏺ rec` in the toolbar — canvas `captureStream` muxed
  with a tap on the drone mix, downloads a WebM (mp4 on Safari) on stop.)*
- [ ] **Capture range tied to the timeline model** *(S)* — since animation is "a 4D
  object, a frame is a slice," let the user pick a `time` in/out range and a loop
  length, so exports loop seamlessly (`sin`-based motion already does).

*Scope note:* this reverses the earlier "out of scope" stance. File persistence of
*programs* stays out (still localStorage + URL only); this is about exporting
*renders*, which is a different thing.

## 8. User media (shipped 2026-07-18)

Uploads earned their way in by the three-part test: each adds a *word*
(`sample` / `scrub` / `texture`), each feeds an existing projection rather
than a new system, and the running program morphs live as files arrive.
Files persist in IndexedDB — the browser-local sibling of the localStorage
autosave; nothing leaves the machine.

- [x] **Audio uploads → drone samples** *(M)* — the `samples` tab. Files are
  decoded once (mono, at the engine's rate) into Elementary's virtual file
  system. `(sample "name" rate)` loops one as a signal — rate itself can be a
  signal; `(scrub "name" pos)` parks a tape head at 0..1 for granular drones.
- [x] **Image uploads → shader textures** *(M)* — the `images` tab. Each image
  becomes a repeating, mipmapped `sampler2D`; `(texture "name" uv)` reads it as
  a vec4, and the Shadertoy export maps images onto iChannel1+ (iChannel0
  carries audio there). Names are compile-checked against what's uploaded, with
  the available names listed in the error.
- [ ] **Sample onsets → visuals** *(M)* — a per-sample envelope follower global,
  so an uploaded rhythm can drive the image the way bass/mid/treble already do.

---

### Guiding question for any new idea

Before adding anything, ask the three-part test this project implicitly uses:

1. **Does it add a *word*, not a *mode*?** Vocabulary compounds; panels fragment.
2. **Does it honor both projections, or at least not fight the symmetry?**
3. **Does the running program keep running while you use it?**

If a feature can't answer yes to at least two, it probably belongs in a
different project.
