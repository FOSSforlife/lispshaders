# Volumetric marching

[Raymarched blobs](raymarched-blobs.md) marches a ray until it *hits a
surface* and stops. But clouds, nebulae, and glowing tunnels have no
surface — they're light scattered through a haze. So change the rule:
don't stop. **Step through the volume and add up the light you pass.**
Same loop, one different question, and you can render things that don't
have an edge at all.

Both examples here are the heaviest in the cookbook — dozens of noise
samples per pixel. They're also where everything you've learned converges.

## volumetric nebula — accumulate light along the ray

```clojure
(iter [i 32] [col (vec3 0.0)]
  (let [t    (* 0.12 i)
        p    (+ ro (* rd t))                      ; a point along the ray
        d1   (fbm (+ (* (:xy p) 1.5) ...))        ; density from two crossed
        d2   (fbm (+ (* (:yz p) 1.5) ...))        ;   noise slices
        dens (max (- (* d1 d2) 0.15) 0.0)         ; cloud where they agree
        tint (palette ...)]
    (+ col (* tint dens 0.22))))                  ; emit and accumulate
```

The structure is the [marching loop](raymarched-blobs.md#the-march-is-just-iter),
but instead of one state variable tracking distance, the state is `col` —
**color accumulating as the ray flies**. At each step we sample a density
and add that much glowing color, so thick regions along the ray build up
into bright cloud and empty stretches contribute nothing.

The density itself is a nice trick: multiply two [`fbm`](domain-warping.md)
fields sampled on *different pairs of axes* (`p.xy` and `p.yz`). Where both
happen to be high the product spikes; elsewhere it's near zero. Two cheap
2D noises stand in for genuine 3D noise, carving wispy pockets. The
`(- ... 0.15)` then `max ... 0.0` clips away the thin haze so the clouds
have clear gaps.

## wormhole — the same loop, shaped into a tunnel

```clojure
(iter [i 40] [col (vec3 0.0)]
  (let [t      (* 0.18 i)
        p      (+ ro (* rd t))
        cx     (* 0.6 (sin (* 0.4 (:z p))))       ; tunnel centerline
        cy     (* 0.6 (cos (* 0.3 (:z p))))       ;   wandering through space
        offset (- (:xy p) (vec2 cx cy))
        r      (length offset)
        wall   (fbm (vec2 (* 3.0 (atan (:y offset) (:x offset))) (* 0.5 (:z p))))
        d      (abs (- r (+ 1.2 (* 0.3 wall))))   ; distance to the wall
        shine  (/ 0.02 (+ d 0.02))]               ; glow where the ray grazes it
    (+ col (* tint shine 0.06))))
```

This fuses three ideas you already own into one grand finale:

- The **accumulating march** from the nebula above.
- The **wandering centerline** — `cx`/`cy` follow sine curves down the
  `z` axis, so the tunnel snakes through space instead of running straight.
  It's [move the space, not the shape](../language/thinking.md#move-the-space-not-the-shape)
  in 3D: the walls are computed relative to a center that drifts.
- The **reciprocal glow** `0.02 / (d + 0.02)` from the [idioms](idioms.md),
  now measuring distance to a noise-textured tube wall — light blooms
  wherever the ray passes close.

The result is an infinite flight down a churning neon throat, and it's
"just" 40 iterations of add-up-the-glow.

## A note on cost

Every step here evaluates `fbm` (which itself is five octaves of noise),
so cost scales with the loop count. Both examples were tuned down from
higher step counts to stay smooth — the step *size* was widened to cover
the same depth with fewer samples. If yours stutters, lower the count
(`32`, `40`) first; if it looks banded, raise it. That trade — samples vs.
smoothness vs. framerate — is the constant balancing act of volumetric
work.

**Try:** in the nebula, change the `1.5` scales for finer or coarser
clouds; in the wormhole, change `1.2` (the tube radius) or the `0.4`/`0.3`
in the centerline to make it writhe differently. Feed `bass` into the glow
of either for an [audio-reactive](../sound/audio-reactive.md) pulse.
