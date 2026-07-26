# Hyperspace

Warp speed: streaks of light stretching past you toward the vanishing
point. Under the hood it's a pattern you'll use constantly — **carve space
into cells, then give each cell its own random number.** That one idea
powers starfields, brick walls, city windows, confetti, and here, the jump
to lightspeed.

## hyperspace — hashing cells for variety

```clojure
(let [pol    (cartopol uvc)                  ; (radius, angle)
      a      (:y pol)
      r      (:x pol)
      cell   (floor (* 60.0 (/ a tau)))      ; which of 60 angular slices?
      seed   (hash1 cell)                    ; that slice's private random number
      streak (fract (+ (* seed 10.0) (* speed (+ 0.5 seed)) (* 0.2 r)))
      star   (pow (smoothstep 0.7 1.0 streak) 3.0)]
  (* tint (* star (smoothstep 0.0 0.6 r)) 2.0))
```

The engine is two lines. `(floor (* 60.0 (/ a tau)))` divides the circle
into 60 wedges and gives every pixel the integer id of the wedge it's in.
`(hash1 cell)` turns that id into a stable pseudo-random value in `0..1` —
the same wedge always hashes to the same number, so each streak keeps its
own identity frame after frame.

From that seed everything follows: each streak starts at a random phase
(`* seed 10.0`), travels outward at its own speed (`* speed (+ 0.5 seed)`),
and `fract` loops it endlessly so the warp never stops. `smoothstep`
carves the bright head of each streak; the outer `(smoothstep 0.0 0.6 r)`
fades the crowded center where the wedges pinch together.

## The cell-and-hash pattern

This is the [grid-of-cells idiom](idioms.md) applied radially, and it's
worth lifting out of the tunnel context because it's *everywhere*:

```clojure
(floor p)          ; the cell's id (here we used floor of an angle)
(fract p)          ; position within the cell, 0..1
(hash1 (floor p))  ; the cell's own random value
(hash2 (floor p))  ; ...from a vec2 id, for a 2D grid
```

Whenever you want "many similar things, each slightly different, without
copy-paste," this is the move: floor to name the cell, `hash` to
characterize it, `fract` to draw inside it. It pairs naturally with the
polar setup from [Tunnels](tunnels.md) — here the "grid" is a fan of
angular slices instead of a square lattice.

The `hash1` / `hash2` functions and what they return are in the [builtin
reference](../language/reference.md).

**Try:** change `60.0` for more or fewer streaks; drop the `pow 3.0` to
`1.0` for long soft trails instead of sharp stars; use `hash1` to tint
each streak a different [palette](coordinates-and-color.md#cosine-waves-a-color-system-not-a-color)
color.
