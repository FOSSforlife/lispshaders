# Raymarched blobs

The big one: real 3D, no meshes, no scene graph, no vertices. A whole
world defined as a single math function and rendered by walking rays
through it. This is where shader art stops feeling like drawing and starts
feeling like sculpting space itself — and it's more approachable than it
looks, because it's built from pieces you've already met.

## Three functions make a renderer

```clojure
(defn scene  [p] ...)   ; the world: distance from any point to the
                        ;   nearest surface (a "signed distance function")
(defn normal [p] ...)   ; which way that surface faces
(defn image  [uv] ...)  ; march a ray from the camera, then light what it hit
```

Everything hangs off `scene`, a pure function that answers one question
for *every* point in 3D space: **"how far is the nearest surface from
here?"** That's a signed distance function (SDF). It's defined everywhere,
but — like all shader infinity — only [computed where rays actually
travel](../language/thinking.md#infinity-is-free-you-only-pay-for-whats-seen).

## Shapes combine by arithmetic

Because `scene` returns a *number*, shapes stop being pictures and become
values you do algebra on:

```clojure
(smin donut ball 0.4)   ; min = union; smin melts the seam smoothly
```

`min` of two distances is their union, `max` is intersection, and the
prelude's `smin` fuses them like heated wax. This is
[shapes-as-values](../language/thinking.md#everything-is-a-pipeline) — the
moment geometry gets an algebra, every algebra question ("what unions with
what? what's the do-nothing shape?") becomes a new technique.

## The camera is a projection

Before you can march a ray you have to *aim* it, and the aiming is the
whole of perspective. A perspective view is a **pinhole camera**: one eye
point, and one flat image plane floating in front of it. Every pixel is a
ray that starts at the eye and passes through that pixel's spot on the
plane. Two lines in `image` set the whole thing up:

```clojure
(let [ro (vec3 0.0 1.1 -3.5)      ; the eye: where all rays begin
      rd (normalize (vec3 uvc 1.6))] ; the aim: one ray per pixel
  ...)
```

`ro` is trivial — a fixed point in space. All the geometry hides in `rd`,
so build it up one component at a time.

**Step 1 — the pixel's spot on the image plane.** `uvc` is this pixel's
screen position, [centered and aspect-corrected](../language/inputs.md#coordinates):
`(0, 0)` dead center, roughly `±1` toward the edges. Read it as a point on
a plane standing upright in front of the camera — its *x* and *y* are how
far right and up this pixel sits from the center of your view.

**Step 2 — push the plane forward.** `(vec3 uvc 1.6)` stacks a *z* of
`1.6` under that `(x, y)`. Now it's a 3D point: `1.6` units *ahead* of the
eye, offset sideways and up by `uvc`. That `1.6` is the **focal length** —
the eye-to-plane distance — and it's the only knob that matters here. Call
it `f`, and the ray (before normalizing) is just `(uvc.x, uvc.y, f)`.

**Step 3 — turn a point into a pure direction.** `normalize` scales that
vector to length 1, discarding *how far away* the plane sits and keeping
only *which way* the ray points. A center pixel (`uvc ≈ 0`) becomes
`(0, 0, 1)` — straight ahead. An edge pixel tilts off-axis. Because every
ray shares the origin `ro` but leaves at its own angle, the rays **fan
out** — and a fan is exactly what makes distant things look small: two
points the same size in the world subtend a smaller angle the farther off
they are, so fewer rays land on them. That shrinking-with-distance *is*
perspective.

### Focal length is your field of view

The half-width of what you can see is set by how the plane's edge (`u ≈ 1`)
compares to its depth `f`. The edge ray makes a right triangle — opposite
side `≈ 1`, adjacent side `f` — so `tan(fov/2) = 1/f`, i.e.
`fov = 2·atan(1/f)`.

So `f = 1.6` gives a half-angle of `atan(1/1.6) ≈ 32°`, a ~64° field of
view — a natural, slightly-tight lens. Crank `f` **up** and the edges
crowd toward center: a narrow FOV, the flattening *telephoto* zoom. Pull
`f` **down** toward `1.0` or below and the rays splay wide: a wide-angle,
almost fisheye bulge. It's a real camera lens, expressed as one number.

*(Drop the `f` entirely and use parallel rays — `(normalize (vec3 uvc 0.0))`
won't work, but a fixed `(vec3 0 0 1)` with `ro` shifted by `uvc` would —
and you get an **orthographic** view instead: no vanishing point, distant
things stay full size. Perspective is precisely the choice to let every
ray share one origin.)*

**Try:** replace `1.6` with `4.0` (zoom in flat), then `0.8` (wide and
warped). You're changing lenses without touching the scene.

## The march is just iter

Sphere tracing walks a ray forward in safe steps: `scene` reports the
distance to the nearest surface, which is exactly how far you can leap
without passing through anything. Repeat until you hit something. That's
[`iter`](../language/iter.md) used as a fold:

```clojure
(iter [i 80] [t 0.05]
  (+ t (scene (+ ro (* rd t))))) ; step forward by the safe distance, 80 times
```

`ro` is the ray origin (the camera), `rd` its direction; `t` is how far
we've traveled. After the loop, `(+ ro (* rd t))` is the hit point.

## Lighting reuses a trick you know

`normal` finds which way the surface faces by sampling `scene` at tiny
offsets on each axis — the **exact finite-difference trick** from
[Faking light](faking-light.md#the-same-idea-in-3d), just in 3D. Then a
single `(dot n light)` gives diffuse shading. Nothing new; the flat-field
skills transfer whole.

**Try:** the `0.4` in `smin` (rigid weld → liquid merge); swap `sd-torus`
for `(sd-box q (vec3 0.7))`; rotate `p` with `(* (rot2 time) ...)` in a
different plane. Each edit re-sculpts the world by editing one function —
the whole appeal of building a scene as [a system you then
play](../language/thinking.md#build-a-system-then-be-surprised-by-it).

---

Next: [Volumetric marching →](volumetric-marching.md) — what happens when
the ray doesn't *stop* at a surface but gathers light the whole way
through.
