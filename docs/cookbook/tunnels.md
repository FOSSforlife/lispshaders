# Tunnels

The endless glowing tunnel — the one you've seen in a hundred hypnotic
loops — is the purest demonstration of the cookbook's central mindset:
**change the coordinate system, and everything drawn in it changes for
free.** You never build a tube. You ask a flat pattern about *polar*
coordinates instead of Cartesian ones, and depth appears out of nowhere.

These three examples are one idea at three levels. Read them in order.

## disco tunnel — change coordinates, change everything

```clojure
(let [angle (/ (atan (:y p) (:x p)) tau)   ; direction around the center
      depth (/ 0.35 (+ (length p) 0.001))  ; 1 / distance = how far down the tube
      cell  (vec2 (* 10.0 angle) (+ depth (* 1.5 time)))]
  (step 1.0 (mod (+ (floor (:x cell)) (floor (:y cell))) 2.0)))
```

One reframe does all the work. `(atan (:y p) (:x p))` is the **angle** of
a point around the center; `1 / length` is a **depth** that shoots to
infinity as you approach the middle. Feed a plain checkerboard those two
numbers instead of `(x, y)` and the flat pattern wraps into an endless
3D tube — no perspective math, no geometry. The checker is unchanged; only
the space it's asked about is new.

Adding `time` to `depth` doesn't move anything either — it slides *the
coordinate system* forward, and the sensation of flight emerges. This is
[time is just another coordinate](../language/thinking.md#time-is-just-another-coordinate)
in action, and it's why this example is the one
[Thinking in shaders](../language/thinking.md#move-the-space-not-the-shape)
keeps pointing at.

Two more tricks worth stealing here: **`mouse` steering** (subtract the
cursor from `p` and the whole scene follows it) and **`(exp (* -k depth))`
fog** to hide the far end where the pattern gets impossibly dense.

**Try:** `(* 10.0 angle)` sets the stripe count; make the pattern
`(step 1.0 (mod (:y cell) 2.0))` for rushing rings instead of a checker.

## neon tunnel — glowing, noise-churned walls

Swap the hard checker for glow and noise and the same tube becomes molten
neon:

```clojure
(let [a (atan (:y uvc) (:x uvc))
      r (length uvc)
      depth (+ (/ 0.6 (+ r 0.06)) (* 1.5 time))
      n     (fbm (vec2 (* 3.0 (/ a tau)) depth))
      rings (pow (max (sin (* 20.0 depth)) 0.0) 8.0)]
  (* tint (+ (* 0.4 n) rings) (smoothstep 0.0 0.4 r)))
```

Same polar setup as disco tunnel. What's new:

- The walls are textured with [`fbm`](domain-warping.md) sampled in
  `(angle, depth)` space, so the noise wraps seamlessly around the tube
  and streams toward you as `depth` grows.
- `(pow (max (sin ...) 0.0) 8.0)` makes **bright rings**: the `max ... 0.0`
  keeps only the crests of the sine, and `pow 8.0` sharpens them into
  thin bands of light rushing past.
- `(smoothstep 0.0 0.4 r)` fades the exact center to black. Polar tunnels
  have a singularity at `r = 0` (where `1/r` blows up); a smoothstep is
  the clean way to hide it.

**Try:** change `20.0` for the ring frequency; feed `bass` into the ring
brightness for an [audio-reactive](../sound/audio-reactive.md) pulse.

## warp gate — kaleidoscope by folding the angle

The last variation adds one transformation *before* the tunnel: mirror
the angle into wedges.

```clojure
(let [seg   (/ tau 6.0)
      wedge (- (abs (- (mod a seg) (* 0.5 seg))) (* 0.25 seg))
      glow  (/ 0.15 (+ (abs (- (fract (* 2.0 depth)) 0.5)) 0.05))]
  (* tint (+ (* 0.3 n) (* 0.2 glow)) (smoothstep 0.0 0.3 r)))
```

`(mod a seg)` chops the full circle into six equal slices; the `abs`
around the slice's midpoint mirrors each one, so whatever you draw in a
single wedge is reflected six times into a kaleidoscope. **Fold, then
draw** is a whole genre of shader — the same [mirror-with-`abs`
idiom](idioms.md) you'd use for bilateral symmetry, wrapped around a
circle.

The `glow` term is the [reciprocal-glow idiom](idioms.md) again:
`0.15 / (small + distance)` blooms light wherever `(fract (* 2.0 depth))`
crosses its midpoint — evenly spaced rings of bloom sliding down the tube.

**Try:** change the `6.0` in `seg` to fold into a different number of
wedges; the whole symmetry of the piece turns on that one number.

---

All three are the same reframe. Once polar coordinates feel natural,
[Hyperspace](hyperspace.md) shows what happens when you slice that circle
into cells and give each one its own random character.
