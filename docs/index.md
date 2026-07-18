# lispshaders

A Shadertoy-style sandbox where the shading language is a **Clojure dialect**.
You write s-expressions in the left pane; a compiler turns them into GLSL ES
3.0 and hot-swaps the fragment shader on a WebGL2 canvas as you type — time
keeps flowing, so editing feels like sculpting a running program.

```clojure
(defn image [uv]
  (vec3 (:x uv)
        (:y uv)
        (+ 0.5 (* 0.5 (sin time)))))
```

It's a drone sandbox too, built on one symmetry:

> **A shader describes what every pixel is. A drone describes what the
> signal is.**

A `defsound` is written in the same language and compiled by the same
compiler — not to GLSL, but to an
[Elementary Audio](https://www.elementary.audio/) signal graph. Editing a
drone _morphs the running sound in place_, exactly the way editing a
shader morphs the running image; a live FFT analysis feeds `bass` /
`mid` / `treble` / `level` back into your shader — one program, two
projections, visuals that hear.

```clojure
(defsound bed
  (->> (+ (saw :a1) (saw 55.4))                     ; note keywords are Hz
       (lowpass (+ 300.0 (* 700.0 (lfo 0.1))) 1.2)  ; a slowly opening filter
       (* 0.3)
       (space 0.4)))                                ; diffused into stereo

(defn image [uv]
  (vec3 (* bass (- 1.0 (length uvc)))))   ; the visuals swell with the bed
```

## Why a Lisp?

Shader art is exploratory programming at its purest: tweak a number, see the
result, repeat. Clojure's strengths map onto that loop surprisingly well:

- **Everything is an expression.** GLSL statements disappear; your whole
  shader is one value flowing outward from `image`.
- **`let`, `->`, and `cond`** structure math pipelines far more readably
  than nested C-style calls.
- **No type annotations.** Types are inferred bottom-up from literals,
  constructors, and builtin signatures — you write `(vec3 0.5)`, the
  compiler knows the rest.
- **One language, two instruments.** The expression you'd write for a
  filter sweep and the one you'd write for a color sweep are the same
  shape — and pitches use [Overtone](https://overtone.github.io/)'s note
  vocabulary (`:a2`, `:c#3`), so they read like music.

## Where to go next

| If you want to…                                | Read…                                       |
| ---------------------------------------------- | ------------------------------------------- |
| Run the app and write your first shader        | [Getting started](getting-started.md)       |
| Learn by doing — a dozen tiny effect exercises | [Tutorial: your first effects](tutorial.md) |
| Understand how shader artists think            | [Thinking in shaders](language/thinking.md) |
| Learn the language properly                    | [The language](language/index.md)           |
| Look up a builtin                              | [Builtin reference](language/reference.md)  |
| Make sound                                     | [Drones](sound/index.md)                    |
| Take a shader to shadertoy.com                 | [Exporting shaders](export.md)              |
| Steal techniques from the gallery              | [Cookbook](cookbook/index.md)               |
| Understand the compiler                        | [Under the hood](internals.md)              |

!!! note "A sandbox, on purpose"
There is no account system, no gallery uploads, and no project files.
Your work-in-progress autosaves to `localStorage` on every compile, so a
refresh won't eat it — but the app is deliberately a scratchpad for
creative exploration, not a portfolio site.
