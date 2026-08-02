# Drum sequencer

Everything else in [the sound section](../sound/index.md) makes a _drone_ —
a sound with no events, evaluated forever. A drum machine looks like the
opposite: events, on a grid, on a clock. The lesson of this page is that
it _isn't_ the opposite. A beat is still one continuous signal graph — you
just shape the continuity so it reads as rhythm.

The sound docs are blunt about this: [there is no `metro`, no transport, no
scheduler](../sound/for-maxmsp-users.md#no-transport-no-scheduler-and-thats-the-point),
and that's on purpose. So where does the pulse come from? The same place
every repeat in this cookbook comes from — [`fract`](idioms.md).

## `fract` is the whole clock

Feed rising `time` through `fract` and you get a sawtooth that climbs 0→1
and snaps back, over and over:

```clojure
(def bar-hz (/ bpm 240.0))                    ; a 4-beat bar, in bars/second
(def phase (* 16.0 (fract (* time bar-hz))))  ; 0..16 across the bar
(def idx   (floor phase))                     ; which sixteenth we're on
(def local (fract phase))                     ; 0..1 ramp *inside* that step
```

Look at what that snap gives you for free. If you've patched in Max, your
instinct for a clock is a `phasor~` into an edge detector into a `line~`
envelope — three objects. Here they're **one expression**: the snap-back
in `local` _is_ the edge (the trigger), and the ramp climbing away from it
_is_ the envelope. A percussive shape is just that ramp, inverted and
curved:

```clojure
(defn perc [fall] (pow (- 1.0 local) fall))   ; 1.0 at the step edge, decaying
```

Because `local` resets to 0 at every step boundary, `perc` retriggers on
its own — no gate, no comparison, no held state. The discontinuity does
the work.

## A pattern is a shape, not a list you index

You might reach for an array: `pattern[idx]`. You can't — [`iter`
compiles to a real loop with a runtime index](../language/iter.md), so
there's nothing to index a Clojure vector _with_. That constraint is a
gift: it forces the pattern to be **math**, and math is what runs at
signal rate anyway.

So a drum's pattern is a function — "is step `s` a hit?" — built from a
whole-number equality test:

```clojure
(defn is [a b] (- 1.0 (step 0.5 (abs (- a b)))))   ; 1.0 when a == b
(defn kick? [s] (+ (is s 0) (is s 6) (is s 8) (is s 14)))
```

`(kick? idx)` asks it of the _live playhead_ and returns the trigger; the
[image](coordinates-and-color.md) asks the very same function of each
_drawn cell_ and gets the grid. One source of truth, two readers — the
[image/sound rhyme](drone-garden.md) again, sharper than ever.

Each voice is then just three things multiplied:

```clojure
(defsound kick
  (let [amp (* (kick? idx) (perc 8.0))            ; trigger x envelope
        f   (+ 45.0 (* 80.0 (perc 40.0)))]        ; a fast pitch dive
    (->> (osc f) (* amp) (drive 0.3) (* 0.9))))   ; ...x a little synth
```

Swap `(osc f)` for filtered `(noise)` and you've got a snare or a hat; the
_only_ thing that makes it a different instrument is the sound you gate.
The rhythm engine is identical across all three.

## Bitcrushing: `quant` on both axes

A drum machine wants a crusher, and this page already owns every piece of
one. A bitcrusher is two quantizers on the two axes of a waveform — one
thinning the _values_ a sample may take (bit depth), one thinning the
_moments_ it may change (sample rate) — and
[`quant`](../language/reference.md#range-mapping), the same builtin that
posterizes a color, is both of them.

### Bit depth — crush the values

Snap the signal onto a coarse ladder. `quant` floors, so add half a rung
on the way in and you get rounding instead, which keeps the silence
between hits exactly silent:

```clojure
(defparam bits 5.0 1.0 12.0)              ; a knob, like bpm

(defn crush [depth sig]
  (let [step (/ 2.0 (pow 2.0 depth))]     ; a 2.0-wide range, 2^depth rungs
    (quant (+ sig (* 0.5 step)) step)))   ; +half a rung = round, not floor
```

It's one more link in a voice's chain, and it belongs _after_ the
envelope — crushing the smooth fade is most of the sound:

```clojure
(defsound kick
  (let [amp (* (kick? idx) (perc 8.0))
        f   (+ 45.0 (* 80.0 (perc 40.0)))]
    (->> (osc f) (* amp) (crush bits) (drive 0.3) (* 0.9))))
```

At 8 bits it's a faint grain. By 3 or 4 the decay stops gliding and falls
in terraces, because a quiet signal has fewer rungs left to fall through —
that unevenness _is_ the sound of an old sampler. At 1 bit the kick is a
square wave with a dying duty cycle. The noise voices answer differently:
crushed hiss is still hiss at 8 bits, but by 1 or 2 it has collapsed into
a flat digital rasp — an ugly, excellent hat.

### Sample rate — crush the clock

The other axis looks impossible at first. Reducing a sample rate means
_holding_ a value between updates, and there's no sample-and-hold here —
[no scheduler, no
state](../sound/for-maxmsp-users.md#no-transport-no-scheduler-and-thats-the-point),
only filters and generators. But you don't have to hold the output. Every
voice on this page is a pure function of a ramp, so staircase the ramp and
everything computed from it staircases with it. **Hold the clock, not the
signal:**

```clojure
(def sr 4000.0)                                          ; the crushed rate
(defn oscq [f] (sin (* tau f (quant time (/ 1.0 sr)))))  ; a sine sampled at sr
```

`time` is a signal inside a drone, so `(quant time (/ 1.0 sr))` is a clock
that only advances 4000 times a second, and nothing built on it can change
faster. That's a real decimator, aliasing and all, as `f` climbs toward
`sr/2` — drop `oscq` in where the kick used `osc` and the pitch dive gains
that downsampled edge, harmonics folding downward as the note falls.

Two smaller crushes come free, on values the page already computes:

```clojure
(quant (perc 8.0) 0.125)                ; a decay that falls in 8 terraces
(-> f hz->midi (quant 1.0) midi->hz)    ; a pitch dive that steps in semitones
```

!!! tip "Precision, again"
    `(quant time (/ 1.0 sr))` multiplies `time` by 4000, so it coarsens
    over a long set for exactly the reason `fract time` does — see the
    next section. The stable version quantizes _per cycle_ instead of per
    second: n samples per waveform, the way a chip actually did it.

    ```clojure
    (defn oscn [f n] (sin (* tau (quant (phasor f) (/ 1.0 n)))))
    ```

    `(oscn 55.0 8.0)` is an 8-sample sine — crunchy, pitch-locked, and
    sample-accurate however long you leave it running.

And since the grid reads the same values the voices do, the crusher is
visible as well as audible. Hand the image the same ladder and the lanes
posterize in step with the ear:

```clojure
(quant bright (/ 1.0 (pow 2.0 bits)))   ; terraced glow, same rungs
```

Posterize a color, staircase time, quantize a pitch — one builtin, three
jobs, all on one page.

## Why not just use a phasor? (and why not add a `seq` builtin)

Two honest tradeoffs, since the question comes up.

**`fract time` vs the `phasor` builtin.** Both draw the same sawtooth.
`fract time` wins here because every clock derived from it reads the _same_
rising `time`, so kick, snare, and hats are phase-locked _by construction_
— they physically cannot drift apart. Its one weakness is precision: after
many minutes `(* time bar-hz)` grows large and the ramp coarsens. The
[`phasor`](../sound/defsound.md) builtin wraps internally and stays
sample-accurate forever, but two independent phasors aren't guaranteed to
share phase — the thing a sequencer most needs. For a track you'll leave
running for an hour, derive one `phasor` and build every sub-clock from it
with `floor`/`fract`, exactly as we do here from `fract time`.

**Why there's no `sequencer` primitive.** There easily _could_ be — but it
would earn its keep only by hiding the one idea this page exists to teach.
The moment rhythm is a black box, you stop seeing that it's the same
`fract` you [tile a plane](../language/thinking.md) with, and the sound and
image stop being able to share it. The constraint is the curriculum.

**Try:** turn the `bpm` knob and watch the grid re-time. Edit
`kick?`'s step numbers — `(is s 10)` drops a kick on the offbeat. Add a
fourth lane (an open hat, `(highpass 6000.0 ...)`, a slower `perc`) and a
fourth row to the image. Or make it swing: nudge the odd steps late by
skewing `phase` before you `floor` it. Then sweep `bits` down to 2 while
it plays and watch the lanes terrace along with the tails.
