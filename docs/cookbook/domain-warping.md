# Domain warping

The most painterly image in the gallery comes from the most functional
idea: **use noise to bend the coordinates you feed to more noise.** Once
that clicks, "organic texture" stops being mysterious — smoke, marble,
clouds, and fire are all the same recipe with different numbers.

## domain-warped fbm — feed a function its own output

```clojure
(let [q (vec2 (fbm (+ p (* 0.15 time)))
              (fbm (+ p (vec2 5.2 1.3))))
      r (vec2 (fbm (+ p (* 4.0 q) (vec2 1.7 9.2) (* 0.1 time)))
              (fbm (+ p (* 4.0 q) (vec2 8.3 2.8))))
      v (fbm (+ p (* 4.0 r)))]
  ...)
```

Read it as three layers of the same move. `fbm` is *fractal noise* — five
octaves of smooth randomness stacked into instant clouds. Here `q` is a
noise-valued offset; `r` warps space by `q` and samples noise again; `v`
warps by `r` once more. Nothing is drawn. Space itself is folded until
flat noise looks like flowing marble.

Why the `(vec2 5.2 1.3)` and `(vec2 1.7 9.2)` sprinkled around? Sampling
`fbm` at far-apart locations gives you *independent* noise values — a
cheap way to get several unrelated random fields from one function. The
exact numbers don't matter, only that they're different and not near zero.

This is [move the space, not the shape](../language/thinking.md#move-the-space-not-the-shape)
at full power, and the same self-feeding structure you met in the
[caustic pool](summing-fields.md#caustic-pool-accumulation-scaled-up-with-iter) —
here nested by hand instead of looped, so you can see each stage.

**Try:** change both `4.0` multipliers. Low values (≈1) barely swirl;
high values (≈10) shred space into froth. That one number is the entire
mood of the piece.

## Where it goes next

Domain warping is a foundation, not a destination. Three cookbook pages
build directly on it:

- [Glowing fields](glowing-fields.md) slices a warped noise field into
  bands and contour lines of light.
- [Faking light](faking-light.md) treats the field as a 3D surface and
  lights it — the "liquid chrome" look is *this exact code* plus a normal.
- Every [tunnel](tunnels.md) textures its walls with noise sampled in
  transformed coordinates.

`fbm`, `noise`, and friends all live in the [builtin
reference](../language/reference.md) if you want to know exactly what each
returns.
