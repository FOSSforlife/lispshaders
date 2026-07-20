# For Max/MSP users

If you've patched in Max/MSP, you already own the hard-won intuition a
drone needs: signal flow, filters as instruments, control-rate versus
audio-rate. This page is a translation layer — it names the Max concept
you know and points at where it lives here, and it's honest about the
two or three places the models genuinely diverge.

The single sentence to start from:

> A drone is a `gen~` patch that never stops. Everything inside it is a
> signal, you write it as an expression instead of wiring boxes, and the
> engine keeps it playing while you edit.

## The one big shift: everything is signal rate

Max has a hard wall down the middle. Messages, number boxes, `metro`,
`line` — the **control** world, event-driven, patch cords that are thin.
Then the `~` objects — `cycle~`, `*~`, `lores~` — the **signal** world,
44 100 samples a second, fat patch cords. Crossing the wall takes special
objects (`sig~`, `snapshot~`, `line~`).

Inside a drone **there is no wall**. Every value is a signal, and
[every parameter of every builtin is a signal too](defsound.md) — so the
`sig~`/`line~` ceremony simply vanishes. You never convert a number into a
signal to feed it somewhere; you just write the number, or the moving
expression, in the slot:

```clojure
(lowpass (+ 300.0 (* 900.0 (lfo 0.05))) 1.4 sig)
```

In Max that cutoff would be a `line~` or a scaled `phasor~` patched into a
filter's right inlet. Here it's arithmetic in the argument. This is the
same freedom `gen~` gives you — and it's why a drone _feels_ like `gen~`
more than like a top-level MSP patch.

!!! note "The one exception, and it's the mirror of Max's rule"
`(hash1 x)` needs a plain compile-time number, not a signal — it picks a
fixed random value _while the graph is built_. It's the one place the old
control/signal instinct still applies: a hash wants a "message," not a
"signal." For random _movement_, reach for [`wander`](movement.md)
instead.

## Object-for-object

Most of what you'd drop onto a patcher canvas has a direct name here.
Signal last in the filters and effects, so they thread with `->>` the way
signal flows left-to-right down a patch cord.

| Max/MSP                         | lispshaders                    | Note                                             |
| ------------------------------- | ------------------------------ | ------------------------------------------------ |
| `cycle~`                        | `(osc f)`                      | sine                                             |
| `saw~` / `rect~` / `tri~`       | `(saw f)` / `(square f)` / `(tri f)` | band-limited, like the BLEP `~` objects    |
| `phasor~`                       | `(phasor rate)`                | bare 0..1 ramp                                   |
| `noise~` / `pink~`              | `(noise)` / `(pink)`           |                                                  |
| `*~` `+~` `-~` `/~`             | `*` `+` `-` `/`                | plain math — no `~` suffix, everything's signal  |
| `lores~` / `svf~` / `reson~`    | `(lowpass c q s)` etc.         | also `highpass`, `bandpass`; `q > 1` resonates   |
| `overdrive~` / `clip~`          | `(drive amt s)`                | `tanh` shaper; see [Aggression](aggression.md)   |
| `tapin~` / `tapout~` (feedback) | `(echo ms fb s)`               | delay + feedback in one                          |
| `pan2~` / `gain~`               | `(pan p s)` / `(* g s)`        | `pan` returns a stereo `vec2`                    |
| `yafr2` / reverb abstractions   | `(space amt s)`                | four detuned combs, the "water tank"             |
| `mtof` / `ftom`                 | `(midi->hz m)` / `(hz->midi f)` | or just write the note keyword — `:a1` _is_ 55.0 |
| `slide~` / `line~` (smoothing)  | `(slew sec s)`                 | low-pass for control signals; glide/portamento   |
| `cycle~ 0.1` as an LFO          | `(lfo rate)`                   | unipolar 0..1, ready to scale                    |
| `scale`                         | `(scale x lo hi lo' hi')`      | same argument order; also `bi->uni` / `uni->bi`  |
| `wrap` / `fold` / `quant` (gen~) | `(wrap x lo hi)` etc.         | same names, same argument order                  |
| `rand~` / `drunk` smoothed      | `(wander rate)`               | smooth random drift, 0..1                         |
| `poly~` with N voices           | `iter`                         | unrolls a voice bank at compile time             |
| `scope~` / `meter~`             | the drones-tab level meter     | one per `defsound`, automatic                    |
| `ezdac~` / "start DSP"          | the **◉ start drone** button   | boots the engine, fades the master in            |

## Patch cords are expressions; recompile is a live diff

A Max patch _is_ a graph — objects are nodes, patch cords are edges. A
drone is the same graph written as a nested expression: `(lowpass c q
(saw f))` is a `saw~` cord running into a `lores~`. `->>` just lets you
draw that cord top-to-bottom instead of inside-out:

```clojure
(->> (saw :a1)                 ; saw~
     (lowpass 400.0 1.2)       ;   → lores~
     (drive 0.25)              ;   → overdrive~
     (* 0.3)                   ;   → gain~
     (space 0.35))             ;   → reverb → ezdac~
```

The workflow feels like Max's too, and better in one spot. In Max you
edit the patch while DSP is on and it quietly recompiles the affected
chain. Here, re-rendering a drone
[**diffs the new graph against the one already playing**](index.md):
change a `0.3` to `0.4` and the gain _moves_ to the new value; change a
cutoff and it _sweeps_ there. There's no dropout, no re-trigger — the
sound morphs in place, closer to dragging a number box than to reloading
a patch.

## No transport, no scheduler — and that's the point

There is no `metro`, no `transport`, no `sync~`, no timeline. A drone
doesn't sequence events; it _is_ a continuous sound, the way a fragment
shader _is_ an image rather than a sequence of draw calls. If your Max
instinct is to reach for a `metro` driving a `counter`, stop — the drone
answer is a **slow modulator**, not a clock:

- Repeating gesture → `(lfo rate)` at a musical rate (`0.05` is a
  20-second breath).
- Never-repeating life → `(wander rate)`.
- One-way development ("the piece has movements") → arithmetic on
  `time`, which inside a drone is _seconds since the engine started_, a
  signal that only rises.

The whole craft of making a static patch breathe lives in
[Movement & modulation](movement.md), and it's the closest thing to
"composing" you'll do — pick rates that share no common period and the
patch takes minutes to repeat itself, the drone version of polyrhythm.

## Voices without poly~

`poly~` exists in Max because instantiating and freeing voices at runtime
is awkward. A drone never allocates at runtime — the graph is fixed once
built — so a voice bank is just a compile-time loop with `iter`, which
unrolls into N oscillators:

```clojure
(iter [i 8] [sum 0.0]
  (let [detune (+ 1.0 (* 0.004 (- (hash1 (+ 1.0 i)) 0.5)))]
    (+ sum (* 0.08 (saw (* :a2 detune))))))
```

`hash1` on the loop index is your per-voice `random`-seeded character,
fixed at build time — the deterministic cousin of seeding each `poly~`
instance. There's no note-stealing or DSP-on-per-voice to manage: all
eight run always, which is exactly what a drone wants.

## `gen~`, specifically

If you live in `gen~`, you'll feel most at home fastest — a drone _is_ a
codegen target, and the mental model transfers almost whole:

| gen~                              | lispshaders drone                                  |
| --------------------------------- | -------------------------------------------------- |
| Sample-accurate expressions       | the whole language is signal-rate expression       |
| `Param` / `in`                    | `def`s and let-bound values, all signals           |
| `history` (one-sample feedback)   | built into `echo`; hand-rolled feedback isn't exposed |
| `cycle` / `phasor` (no `~`)       | `osc` / `phasor`                                    |
| Writing DSP as math, not boxes    | exactly the same, in Clojure                        |

The gap: `gen~` lets you write your own single-sample feedback with
`history`, and drones don't expose a raw one-sample delay — feedback
comes packaged in `echo` (and the resonance inside the filters). For the
FM, distortion, and wavefolding you'd normally hand-build in `gen~`, see
[Aggression: FM & distortion](aggression.md) — it's all there, built the
same way, out of the fact that a frequency is just a signal.

## Metering and the DSP toggle

Every `defsound` is a named layer and gets its own card in the **drones**
tab — a live level meter (your `meter~`), plus **mute / solo**. Solo is
how you put one layer under the `scope~` while you shape it; muting fades
over ~80 ms so nothing clicks, and the layer keeps running silently, so
unmuting drops you back in place.

The **◉ start drone** button is `ezdac~` / "turn on DSP" — browsers
require a user gesture before audio, so the engine boots on that click
and fades the master in over about a second. Stopping _suspends_ the
engine in place, like pausing rather than clearing DSP; starting again
resumes where it left off, `time` and all.

## What genuinely has no equivalent

A few Max staples don't map, mostly because a drone deliberately isn't an
event system:

- **MIDI / live input** — no `notein`, no `ctlin`, no live controller
  binding. A drone's "controllers" are its internal modulators; the
  outside inputs are the shader's, not the sound's.
- **Message boxes, `pack`, `route`, `sel`** — there are no messages to
  pack or route. Structured control is just Clojure data and functions at
  build time.
- **Presets / `pattr` / snapshots** — a drone's "state" is its source
  text; you version the expression, not a preset blob.
- **`buffer~` / sample playback** — the engine is synthesis-only; there's
  no sample loading.

## The payoff Max doesn't have

The reason to make the jump: the same expression language compiles to
_two_ targets. A `def` is shared by the drone engine and the fragment
shader, so one constant — or one slow modulator — can drive sound and
picture at once, and the whole mix feeds the shader as `bass` `mid`
`treble` `level`. It's `jit.gl` reacting to `meter~`, except the two
sides are written in one language and can literally share a variable. See
[Audio-reactive shaders](audio-reactive.md) — that shared structure is
the thing a Max+Jitter patch has to bolt together by hand, and here it's
the default.
