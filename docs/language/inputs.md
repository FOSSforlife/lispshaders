# Inputs & coordinates

These symbols are simply *in scope* everywhere — no imports, no
declarations. They are the shader's senses.

## Coordinates

| Symbol | Type | Meaning |
|---|---|---|
| `uv` | `vec2` | This pixel's position, 0..1 on both axes, origin bottom-left |
| `uvc` | `vec2` | **Centered, aspect-correct** coordinates: y spans −1..1, x spans ±aspect-ratio |
| `resolution` | `vec2` | Canvas size in pixels |

`uv` is what `image` receives as its parameter, and it's right for
gradients and anything screen-aligned. For almost everything else —
circles, tunnels, raymarching — use `uvc`: because it's aspect-correct, a
circle drawn in `uvc` space is actually round, and `(length uvc)` is a
true distance from the center of the screen.

```clojure
(defn image [uv]
  (let [d (length uvc)]                        ; distance from center
    (vec3 (smoothstep 0.51 0.49 d))))          ; a round white disc
```

## Time and mouse

| Symbol | Type | Meaning |
|---|---|---|
| `time` | `float` | Seconds since the shader started |
| `mouse` | `vec2` | Mouse position over the canvas, 0..1 on both axes |

`time` is pausable (**⏸** in the toolbar) and rewindable (**⟲ t=0**)
without recompiling — handy for freezing a frame while you tweak colors.

`mouse` makes sketches instantly interactive; a common move is centering
it and using it as an offset:

```clojure
(let [m (- mouse (vec2 0.5))
      p (- uvc (* m 1.5))]      ; the scene follows the cursor
  ...)
```

## Constants

| Symbol | Value |
|---|---|
| `pi` | 3.14159… |
| `tau` | 6.28318… (a full turn — most oscillation code reads better in tau) |

## Audio inputs

While the drone engine runs, a live FFT analysis of the mix is available
to the shader:

| Symbol | Type | Meaning |
|---|---|---|
| `bass` | `float` | Low-frequency energy, 0..1 |
| `mid` | `float` | Mid-frequency energy, 0..1 |
| `treble` | `float` | High-frequency energy, 0..1 |
| `level` | `float` | Overall energy (mean of the three), 0..1 |
| `audio` | `vec4` | All four at once: `(bass, mid, treble, level)` |

The band values have fast-attack / slow-release smoothing baked in, so
visuals pulse rather than flicker. Because drones evolve slowly, treat
these as *slow inputs* — a `mouse` the music is dragging:

```clojure
(+ 0.25 (* 0.9 bass))   ; a size that swells with the low end
```

All of these are safely 0 when the engine is stopped, so an
audio-reactive shader still renders (just quietly) in silence. The full
story — including how these map onto the Shadertoy export — is in
[Audio-reactive shaders](../sound/audio-reactive.md).
