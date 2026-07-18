(ns lispshaders.audio
  "The drone engine, on top of Elementary Audio.

   Every compiled defsound body is a function that builds an Elementary
   signal graph. This namespace evaluates all of them, mixes the layers
   through per-drone gain gates (mute/solo) and level meters, runs the sum
   through a gentle tanh saturator so stacked layers glue instead of
   clipping, and renders the result into a WebRenderer worklet.

   The magic that makes editing feel like editing a shader: re-rendering a
   graph *diffs* it against the playing one, so code changes morph the
   sound in place — the drone never stops or restarts. An AnalyserNode
   taps the output, and the shader reads its band energies every frame
   via `frame-data`."
  (:require ["@elemaudio/core" :refer [el]]
            ["@elemaudio/web-renderer" :default WebRenderer]
            [lispshaders.audio-lib :as lib]))

(defonce ^:private state
  (atom {:drones []       ;; [{:name :channels :js-body :fn} ...] in source order
         :muted #{}       ;; drone names silenced from the UI
         :solo nil        ;; when set, only this drone plays
         :engine nil      ;; {:ctx :core :analyser :fft} — built on first ▶
         :running? false
         :levels [0 0 0 0]
         :on-level nil    ;; (fn [name level]) — feeds the UI's meters
         :sample-buffers {} ;; vfs-path -> Float32Array of uploaded audio
         :vfs-sent #{}}))   ;; vfs paths already handed to the renderer

;; ---------------------------------------------------------------------------
;; Graph assembly

(defn- audible? [drone-name {:keys [muted solo]}]
  (if solo
    (= drone-name solo)
    (not (contains? muted drone-name))))

(defn- layer-signal
  "One drone -> a stereo [L R] pair. The layer's audibility rides a keyed,
   smoothed const, so mute/solo re-renders fade in ~80ms instead of
   clicking; a meter node on the gated left channel reports its level."
  [{:keys [name channels] :as drone} s]
  (let [out ((:fn drone) lib/lib)
        [l r] (if (= 2 channels) [(aget out 0) (aget out 1)] [out out])
        gate (.smooth el (.tau2pole el 0.08)
                      (.const el #js {:key (str "layer/" name)
                                      :value (if (audible? name s) 1 0)}))]
    [(.meter el #js {:name name} (.mul el l gate))
     (.mul el r gate)]))

(defn- build-master
  "Evaluate every drone and mix them into the final stereo pair. Pure graph
   construction — this both validates freshly compiled code (it throws with
   a friendly message on bad graphs) and produces what gets rendered.
   The master stage fades in from silence and tanh-saturates, so however
   many layers pile up, the output stays musical and inside ±1."
  [{:keys [drones] :as s}]
  (if (empty? drones)
    [(.const el #js {:value 0}) (.const el #js {:value 0})]
    (let [pairs (mapv #(layer-signal % s) drones)
          sum-l (reduce #(.add el %1 %2) (map first pairs))
          sum-r (reduce #(.add el %1 %2) (map second pairs))
          ;; smoothing starts at 0 in a fresh worklet: a free ~1s fade-in
          master (.smooth el (.tau2pole el 0.35)
                          (.const el #js {:key "master" :value 0.85}))]
      [(.tanh el (.mul el sum-l master))
       (.tanh el (.mul el sum-r master))])))

(defn- push-graph!
  "Render the current graph into the worklet, if the engine exists yet."
  []
  (when-let [{:keys [core]} (:engine @state)]
    (let [[l r] (build-master @state)]
      (.render core l r))))

;; ---------------------------------------------------------------------------
;; Uploaded samples -> the renderer's virtual file system

(defn- flush-samples!
  "Hand any not-yet-sent sample buffers to the renderer. Paths are
   versioned by the media library (VFS entries are immutable), so only
   fresh ones need sending."
  []
  (when-let [^js core (:core (:engine @state))]
    (let [{:keys [sample-buffers vfs-sent]} @state
          fresh (remove (comp vfs-sent key) sample-buffers)]
      (when (seq fresh)
        (.updateVirtualFileSystem core (clj->js (into {} fresh)))
        (swap! state update :vfs-sent into (map key fresh))))))

(defn set-samples!
  "Receive the uploaded-audio library from lispshaders.media:
   {:buffers {vfs-path Float32Array} :paths {name vfs-path}}. Repoints
   (sample ...)/(scrub ...) at the new data and re-renders so a playing
   drone picks it up immediately."
  [{:keys [buffers paths]}]
  (swap! state assoc :sample-buffers buffers)
  (lib/set-sample-paths! paths)
  (flush-samples!)
  (push-graph!))

;; ---------------------------------------------------------------------------
;; Engine lifecycle

(defn- init-engine!
  "Create the AudioContext + WebRenderer worklet and the analyser tap.
   Called lazily from the first ▶ press — audio contexts need a user
   gesture. Returns a promise of the engine map."
  []
  (let [ctx (js/AudioContext.)
        core (WebRenderer.)]
    (-> (.initialize core ctx
                     #js {:numberOfInputs 0
                          :numberOfOutputs 1
                          :outputChannelCount #js [2]})
        (.then (fn [node]
                 (let [analyser (.createAnalyser ctx)]
                   (set! (.-fftSize analyser) 256)
                   (set! (.-smoothingTimeConstant analyser) 0.5)
                   (.connect node analyser)
                   (.connect analyser (.-destination ctx))
                   (.on core "meter"
                        (fn [event]
                          (when-let [on-level (:on-level @state)]
                            (on-level (.-source event)
                                      (max (js/Math.abs (.-min event))
                                           (js/Math.abs (.-max event)))))))
                   (let [engine {:ctx ctx :core core :analyser analyser
                                 :fft (js/Float32Array. (.-frequencyBinCount analyser))}]
                     (swap! state assoc :engine engine)
                     ;; samples may have been uploaded before the first ▶
                     (flush-samples!)
                     engine)))))))

(defn drones-running? [] (:running? @state))
(defn has-drones? [] (boolean (seq (:drones @state))))

(defn toggle-drones!
  "Start/stop the soundscape. Builds the engine on first use; after that,
   stopping suspends the context (the graph — and its sense of time —
   freezes in place, like pausing a shader). Returns a promise."
  []
  (let [{:keys [engine running?]} @state]
    (cond
      running?
      (do (swap! state assoc :running? false)
          (.suspend (:ctx engine)))

      engine
      (do (swap! state assoc :running? true)
          (push-graph!)
          (.resume (:ctx engine)))

      :else
      (-> (init-engine!)
          (.then (fn [{:keys [ctx]}]
                   (swap! state assoc :running? true)
                   (push-graph!)
                   (.resume ctx)))))))

;; ---------------------------------------------------------------------------
;; Mute / solo, from the drone cards

(defn muted? [drone-name] (contains? (:muted @state) drone-name))
(defn soloed? [drone-name] (= drone-name (:solo @state)))

(defn toggle-mute! [drone-name]
  (swap! state update :muted
         #(if (contains? % drone-name) (disj % drone-name) (conj % drone-name)))
  (push-graph!))

(defn toggle-solo! [drone-name]
  (swap! state update :solo #(when (not= % drone-name) drone-name))
  (push-graph!))

(defn capture-stream
  "A live MediaStream of the drone mix, for the recorder — or nil when the
   engine isn't running (the recording simply has no audio track then).
   Returns {:stream .. :stop fn} — call :stop when the recording ends."
  []
  (let [{:keys [engine running?]} @state]
    (when (and engine running?)
      (let [{:keys [ctx analyser]} engine
            dest (.createMediaStreamDestination ctx)]
        (.connect analyser dest)
        {:stream (.-stream dest)
         :stop #(.disconnect analyser dest)}))))

(defn set-level-listener!
  "Register (fn [drone-name level]) to receive live per-layer levels from
   the meter nodes (level is peak |amplitude|, roughly 0..1)."
  [f]
  (swap! state assoc :on-level f))

;; ---------------------------------------------------------------------------
;; Param knobs (the params tab)

(defn prime-param-values!
  "Load the current knob values into the runtime *without* re-rendering —
   used right before a recompile so the fresh graph builds with correct
   values. `values` is {param-name value}."
  [values]
  (lib/set-param-values! values))

(defn set-param-values!
  "A knob moved: update the runtime and re-render so the playing graph
   glides to the new value (Elementary diffs the keyed const, so it ramps
   smoothly rather than clicking)."
  [values]
  (lib/set-param-values! values)
  (push-graph!))

;; ---------------------------------------------------------------------------
;; Entry point from the compile pipeline

(defn apply!
  "Take the compiler's :drones and make them real: build each graph
   function, validate by constructing the full mix once, and — if the
   engine is live — render it (Elementary diffs against the playing graph,
   so the drone morphs without restarting). Returns nil on success or an
   error message."
  [drones]
  (try
    (let [prepared (mapv (fn [{:keys [js-body] :as spec}]
                           (assoc spec :fn (lib/make-drone-fn js-body)))
                         drones)
          names (into #{} (map :name) prepared)]
      ;; validate before committing, so mute/solo toggles never re-render
      ;; a graph that can't build
      (build-master {:drones prepared :muted #{} :solo nil})
      (swap! state
             (fn [s]
               (-> s
                   (assoc :drones prepared)
                   ;; forget mute/solo for drones that were removed
                   (update :muted #(into #{} (filter names) %))
                   (update :solo #(when (names %) %)))))
      (push-graph!)
      nil)
    (catch :default e (or (ex-message e) (str e)))))

;; ---------------------------------------------------------------------------
;; Analysis: the audio -> shader bridge

(def ^:private band-ranges
  ;; FFT bin ranges (of 128 bins spanning 0..~24kHz): lows, mids, highs
  {:bass [1 4] :mid [4 24] :treble [24 72]})

(defn- band-energy [fft [from to]]
  (let [n (- to from)]
    (if (pos? n)
      (loop [i from, sum 0.0]
        (if (< i to)
          ;; analyser values are dB (-Infinity..0); map ~[-90,-30] to 0..1
          (recur (inc i) (+ sum (-> (aget fft i) (+ 90) (/ 60) (max 0) (min 1))))
          (/ sum n)))
      0)))

(defn frame-data
  "Called by the render loop each frame. Returns {:audio [bass mid treble
   level]} with fast-attack / slow-release smoothing so visuals pulse
   instead of flickering."
  []
  (let [{:keys [engine levels running?]} @state]
    (if-not (and engine running?)
      {:audio [0 0 0 0]}
      (let [{:keys [analyser fft]} engine]
        (.getFloatFrequencyData analyser fft)
        (let [bass (band-energy fft (:bass band-ranges))
              mid (band-energy fft (:mid band-ranges))
              treble (band-energy fft (:treble band-ranges))
              level (/ (+ bass mid treble) 3)
              smoothed (mapv (fn [previous instant] (max instant (* previous 0.92)))
                             levels [bass mid treble level])]
          (swap! state assoc :levels smoothed)
          {:audio smoothed})))))
