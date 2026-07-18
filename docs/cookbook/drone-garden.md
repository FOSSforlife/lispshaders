# Drone garden

The capstone. This program does something the others don't: it makes
_sound_ as well as an image, and — the real lesson — it uses the **same
ideas in both**. A drone is [a shader for your ears](../sound/index.md):
one signal graph, evaluated continuously, exactly as the image is one
function evaluated per pixel.

## Two projections of one mindset

Three `defsound` layers build the sound, and the shader listens to them.
Each layer demonstrates one drone move ([the drones guide](../sound/defsound.md)
covers them properly):

- **`bed`** — a detuned stack of saws under a tide-swept filter. The
  filter cutoff rides an `(lfo 0.05)`, breathing open and closed.
- **`shimmer`** — an [`iter`](../language/iter.md)-unrolled bank of
  partials, each with its own `hash`-picked detune and slowly wandering
  loudness. The _same `iter`-as-accumulator_ pattern from
  [aurora curtains](glowing-fields.md#aurora-curtains-stack-soft-bands-with-iter),
  here stacking sine tones instead of light bands.
- **`air`** — filtered noise drifting across the stereo field.

Then the audio reaches the visuals through four numbers — `bass`, `mid`,
`treble`, `level`:

```clojure
(* (+ 2.0 (* 3.0 bass)) q)     ; energy -> warp   (the nebula swells on the beat)
(* 0.3 treble)                 ; energy -> color  (the filter opening tints it)
```

## The rhyme is the point

Look at how the _same shapes_ appear in both domains. The drone's
`(lfo 0.05)` filter tide and the shader's `(* 0.03 time)` texture drift are
the identical slow-modulation idea — one measured in Hz, one in coordinate
space. [Movement in a drone](../sound/movement.md) and movement in an image
are the same `sin`-of-a-slow-thing you've used all through this cookbook;
only the output differs.

That's the whole design of the audio system: you don't learn a second
language for sound, you [point the language you already know at your
speakers](../sound/index.md). And you close the loop by making the image
_react_ to it — the full recipe is on the [audio-reactive
shaders](../sound/audio-reactive.md) page.

**Try:** solo each layer in the **drones** tab and tweak it in isolation;
change `bed`'s detune `1.006` to `1.02` for faster beating; give `shimmer`
a minor third with `(* :c4 n ...)`; route `mid` somewhere visible in the
image.
