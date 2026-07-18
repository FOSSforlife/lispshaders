# Coordinates & color

Every shader, no matter how elaborate, is two halves: **turn a pixel's
position into a number**, then **turn that number into a color**. These
two examples are those halves, isolated. Master them and the rest of the
cookbook is just fancier ways to compute the number.

## hello uv — coordinates are the whole input

```clojure
(defn image [uv]
  (vec3 (:x uv)
        (:y uv)
        (+ 0.5 (* 0.5 (sin time)))))
```

The minimal true shader: color *is* position, with time breathing in the
blue channel. The function gets `uv` — this pixel's location, `0..1` on
each axis — and returns its color. That's the entire contract of a
shader ([the full list of inputs](../language/inputs.md) is short).

Two things to internalize before moving on:

- Red increasing rightward and green increasing upward is the classic
  "am I sane?" test pattern — you'll write this gradient forever to debug
  coordinates. When a shader misbehaves, returning `(vec3 (:x uv) (:y uv) 0.0)`
  tells you instantly whether your coordinates point where you think.
- `(+ 0.5 (* 0.5 (sin time)))` is the **range-shift idiom**: `sin` spans
  −1..1 but colors want 0..1, so halve it and slide it up. You will type
  this shape constantly; notice it once and it becomes automatic.

**Try:** swap `uv` for `uvc` and watch the origin jump to the center and
the colors go negative (clamped to black) on the left half. `uvc` is
centered and aspect-corrected — it's what most effects want, and the
difference between the two is worth [feeling directly](../language/inputs.md#coordinates).

## cosine waves — a color system, not a color

Once you can compute a scalar, how do you color it? The naive answer is
to hand-pick RGB values. The shader-artist answer is `palette`:

```clojure
(palette (+ d (* 0.15 w) (* 0.1 time))
         (vec3 0.5) (vec3 0.5)
         (vec3 1.0) (vec3 0.0 0.33 0.67))
```

`palette` isn't a gradient — it's a **gradient factory**. Its first
argument is any scalar you can compute (here a distance `d`, a sine wave
`w`, and drifting `time`); the four `vec3` knobs define an infinite family
of color schemes. Feed a value with interesting structure into a palette
and color design is *done* — you never picked a single color, you picked
a system that assigns colors to numbers.

This is the mindset the whole toolkit rewards: don't make a color, make a
rule that turns your numbers into colors. Almost every example in this
section ends in a `palette` call for exactly this reason.

**Try:** change the last knob to `(vec3 0.0 0.1 0.2)`, then to
`(vec3 0.3 0.2 0.2)`. Each is a whole new palette; none required picking
individual colors. ([IQ's article](https://iquilezles.org/articles/palettes/)
maps the knob space — it's the reference every shader artist keeps open.)

---

Next: [Summing fields →](summing-fields.md) — the first way to make that
input scalar interesting.
