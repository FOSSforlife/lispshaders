# Program shape

A lispshaders program is a flat sequence of top-level forms. Only three
are allowed:

```clojure
(def name expr)             ; a named value
(defn name [params] body)   ; a helper function
(defsound name body)        ; one layer of the soundscape (see Drones)
```

Everything else — `let`, `if`, `iter`, math — lives _inside_ those forms.
The visual entry point is a `defn` named `image`:

```clojure
(defn image [uv]
  ...)   ; must return vec3, vec4, or a float (grayscale), components 0..1
```

`image` is called conceptually once per pixel, receiving that pixel's `uv`
coordinate. If your program defines drones but no `image`, you get a
built-in bass/mid/treble meter instead of an error — sound-first sketches
still have visuals.

## One expression per body

Every `defn` (and `let`) takes **exactly one body expression** — its value.
There is no `do`, because shaders have no side effects to sequence. When a
computation has steps, name them with `let`:

```clojure
(defn image [uv]
  (let [p     (* uvc 3.0)
        d     (length p)
        glow  (/ 0.08 (+ d 0.01))]
    (vec3 glow (* 0.5 glow) (* 0.2 glow))))
```

Later bindings see earlier ones, and rebinding a name shadows it, exactly
like Clojure.

## The type system you never write

Under the hood everything is statically typed with GLSL's types — `float`,
`int`, `bool`, `vec2`/`vec3`/`vec4`, `mat2`/`mat3`/`mat4` — but you never
annotate anything. Types are inferred bottom-up:

- Number literals are floats. `1` compiles to `1.0`; if you truly need an
  integer (rare), there's an `(int x)` cast.
- Constructors declare themselves: `(vec3 ...)` is a `vec3`.
- Builtins have known signatures: `(length v)` is a `float`, `(sin x)`
  returns whatever type it was given.
- User functions are **inlined at every call site** (see
  [Functions & defs](functions.md)), so their types are re-inferred per
  call — one `defn` can be used with floats in one place and `vec3`s in
  another, like an implicit generic.

The one implicit conversion is `int` → `float` (so loop indices flow into
math without ceremony). Everything else is checked, and mismatches fail
with a message naming both types — see
[Errors & Clojure differences](errors.md).

## What compiles to what

The **GLSL tab** in the app shows the output live, and it's deliberately
readable — your names survive (kebab-case becomes snake_case, shadowed
names get a `_2` suffix). A quick feel for the mapping:

| You write                   | The shader says                            |
| --------------------------- | ------------------------------------------ |
| `(+ a b c)`                 | `((a + b) + c)`                            |
| `(:xy v)`                   | `v.xy`                                     |
| `(if c a b)`                | `(c ? a : b)`                              |
| `(let [d (length p)] ...)`  | `float d = length(p);`                     |
| `(iter [i 64] [t 0.0] ...)` | `for (int i = 0; i < 64; i++) { ... }`     |
| `(my-fn x)`                 | my-fn's body, inlined with `x` substituted |

## Chapter map

- [Thinking in shaders](thinking.md) — the mindset: start here if
  shaders are new to you
- [Inputs & coordinates](inputs.md) — `uv`, `uvc`, `time`, `mouse`, and
  the audio globals
- [Vectors & swizzles](vectors.md) — constructing and picking apart data
- [Flow control](flow.md) — `if`, `cond`, booleans, threading macros
- [Loops with iter](iter.md) — the GPU's answer to `loop`/`recur`
- [Functions & defs](functions.md) — helpers, inlining, and its limits
- [Builtin reference](reference.md) — every function, one page
- [Errors & Clojure differences](errors.md) — what's missing and why
