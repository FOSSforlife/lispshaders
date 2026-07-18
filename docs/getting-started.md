# Getting started

## Install and run

You need Node.js (the toolchain is [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html),
installed as a dev dependency — no global Clojure setup required).

```sh
npm install
npm run dev      # then open http://localhost:8021
```

Other useful commands:

| Command                                            | What it does                                                               |
| -------------------------------------------------- | -------------------------------------------------------------------------- |
| `npm test`                                         | Runs the compiler test suite in Node — no browser needed                   |
| `npm run build`                                    | Optimized release build into `public/js`                                   |
| `npx shadow-cljs compile dump && node out/dump.js` | Writes every gallery example's compiled GLSL to `out/glsl/` for inspection |

To serve _these docs_ locally:

```sh
pip install mkdocs-material
mkdocs serve
```

## A tour of the app

The window has four regions:

- **Toolbar** (top) — the example gallery dropdown, **⏸ pause / ▶ play**
  (freezes shader time), **⟲ t=0** (rewinds time), **◉ start drone**
  (appears only when your program has a `defsound`), and a live
  `seconds · fps` readout.
- **Editor** (left) — a CodeMirror pane with Clojure highlighting. The
  shader recompiles about 250 ms after you stop typing; there is no
  "run" button.
- **Canvas** (top right) — the live WebGL2 output. Move the mouse over it
  to feed the `mouse` input.
- **Tabs** (bottom right):
  - **reference** — a searchable card of everything the language
    understands, always at hand.
  - **drones** — one card per `defsound`: a live level meter and
    mute/solo for sculpting the mix.
  - **GLSL** — exactly what your code compiled to (deliberately
    readable; your names survive).
  - **Shadertoy export** — the same shader re-targeted for
    shadertoy.com. See [Exporting shaders](export.md).
- **Status bar** (bottom) — `● live` when everything compiles, or the
  full error message when it doesn't. Errors never kill the canvas: the
  last good shader keeps running while you fix things.

## Your first shader

Pick **hello uv** from the gallery dropdown (it's the default) and read it:

```clojure
;; `image` runs once per pixel; return a color (vec3/vec4, 0..1).
(defn image [uv]
  (vec3 (:x uv)
        (:y uv)
        (+ 0.5 (* 0.5 (sin time)))))
```

Three ideas, all visible at once:

1. **`image` is the entry point.** It is called for every pixel with that
   pixel's `uv` coordinate (0..1 on both axes) and must return a color.
2. **Keywords are swizzles.** `(:x uv)` reads the x component — the Clojure
   habit of keywords-as-accessors, pointed at vectors.
3. **`time` just exists.** Along with `mouse`, `resolution`, and friends —
   see [Inputs & coordinates](language/inputs.md).

Now break it on purpose: change `(:x uv)` to `(:x wat)`. The status bar
says `Unknown symbol: wat`, the canvas keeps showing the last good frame,
and fixing the typo brings the compile back. That edit-see-tweak loop is
the whole workflow.

## Your first drone

Add one form to any shader:

```clojure
(defsound hum
  (->> (+ (saw :a1) (saw 55.4))
       (lowpass (+ 300.0 (* 700.0 (lfo 0.1))) 1.2)
       (* 0.3)))
```

Press **◉ start drone** in the toolbar. (Browsers require a click before
audio can start, which is why it always begins from a button.) You'll
hear a low, slowly breathing tone: two detuned saws beating against each
other under a filter swept by a ten-second LFO.

Now — while it plays — change `0.1` to `0.3`. The breathing speeds up.
Change `:a1` to `:c2`. The pitch moves. **The sound never stops or
restarts**; like the shader, it's a running program you're sculpting.
Open the **drones** tab to see `hum`'s live level meter (with several
drones you get mute/solo per layer).

To make the visuals react, read `bass` or `level` anywhere in your shader:

```clojure
(defn image [uv]
  (vec3 (* bass (- 1.0 (length uvc)))))
```

The **drone garden** example in the gallery puts all of this together —
it's the recommended second stop after hello uv.

## Where your code lives

The editor's contents are saved to `localStorage` under the key
`lispshaders/code` on every compile. Loading a gallery example replaces
the buffer (and therefore the autosave), so copy anything you care about
somewhere safe before switching examples.

## Next steps

If shaders are brand new to you, the gentlest next step is the
[Tutorial: your first effects](tutorial.md) — a dozen tiny, hands-on
exercises (zoom, flip, mirror, tile, pixelate, warp) you type one at a
time. For the mental reorientation everything else builds on, read
[Thinking in shaders](language/thinking.md). Then work through the
[Cookbook](cookbook/index.md) with the gallery open, and keep the
[Builtin reference](language/reference.md) (or the in-app reference tab)
within reach.
