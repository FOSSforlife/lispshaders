# The intro series (after Uğur Güney)

This page is a lispshaders translation of **["Introduction to shaders"](https://www.shadertoy.com/view/Md23DV)**,
the beloved 28-step beginner series **by Uğur Güney** (originally published
on Shadertoy, March 8, 2014). The original is written in raw GLSL for
Shadertoy; all credit for the arc, the examples, and the palette belongs to
its author. What follows walks the same 28 steps, in the same order, but
written in this language — so you can learn shaders the classic way while
staying in the editor you already have open.

!!! quote "From the original"
    *"Hi! I started learning GLSL a month ago. The speedup gained by using
    the GPU to draw real-time graphics amazed me. If you want to learn how
    to write shaders, this tutorial written by a beginner can be a starting
    place for you."* — Uğur Güney

## How this translation differs from the GLSL

A few things change when the same ideas move into lispshaders. None of them
is a shortcut — each is a real difference worth understanding:

- **You *return* a color; you don't write one.** Shadertoy's `mainImage`
  assigns `fragColor = vec4(...)`. Here, `image` is a function that
  **returns** a `vec3`. There is no alpha channel to worry about and no
  output variable to set.
- **Coordinates arrive pre-normalized.** Shadertoy hands you `fragCoord` in
  raw pixels and makes you divide by `iResolution`. lispshaders hands
  `image` a `uv` that's already `0..1` — and offers `uvc` (centered,
  aspect-correct) for free. Steps 5–11 of the original are largely *about*
  building `uvc` by hand; we'll build it, then switch to the real thing.
- **No `inout`. Shapes return masks.** The original threads a mutable
  `pixel` through drawing functions. GLSL-style mutation doesn't exist here,
  so a shape function returns a `0..1` **coverage mask** and the caller
  blends with `mix` — which is exactly the cleaner style the original itself
  arrives at by step 20.
- **One canvas, but you edit live.** The original crams five demos
  side-by-side because Shadertoy shows one shader at a time. Here you just
  change a number and recompile, so we unpack those into single focused
  snippets you can poke at.

Ready? Open the app, keep this page beside it, and type every snippet.

---

# Part 1 — A color on the screen (steps 1–4)

## 1. Void

The original's first shader is empty and shows black. Ours can't be *empty* —
`image` must return a color — but returning black is the same idea, and it
highlights the core mental model: **this function runs once per pixel**, in
parallel, and its return value *is* that pixel.

```clojure
(defn image [uv]
  (vec3 0.0))          ; every pixel: black
```

`(vec3 0.0)` is the **scalar splat** — one number fanned out to all three
channels. A pixel's color can only depend on its own coordinate and the
shared inputs (time, mouse…); it can never read another pixel. That single
constraint is what lets the GPU run millions of these at once.

## 2. Solid color

Return a constant and the whole screen takes it. Three numbers, red-green-blue,
each `0..1`:

```clojure
(defn image [uv]
  (vec3 1.0 1.0 0.0))   ; yellow: full red + full green
```

## 3. Vectors

A color is just a `vec3`. You can name its pieces and assemble it, which
pays off the moment the color starts coming from a calculation:

```clojure
(defn image [uv]
  (let [color (vec3 0.0 1.0 1.0)]   ; cyan
    color))
```

(In GLSL you'd build a `vec4` with an alpha; here the harness owns alpha, so
you only ever deal in `vec3`.)

## 4. RGB and components

Vectors are **immutable** — you don't assign into `color.x` the way the
original does. You *read* components with keyword swizzles (`(:x c)`,
`(:rgb c)`) and *build* new vectors with the constructor. Same result,
functional style:

```clojure
(defn image [uv]
  (let [red   0.6
        green 0.2
        blue  0.9]
    (vec3 red green blue)))
```

**Try:** nudge each amount and watch the flat field shift hue. Palette
pickers like [coolors.co](https://coolors.co) are a good way to find sets
that sit well together — the original recommends the same habit.

---

# Part 2 — Building a coordinate system (steps 5–11)

## 5. The coordinate system (and a resolution trap)

The original introduces `fragCoord`, the pixel's position in *raw pixels*,
and splits the screen at pixel `x = 100`. We can recover raw pixels by
multiplying `uv` by `resolution` — and doing so reproduces the original's
first bug on purpose:

```clojure
(defn image [uv]
  (let [px (* (:x uv) (:x resolution))]     ; back to raw pixels
    (if (> px 100.0)
      (vec3 0.537 0.741 0.408)
      (vec3 0.886 0.576 0.898))))
```

Resize the window and the colored strip changes width, because `100.0` is an
absolute pixel count. That's the problem step 6 fixes.

## 6. Resolution independence

Because `image` already receives `uv` in `0..1`, splitting the screen in
half needs no `iResolution` at all — the normalization the original does by
hand is simply done for you:

```clojure
(defn image [uv]
  (if (> (:x uv) 0.5)
    (vec3 0.741 0.635 0.471)
    (vec3 0.192 0.329 0.439)))
```

Now the split holds at any size. This is what "coordinates arrive
pre-normalized" buys you.

## 7. Three bands

`cond` is the multi-way `if`. Three vertical bands across the normalized x:

```clojure
(defn image [uv]
  (cond
    (< (:x uv) (/ 1.0 3.0)) (vec3 0.841 0.582 0.594)
    (< (:x uv) (/ 2.0 3.0)) (vec3 0.884 0.850 0.648)
    :else                   (vec3 0.348 0.555 0.641)))
```

## 8. Horizontal and vertical lines

A line is "where a coordinate is *close to* a value" — `abs(coord − c) <
thickness`. Instead of the original's last-write-wins pixel mutation, we
layer with `mix`: `(mix bg col m)` keeps `bg` when the mask `m` is 0 and
paints `col` when it's 1. Thread the background through several layers:

```clojure
(defn image [uv]
  (let [x  (:x uv)
        y  (:y uv)
        bg (vec3 1.0)
        v1 (if (and (> x 0.54) (< x 0.55)) 1.0 0.0)   ; a band
        v2 (if (< (abs (- x 0.40)) 0.003) 1.0 0.0)    ; a thin line
        v3 (if (< (abs (- y 0.60)) 0.010) 1.0 0.0)]   ; a horizontal line
    (-> bg
        (mix (vec3 0.216 0.471 0.698) v1)
        (mix (vec3 1.00 0.329 0.298) v2)
        (mix (vec3 0.867 0.910 0.247) v3))))
```

The last `mix` wins where masks overlap — the functional echo of "the last
statement sets the pixel."

## 9–11. Visualising the grid, centering, and fixing the aspect ratio

Steps 9–11 build, in three moves, exactly what `uvc` already is:
**centered** at the screen's middle and **aspect-correct** so a grid square
is actually square. Rather than divide by resolution by hand, meet the built-in
and draw a coordinate grid on it. Here's a reusable grid helper — an
anti-aliased set of ticks and axes — built with `iter`, this language's
GPU-safe `for` loop:

```clojure
;; Keep this helper in your editor for the transformation steps below.
(defn coord-grid [p]
  (let [ticks (iter [i 40] [acc 0.0]                 ; 40 lines, -2.0 .. +1.9
                 (let [c (- (* 0.1 i) 2.0)]          ; this line's coordinate
                   (+ acc
                      (- 1.0 (smoothstep 0.0 0.008 (abs (- (:x p) c))))
                      (- 1.0 (smoothstep 0.0 0.008 (abs (- (:y p) c)))))))
        axes  (+ (- 1.0 (smoothstep 0.001 0.015 (abs (:x p))))
                 (- 1.0 (smoothstep 0.001 0.015 (abs (:y p)))))]
    (+ ticks axes)))

(defn image [uv]
  (let [bg (vec3 1.0)]
    (mix bg (vec3 0.3) (coord-grid uvc))))    ; uvc: centered AND square
```

`iter [i 40] [acc 0.0] body` runs `body` 40 times, rebinding `acc` to its
value each pass — an accumulator, the shader stand-in for `reduce`. The
count must be a literal so the GPU can unroll it. Note the origin sits dead
center and the grid squares are truly square: that's the payoff of `uvc`
over raw `uv`, and it's what the original's steps 10 and 11 work to achieve
manually.

---

# Part 3 — Shapes (steps 12–13)

## 12. Disk

A shape is an *inside/outside test*. A disk is "points closer to the center
than the radius" — `length(p − center) < r`. Using `smoothstep` for the test
instead of a hard `<` gives a soft, anti-aliased edge from the start:

```clojure
(defn image [uv]
  (let [p uvc
        d (distance p (vec2 0.0 0.0))              ; distance to center
        m (- 1.0 (smoothstep 0.29 0.31 d))]        ; 1 inside r≈0.3, 0 outside
    (mix (vec3 0.3) (vec3 0.216 0.471 0.698) m)))
```

To move a shape, subtract its center from the coordinate: `f(p − c)` draws
`f` shifted by `c`. That trick works for *every* shape.

## 13. Shapes as functions

Package the disk as a helper. The original passes an `inout pixel`; we
return a **coverage mask** instead and let the caller blend. This is the
idiom the whole rest of the series (and this whole toolkit) uses:

```clojure
(defn disk [p center r]
  (- 1.0 (smoothstep (- r 0.005) (+ r 0.005) (distance p center))))

(defn image [uv]
  (let [p uvc]
    (-> (vec3 0.3)                                            ; background
        (mix (vec3 0.867 0.910 0.247) (disk p (vec2 0.1 0.3) 0.5))
        (mix (vec3 0.216 0.471 0.698) (disk p (vec2 -0.8 -0.6) 1.5))
        (mix (vec3 1.00 0.329 0.298) (disk p (vec2 0.8 0.0) 0.15)))))
```

Because `disk` returns a number, the disks compose by ordinary function
calls — and thanks to `smoothstep`, their edges are already smooth, no
jaggies. **Keep `disk` in your editor** for the steps ahead.

---

# Part 4 — The built-in functions (steps 14–18)

These are the five workhorses. The original devotes a step to each; here
they are, one snippet apiece, each mapping a value up the vertical axis so
you can *see* its shape.

## 14. step — the hard switch

`(step edge x)` is `0` below the edge and `1` above it:

```clojure
(defn image [uv]
  (vec3 (step 0.5 (:y uv))))     ; black bottom half, white top half
```

## 15. clamp — saturate to a range

`(clamp x lo hi)` pins a value between two bounds. Below, a gradient that
flattens out top and bottom:

```clojure
(defn image [uv]
  (vec3 (clamp (:y uv) 0.3 0.7)))
```

## 16. smoothstep — the soft switch

Like `step`, but with a smooth (cubic) ramp between two edges — the reason
our disks aren't jagged:

```clojure
(defn image [uv]
  (vec3 (smoothstep 0.45 0.55 (:y uv))))
```

Compare it directly with step 14 by swapping `step` back in: the sudden jump
becomes a graceful fade.

## 17. mix — blend two things

`(mix a b t)` is a linear blend: all `a` at `t=0`, all `b` at `t=1`. It's
the single most-used function in shader art. Drive `t` with a coordinate for
a gradient between two colors:

```clojure
(defn image [uv]
  (mix (vec3 0.216 0.471 0.698)      ; blue at the bottom
       (vec3 1.00 0.329 0.298)       ; red at the top
       (:y uv)))
```

Combine it with `smoothstep` for a controlled transition band:
`(mix col-a col-b (smoothstep 0.5 0.6 (:y uv)))`.

## 18. Anti-aliasing with smoothstep

Here's the whole point of `smoothstep` in one comparison — a hard-edged disk
next to a soft one. Split the screen and use a plain threshold on the left,
`smoothstep` on the right:

```clojure
(defn image [uv]
  (let [p uvc
        d (distance p (vec2 0.0 0.0))
        hard (step 0.5 d)                        ; jagged rim
        soft (smoothstep 0.49 0.51 d)            ; clean rim
        m    (if (< (:x p) 0.0) hard soft)]
    (mix (vec3 0.216 0.471 0.698) (vec3 0.3) m)))
```

Widen the `0.49 → 0.51` gap and the edge gets softer; that gap is your
anti-aliasing dial. (`disk` from step 13 already bakes this in.)

---

# Part 5 — Plotting and compositing (steps 19–20)

## 19. Plotting functions

To *see* a function `y = f(x)`, shade the pixels where `y` is close to
`f(x)` — the same "close to a value" idea as a line, applied to a curve. A
tiny `plot` helper returns a mask; feed it any function of `x`:

```clojure
(defn plot [p y thick]
  (- 1.0 (smoothstep 0.0 thick (abs (- (:y p) y)))))

(defn image [uv]
  (let [p uvc
        x (:x p)
        bg (mix (vec3 1.0) (vec3 0.5) (coord-grid p))]   ; grid from step 9
    (-> bg
        (mix (vec3 0.84 0.58 0.59) (plot p (* x x) 0.02))         ; parabola
        (mix (vec3 0.35 0.55 0.64) (plot p (sin (* pi x)) 0.02))  ; sine
        (mix (vec3 0.88 0.85 0.65) (plot p (* 0.5 x) 0.02)))))    ; line
```

Now GLSL functions are plottable natively — handy for seeing exactly what a
`smoothstep` or a `fract` actually does before you use it.

## 20. Adding and subtracting color

Shapes-as-masks compose like light and like paint. **Additive** (like colored
lights) sums masked colors toward white; **subtractive** (like dyes) removes
them from white toward black:

```clojure
(defn image [uv]
  (let [p uvc]
    ;; additive: three colored lights overlapping brighten toward white
    (+ (* (disk p (vec2 -0.3 0.2) 0.4) (vec3 0.216 0.471 0.698))
       (* (disk p (vec2  0.3 0.2) 0.4) (vec3 1.00 0.329 0.298))
       (* (disk p (vec2  0.0 -0.3) 0.4) (vec3 0.867 0.910 0.247)))))
```

Swap `(+ ...)` starting from `(vec3 1.0)` and subtracting each term to see
the subtractive (paint-mixing) version.

---

# Part 6 — Coordinate transformations (steps 21–23)

The key reframe, and the heart of shader thinking: **you don't move the
shape, you move the space you ask it about.** All three steps here transform
the coordinate `p` *before* handing it to a shape.

## 21. Rotation

`rot2` builds a 2D rotation matrix; multiply a coordinate by it to spin the
space. Draw the same disk in the original space and the rotated space:

```clojure
(defn image [uv]
  (let [p uvc
        q (* (rot2 (* 0.2 pi)) p)]        ; rotated coordinate system
    (-> (vec3 1.0)
        (mix (vec3 0.216 0.471 0.698) (disk p (vec2 0.6 0.0) 0.2))
        (mix (vec3 1.00 0.329 0.298) (disk q (vec2 0.6 0.0) 0.2)))))
```

Both disks are drawn at `(0.6, 0)` — the same coordinate in *their* space —
yet they land in different screen places, because `q` measures a rotated
world.

## 22. Scaling

Dividing the coordinate by a factor **zooms in** (the space shrinks, so
what's in it looks bigger):

```clojure
(defn image [uv]
  (let [p uvc
        q (/ p 3.3)]                       ; zoomed-in coordinate system
    (-> (vec3 1.0)
        (mix (vec3 0.5) (* 0.5 (coord-grid q)))
        (mix (vec3 0.216 0.471 0.698) (disk p (vec2 0.0 0.0) 0.1)))))
```

A shape at the origin doesn't move under scaling (multiplying zero by
anything is zero); shapes elsewhere slide, because scaling multiplies their
distance from the origin.

## 23. Order matters

Transformations don't commute. Rotate-then-translate and
translate-then-rotate land the same shape in different places:

```clojure
(defn image [uv]
  (let [p   uvc
        rt  (- (* (rot2 0.6) p) (vec2 0.4 0.3))   ; rotate, then translate
        tr  (* (rot2 0.6) (- p (vec2 0.4 0.3)))]  ; translate, then rotate
    (-> (vec3 1.0)
        (mix (vec3 0.216 0.471 0.698) (disk rt (vec2 0.0 0.0) 0.12))
        (mix (vec3 1.00 0.329 0.298) (disk tr (vec2 0.0 0.0) 0.12)))))
```

Two disks, identical code except the order of two operations — and they
don't coincide. Mastering the order of transforms is how you place anything
exactly where you want it.

---

# Part 7 — Motion (steps 24–25)

## 24. Time, motion, animation

`time` is seconds since the shader started — the one input that makes things
move. Feed it into a coordinate and the picture animates. A disk bouncing on
a `mod` sawtooth, plus a size that breathes with `sin`:

```clojure
(defn image [uv]
  (let [p uvc
        y (- (mod time 2.0) 1.0)               ; 0→2 sawtooth, remapped to -1→1
        r (+ 0.15 (* 0.05 (sin (* time 8.0)))) ; radius pulsing
        m (disk p (vec2 0.0 y) r)]
    (mix (vec3 0.2) (vec3 0.216 0.471 0.698) m)))
```

**Try:** replace `y` with `(* 0.8 (sin (* tau time)))` for a smooth
oscillation instead of a bounce. `tau` is one full turn, so this completes a
cycle per second.

## 25. Plasma

The classic: sum a few sine waves of the coordinates and the time, then map
the total through sines-of-different-phase to get color. Pure math, no
shapes — a whole moving image from four `sin`s:

```clojure
(defn image [uv]
  (let [r  (* uvc 8.0)
        t  time
        v  (+ (sin (+ (:x r) t))                 ; vertical waves
              (sin (+ (:y r) t))                 ; horizontal waves
              (sin (+ (:x r) (:y r) t))          ; diagonal waves
              (sin (+ (length r) (* 1.7 t))))]   ; circular waves
    (+ (vec3 0.5)
       (* 0.5 (vec3 (sin v)
                    (sin (+ v (* 0.5 pi)))
                    (sin (+ v pi)))))))
```

Each `sin` is one wave family; their sum has rich interference, and the
phase-shifted color mapping turns that scalar into the shifting plasma
palette.

---

# Part 8 — Inputs (steps 26–28)

## 26. Textures

Upload an image in the app's **images** tab, then read it as color with
`(texture "name" uv)` — it returns a `vec4`, so take `.rgb`. (Coordinates
wrap, so the image tiles.) Say you named your image `grid`:

```clojure
(defn image [uv]
  (:rgb (texture "grid" uv)))
```

Scroll or scale it by transforming the coordinate first, exactly like any
other space: `(:rgb (texture "grid" (+ uv (vec2 (* 0.1 time) 0.0))))`. In
the Shadertoy export, uploaded images map to `iChannel1` and up.

## 27. Mouse

`mouse` is the cursor position, already `0..1` on both axes (no pixel
conversion needed). A disk that follows the cursor, over a background that
tracks the cursor's x:

```clojure
(defn image [uv]
  (let [bg (vec3 (:x mouse))                          ; brightness follows cursor x
        m  (disk uv mouse 0.03)]                       ; disk sits under the cursor
    (mix bg (vec3 0.216 0.471 0.698) m)))
```

Interactivity is one input away — `mouse` behaves just like `time`, a slow
signal you can drag by hand.

## 28. Randomness

GPUs have no random-number generator, so shaders fake it with a **hash**: a
function whose output jumps wildly for nearby inputs. lispshaders ships
`hash1` (from a number) and `hash2` (from a `vec2`), plus smooth `noise`
built on them — so you rarely write your own, but it's worth seeing the
scatter it enables. Twenty disks at hashed positions and sizes, via `iter`:

```clojure
(defn image [uv]
  (let [p uvc]
    (iter [i 20] [col (vec3 0.3)]                       ; accumulate over disks
      (let [seed (float i)
            pos  (- (* 2.6 (vec2 (hash1 seed)           ; a "random" position
                                 (hash1 (+ seed 0.5)))) 1.3)
            r    (* 0.18 (hash1 (+ seed 3.5)))]         ; a "random" radius
        (mix col (vec3 0.216 0.471 0.698) (disk p pos r))))))
```

Each loop pass seeds `hash1` with the index, so every disk gets a stable,
scattered position and size. Change the multipliers or the seed offsets and
you get an entirely different arrangement — deterministic, yet random-looking.

---

## Where to go next

That's the whole of Uğur Güney's introduction, rebuilt in lispshaders. You
now have the same foundation the original set out to give: coordinates,
shapes, the built-in functions, transformations, motion, and inputs — all in
this language's idiom of **returning colors and transforming the space you
ask about.**

| Now that this clicks… | Go to |
|---|---|
| The same moves as a modern hands-on sequence | [Tutorial: your first effects](tutorial.md) |
| The mental model underneath all of it | [Thinking in shaders](language/thinking.md) |
| These ideas at full power in finished pieces | [Cookbook](cookbook/index.md) |
| Every function you can paint with | [Builtin reference](language/reference.md) |

And do the thing the original author kept urging: open an example, find a
number, and change it. You now know what most of them do.

!!! info "Credit"
    Adapted with gratitude from **["Introduction to shaders" by Uğur
    Güney](https://www.shadertoy.com/view/Md23DV)** (Shadertoy, 2014). The
    teaching sequence and examples are the author's; this page only
    translates them into lispshaders.
