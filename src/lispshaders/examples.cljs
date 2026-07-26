(ns lispshaders.examples
  "Starter shaders shown in the gallery dropdown. Each one is written to
   teach a technique: read them top to bottom and tweak numbers as you go.")

(def gallery
  [{:name "hello uv"
    :code
";; Welcome to lispshaders!
;; `image` runs once per pixel; return a color (vec3/vec4, 0..1).
;; Edit anything — the shader recompiles as you type.

(defn image [uv]
  (vec3 (:x uv)
        (:y uv)
        (+ 0.5 (* 0.5 (sin time)))))"}

   {:name "knobs"
    :code
";; defparam makes a live knob — like a REAPER param or an Ableton macro.
;; Open the `params` tab and turn the dials: the image responds instantly,
;; and so does the drone (press ◉ start drone). No recompile — the value
;; just flows in wherever you named it. The two-arg form (defparam x 0.5)
;; is a 0..1 knob; add min/max for any other range.

(defparam speed  0.5 0.0 3.0)   ; how fast the rings breathe
(defparam warp   2.5 0.0 8.0)   ; how much the plane folds
(defparam glow   0.6 0.0 1.0)   ; overall brightness
(defparam pitch 55.0 20.0 220.0) ; the drone's root note, in Hz

(defn image [uv]
  (let [p (* uvc (+ 1.0 warp))
        r (length p)
        rings (sin (- (* 6.0 r) (* speed time)))]
    (* glow
       (palette (+ (* 0.3 rings) (* 0.1 time))
                (vec3 0.5) (vec3 0.5)
                (vec3 1.0) (vec3 0.0 0.33 0.67)))))

;; the same `speed` and `pitch` knobs steer the sound
(defsound hum
  (->> (+ (saw pitch) (saw (* pitch 1.005)))
       (lowpass (+ 200.0 (* 600.0 (lfo speed))) 1.4)
       (* 0.3)
       (space 0.4)))"}

   {:name "drone garden"
    :code
";; A drone is a signal graph — a shader for your ears. Press
;; ◉ start drone in the toolbar, then edit anything: the sound
;; morphs live, it never restarts. Each defsound is one layer
;; (mute/solo them in the `drones` tab). Note keywords are
;; frequencies: :a1 is just another way to write 55.0.

;; Layer 1: a slow-breathing bass bed. Two saws beat against each
;; other, and an lfo sweeps the filter like a tide.
(defsound bed
  (let [f :a1
        fat (+ (saw f)
               (saw (* f 1.006))
               (* 0.5 (square (* f 0.5))))]
    (->> fat
         (lowpass (+ 220.0 (* 500.0 (lfo 0.05))) 1.4)
         (drive 0.25)
         (* 0.3)
         (space 0.35))))

;; Layer 2: shimmering partials. iter unrolls into six sines, each
;; with a hash-picked detune and its own slowly wandering loudness.
(defsound shimmer
  (->> (iter [i 6] [sum 0.0]
         (let [n (+ 1.0 i)
               f (* :a3 n (+ 1.0 (* 0.002 (hash1 n))))
               vol (* 0.1 (wander (+ 0.06 (* 0.05 (hash1 (* 3.0 n))))))]
           (+ sum (* vol (osc f)))))
       (echo 700.0 0.45)
       (pan 0.65)))

;; Layer 3: filtered air, drifting across the stereo field.
(defsound air
  (->> (pink)
       (bandpass (+ 800.0 (* 2200.0 (wander 0.08))) 2.0)
       (* 0.2)
       (pan (wander 0.05))))

;; The shader listens: bass swells the nebula, treble tints it.
(defn image [uv]
  (let [p (* uvc 1.6)
        drift (* 0.03 time)
        q (vec2 (fbm (+ p drift))
                (fbm (+ p (vec2 5.2 1.3))))
        v (fbm (+ p (* (+ 2.0 (* 3.0 bass)) q)))
        col (palette (+ v (* 0.3 treble) (* 0.05 time))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (* col (+ 0.2 (* (+ 0.4 (* 1.6 bass)) v)))))"}

   {:name "drum sequencer"
    :code
";; A drum machine with no scheduler. The trick: a drum pattern is a
;; *shape in time*, and `fract` is the only clock you need. It turns
;; rising `time` into a sawtooth that snaps back to 0 on every downbeat —
;; that snap is the trigger, and the ramp climbing away from it is the
;; envelope. One expression is a Max/MSP `phasor~`, its edge detector, and
;; the `line~` after it, all at once. Press ◉ start drone to hear it.

;; ── The clock ──────────────────────────────────────────────────────
(defparam bpm 120.0 60.0 180.0)
(def bar-hz (/ bpm 240.0))                    ; a 4-beat bar, in bars/second
(def phase (* 16.0 (fract (* time bar-hz))))  ; 0..16 sweep across the bar
(def idx   (floor phase))                     ; the sixteenth we're on (0..15)
(def local (fract phase))                     ; 0..1 ramp *inside* that step

;; is whole-number step `a` the same as `b`?  1.0 if so, else 0.0
(defn is [a b] (- 1.0 (step 0.5 (abs (- a b)))))
;; a percussive envelope: a spike at each step edge, decaying by `fall`
(defn perc [fall] (pow (- 1.0 local) fall))

;; ── The patterns ───────────────────────────────────────────────────
;; One source of truth per drum: 'is step `s` a hit?'. Read them like a
;; grid and edit the numbers to program a beat (a uniform pattern like the
;; hats is really just a faster phasor — but the list lets you syncopate).
(defn kick?  [s] (+ (is s 0) (is s 6) (is s 8)  (is s 14)))
(defn snare? [s] (+ (is s 4) (is s 12)))
(defn hat?   [s] (+ (is s 2) (is s 6) (is s 10) (is s 14)))

;; ── The voices ─────────────────────────────────────────────────────
;; Each drum = trigger (does this step hit?) x envelope x a little synth.
(defsound kick
  (let [amp (* (kick? idx) (perc 8.0))
        ;; pitch dives from ~125 Hz down to 45 — the classic 808 thump
        f   (+ 45.0 (* 80.0 (perc 40.0)))]
    (->> (osc f) (* amp) (drive 0.3) (* 0.9))))

(defsound snare
  (let [crack (* (->> (noise) (highpass 1500.0 1.0)) (perc 18.0))
        body  (* 0.7 (osc 180.0) (perc 26.0))]     ; a short tonal 'thwack'
    (->> (+ crack body) (* (snare? idx) 0.5) (space 0.15))))

(defsound hat
  (let [tick (* (->> (noise) (highpass 8000.0 1.0)) (perc 60.0))]
    (->> tick (* (hat? idx) 0.3) (pan 0.62))))     ; ticks off to one side

;; ── The view: the sequencer grid, playing itself ───────────────────
;; Three lanes (kick / snare / hat) x sixteen steps. Programmed hits glow;
;; the playhead sweeps left to right and flashes each cell as it fires —
;; driven by the same `idx` and `perc` the sound uses, so eye and ear lock.
(defn image [uv]
  (let [c   (floor (* 16.0 (:x uv)))          ; step column 0..15
        row (floor (* 3.0  (:y uv)))          ; lane: 0 kick, 1 snare, 2 hat
        hit (cond (= row 0.0) (kick? c)
                  (= row 1.0) (snare? c)
                  :else       (hat? c))
        tint (cond (= row 0.0) (vec3 1.0 0.45 0.2)   ; kick  — warm
                   (= row 1.0) (vec3 0.35 0.8 1.0)   ; snare — blue
                   :else       (vec3 0.95 0.85 0.3)) ; hat   — yellow
        here  (is c idx)                      ; is the playhead on this column?
        flash (* here (perc 4.0))             ; the sweeping trigger flash
        ;; thin gutters carve the plane into cells
        gx (smoothstep 0.0 0.06 (- 0.5 (abs (- (fract (* 16.0 (:x uv))) 0.5))))
        gy (smoothstep 0.0 0.10 (- 0.5 (abs (- (fract (* 3.0  (:y uv))) 0.5))))
        ;; dim grid, lit hits, playhead flash, and a kick-driven `bass` glow
        bright (+ 0.08 (* 0.5 hit) (* 0.9 flash) (* 0.6 hit bass))]
    (* tint bright gx gy)))"}

   {:name "cosine waves"
    :code
";; IQ's cosine palette: four vec3 knobs give endless color schemes.
;; `uvc` is centered + aspect-correct — better than `uv` for most effects.

(defn wave [p t]
  (sin (+ (* 6.0 (:x p))
          (* 4.0 (:y p))
          t)))

(defn image [uv]
  (let [p uvc
        w (wave p time)
        d (length p)
        col (palette (+ d (* 0.15 w) (* 0.1 time))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (* col (- 1.1 (* 0.3 d)))))"}

   {:name "plasma"
    :code
";; The demoscene classic: sum a few sine fields, map to hue.

(defn image [uv]
  (let [p (* uvc 3.0)
        v (+ (sin (+ (:x p) time))
             (sin (+ (:y p) (* 0.5 time)))
             (sin (+ (length p) time))
             (sin (+ (* 4.0 (noise (+ p (* 0.2 time)))) time)))
        hue (+ 0.5 (* 0.125 v))]
    (-> (vec3 hue 0.65 1.0)
        hsv->rgb)))"}

   {:name "domain-warped fbm"
    :code
";; Feed noise its own output as coordinates and organic
;; textures appear. The classic 'warping' recipe.

(defn image [uv]
  (let [p (* uvc 1.8)
        q (vec2 (fbm (+ p (* 0.15 time)))
                (fbm (+ p (vec2 5.2 1.3))))
        r (vec2 (fbm (+ p (* 4.0 q) (vec2 1.7 9.2) (* 0.1 time)))
                (fbm (+ p (* 4.0 q) (vec2 8.3 2.8))))
        v (fbm (+ p (* 4.0 r)))
        col (mix (vec3 0.02 0.05 0.1)
                 (vec3 0.9 0.95 1.0)
                 v)
        col (mix col (vec3 0.4 0.65 1.0) (* 0.5 (:x q)))]
    col))"}

   {:name "fire"
    :code
";; Fire — rising heat. Sample turbulent noise that scrolls *downward*
;; (so it reads as rising), then carve it into a flame with a height mask:
;; hot at the base, cooling toward the top and the edges. `abs` of the
;; noise makes the sharp licking creases; a hand-mixed hot ramp colors it.

;; The sound of fire: broadband noise with a body. A wandering bandpass
;; makes a low roar breathe like something burning, a sub rumble gives it
;; weight, and sputtering high noise is the crackle of sparks. Its `bass`
;; feeds back into the flame below, so the fire leaps with the roar.
(defsound fire
  (let [roar    (->> (pink)
                     (bandpass (+ 200.0 (* 400.0 (wander 0.3))) 1.5)
                     (drive 0.4)
                     (* 0.5))
        rumble  (->> (square (* :a1 0.5))
                     (lowpass 90.0 1.2)
                     (drive 0.3)
                     (* 0.25))
        crackle (->> (noise)
                     (highpass 3000.0 1.0)
                     (* 0.15 (wander 8.0)))]   ; sputtering sparks
    (->> (+ roar rumble crackle)
         (* 0.7)
         (space 0.3))))

(defn image [uv]
  (let [x (- (:x uv) 0.5)
        ;; the noise field drifts down, so the flame appears to climb
        drift (vec2 (* 0.15 (sin (* 1.7 time))) (* -1.3 time))
        turb (abs (- (* 2.0 (fbm (+ (* (vec2 x (:y uv)) (vec2 4.0 3.0)) drift)))
                     1.0))
        ;; heat: high near the base, falling with height and toward the
        ;; sides — and lifted by the drone's `bass`, so the flame roars up
        heat (clamp (- (+ 1.2 (* 0.5 turb) (* 0.6 bass))
                       (* 1.7 (:y uv))
                       (* 3.0 x x))
                    0.0 1.0)
        ;; black -> deep red -> orange -> white as heat climbs
        col (mix (mix (vec3 0.0)
                      (vec3 1.0 0.2 0.0) (smoothstep 0.0 0.4 heat))
                 (vec3 1.0 0.9 0.5) (smoothstep 0.55 1.0 heat))]
    (* col heat)))"}

   {:name "water"
    :code
";; Water — a rippling surface seen from just above. A handful of moving
;; sine ripples plus a little fbm make a height field; its slope (sampled
;; at +/-e, the finite-difference normal) bends a sky reflection, and a
;; tight highlight becomes the sun's glint on the swell.

;; The sound of water: filtered noise given a current. A soft rushing band
;; wanders in pitch like a stream finding its channel, a low burble moves
;; underneath, and `space` drowns it all in a wide wash. Its `treble`
;; brightens the surface glint below.
(defsound water
  (let [rush   (->> (pink)
                    (bandpass (+ 700.0 (* 1200.0 (wander 0.15))) 2.0)
                    (* 0.4))
        burble (->> (pink)
                    (lowpass (+ 150.0 (* 200.0 (wander 0.5))) 1.5)
                    (* 0.35))
        ;; a faint pitched shimmer, like light catching the surface
        shimmer (* 0.06 (osc (* :a5 (+ 1.0 (* 0.01 (lfo 0.2))))))]
    (->> (+ rush burble shimmer)
         (* 0.7)
         (space 0.7))))

(defn waves [p]
  (+ (sin (+ (* 3.0 (:x p)) (* 2.0 time)))
     (* 0.7 (sin (+ (* 4.0 (:y p)) (* 1.6 time))))
     (* 0.5 (sin (+ (* 2.5 (+ (:x p) (:y p))) (* 2.3 time))))
     (* 0.6 (fbm (+ (* p 2.0) (vec2 (* 0.2 time) 0.0))))))

(defn image [uv]
  (let [p (* uvc 3.0)
        e 0.02
        h  (waves p)
        hx (- (waves (+ p (vec2 e 0.0))) h)
        hy (- (waves (+ p (vec2 0.0 e))) h)
        n (normalize (vec3 (* -1.0 hx) (* -1.0 hy) 0.3))
        ;; deep teal, brightening to sky where the surface tilts up (Fresnel)
        fresnel (pow (- 1.0 (max (:z n) 0.0)) 3.0)
        col (mix (vec3 0.0 0.15 0.3) (vec3 0.6 0.85 1.0) fresnel)
        ;; the sun's glint: a very tight specular highlight
        light (normalize (vec3 0.4 0.6 0.7))
        spec (pow (max (dot n light) 0.0) 32.0)]
    ;; the glint flares brighter with the rush's `treble`
    (+ col (* (vec3 1.0 0.95 0.8) spec (+ 1.0 (* 2.0 treble))))))"}

   {:name "earth"
    :code
";; Earth — sedimentary rock. Slice height into horizontal strata (a
;; periodic function of y), then domain-warp the field so the layers
;; buckle and fault like real geology. Each band gets its own earthy tone,
;; darker seams settle between them, and fine noise adds mineral grain.

;; The sound of earth: mass you feel more than hear. Detuned sub-
;; oscillators beat slowly against each other, a resonant lowpass sweeps
;; like shifting bedrock, and drive adds the grind of stone. Its `bass`
;; heaves the rock strata below.
(defsound earth
  (let [root (* :a1 0.5)                    ; 27.5 Hz — deep sub
        fat  (+ (saw root)
                (saw (* root 1.005))        ; a slow beat against it
                (* 0.6 (square (* root 0.5))))]
    (->> fat
         (lowpass (+ 80.0 (* 160.0 (lfo 0.02))) 2.0)
         (drive 0.5)
         (* 0.35)
         (space 0.25))))

(defn image [uv]
  (let [p (* uvc 1.5)
        ;; warp the vertical coordinate so the strata bend and fold —
        ;; deeper when the drone's `bass` heaves, like a slow quake
        warp (fbm (+ (* p 1.2) (vec2 (* 0.02 time) 0.0)))
        y (+ (:y p) (* (+ 0.8 (* 0.6 bass)) warp))
        ;; carve height into rock bands: `band` ids the layer,
        ;; `edge` is the position within it (0 at each seam)
        band (floor (* 3.0 y))
        edge (fract (* 3.0 y))
        ;; each layer a slightly different earthy color
        base (palette (* 0.15 band)
                      (vec3 0.4 0.3 0.2) (vec3 0.2 0.15 0.1)
                      (vec3 1.0) (vec3 0.0 0.2 0.4))
        grain (* 0.12 (noise (* p 24.0)))
        seam (smoothstep 0.0 0.09 edge)]
    (* (+ base grain) (+ 0.55 (* 0.45 seam)))))"}

   {:name "air"
    :code
";; Air — wind made visible. Warp the plane along a sideways drift so the
;; field shears into streaks, the way moving cloud stretches downwind.
;; Thin bright filaments come from folding the warped field with `fract`;
;; a soft blue gradient sits behind them as open sky.

;; The sound of air: breath with its lows shaved away. Pink noise through a
;; highpass is the rush; a high resonant bandpass wanders into a whistle
;; when the wind gusts. Wide and weightless. Its `treble` lights the wind
;; streaks below.
(defsound air
  (let [breath  (->> (pink)
                     (highpass 500.0 0.8)
                     (* 0.4))
        whistle (->> (pink)
                     (bandpass (+ 1200.0 (* 2500.0 (wander 0.1))) 6.0)
                     (* (+ 0.05 (* 0.2 (wander 0.08)))))]   ; gusts come and go
    (->> (+ breath whistle)
         (* 0.6)
         (space 0.6))))

(defn image [uv]
  (let [p (* uvc 1.4)
        t (* 0.15 time)
        ;; a slow flow field, then a second warp biased sideways for wind
        q (vec2 (fbm (+ p (vec2 t 0.0)))
                (fbm (+ p (vec2 5.2 1.3) (vec2 0.0 (* 0.5 t)))))
        flow (fbm (+ (* p (vec2 3.0 1.0)) (* 2.0 q) (vec2 (* 2.0 t) 0.0)))
        ;; fold the field into thin, feathered wind filaments
        streaks (pow (- 1.0 (abs (- (* 2.0 (fract (* 3.0 flow))) 1.0))) 3.0)
        sky (mix (vec3 0.3 0.45 0.65) (vec3 0.85 0.9 1.0) (:y uv))]
    ;; the wind filaments flare with the whistle's `treble`
    (+ sky (* (vec3 1.0) streaks (+ 0.4 (* 0.8 treble))))))"}

   {:name "raymarched blobs"
    :code
";; Sphere tracing: `iter` walks a ray through a signed-distance field.
;; Try editing `scene` — swap sd-torus for sd-box, change the smin blend.

(defn scene [p]
  (let [xz (* (rot2 (* 0.6 time)) (:xz p))
        q (vec3 (:x xz) (:y p) (:y xz))
        donut (sd-torus q (vec2 1.0 0.35))
        ball (sd-sphere (- p (vec3 0.0 (* 0.8 (sin time)) 0.0)) 0.45)]
    (smin donut ball 0.4)))

(defn normal [p]
  (let [e 0.001]
    (normalize
     (vec3 (- (scene (+ p (vec3 e 0.0 0.0))) (scene (- p (vec3 e 0.0 0.0))))
           (- (scene (+ p (vec3 0.0 e 0.0))) (scene (- p (vec3 0.0 e 0.0))))
           (- (scene (+ p (vec3 0.0 0.0 e))) (scene (- p (vec3 0.0 0.0 e))))))))

(defn image [uv]
  (let [ro (vec3 0.0 1.1 -3.5)
        rd (normalize (vec3 uvc 1.6))
        t (iter [i 80] [t 0.05]
            (+ t (scene (+ ro (* rd t)))))
        p (+ ro (* rd t))
        n (normal p)
        light (normalize (vec3 0.6 0.8 -0.4))
        diffuse (clamp (dot n light) 0.0 1.0)
        tint (palette (* 0.35 (:y p))
                      (vec3 0.5) (vec3 0.5)
                      (vec3 1.0) (vec3 0.0 0.33 0.67))
        sky (vec3 0.01 0.01 0.03)]
    (if (< t 20.0)
      (+ (* tint diffuse) (* 0.08 tint))
      sky)))"}

   {:name "mandelbrot"
    :code
";; iter with two state vars: z walks the complex plane while n
;; counts steps before escape. The body returns [new-z new-n],
;; and the trailing `n` is the iter's result.

(defn image [uv]
  (let [zoom (pow 0.5 (+ 2.0 (* 2.0 (sin (* 0.2 time)))))
        c (+ (vec2 -0.745 0.186) (* uvc zoom))
        n (iter [i 96] [z (vec2 0.0) n 0.0]
            (let [z2 (vec2 (- (* (:x z) (:x z)) (* (:y z) (:y z)))
                           (* 2.0 (:x z) (:y z)))
                  escaped (> (dot z z) 4.0)]
              [(if escaped z (+ z2 c))
               (if escaped n (+ n 1.0))])
            n)]
    (if (< n 95.5)
      (palette (+ (* 0.02 n) (* 0.05 time))
               (vec3 0.5) (vec3 0.5)
               (vec3 1.0) (vec3 0.0 0.1 0.2))
      (vec3 0.0))))"}

   {:name "disco tunnel"
    :code
";; Polar coordinates turn the plane into an endless tunnel. `cartopol`
;; is the whole trick in one word: it packs a point into (radius, angle),
;; exactly like Max/MSP's [cartopol]. Move your mouse to steer!

(defn image [uv]
  (let [m (- mouse (vec2 0.5))
        p (- uvc (* m 1.5))
        pol (cartopol p)                       ; (radius, angle) — go polar
        angle (/ (:y pol) tau)
        depth (/ 0.35 (+ (:x pol) 0.001))
        cell (vec2 (* 10.0 angle) (+ depth (* 1.5 time)))
        checker (step 1.0 (mod (+ (floor (:x cell)) (floor (:y cell))) 2.0))
        tint (palette (+ (* 0.1 depth) (* 0.05 time))
                      (vec3 0.5) (vec3 0.5)
                      (vec3 1.0) (vec3 0.0 0.33 0.67))
        fog (exp (* -0.12 depth))]
    (* tint (+ 0.35 (* 0.65 checker)) fog)))"}

   {:name "glowing contours"
    :code
";; A drifting fbm height field, drawn as glowing topographic lines.
;; `(sin (* 24 h))` slices the field into evenly spaced bands; the
;; closer a pixel sits to a slice edge, the brighter it burns.

(defn image [uv]
  (let [p (+ (* uvc 1.6) (vec2 (* 0.1 time) 0.0))
        h (fbm (* p 1.5))
        lines (abs (sin (* 24.0 h)))
        glow (pow (- 1.0 lines) 4.0)
        col (palette (+ h (* 0.05 time))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.15 0.3))]
    ;; a dim base wash plus the searing contour lines on top
    (+ (* col 0.15) (* col glow 1.5))))"}

   {:name "caustic pool"
    :code
";; The seascape-caustics trick: fold the plane back on itself a few
;; times with sin/cos, accumulating thin bright creases each pass.
;; iter carries two state vars — the warped point and the running sum.

(defn image [uv]
  (let [p (* uvc 3.0)
        t (* 0.3 time)
        v (iter [i 6] [q p sum 0.0]
            (let [warped (+ q (vec2 (sin (+ (:y q) t))
                                    (cos (+ (:x q) t))))]
              [warped
               (+ sum (/ 1.0 (+ 0.1 (abs (sin (* (:x warped) (:y warped) 0.3))))))])
            sum)
        col (palette (+ (* 0.2 v) (* 0.05 time))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (* col 0.06 v)))"}

   {:name "aurora curtains"
    :code
";; Four stacked curtains of light. Each is a horizontal band whose
;; height is pushed around by its own fbm warp, then falls off with a
;; sharp exponential so the edges feather like real aurora.

(defn image [uv]
  (let [p uvc
        t (* 0.12 time)
        c (iter [i 4] [sum (vec3 0.0)]
            (let [off (* 0.4 i)
                  warp (fbm (+ (* p (+ 1.0 (* 0.3 i)))
                               (vec2 t (* 0.5 t))
                               (vec2 off 0.0)))
                  band (exp (* -4.0 (abs (+ (:y p) (- warp 0.5) (- off 0.6)))))
                  tint (palette (+ 0.35 (* 0.15 warp) (* 0.1 i))
                                (vec3 0.5) (vec3 0.5)
                                (vec3 1.0) (vec3 0.0 0.33 0.5))]
              (+ sum (* tint band))))]
    (* c 0.9)))"}

   {:name "neon tunnel"
    :code
";; Polar coordinates make an endless tunnel; `0.6 / r` is depth, so the
;; center recedes forever. `cartopol` gives us the angle and radius in one
;; step. fbm churns the walls, sharp sine rings rush toward you, and
;; smoothstep hides the singular point at r = 0.

(defn image [uv]
  (let [pol (cartopol uvc)                      ; (radius, angle)
        a (:y pol)
        r (:x pol)
        depth (+ (/ 0.6 (+ r 0.06)) (* 0.5 time))
        n (fbm (vec2 (* 3.0 (/ a tau)) depth))
        rings (pow (max (sin (* 20.0 depth)) 0.0) 8.0)
        tint (palette (+ (* 0.08 depth) (* 0.4 n))
                      (vec3 0.5) (vec3 0.5)
                      (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (* tint (+ (* 0.4 n) rings) (smoothstep 0.0 0.4 r))))"}

   {:name "warp gate"
    :code
";; A kaleidoscopic tunnel: fold the angle into six mirrored wedges,
;; then texture the walls with noise. The `0.15 / d` term is a cheap
;; glow — light blooms wherever a value crosses zero.

(defn image [uv]
  (let [pol (cartopol uvc)                      ; (radius, angle)
        a (:y pol)
        r (:x pol)
        seg (/ tau 6.0)
        wedge (- (abs (- (mod a seg) (* 0.5 seg))) (* 0.25 seg))
        depth (+ (/ 0.5 (+ r 0.05)) time)
        n (fbm (vec2 (* 6.0 wedge) depth))
        glow (/ 0.15 (+ (abs (- (fract (* 2.0 depth)) 0.5)) 0.05))
        tint (palette (+ n (* 0.05 depth))
                      (vec3 0.5) (vec3 0.5)
                      (vec3 2.0 1.0 0.0) (vec3 0.5 0.2 0.25))]
    (* tint (+ (* 0.3 n) (* 0.2 glow)) (smoothstep 0.0 0.3 r))))"}

   {:name "hyperspace"
    :code
";; Warp speed. Slice the view into 60 angular cells; each gets its own
;; hashed star, streaking outward at its own pace. fract() loops each
;; streak endlessly, so the jump to lightspeed never stops.

(defn image [uv]
  (let [pol (cartopol uvc)                      ; (radius, angle)
        a (:y pol)
        r (:x pol)
        speed (* 0.5 time)
        cell (floor (* 60.0 (/ a tau)))
        seed (hash1 cell)
        streak (fract (+ (* seed 10.0) (* speed (+ 0.5 seed)) (* 0.2 r)))
        star (pow (smoothstep 0.7 1.0 streak) 3.0)
        bright (* star (smoothstep 0.0 0.6 r))
        tint (palette (+ seed (* 0.1 time))
                      (vec3 0.5) (vec3 0.5)
                      (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (* tint bright 2.0)))"}

   {:name "liquid chrome"
    :code
";; Domain-warped fbm, but lit like a metal surface. We treat the noise
;; as a height field, take finite-difference derivatives for a normal,
;; then add a tight specular highlight — that hard glint sells the metal.

(defn image [uv]
  (let [p (* uvc 1.4)
        t (* 0.08 time)
        q (vec2 (fbm (+ p (vec2 t 0.0)))
                (fbm (+ p (vec2 5.2 1.3) (vec2 0.0 t))))
        r (vec2 (fbm (+ p (* 4.0 q) (vec2 1.7 9.2)))
                (fbm (+ p (* 4.0 q) (vec2 8.3 2.8))))
        h (fbm (+ p (* 4.0 r)))
        e 0.02
        hx (- (fbm (+ p (vec2 e 0.0) (* 4.0 r))) h)
        hy (- (fbm (+ p (vec2 0.0 e) (* 4.0 r))) h)
        n (normalize (vec3 (* -60.0 hx) (* -60.0 hy) 1.0))
        light (normalize (vec3 0.5 0.7 0.6))
        spec (pow (max (dot n light) 0.0) 16.0)
        col (palette (+ h (* 0.3 (:x r)))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.33 0.67))]
    (+ (* col (+ 0.2 (* 0.8 h))) (* (vec3 1.0) spec))))"}

   {:name "electric julia"
    :code
";; A Julia set, but instead of counting escape steps we trap the orbit:
;; track how close z ever comes to the origin. Filaments where the orbit
;; grazes zero light up like neon. The seed c circles slowly, so the
;; whole fractal breathes and rewires itself.

(defn image [uv]
  (let [c (vec2 (* 0.7 (sin (* 0.3 time)))
                (* 0.7 (cos (* 0.23 time))))
        z0 (* uvc 1.4)
        trap (iter [i 48] [z z0 m 1000.0]
               (let [z2 (vec2 (- (* (:x z) (:x z)) (* (:y z) (:y z)))
                              (* 2.0 (:x z) (:y z)))
                     nz (+ z2 c)]
                 [nz (min m (length nz))])
               m)
        glow (exp (* -6.0 trap))
        col (palette (+ trap (* 0.1 time))
                     (vec3 0.5) (vec3 0.5)
                     (vec3 1.0) (vec3 0.0 0.1 0.2))]
    (+ (* col 0.3) (* col glow 2.0))))"}

   {:name "volumetric nebula"
    :code
";; Now we march *through* the volume. Step a ray forward 48 times and
;; sample density from two crossed noise slices — their product carves
;; out wispy cloud pockets. Accumulate emissive color the whole way for
;; a soft, glowing, three-dimensional cloud.

(defn image [uv]
  (let [rd (normalize (vec3 uvc 1.3))
        ro (vec3 0.0 0.0 (* -0.3 time))
        neb (iter [i 32] [col (vec3 0.0)]
              (let [t (* 0.12 i)
                    p (+ ro (* rd t))
                    d1 (fbm (+ (* (:xy p) 1.5) (vec2 0.0 (* 0.2 time))))
                    d2 (fbm (+ (* (:yz p) 1.5) (vec2 (* 0.15 time) 0.0)))
                    dens (max (- (* d1 d2) 0.15) 0.0)
                    tint (palette (+ (* 0.15 t) (* 0.5 dens) (* 0.03 time))
                                  (vec3 0.5) (vec3 0.5)
                                  (vec3 1.0) (vec3 0.0 0.33 0.67))]
                (+ col (* tint dens 0.22))))]
    neb))"}

   {:name "wormhole"
    :code
";; The grand finale: a raymarched glowing tunnel that flies forever.
;; The centerline wobbles through space on sine curves, the walls are
;; textured with noise around their circumference, and a 1/d falloff
;; blooms light wherever the ray passes close to the tunnel surface —
;; forty accumulated steps of pure hypnotic glow.

(defn image [uv]
  (let [ro (vec3 0.0 0.0 (* -3.0 time))
        rd (normalize (vec3 uvc 1.0))
        glow (iter [i 40] [col (vec3 0.0)]
               (let [t (* 0.18 i)
                     p (+ ro (* rd t))
                     cx (* 0.6 (sin (* 0.4 (:z p))))
                     cy (* 0.6 (cos (* 0.3 (:z p))))
                     offset (- (:xy p) (vec2 cx cy))
                     pol (cartopol offset)          ; (radius, angle) of the wall
                     r (:x pol)
                     wall (fbm (vec2 (* 3.0 (:y pol)) (* 0.5 (:z p))))
                     d (abs (- r (+ 1.2 (* 0.3 wall))))
                     shine (/ 0.02 (+ d 0.02))
                     tint (palette (+ (* 0.05 (:z p)) (* 0.3 wall))
                                   (vec3 0.5) (vec3 0.5)
                                   (vec3 1.0) (vec3 0.0 0.33 0.67))]
                 (+ col (* tint shine 0.09))))]
    glow))"}])

(def default-code (:code (first gallery)))

(defn find-example [example-name]
  (some #(when (= (:name %) example-name) %) gallery))
