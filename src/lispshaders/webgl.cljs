(ns lispshaders.webgl
  "WebGL2 runtime: owns the canvas, a fullscreen-triangle program, the
   animation loop, the time/mouse uniforms, and the pool of uploaded-image
   textures. The fragment shader can be hot-swapped at any moment without
   resetting the clock — that's what makes live editing feel continuous."
  (:require [lispshaders.compiler :as compiler]))

(defonce ^:private state (atom nil))

(def ^:private vertex-source
  ;; A single triangle that covers the screen, generated from gl_VertexID —
  ;; no vertex buffers needed.
  "#version 300 es
void main() {
  vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
  gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
}")

(defn- compile-stage [gl stage-type source]
  (let [shader (.createShader gl stage-type)]
    (.shaderSource gl shader source)
    (.compileShader gl shader)
    (if (.getShaderParameter gl shader (.-COMPILE_STATUS gl))
      {:shader shader}
      (let [log (.getShaderInfoLog gl shader)]
        (.deleteShader gl shader)
        {:error log}))))

(defn- link-program
  "Build a complete program from our fixed vertex stage + the given fragment
   source. Returns {:program ...} or {:error log}."
  [gl fragment-source]
  (let [{vs :shader vs-err :error} (compile-stage gl (.-VERTEX_SHADER gl) vertex-source)]
    (if vs-err
      {:error vs-err}
      (let [{fs :shader fs-err :error} (compile-stage gl (.-FRAGMENT_SHADER gl) fragment-source)]
        (if fs-err
          (do (.deleteShader gl vs) {:error fs-err})
          (let [program (.createProgram gl)]
            (.attachShader gl program vs)
            (.attachShader gl program fs)
            (.linkProgram gl program)
            (.deleteShader gl vs)
            (.deleteShader gl fs)
            (if (.getProgramParameter gl program (.-LINK_STATUS gl))
              {:program program}
              (let [log (.getProgramInfoLog gl program)]
                (.deleteProgram gl program)
                {:error log}))))))))

;; ---------------------------------------------------------------------------
;; Uploaded images -> sampler2D uniforms

(defn- bind-textures!
  "Point each u_img_* sampler uniform of the current program at its
   texture unit, and (re)bind the textures onto those units."
  []
  (when-let [{:keys [gl program textures]} @state]
    (when program
      (.useProgram gl program)
      (doseq [[image-name {:keys [tex unit]}] textures]
        (.activeTexture gl (+ (.-TEXTURE0 gl) unit))
        (.bindTexture gl (.-TEXTURE_2D gl) tex)
        (when-let [loc (.getUniformLocation gl program (compiler/texture-uniform-name image-name))]
          (.uniform1i gl loc unit))))))

(defn- upload-texture!
  "One uploaded image (already flipped to GL's bottom-left origin) into a
   mipmapped, repeating texture — so (texture ...) tiles the plane."
  [gl bitmap]
  (let [t2d (.-TEXTURE_2D gl)
        tex (.createTexture gl)]
    (.bindTexture gl t2d tex)
    (.texImage2D gl t2d 0 (.-RGBA gl) (.-RGBA gl) (.-UNSIGNED_BYTE gl) bitmap)
    (.generateMipmap gl t2d)
    (.texParameteri gl t2d (.-TEXTURE_WRAP_S gl) (.-REPEAT gl))
    (.texParameteri gl t2d (.-TEXTURE_WRAP_T gl) (.-REPEAT gl))
    (.texParameteri gl t2d (.-TEXTURE_MIN_FILTER gl) (.-LINEAR_MIPMAP_LINEAR gl))
    (.texParameteri gl t2d (.-TEXTURE_MAG_FILTER gl) (.-LINEAR gl))
    tex))

(defn set-images!
  "Sync the uploaded-image library into GL textures: [{:name .. :bitmap ..}].
   Uploads are rare, so the whole pool is simply rebuilt each time."
  [images]
  (when-let [{:keys [gl textures]} @state]
    (doseq [{:keys [tex]} (vals textures)] (.deleteTexture gl tex))
    (swap! state assoc :textures
           (into {} (map-indexed (fn [i {:keys [name bitmap]}]
                                   ;; units from 1 up, leaving 0 as GL's scratch unit
                                   [name {:tex (upload-texture! gl bitmap) :unit (inc i)}])
                                 images)))
    (bind-textures!)))

;; ---------------------------------------------------------------------------
;; Param knobs -> float uniforms
;;
;; Each (defparam ...) compiles to a `u_param_<name>` uniform. The uniform
;; locations must be re-resolved every time the program changes, so the list
;; of param names is remembered and looked up here from both set-shader! and
;; set-params!.

(defn- resolve-param-locs!
  "Cache the uniform location of every known param name against the current
   program (locations are invalid after a relink)."
  []
  (when-let [{:keys [gl program param-names]} @state]
    (when program
      (swap! state assoc :param-locs
             (into {} (for [nm param-names]
                        [nm (.getUniformLocation gl program (compiler/param-uniform-name nm))]))))))

(defn set-params!
  "Tell the runtime which param knobs the current shader declares (their
   names), so their uniforms can be found and fed each frame."
  [param-names]
  (swap! state assoc :param-names (vec param-names))
  (resolve-param-locs!))

(defn set-param-provider!
  "Register a fn returning {param-name value}, sampled once per frame to
   feed the u_param_* uniforms."
  [provider]
  (swap! state assoc :param-provider provider))

(defn set-shader!
  "Swap in a new fragment shader. Returns nil on success, or the GLSL
   compile/link log on failure (the previous shader keeps running)."
  [fragment-source]
  (when-let [{:keys [gl program]} @state]
    (let [{new-program :program error :error} (link-program gl fragment-source)]
      (if error
        error
        (do (when program (.deleteProgram gl program))
            (swap! state assoc
                   :program new-program
                   :uniforms {:time (.getUniformLocation gl new-program "u_time")
                              :resolution (.getUniformLocation gl new-program "u_resolution")
                              :mouse (.getUniformLocation gl new-program "u_mouse")
                              :audio (.getUniformLocation gl new-program "u_audio")})
            (resolve-param-locs!)
            (bind-textures!)
            nil)))))

(defn set-audio-provider!
  "Register a fn returning {:audio [bass mid treble level]}, sampled once
   per frame to feed the u_audio uniform."
  [provider]
  (swap! state assoc :audio-provider provider))

;; ---------------------------------------------------------------------------
;; Clock — pausable without losing the shader's sense of "now"

(defn current-time
  "Shader time in seconds, respecting pause."
  []
  (let [{:keys [playing? accum epoch]} @state]
    (if playing?
      (+ accum (/ (- (js/performance.now) epoch) 1000))
      accum)))

(defn playing? [] (:playing? @state))

(defn toggle-play! []
  (let [now (js/performance.now)]
    (swap! state (fn [{:keys [playing? accum epoch] :as s}]
                   (if playing?
                     (assoc s :playing? false :accum (+ accum (/ (- now epoch) 1000)))
                     (assoc s :playing? true :epoch now))))))

(defn restart-time! []
  (swap! state assoc :accum 0 :epoch (js/performance.now)))

(defn rewind!
  "Jump shader time back by `secs` (never past zero), whether playing or
   paused — replay the last few seconds of a moment that just went by."
  [secs]
  (swap! state (fn [{:keys [playing? accum epoch] :as s}]
                 (let [now (js/performance.now)
                       elapsed (if playing? (+ accum (/ (- now epoch) 1000)) accum)]
                   (assoc s :accum (max 0 (- elapsed secs)) :epoch now)))))

(defn capture-stream
  "A MediaStream of the rendering canvas at `fps`, for the recorder."
  [fps]
  (when-let [{:keys [canvas]} @state]
    (.captureStream canvas fps)))

;; ---------------------------------------------------------------------------
;; Render loop

(defn- resize-to-display!
  "Match the drawing buffer to the canvas's CSS size (capped device pixel
   ratio keeps retina displays from quadrupling the pixel work)."
  [gl canvas]
  (let [dpr (min 2 (or (.-devicePixelRatio js/window) 1))
        width (js/Math.round (* dpr (.-clientWidth canvas)))
        height (js/Math.round (* dpr (.-clientHeight canvas)))]
    (when (and (pos? width)
               (or (not= (.-width canvas) width) (not= (.-height canvas) height)))
      (set! (.-width canvas) width)
      (set! (.-height canvas) height)
      (.viewport gl 0 0 width height))))

(defn- frame [timestamp]
  (when-let [{:keys [gl canvas program uniforms mouse on-stats
                     audio-provider param-provider param-locs last-stamp fps]} @state]
    (resize-to-display! gl canvas)
    (when program
      (let [[mx my] (or mouse [(/ (.-width canvas) 2) (/ (.-height canvas) 2)])
            {:keys [audio]} (when audio-provider (audio-provider))
            [bass mid treble level] (or audio [0 0 0 0])]
        (.useProgram gl program)
        (.uniform1f gl (:time uniforms) (current-time))
        (.uniform2f gl (:resolution uniforms) (.-width canvas) (.-height canvas))
        (.uniform2f gl (:mouse uniforms) mx my)
        (.uniform4f gl (:audio uniforms) bass mid treble level)
        ;; feed each knob's current value into its uniform
        (when (and param-provider (seq param-locs))
          (let [values (param-provider)]
            (doseq [[nm loc] param-locs]
              (when loc (.uniform1f gl loc (or (get values nm) 0))))))
        (.drawArrays gl (.-TRIANGLES gl) 0 3)))
    ;; exponential moving average keeps the fps readout steady
    (let [dt (- timestamp (or last-stamp timestamp))
          instant (if (pos? dt) (/ 1000 dt) 60)
          smoothed (+ (* 0.95 (or fps instant)) (* 0.05 instant))]
      (swap! state assoc :last-stamp timestamp :fps smoothed)
      (when (and on-stats (zero? (mod (:frame-count (swap! state update :frame-count inc)) 20)))
        (on-stats {:fps (js/Math.round smoothed) :time (current-time)})))
    (swap! state assoc :raf-id (js/requestAnimationFrame frame))))

(defn- track-mouse! [canvas]
  (.addEventListener canvas "pointermove"
                     (fn [event]
                       (let [rect (.getBoundingClientRect canvas)
                             scale-x (/ (.-width canvas) (max 1 (.-width rect)))
                             scale-y (/ (.-height canvas) (max 1 (.-height rect)))
                             x (* (- (.-clientX event) (.-left rect)) scale-x)
                             ;; GL's y axis points up; the DOM's points down
                             y (* (- (.-bottom rect) (.-clientY event)) scale-y)]
                         (swap! state assoc :mouse [x y])))))

(defn init!
  "Set up WebGL2 on the canvas and start the render loop. Safe to call
   again after hot reload — an existing loop is cancelled first.
   `on-stats` receives {:fps .. :time ..} a few times per second."
  [canvas on-stats]
  (when-let [{:keys [raf-id]} @state]
    (when raf-id (js/cancelAnimationFrame raf-id)))
  ;; preserveDrawingBuffer keeps the last frame readable between draws, so
  ;; captureStream (the record button) always gets real pixels
  (let [gl (.getContext canvas "webgl2" #js {:antialias false
                                             :preserveDrawingBuffer true})]
    (if-not gl
      {:error "WebGL2 isn't available in this browser"}
      (do (reset! state {:gl gl
                         :canvas canvas
                         :program nil
                         :playing? true
                         :accum 0
                         :epoch (js/performance.now)
                         :frame-count 0
                         :on-stats on-stats})
          (track-mouse! canvas)
          (swap! state assoc :raf-id (js/requestAnimationFrame frame))
          {:ok true}))))
