# defsound: designing drones

## Anatomy

```clojure
(defsound name body)
```

- **`name`** — how the drones tab refers to this layer.
- **`body`** — one expression describing the signal. A `float` is a mono
  signal; a `vec2` is a stereo `(left, right)` pair. There is no duration
  and no time parameter: a drone runs for as long as the engine does.

The whole language is available inside — `let`, `->>`, `iter`, user
`defn`s — plus the drone builtins below. The shader-only inputs (`uv`,
`mouse`, `resolution`, the audio bands) don't exist here; the one shared
input is `time`, which inside a drone is a _signal_: seconds since the
engine started, rising forever.

Amplitudes are conventionally kept within ±1. You don't have to be
precise about it — every layer sums into a master `tanh` saturator, so an
over-hot mix compresses warmly instead of clipping — but each drone's
overall gain (that final `(* 0.3)`) is your mixing console.

## Notes are keywords

Anywhere a number fits, an Overtone-style note keyword fits: `:a1` _is_
55.0. Drones care about pitch relationships even without melodies, and
`(saw :a1)` says more than `(saw 55.0)`:

```clojure
(def root :a1)
(defsound bed (+ (saw root) (saw (* root 1.5))))   ; root + fifth
```

`(midi->hz m)` is there when you'd rather compute pitches in semitones.

## The raw material: oscillators

```clojure
(osc 110.0)      ; sine — pure, round
(saw :a1)        ; sawtooth — bright, full harmonics (anti-aliased)
(square :a1)     ; hollow, woody odd harmonics
(tri :a2)        ; soft, flute-like
(phasor 0.5)     ; a bare 0..1 ramp — raw material for control shapes
(noise)          ; white noise
(pink)           ; pink noise — noise with the treble tamed, better for air
```

One oscillator is a test tone. The classic drone move is a **detuned
stack** — near-identical frequencies beat against each other at their
difference frequency, which reads as slow internal movement:

```clojure
(+ (saw :a1)
   (saw (* :a1 1.006))          ; ~0.3 Hz of beating
   (* 0.5 (square (* :a1 0.5))))  ; sub-octave underneath
```

## Sculpting: filters and drive

Filters take `(cutoff-hz q signal)` — signal last, so they thread with
`->>`:

```clojure
(->> (saw :a1)
     (lowpass 400.0 1.2))    ; darken; q > 1 adds a resonant peak
     ;; also (highpass ...) and (bandpass ...)
```

A _static_ filter is just EQ. A _moving_ filter is an instrument — sweep
the cutoff with an [lfo or wander](movement.md):

```clojure
(lowpass (+ 300.0 (* 900.0 (lfo 0.05))) 1.4 fat)   ; a 20-second tide
```

`(drive amount sig)` pushes the signal through a tanh curve (0..1):
gentle amounts thicken, high amounts growl. Driving a _filtered_ signal
regenerates harmonics the filter removed — a very analog trick:

```clojure
(->> fat (lowpass 350.0 1.5) (drive 0.4))
```

## Space: echo, pan, and the wash

```clojure
(echo 700.0 0.45 sig)   ; dry + repeats every 700 ms, feeding back at 0.45
(pan 0.3 sig)           ; place a mono signal in the field: 0 left, 1 right
(space 0.4 sig)         ; diffuse into a wide stereo wash, 0 dry .. 1 drowned
```

`echo` at short times (< 50 ms) is a resonator; at long times with high
feedback it smears anything into texture. `space` is four detuned comb
delays split across the channels — the "recorded in a water tank" button.
Both `pan`'s position and `space`'s amount are modulation targets:
`(pan (wander 0.05) sig)` lets a layer drift around the room.

`pan` and `space` return `vec2`, so they usually come last in the
pipeline. Stereo signals pass through filters, `echo`, `drive`, and
`slew` per-channel automatically.

## Building thickness with iter

`iter` unrolls at compile time, so it builds graph structure — a loop
over partials becomes a bank of oscillators. Use `hash1` on the loop
index to give each voice its own stable random character:

```clojure
(defsound chorus
  (iter [i 8] [sum 0.0]
    (let [n (+ 1.0 i)
          detune (+ 1.0 (* 0.004 (- (hash1 n) 0.5)))]
      (+ sum (* 0.08 (saw (* :a2 detune)))))))
```

Eight saws, each a hair off pitch — a string section instead of a synth.
The same shape with `(osc (* f n))` and `1/n` gains is additive synthesis;
with per-voice `wander`-driven gains it's an evolving cloud.

!!! warning "hashes want plain numbers"
`(hash1 x)` picks a fixed random value while the graph is built, so it
needs a compile-time number (like the iter index) — a signal has no
single value to hash. Feeding it `(lfo ...)` is an error; for random
_movement_, use [`wander`](movement.md) instead.

## Conditionals are crossfades

`if` and the comparisons compile to signal-rate gates, not branches —
`(if (< (lfo 0.05) 0.5) a b)` switches between two signals twenty seconds
apart. Hard switches click; wrap the condition through `slew`, or better,
use `mix` with a modulator directly:

```clojure
(mix dark bright (lfo 0.02))   ; a 50-second crossfade, forever
```

## A worked layer

Everything above, in the order it usually happens — generate, sculpt,
set a level, place in space:

```clojure
(defsound bed
  (let [f :a1
        fat (+ (saw f)
               (saw (* f 1.006))
               (* 0.5 (square (* f 0.5))))]
    (->> fat
         (lowpass (+ 220.0 (* 500.0 (lfo 0.05))) 1.4)
         (drive 0.25)
         (* 0.3)
         (space 0.35))))
```

## The drones tab

Every `defsound` gets a card: its name, mono/stereo, a **live level
meter**, and **mute / solo**. Muting fades the layer over ~80 ms (no
clicks) and it keeps running silently underneath, so unmuting drops you
back into the exact same place. Solo is how you put one layer under the
microscope while you work on it.

The **◉ start drone** button in the toolbar boots the engine (browsers
require a user gesture for audio) and fades the master in over about a
second. Stopping suspends the engine in place — like pausing a shader —
and starting again resumes where it left off.
