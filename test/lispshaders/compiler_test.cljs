(ns lispshaders.compiler-test
  "Node-based tests for the Clojure->GLSL/Elementary compiler: every
   gallery example must compile, codegen details must look right, drone
   bodies must build real Elementary graphs, and bad input must fail with
   a helpful message."
  (:require ["@elemaudio/core" :refer [isNode]]
            [cljs.test :refer [deftest is testing]]
            [clojure.string :as str]
            [lispshaders.compiler :as compiler]
            [lispshaders.audio-lib :as audio-lib]
            [lispshaders.examples :as examples]))

(defn- glsl [src] (:glsl (compiler/compile-shader src)))

(defn- compile-error [src]
  (try (compiler/compile-shader src)
       nil
       (catch :default e (ex-message e))))

(deftest all-gallery-examples-compile
  (doseq [{:keys [name code]} examples/gallery]
    (testing name
      (let [{:keys [glsl shadertoy drones]} (compiler/compile-shader code)]
        (is (str/includes? glsl "void main()"))
        (is (str/includes? glsl "#version 300 es"))
        (is (str/includes? shadertoy "void mainImage(out vec4 fragColor, in vec2 fragCoord)"))
        (is (not (str/includes? shadertoy "u_time"))
            "shadertoy export must use iTime, not our uniforms")
        (doseq [{:keys [js-body] :as drone} drones]
          (testing (:name drone)
            (is (some? ((audio-lib/make-drone-fn js-body) audio-lib/lib))
                "every gallery drone must build a real Elementary graph")))))))

(deftest literals-and-arithmetic
  (let [out (glsl "(defn image [uv] (vec3 (+ 1 2 0.5)))")]
    (is (str/includes? out "((1.0 + 2.0) + 0.5)")
        "ints become floats; n-ary + folds left")))

(deftest swizzles
  (let [out (glsl "(defn image [uv] (vec3 (:yx uv) (:x uv)))")]
    (is (str/includes? out "uv.yx"))
    (is (str/includes? out "uv.x")))
  (testing "rgba aliases map onto xyzw"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (:r (vec4 uv 0 1))))")
                       ".x"))))

(deftest let-shadowing-gets-unique-names
  (let [out (glsl "(defn image [uv]
                     (let [c (vec3 0.1)
                           c (+ c 0.2)]
                       c))")]
    (is (str/includes? out "vec3 c = "))
    (is (str/includes? out "vec3 c_2 = "))))

(deftest if-compiles-to-ternary
  (let [out (glsl "(defn image [uv] (if (< (:x uv) 0.5) (vec3 1.0) (vec3 0.0)))")]
    (is (str/includes? out "(uv.x < 0.5) ?"))))

(deftest iter-single-state
  (let [out (glsl "(defn image [uv]
                     (vec3 (iter [i 8] [t 0.0] (+ t 0.1))))")]
    (is (str/includes? out "for (int i = 0; i < 8; i++)"))
    (is (str/includes? out "t = (t + 0.1);"))))

(deftest iter-multi-state-with-result
  (let [out (glsl "(defn image [uv]
                     (vec3 (iter [i 4] [a 0.0 b 1.0]
                             [(+ a b) (* b 2.0)]
                             (+ a b))))")]
    (is (str/includes? out "for (int i = 0; i < 4; i++)"))
    ;; parallel update: both next-states computed before either assignment
    (is (str/includes? out "a_next"))
    (is (str/includes? out "b_next"))))

(deftest loop-index-auto-casts-to-float
  (let [out (glsl "(defn image [uv]
                     (vec3 (iter [i 4] [t 0.0] (+ t (* 0.1 i)))))")]
    (is (str/includes? out "float(i)"))))

(deftest user-fns-inline
  (let [out (glsl "(defn double-up [x] (* x 2.0))
                   (defn image [uv] (vec3 (double-up (:x uv))))")]
    (is (str/includes? out "(uv.x * 2.0)")
        "simple args substitute directly, no temp needed")))

(deftest rsub-and-rdiv-builtins-flip-operands
  (let [out (glsl "(defn image [uv] (vec3 (rdiv (:x uv) 6.0) (rsub (:y uv) 1.0) 0.0))")]
    (is (str/includes? out "(6.0 / uv.x)") "rdiv is n/m — reversed divide")
    (is (str/includes? out "(1.0 - uv.y)") "rsub is n-m — reversed subtract"))
  (testing "a user's own definition shadows the builtin"
    (is (str/includes? (glsl "(defn rdiv [m n] (* m n))
                              (defn image [uv] (vec3 (rdiv (:x uv) 6.0)))")
                       "(uv.x * 6.0)"))))

(deftest scale-builtins-remap-ranges
  (testing "scale is Max's [scale]: x from in-range to out-range"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (scale (:x uv) 0.0 1.0 20.0 200.0)))")
                       "(20.0 + ((200.0 - 20.0) * ((uv.x - 0.0) / (1.0 - 0.0))))")))
  (testing "bi->uni and uni->bi are scale with the two standard ranges"
    (let [out (glsl "(defn image [uv] (vec3 (bi->uni (:x uv)) (uni->bi (:y uv)) 0.0))")]
      (is (str/includes? out "(0.0 + ((1.0 - 0.0) * ((uv.x - -1.0) / (1.0 - -1.0))))"))
      (is (str/includes? out "(-1.0 + ((1.0 - -1.0) * ((uv.y - 0.0) / (1.0 - 0.0))))"))))
  (testing "they stay generic over vectors, like any inlined helper"
    (is (str/includes? (glsl "(defn image [uv] (bi->uni (vec3 (:x uv))))")
                       "vec3(uv.x)"))))

(deftest gen-style-range-builtins
  (testing "wrap forces a value into lo..hi by jumping to the far edge"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (wrap (:x uv) -1.0 1.0)))")
                       "(-1.0 + mod((uv.x - -1.0), (1.0 - -1.0)))")))
  (testing "fold reflects off the edges instead — a 2-span cycle, mirrored"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (fold (:x uv) -1.0 1.0)))")
                       "abs(")
        "the mirror comes from abs around the span midpoint"))
  (testing "quant snaps down to a grid of step-sized rungs"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (quant (:x uv) 0.1)))")
                       "(floor((uv.x / 0.1)) * 0.1)")))
  (testing "ramp is the shader-side phasor, built on time"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (ramp 0.5)))")
                       "fract((u_time * 0.5))")))
  (testing "hz->midi inverts midi->hz"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (hz->midi 440.0)))")
                       "(69.0 + (12.0 * log2((440.0 / 440.0))))")))
  (testing "a user's own definition still shadows these"
    (is (str/includes? (glsl "(defn quant [x step] (* x step))
                              (defn image [uv] (vec3 (quant (:x uv) 0.1)))")
                       "(uv.x * 0.1)"))))

(deftest anon-fn-shorthand-inlines
  (testing "% is the sole argument"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (#(* % 2.0) (:x uv))))")
                       "(uv.x * 2.0)")
        "an immediately-called #() inlines just like a named helper"))
  (testing "%1 %2 are positional arguments"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (#(+ (* %1 %1) %2) (:x uv) (:y uv))))")
                       "((uv.x * uv.x) + uv.y)")))
  (testing "a zero-arg #() is allowed"
    (is (str/includes? (glsl "(defn image [uv] (#(vec3 (:x uv))))")
                       "vec3(uv.x)")))
  (testing "#() works inside a drone too"
    (is (some? (audio-lib/make-drone-fn
                (:js-body (first (:drones (compiler/compile-shader
                                           "(defsound hum (#(* % 0.3) (osc :a2)))")))))))))

(deftest anon-fn-shorthand-threads
  (testing "a bare #() in -> is called with the threaded value"
    (is (str/includes? (glsl "(defn image [uv] (-> (:x uv) #(* % %) vec3))")
                       "(uv.x * uv.x)")))
  (testing "extra args around % are kept"
    (is (str/includes? (glsl "(defn image [uv] (-> (:x uv) #(+ % 2.0) vec3))")
                       "(uv.x + 2.0)")))
  (testing "a bare #() threads the same way in ->>"
    (is (str/includes? (glsl "(defn image [uv] (->> (:x uv) #(* % 3.0) vec3))")
                       "(uv.x * 3.0)")))
  (testing "the explicitly-wrapped idiom (#(...)) still works too"
    (is (str/includes? (glsl "(defn image [uv] (-> (:x uv) (#(* % %)) vec3))")
                       "(uv.x * uv.x)"))))

(deftest anon-fn-shorthand-errors
  (testing "arity mismatch is reported"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (#(+ %1 %2) (:x uv))))")
                       "2 argument")))
  (testing "rest args are rejected"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (#(vec3 %&) (:x uv))))")
                       "rest argument")))
  (testing "nesting is rejected"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (#(#(* % %) %) (:x uv))))")
                       "Nested")))
  (testing "an uncalled #() can't be used as a value"
    (is (str/includes? (compile-error "(defn image [uv] #(* % %))")
                       "called"))))

(deftest anon-fn-shorthand-ignores-strings-and-comments
  (testing "a #( inside a string literal is left verbatim"
    (let [js (:js-body (first (:drones (compiler/compile-shader
                                        "(defsound s (sample \"a#(b\" 1.0))"))))]
      (is (str/includes? js "a#(b")
          "the sample name must survive preprocessing untouched")))
  (testing "a #( in a ; comment is ignored, and the code still compiles"
    (is (str/includes? (glsl "(defn image [uv] ; try #(inc %)\n  (vec3 (:x uv)))")
                       "void main()"))))

(deftest defs-become-main-locals
  (let [out (glsl "(def speed 2.0)
                   (defn image [uv] (vec3 (sin (* time speed))))")]
    (is (str/includes? out "float speed = 2.0;"))
    (is (str/includes? out "(u_time * speed)"))))

(deftest threading-macros-expand
  (is (str/includes? (glsl "(defn image [uv] (-> (:x uv) (* 2.0) sin (+ 0.5) vec3))")
                     "sin((uv.x * 2.0))")))

(deftest kebab-case-names-sanitize
  (let [out (glsl "(defn image [uv]
                     (let [glow-amount 0.5]
                       (vec3 glow-amount)))")]
    (is (str/includes? out "float glow_amount = 0.5;"))))

(deftest pow-splats-scalar-exponent
  (is (str/includes? (glsl "(defn image [uv] (pow (vec3 0.5) 2.2))")
                     "pow(vec3(0.5), vec3(2.2))")
      "GLSL has no pow(vec, float) overload, so the exponent is splatted"))

(deftest helpful-errors
  (testing "unknown symbol"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 wat))")
                       "Unknown symbol: wat")))
  (testing "missing entry point"
    (is (str/includes? (compile-error "(def x 1.0)") "image")))
  (testing "if without else"
    (is (str/includes? (compile-error "(defn image [uv] (if (< 1.0 2.0) (vec3 1.0)))")
                       "if needs all three parts")))
  (testing "type mismatch"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (+ uv (vec3 1.0))))")
                       "vec2")))
  (testing "clojure sequence fns point to iter"
    (is (str/includes? (compile-error "(defn image [uv] (reduce + [1 2 3]))")
                       "iter")))
  (testing "wrong swizzle size"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (:z uv)))")
                       "only a vec2")))
  (testing "image must return a color"
    (is (str/includes? (compile-error "(defn image [uv] (< 1.0 2.0))")
                       "color"))))

;; ---------------------------------------------------------------------------
;; Drones: the Elementary Audio target

(defn- first-drone [src]
  (first (:drones (compiler/compile-shader src))))

(defn- build-drone
  "Compile a defsound and build its Elementary signal graph."
  [src]
  (let [{:keys [js-body]} (first-drone src)]
    ((audio-lib/make-drone-fn js-body) audio-lib/lib)))

(deftest defsound-compiles-to-a-signal-graph
  (let [src "(defsound hum (* 0.3 (osc :a2)))"
        {:keys [name channels js-body]} (first-drone src)]
    (is (= "hum" name))
    (is (= 1 channels))
    (is (str/includes? js-body "osc(110.0)")
        "note keywords compile straight to their frequency")
    (is (isNode (build-drone src)))))

(deftest range-builtins-work-in-drones-too
  (testing "fold is a wavefolder on a driven signal"
    (is (isNode (build-drone "(defsound metal (-> (saw :a1) (* 3.0) (fold -1.0 1.0) (* 0.2)))"))))
  (testing "wrap and quant build over signals"
    (is (isNode (build-drone "(defsound steps (* 0.2 (osc (wrap (* 400.0 (lfo 0.1)) 100.0 300.0))))")))
    (is (isNode (build-drone "(defsound stair (* 0.2 (saw (quant (* 400.0 (lfo 0.1)) 55.0))))"))))
  (testing "pitch math round-trips through note-space"
    (is (isNode (build-drone "(defsound snapped
                                (* 0.2 (saw (-> (* 220.0 (+ 1.0 (lfo 0.05)))
                                                hz->midi (quant 1.0) midi->hz))))")))))

(deftest stereo-drones-are-node-pairs
  (let [src "(defsound wide (->> (pink)
                                 (bandpass 900.0 2.0)
                                 (pan 0.3)))"
        {:keys [channels]} (first-drone src)
        out (build-drone src)]
    (is (= 2 channels))
    (is (array? out))
    (is (isNode (aget out 0)))
    (is (isNode (aget out 1)))))

(deftest drone-builtins-build
  (testing "modulation, space, saturation and time all construct graphs"
    (is (isNode (build-drone "(defsound m (* (wander 0.1) (osc 110.0)))")))
    (is (array? (build-drone "(defsound s (space 0.4 (saw :a1)))")))
    (is (isNode (build-drone "(defsound e (echo 500.0 0.5 (tri 220.0)))")))
    (is (isNode (build-drone "(defsound d (drive 0.4 (square 55.0)))")))
    (is (isNode (build-drone "(defsound t (osc (+ 55.0 (* 5.0 (sin (* 0.1 time))))))"))
        "time is a signal inside drones")))

(deftest iter-unrolls-into-the-graph
  (let [src "(defsound partials
               (iter [i 6] [sum 0.0]
                 (+ sum (* 0.1 (osc (* 110.0 (+ 1.0 i)))))))"]
    (is (isNode (build-drone src)))))

(deftest comparisons-and-if-become-gates
  (let [src "(defsound gated (if (< (lfo 0.1) 0.5) (osc 110.0) (osc 165.0)))"
        {:keys [js-body]} (first-drone src)]
    (is (str/includes? js-body "select("))
    (is (str/includes? js-body "lt("))
    (is (isNode (build-drone src)))))

(deftest plain-number-math-folds-at-build-time
  (let [add (aget audio-lib/lib "add")
        mix (aget audio-lib/lib "mix")]
    (is (= 5 (add 2 3)) "numbers stay numbers until a signal appears")
    (is (= 1.5 (mix 1 2 0.5)))
    (is (isNode (add 2 (build-drone "(defsound x (osc 110.0))"))))))

(deftest hashes-insist-on-plain-numbers
  (is (number? (build-drone "(defsound h (* (hash1 3.0) 1.0))"))
      "hash of a literal folds to a number")
  (is (thrown-with-msg? js/Error #"plain number"
                        (build-drone "(defsound h (osc (* 100.0 (hash1 (lfo 1.0)))))"))))

(deftest defparam-becomes-a-knob-in-every-target
  (let [src "(defparam speed 0.5 0.0 3.0)
             (defsound hum (osc (* :a1 (+ 1.0 (* 0.1 speed)))))
             (defn image [uv] (vec3 (* speed (:x uv))))"
        {:keys [glsl shadertoy drones params]} (compiler/compile-shader src)]
    (testing "the WebGL target reads it from a live uniform"
      (is (str/includes? glsl "uniform float u_param_speed;"))
      (is (str/includes? glsl "u_param_speed")))
    (testing "the Shadertoy export bakes it in as an editable const"
      (is (str/includes? shadertoy "const float speed = 0.5;"))
      (is (not (str/includes? shadertoy "u_param_"))))
    (testing "a drone reads it live by name through the runtime"
      (let [js (:js-body (first drones))]
        (is (str/includes? js "param(\"speed\")"))
        (is (isNode (build-drone src)))))
    (testing "the specs come back for the UI to render sliders"
      (is (= [{:name "speed" :default 0.5 :min 0.0 :max 3.0}] params)))))

(deftest defparam-short-form-is-a-0-1-knob
  (let [{:keys [params]} (compiler/compile-shader
                          "(defparam glow 0.6)\n(defn image [uv] (vec3 glow))")]
    (is (= [{:name "glow" :default 0.6 :min 0 :max 1}] params)
        "two-arg form defaults to a 0..1 range")))

(deftest defparam-runtime-knob-value-flows-into-the-graph
  (let [src "(defparam gain 0.5 0.0 1.0)\n(defsound s (* gain (osc 110.0)))"]
    (audio-lib/set-param-values! {"gain" 0.8})
    (is (isNode (build-drone src))
        "a knob compiles to a real signal the engine can render")))

(deftest defparam-rejects-bad-declarations
  (testing "min must be below max"
    (is (str/includes? (compile-error "(defparam x 0.5 1.0 0.0)\n(defn image [uv] (vec3 x))")
                       "min")))
  (testing "the default must sit inside the range"
    (is (str/includes? (compile-error "(defparam x 5.0 0.0 1.0)\n(defn image [uv] (vec3 x))")
                       "between")))
  (testing "duplicate knob names are caught"
    (is (str/includes? (compile-error "(defparam x 0.5)\n(defparam x 0.2)\n(defn image [uv] (vec3 x))")
                       "share a name"))))

(deftest defs-and-defns-are-shared-between-image-and-drones
  (let [src "(def root 55.0)
             (defn wobble [x] (sin (* tau x)))
             (defsound bed (* 0.5 (osc (* root (+ 1.0 (* 0.01 (wobble time)))))))
             (defn image [uv] (vec3 (wobble (+ (:x uv) root))))"
        {:keys [glsl drones]} (compiler/compile-shader src)]
    (is (str/includes? glsl "float root = 55.0;"))
    (is (str/includes? (:js-body (first drones)) "let root = 55.0;"))))

(deftest defs-only-fail-where-referenced
  (testing "a shader-only def doesn't break drones that ignore it"
    (let [src "(def m (* (:x mouse) 2.0))
               (defsound blip (osc 880.0))
               (defn image [uv] (vec3 m))"
          {:keys [drones glsl]} (compiler/compile-shader src)]
      (is (str/includes? glsl "u_mouse"))
      (is (seq (:js-body (first drones))))))
  (testing "referencing it from a drone gives a pointed error"
    (is (str/includes? (compile-error "(def m (* (:x mouse) 2.0))
                                       (defsound blip (osc m))
                                       (defn image [uv] (vec3 0.0))")
                       "can't be used here")))
  (testing "an audio-only def doesn't break the image"
    (let [src "(def sweep (lfo 0.1))
               (defsound bed (* sweep (osc 110.0)))
               (defn image [uv] (vec3 0.5))"
          {:keys [drones glsl]} (compiler/compile-shader src)]
      (is (seq glsl))
      (is (str/includes? (:js-body (first drones)) "lfo")))))

(deftest drone-only-programs-get-the-fallback-visualizer
  (let [{:keys [glsl]} (compiler/compile-shader
                        "(defsound tick (osc 1000.0))")]
    (is (str/includes? glsl "u_audio"))))

(deftest audio-globals-reach-both-shader-targets
  (let [src "(defn image [uv] (vec3 (+ bass (* level treble))))"
        {:keys [glsl shadertoy]} (compiler/compile-shader src)]
    (is (str/includes? glsl "u_audio.x"))
    (is (str/includes? shadertoy "iChannel0"))))

(deftest cross-target-mistakes-get-pointed-errors
  (testing "audio builtins in the image point at defsound"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (osc 220.0)))")
                       "defsound")))
  (testing "shader tools in a drone say they're shader-only"
    (is (str/includes? (compile-error "(defsound x (fbm (vec2 1.0 2.0)))
                                       (defn image [uv] (vec3 0.0))")
                       "shader tool")))
  (testing "the beat global died with the sequencer"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 beat))")
                       "Unknown symbol")))
  (testing "defsound rejects a multi-form body"
    (is (str/includes? (compile-error "(defsound kick 0.35 [t] (sin t))")
                       "defsound"))))

;; ---------------------------------------------------------------------------
;; Uploaded media: samples in drones, textures in shaders

(defn- media-error [src media]
  (try (compiler/compile-shader src media)
       nil
       (catch :default e (ex-message e))))

(deftest samples-loop-and-scrub-uploaded-audio
  (let [src "(defsound tape (* 0.5 (sample \"vinyl\" 1.0)))"
        {:keys [js-body]} (first (:drones (compiler/compile-shader src {:samples #{"vinyl"}})))]
    (is (str/includes? js-body "sample(\"vinyl\", 1.0)"))
    (is (isNode ((audio-lib/make-drone-fn js-body) audio-lib/lib))
        "sample builds a real Elementary graph"))
  (testing "scrub reads a position, which can be a signal"
    (is (isNode (build-drone "(defsound s (scrub \"vinyl\" (lfo 0.05)))")))))

(deftest sample-names-are-checked-against-uploads
  (testing "unknown names list what's uploaded"
    (is (str/includes? (media-error "(defsound t (sample \"nope\" 1.0))"
                                    {:samples #{"vinyl" "birds"}})
                       "birds, vinyl")))
  (testing "an empty library points at the samples tab"
    (is (str/includes? (media-error "(defsound t (sample \"x\" 1.0))" {:samples #{}})
                       "samples tab")))
  (testing "the name must be a string literal"
    (is (str/includes? (media-error "(defsound t (sample 3.0 1.0))" {:samples #{"vinyl"}})
                       "name as a string")))
  (testing "without media info (tests, tooling) any name is trusted"
    (is (some? (compiler/compile-shader "(defsound t (sample \"anything\" 1.0))")))))

(deftest textures-read-uploaded-images
  (let [src "(defn image [uv] (:rgb (texture \"photo\" uv)))"
        {:keys [glsl shadertoy]} (compiler/compile-shader src {:textures ["photo"]})]
    (is (str/includes? glsl "uniform sampler2D u_img_photo"))
    (is (str/includes? glsl "texture(u_img_photo, uv)"))
    (testing "the export maps images to iChannels from 1 up (0 carries audio)"
      (is (str/includes? shadertoy "texture(iChannel1, uv)"))
      (is (str/includes? shadertoy "iChannel1"))))
  (testing "unknown image names list the uploads"
    (is (str/includes? (media-error "(defn image [uv] (texture \"nope\" uv))"
                                    {:textures ["photo"]})
                       "uploaded: photo")))
  (testing "texture is shader-only"
    (is (str/includes? (media-error "(defsound t (texture \"photo\" (vec2 0.0)))
                                     (defn image [uv] (vec3 0.0))"
                                    {:textures ["photo"]})
                       "not inside a defsound")))
  (testing "sample is drone-only"
    (is (str/includes? (compile-error "(defn image [uv] (vec3 (sample \"x\" 1.0)))")
                       "defsound"))))

;; ---------------------------------------------------------------------------
;; Notes: keyword literals are frequencies

(deftest overtone-compatible-note-keywords
  (is (= 440 (compiler/note->hz :a4)))
  (is (< 276 (compiler/note->hz :c#4) 278))
  (is (= (compiler/note->hz :c#4) (compiler/note->hz :db4)))
  (is (thrown-with-msg? js/Error #"isn't a note" (compiler/note->hz :h9)))
  (testing "they work in shaders too — a keyword is just a number"
    (is (str/includes? (glsl "(defn image [uv] (vec3 (sin (* time :a4))))")
                       "440.0"))))
