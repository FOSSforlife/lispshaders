# Flow control

## `if` — always three parts

```clojure
(if (< (length p) 0.5)
  (vec3 1.0)      ; then
  (vec3 0.0))     ; else — required
```

Two rules, both consequences of running on a GPU:

1. **`else` is mandatory.** A shader must always produce a value; there is
   no "do nothing" branch. (`when` is rejected with a hint for the same
   reason.)
2. **Both branches must have the same type**, because the whole `if` is an
   expression with one type. `int` unifies with `float` automatically;
   anything else is a compile error naming both types.

!!! warning "Both branches run"
    `if` compiles to GLSL's ternary operator, and on a GPU **both sides
    are typically evaluated** before one result is selected. That's fine
    for choosing between values, but it means you can't use `if` to *skip
    expensive work* — a raymarch inside the `then` branch costs the same
    whether or not the condition holds. Structure heavy computation so it
    always runs and use `if`/`mix`/`step` to select results.

## `cond` — multi-way choice

```clojure
(cond
  (< x 0.333) bass
  (< x 0.666) mid
  :else       treble)
```

`cond` is sugar for nested `if`s and inherits their rules; the final
`:else` clause is required.

## Choosing without branching

Much shader style avoids conditionals entirely in favor of arithmetic
selection — often smoother *and* faster:

```clojure
(step 0.5 x)              ; 0.0 or 1.0 — a hard threshold as a number
(smoothstep 0.4 0.6 x)    ; a soft threshold: 0->1 ramp between the edges
(mix a b t)               ; blend between two values by t
```

A hard-edged circle vs. an antialiased one:

```clojure
(if (< d 0.5) 1.0 0.0)         ; jagged edge
(smoothstep 0.51 0.49 d)       ; one pixel of soft falloff
```

## Booleans

Comparisons produce `bool`s; logic combines them:

```clojure
(< a b)  (> a b)  (<= a b)  (>= a b)  (= a b)  (not= a b)
(and c1 c2 ...)  (or c1 c2 ...)  (not c)
```

- The ordered comparisons (`<` `>` `<=` `>=`) work on numbers only. To
  compare vectors, compare a component or a `(length ...)` — the error
  message will remind you.
- `=` / `not=` compare any two values of the same type, including vectors
  (componentwise, by value).
- `bool`s are not numbers. To use a condition in math, convert explicitly
  with `(if c 1.0 0.0)` — or reach for `step`, which is usually what the
  math wanted anyway.

## Threading macros

`->` and `->>` work exactly as in Clojure and are pure syntax — they
rearrange the form before compilation:

```clojure
(-> (:x uv)
    (* 2.0)
    sin
    (+ 0.5)
    vec3)
;; = (vec3 (+ (sin (* (:x uv) 2.0)) 0.5))
```

Color pipelines read especially well threaded:

```clojure
(-> (vec3 hue 0.65 1.0)
    hsv->rgb)
```
