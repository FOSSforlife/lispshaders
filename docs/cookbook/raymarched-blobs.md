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
