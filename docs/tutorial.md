# Tutorial: your first effects

[Thinking in shaders](language/thinking.md) is the *why*. This page is the
*do*. It's a sequence of tiny, hands-on exercises — the kind you'd get in a
good video course — each one a single edit that produces a visible effect.
Work through them in order with the app open, typing every snippet yourself
and nudging the numbers. Half an hour here and UV coordinates will stop
feeling like magic.

Nothing below assumes you've read the rest of the language guide. The only
prerequisite is [Getting started](getting-started.md): you can run the app
and you've seen the editor recompile as you type.

## A picture to play with

Real shader courses start with a sprite — an image to distort. We don't
have images (lispshaders is purely procedural), so our first job is to
*grow* one. Here's a little scene: a soft yellow dot sitting on a color
gradient, deliberately **off-center** so that every flip, zoom, and mirror
to come is easy to see.

```clojure
;; Our stand-in for a "sprite": a function from a point to a color.
(defn picture [uv]
  (let [bg  (vec3 (:x uv) (* 0.4 (:y uv)) 0.7)   ; a gradient backdrop
        d   (distance uv (vec2 0.35 0.62))        ; distance to the dot's home
        dot (smoothstep 0.19 0.16 d)]             ; 1 near the dot, 0 far away
    (mix bg (vec3 1.0 0.9 0.2) dot)))             ; paint the dot over the bg

;; `image` is the real entry point. For now it just shows the picture.
(defn image [uv]
  (picture uv))
```

Paste the whole thing in and you should see a golden blob up and to the
left, over a blue-green wash. **Keep `picture` in your editor for the rest
of this page** — every exercise below changes only `image`, and reads
`picture` as if it were a photo we're about to mess with.

!!! tip "The one idea that makes all of this work"
    Look at how `image` calls `(picture uv)`. Every exercise from here on
    works by **handing `picture` a different coordinate than the pixel
    actually has**. We never move the dot. We change where we *look*. If
    that sounds strange, it's the exact reorientation
    [Thinking in shaders](language/thinking.md#move-the-space-not-the-shape)
    is about — and you're about to feel it seven different ways.

---

# Part 1 — Moving the picture around (UVs)

## 1. Tint it (a damage flash)

The gentlest start: leave the coordinates alone and touch the *output*
instead. `mix` blends between two colors, and a `sin` pushed into the 0..1
range gives us a pulse. Here the picture flashes white, the way a game
sprite flashes when it takes a hit.

```clojure
(defn image [uv]
  (let [flash (* 0.6 (+ 0.5 (* 0.5 (sin (* 6.0 time)))))]
    (mix (picture uv) (vec3 1.0) flash)))
```

`(+ 0.5 (* 0.5 (sin …)))` is the **range-shift idiom** you'll type a
thousand times: `sin` swings −1..1, but almost everything visual wants
0..1.

**Try:** flash red instead of white (`(vec3 1.0 0.0 0.0)`); speed the pulse
up by changing `6.0`.

## 2. Zoom toward a point

Now we touch the coordinates. Multiplying `uv` by less than 1 **shrinks the
coordinate space**, which makes the picture inside it look bigger — a zoom.
Adding a constant afterward slides which part we've zoomed into.

```clojure
(defn image [uv]
  (picture (+ (* uv 0.5)            ; halve the coordinates -> 2× zoom
              (vec2 0.15 0.28))))   ; then pan to frame the dot
```

Multiply → zoom. Add → pan. That's the whole move, and it's the tetris of
UV work: those two operations, in that order, reframe anything.

**Try:** change `0.5` to `0.25` (a 4× zoom) and re-pick the offset so the
dot is centered again. Notice how zooming *more* means you have to pan
*less* — the space shrank.

## 3. Zoom from the center

Exercise 2 zoomed toward the bottom-left corner, because `(0,0)` — the
place multiplication leaves untouched — lives there. To zoom around the
**middle** instead, first move the center to the origin, scale, then move
it back:

```clojure
(defn image [uv]
  (let [centered (- uv (vec2 0.5))      ; put (0,0) at the middle
        zoomed   (* centered 0.5)]      ; scale around that middle
    (picture (+ zoomed (vec2 0.5)))))   ; move the middle back
```

"Subtract the center, do the thing, add the center back" is a pattern you'll
reuse for rotation and mirroring too — anything that should pivot about a
point instead of the corner.

!!! note "This is what `uvc` is for"
    Doing `(- uv (vec2 0.5))` by hand gets old, and it ignores the screen's
    aspect ratio (so circles come out as ovals). The built-in `uvc` is
    exactly this — centered *and* aspect-corrected — and it's what real
    shaders reach for. See [Inputs & coordinates](language/inputs.md). We're
    using plain `uv` here only so the arithmetic stays in plain sight.

## 4. Flip it

To flip vertically, reverse the y axis. On a 0..1 coordinate, "reverse"
means `1 − y`: the top row asks for the bottom row's content.

```clojure
(defn image [uv]
  (picture (vec2 (:x uv)          ; x unchanged
                 (- 1.0 (:y uv))))) ; y runs the other way -> upside down
```

The dot jumps from the top to the bottom. Swap which component you flip for
a horizontal mirror-image instead.

**Try:** flip *both* axes at once (`(- 1.0 uv)` — the whole vector) for a
180° rotation. Then make the flip a toggle: multiply the flip by
`(step 0.5 (:x mouse))` so the left and right halves of the mouse decide
whether it's flipped.

## 5. Mirror it

A mirror is a fold: make the right half of space show a reflection of the
left. `abs` is the fold operator. Center the x axis, take its absolute
value so left and right map to the same place, then un-center it:

```clojure
(defn image [uv]
  (let [x (:x uv)
        folded (- 0.5 (abs (- x 0.5)))]  ; both sides mirror the left half
    (picture (vec2 folded (:y uv)))))
```

You get two dots now, perfectly symmetric — one real, one its reflection —
because the single dot on the left is being shown on both sides.

**Try:** fold the y axis the same way for a four-way kaleidoscope. This
`abs`-to-mirror trick is one of the most reused moves in all of shader art.

## 6. Wipe transition

Time to *hide* parts of the picture. `step` is a hard on/off switch:
`(step edge x)` is `0` while `x` is below `edge` and `1` above it. Multiply
the color by that switch and half the screen goes black. Drive the edge with
time and the black sweeps across like a scene transition.

```clojure
(defn image [uv]
  (let [edge   (fract time)                 ; a 0->1 ramp that resets each second
        shown  (step edge (:x uv))]         ; 1 to the right of the edge, else 0
    (* (picture uv) shown)))
```

`(fract time)` — the fractional part of the clock — is a free sawtooth wave:
it climbs 0→1 and snaps back, over and over. Perfect for a looping wipe.

**Try:** wipe vertically (use `(:y uv)`); soften the hard edge into a fade
by swapping `step` for `(smoothstep (- edge 0.1) edge (:x uv))`.

## 7. Crop it

Cropping is wiping on all four sides at once. Build a mask that's `1` only
inside a rectangle by multiplying four `step`s together — each one gates one
edge — then multiply the picture by it.

```clojure
(defn image [uv]
  (let [x (:x uv) y (:y uv)
        inside (* (step 0.15 x) (step x 0.85)     ; keep 0.15 < x < 0.85
                  (step 0.30 y) (step y 0.95))]   ; keep 0.30 < y < 0.95
    (* (picture uv) inside)))
```

Read `(step x 0.85)` carefully: the coordinate is the *edge* this time and
`0.85` is the value, so it's `1` while `x` stays **below** 0.85. Flipping
which argument is the edge is how you choose which side gets cut.

**Try:** shrink the rectangle to a thin slot; multiply `inside` into a
color of your choice to tint the border region instead of blacking it out.

---

# Part 2 — Functions as brushes

The UV moves above were mostly straight lines and boxes. The real
expressiveness comes from **feeding coordinates through math functions**.
Each function has a "shape," and painting a coordinate with it stamps that
shape onto the picture. Four functions carry most of the load: `sin`
(waves), `fract` (repetition), `floor` (chunky steps), and again the
range-shift trick to keep results in 0..1.

## 8. Pulse (sine as a heartbeat)

`sin` over time is the metronome of shaders. Range-shift it and multiply it
into the brightness for a smooth throb:

```clojure
(defn image [uv]
  (let [beat (+ 0.4 (* 0.6 (sin (* tau time))))]  ; breathe between 0.4 and 1.0
    (* (picture uv) beat)))
```

`tau` is a full turn (2π), so `(sin (* tau time))` completes exactly one
cycle per second — handy when you want motion to line up with a clock or
loop cleanly.

**Try:** pulse the *size* of the dot instead of the brightness by feeding
`beat` into a zoom-from-center (exercise 3). Coordinate tricks and function
tricks compose freely.

## 9. Tile it (repetition with `fract`)

`fract` throws away the whole-number part of a value, so it always lands in
0..1 — which means it **wraps**. Multiply the coordinate up first and every
whole step becomes a fresh copy of the picture:

```clojure
(defn image [uv]
  (picture (fract (* uv 3.0))))   ; 3×3 grid of the whole picture
```

One word, `fract`, turned one dot into nine. In a painting program that's
nine times the work; here it's *less* code than drawing one, because you
folded space before the picture-question was ever asked. This is the single
biggest payoff of thinking in coordinates.

**Try:** use `(* uv (vec2 4.0 2.0))` for a non-square grid; add
`(* 0.1 time)` to the coordinate before `fract` to make the tiles scroll.

## 10. Pixelate it (chunk it with `floor`)

`floor` rounds down to whole numbers — it turns a smooth ramp into a
staircase. Snap the coordinate to a grid before sampling and the picture
loses resolution, going blocky:

```clojure
(defn image [uv]
  (let [cells 24.0
        snapped (/ (floor (* uv cells)) cells)]  ; round to the nearest cell
    (picture snapped)))
```

The `(/ (floor (* x n)) n)` sandwich — scale up, round, scale back down — is
the universal "quantize to `n` steps" recipe. It works on colors and time
too, not just coordinates.

**Try:** drop `cells` to `6.0` for heavy pixels; drive it from the mouse
with `(+ 4.0 (* 40.0 (:x mouse)))` to slide from chunky to sharp.

## 11. Scanlines (a CRT look)

Stack a fast `sin` across the vertical axis and you get stripes; multiply
them over the picture and it reads as an old monitor. Slide the stripes with
time and they roll like a real CRT's refresh:

```clojure
(defn image [uv]
  (let [lines (+ 0.7 (* 0.3 (sin (- (* 220.0 (:y uv))   ; ~110 stripes
                                     (* 6.0 time)))))]   ; scrolling upward
    (* (picture uv) lines)))
```

The big number (`220.0`) is the stripe count; the `(* 6.0 time)` subtracted
inside is the scroll speed. Notice the same range-shift keeps the darkening
gentle instead of pitch-black gaps.

**Try:** add a subtle horizontal wobble by also multiplying in
`(sin (* 3.0 time))`; combine this with the pulse from exercise 8 for a
flickering screen.

## 12. Wave it (a flag in the wind)

The finale ties space, time, and `sin` together. Offset each row's x
coordinate by a sine of its *height* and the clock, and straight things
ripple. Scale the offset by `x` itself so the left edge stays pinned — like
a flag on a pole:

```clojure
(defn image [uv]
  (let [amount (* 0.06 (:x uv))                     ; 0 at the pole, big at the fly
        wave   (* amount (sin (+ (* 9.0 (:y uv))    ; a wave up the height...
                                 (* 3.0 time))))]   ; ...travelling in time
    (picture (+ uv (vec2 wave 0.0)))))
```

Every row asks for a slightly shifted column, and because the shift depends
on `time`, the ripple travels. This one snippet is `sin` (the wave shape),
a coordinate offset (moving the space), and time-as-input all at once —
which is to say, it's the whole tutorial in five lines.

**Try:** wave the y coordinate too, for a flutter; increase `9.0` for
tighter ripples; increase `3.0` for a faster wind.

---

## Where to go next

You've now met, hands-on, the moves that underlie most 2D shader effects:
zoom, pan, flip, mirror, mask, tile, pixelate, and warp — every one of them
a transformation of the coordinate you hand to a function. That's not a
grab-bag of tricks; it's *one* idea seen from many sides.

| Now that this clicks… | Go to |
|---|---|
| Cement the mental model behind all of it | [Thinking in shaders](language/thinking.md) |
| See these ideas at full power in real pieces | [Cookbook](cookbook/index.md) |
| Learn the coordinate inputs properly (`uvc`, `mouse`, `time`) | [Inputs & coordinates](language/inputs.md) |
| Look up every function you can paint with | [Builtin reference](language/reference.md) |
| Make the picture *repeat* a rule instead of tiling a copy | [Loops with iter](language/iter.md) |

And the best exercise of all is still the one the course creators keep
repeating: open a [gallery example](cookbook/index.md), find a number, and change
it. You now know what most of them do.
