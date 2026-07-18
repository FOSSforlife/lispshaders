# Audio-reactive shaders

The whole soundscape — every drone layer, after the master saturator —
flows through one analyser. Every frame, its FFT is condensed into a
handful of values your shader reads like any other input. No wiring, no
setup: mention `bass` and the shader hears.

## The inputs

| Symbol   | Type    | What it measures                                      |
| -------- | ------- | ----------------------------------------------------- |
| `bass`   | `float` | Low band (the bed, the sub), 0..1                     |
| `mid`    | `float` | Mid band (body, thickness), 0..1                      |
| `treble` | `float` | High band (air, shimmer, filter sweeps opening), 0..1 |
| `level`  | `float` | Mean of the three — overall loudness                  |
| `audio`  | `vec4`  | `(bass, mid, treble, level)` in one value             |

The energies are _analysis_ — they respond to what things actually sound
like, with fast-attack / slow-release smoothing baked in so visuals pulse
rather than flicker at FFT rate. Because drones move slowly, these values
move slowly too: think of them less as triggers and more as _slow inputs_,
like a `mouse` the music is dragging.

## Recipes

**Breathe with the bed** — feed `bass` into any size:

```clojure
(let [ring (- (length uvc) (+ 0.25 (* 0.9 bass)))]
  (/ 0.05 (+ (abs ring) 0.02)))          ; a ring that swells with the low end
```

**Hear the filter open** — a sweep composed in the drone shows up as
`treble`; tint or sharpen with it:

```clojure
(palette (+ v (* 0.4 treble) (* 0.05 time))
         (vec3 0.5) (vec3 0.5) (vec3 1.0) (vec3 0.0 0.33 0.67))
```

**Let loudness set the pace** — scale a motion rate by `level` so the
image drifts faster when the mix thickens:

```clojure
(fbm (+ p (* (+ 0.02 (* 0.1 level)) time)))
```

**Bands to bars** — the built-in fallback visualizer (what you see when a
program has drones but no `image`) is just this:

```clojure
(defn image [uv]
  (let [band (cond (< (:x uv) 0.333) bass
                   (< (:x uv) 0.666) mid
                   :else treble)
        lit (- 1.0 (smoothstep (- band 0.02) (+ band 0.02) (:y uv)))]
    (vec3 lit)))
```

All of these degrade gracefully: with the engine stopped the energies are
0, so an audio-reactive shader is still a working shader — it just holds
still until the drone starts.

## Closing the loop

The deepest audiovisual patches share _structure_, not just analysis.
`def`s are compiled into both targets, so one constant — or one slow
expression — can drive sound and image together:

```clojure
(def root :a1)
(defsound bed (saw root))
(defn image [uv] (vec3 (fract (* (length uvc) (/ root 55.0)))))
```

Analysis makes visuals _react_; shared defs make the two sides _rhyme_.
Use both.

## How it maps to the Shadertoy export

Shadertoy has no lispshaders drone engine, but it _does_ have
audio-texture channels. The [Shadertoy export](../export.md) re-spells
the audio inputs as `iChannel0` reads:

- `bass` / `mid` / `treble` become `texture(iChannel0, ...)` samples at
  low / mid / high positions along Shadertoy's spectrum row.
- `level` is recomputed as their mean; `audio` is reassembled from them.

To make the exported shader hear, add **SoundCloud, a music file, or the
microphone to iChannel0** in the Shadertoy editor.

## The plumbing, briefly

For the curious (details in [Under the hood](../internals.md)): the
drone mix feeds a 128-bin FFT analyser; bins are grouped into the three
bands, their dB values mapped into 0..1, smoothed with a decaying-max
envelope, and handed to the render loop, which uploads them as the
`u_audio` vec4 uniform each frame. One analysis, shared by every pixel.
