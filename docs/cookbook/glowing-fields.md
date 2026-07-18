# Glowing fields

You have a smooth noise field (from [`fbm`](domain-warping.md)). It's a
grey blur. How do you turn it into something that reads as *light*? Both
examples here answer the same way: don't draw shapes into the field —
**find the interesting values in it and make those glow.** This is a
mindset worth keeping: a field is a landscape, and you get to choose which
altitudes light up.

## glowing contours — slice a field into iso-lines

```clojure
(let [h (fbm (* p 1.5))
      lines (abs (sin (* 24.0 h)))
      glow (pow (- 1.0 lines) 4.0)
      col (palette (+ h (* 0.05 time)) ...)]
  (+ (* col 0.15) (* col glow 1.5)))
```

`h` is a height field — think of a contour map. The trick is
`(sin (* 24.0 h))`: as `h` rises smoothly, the sine sweeps up and down 24
times, hitting zero at evenly spaced heights. `(abs (sin ...))` is small
right at those crossings and large between them, so `(- 1.0 lines)` is
bright *on the contour lines* and dark elsewhere. The `pow ... 4.0`
sharpens those bands into thin glowing threads.

Two reusable ideas:

- **Periodic-of-a-value makes stripes.** `sin`, `fract`, or `mod` of any
  smooth quantity carves it into repeating bands — the foundation of
  contour lines, rings, and layers everywhere.
- **`pow` is a contrast knob.** Raising a `0..1` value to a power > 1
  crushes the low end toward black and leaves peaks bright — instant
  sharpening, no branching.

Notice the final line: a dim base wash `(* col 0.15)` plus the bright
lines on top. Adding a faint base keeps the dark regions from reading as
dead black — a small habit that makes glow look intentional.

**Try:** change `24.0` for more or fewer contour rings; drop the `pow`
exponent to `2.0` for soft bands instead of hairlines.

## aurora curtains — stack soft bands with iter

Contours light up *level sets* of a field. Aurora instead stacks a few
**warped, feathered bands** using [`iter`](../language/iter.md):

```clojure
(iter [i 4] [sum (vec3 0.0)]
  (let [off  (* 0.4 i)
        warp (fbm (+ (* p (+ 1.0 (* 0.3 i))) (vec2 t (* 0.5 t)) (vec2 off 0.0)))
        band (exp (* -4.0 (abs (+ (:y p) (- warp 0.5) (- off 0.6)))))
        tint (palette (+ 0.35 (* 0.15 warp) (* 0.1 i)) ...)]
    (+ sum (* tint band))))
```

Each pass draws one curtain and adds its color into `sum`. The heart of
it is `(exp (* -4.0 (abs ...)))` — the **exponential falloff idiom**. The
`abs` measures distance from a horizontal line; `exp` of its negative is a
bright ridge that fades smoothly to both sides, exactly the feathered edge
real aurora have. Each curtain's line is pushed around by its own `fbm`
warp and offset up the screen by `off`, so the four layers drift
independently and overlap into depth.

Here `iter` is used as a **layer accumulator**: same body, four times,
summing colors. That's the other big use of `iter` besides the folding
you saw in [caustics](summing-fields.md#caustic-pool-accumulation-scaled-up-with-iter) —
and the loop index `i` (which auto-promotes from int to float when it
meets `0.4`) gives each layer its own parameters for free.

**Try:** raise the curtain count `4`; sharpen the falloff by changing
`-4.0` to `-8.0` for thinner, brighter ribbons; feed `treble` into the
warp to make it [react to a drone](../sound/audio-reactive.md).
