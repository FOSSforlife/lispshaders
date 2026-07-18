# Loops with iter

GPUs can't do `loop`/`recur` — recursion doesn't exist in GLSL, and loop
bounds must be known to the compiler. `iter` is this language's answer: a
fixed-count loop that rebinds state each pass, designed to feel like
`loop`/`recur` while compiling to a plain `for` loop.

## One state variable

```clojure
(iter [i 80]        ; loop index i, exactly 80 passes (literal count!)
      [t 0.05]      ; state var t, initial value 0.05
  (+ t (scene (+ ro (* rd t)))))   ; body: its value becomes the next t
```

Reading it aloud: *run the body 80 times; each pass, `t` is rebound to the
body's value; the whole `iter` form evaluates to the final `t`.* That
example is sphere tracing — the classic raymarching loop — in four lines.

The index `i` is an `int` that counts 0, 1, 2, …; the moment it meets
float math it auto-promotes, so `(* 0.1 i)` just works.

## Several state variables

With more than one state binding, the body must end in a **vector literal**
holding the next value of each, in order — this is the one place square
brackets are legal:

```clojure
(iter [i 96]
      [z (vec2 0.0)     ; two state vars:
       n 0.0]           ;   z walks the complex plane, n counts steps
  [(step-z z c)         ; body returns [new-z new-n]
   (if (escaped? z) n (+ n 1.0))]
  n)                    ; optional result expression (default: first state var)
```

Updates are **parallel**, like `loop`/`recur`: every next-state expression
sees the *old* values of all state variables. (The compiled GLSL routes
them through `_next` temporaries to guarantee it.)

The optional trailing expression after the body is the `iter`'s result,
evaluated with the final state in scope. Without it, you get the first
state variable.

## Rules and errors

- **The count must be a positive literal integer.** `(iter [i n] ...)`
  with a computed `n` won't compile — GLSL wants unrollable bounds. If you
  need "variable" iteration depth, loop the maximum and make extra passes
  no-ops with `if`.
- **The update vector's length must match the state count.** Two state
  vars, two elements — checked with a pointed message.
- **`[...]` may only appear as the body of the `iter`** — or as the body
  of a `let` inside it, so you can name intermediate results:

```clojure
(iter [i 96] [z (vec2 0.0) n 0.0]
  (let [z2      (complex-square z)
        escaped (> (dot z z) 4.0)]
    [(if escaped z (+ z2 c))          ; the let's body is the update vector
     (if escaped n (+ n 1.0))])
  n)
```

## Patterns

**Accumulate** (fbm-style, summing octaves):

```clojure
(iter [i 5] [acc 0.0]
  (+ acc (* (pow 0.5 (+ 1.0 i)) (noise (* p (pow 2.0 i))))))
```

**March** (walk a ray until it hits something):

```clojure
(iter [i 80] [t 0.05]
  (+ t (scene (+ ro (* rd t)))))
```

**Escape-time** (Mandelbrot — see the gallery example):

```clojure
(iter [i 96] [z (vec2 0.0) n 0.0]
  [...next-z... ...next-n...]
  n)
```

!!! note "Why not `map`/`reduce`/`for`?"
    Sequence functions run at Clojure's level — lazy seqs, allocation,
    dynamic length — none of which exists on a GPU. Using them gives a
    compile error that points you here. `iter` with an accumulator *is*
    `reduce` over the range 0..n; that mental translation covers nearly
    every case.
