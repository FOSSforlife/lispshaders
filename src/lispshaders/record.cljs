(ns lispshaders.record
  "One-button capture of the running program: the canvas's frames plus the
   live drone mix, muxed by MediaRecorder into a video file that downloads
   when recording stops. This is the realtime path — it records exactly
   what you're seeing and hearing, dropped frames and all. (A frame-exact
   offline render is sketched in ROADMAP §7.)"
  (:require [clojure.string :as str]
            [lispshaders.webgl :as webgl]
            [lispshaders.audio :as audio]))

(defonce ^:private session (atom nil)) ;; {:recorder :audio-tap} while recording

(defn recording? [] (some? @session))

(defn- pick-mime-type
  "The best container/codec this browser can record. WebM almost
   everywhere; Safari answers to mp4."
  []
  (some #(when (js/MediaRecorder.isTypeSupported %) %)
        ["video/webm;codecs=vp9,opus"
         "video/webm;codecs=vp8,opus"
         "video/webm"
         "video/mp4"]))

(defn- download! [blob mime-type]
  (let [extension (if (str/includes? mime-type "mp4") "mp4" "webm")
        stamp (-> (js/Date.) .toISOString (str/replace #"[:.]" "-"))
        url (js/URL.createObjectURL blob)
        link (js/document.createElement "a")]
    (set! (.-href link) url)
    (set! (.-download link) (str "lispshaders-" stamp "." extension))
    (.click link)
    (js/setTimeout #(js/URL.revokeObjectURL url) 10000)))

(defn- start! []
  (when-let [video-stream (webgl/capture-stream 60)]
    (let [audio-tap (audio/capture-stream) ;; nil when the drone isn't running
          tracks (concat (array-seq (.getTracks video-stream))
                         (some-> audio-tap :stream .getTracks array-seq))
          mime-type (pick-mime-type)
          recorder (js/MediaRecorder. (js/MediaStream. (into-array tracks))
                                      #js {:mimeType mime-type
                                           :videoBitsPerSecond 12000000})
          chunks #js []]
      (set! (.-ondataavailable recorder)
            #(when (pos? (.. % -data -size)) (.push chunks (.-data %))))
      (set! (.-onstop recorder)
            (fn []
              (when audio-tap ((:stop audio-tap)))
              (download! (js/Blob. chunks #js {:type mime-type}) mime-type)))
      (.start recorder 1000) ;; chunk every second so long takes stay safe
      (reset! session {:recorder recorder}))))

(defn- stop! []
  (when-let [{:keys [recorder]} @session]
    (.stop recorder)
    (reset! session nil)))

(defn toggle!
  "Start recording, or stop and download what was captured."
  []
  (if (recording?) (stop!) (start!)))
