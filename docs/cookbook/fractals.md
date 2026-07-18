# Fractals

The deepest objects in the gallery have the shortest definitions. Infinite
filigree at every zoom, none of it "drawn" — all of it the consequence of
one tiny rule repeated. When you want intricate detail, the shader lesson
is never "add detail"; it's **find a rule whose repetition generates it.**
Both examples are that lesson, and both are a masterclass in
[`iter`](../language/iter.md).

## mandelbrot — infinite detail from a two-line rule

```clojure
(iter [i 96] [z (vec2 0.0) n 0.0]
  (let [z2 (vec2 (- (* (:x z) (:x z)) (* (:y z) (:y z)))
                 (* 2.0 (:x z) (:y z)))       ; complex square of z
        escaped (> (dot z z) 4.0)]
    [(if escaped z (+ z2 c))                  ; z <- z^2 + c, until it escapes
     (if escaped n (+ n 1.0))])               ; n counts steps before escape
  n)
```

The rule is *square `z`, add `c`, repeat; color by how long that stays
bounded*. Points whose orbit never runs off to infinity are inside the
set; the rest are colored by `n`, how many steps they lasted. That single
recurrence generates endless self-similar structure at every scale.

Technique-wise this is `iter` in full: **two state variables** (`z` and
`n`) updated in parallel, a `let` inside the body naming the intermediate
`z2`, and a trailing `n` as the result. The `if escaped` guards
[freeze each value once it's done](../language/flow.md#choosing-without-branching)
rather than letting it run off. If you can read this example, you've
mastered `iter` — the [iter page](../language/iter.md#several-state-variables)
has the formal rules.

**Try:** the zoom center `(vec2 -0.745 0.186)` — nudge the third decimal
and a different arm of the set unfolds. Raise `96` for deeper detail at
high zoom.

## electric julia — trap the orbit instead of counting it

The Mandelbrot colors by *how long* an orbit survives. The Julia set here
colors by *how close* it ever comes to the origin — an **orbit trap** —
and the result is glowing neon filaments instead of banded contours:

```clojure
(let [c (vec2 (* 0.7 (sin (* 0.3 time)))     ; the seed circles slowly...
              (* 0.7 (cos (* 0.23 time))))]  ;   so the whole fractal breathes
  (iter [i 48] [z z0 m 1000.0]
    (let [z2 (vec2 (- (* (:x z) (:x z)) (* (:y z) (:y z)))
                   (* 2.0 (:x z) (:y z)))
          nz (+ z2 c)]
      [nz (min m (length nz))])              ; m = closest the orbit ever got
    m))
```

Same complex-squaring engine, two changes that teach a lot:

- **`c` is animated, not fixed.** In the Mandelbrot, `c` is the pixel's
  position; in a Julia set, `c` is a single constant shared by every
  pixel. Move that constant on a slow circle and the entire fractal
  morphs and rewires — a whole gallery of shapes from one number path.
- **The trap.** The second state variable `m` keeps a running `min` of
  how near `z` passes the origin. That's a different question than "did it
  escape?", and it lights up the delicate threads where orbits graze zero.
  `(exp (* -6.0 trap))` then turns *small distance* into *bright glow* —
  the [exponential falloff idiom](idioms.md) once more.

Swapping "count the steps" for "track the closest approach" is a tiny
edit with a total change in character. That's the fractal mindset in
miniature: the picture lives in the *rule*, so new pictures come from new
rules, not more code.

**Try:** change the `0.7` amplitude of `c` (near the edge of the set the
shapes get wildest); replace `(length nz)` with `(abs (:x nz))` to trap
against a line instead of a point.
