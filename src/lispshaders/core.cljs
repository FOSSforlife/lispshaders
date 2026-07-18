(ns lispshaders.core
  "The lispshaders UI: a Clojure editor on the left, a live WebGL2 canvas on
   the right, and tabs for the reference card and the compiled GLSL /
   Shadertoy exports."
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdom]
            [clojure.string :as str]
            [lispshaders.compiler :as compiler]
            [lispshaders.webgl :as webgl]
            [lispshaders.audio :as audio]
            [lispshaders.editor :as editor]
            [lispshaders.examples :as examples]
            [lispshaders.media :as media]
            [lispshaders.record :as record]
            [lispshaders.prelude :as prelude]))

(def storage-key "lispshaders/code")

(defonce app-state
  (r/atom {:status {:ok? true :message "compiling…"}
           :compiled nil
           :tab :reference
           :playing? true
           :stats nil
           :param-values {} ;; knob name -> current value, survives recompiles
           :example (:name (first examples/gallery))}))

(defonce editor-view (atom nil))
(defonce compile-timer (atom nil))

;; ---------------------------------------------------------------------------
;; Compile pipeline: editor text -> Clojure forms -> GLSL -> running shader

(defn- reconcile-param-values
  "Keep the knob positions the user set for params that survive a recompile,
   introduce newly-declared params at their default, and forget removed ones."
  [old-values param-specs]
  (into {} (for [{:keys [name default]} param-specs]
             [name (get old-values name default)])))

(defn- compile! [code]
  (js/localStorage.setItem storage-key code)
  (let [result (try (compiler/compile-shader code {:samples (media/sample-names)
                                                   :textures (media/image-names)})
                    (catch :default e {:error (or (ex-message e) (str e))}))]
    (if (:error result)
      (swap! app-state assoc :status {:ok? false :message (:error result)})
      (let [param-values (reconcile-param-values (:param-values @app-state) (:params result))
            ;; prime the runtime *before* building graphs so drones read the
            ;; live knob values, not zeros
            _ (audio/prime-param-values! param-values)
            glsl-log (webgl/set-shader! (:glsl result))
            _ (webgl/set-params! (map :name (:params result)))
            audio-log (audio/apply! (:drones result))]
        (swap! app-state assoc
               :compiled result
               :param-values param-values
               :drones-on? (audio/drones-running?)
               :status (cond
                         glsl-log {:ok? false
                                   :message (str "GLSL rejected the compiled shader:\n" glsl-log)}
                         audio-log {:ok? false :message (str "audio: " audio-log)}
                         :else {:ok? true :message "live"}))))))

(defn- set-param-value!
  "A knob moved: store its value (the WebGL loop reads it every frame) and
   nudge the drone engine so any sound reading it glides to the new value."
  [param-name value]
  (swap! app-state assoc-in [:param-values param-name] value)
  (audio/set-param-values! (:param-values @app-state)))

(defn- schedule-compile!
  "Debounce keystrokes so we compile at most ~4x/second while typing."
  [code]
  (some-> @compile-timer js/clearTimeout)
  (reset! compile-timer (js/setTimeout #(compile! code) 250)))

(defn- initial-code []
  (or (js/localStorage.getItem storage-key)
      examples/default-code))

(defn- load-example! [example-name]
  (when-let [example (examples/find-example example-name)]
    (swap! app-state assoc :example example-name)
    (when-let [view @editor-view]
      ;; set-doc! fires the editor's change listener, which schedules the compile
      (editor/set-doc! view (:code example)))))

;; ---------------------------------------------------------------------------
;; Components

(defn- mount-editor!
  "Ref callback: build the CodeMirror instance when the pane appears in the
   DOM, tear it down when it leaves. Defined at namespace level so re-renders
   reuse the same callback and React doesn't remount the editor."
  [element]
  (if element
    (let [code (initial-code)]
      (reset! editor-view (editor/create! element code schedule-compile!))
      (compile! code))
    (do (some-> @editor-view .destroy)
        (reset! editor-view nil))))

(defn- recompile-editor!
  "Recompile whatever's in the editor — used when the media library
   changes, since (sample ...)/(texture ...) validity depends on it."
  []
  (when-let [view @editor-view]
    (compile! (editor/current-doc view))))

(defn- mount-canvas! [canvas]
  (when canvas
    (let [{:keys [error]} (webgl/init! canvas #(swap! app-state assoc :stats %))]
      (webgl/set-audio-provider! audio/frame-data)
      (webgl/set-param-provider! #(:param-values @app-state))
      ;; a fresh GL context needs the uploaded images re-uploaded as textures
      (media/push-to-engines!)
      (when error
        (swap! app-state assoc :status {:ok? false :message error})))))

(defn- editor-pane []
  [:div.editor-pane {:ref mount-editor!}])

(defn- shader-canvas []
  [:canvas.shader-canvas {:ref mount-canvas!}])

(defn- toolbar []
  (let [{:keys [playing? drones-on? stats example compiled]} @app-state]
    [:header.toolbar
     [:span.logo "(" [:span.logo-lambda "λ"] " " [:span.logo-name "lispshaders"] ")"]
     [:select.example-select
      {:value example
       :on-change #(load-example! (.. % -target -value))}
      (for [{:keys [name]} examples/gallery]
        ^{:key name} [:option {:value name} name])]
     [:button.control
      {:on-click (fn []
                   (webgl/toggle-play!)
                   (swap! app-state assoc :playing? (webgl/playing?)))
       :title "Pause / resume shader time"}
      (if playing? "⏸ pause" "▶ play")]
     [:button.control
      {:on-click webgl/restart-time!
       :title "Rewind time to zero"}
      "⟲ t=0"]
     [:button.control
      {:on-click #(webgl/rewind! 10)
       :title "Jump shader time back ten seconds"}
      "↩ -10s"]
     [:button.control
      {:class (when (record/recording?) "recording")
       :on-click (fn []
                   (record/toggle!)
                   (swap! app-state assoc :recording? (record/recording?)))
       :title "Record the canvas (and the drone, if it's playing) to a video file"}
      (if (record/recording?) "◼ stop rec" "⏺ rec")]
     (when (seq (:drones compiled))
       [:button.control.drone
        {:on-click (fn []
                     (.then (audio/toggle-drones!)
                            #(swap! app-state assoc :drones-on? (audio/drones-running?))))
         :title "Start / stop the drone engine"}
        (if drones-on? "◼ stop drone" "◉ start drone")])
     [:span.stats
      (when stats
        (str (.toFixed (:time stats) 1) "s · " (:fps stats) " fps"))
      " · "
      [:a {:href "https://sites.eliasjackson.com/lispshaders/docs"} "Docs"]]]))

;; ---------------------------------------------------------------------------
;; Drones tab

(defonce drone-levels
  ;; name -> live level from the engine's meter nodes, with a little decay
  ;; so the bars fall gracefully instead of flickering
  (r/atom {}))

(defn- watch-drone-levels! []
  (audio/set-level-listener!
   (fn [drone-name level]
     (swap! drone-levels update drone-name
            (fn [previous] (max level (* 0.85 (or previous 0))))))))

(defn- level-meter [drone-name]
  (let [level (get @drone-levels drone-name 0)]
    [:div.level-track
     [:div.level-fill {:style {:width (str (* 100 (min 1 level)) "%")}}]]))

(defn- bump-audio-rev!
  "Mute/solo live outside app-state (in the audio engine), so nudge a
   counter to tell Reagent the cards need a fresh look."
  []
  (swap! app-state update :audio-rev (fnil inc 0)))

(defn- drone-card [{:keys [name channels]} muted? soloed?]
  [:div.drone-card
   [:div.drone-head
    [:span.drone-name name]
    [:span.drone-meta (if (= channels 2) "stereo" "mono")]]
   [level-meter name]
   [:div.drone-actions
    [:button.control
     {:class (when muted? "engaged")
      :on-click #(do (audio/toggle-mute! name) (bump-audio-rev!))
      :title "Silence this layer (it keeps running underneath)"}
     "mute"]
    [:button.control
     {:class (when soloed? "engaged")
      :on-click #(do (audio/toggle-solo! name) (bump-audio-rev!))
      :title "Hear only this layer"}
     "solo"]]])

(defn- drones-panel []
  (let [{:keys [compiled]} @app-state
        drones (:drones compiled)]
    (if (empty? drones)
      [:div.drones-empty
       [:p "No drones yet. A drone is a signal graph — the same idea as a"
        " shader, but the function runs in time instead of space:"]
       [:pre "(defsound hum\n  (->> (+ (saw :a1) (saw 55.4))\n       (lowpass (+ 300.0 (* 700.0 (lfo 0.1))) 1.2)\n       (* 0.3)\n       (space 0.4)))"]
       [:p "Press " [:code "◉ start drone"] " in the toolbar, then edit anything —"
        " the sound morphs live, it never restarts. Each " [:code "defsound"]
        " is one layer of the soundscape (mute/solo them here), and your shader"
        " hears it all through " [:code "bass"] " / " [:code "mid"] " / "
        [:code "treble"] " / " [:code "level"] ". Try the “drone garden” example."]]
      [:div.drones
       (for [drone drones]
         ^{:key (:name drone)} [drone-card drone
                                (audio/muted? (:name drone))
                                (audio/soloed? (:name drone))])])))

;; ---------------------------------------------------------------------------
;; Params tab: user-declared knobs (defparam)

(defn- param-card
  "One knob: its name, live value, a slider spanning its range, and a reset
   to the declared default (double-click the value)."
  [{:keys [name default min max]} value]
  [:div.param-card
   [:div.param-head
    [:span.param-name name]
    [:span.param-value
     {:title "Double-click to reset to the default"
      :on-double-click #(set-param-value! name default)}
     (.toFixed value 3)]]
   [:input.param-slider
    {:type "range" :min min :max max
     :step (/ (- max min) 200)
     :value value
     :on-change #(set-param-value! name (js/parseFloat (.. % -target -value)))}]
   [:div.param-range
    [:span (str min)]
    [:span (str max)]]])

(defn- params-panel []
  (let [{:keys [compiled param-values]} @app-state
        params (:params compiled)]
    (if (empty? params)
      [:div.params-empty
       [:p "No knobs yet. A " [:code "defparam"] " is a live dial — the same idea as a"
        " REAPER parameter or an Ableton macro. Declare one, then use its name"
        " anywhere a number fits, in the shader or a drone:"]
       [:pre "(defparam glow 0.6 0.0 1.0)\n\n(defn image [uv]\n  (* glow (vec3 (length uvc))))"]
       [:p "Turn the dial here and the image — and any drone that reads it — responds"
        " instantly, no recompile. The two-argument form " [:code "(defparam x 0.5)"]
        " gives a 0..1 knob. Exported Shadertoy code has no knobs, so each one is baked"
        " in at its current default as an editable " [:code "const"] "."]]
      [:div.params
       (for [{:keys [name default] :as spec} params]
         ^{:key name}
         [param-card spec (get param-values name default)])])))

;; ---------------------------------------------------------------------------
;; Samples & images tabs: the user's uploaded media

(defn- upload-button
  "A styled button wrapping a hidden file input. Uploads go through the
   media library, which persists them and pushes them into the engines."
  [kind accept label]
  [:label.control.upload label
   [:input {:type "file" :accept accept :multiple true
            :style {:display "none"}
            :on-change (fn [event]
                         (media/add-files! kind (.. event -target -files))
                         (set! (.. event -target -value) ""))}]])

(defn- media-card
  "One uploaded file: an optional preview, its name, a copyable usage
   snippet, and a remove button."
  [kind file-name meta-text snippet preview]
  [:div.media-card
   preview
   [:div.media-body
    [:div.media-head
     [:span.media-name file-name]
     [:span.media-meta meta-text]]
    [:code.media-snippet {:title "How to use it in your code"} snippet]]
   [:button.control.media-remove
    {:on-click #(media/remove! kind file-name)
     :title "Remove this file"}
    "✕"]])

(defn- samples-panel []
  (let [samples (:samples @media/library)]
    [:div.media-panel
     [:div.media-actions
      [upload-button :samples "audio/*" "+ add audio"]
      [:span.media-hint "Audio files stay in your browser, mixed to mono. Loop one with "
       [:code "(sample \"name\" 1.0)"] " or freeze it with " [:code "(scrub \"name\" pos)"]
       " inside a defsound."]]
     (when (seq samples)
       [:div.media-cards
        (for [[file-name {:keys [duration]}] (sort-by key samples)]
          ^{:key file-name}
          [media-card :samples file-name
           (str (.toFixed duration 1) "s")
           (str "(sample \"" file-name "\" 1.0)")
           nil])])]))

(defn- images-panel []
  (let [images (:images @media/library)]
    [:div.media-panel
     [:div.media-actions
      [upload-button :images "image/*" "+ add image"]
      [:span.media-hint "Images stay in your browser and tile the plane. Read one with "
       [:code "(:rgb (texture \"name\" uv))"] " in your shader."]]
     (when (seq images)
       [:div.media-cards
        (for [[file-name {:keys [url width height]}] (sort-by key images)]
          ^{:key file-name}
          [media-card :images file-name
           (str width "×" height)
           (str "(texture \"" file-name "\" uv)")
           [:img.media-thumb {:src url :alt file-name}]])])]))

(defn- reference-panel []
  [:div.reference
   (for [{:keys [title entries]} prelude/reference-groups]
     ^{:key title}
     [:section
      [:h3 title]
      (for [{:keys [sig doc]} entries]
        ^{:key sig}
        [:div.entry
         [:code sig]
         [:p doc]])])])

(defn- copy-to-clipboard! [text]
  (.. js/navigator -clipboard (writeText text)))

(defn- code-panel [text]
  (r/with-let [copied? (r/atom false)]
    [:div.code-panel
     [:button.control.copy
      {:on-click (fn []
                   (copy-to-clipboard! text)
                   (reset! copied? true)
                   (js/setTimeout #(reset! copied? false) 1500))}
      (if @copied? "✓ copied" "copy")]
     [:pre [:code text]]]))

(defn- output-tabs []
  (let [{:keys [tab compiled]} @app-state]
    [:div.output
     [:nav.tabs
      (for [[id label] [[:reference "reference"] [:params "params"] [:drones "drones"]
                        [:samples "samples"] [:images "images"]
                        [:glsl "GLSL"] [:shadertoy "Shadertoy export"]]]
        ^{:key id}
        [:button.tab {:class (when (= tab id) "active")
                      :on-click #(swap! app-state assoc :tab id)}
         label])]
     (case tab
       :reference [reference-panel]
       :params [params-panel]
       :drones [drones-panel]
       :samples [samples-panel]
       :images [images-panel]
       :glsl [code-panel (or (:glsl compiled) "; nothing compiled yet")]
       :shadertoy [code-panel (or (:shadertoy compiled) "; nothing compiled yet")])]))

(defn- status-bar []
  (let [{:keys [ok? message]} (:status @app-state)]
    [:footer.status {:class (if ok? "ok" "error")}
     (if ok?
       [:span "● " message]
       [:pre message])]))

(defn- app []
  [:div.app
   [toolbar]
   [:main.workspace
    [editor-pane]
    [:div.right-side
     [:div.canvas-wrap [shader-canvas]]
     [output-tabs]]]
   [status-bar]])

;; ---------------------------------------------------------------------------
;; Entry points

(defonce root (delay (rdom/create-root (js/document.getElementById "app"))))

(defn- render! []
  (rdom/render @root [app]))

(defn init! []
  (watch-drone-levels!)
  (media/init! recompile-editor!)
  (render!))

(defn after-load!
  "shadow-cljs hot-reload hook: re-render the UI, then recompile the current
   editor contents against the (possibly changed) compiler."
  []
  (render!)
  (when-let [view @editor-view]
    (compile! (editor/current-doc view))))
