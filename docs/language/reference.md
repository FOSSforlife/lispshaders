# Builtin reference

Everything callable, on one page. Throughout, _gen_ means "a `float` or
any vector, componentwise" — `(sin x)` works on a `float` and on a `vec3`,
returning the same type it was given.

This same material lives in the app's **reference** tab, so it's always a
click away while you write.

## Special forms

| Form                                         | Meaning                                                       |
| -------------------------------------------- | ------------------------------------------------------------- |
| `(def name expr)`                            | Top-level named value, computed once per pixel                |
| `(defn name [args] body)`                    | Helper function, inlined at call sites                        |
| `(#(* % %) x)`                               | Inline `#()` shorthand, called on the spot (`%`, `%1`, `%2` …) |
| `(defsound name body)`                       | One layer of the soundscape — see [Drones](../sound/index.md) |
| `(let [a 1.0 b (* a 2.0)] body)`             | Local bindings; later ones see earlier ones                   |
| `(if c then else)`                           | Both branches typed alike; `else` required                    |
| `(cond c1 e1 c2 e2 :else e)`                 | Multi-way `if`; `:else` required                              |
| `(iter [i n] [state init ...] body result?)` | Fixed-count loop — see [Loops with iter](iter.md)             |
| `(-> x f (g 2.0))`, `(->> ...)`              | Thread-first / thread-last                                    |
| `(and ...)`, `(or ...)`, `(not x)`           | Boolean logic                                                 |

## Constants

| Symbol | Value             |
| ------ | ----------------- |
| `pi`   | 3.141592653589793 |
| `tau`  | 6.283185307179586 |

Note keywords are constants too, Overtone-style: `:a4` compiles to
`440.0`, `:c#3` to `138.59…` — usable anywhere a number fits, in shaders
and drones alike.

## Constructors & casts

| Call                                           | Result                                                    |
| ---------------------------------------------- | --------------------------------------------------------- |
| `(vec2 x y)`, `(vec3 x y z)`, `(vec4 x y z w)` | Build a vector; nested vectors flatten (`(vec4 rgb 1.0)`) |
| `(vec3 s)`                                     | Scalar splat: all components = `s`                        |
| `(mat2 ...)`, `(mat3 ...)`, `(mat4 ...)`       | Matrix from column-major components                       |
| `(float x)`, `(int x)`                         | Scalar casts (scalars only)                               |

## Swizzles

| Call                                | Result                                            |
| ----------------------------------- | ------------------------------------------------- |
| `(:x v)` … `(:w v)`                 | One component, as `float`                         |
| `(:xy v)`, `(:zyx v)`, `(:xxy v)` … | 2–4 components, any order/repetition, as a vector |
| `(:r v)`, `(:rgb v)`, `(:rgba v)` … | Same thing in color letters (`rgba` ≡ `xyzw`)     |

## Arithmetic

| Call                                                       | Notes                                                                                                                   |
| ---------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `(+ a b ...)`, `(- a b ...)`, `(* a b ...)`, `(/ a b ...)` | N-ary, folding left. Componentwise on same-size vectors; scalars broadcast; `*` also does matrix×vector / matrix×matrix |
| `(- x)`                                                    | Negation                                                                                                                |
| `(/ x)`                                                    | Reciprocal, `1.0 / x`                                                                                                   |
| `(rsub m n)`                                               | Reversed subtract, `n - m` — reads well threaded: `(-> x (rsub 1.0))` is `1.0 - x`                                       |
| `(rdiv m n)`                                               | Reversed divide, `n / m` — reads well threaded: `(-> x (rdiv 6.0))` is `6.0 / x`                                         |

## Range mapping

Borrowed from Max/MSP and `gen~`, and available in shaders and drones
alike. Being ordinary DSL functions, they all work componentwise on
vectors too — `(bi->uni (vec3 -1.0 0.0 1.0))` is `(0.0, 0.5, 1.0)`.

| Call                                      | Notes                                                                                                         |
| ----------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| `(scale x in-min in-max out-min out-max)` | _gen_ — remap `x` from one range to another, exactly like Max/MSP's `[scale]`. Nothing is clamped: feed it a value outside `in-min..in-max` and you get a proportionally extrapolated result |
| `(bi->uni x)`                             | _gen_ — bipolar → unipolar: `-1..1` becomes `0..1`, i.e. `(scale x -1.0 1.0 0.0 1.0)`                        |
| `(uni->bi x)`                             | _gen_ — unipolar → bipolar: `0..1` becomes `-1..1`, i.e. `(scale x 0.0 1.0 -1.0 1.0)`                        |
| `(wrap x lo hi)`                          | _gen_ — force `x` into `lo..hi` by jumping back to the far edge; `mod` generalized off zero                   |
| `(fold x lo hi)`                          | _gen_ — force `x` into `lo..hi` by _reflecting_ off each edge instead of jumping                              |
| `(quant x step)`                          | _gen_ — snap `x` down to a grid of `step`-sized rungs; `(quant x 0.1)` gives ten bands                        |

`sin`, `cos` and `wander`-style motion swing bipolar; brightness, `mix`
amounts and `smoothstep` want unipolar. `scale` and the two converters are
the bridge:

```clojure
(mix col-a col-b (bi->uni (sin (* t 2.0))))   ; oscillate a blend
(-> (:x uv) (scale 0.0 1.0 20.0 200.0))       ; uv across a cutoff sweep
```

**`wrap` vs `fold`** is the difference between a value that teleports and
one that bounces. Wrapping a coordinate tiles space with hard seams;
folding it mirrors space, so the seams disappear:

```clojure
(-> (:x uvc) (wrap -1.0 1.0))   ; tiles, with a visible cut at each edge
(-> (:x uvc) (fold -1.0 1.0))   ; kaleidoscope — edges meet seamlessly
```

The same split holds in a drone: `fold` on an over-driven signal is a
wavefolder, adding harmonics rather than flattening peaks — see
[Aggression](../sound/aggression.md#hard-clip-and-wavefold-the-mean-end).

`quant` is the "scale up, round, scale back down" sandwich with a name. It
posterizes color, staircases time into steps, and — via `hz->midi` —
quantizes pitch:

```clojure
(-> freq hz->midi (quant 1.0) midi->hz)   ; snap a glide to semitones
```

## Trigonometry

| Call                          | Notes                                                         |
| ----------------------------- | ------------------------------------------------------------- |
| `(sin x)` `(cos x)` `(tan x)` | _gen_, radians                                                |
| `(asin x)` `(acos x)`         | _gen_                                                         |
| `(atan y)`                    | _gen_ — arctangent                                            |
| `(atan y x)`                  | Two-argument form: the angle of point `(x, y)`, full ±π range |

## Exponents & rounding

| Call                                      | Notes                                                            |
| ----------------------------------------- | ---------------------------------------------------------------- |
| `(pow x y)`                               | A scalar exponent broadcasts to match a vector base              |
| `(sqrt x)` `(inversesqrt x)`              | _gen_                                                            |
| `(exp x)` `(log x)` `(exp2 x)` `(log2 x)` | _gen_, natural / base-2                                          |
| `(abs x)` `(sign x)`                      | _gen_                                                            |
| `(floor x)` `(ceil x)` `(fract x)`        | _gen_ — `fract` is the endlessly useful "repeat" operator        |
| `(mod x y)`                               | GLSL-style: `x - y*floor(x/y)`, always positive for positive `y` |

## Ranges & blending

| Call                            | Notes                                                              |
| ------------------------------- | ------------------------------------------------------------------ |
| `(min a b ...)` `(max a b ...)` | N-ary; a scalar can mix with vectors                               |
| `(clamp x lo hi)`               | Bounds both scalar, or both matching `x`                           |
| `(mix a b t)`                   | Linear blend — the workhorse of shader art; `t` scalar or matching |
| `(step edge x)`                 | `0.0` when `x < edge`, else `1.0` — hard threshold                 |
| `(smoothstep e0 e1 x)`          | Smooth 0→1 ramp between two edges — soft threshold / antialiasing  |

## Geometry

| Call                | Notes                                             |
| ------------------- | ------------------------------------------------- |
| `(length v)`        | Euclidean length → `float`                        |
| `(distance a b)`    | `(length (- a b))`                                |
| `(normalize v)`     | `v` scaled to length 1                            |
| `(dot a b)`         | Dot product → `float`                             |
| `(cross a b)`       | Cross product, `vec3` only                        |
| `(reflect i n)`     | Mirror `i` about normal `n` (normalize `n` first) |
| `(refract i n eta)` | Snell's-law bend; `eta` is the ratio of indices   |

## Comparisons

| Call                                      | Notes                                               |
| ----------------------------------------- | --------------------------------------------------- |
| `(< a b)` `(> a b)` `(<= a b)` `(>= a b)` | Numbers only → `bool`                               |
| `(= a b)` `(not= a b)`                    | Any same-typed values, including vectors (by value) |

Inside a drone, comparisons (and the `if` built on them) compile to
signal-rate gates and crossfades — see
[defsound](../sound/defsound.md#conditionals-are-crossfades).

## The creative kit (shader prelude)

Hand-written GLSL helpers baked into every shader — the classic building
blocks of creative shader work. These are shader-side tools; only the
hashes and `midi->hz` also exist inside drones.

| Call                  | Signature              | Notes                                                                                                                                     |
| --------------------- | ---------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `(hash1 x)`           | `float → float`        | Deterministic pseudo-random, 0..1                                                                                                         |
| `(hash2 v)`           | `vec2 → float`         | Same, from a 2D seed                                                                                                                      |
| `(noise v)`           | `vec2 → float`         | Smooth value noise, ~0..1                                                                                                                 |
| `(fbm v)`             | `vec2 → float`         | 5 octaves of noise, rotated per octave — instant clouds/terrain                                                                           |
| `(palette t a b c d)` | `float, vec3×4 → vec3` | [IQ's cosine palette](https://iquilezles.org/articles/palettes/); try `(palette t (vec3 0.5) (vec3 0.5) (vec3 1.0) (vec3 0.0 0.33 0.67))` |
| `(hsv->rgb v)`        | `vec3 → vec3`          | Hue/saturation/value → RGB — easy rainbows                                                                                                |
| `(rot2 a)`            | `float → mat2`         | 2D rotation matrix: `(* (rot2 a) v)` spins a `vec2`                                                                                       |
| `(smin a b k)`        | `float×3 → float`      | Polynomial smooth minimum — melts two distance fields together                                                                            |
| `(sd-sphere p r)`     | `vec3, float → float`  | Signed distance to a sphere                                                                                                               |
| `(sd-box p b)`        | `vec3, vec3 → float`   | Signed distance to a box with half-extents `b`                                                                                            |
| `(sd-torus p t)`      | `vec3, vec2 → float`   | Signed distance to a torus, `t` = `(vec2 major minor)`                                                                                    |
| `(midi->hz m)`        | `float → float`        | MIDI note number → frequency, Overtone-style (69 → 440)                                                                                   |
| `(hz->midi f)`        | `float → float`        | The inverse — frequency → note number. Go to note-space to do pitch math, then come back                                                  |

## Drone builtins

Available only inside `(defsound ...)` bodies — the audio counterpart of
the prelude. `sig` means a `float` (mono) or `vec2` (stereo pair); the
result keeps the shape that came in. Covered in depth in
[defsound](../sound/defsound.md) and
[Movement & modulation](../sound/movement.md).

| Call                                                   | Notes                                                 |
| ------------------------------------------------------ | ----------------------------------------------------- |
| `(osc f)` `(saw f)` `(square f)` `(tri f)`             | Oscillators at `f` Hz (saw/square/tri anti-aliased)   |
| `(phasor f)`                                           | Raw 0..1 ramp at `f` Hz                               |
| `(noise)` `(pink)`                                     | White / pink noise                                    |
| `(lfo rate)`                                           | Slow sine breath, 0..1                                |
| `(wander rate)`                                        | Smooth random drift, 0..1                             |
| `(slew secs sig)`                                      | Ease a jumpy signal over `secs` (portamento, declick) |
| `(lowpass fc q sig)` `(highpass ...)` `(bandpass ...)` | Filters; sweep `fc` with a modulator                  |
| `(drive amount sig)`                                   | Warm tanh saturation, `amount` 0..1                   |
| `(echo ms feedback sig)`                               | Dry + feedback delay (up to ~5 s)                     |
| `(pan pos sig)`                                        | Place mono in the field, 0 left .. 1 right → `vec2`   |
| `(space amount sig)`                                   | Diffuse into a wide stereo wash → `vec2`              |
| `(sample "name" rate)`                                 | Loop an uploaded audio file (mono); `rate` 1.0 = original speed, and can itself be a signal |
| `(scrub "name" pos)`                                   | Read an uploaded audio file at position 0..1 — sweep `pos` for tape-head / granular textures |

## Uploaded media

The **samples** and **images** tabs in the app take audio and image files
(stored in your browser, never uploaded anywhere). Each file is referred
to by its slugified name as a string literal, and names are checked at
compile time against what you've actually added.

| Call                   | Where          | Notes                                                                     |
| ---------------------- | -------------- | ------------------------------------------------------------------------- |
| `(sample "name" rate)` | drones only    | See the drone builtins above                                              |
| `(scrub "name" pos)`   | drones only    | See the drone builtins above                                              |
| `(texture "name" uv)`  | shaders only   | Read an image as a `vec4` color; coordinates wrap, so the image tiles. In the Shadertoy export each image maps to `iChannel1`+ (`iChannel0` carries audio) |

## Inputs

Covered in depth in [Inputs & coordinates](inputs.md):
`uv` `uvc` `time` `resolution` `mouse` and the audio globals
`bass` `mid` `treble` `level` `audio`. Inside a drone, `time` is a
signal — seconds since the engine started.

| Call         | Notes                                                                                                    |
| ------------ | ---------------------------------------------------------------------------------------------------------- |
| `(ramp rate)` | A 0..1 sawtooth restarting `rate` times a second — `(fract (* time rate))`, the shader counterpart of a drone's `phasor`. Drives anything cyclic: `(mix a b (ramp 0.5))` |
