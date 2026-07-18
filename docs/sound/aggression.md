# Aggression: FM & distortion

Most of the drone toolkit pulls toward the _pretty_ — detuned saws, opening
filters, a wash of reverb. This page is about the other direction: sounds
with **teeth**. Metallic, buzzing, snarling textures that still earn their
place in a drone by _moving_ — a scream that slowly changes its mind is
music; a scream that holds still is a fire alarm.

Two families of technique get you there, and they stack:

1. **FM** — modulate an oscillator's frequency _at audio rate_ to grow
   dense, inharmonic, bell-and-metal spectra out of two sine waves.
2. **Distortion** — push a signal past what a clean curve can hold, so it
   sprouts new harmonics: warm at the edges, vicious past them.

Neither is a new builtin. FM falls out of the fact that
[every parameter is a signal](movement.md#modulating-modulators) — including
an oscillator's frequency — and distortion is `drive` plus a little math.

## FM: two sines, a whole spectrum

The [`Modulating modulators`](movement.md#modulating-modulators) section
used a slow `lfo` inside a frequency to make vibrato. Speed that modulator
up into the audio range — tens to thousands of Hz — and it stops sounding
like _wobble_ and starts sounding like _timbre_. That's frequency
modulation:

```clojure
(defsound fm
  (let [carrier :a2                      ; the pitch you hear
        ratio   1.0                      ; modulator freq = carrier * ratio
        index   200.0]                   ; modulation depth, in Hz
    (* 0.3
       (osc (+ carrier
               (* index (osc (* carrier ratio))))))))
```

Three knobs, and each one owns a different quality of the sound:

| Knob                      | Controls             | Push it and…                                     |
| ------------------------- | -------------------- | ------------------------------------------------ |
| **carrier**               | perceived pitch      | the note moves                                   |
| **ratio** (mod ÷ carrier) | harmonic _character_ | integers ring musical; irrationals turn metallic |
| **index** (mod depth)     | brightness / density | 0 is a pure sine; high is a wall of partials     |

The **index** is the aggression dial. At `0.0` you have a clean sine; wind
it up and sidebands pile on either side of the carrier, the tone getting
brighter, then buzzier, then harsh. Because index is just a number in an
expression, it's also a [modulation target](movement.md) — which is the
whole game (below).

### Ratio picks the flavour

Integer and simple-fraction ratios keep the sidebands landing on the
harmonic series, so the result is bright but _tonal_ — a reed, a
clav, a brass stab:

```clojure
(osc (+ f (* index (osc (* f 2.0)))))    ; ratio 2 — hollow, clarinet-ish
(osc (+ f (* index (osc (* f 1.5)))))    ; ratio 3:2 — buzzy but still pitched
```

Irrational and awkward ratios throw sidebands _between_ the harmonics, and
the ear reads that inharmonicity as **metal** — bells, gongs, engine
blocks, things being hit:

```clojure
(osc (+ f (* index (osc (* f 2.41)))))   ; clangorous, detuned-bell
(osc (+ f (* index (osc (* f 7.13)))))   ; high, glassy, aggressive
```

For a drone, the sweet spot is a ratio _near_ a simple one but off by a
hair — `1.01`, `2.02` — so the sound keeps a sense of pitch while the
inharmonic partials beat slowly against the harmonic ones. Free movement,
same trick as [detuned saws](defsound.md#the-raw-material-oscillators).

### Stacked operators go further

One modulator gives you sidebands; modulate _the modulator_ and you get
sidebands of sidebands — the dense, evolving spectra that make FM worth
the trouble. Just nest another `osc` in the frequency:

```clojure
(defsound fm2
  (let [f :a2]
    (* 0.25
       (osc (+ f
               (* 300.0
                  (osc (+ (* f 2.0)                 ; first modulator
                          (* 400.0 (osc (* f 3.0))))))))))) ; second, feeding it
```

Two operators is expressive; three is where FM gets its reputation for
being hard to predict. Lean on your ears — nudge a ratio and _listen_,
the way you'd nudge a `smoothstep` edge and look.

## Distortion: harmonics from clipping

Distortion is the blunt instrument, and it pairs with FM because they
roughen different things. FM builds a _rich but clean_ spectrum;
distortion takes whatever you feed it and adds the gritty odd harmonics of
a signal slamming into a ceiling.

### drive — the warm end

You met [`drive`](defsound.md#sculpting-filters-and-drive) already: a
`tanh` curve, gentle at low amounts, growling near `1.0`. The move that
makes it _aggressive_ rather than merely thick is to **hit it hard** —
boost the signal well past ±1 going in, so it spends most of its time on
the flat part of the curve:

```clojure
(->> (saw :a1)
     (* 4.0)          ; slam it into the curve
     (drive 0.9))     ; and squash — a fat, buzzing square-ish snarl
```

Driving a _filtered_ signal and then filtering _again_ is the classic
analog-fuzz chain — distort, tame, repeat:

```clojure
(->> (fm-voice :a1)
     (lowpass 1200.0 1.4)   ; carve a band
     (* 3.0) (drive 0.8)    ; regrow harshness inside it
     (lowpass 3000.0 0.9))  ; and shave the very top so it's not painful
```

### Hard clip and wavefold — the mean end

`drive` is smooth, so it can only get so nasty. For harder edges, build the
shaper yourself out of the math builtins — these all work inside a drone:

```clojure
;; hard clip — a brick wall; buzzy, digital, unforgiving
(defn clip [amt sig]
  (max -1.0 (min 1.0 (* (+ 1.0 (* 20.0 amt)) sig))))

;; wavefold — fold the peaks back instead of flattening them. A `sin`
;; transfer function reflects the signal every time it climbs past a
;; quarter-turn, adding a shifting comb of harmonics a clipper never
;; makes. Push `amt` and the folds multiply. This is the metallic one.
(defn fold [amt sig]
  (sin (* (+ 1.0 (* 6.0 amt)) sig)))
```

A wavefolder is the distortion with the most _timbral travel_ — as you push
`amt`, the harmonics don't just get louder, they reshuffle, which is
exactly the "interesting over time" property we're after. It's the
distortion that rewards a slow modulator most.

## Making aggression move

A harsh sound that never changes is exhausting in about ten seconds. The
craft is to keep the _aggression itself_ in motion — sweep the parameters
that control it with the slow modulators from
[Movement & modulation](movement.md), so the sound prowls between mild and
savage instead of pinning the meters forever.

**Sweep the FM index** so the timbre opens and closes like a filter — the
single highest-value modulation in FM:

```clojure
(defsound growl
  (let [f :a1
        index (+ 20.0 (* 500.0 (lfo 0.03)))]   ; 20..520 Hz over ~30 s
    (* 0.3 (osc (+ f (* index (osc (* f 2.01))))))))
```

**Wander the ratio** by a tiny amount so the metallic character drifts and
never quite repeats — the difference between a _machine_ and a _creature_:

```clojure
(let [ratio (+ 2.0 (* 0.6 (wander 0.05)))]      ; 2.0..2.6, meandering
  (osc (+ f (* 300.0 (osc (* f ratio))))))
```

**Modulate the distortion drive** so the grit breathes in and out:

```clojure
(->> voice (* (+ 1.0 (* 6.0 (lfo 0.07)))) (drive 0.85))
```

**Sweep a bandpass across the noise** and the distortion starts to _talk_ —
a moving formant over a buzzy source reads as a vowel, which is far more
alive than a static blare:

```clojure
(->> raspy
     (bandpass (+ 400.0 (* 2500.0 (lfo 0.04))) 3.0))  ; a slow "aah…ooh"
```

And obey the golden rule of [picking rates](movement.md#pick-rates-like-a-composer):
use several slow modulators at periods that share no common multiple
(`0.03`, `0.05`, `0.07`) so the combined timbre takes minutes to arrive
back where it started.

!!! warning "declick the harsh switches"
Aggressive patches are the _least_ forgiving of hard jumps — a stepped
index or a snapped ratio clicks loudly. Wrap any jumpy control in
[`slew`](movement.md#smoothing-and-glide-slew) so it glides:
`(slew 0.5 (if (< (wander 0.05) 0.5) 2.0 3.5))` morphs the FM ratio
between two metals instead of cutting between them.

## A worked snarl

Everything together: an FM voice with a wandering metallic ratio, a
sweeping index, driven hard, then band-swept so it phrases — and finally
kept in check by the master saturator. It never repeats, and it's always
about to become something.

```clojure
(defn snarl [f]
  (let [ratio (+ 2.0 (* 0.5 (wander 0.04)))       ; drifting metal
        index (+ 30.0 (* 600.0 (lfo 0.035)))]     ; timbre tide
    (osc (+ f (* index (osc (* f ratio)))))))

(defsound lead
  (->> (snarl :a1)
       (* (+ 2.0 (* 4.0 (lfo 0.06))))             ; drive amount, breathing
       (drive 0.85)                               ; snarl → growl
       (bandpass (+ 500.0 (* 2000.0 (lfo 0.05))) 2.5) ; a slow vowel
       (* 0.3)
       (space 0.25)))                             ; a little room, not a bath
```

## It plays on screen, too

Aggression is loud in the analysis. A swept FM index dumps energy into
`treble`; a driven bed swells `bass`; the whole thing pushes `level`. A
harsh drone paired with a shader that _reacts_ to that harshness — edges
sharpening as the index climbs, color tearing on the loud transients — is
some of the most visceral audiovisual work the sandbox can do. Point it at
[Audio-reactive shaders](audio-reactive.md) and let the picture flinch.
