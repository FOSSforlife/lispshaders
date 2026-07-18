(ns lispshaders.media
  "The user's uploaded media: audio files that drones can play with
   (sample ...) / (scrub ...), and images that shaders can read with
   (texture ...).

   Files persist in IndexedDB — the same spirit as the localStorage code
   autosave: everything stays in the browser, nothing leaves the machine.
   On startup (and on every upload) each file is decoded once and pushed
   into the engines that consume it: audio becomes a mono Float32Array in
   the Elementary renderer's virtual file system, an image becomes a WebGL
   texture. Uploading a file with the same name replaces it everywhere —
   live, like every other edit in this app."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [lispshaders.audio :as audio]
            [lispshaders.webgl :as webgl]))

(defonce library
  ;; name -> entry; sample entries {:duration :path :data}, image entries
  ;; {:url :width :height :bitmap}. A reagent atom so the tabs stay live.
  (r/atom {:samples {} :images {}}))

(defonce ^:private on-change (atom nil))
(defonce ^:private path-rev (atom 0))

;; ---------------------------------------------------------------------------
;; Names

(defn- slugify
  "Filename -> the name users type in code: lowercase, extension dropped,
   anything odd collapsed to single dashes. \"My Loop (2).wav\" -> \"my-loop-2\"."
  [filename]
  (let [slug (-> filename
                 (str/replace #"\.[A-Za-z0-9]+$" "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))]
    (if (str/blank? slug) "file" slug)))

(defn sample-names
  "The uploaded audio names, as a set — the compiler checks (sample ...) calls against it."
  []
  (set (keys (:samples @library))))

(defn image-names
  "The uploaded image names, sorted — order fixes the Shadertoy iChannel mapping."
  []
  (vec (sort (keys (:images @library)))))

;; ---------------------------------------------------------------------------
;; IndexedDB persistence (one store of {name, kind, blob} records)

(defn- idb-request
  "Wrap an IDBRequest into a promise."
  [request]
  (js/Promise. (fn [resolve reject]
                 (set! (.-onsuccess request) #(resolve (.-result request)))
                 (set! (.-onerror request) #(reject (.-error request))))))

(defn- open-db []
  (let [request (js/indexedDB.open "lispshaders-media" 1)]
    (set! (.-onupgradeneeded request)
          (fn [] (.createObjectStore (.-result request) "files" #js {:keyPath "key"})))
    (idb-request request)))

(defn- with-store
  "Run (f store) inside a fresh transaction and return its promised result."
  [mode f]
  (.then (open-db)
         (fn [db] (f (.objectStore (.transaction db #js ["files"] mode) "files")))))

(defn- persist! [kind file-name blob]
  ;; records outlive any one build of the app, so their keys are literal
  ;; strings — never subject to release-build property renaming
  (with-store "readwrite"
    #(idb-request (.put % (js-obj "key" (str (name kind) "/" file-name)
                                  "kind" (name kind) "name" file-name "blob" blob)))))

(defn- unpersist! [kind file-name]
  (with-store "readwrite"
    #(idb-request (.delete % (str (name kind) "/" file-name)))))

(defn- load-persisted []
  (with-store "readonly" #(idb-request (.getAll %))))

;; ---------------------------------------------------------------------------
;; Decoding

(defonce ^:private device-sample-rate
  ;; Decode at the hardware rate the (lazily created) audio engine will
  ;; run at, so rate 1.0 plays samples at true pitch. A throwaway context
  ;; just reports the rate; it never produces sound.
  (delay (let [probe (js/AudioContext.)
               rate (.-sampleRate probe)]
           (.close probe)
           rate)))

(defn- mono-mix
  "Average an AudioBuffer's channels into one Float32Array — drones place
   sounds in the stereo field themselves, with pan and space."
  [audio-buffer]
  (let [channels (.-numberOfChannels audio-buffer)]
    (if (= 1 channels)
      (.getChannelData audio-buffer 0)
      (let [length (.-length audio-buffer)
            out (js/Float32Array. length)]
        (dotimes [c channels]
          (let [data (.getChannelData audio-buffer c)]
            (dotimes [i length]
              (aset out i (+ (aget out i) (/ (aget data i) channels))))))
        out))))

(defn- decode-audio
  "Blob -> promise of a sample entry. The VFS path is versioned because
   renderer VFS entries are immutable — a re-upload needs a fresh path."
  [file-name blob]
  (-> (.arrayBuffer blob)
      (.then #(.decodeAudioData (js/OfflineAudioContext. 1 1 @device-sample-rate) %))
      (.then (fn [audio-buffer]
               {:duration (.-duration audio-buffer)
                :path (str file-name "#" (swap! path-rev inc))
                :data (mono-mix audio-buffer)}))))

(defn- decode-image
  "Blob -> promise of an image entry, flipped to GL's bottom-left origin
   so (texture \"name\" uv) reads the image right way up."
  [_file-name blob]
  (-> (js/createImageBitmap blob #js {:imageOrientation "flipY"})
      (.then (fn [bitmap]
               {:url (js/URL.createObjectURL blob)
                :width (.-width bitmap)
                :height (.-height bitmap)
                :bitmap bitmap}))))

;; ---------------------------------------------------------------------------
;; Pushing into the engines

(defn push-to-engines!
  "Hand the decoded library to its consumers: sample buffers to the audio
   renderer's virtual file system, bitmaps to the WebGL texture pool.
   Also called by the app after (re)creating the GL context."
  []
  (let [{:keys [samples images]} @library]
    (audio/set-samples!
     {:buffers (into {} (map (fn [[_ {:keys [path data]}]] [path data])) samples)
      :paths (into {} (map (fn [[n {:keys [path]}]] [n path])) samples)})
    (webgl/set-images!
     (map (fn [n] {:name n :bitmap (get-in images [n :bitmap])}) (image-names)))))

(defn- changed! []
  (push-to-engines!)
  (when-let [notify @on-change] (notify)))

;; ---------------------------------------------------------------------------
;; The public library operations

(def ^:private kind->config
  {:samples {:decode decode-audio :store-kind :audio}
   :images  {:decode decode-image :store-kind :image}})

(defn- ingest
  "Decode one file into the in-memory library. Returns a promise."
  [kind file-name blob]
  (-> ((get-in kind->config [kind :decode]) file-name blob)
      (.then (fn [entry]
               (when-let [old-url (get-in @library [kind file-name :url])]
                 (js/URL.revokeObjectURL old-url))
               (swap! library assoc-in [kind file-name] entry)))
      (.catch (fn [e]
                (js/console.warn "couldn't decode" file-name e)
                (unpersist! (get-in kind->config [kind :store-kind]) file-name)))))

(defn add-files!
  "Add uploaded files (a FileList) to the library under `kind` (:samples
   or :images), persist them, and notify once they're usable."
  [kind files]
  (-> (js/Promise.all
       (into-array
        (for [file (array-seq files)
              :let [file-name (slugify (.-name file))]]
          (do (persist! (get-in kind->config [kind :store-kind]) file-name file)
              (ingest kind file-name file)))))
      (.then changed!)))

(defn remove!
  "Delete one file from the library and from IndexedDB."
  [kind file-name]
  (when-let [url (get-in @library [kind file-name :url])]
    (js/URL.revokeObjectURL url))
  (swap! library update kind dissoc file-name)
  (unpersist! (get-in kind->config [kind :store-kind]) file-name)
  (changed!))

(defn init!
  "Load everything persisted in earlier sessions, then call `notify` (also
   called after any later library change) so the app recompiles against
   the loaded names."
  [notify]
  (reset! on-change notify)
  (-> (load-persisted)
      (.then (fn [records]
               (js/Promise.all
                (into-array
                 (for [record (array-seq records)]
                   (ingest (if (= "audio" (unchecked-get record "kind")) :samples :images)
                           (unchecked-get record "name") (unchecked-get record "blob")))))))
      (.then (fn [_] (changed!)))
      (.catch #(js/console.warn "media library unavailable:" %))))
