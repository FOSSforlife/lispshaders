# Movement & modulation

A drone earns its keep by _changing slowly_. This page is the toolkit for
making a static patch breathe — it's the audio counterpart of animating a
shader with `time`.

## The two workhorses

```clojure
(lfo rate)      ; a sine breath, 0..1, `rate` cycles per second
(wander rate)   ; a smooth random drift, 0..1, changing direction ~rate times/sec
```

Both are _unipolar_ (0..1) on purpose: they're ready to scale and offset
into any parameter range without sign gymnastics:

```clojure
(+ 300.0 (* 900.0 (lfo 0.05)))    ; a cutoff tide: 300..1200 Hz over 20 s
(* 0.1 (wander 0.07))             ; a gain that meanders below 0.1
(wander 0.05)                     ; a pan position, drifting around the room
```

`lfo` is periodic — you'll feel its cycle. `wander` never repeats — it's
what makes a layer sound _alive_ rather than _on a timer_. Most rich
patches use both: an `lfo` for the big deliberate gesture, `wander` for
the human unsteadiness on top.

## Pick rates like a composer

The interesting range for drones is far below what synth LFOs usually do:

| Rate   | Period | Feels like                                    |
| ------ | ------ | --------------------------------------------- |
| `2.0`  | 0.5 s  | vibrato / tremolo                             |
| `0.2`  | 5 s    | a slow pulse you can track                    |
| `0.05` | 20 s   | a tide — you notice the change, not the cycle |
| `0.01` | ~2 min | the piece has _movements_                     |

Layer rates that share no common period (`0.05` and `0.07`, not `0.05`
and `0.1`) and the combined patch takes minutes to repeat a state — the
drone equivalent of polyrhythm.

## Detune is modulation too

The humble `(+ (saw f) (saw (* f 1.006)))` stack is a modulator in
disguise: two tones 0.6% apart beat at their difference frequency. At
`:a1` that's ~0.33 Hz — a free, perfectly smooth amplitude wobble that no
LFO has to provide. Tune the _musical speed_ of a drone by choosing
detune amounts, then let `lfo`/`wander` handle the slower gestures.

## Smoothing and glide: slew

`(slew seconds sig)` eases a signal toward its input over roughly
`seconds` — a low-pass for _control_ signals:

```clojure
(slew 2.0 (if (< (wander 0.03) 0.5) :a1 :c2))   ; a bass that *glides* between roots
```

The `if` produces a hard-switching frequency signal; `slew` turns the
steps into portamento. The same trick softens anything that jumps.

## time, for the long arc

Inside a drone, `time` is seconds since the engine started, as a signal.
Trig over `time` is another modulator (`(sin (* 0.02 time))` is a
bipolar 5-minute cycle), but its distinct power is the _one-way_ gesture —
things that develop and never come back:

```clojure
(* (min 1.0 (* 0.02 time)) layer)     ; this layer fades in over 50 seconds
(lowpass (min 4000.0 (+ 200.0 (* 8.0 time))) 1.0 sig)  ; brightening for ~8 minutes
```

Stopping the engine freezes `time`; starting again resumes it — the
piece continues rather than restarting.

## Modulating modulators

Every builtin parameter is a signal, including the modulators' own inputs
— so depth and rate can themselves move:

```clojure
;; vibrato that fades in, like a string player
(osc (* :a3 (+ 1.0 (* (* 0.005 (min 1.0 (* 0.1 time)))
                      (- (* 2.0 (lfo 5.0)) 1.0)))))

;; a filter whose *sweep speed* wanders
(lowpass (+ 400.0 (* 800.0 (lfo (+ 0.03 (* 0.1 (wander 0.01)))))) 1.2 sig)
```

Two levels of modulation is usually plenty; three is how you lose an
afternoon (pleasantly).

## Movement in the mix

Modulate _between_ layers, not just inside them. Give each `defsound` a
`wander`-driven gain and the soundscape slowly re-balances itself, layers
surfacing and submerging:

```clojure
(defsound shimmer
  (* (wander 0.04)                       ; this whole layer comes and goes
     (iter [i 6] [sum 0.0]
       (+ sum (* 0.1 (osc (* :a3 (+ 1.0 i))))))))
```

And remember the shader is listening: a slow filter sweep shows up in
`treble`, a swelling bed in `bass` — movement you compose here plays on
screen too. That's the subject of
[Audio-reactive shaders](audio-reactive.md).
