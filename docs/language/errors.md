# Errors & Clojure differences

The compiler tries hard to fail with messages that teach. This page
collects what's deliberately _not_ Clojure, and how to read the errors you
will actually hit.

## The status bar never kills your shader

A compile error shows in the status bar (red, full message, often with
`in: <the offending form>` so you can find it), but the **last good shader
keeps rendering** and the last good drone keeps playing. Fix the error and
the live compile picks everything back up.

## What's missing, and what to use instead

Each of these is rejected with a hint pointing at the replacement:

| Clojure habit           | Why it can't work here                                             | Use instead                                                                                  |
| ----------------------- | ------------------------------------------------------------------ | -------------------------------------------------------------------------------------------- |
| `loop` / `recur`        | GPUs need fixed loop bounds, not recursion                         | [`iter`](iter.md)                                                                            |
| `map` `reduce` `for`    | Lazy sequences don't exist at shader level                         | `iter` with an accumulator                                                                   |
| `when` / `when-not`     | No else branch, but a shader must always produce a value           | `(if c then else)`                                                                           |
| `do`                    | Implies side effects; shaders have none                            | `let` to name intermediate values                                                            |
| `fn` (anonymous fns)    | Functions are compile-time inlining, not values                    | A top-level `defn`                                                                           |
| Recursive `defn`s       | Inlining a self-call never terminates (and GLSL forbids recursion) | `iter`                                                                                       |
| `[a b]` as a value      | Reserved for `iter` state updates                                  | `(vec2 a b)`                                                                                 |
| `def` inside a function | Definitions are top-level structure                                | `let`                                                                                        |
| Strings, maps           | The only runtime data is numbers, vectors, matrices, booleans      | —                                                                                            |

(Keywords, uniquely, _are_ values: a bare `:a2` compiles to its frequency
in Hz, and in call position `(:xy v)` is a swizzle.)

## Reading the common errors

**`Unknown symbol: wat`** — a typo, or a name that exists in the other
target (see below). Check the reference tab for what's in scope.

**`Unknown function: shuffle — check the reference panel for what's available`**
— you called something the language doesn't have. The
[reference](reference.md) is the source of truth.

**`+ can't apply + to vec2 and vec3`** _(and similar type mismatches)_ —
vector sizes must match. Usually the fix is a swizzle (`(:xy v)`) or a
constructor (`(vec3 v 0.0)`) at the point of the mismatch.

**`if branches must have the same type, got float and vec3`** — make both
branches the same shape; often that means splatting the scalar:
`(vec3 0.0)` instead of `0.0`.

**`if's condition must be a boolean (from < > = etc), got float`** —
numbers aren't truthy here. Compare explicitly: `(if (< x 0.5) ...)`.

**`< only compares numbers — for vectors compare (length ...) or a component`**
— ordered comparison of vectors has no single meaning; say which one you
want.

**`:z reads component .z but the value is only a vec2`** — a swizzle
checked against the value's actual size.

**`Call chain too deep at f — recursive functions can't run on a GPU; use iter for loops`**
— a `defn` (directly or indirectly) calls itself.

**`(def m ...) can't be used here — Unknown symbol: mouse`** — you
referenced a shader-side `def` from a drone (or vice versa). `def`s are
compiled per target and fail only where referenced; see
[Functions & defs](functions.md#defs-are-shared-with-drones-and-fail-lazily).

**`(osc ...) makes sound, so it only works inside a (defsound ...)`** —
and its mirror image, **`(fbm ...) is a shader tool — it isn't available
inside a defsound`**. The two targets have different toolkits; the error
names which side you're on.

**`(hash1 ...) needs a plain number`** — the hashes pick a fixed random
value while the graph is built, so they can't consume a signal. Hash
`iter` indexes for per-voice character; use `wander` for random movement.

**`GLSL rejected the compiled shader: ...`** — rare: the DSL compiled but
the driver's GLSL compiler objected (most often via hand-written `mat3`/
`mat4` constructors, which are validated lightly). The message includes
the driver's own error and the GLSL tab shows the exact code it saw.

**`audio: ...`** — drone-side problems are prefixed `audio:`. The shader
half of your program still compiled and is running.

## Numbers: one honest difference

All number literals are **floats**. `1` and `1.0` are the same thing, and
integer division doesn't exist: `(/ 3 2)` is `1.5`. The only true integer
in the language is `iter`'s loop index, which silently promotes to float
the moment it meets float math. If you need integer-style behavior —
lattice cells, checkerboards — use `floor` and `mod`, which is the shader
idiom anyway:

```clojure
(mod (+ (floor (:x cell)) (floor (:y cell))) 2.0)   ; checkerboard parity
```
