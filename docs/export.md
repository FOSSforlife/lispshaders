# Exporting shaders

There's no export _step_ — both output tabs update live on every compile,
and each has a **copy** button.

## The GLSL tab

Exactly what your code compiled to: a complete GLSL ES 3.0 fragment
shader, including the prelude helpers, ready for any WebGL2 context.

It's deliberately readable. Your names survive (`glow-amount` →
`glow_amount`), your `let` structure becomes declarations in the same
order, and user functions appear inlined where you called them. Two good
reasons to keep this tab open even if you never export:

- **It's the ground truth when something surprises you.** If a shader
  doesn't do what you expect, reading its GLSL usually shows why.
- **It's a GLSL tutor.** If you're using this app to learn shader
  programming in general, seeing every Lisp construct's translation —
  `iter` becoming a `for` loop, `if` becoming a ternary — teaches the
  target language for free.

## The Shadertoy export tab

The same shader re-targeted to [shadertoy.com](https://shadertoy.com)'s
conventions — `mainImage`/`fragCoord`, `iTime`, `iMouse`, `iResolution`.
To publish:

1. Copy the tab's contents.
2. On Shadertoy, create a **New Shader** and replace the default code.
3. Compile (++alt+enter++ or the ▶ button below the editor).

The wrapper re-derives `uv`, `uvc`, and `mouse` from Shadertoy's inputs,
so coordinates match what you saw locally.

### If your shader uses audio

The audio inputs are re-spelled as **iChannel0 audio-texture reads**:
Shadertoy renders any audio input as a texture whose bottom row is the
frequency spectrum, and the export samples `bass`, `mid`, and `treble`
from its low, middle, and high ends (`level` and `audio` are rebuilt from
those three).

To make it live: below the Shadertoy code editor, click **iChannel0** and
choose something audible — SoundCloud, one of the built-in music tracks,
or the microphone.

One honest difference from running locally: **the band values are
analyzed differently** (Shadertoy's spectrum texture vs. this app's
smoothed FFT bands), so reactivity will be in the same spirit but not
identical — expect to re-tune multipliers.

And of course, only the shader exports — `defsound` has no Shadertoy
equivalent. Feed the exported shader _any_ audio on iChannel0 and it
reacts to that instead.

## Capturing video & audio

Deliberately out of scope, for both projections: to keep a piece, screen
record it (macOS: ++cmd+shift+5++; add a system-audio loopback device if
you want the drone in the capture). Pausing shader time (**⏸**) and
rewinding (**⟲ t=0**) help line up a clean take, and motion built from
`sin`/`fract` of time [loops seamlessly](cookbook/idioms.md#small-idioms-to-keep-in-your-pocket)
for perfect GIF-style cycles.
