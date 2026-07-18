# Faking light

Here's a move that feels like magic the first time: take a flat noise
field, pretend it's a bumpy 3D surface, and light it — complete with a
sharp specular glint that sells it as wet metal. No 3D, no geometry. Just
the field from [domain warping](domain-warping.md) and a little calculus.

## liquid chrome — a height field, lit

```clojure
(let [h  (fbm (+ p (* 4.0 r)))          ; the domain-warped field, as a height
      e  0.02
      hx (- (fbm (+ p (vec2 e 0.0) (* 4.0 r))) h)   ; slope in x
      hy (- (fbm (+ p (vec2 0.0 e) (* 4.0 r))) h)   ; slope in y
      n  (normalize (vec3 (* -60.0 hx) (* -60.0 hy) 1.0))  ; surface normal
      light (normalize (vec3 0.5 0.7 0.6))
      spec  (pow (max (dot n light) 0.0) 16.0)]      ; the glint
  (+ (* col (+ 0.2 (* 0.8 h))) (* (vec3 1.0) spec)))
```

The whole idea is in two steps.

**1. A normal from finite differences.** A surface's "which way am I
facing?" is its *normal*. We don't have a formula for the noise's slope,
so we measure it: sample the field a tiny step `e` to the right and
subtract — that's `hx`, how fast the height rises eastward. Same for `hy`
northward. A surface tilting steeply in x faces mostly sideways, so the
normal is `(-hx, -hy, 1)`, scaled up and normalized. **Sampling a function
at tiny offsets to find its slope** is one of the most transferable tricks
in all of shader work.

**2. Lighting from a dot product.** `(dot n light)` is large when the
surface faces the light and zero when it faces away — that's diffuse
shading in one operation. Raising it to a high power (`pow ... 16.0`)
keeps only the values *very* close to 1, collapsing the lit area to a
tight hotspot: the specular highlight. Bigger exponent, sharper glint.

## The same idea, in 3D

This finite-difference normal isn't a one-off. The [raymarched
blobs](raymarched-blobs.md) example computes its `normal` function the
exact same way — sampling the distance field at `±e` on each axis — just
in three dimensions instead of two. Learn it flat here and you already
understand it in 3D.

`dot`, `normalize`, and the other vector operations are covered on the
[Vectors & swizzles](../language/vectors.md) page.

**Try:** drop the `pow` exponent to `4.0` for a broad satin sheen, or push
it to `64.0` for a pinpoint mirror glint; change `-60.0` to exaggerate or
flatten the bumps; move the `light` vector and watch the highlight slide.
