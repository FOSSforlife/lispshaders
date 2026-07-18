(ns lispshaders.dump
  "Dev utility: compile every gallery example and write the generated GLSL
   to files so it can be inspected or fed to an external validator.
   Run with: npx shadow-cljs compile dump && node out/dump.js <out-dir>"
  (:require ["fs" :as fs]
            ["path" :as path]
            [lispshaders.compiler :as compiler]
            [lispshaders.examples :as examples]))

(defn main! [& [out-dir]]
  (let [dir (or out-dir "out/glsl")]
    (fs/mkdirSync dir #js {:recursive true})
    (doseq [{:keys [name code]} examples/gallery]
      (let [{:keys [glsl shadertoy]} (compiler/compile-shader code)
            base (.replace name (js/RegExp. "[^a-z0-9]+" "g") "-")]
        (fs/writeFileSync (path/join dir (str base ".frag")) glsl)
        (fs/writeFileSync (path/join dir (str base ".shadertoy.frag")) shadertoy)
        (println "wrote" base)))))
