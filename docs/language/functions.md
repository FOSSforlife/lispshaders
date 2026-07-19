# Functions & defs

## `defn` — helpers

```clojure
(defn scene [p]
  (smin (sd-torus p (vec2 1.0 0.35))
        (sd-sphere (- p (vec3 0.0 (sin time) 0.0)) 0.45)
        0.4))

(defn image [uv]
  ... (scene point) ...)
```

- Parameters are a plain vector of names — no destructuring, no type
  annotations (types come from the arguments at each call site).
- Exactly **one body expression**; use `let` to sequence work.
- Helpers can call other helpers freely. Definition order across the
  top level doesn't matter for `defn`s.
- A user `defn` **shadows a builtin** of the same name, so you can
  redefine `noise` if you have opinions about noise.

## `#()` — inline shorthand

For a helper too small to deserve a name, use Clojure's anonymous-function
shorthand right at the call site:

```clojure
(defn image [uv]
  (let [sq  (#(* % %) (:x uv))        ; % is the single argument
        lo  (#(+ (* %1 %1) %2) (:x uv) (:y uv))]   ; %1 %2 are positional
    (vec3 sq lo 0.0)))
```

- `%` is the first (and only) argument; `%1`, `%2`, … are positional. `%`
  and `%1` mean the same thing.
- The arity is however many parameters the body mentions — `#(vec3 %)` takes
  one, `#(+ %1 %2)` takes two, `#(vec3 0.0)` takes none.
- Because there are **no first-class functions** here (see inlining below), an
  `#()` only makes sense when it's called immediately — `(#(...) args)`. You
  can't store one in a `let` and pass it around, and rest args (`%&`) and
  nesting one `#()` inside another aren't supported, just as in Clojure.

Inside a thread, a bare `#()` is **called** with the threaded value (bound to
`%`) — no extra wrapping parens needed:

```clojure
(defn image [uv]
  (-> (:x uv)
      #(* % %)          ; threaded value binds to %  →  (#(* % %) (:x uv))
      (+ 0.1)
      vec3))
```

An `#()` compiles exactly like a one-off `defn`: it's inlined at the call
site, so it works in both the shader and a `defsound` and needs no type
annotations.

## Inlining: how functions really work

User functions don't compile to GLSL functions — every call is **inlined**:
the body is expanded at the call site with the arguments bound (cheap
arguments substitute directly; anything else gets a temporary, so an
argument expression is never evaluated twice).

This is the design decision the whole language leans on:

- **It's why there are no type annotations.** Each call site re-infers the
  body's types from that call's actual arguments, so one helper works for
  floats here and `vec3`s there.
- **It's why recursion is impossible.** A function that calls itself would
  inline forever; the compiler stops at depth 32 with an error suggesting
  `iter`. GLSL forbids recursion anyway, so nothing real is lost.
- **It's why the GLSL tab looks the way it does** — your helper's math
  appears at each place it's used, with your variable names intact.

The practical consequence: a big helper called inside an
80-pass `iter` appears once in the emitted loop body (the loop _runs_ 80
times but is _emitted_ once), but a big helper called in ten different
places is emitted ten times. For a live sandbox this is a fine trade; it's
just worth knowing when you read the GLSL output.

## `def` — named values

```clojure
(def speed 2.0)
(def spin (* time speed))   ; defs can use inputs and earlier defs

(defn image [uv]
  (vec3 (sin (+ spin (:x uvc)))))
```

`def`s compile to local variables at the top of the shader's `main`, in
source order — so a `def` may reference inputs (`time`, `mouse`, …) and
any `def` above it, and is conceptually recomputed per pixel. Use them for
tunable constants and shared per-frame quantities.

## Defs are shared with drones — and fail lazily

The same `def`s and `defn`s are compiled into **both** targets: the shader
(GLSL) and every `defsound` (an Elementary signal graph). One constant can
tune a drone _and_ draw its geometry — that's the point.

But some values only exist on one side: `mouse` and `uv` are shader
inputs with no meaning inside a drone, and `(osc ...)` makes sound with
no meaning in the image. Rather than poisoning the whole program, a
`def` that fails to compile for one target is **skipped there and only
errors if referenced**:

```clojure
(def m (* (:x mouse) 2.0))         ; fine in the shader, meaningless in a drone
(def sweep (lfo 0.1))              ; fine in a drone, meaningless in the shader

(defsound bed (* sweep (osc :a1))) ; ✓ compiles — never mentions m
(defsound bad (osc (* 100.0 m)))   ; ✗ "(def m ...) can't be used here"
(defn image [uv] (vec3 m))         ; ✓ the shader uses m happily
```

## Name mangling (why the GLSL looks slightly different)

Clojure names aren't legal GLSL identifiers, so the compiler cleans them
up predictably: `glow-amount` → `glow_amount`, `hsv->rgb` → `hsv_to_rgb`,
`hit?` → `hit_p`. When a `let` shadows a name (or a name collides with a
GLSL keyword), the later binding gets a numeric suffix: `c`, then `c_2`.
Purely cosmetic — but it makes the GLSL tab diffable against your source
by eye.
