# The four elements

Four small shaders — **fire**, **water**, **earth**, **air** — that
don't introduce a single new idea. Every one is built from moves you've
already met: a [warped noise field](domain-warping.md), a
[falloff](glowing-fields.md), a [fake normal](faking-light.md). They're
here to prove the point one more time: naming what you want to _see_, then
reaching for the matching move, is the whole craft. Load each, then ask
"which recipe is this?" — the answer is always in an earlier page.

## fire — a mask carves a flame out of noise

```clojure
(let [x (- (:x uv) 0.5)
      drift (vec2 (* 0.15 (sin (* 1.7 time))) (* -1.3 time))
      turb (abs (- (* 2.0 (fbm (+ (* (vec2 x (:y uv)) (vec2 4.0 3.0)) drift))) 1.0))
      heat (clamp (- (+ 1.2 (* 0.5 turb)) (* 1.7 (:y uv)) (* 3.0 x x)) 0.0 1.0)
      col (mix (mix (vec3 0.0) (vec3 1.0 0.2 0.0) (smoothstep 0.0 0.4 heat))
               (vec3 1.0 0.9 0.5) (smoothstep 0.55 1.0 heat))]
  (* col heat))
```

Two ideas do all the work. First, **the field scrolls downward**
(`drift` has a negative `y`) so the eye reads the motion as rising — a
flame is just turbulence flowing the "wrong" way. Second, **a height mask
shapes it**: `heat` starts hot, then subtracts `(* 1.7 (:y uv))` to cool
with altitude and `(* 3.0 x x)` to pinch in the sides, so the noise only
survives as a tapering column. The `(abs (- (* 2.0 fbm) 1.0))` is the
[turbulence idiom](domain-warping.md) — folding noise around its midpoint
gives the sharp creases that read as licking tongues. Color is a plain
two-step `mix` up a hot ramp; nothing fancy earns the fire.

**Try:** flip `drift`'s `y` positive and the flame becomes a falling
ember-storm; widen the column by shrinking `3.0` in the edge term.

## water — a surface is a height field, lit

```clojure
(defn waves [p]
  (+ (sin (+ (* 3.0 (:x p)) (* 2.0 time)))
     (* 0.7 (sin (+ (* 4.0 (:y p)) (* 1.6 time))))
     (* 0.5 (sin (+ (* 2.5 (+ (:x p) (:y p))) (* 2.3 time))))
     (* 0.6 (fbm (+ (* p 2.0) (vec2 (* 0.2 time) 0.0))))))
```

The surface is a [summed field](summing-fields.md): three directional
sine ripples (each a different heading and speed) plus a little `fbm` to
break the regularity. Then it's lit exactly like [liquid
chrome](faking-light.md) — sample `waves` at `±e`, difference the samples
into a **finite-difference normal**, and read light off that surface:

```clojure
fresnel (pow (- 1.0 (max (:z n) 0.0)) 3.0)          ; edge-on = sky-bright
col     (mix (vec3 0.0 0.15 0.3) (vec3 0.6 0.85 1.0) fresnel)
spec    (pow (max (dot n light) 0.0) 32.0)          ; the sun's glint
```

The **Fresnel** term is the only new face, and it's just a falloff: water
seen face-on is dark and deep; water seen edge-on mirrors the sky. `(:z n)`
is 1 when the surface faces you, so `(- 1.0 (:z n))` brightens toward
grazing angles. The tight `pow ... 32.0` specular is the same highlight
that sold the metal — here it's a point of sun.

**Try:** add a fourth wave heading; drop the specular exponent to `8.0`
for a broad, hazy sheen instead of a sharp glint.

## earth — warp turns flat stripes into geology

```clojure
(let [p (* uvc 1.5)
      warp (fbm (+ (* p 1.2) (vec2 (* 0.02 time) 0.0)))
      y (+ (:y p) (* 0.8 warp))
      band (floor (* 3.0 y))
      edge (fract (* 3.0 y))
      base (palette (* 0.15 band) ...)
      seam (smoothstep 0.0 0.09 edge)]
  (* (+ base grain) (+ 0.55 (* 0.45 seam))))
```

Start with the most boring thing imaginable: horizontal stripes, made by
`floor`/`fract` of the height — the [cell idiom](hyperspace.md), one
dimension. `floor` numbers each rock layer (feeding a per-band earthy
`palette`), `fract` is the position inside it. Flat stripes would look
like a barcode; the single move that makes it _rock_ is
[domain-warping](domain-warping.md) the height first: adding `(* 0.8 warp)`
to `y` before slicing makes every stratum buckle, pinch, and fault the way
sediment actually does. The dark `seam` — a `smoothstep` that's black only
right at each layer boundary — settles shadow into the bedding planes.

**Try:** raise `3.0` for thinner strata; push the warp amount past `0.8`
until the layers fold back on themselves into folded, metamorphic rock.

## air — advection, and streaks fall out of it

```clojure
(let [t (* 0.15 time)
      q (vec2 (fbm (+ p (vec2 t 0.0)))
              (fbm (+ p (vec2 5.2 1.3) (vec2 0.0 (* 0.5 t)))))
      flow (fbm (+ (* p (vec2 3.0 1.0)) (* 2.0 q) (vec2 (* 2.0 t) 0.0)))
      streaks (pow (- 1.0 (abs (- (* 2.0 (fract (* 3.0 flow))) 1.0))) 3.0)
      sky (mix (vec3 0.3 0.45 0.65) (vec3 0.85 0.9 1.0) (:y uv))]
  (+ sky (* (vec3 1.0) streaks 0.4)))
```

This is [domain warping](domain-warping.md) with a thumb on the scale.
The `(vec2 3.0 1.0)` scale stretches the field three times harder
horizontally than vertically, so features smear sideways — and the drift
`(vec2 (* 2.0 t) 0.0)` pushes everything downwind. That directional bias
is all it takes to turn generic organic noise into _moving cloud_. The
`streaks` line is the [contour trick](glowing-fields.md): `fract` of the
warped field slices it into bands, and `(- 1.0 (abs (- ... 1.0)))` peaks
along each band's center, giving thin feathered filaments of wind over a
plain vertical-gradient sky.

**Try:** raise the horizontal scale to `(vec2 6.0 1.0)` for a howling gale;
feed `treble` into the drift so the wind [gusts to a
drone](../sound/audio-reactive.md).

## Each element has a voice

Every one of these carries a `defsound`, so press **◉ start drone** and the
element sounds as well as looks — and each drone is the same idea in the
[sound domain](../sound/index.md). **Fire** is broadband noise shaped by a
wandering [bandpass](../sound/defsound.md#sculpting-filters-and-drive) (the
roar), a driven sub (the weight), and sputtering highs (the crackle).
**Water** is filtered noise given a current by a wandering filter, drowned
in [`space`](../sound/defsound.md#space-echo-pan-and-the-wash). **Earth** is
a detuned sub-oscillator stack ground through `drive`. **Air** is highpassed
pink noise with a high-Q bandpass that [wanders](../sound/movement.md) into a
whistle when the wind gusts.

And the loop closes: each shader _listens_ to its own drone through
[audio analysis](../sound/audio-reactive.md). `bass` heaves the fire and the
earth's strata; `treble` flares the water's glint and the wind's filaments.
The sound you shape plays back on screen — one program, [projected to
both](drone-garden.md).

---

Four elements, zero new machinery. That's the payoff of the whole
cookbook: once _transform the space, shape a falloff, fold or repeat_ are
in your hands, "make it look like X" stops being a mystery and becomes a
question of which two or three you reach for. Keep the
[idioms](idioms.md) close, and go make a fifth element of your own.
