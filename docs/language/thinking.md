# Thinking in shaders

If you've painted, or written imperative graphics code (`canvas.drawCircle(...)`),
shaders will feel upside-down at first. This page is the reorientation. None
of it is specific to lispshaders — it's how all shader artists think — but
a Lisp makes each idea unusually visible.

## You don't paint. You answer a question.

There is no canvas, no brush, no "draw" call. There is one function,
`image`, and the GPU asks it the same question about every pixel at once:

> *"What color is the point `uv`?"*

Your entire artwork is your answer. This has a strange and wonderful
consequence: **you must describe what the image *is*, not the steps to
draw it**. A circle isn't "move the pen around a center" — it's a fact
about distance:

```clojure
(defn image [uv]
  (let [d (length uvc)]              ; how far is this point from center?
    (vec3 (smoothstep 0.5 0.49 d)))) ; inside: white. outside: black.
```

Read that as a sentence: *the brightness of a point is whether its
distance from the center is under 0.5*. Every shader, no matter how
elaborate, is a sentence like this — the color of a point, derived
logically from its coordinates and the time. Nothing else exists: no
hidden state, no memory of the last frame, no pixel knowing what its
neighbor did. The same inputs always give the same color.

That purity sounds like a limitation. It's actually the superpower the
rest of this page unpacks.

## Move the space, not the shape

Here's the inversion that unlocks everything. You never move a shape —
you can't; the shape is just a question about points. Instead, **you
transform the question's input**. To put the circle somewhere else, shift
every incoming point the *opposite* way before asking:

```clojure
(let [p (- uvc (vec2 0.3 0.0))]   ; slide space left...
  (length p))                     ; ...and the circle appears to the right
```

This feels backwards for about a day, and then it becomes the most
powerful tool you own, because *any* function can transform space, not
just translation:

```clojure
(* (rot2 time) p)          ; rotate space -> everything in it spins
(* p 3.0)                  ; scale space up -> everything shrinks
(fract p)                  ; fold space into repeating tiles ->
                           ;   one circle becomes an infinite grid of them
(abs p)                    ; mirror space -> instant symmetry
(+ p (* 0.3 (noise p)))    ; wobble space -> everything melts organically
```

Look at `fract` again. In a painting mindset, drawing a thousand circles
is a thousand times the work of drawing one. Here, it's *one extra
word* — you didn't copy the circle, you made all of space repeat before
the circle question gets asked. The **disco tunnel** gallery example is
nothing but this trick: switching to polar coordinates turns the flat
plane into an endless tube. Same circle-math, different space.

Sculpt the coordinate system; the things inside it come along for free.

## Everything is a pipeline

A shader grows by composing small transformations, each feeding the next.
Clojure's threading macro makes the shape of that thought literal:

```clojure
(-> uvc                 ; start with a point in space
    (* 3.0)             ; zoom out
    (warp time)         ; bend space with noise
    scene               ; measure distance to the geometry
    glow                ; turn distance into brightness
    tint)               ; turn brightness into color
```

Each stage is a pure function — testable alone, swappable, reorderable.
Creativity here *is* composition: you rarely invent new math; you plug
existing pieces together in new orders and see what emerges. Swap `warp`
and `scene` in that pipeline and you get a genuinely different artwork,
for free.

Even shapes combine algebraically. With signed distance functions, `min`
of two distances is their **union**, `max` is intersection, and the
prelude's `smin` *melts* them together:

```clojure
(smin (sd-torus p (vec2 1.0 0.35))
      (sd-sphere (- p ball-pos) 0.45)
      0.4)                            ; donut + ball, smoothly fused
```

Shapes stop being pictures and become **values you do arithmetic on**.
Once combining things has an algebra, you can ask algebra questions —
"what combines with what?", "what's the do-nothing element?" — and each
answer is a new technique.

## Time is just another coordinate

Because a shader has no memory, animation can't be "change things each
frame." Instead, `time` arrives as one more input, exactly like `uv` —
and you treat it exactly like space. Your animation is a 3D shape (x, y,
time), and each frame is a slice through it.

That means every trick from the previous sections works on time too:

```clojure
(sin time)                 ; loop time (motion that repeats seamlessly)
(* time 0.1)               ; scale time (slow motion)
(+ time (* 2.0 (:y uv)))   ; shear time across space -> a wave rolls
                           ;   through, because higher pixels live
                           ;   slightly in the future
(fract time)               ; tile time (a beat that resets each second)
```

Nothing is "moving" anywhere — you're describing a still, unchanging
4-dimensional object, and the passage of time is your window sliding
through it. Pause the clock (**⏸** in the toolbar) and this stops being
philosophy: the frozen frame is just the slice at that instant, and you
can keep editing it.

## Infinity is free (you only pay for what's seen)

Try this: `(fract p)` tiles space *forever*. The Mandelbrot example
contains literally infinite detail at every zoom level. The raymarcher
defines a complete 3D world in every direction. None of this costs
anything, because **only the points actually asked about are ever
computed** — the pixels on screen, the places rays land.

So define the unbounded fearlessly. A shader artist's scene is usually an
infinite mathematical object, of which the screen shows a finite window.
Zooming, panning, and flying around are then just… moving the window
(which, you now know, means transforming the input).

## Build a system, then be surprised by it

Notice what `palette` is: not a color, but a *color-scheme factory* —
four knobs that generate an endless family of gradients. Your `defn`s
work the same way. You're rarely making one image; you're making a
**parameterized family of images** and then exploring it:

```clojure
(defn scene [p k]                 ; k: how melted?
  (smin (donut p) (ball p) k))    ; k=0.01 rigid ... k=1.0 goo
```

This is the real workflow of shader art, and why the app recompiles on
every keystroke: you build a small system of composed functions, then
*play* it — nudging numbers, swapping stages, following accidents. And
because everything is pure, accidents are never mysteries: an unexpected
beauty is sitting right there in the code, reproducible, ready to be
understood and pushed further. In most programming, surprising behavior
is a bug to eliminate. Here it's often the artwork announcing itself.

## Small code, big image

You may notice shader artists prize a strange metric: how much visual
complexity emerges per line of code. The plasma example is ~10 lines; the
Mandelbrot, with its infinite filigree, ~15. This isn't code golf for its
own sake — when the description is small, *you can still see the whole
thought*, and every part remains touchable. Prefer a few well-named
`let` bindings over many; prefer transforming space over special-casing
regions; let repetition come from `fract` and `iter`, not copy-paste.
When a shader gets long, the fix is usually not organization but a better
idea.

---

**The shift, in one line:** the image is not a canvas you paint — it's a
statement you make, and every pixel is that statement evaluated at one
point. Get comfortable with that, and the rest of this guide is just
vocabulary. Now go break the [gallery examples](../cookbook/index.md) — that's
what they're for.
