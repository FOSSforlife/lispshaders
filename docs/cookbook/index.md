# Cookbook

The gallery dropdown isn't a showcase — it's a curriculum. Each example
was written to teach one technique, and the fastest way to learn is to
load one, read it top to bottom, and **break it**: change a number, watch
the canvas, change it back. Nothing here can crash and nothing is
precious — the worst that happens is a black screen and an undo.

If you haven't read [Thinking in shaders](../language/thinking.md) yet, go
do that first — every recipe in this section is one of its ideas made
concrete. The three moves it names — **transform the space**, **shape a
falloff**, **fold or repeat** — are all you'll see below, recombined.

## How to read this section

The pages are ordered like a course: each builds on the mindset of the
last. If you're new, walk them in order. If you're hunting a specific
trick, jump straight in — every page stands alone and links back to
whatever it assumes.

| Page | The idea you'll steal |
|---|---|
| [Coordinates & color](coordinates-and-color.md) | Every shader is *compute a number, then color it* |
| [Summing fields](summing-fields.md) | Interference: simple fields added together get complex |
| [Domain warping](domain-warping.md) | Feed a function its own output for organic texture |
| [Glowing fields](glowing-fields.md) | Turn a noise field into bands and lines of light |
| [Faking light](faking-light.md) | Fake a 3D surface from a flat height field |
| [Tunnels](tunnels.md) | Polar coordinates turn the plane into infinite depth |
| [Hyperspace](hyperspace.md) | Give every cell of space its own random character |
| [Raymarched blobs](raymarched-blobs.md) | A whole 3D world as one distance function |
| [Volumetric marching](volumetric-marching.md) | March *through* a volume and accumulate light |
| [Fractals](fractals.md) | Infinite detail from a rule repeated |
| [The four elements](four-elements.md) | Fire, water, earth & air — old moves, recombined |
| [Drone garden](drone-garden.md) | One program, projected to both image and sound |

And once the patterns are in your hands, keep the
[small idioms](idioms.md) within reach — the one-liners that show up in
almost every shader.

## The workflow, in one sentence

Build a small system of composed functions, then *play* it. The app
recompiles on every keystroke for exactly this reason: you're not typing
a finished image, you're tuning a live one. Follow the accidents — in a
world where every pixel is pure math, a surprise on screen is never a
mystery, just a beautiful line of code waiting to be understood.
