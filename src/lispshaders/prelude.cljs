(ns lispshaders.prelude
  "The shader 'standard library': hand-written GLSL helpers that every
   compiled shader can call, plus the signature table the compiler uses to
   type-check calls to them, plus the reference-card data shown in the UI.")

(def glsl-helpers
  ;; Helper functions injected into every compiled fragment shader — the
  ;; classic building blocks of creative shader work (hash noise, fbm,
  ;; cosine palettes, signed-distance primitives).
  "// ---- lispshaders prelude ----------------------------------------------
float hash1(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}

float hash2(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// value noise over a 2d lattice
float noise2(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash2(i),                 hash2(i + vec2(1.0, 0.0)), u.x),
             mix(hash2(i + vec2(0.0, 1.0)), hash2(i + vec2(1.0, 1.0)), u.x),
             u.y);
}

// five octaves of value noise, rotated each octave to hide the grid
float fbm(vec2 p) {
  float value = 0.0;
  float amplitude = 0.5;
  mat2 rotate = mat2(0.8, 0.6, -0.6, 0.8);
  for (int i = 0; i < 5; i++) {
    value += amplitude * noise2(p);
    p = rotate * p * 2.0;
    amplitude *= 0.5;
  }
  return value;
}

vec3 hsv2rgb(vec3 c) {
  vec3 rgb = clamp(abs(mod(c.x * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
  return c.z * mix(vec3(1.0), rgb, c.y);
}

mat2 rot2(float a) {
  float s = sin(a);
  float c = cos(a);
  return mat2(c, s, -s, c);
}

// Inigo Quilez's cosine color palette
vec3 palette(float t, vec3 a, vec3 b, vec3 c, vec3 d) {
  return a + b * cos(6.283185307179586 * (c * t + d));
}

float sdSphere(vec3 p, float r) {
  return length(p) - r;
}

float sdBox(vec3 p, vec3 b) {
  vec3 q = abs(p) - b;
  return length(max(q, 0.0)) + min(max(q.x, max(q.y, q.z)), 0.0);
}

float sdTorus(vec3 p, vec2 t) {
  vec2 q = vec2(length(p.xz) - t.x, p.y);
  return length(q) - t.y;
}

// polynomial smooth-minimum: blends two distance fields together
float smin(float a, float b, float k) {
  float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
  return mix(b, a, h) - k * h * (1.0 - h);
}

// MIDI note number -> frequency in Hz (A4 = 69 = 440Hz), Overtone-style
float midiToHz(float m) {
  return 440.0 * exp2((m - 69.0) / 12.0);
}
// ---- end prelude -------------------------------------------------------")

(def functions
  "Prelude functions callable from the Clojure DSL.
   Maps DSL symbol -> {:glsl-name .. :params [types] :return type}."
  {'hash1     {:glsl-name "hash1"    :params [:float] :return :float}
   'hash2     {:glsl-name "hash2"    :params [:vec2]  :return :float}
   'noise     {:glsl-name "noise2"   :params [:vec2]  :return :float}
   'fbm       {:glsl-name "fbm"      :params [:vec2]  :return :float}
   'hsv->rgb  {:glsl-name "hsv2rgb"  :params [:vec3]  :return :vec3}
   'rot2      {:glsl-name "rot2"     :params [:float] :return :mat2}
   'palette   {:glsl-name "palette"  :params [:float :vec3 :vec3 :vec3 :vec3] :return :vec3}
   'sd-sphere {:glsl-name "sdSphere" :params [:vec3 :float] :return :float}
   'sd-box    {:glsl-name "sdBox"    :params [:vec3 :vec3]  :return :float}
   'sd-torus  {:glsl-name "sdTorus"  :params [:vec3 :vec2]  :return :float}
   'smin      {:glsl-name "smin"     :params [:float :float :float] :return :float}
   'midi->hz  {:glsl-name "midiToHz" :params [:float] :return :float}})

(def audio-functions
  "Drone builtins callable from the Clojure DSL inside defsound bodies.
   Maps DSL symbol -> {:js-name .. :params [types] :return type}, where the
   implementations live in lispshaders.audio-lib and build Elementary Audio
   nodes. A `:float` here is a mono signal; the pseudo-type `:sig` accepts
   a float or a vec2 (stereo pair) and a `:sig` return keeps that shape —
   so the same filter call works on mono and stereo alike."
  {;; oscillators — the raw material of a drone
   'osc      {:js-name "osc"      :params [:float] :return :float}
   'saw      {:js-name "saw"      :params [:float] :return :float}
   'square   {:js-name "square"   :params [:float] :return :float}
   'tri      {:js-name "tri"      :params [:float] :return :float}
   'phasor   {:js-name "phasor"   :params [:float] :return :float}
   'noise    {:js-name "noise"    :params []       :return :float}
   'pink     {:js-name "pink"     :params []       :return :float}
   ;; uploaded audio (the samples tab); :sample-name is a string literal
   'sample   {:js-name "sample"   :params [:sample-name :float] :return :float}
   'scrub    {:js-name "scrub"    :params [:sample-name :float] :return :float}
   ;; slow modulators — what makes a drone breathe
   'lfo      {:js-name "lfo"      :params [:float] :return :float}
   'wander   {:js-name "wander"   :params [:float] :return :float}
   'slew     {:js-name "slew"     :params [:float :sig] :return :sig}
   ;; sculpting
   'lowpass  {:js-name "lowpass"  :params [:float :float :sig] :return :sig}
   'highpass {:js-name "highpass" :params [:float :float :sig] :return :sig}
   'bandpass {:js-name "bandpass" :params [:float :float :sig] :return :sig}
   'drive    {:js-name "drive"    :params [:float :sig] :return :sig}
   ;; space
   'echo     {:js-name "echo"     :params [:float :float :sig] :return :sig}
   'pan      {:js-name "pan"      :params [:float :float] :return :vec2}
   'space    {:js-name "space"    :params [:float :sig] :return :vec2}
   ;; pitch math & hashes (also available in shaders via the GLSL prelude);
   ;; the hashes want plain numbers — great for detuning iter partials
   'midi->hz {:js-name "midiToHz" :params [:float] :return :float}
   'hash1    {:js-name "hash1"    :params [:float] :return :float}
   'hash2    {:js-name "hash2"    :params [:vec2]  :return :float}})

(def reference-groups
  "Data for the in-app reference card. Each group is a titled list of
   {:sig .. :doc ..} entries covering everything the DSL understands."
  [{:title "Getting started"
    :entries
    [{:sig "(defn image [uv] ...)" :doc "Your shader's entry point. Called per-pixel; return a color (vec3 or vec4, components 0..1)."}
     {:sig "(defn name [args] body)" :doc "Define a helper function (inlined into the shader)."}
     {:sig "(def name expr)" :doc "Define a top-level value, computed once per pixel."}
     {:sig "(let [a 1.0 b (* a 2.0)] ...)" :doc "Local bindings; later bindings see earlier ones."}
     {:sig "(defparam name 0.6 0.0 1.0)" :doc "A live knob (like a REAPER param / Ableton macro). Use its name anywhere a number fits, in the shader or a drone, and turn the dial in the params tab — no recompile. Short form (defparam name 0.5) is a 0..1 knob."}]}

   {:title "Coordinates & inputs"
    :entries
    [{:sig "uv" :doc "This pixel's position, 0..1 on both axes (bottom-left origin)."}
     {:sig "uvc" :doc "Centered, aspect-correct coords: y spans -1..1, x spans ±aspect. Best for most effects."}
     {:sig "time" :doc "Seconds since the shader started (pausable from the toolbar)."}
     {:sig "(ramp rate)" :doc "A 0..1 sawtooth restarting rate times a second — the shader counterpart of a drone's phasor. Drives cycles: (mix a b (ramp 0.5))."}
     {:sig "resolution" :doc "Canvas size in pixels, as a vec2."}
     {:sig "mouse" :doc "Mouse position over the canvas, 0..1 on both axes."}
     {:sig "pi, tau" :doc "3.14159..., 6.28318..."}
     {:sig "bass, mid, treble, level" :doc "Live energy 0..1 of the playing drone, band by band (also as vec4 `audio`) — the bridge from sound to image."}]}

   {:title "Drones"
    :entries
    [{:sig "(defsound name signal)" :doc "A drone is a signal graph — a shader for your ears. Produce a float (mono) or vec2 (stereo, -1..1). Every defsound is one layer of the soundscape; edits morph the sound live without restarting it."}
     {:sig ":a2, :c#3, :eb1" :doc "Note keywords are frequencies: :a2 compiles to 110.0. Overtone-style, usable anywhere a number fits — (saw :a1)."}
     {:sig "(midi->hz m), (hz->midi f)" :doc "Convert between MIDI note numbers and Hz (69 is 440.0). Go to note-space to do pitch math — (-> f hz->midi (quant 1.0) midi->hz) snaps a glide to semitones."}
     {:sig "(osc f) (saw f) (square f) (tri f)" :doc "Oscillators at f Hz: sine, and anti-aliased saw/square/triangle. Detune a stack for width: (+ (saw f) (saw (* f 1.003)))."}
     {:sig "(noise) (pink) (phasor f)" :doc "White noise, mellower pink noise, and a raw 0..1 ramp at f Hz."}
     {:sig "(lfo rate), (wander rate)" :doc "Slow modulators, 0..1: a sine breath and a smooth random drift. Patch them into any parameter — that's what makes a drone feel alive."}
     {:sig "(lowpass cutoff q sig)" :doc "Filters (also highpass, bandpass). Signal-last, so they thread: (->> (saw :a1) (lowpass 400 1.2)). Sweep cutoff with an lfo!"}
     {:sig "(drive amount sig), (slew secs sig)" :doc "Warm tanh saturation (0..1); and a smoother that eases a jumpy control signal over secs."}
     {:sig "(echo ms feedback sig)" :doc "Feedback delay — long echoes (500ms+, feedback ~0.6) turn anything into a wash."}
     {:sig "(pan pos sig), (space amount sig)" :doc "Stereo: pan places a mono signal 0..1 left-to-right; space diffuses it into a wide reverberant vec2. Both return stereo pairs."}
     {:sig "(if (< (lfo 0.1) 0.5) a b)" :doc "Comparisons and if work on signals — they compile to gates and crossfades, not branches."}
     {:sig "(iter [i 8] [sum 0.0] ...)" :doc "iter unrolls into the graph: stack 8 partials, 8 detuned saws, 8 echo taps. (hash1 i) gives each layer its own stable random detune."}
     {:sig "drones tab" :doc "Every defsound gets a card with a live level meter and mute/solo — sculpt the mix layer by layer."}]}

   {:title "Your uploads"
    :entries
    [{:sig "samples / images tabs" :doc "Drop audio and image files into the app. They live in your browser (like the code autosave) and are referred to by name."}
     {:sig "(sample \"name\" rate)" :doc "Loop an uploaded audio file as a signal, inside a defsound. Mixed to mono; rate 1.0 is original speed, 0.5 an octave down — and rate can be a signal."}
     {:sig "(scrub \"name\" pos)" :doc "Read an uploaded audio file at position 0..1 — sweep pos with (phasor ...) or (wander ...) for granular, frozen-in-time drones."}
     {:sig "(texture \"name\" uv)" :doc "Read an uploaded image as a vec4 color in the shader: (:rgb (texture \"photo\" uv)). Coordinates wrap, so the image tiles — warp uv and the photo melts."}]}

   {:title "Vectors"
    :entries
    [{:sig "(vec2 x y), (vec3 r g b), (vec4 ...)" :doc "Build vectors. One scalar arg splats: (vec3 0.5) => (0.5, 0.5, 0.5)."}
     {:sig "(:x v), (:xy v), (:zyx v)" :doc "Keyword swizzles read/reorder components (xyzw or rgba, any combo)."}
     {:sig "(length v), (distance a b)" :doc "Euclidean length / distance."}
     {:sig "(dot a b), (cross a b)" :doc "Dot product (any vec), cross product (vec3)."}
     {:sig "(normalize v)" :doc "v scaled to length 1."}
     {:sig "(reflect i n), (refract i n eta)" :doc "Optics helpers; n should be normalized."}
     {:sig "(rot2 angle)" :doc "2x2 rotation matrix: (* (rot2 a) v) spins a vec2."}]}

   {:title "Math"
    :entries
    [{:sig "(+ - * /)" :doc "Work on scalars, vectors (componentwise), scalar+vector, and matrix*vector."}
     {:sig "(sin cos tan asin acos atan)" :doc "Radians. (atan y x) gives the angle of a point."}
     {:sig "(pow x y), (sqrt x), (exp x), (log x)" :doc "Also exp2/log2/inversesqrt."}
     {:sig "(abs x), (sign x), (floor x), (ceil x), (fract x)" :doc "(fract x) is the endlessly useful 'repeat' operator."}
     {:sig "(mod x y), (min a b ...), (max a b ...)" :doc "mod is GLSL-style (always positive for positive y)."}
     {:sig "(rsub m n), (rdiv m n)" :doc "Reversed subtract/divide: n - m and n / m. Made for threading — (-> x (rdiv 6.0)) is 6.0/x, (-> x (rsub 1.0)) is 1.0-x."}
     {:sig "(scale x in-min in-max out-min out-max)" :doc "Remap x from one range to another, like Max's [scale]: (scale s 0.0 1.0 20.0 200.0) turns 0..1 into 20..200."}
     {:sig "(bi->uni x), (uni->bi x)" :doc "Convert between bipolar -1..1 and unipolar 0..1 — (bi->uni (sin t)) is an oscillator you can use as brightness."}
     {:sig "(wrap x lo hi), (fold x lo hi)" :doc "Force x into lo..hi — wrap jumps back to the far edge, fold reflects off it. gen~'s pair; fold is a wavefolder in a drone and a mirror in a shader."}
     {:sig "(quant x step)" :doc "Snap x down to a grid of step-sized rungs: (quant x 0.1) gives 10 bands. Posterizes color, staircases time, quantizes pitch."}
     {:sig "(clamp x lo hi)" :doc "Constrain x to [lo, hi]."}
     {:sig "(mix a b t)" :doc "Linear blend from a to b by t — the workhorse of shader art."}
     {:sig "(step edge x)" :doc "0.0 when x < edge, else 1.0 — hard threshold."}
     {:sig "(smoothstep e0 e1 x)" :doc "Smooth 0->1 ramp between two edges — soft threshold / antialiasing."}]}

   {:title "Flow control"
    :entries
    [{:sig "(if cond then else)" :doc "Both branches are computed (GPU-style); cond comes from < > <= >= = not=."}
     {:sig "(cond c1 e1 c2 e2 :else e)" :doc "Multi-way if; :else is required."}
     {:sig "(and ...) (or ...) (not x)" :doc "Boolean logic for conditions."}
     {:sig "(iter [i 64] [t 0.0] body)" :doc "Run body 64 times; each pass rebinds t to body's value; the whole form is the final t. i is the loop index."}
     {:sig "(iter [i 64] [a 0.0 b 1.0] [a' b'] result)" :doc "Multiple state vars: body returns a vector of updates; optional final result expression."}
     {:sig "(-> x f (g 2.0)), (->> ...)" :doc "Thread-first / thread-last, just like Clojure."}]}

   {:title "Creative kit"
    :entries
    [{:sig "(hash1 x), (hash2 v)" :doc "Deterministic pseudo-random float in 0..1 from a float / vec2."}
     {:sig "(noise v)" :doc "Smooth value noise over a vec2 (~0..1)."}
     {:sig "(fbm v)" :doc "Fractal noise (5 octaves) — instant clouds, terrain, texture."}
     {:sig "(palette t a b c d)" :doc "IQ's cosine palette. Try (palette t (vec3 0.5) (vec3 0.5) (vec3 1.0) (vec3 0.0 0.33 0.67))."}
     {:sig "(hsv->rgb (vec3 h s v))" :doc "Hue/saturation/value to RGB — easy rainbows."}
     {:sig "(sd-sphere p r), (sd-box p b), (sd-torus p (vec2 R r))" :doc "Signed-distance primitives for raymarching."}
     {:sig "(smin a b k)" :doc "Smooth minimum — melts two distance fields together."}]}])
