# Vectors & swizzles

Vectors are the atoms of shader programming: positions, directions, and
colors are all `vec2`/`vec3`/`vec4` values, and nearly every builtin works
on them componentwise.

## Constructing

```clojure
(vec2 x y)
(vec3 r g b)
(vec4 r g b a)
```

Two conveniences, both straight from GLSL:

- **Splat.** One scalar fills every component:
  `(vec3 0.5)` → `(0.5, 0.5, 0.5)`.
- **Composition.** Smaller vectors nest into bigger ones as long as the
  component count works out: `(vec4 some-vec3 1.0)`, `(vec3 uv 0.0)`,
  `(vec4 (vec2 0.0) uv)`.

Component counts are checked at compile time; `(vec3 uv)` (2 components
into 3) is an error that tells you exactly what it got.

## Swizzles: keywords as accessors

Reading components uses Clojure's keyword-call idiom:

```clojure
(:x v)      ; one component -> float
(:xy v)     ; two -> vec2
(:zyx v)    ; any order, including reversed -> vec3
(:xxx v)    ; repetition is fine -> vec3
```

Both GLSL alphabets work and mean the same thing: `x y z w` for space,
`r g b a` for color. `(:rgb c)` and `(:xyz c)` are identical; pick
whichever reads better in context.

Swizzles are checked against the value's actual size — `(:z uv)` fails at
compile time with a message pointing out that `uv` is only a `vec2`.

!!! tip "There is no write-swizzle"
    Values are immutable (this is a Lisp, and GLSL assignment doesn't
    survive the translation). To "change one component," build a new
    vector: `(vec3 (:xy p) new-z)`.

## Vector math

`+ - * /` work componentwise on same-size vectors, and scalars broadcast
against vectors from either side:

```clojure
(+ (vec3 1 2 3) (vec3 10))     ; (11.0, 12.0, 13.0)
(* uv 2.0)                     ; both components doubled
(/ 1.0 p)                      ; scalar broadcast over a vector
```

Mixing *different* vector sizes is a compile error — there's no sensible
meaning for `vec2 + vec3`, and the compiler says so.

Geometry helpers (full signatures in the [reference](reference.md)):

```clojure
(length v)         (distance a b)      (normalize v)
(dot a b)          (cross a b)         ; cross is vec3-only
(reflect i n)      (refract i n eta)
```

## Matrices

Matrices exist mainly to rotate things. The one you'll actually use is the
prelude's `rot2`:

```clojure
(let [spun (* (rot2 (* 0.5 time)) uvc)]   ; mat2 * vec2 rotates the plane
  ...)
```

Raw constructors `(mat2 ...)`, `(mat3 ...)`, `(mat4 ...)` accept
column-major components like GLSL, and `*` handles matrix×vector,
matrix×scalar, and matrix×matrix. This is a power-user corner: the
compiler validates it lightly and lets GLSL report component-count
mistakes.

## Vector literals `[...]` are not vectors

A square-bracket literal like `[a b]` is **reserved for `iter`'s
multi-state updates** ([Loops with iter](iter.md)) and is an error
anywhere else. To build a value, always use a constructor:

```clojure
[x y]        ; ✗ only legal as an iter state update
(vec2 x y)   ; ✓ a value
```

This split is deliberate: `(vec2 ...)` is *data flowing through math*,
while `[...]` is *the loop's next state* — the same distinction Clojure
draws between a value and `recur`'s argument list.
