# Drones: the big idea

The audio system rests on one symmetry:

> A fragment shader is a **pure expression of space** — you describe what
> every pixel _is_, and the GPU keeps it true frame after frame. A drone
> is a **pure expression of sound** — you describe what the signal _is_,
> and the audio engine keeps it true sample after sample.

There are no notes, no beats, no timeline. A lispshaders program doesn't
_play_ music — it _is_ a sound, continuously, the way a shader _is_ an
image. You sculpt that sound the same way you sculpt pixels: edit an
expression, and the running result changes under your hands.

```clojure
(defsound hum
  (->> (+ (saw :a1) (saw 55.4))                     ; two saws beating
       (lowpass (+ 300.0 (* 700.0 (lfo 0.1))) 1.2)  ; a slowly opening filter
       (* 0.3)
       (space 0.4)))                                ; diffused into stereo
```

Behind the scenes each `defsound` compiles to an
[Elementary Audio](https://www.elementary.audio/) signal graph — the same
Clojure dialect, the same compiler, a different target. And Elementary
gives drones the property that makes shader editing feel alive:
re-rendering a graph **diffs it against the one already playing**. Change
`0.3` to `0.4` and the gain moves; change a filter cutoff and it sweeps.
The sound morphs in place. It never stops, clicks, or restarts.

## The design, in three parts

### 1. Drones are layers

Every `defsound` in your program is one layer of a single soundscape —
they all mix into one output. Big drones aren't written as one huge
expression; they're grown as named layers with separate concerns:

```clojure
(defsound bed ...)       ; the low fundament
(defsound shimmer ...)   ; partials floating above it
(defsound air ...)       ; filtered-noise texture
```

Each layer gets a card in the **drones** tab with a live level meter and
**mute / solo** buttons, so you can audition a layer in isolation while
you shape it — the audio equivalent of temporarily returning an
intermediate value from `image` to _see_ it.

### 2. Movement comes from modulation

A drone with nothing moving is a test tone. The drone toolkit is built
around slow modulators — `(lfo rate)` breathes sinusoidally, `(wander
rate)` drifts at random — that you patch into any parameter: a filter
cutoff, a detune amount, a pan position, an echo mix. That's the craft,
and it has its own page: [Movement & modulation](movement.md).

### 3. Audio drives the shader

The whole mix flows through one analyser, and your shader reads the
result live as `bass` `mid` `treble` `level` — so when the filter opens,
the visuals feel it. Details in
[Audio-reactive shaders](audio-reactive.md).

## Why drones?

Because they're the honest audio analog of a shader. A sequencer bolted
onto a shader sandbox is two instruments taped together: one edited as a
timeline, one edited as an expression. A drone _is_ an expression. The
skills transfer directly — an LFO is `time`-based animation, additive
partials are `fbm` octaves, a filter sweep is a moving `smoothstep` — and
so does the workflow: tweak, listen, tweak again, never restart.

## Recording

There's no export button, by design — the same as for video. When you
make something worth keeping, screen-record it (add a system-audio
loopback device if your OS doesn't capture app audio natively). What you
keep is a _performance_ of the patch, which suits what a drone is.
