(ns lispshaders.audio-lib
  "The JS runtime for compiled drones. Where the GLSL target's ops run on
   the GPU, this library's ops build an Elementary Audio node graph: a
   compiled defsound body calls `add(a, b)` / `osc(f)` / `lowpass(...)`
   and gets back signal nodes, not numbers.

   Two rules make the DSL feel identical across targets:
   - vectors are JS arrays and every op broadcasts scalars over them,
     exactly like GLSL — a vec2 here is a stereo [left right] pair;
   - plain numbers stay plain numbers ((* 2.0 3.0) folds to 6 while the
     graph is built); a value only becomes a node once a signal touches it.

   Everything here is pure graph construction with no browser
   dependencies, so the compiler tests exercise it in Node."
  (:require ["@elemaudio/core" :refer [el]]
            ["./audio_lib.js" :refer [buildAudioLib]]))

(def lib
  "The runtime library object passed to every compiled drone function."
  (buildAudioLib el))

(def ^:private lib-keys (js/Object.keys lib))

(defn set-sample-paths!
  "Point uploaded-sample names at their current (versioned) virtual-file-
   system paths, so (sample \"vinyl\" ...) finds the latest upload."
  [name->path]
  ((aget lib "__setSamplePaths") (clj->js name->path)))

(defn set-param-values!
  "Push the current knob values into the runtime, so freshly built (and
   re-rendered) graphs read each (defparam ...) at its latest slider value."
  [name->value]
  ((aget lib "__setParamValues") (clj->js name->value)))

(defn make-drone-fn
  "Turn a compiled :js-body into a callable (fn [lib] -> signal), where the
   returned signal is an Elementary node (mono) or a [left right] pair.
   The body sees every library function by its bare name."
  [js-body]
  (js/Function "L"
               (str "\"use strict\";\n"
                    "const {" (.join lib-keys ", ") "} = L;\n"
                    js-body)))
