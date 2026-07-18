(ns lispshaders.editor
  "Thin wrapper around CodeMirror 6 with Clojure syntax highlighting."
  (:require ["codemirror" :refer [basicSetup]]
            ["@codemirror/view" :refer [EditorView]]
            ["@codemirror/language" :refer [StreamLanguage]]
            ["@codemirror/legacy-modes/mode/clojure" :refer [clojure]]
            ["@codemirror/theme-one-dark" :refer [oneDark]]))

(defn create!
  "Mount an editor under `parent` and call `on-change` with the full
   document text on every edit. Returns the EditorView."
  [parent initial-doc on-change]
  (let [change-watcher (.. EditorView -updateListener
                           (of (fn [^js update]
                                 (when (.-docChanged update)
                                   (on-change (.. update -state -doc (toString)))))))]
    (EditorView. #js {:parent parent
                      :doc initial-doc
                      :extensions #js [basicSetup
                                       (.define StreamLanguage clojure)
                                       oneDark
                                       change-watcher]})))

(defn set-doc!
  "Replace the entire document (used when loading an example)."
  [view text]
  (.dispatch view #js {:changes #js {:from 0
                                     :to (.. view -state -doc -length)
                                     :insert text}}))

(defn current-doc [view]
  (.. view -state -doc (toString)))
