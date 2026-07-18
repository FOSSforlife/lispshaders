# Summing fields

The single most reliable way to make a flat image interesting: add
another simple field to it. Neither of these examples has a single
complicated part — all the beauty lives in the *interference* between
plain ingredients. When a shader feels boring, the move is almost never
"make this piece fancier"; it's "what second simple thing can I add?"

## plasma — complexity by addition

```clojure
(+ (sin (+ (:x p) time))
   (sin (+ (:y p) (* 0.5 time)))
   (sin (+ (length p) time))
   (sin (+ (* 4.0 (noise (+ p (* 0.2 time)))) time)))
```

Four boring sine fields; one hypnotic result. The first ripples
left-to-right, the second bottom-to-top at half speed, the third radiates
from the center, the fourth wobbles along a noise field. Alone each is a
plain stripe pattern. Summed, their peaks and troughs cross in
ever-shifting ways, and the plane comes alive — the demoscene's oldest
trick, and pure composition.

The lesson generalizes far past sines: interesting = several understandable
things overlaid. Keep each term simple enough that you can predict it
alone, and let the *combination* surprise you.

The final scalar becomes a color the way [everything does](coordinates-and-color.md#cosine-waves-a-color-system-not-a-color):

```clojure
(-> (vec3 hue 0.65 1.0) hsv->rgb)   ; here via HSV instead of a palette
```

**Try:** delete terms one at a time to see what each contributes, then
add a fifth: `(sin (+ (* 3.0 (:x p) (:y p)) time))`.

## caustic pool — accumulation, scaled up with iter

Plasma sums four fields by hand. What if you want to sum *dozens*, each
feeding the next? That's a job for [`iter`](../language/iter.md), the
loop that unrolls into the shader:

```clojure
(iter [i 6] [q p sum 0.0]
  (let [warped (+ q (vec2 (sin (+ (:y q) t))
                          (cos (+ (:x q) t))))]
    [warped
     (+ sum (/ 1.0 (+ 0.1 (abs (sin (* (:x warped) (:y warped) 0.3))))))])
  sum)
```

This carries **two state variables**: `q`, the point as it gets folded
around by `sin`/`cos` a little more each pass, and `sum`, the brightness
accumulating along the way. The `(/ 1.0 (+ 0.1 (abs ...)))` term spikes
wherever the folded coordinate lands near a zero — thin bright creases
that pile up into the shimmering caustics you see on a pool floor.

Two patterns worth naming here:

- **Iterative folding.** Each pass warps space *based on where it already
  landed*. Feeding a transformation its own output is the engine behind
  most organic-looking shaders — see [domain warping](domain-warping.md)
  for the same idea taken further.
- **The reciprocal glow.** `1 / (small + distance)` is huge where distance
  is tiny and fades fast elsewhere — the [go-to glow idiom](idioms.md),
  used anywhere you want light to bloom around a feature.

`iter` with several state variables is a core skill; the [iter
page](../language/iter.md#several-state-variables) walks its rules, and
you'll see its full power again in [Fractals](fractals.md).

**Try:** raise the loop count `6` to `12` for denser caustics (and a
heavier GPU bill); change `0.3` inside the `sin` to restretch the creases.
