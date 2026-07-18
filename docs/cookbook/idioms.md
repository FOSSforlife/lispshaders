# Small idioms to keep in your pocket

Every example in this section is built from a handful of one-liners that
recur everywhere. Here they are in one place — the shader artist's muscle
memory. None is more than a few characters; all reward being recognized on
sight.

| You want | Write | Why it works |
|---|---|---|
| Repeat anything infinitely | `(fract p)` or `(- (fract p) 0.5)` | Tiles space before your shape-question is asked; the centered form puts the shape mid-tile |
| Mirror symmetry | `(abs p)` | Folds space onto one quadrant — draw once, get four. [Warp gate](tunnels.md#warp-gate-kaleidoscope-by-folding-the-angle) folds a circle this way |
| Soft glow from a shape | `(/ 0.05 (+ (abs d) 0.02))` | Brightness as inverse distance; the `0.02` stops it blowing up at zero. Used in [caustics](summing-fields.md), [tunnels](tunnels.md), and the [wormhole](volumetric-marching.md) |
| Crisp-but-antialiased edge | `(smoothstep 0.51 0.49 d)` | A two-pixel ramp instead of a hard `step`; note the flipped edges invert the result |
| Anything that decays or feathers | `(exp (* -k t))` | The universal envelope — glow falloff, fog, [aurora bands](glowing-fields.md), [orbit-trap glow](fractals.md); `k` is snappiness |
| Sharpen a `0..1` value | `(pow x n)` for `n > 1` | Crushes the low end toward zero, leaves peaks bright — instant contrast, no branching. See [glowing contours](glowing-fields.md) |
| Motion that loops perfectly | `(sin (* tau (fract time)))` or just `sin`/`cos` of time | Periodic functions can't drift — good for screen-recorded loops |
| A grid of cells, each unique | `(floor p)` for the cell id, `(fract p)` for position within it | Then `(hash2 (floor p))` gives each cell its own random number — the [hyperspace](hyperspace.md) engine |
| Stripes / rings / contours | `(sin (* freq value))` or `(fract (* freq value))` | Any periodic function of a smooth quantity carves it into repeating bands |
| A surface normal from a field | sample at `±e`, subtract | Finite-difference slope — works flat ([faking light](faking-light.md)) and in 3D ([raymarching](raymarched-blobs.md)) |
| Vignette | `(- 1.0 (* 0.5 (dot uvc uvc)))` | Distance-squared falloff, multiplied over the final color |
| Gamma-ish punch | `(pow col (vec3 0.4545))` | Cheap linear→display correction; brightens the midtones |

Every one of these is a **space transformation**, a **falloff**, or a
**fold** — the three moves that, composed, are most of shader art. That's
not a coincidence; it's the thesis of
[Thinking in shaders](../language/thinking.md), and now you've seen it
proven a dozen times over.

Where to go from here:

- Keep breaking the gallery — [pick any example](index.md) and change one
  number.
- When something errors, the [errors & Clojure differences](../language/errors.md)
  page decodes the message.
- Ready to make it move to sound? Start at [the drones' big
  idea](../sound/index.md).
- Want your shader out of the sandbox? [Export it](../export.md) to
  Shadertoy or capture a video.
