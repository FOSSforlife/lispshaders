(ns lispshaders.compiler
  "Compiles a small Clojure dialect into GLSL fragment shaders and
   Elementary Audio signal graphs.

   The pipeline: read the source into forms with the Clojure reader, collect
   top-level `def`/`defn`/`defsound` forms, then compile the
   `(defn image [uv] ...)` entry point into GLSL and each `(defsound ...)`
   body into JS that builds an Elementary node graph. User functions are
   inlined at call sites, so types can always be inferred bottom-up from
   literals, constructors, and builtin signatures — no type annotations
   needed.

   Both targets share one code path: every expression compiles to
   {:stmts [lines] :code \"expr\" :type :vec3} and only the last-mile
   emission (declarations, operators, swizzles) differs. GLSL gets infix
   `(a + b)`; the audio target gets `add(a, b)` calls into the runtime
   library in lispshaders.audio-lib, where each op combines Elementary
   signal nodes instead of numbers (a `:float` there is a mono signal, a
   `:vec2` is a stereo pair)."
  (:require [cljs.reader :as reader]
            [clojure.string :as str]
            [lispshaders.prelude :as prelude]))

(declare compile-expr)

;; ---------------------------------------------------------------------------
;; Types & errors

(def ^:private type-names
  {:float "float" :int "int" :bool "bool"
   :vec2 "vec2" :vec3 "vec3" :vec4 "vec4"
   :mat2 "mat2" :mat3 "mat3" :mat4 "mat4"
   :string "string"})

(def ^:private vec-size {:vec2 2 :vec3 3 :vec4 4})
(def ^:private vec-of-size {1 :float 2 :vec2 3 :vec3 4 :vec4})
(def ^:private mat-type? #{:mat2 :mat3 :mat4})
(def ^:private mat->vec {:mat2 :vec2, :mat3 :vec3, :mat4 :vec4})

(defn- type-name [t] (get type-names t (str t)))

(defn- fail!
  "Abort compilation with a user-facing message, optionally showing the
   offending form so people can find it in their code."
  ([msg] (throw (ex-info msg {:shader-error true})))
  ([msg form] (throw (ex-info (str msg "\n  in: " (pr-str form)) {:shader-error true}))))

;; ---------------------------------------------------------------------------
;; Notes: keyword literals are frequencies
;;
;; Anywhere a number fits, an Overtone-style note keyword fits too — :a1 is
;; just another way to write 55.0. That keeps drone patches readable
;; ((osc :a2) instead of (osc 110.0)) and keeps pitches compatible with
;; Overtone sketches.

(def ^:private note-semitones
  {"c" 0 "d" 2 "e" 4 "f" 5 "g" 7 "a" 9 "b" 11})

(defn note->hz
  "Overtone-style note keyword -> frequency in Hz. (note->hz :a4) => 440.
   Accepts a letter, an optional # or b, and an octave: :c4 :a#3 :eb2."
  [kw]
  (let [s (str/lower-case (name kw))
        [_ letter accidental octave] (re-matches #"([a-g])([#b]?)(-?\d+)" s)]
    (when-not letter
      (fail! (str (pr-str kw) " isn't a note — keywords compile to frequencies,"
                  " written Overtone-style: :c4 :a#3 :eb2")))
    (let [midi (+ (* 12 (inc (js/parseInt octave 10)))
                  (note-semitones letter)
                  (case accidental "#" 1 "b" -1 0))]
      (* 440 (js/Math.pow 2 (/ (- midi 69) 12))))))

;; ---------------------------------------------------------------------------
;; GLSL identifiers

(defn- sanitize-name
  "Turn a Clojure symbol into a legal GLSL identifier."
  [sym]
  (let [s (-> (name sym)
              (str/replace "->" "_to_")
              (str/replace "-" "_")
              (str/replace "?" "_p")
              (str/replace "!" "")
              (str/replace #"[^A-Za-z0-9_]" ""))]
    (if (or (= s "") (re-matches #"[0-9].*" s))
      (str "v_" s)
      s)))

(defn texture-uniform-name
  "The sampler2D uniform an uploaded image is bound to, from its media
   name — shared with the WebGL runtime so both sides agree."
  [image-name]
  (str "u_img_" (sanitize-name image-name)))

(defn param-uniform-name
  "The float uniform a (defparam ...) knob is bound to, from its name —
   shared with the WebGL runtime so both sides agree on the identifier."
  [param-name]
  (str "u_param_" (sanitize-name param-name)))

(defn- fresh-name
  "Reserve a GLSL identifier, adding a numeric suffix when the natural name
   is taken (shadowed let bindings, collisions with GLSL keywords, etc.)."
  [ctx sym]
  (let [names (:names ctx)
        base (sanitize-name sym)]
    (loop [candidate base, n 2]
      (if (contains? @names candidate)
        (recur (str base "_" n) (inc n))
        (do (swap! names conj candidate)
            candidate)))))

;; ---------------------------------------------------------------------------
;; Target-specific emission (GLSL vs JS)

(defn- js-target? [ctx] (= :js (:lang ctx)))

(defn- emit-decl
  "A typed declaration in GLSL, an untyped `let` in JS."
  [ctx type name code]
  (if (js-target? ctx)
    (str "let " name " = " code ";")
    (str (type-name type) " " name " = " code ";")))

(def ^:private js-arith-fns {"+" "add", "-" "sub", "*" "mul", "/" "div"})

(def ^:private js-comparison-fns
  ;; In the audio target a comparison of signals is itself a signal (a 0/1
  ;; gate), so every comparison goes through the runtime library.
  {"<" "lt", ">" "gt", "<=" "lte", ">=" "gte", "==" "eq", "!=" "neq"})

(def ^:private js-logic-fns {"&&" "and", "||" "or"})

;; ---------------------------------------------------------------------------
;; Small helpers over compiled expressions

(defn- merge-stmts [results]
  (into [] (mapcat :stmts) results))

(defn- fmt-float
  "1 => \"1.0\" so GLSL treats every DSL number as a float."
  [x]
  (let [s (str x)]
    (if (re-find #"[.eE]" s) s (str s ".0"))))

(defn- as-float
  "Auto-promote ints (loop indices) to floats when they meet float math."
  [{:keys [type code] :as result}]
  (if (= type :int)
    (assoc result :type :float :code (str "float(" code ")"))
    result))

(defn- coerce
  "Force a result to an expected type, allowing only the int->float cast."
  [result expected what form]
  (let [result (if (and (= :int (:type result)) (= :float expected)) (as-float result) result)]
    (when (not= (:type result) expected)
      (fail! (str what " should be " (type-name expected)
                  " but is " (type-name (:type result))) form))
    result))

(defn- expect-value
  "Reject iter state-update vectors in ordinary expression positions."
  [result form]
  (when-not (:code result)
    (fail! "A state-update vector can only appear as the body of an iter (or the body of a let inside one)" form))
  result)

(defn- indent-lines [lines]
  (mapv #(str "  " (str/replace % "\n" "\n  ")) lines))

;; ---------------------------------------------------------------------------
;; Arithmetic with type promotion

(defn- binary-arith
  "Combine two compiled operands under +,-,*,/ following GLSL's promotion
   rules (scalar-with-vector broadcasts; * also handles matrices)."
  [ctx op a b form]
  (let [both-int? (and (= :int (:type a)) (= :int (:type b)))
        a (if both-int? a (as-float a))
        b (if both-int? b (as-float b))
        ta (:type a) tb (:type b)
        result-type
        (cond
          both-int? :int
          (and (= ta tb) (not (mat-type? ta))) ta
          (and (= ta tb) (mat-type? ta) (#{"+" "-" "*"} op)) ta
          (and (= ta :float) (vec-size tb)) tb
          (and (vec-size ta) (= tb :float)) ta
          (and (= ta :float) (mat-type? tb) (= op "*")) tb
          (and (mat-type? ta) (= tb :float) (#{"*" "/"} op)) ta
          (and (mat-type? ta) (= tb (mat->vec ta)) (= op "*")) tb
          (and (mat-type? tb) (= ta (mat->vec tb)) (= op "*")) ta
          :else (fail! (str "Can't apply " op " to " (type-name ta) " and " (type-name tb)) form))]
    {:stmts (into (:stmts a) (:stmts b))
     :code (if (js-target? ctx)
             (str (js-arith-fns op) "(" (:code a) ", " (:code b) ")")
             (str "(" (:code a) " " op " " (:code b) ")"))
     :type result-type}))

(defn- compile-arith [glsl-op ctx env [_ & args :as form]]
  (let [results (mapv #(expect-value (compile-expr ctx env %) form) args)]
    (case (count results)
      0 (fail! (str glsl-op " needs at least one argument") form)
      1 (let [r (first results)]
          (case glsl-op
            "-" (assoc r :code (if (js-target? ctx)
                                 (str "neg(" (:code r) ")")
                                 (str "(-" (:code r) ")")))
            "/" (binary-arith ctx "/" {:stmts [] :code "1.0" :type :float} r form)
            r))
      (reduce #(binary-arith ctx glsl-op %1 %2 form) results))))

;; ---------------------------------------------------------------------------
;; Builtin GLSL functions

(defn- compile-args [ctx env args form]
  (mapv #(expect-value (compile-expr ctx env %) form) args))

(defn- call-code [glsl-name results]
  (str glsl-name "(" (str/join ", " (map :code results)) ")"))

(defn- gen-type?
  "Types accepted by GLSL's genType builtins (sin, abs, mix, ...)."
  [t]
  (or (= t :float) (vec-size t)))

(defn- expect-gen [r op form]
  (let [r (as-float r)]
    (when-not (gen-type? (:type r))
      (fail! (str op " doesn't work on " (type-name (:type r))) form))
    r))

(defn- gen1
  "Componentwise 1-arg builtin: same type in, same type out."
  [glsl-name]
  (fn [ctx env [op & args :as form]]
    (when (not= 1 (count args)) (fail! (str "(" op " x) takes exactly one argument") form))
    (let [r (expect-gen (first (compile-args ctx env args form)) op form)]
      {:stmts (:stmts r) :code (call-code glsl-name [r]) :type (:type r)})))

(defn- same-gen-pair
  "Align two genType args: identical types pass; float broadcasts to match a
   vector when `splat?` (needed for pow, which has no (vec, float) overload)."
  [a b op form splat?]
  (let [a (expect-gen a op form) b (expect-gen b op form)
        ta (:type a) tb (:type b)]
    (cond
      (= ta tb) [a b]
      (and splat? (vec-size ta) (= tb :float))
      [a (assoc b :code (str (type-name ta) "(" (:code b) ")") :type ta)]
      :else (fail! (str op " can't combine " (type-name ta) " and " (type-name tb)) form))))

(defn- compile-pow [ctx env [op & args :as form]]
  (when (not= 2 (count args)) (fail! "(pow x y) takes exactly two arguments" form))
  (let [[a b] (compile-args ctx env args form)
        [a b] (same-gen-pair a b op form true)]
    {:stmts (merge-stmts [a b]) :code (call-code "pow" [a b]) :type (:type a)}))

(defn- compile-mod [ctx env [op & args :as form]]
  (when (not= 2 (count args)) (fail! "(mod x y) takes exactly two arguments" form))
  (let [[a b] (compile-args ctx env args form)
        a (expect-gen a op form) b (expect-gen b op form)]
    (when-not (or (= (:type a) (:type b)) (= :float (:type b)))
      (fail! (str "mod can't combine " (type-name (:type a)) " and " (type-name (:type b))) form))
    {:stmts (merge-stmts [a b]) :code (call-code "mod" [a b]) :type (:type a)}))

(defn- compile-minmax
  "n-ary min/max. GLSL only has (genType, genType) and (genType, float), so
   a leading scalar is swapped behind the vector (min/max commute)."
  [glsl-name ctx env [op & args :as form]]
  (when (< (count args) 2) (fail! (str "(" op " ...) needs at least two arguments") form))
  (let [results (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (reduce (fn [a b]
              (let [[a b] (if (and (= :float (:type a)) (vec-size (:type b))) [b a] [a b])]
                (when-not (or (= (:type a) (:type b)) (= :float (:type b)))
                  (fail! (str op " can't combine " (type-name (:type a)) " and " (type-name (:type b))) form))
                {:stmts (merge-stmts [a b]) :code (call-code glsl-name [a b]) :type (:type a)}))
            results)))

(defn- compile-clamp [ctx env [op & args :as form]]
  (when (not= 3 (count args)) (fail! "(clamp x lo hi) takes exactly three arguments" form))
  (let [[x lo hi] (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (when-not (and (= (:type lo) (:type hi))
                   (or (= (:type lo) (:type x)) (= :float (:type lo))))
      (fail! "clamp bounds must both be floats, or both match x's type" form))
    {:stmts (merge-stmts [x lo hi]) :code (call-code "clamp" [x lo hi]) :type (:type x)}))

(defn- compile-mix [ctx env [op & args :as form]]
  (when (not= 3 (count args)) (fail! "(mix a b t) takes exactly three arguments" form))
  (let [[a b t] (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (when (not= (:type a) (:type b)) (fail! "mix needs a and b to have the same type" form))
    (when-not (or (= :float (:type t)) (= (:type t) (:type a)))
      (fail! "mix's t must be a float or match a's type" form))
    {:stmts (merge-stmts [a b t]) :code (call-code "mix" [a b t]) :type (:type a)}))

(defn- compile-step [ctx env [op & args :as form]]
  (when (not= 2 (count args)) (fail! "(step edge x) takes exactly two arguments" form))
  (let [[e x] (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (when-not (or (= (:type e) (:type x)) (= :float (:type e)))
      (fail! "step's edge must be a float or match x's type" form))
    {:stmts (merge-stmts [e x]) :code (call-code "step" [e x]) :type (:type x)}))

(defn- compile-smoothstep [ctx env [op & args :as form]]
  (when (not= 3 (count args)) (fail! "(smoothstep e0 e1 x) takes exactly three arguments" form))
  (let [[e0 e1 x] (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (when-not (and (= (:type e0) (:type e1))
                   (or (= :float (:type e0)) (= (:type e0) (:type x))))
      (fail! "smoothstep edges must both be floats, or both match x's type" form))
    {:stmts (merge-stmts [e0 e1 x]) :code (call-code "smoothstep" [e0 e1 x]) :type (:type x)}))

(defn- compile-atan [ctx env [op & args :as form]]
  (let [results (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (case (count results)
      1 {:stmts (:stmts (first results)) :code (call-code "atan" results) :type (:type (first results))}
      2 (let [[y x] (same-gen-pair (first results) (second results) op form false)]
          {:stmts (merge-stmts [y x]) :code (call-code "atan" [y x]) :type (:type y)})
      (fail! "(atan y) or (atan y x)" form))))

(defn- compile-length-like [glsl-name arity ret ctx env [op & args :as form]]
  (when (not= arity (count args)) (fail! (str op " takes exactly " arity " argument(s)") form))
  (let [results (mapv #(expect-gen % op form) (compile-args ctx env args form))]
    (when (and (= 2 arity) (not= (:type (first results)) (:type (second results))))
      (fail! (str op " needs both arguments to have the same type") form))
    {:stmts (merge-stmts results)
     :code (call-code glsl-name results)
     :type (or ret (:type (first results)))}))

(defn- compile-cross [ctx env [_ & args :as form]]
  (when (not= 2 (count args)) (fail! "(cross a b) takes exactly two arguments" form))
  (let [results (compile-args ctx env args form)]
    (doseq [r results]
      (when (not= :vec3 (:type r)) (fail! "cross only works on vec3s" form)))
    {:stmts (merge-stmts results) :code (call-code "cross" results) :type :vec3}))

(defn- compile-refract [ctx env [_ & args :as form]]
  (when (not= 3 (count args)) (fail! "(refract i n eta) takes exactly three arguments" form))
  (let [[i n eta] (compile-args ctx env args form)]
    (when (not= (:type i) (:type n)) (fail! "refract needs i and n to have the same type" form))
    (let [eta (coerce eta :float "refract's eta" form)]
      {:stmts (merge-stmts [i n eta]) :code (call-code "refract" [i n eta]) :type (:type i)})))

;; ---------------------------------------------------------------------------
;; Constructors, casts & swizzles

(defn- compile-vec-constructor [n ctx env [op & args :as form]]
  (when (empty? args) (fail! (str "(" op " ...) needs arguments") form))
  (let [results (mapv as-float (compile-args ctx env args form))
        comp-counts (mapv (fn [r]
                            (or (when (= :float (:type r)) 1)
                                (vec-size (:type r))
                                (fail! (str "(" op " ...) accepts floats and vectors, got "
                                            (type-name (:type r))) form)))
                          results)
        total (reduce + comp-counts)]
    (when-not (or (= total n)
                  (and (= 1 (count results)) (or (= 1 total) (> total n))))
      (fail! (str "(" op " ...) needs " n " components (or one scalar to splat), got " total) form))
    {:stmts (merge-stmts results)
     :code (call-code (str "vec" n) results)
     :type (vec-of-size n)}))

(defn- compile-mat-constructor [n ctx env [op & args :as form]]
  ;; Light validation only — matrices are a power-user corner and GLSL
  ;; gives a clear error if the component count is off.
  (when (js-target? ctx)
    (fail! (str "(" op " ...) — matrices aren't available in drones;"
                " audio values are mono signals (floats) and stereo pairs (vec2)") form))
  (let [results (mapv as-float (compile-args ctx env args form))]
    {:stmts (merge-stmts results)
     :code (call-code (str "mat" n) results)
     :type (keyword (str "mat" n))}))

(defn- compile-cast [glsl-type ret ctx env [op & args :as form]]
  (when (not= 1 (count args)) (fail! (str "(" op " x) takes exactly one argument") form))
  (let [r (first (compile-args ctx env args form))]
    (when-not (#{:float :int :bool} (:type r))
      (fail! (str "(" op " x) only casts scalars, got " (type-name (:type r))) form))
    {:stmts (:stmts r) :code (str glsl-type "(" (:code r) ")") :type ret}))

(def ^:private swizzle-index {"x" 0 "y" 1 "z" 2 "w" 3})

(defn- compile-swizzle [ctx env [kw & args :as form]]
  (when (not= 1 (count args)) (fail! (str "(" kw " v) takes exactly one argument") form))
  (let [letters (-> (name kw)
                    (str/replace "r" "x") (str/replace "g" "y")
                    (str/replace "b" "z") (str/replace "a" "w"))
        chars (map str letters)]
    (when-not (and (<= 1 (count chars) 4) (every? swizzle-index chars))
      (fail! (str kw " isn't a swizzle — use 1-4 of x y z w (or r g b a)") form))
    (let [v (expect-value (compile-expr ctx env (first args)) form)
          size (or (vec-size (:type v))
                   (fail! (str "Can't swizzle a " (type-name (:type v)) " with " kw) form))]
      (doseq [c chars]
        (when (>= (swizzle-index c) size)
          (fail! (str kw " reads component ." c " but the value is only a " (type-name (:type v))) form)))
      {:stmts (:stmts v)
       :code (if (js-target? ctx)
               (str "sw(" (:code v) ", \"" letters "\")")
               (str (:code v) "." letters))
       :type (vec-of-size (count chars))})))

;; ---------------------------------------------------------------------------
;; Comparisons & boolean logic

(defn- compile-comparison [glsl-op ctx env [op & args :as form]]
  (when (not= 2 (count args)) (fail! (str "(" op " a b) takes exactly two arguments") form))
  (let [[a b] (compile-args ctx env args form)
        ordered? (not (#{"==" "!="} glsl-op))
        [a b] (if (= (:type a) (:type b)) [a b] [(as-float a) (as-float b)])]
    (when (not= (:type a) (:type b))
      (fail! (str op " can't compare " (type-name (:type a)) " and " (type-name (:type b))) form))
    (when (and ordered? (not (#{:float :int} (:type a))))
      (fail! (str op " only compares numbers — for vectors compare (length ...) or a component") form))
    {:stmts (merge-stmts [a b])
     :code (if (js-target? ctx)
             (str (js-comparison-fns glsl-op) "(" (:code a) ", " (:code b) ")")
             (str "(" (:code a) " " glsl-op " " (:code b) ")"))
     :type :bool}))

(defn- compile-logic [glsl-op ctx env [op & args :as form]]
  (when (< (count args) 2) (fail! (str "(" op " ...) needs at least two arguments") form))
  (let [results (compile-args ctx env args form)]
    (doseq [r results]
      (when (not= :bool (:type r))
        (fail! (str op " needs booleans (from < > = etc), got " (type-name (:type r))) form)))
    {:stmts (merge-stmts results)
     :code (if (js-target? ctx)
             (str (js-logic-fns glsl-op) "(" (str/join ", " (map :code results)) ")")
             (str "(" (str/join (str " " glsl-op " ") (map :code results)) ")"))
     :type :bool}))

(defn- compile-not [ctx env [_ & args :as form]]
  (when (not= 1 (count args)) (fail! "(not x) takes exactly one argument" form))
  (let [r (first (compile-args ctx env args form))]
    (when (not= :bool (:type r)) (fail! "not needs a boolean" form))
    {:stmts (:stmts r)
     :code (if (js-target? ctx)
             (str "not(" (:code r) ")")
             (str "(!" (:code r) ")"))
     :type :bool}))

;; ---------------------------------------------------------------------------
;; Special forms: let, if, cond, iter, threading

(defn- compile-let [ctx env [_ bindings & body :as form]]
  (when-not (and (vector? bindings) (even? (count bindings)))
    (fail! "let needs a vector of name/value pairs: (let [a 1.0 b 2.0] ...)" form))
  (when (not= 1 (count body))
    (fail! "let takes exactly one body expression (its value)" form))
  (loop [pairs (partition 2 bindings), env env, stmts []]
    (if-let [[sym init] (first pairs)]
      (do
        (when-not (symbol? sym) (fail! (str "let can only bind plain names, got " (pr-str sym)) form))
        (let [r (expect-value (compile-expr ctx (dissoc env :tuple-arity) init) form)
              glsl (fresh-name ctx sym)]
          (recur (rest pairs)
                 (assoc-in env [:vars sym] {:name glsl :type (:type r)})
                 (into stmts (conj (:stmts r)
                                   (emit-decl ctx (:type r) glsl (:code r)))))))
      ;; :tuple-arity flows into the body so (let ...) can end an iter with
      ;; a state-update vector.
      (let [r (compile-expr ctx env (first body))]
        (assoc r :stmts (into stmts (:stmts r)))))))

(defn- unify-branches [then else form]
  (let [then (if (and (= :int (:type then)) (= :float (:type else))) (as-float then) then)
        else (if (and (= :int (:type else)) (= :float (:type then))) (as-float else) else)]
    (when (not= (:type then) (:type else))
      (fail! (str "if branches must have the same type, got " (type-name (:type then))
                  " and " (type-name (:type else))) form))
    [then else]))

(defn- compile-if [ctx env [_ condition then else :as form]]
  (when-not (and (= 4 (count form)) (some? else))
    (fail! "if needs all three parts — a shader must always produce a value: (if cond then else)" form))
  (let [env (dissoc env :tuple-arity)
        c (expect-value (compile-expr ctx env condition) form)
        t (expect-value (compile-expr ctx env then) form)
        e (expect-value (compile-expr ctx env else) form)]
    (when (not= :bool (:type c))
      (fail! (str "if's condition must be a boolean (from < > = etc), got " (type-name (:type c))) form))
    (let [[t e] (unify-branches t e form)]
      {:stmts (merge-stmts [c t e])
       ;; in the audio target the condition can be a signal, so the choice
       ;; is a signal crossfade (select), not a JS ternary
       :code (if (js-target? ctx)
               (str "select(" (:code c) ", " (:code t) ", " (:code e) ")")
               (str "(" (:code c) " ? " (:code t) " : " (:code e) ")"))
       :type (:type t)})))

(defn- compile-cond [ctx env [_ & clauses :as form]]
  (when-not (and (even? (count clauses)) (>= (count clauses) 2))
    (fail! "cond needs test/result pairs, ending with :else: (cond c1 e1 :else e2)" form))
  (let [[test result & more] clauses]
    (if (= :else test)
      (compile-expr ctx env result)
      (do
        (when (empty? more) (fail! "cond needs a final :else clause" form))
        (compile-if ctx env (list 'if test result (cons 'cond more)))))))

(defn- compile-iter
  "(iter [i n] [state init ...] body result?)

   Emits a GLSL for-loop. Each pass, `body` produces the next state — a
   single expression for one state var, or a vector [new-a new-b ...] for
   several. The whole form evaluates to `result` (default: the first
   state var) with the final state in scope."
  [ctx env [_ index-binding state-bindings body & more :as form]]
  (when-not (and (vector? index-binding) (= 2 (count index-binding))
                 (symbol? (first index-binding))
                 (integer? (second index-binding)) (pos? (second index-binding)))
    (fail! "iter needs [index-name count] with a literal positive count, e.g. (iter [i 64] ...)" form))
  (when-not (and (vector? state-bindings) (even? (count state-bindings)) (seq state-bindings))
    (fail! "iter needs state bindings: (iter [i 64] [t 0.0] ...)" form))
  (when (> (count more) 1)
    (fail! "iter takes at most one result expression after the body" form))
  (let [[index-sym n] index-binding
        env (dissoc env :tuple-arity)
        ;; declare each state variable before the loop
        states (mapv (fn [[sym init]]
                       (let [r (expect-value (compile-expr ctx env init) form)]
                         {:sym sym :glsl (fresh-name ctx sym) :type (:type r) :init r}))
                     (partition 2 state-bindings))
        decl-stmts (into [] (mapcat (fn [{:keys [glsl type init]}]
                                      (conj (:stmts init)
                                            (emit-decl ctx type glsl (:code init)))))
                         states)
        index-glsl (fresh-name ctx index-sym)
        body-env (reduce (fn [e {:keys [sym glsl type]}]
                           (assoc-in e [:vars sym] {:name glsl :type type}))
                         (assoc-in env [:vars index-sym] {:name index-glsl :type :int})
                         states)
        multi? (> (count states) 1)
        body-r (compile-expr ctx (if multi? (assoc body-env :tuple-arity (count states)) body-env) body)
        updates (if multi?
                  (or (:tuple body-r)
                      (fail! (str "iter has " (count states) " state vars, so its body must end in a"
                                  " vector of that many updates: [new-a new-b ...]") form))
                  [body-r])
        updates (mapv (fn [u {:keys [sym type]}]
                        (coerce u type (str "iter state " sym " update") form))
                      updates states)
        ;; multi-var updates go through temporaries so each next-state sees
        ;; the *old* values of every state var, like loop/recur
        assign-stmts (if multi?
                       (let [temps (mapv (fn [{:keys [sym]}] (fresh-name ctx (str (name sym) "_next"))) states)]
                         (into (mapv (fn [t u {:keys [type]}]
                                       (emit-decl ctx type t (:code u)))
                                     temps updates states)
                               (mapv (fn [t {:keys [glsl]}] (str glsl " = " t ";")) temps states)))
                       [(str (:glsl (first states)) " = " (:code (first updates)) ";")])
        loop-body (into (vec (:stmts body-r)) assign-stmts)
        for-stmt (str "for (" (if (js-target? ctx) "let" "int") " " index-glsl " = 0; "
                      index-glsl " < " n "; " index-glsl "++) {\n"
                      (str/join "\n" (indent-lines loop-body))
                      "\n}")
        result-r (if (seq more)
                   (expect-value (compile-expr ctx body-env (first more)) form)
                   (let [{:keys [glsl type]} (first states)] {:stmts [] :code glsl :type type}))]
    {:stmts (into (conj decl-stmts for-stmt) (:stmts result-r))
     :code (:code result-r)
     :type (:type result-r)}))

(defn- thread-step [first? acc form]
  (if (seq? form)
    (if first?
      (apply list (first form) acc (rest form))
      (concat form [acc]))
    (list form acc)))

(defn- compile-threading [first? ctx env [_ x & forms :as form]]
  (when (nil? x) (fail! "-> needs something to thread" form))
  (compile-expr ctx env (reduce #(thread-step first? %1 %2) x forms)))

;; ---------------------------------------------------------------------------
;; User-defined functions (inlined at every call site)

(def ^:private simple-code?
  ;; Codes safe to substitute directly for a parameter without a temp var:
  ;; bare identifiers, identifier swizzles, and numeric literals.
  #(re-matches #"[A-Za-z_][A-Za-z0-9_]*(\.[xyzw]{1,4})?|-?[0-9]+\.[0-9]+" %))

(defn- inline-call
  "Expand a user (defn ...) at its call site: bind arguments (via temps
   unless trivially cheap), then compile the body with only params +
   globals in scope — proper lexical scoping."
  [ctx env fn-sym {:keys [params body]} arg-results form]
  (let [depth (inc (:depth env 0))]
    (when (> depth 32)
      (fail! (str "Call chain too deep at " fn-sym " — recursive functions can't run on a GPU;"
                  " use iter for loops") form))
    (when (not= (count arg-results) (count params))
      (fail! (str fn-sym " takes " (count params) " argument(s), got " (count arg-results)) form))
    (let [{:keys [vars stmts]}
          (reduce (fn [acc [param arg]]
                    (if (simple-code? (:code arg))
                      (update acc :vars assoc param {:name (:code arg) :type (:type arg)})
                      (let [glsl (fresh-name ctx param)]
                        (-> acc
                            (update :vars assoc param {:name glsl :type (:type arg)})
                            (update :stmts conj (emit-decl ctx (:type arg) glsl (:code arg)))))))
                  {:vars {} :stmts (merge-stmts arg-results)}
                  (map vector params arg-results))
          body-r (expect-value (compile-expr ctx {:vars vars :depth depth} body) form)]
      (assoc body-r :stmts (into stmts (:stmts body-r))))))

;; ---------------------------------------------------------------------------
;; Uploaded media: samples in drones, textures in shaders
;;
;; Media names are compile-time string literals checked against what's
;; actually uploaded (the app passes the current names in; when it doesn't —
;; e.g. in tests — the check is skipped and any name is trusted).

(defn- check-media-name!
  "Validate a compiled name argument against the uploaded media `known`
   (nil = don't check). Returns the name's compiled result."
  [r known op what tab form]
  (when (not= :string (:type r))
    (fail! (str "(" op " ...) needs the " what "'s name as a string, like"
                " (" op " \"vinyl\" ...) — upload files in the " tab " tab") form))
  (when known
    (cond
      (empty? known)
      (fail! (str "No " tab " uploaded yet — add a file in the " tab
                  " tab, then refer to it by name: (" op " " (:code r) " ...)") form)

      (not (some #{(:value r)} known))
      (fail! (str "No " what " named " (:code r) " — uploaded: "
                  (str/join ", " (sort known))) form)))
  r)

(defn- compile-texture [ctx env [op & args :as form]]
  (when (js-target? ctx)
    (fail! "(texture ...) reads pixels from an uploaded image, so it only works in the shader — not inside a defsound" form))
  (when (not= 2 (count args))
    (fail! "(texture \"name\" uv) takes an image name and a vec2 coordinate" form))
  (let [[nm coord] (compile-args ctx env args form)
        nm (check-media-name! nm (:textures ctx) op "image" "images" form)
        coord (coerce coord :vec2 "texture's coordinate" form)]
    {:stmts (merge-stmts [nm coord])
     :code (if (= :shadertoy (:target ctx))
             ;; the export maps each image to a Shadertoy channel, in name
             ;; order — from iChannel1 up, since iChannel0 carries audio there
             (str "texture(iChannel" (inc (max 0 (.indexOf (vec (:textures ctx)) (:value nm))))
                  ", " (:code coord) ")")
             (str "texture(" (texture-uniform-name (:value nm)) ", " (:code coord) ")"))
     :type :vec4}))

;; ---------------------------------------------------------------------------
;; Drone builtins
;;
;; Signatures live in prelude/audio-functions. A parameter typed :sig takes
;; either a float (mono signal) or a vec2 (stereo pair), and a :sig return
;; matches the shape that came in — so (lowpass 400 1.0 x) filters mono and
;; stereo alike. A :sample-name parameter is a string literal naming an
;; uploaded audio file.

(defn- compile-audio-call [ctx env op {:keys [js-name params return]} [_ & args :as form]]
  (let [results (compile-args ctx env args form)]
    (when (not= (count results) (count params))
      (fail! (str "(" op " ...) takes " (count params) " argument(s)") form))
    (let [results (mapv (fn [r p i]
                          (case p
                            :sig
                            (let [r (as-float r)]
                              (when-not (#{:float :vec2} (:type r))
                                (fail! (str op "'s signal argument must be a float (mono)"
                                            " or vec2 (stereo), got " (type-name (:type r))) form))
                              r)
                            :sample-name
                            (check-media-name! r (:samples ctx) op "sample" "samples" form)
                            (coerce r p (str op "'s argument " (inc i)) form)))
                        results params (range))
          sig-type (some (fn [[r p]] (when (= :sig p) (:type r)))
                         (map vector results params))]
      {:stmts (merge-stmts results)
       :code (call-code js-name results)
       :type (if (= :sig return) (or sig-type :float) return)})))

;; ---------------------------------------------------------------------------
;; Dispatch

(def ^:private constants
  {'pi  "3.141592653589793"
   'tau "6.283185307179586"})

(def ^:private gen1-fns
  '{sin "sin", cos "cos", tan "tan", asin "asin", acos "acos"
    exp "exp", log "log", exp2 "exp2", log2 "log2"
    sqrt "sqrt", inversesqrt "inversesqrt"
    abs "abs", sign "sign", floor "floor", ceil "ceil", fract "fract"
    normalize "normalize"})

(def ^:private comparison-ops
  '{< "<", > ">", <= "<=", >= ">=", = "==", not= "!="})

(def ^:private arith-ops '{+ "+", - "-", * "*", / "/"})

(def ^:private unsupported-hints
  {'loop "Clojure loop/recur doesn't map to GPUs — use (iter [i n] [state init] body) instead"
   'recur "Clojure loop/recur doesn't map to GPUs — use (iter [i n] [state init] body) instead"
   'when "when has no else branch, but a shader always needs a value — use (if c then else)"
   'when-not "when-not has no else branch — use (if (not c) then else)"
   'fn "anonymous functions aren't supported — use a top-level (defn name [args] body)"
   'do "do implies side effects, which shaders don't have — use let to name intermediate values"
   'map "sequence functions run at Clojure level, which this compiler skips — use iter or vec math"
   'reduce "sequence functions aren't supported — use (iter [i n] [acc init] body) to accumulate"
   'for "for comprehensions aren't supported — use (iter [i n] [state init] body)"
   'def "def is only allowed at the top level"
   'defn "defn is only allowed at the top level"
   'defsound "defsound is only allowed at the top level"
   'defparam "defparam is only allowed at the top level"
   'quote "quoted forms aren't supported"})

(defn- compile-list [ctx env [op & args :as form]]
  (cond
    (nil? op) (fail! "Empty list () isn't a valid expression" form)

    (keyword? op) (compile-swizzle ctx env form)

    (not (symbol? op))
    (fail! (str "Can't call " (pr-str op) " as a function") form)

    :else
    (condp = op
      'let  (compile-let ctx env form)
      'if   (compile-if ctx env form)
      'cond (compile-cond ctx env form)
      'iter (compile-iter ctx env form)
      '->   (compile-threading true ctx env form)
      '->>  (compile-threading false ctx env form)
      'and  (compile-logic "&&" ctx env form)
      'or   (compile-logic "||" ctx env form)
      'not  (compile-not ctx env form)
      (let [env (dissoc env :tuple-arity)]
        (cond
          ;; user-defined functions shadow builtins
          (get-in ctx [:fns op])
          (inline-call ctx env op (get-in ctx [:fns op])
                       (compile-args ctx env args form) form)

          (contains? arith-ops op) (compile-arith (arith-ops op) ctx env form)
          (contains? comparison-ops op) (compile-comparison (comparison-ops op) ctx env form)
          (contains? gen1-fns op) ((gen1 (gen1-fns op)) ctx env form)

          (= op 'vec2) (compile-vec-constructor 2 ctx env form)
          (= op 'vec3) (compile-vec-constructor 3 ctx env form)
          (= op 'vec4) (compile-vec-constructor 4 ctx env form)
          (= op 'mat2) (compile-mat-constructor 2 ctx env form)
          (= op 'mat3) (compile-mat-constructor 3 ctx env form)
          (= op 'mat4) (compile-mat-constructor 4 ctx env form)
          (= op 'float) (compile-cast "float" :float ctx env form)
          (= op 'int) (compile-cast "int" :int ctx env form)

          (= op 'pow) (compile-pow ctx env form)
          (= op 'mod) (compile-mod ctx env form)
          (= op 'min) (compile-minmax "min" ctx env form)
          (= op 'max) (compile-minmax "max" ctx env form)
          (= op 'clamp) (compile-clamp ctx env form)
          (= op 'mix) (compile-mix ctx env form)
          (= op 'step) (compile-step ctx env form)
          (= op 'smoothstep) (compile-smoothstep ctx env form)
          (= op 'atan) (compile-atan ctx env form)
          (= op 'length) (compile-length-like "length" 1 :float ctx env form)
          (= op 'distance) (compile-length-like "distance" 2 :float ctx env form)
          (= op 'dot) (compile-length-like "dot" 2 :float ctx env form)
          (= op 'reflect) (compile-length-like "reflect" 2 nil ctx env form)
          (= op 'cross) (compile-cross ctx env form)
          (= op 'refract) (compile-refract ctx env form)
          (= op 'texture) (compile-texture ctx env form)

          ;; shader prelude functions (noise, fbm, palette, sdfs, ...)
          (and (not (js-target? ctx)) (contains? prelude/functions op))
          (let [{:keys [glsl-name params return]} (prelude/functions op)
                results (compile-args ctx env args form)]
            (when (not= (count results) (count params))
              (fail! (str "(" op " ...) takes " (count params) " argument(s)") form))
            (let [results (mapv (fn [r p i] (coerce r p (str op "'s argument " (inc i)) form))
                                results params (range))]
              {:stmts (merge-stmts results) :code (call-code glsl-name results) :type return}))

          ;; drone builtins (osc, lowpass, wander, space, ...)
          (and (js-target? ctx) (contains? prelude/audio-functions op))
          (compile-audio-call ctx env op (prelude/audio-functions op) form)

          ;; the op exists, just not in this target — say so precisely
          (contains? prelude/functions op)
          (fail! (str "(" op " ...) is a shader tool — it isn't available inside a defsound") form)

          (contains? prelude/audio-functions op)
          (fail! (str "(" op " ...) makes sound, so it only works inside a (defsound ...) —"
                      " in the image, listen to it through bass / mid / treble / level") form)

          (contains? unsupported-hints op) (fail! (unsupported-hints op) form)

          :else (fail! (str "Unknown function: " op " — check the reference panel for what's available") form))))))

(defn- compile-symbol [ctx env sym]
  (or (when-let [v (get-in env [:vars sym])]
        {:stmts [] :code (:name v) :type (:type v)})
      (when-let [g (get-in ctx [:globals sym])]
        {:stmts [] :code (:code g) :type (:type g)})
      (when-let [c (constants sym)]
        {:stmts [] :code c :type :float})
      ;; a def that couldn't compile for this target (e.g. it uses `uv`
      ;; and we're compiling a sound) fails only when actually referenced
      (when-let [err (get-in ctx [:failed-defs sym])]
        (fail! (str "(def " sym " ...) can't be used here — " err)))
      (when (get-in ctx [:fns sym])
        (fail! (str sym " is a function — call it like (" sym " ...)")))
      (fail! (str "Unknown symbol: " sym))))

(defn- compile-tuple [ctx env form]
  (if-let [arity (:tuple-arity env)]
    (if (= arity (count form))
      (let [results (mapv #(expect-value (compile-expr ctx (dissoc env :tuple-arity) %) form) form)]
        {:stmts (merge-stmts results) :tuple results})
      (fail! (str "This iter has " arity " state vars, so the update vector needs "
                  arity " elements") form))
    (fail! "Vector literals only update iter state — to build a vector value use (vec2 ...) / (vec3 ...)" form)))

(defn- compile-expr [ctx env form]
  (cond
    (number? form)  {:stmts [] :code (fmt-float form) :type :float}
    (true? form)    {:stmts [] :code "true" :type :bool}
    (false? form)   {:stmts [] :code "false" :type :bool}
    ;; a bare keyword is a note: :a2 compiles to 110.0
    (keyword? form) {:stmts [] :code (fmt-float (note->hz form)) :type :float}
    ;; strings only name uploaded media: (sample "vinyl" 1.0), (texture "photo" uv)
    (string? form)  {:stmts [] :code (js/JSON.stringify form) :type :string :value form}
    (symbol? form)  (compile-symbol ctx env form)
    (seq? form)     (compile-list ctx env form)
    (vector? form)  (compile-tuple ctx env form)
    :else (fail! (str "Can't compile " (pr-str form) " — shaders work with numbers, vectors and math") form)))

;; ---------------------------------------------------------------------------
;; Top level: collect defs/defns, compile the image entry point, assemble GLSL

(def ^:private reserved-names
  "Identifiers compiled GLSL must never claim: GLSL keywords, our wrapper
   variables, and prelude function names."
  (into #{"main" "fragColor" "fragCoord" "uv" "uvc" "mouse"
          "u_time" "u_resolution" "u_mouse" "u_audio"
          "iTime" "iResolution" "iMouse" "iChannel0"
          "bass" "mid" "treble" "level" "audio"
          "float" "int" "bool" "vec2" "vec3" "vec4" "mat2" "mat3" "mat4"
          "if" "else" "for" "while" "do" "return" "break" "continue" "const"
          "in" "out" "inout" "uniform" "varying" "attribute" "layout"
          "precision" "highp" "mediump" "lowp" "invariant" "discard"
          "struct" "void" "true" "false" "sampler2D" "texture"
          "smooth" "flat" "centroid" "switch" "case" "default"}
        (map :glsl-name (vals prelude/functions))))

(defn- param-const-name
  "The file-scope const an exported Shadertoy shader uses for a knob:
   the readable user name, unless it would clash with a GLSL keyword or a
   prelude function, in which case it gets a suffix. Deterministic, so the
   declaration and every reference agree without extra bookkeeping."
  [param-name]
  (let [n (sanitize-name param-name)]
    (if (contains? reserved-names n) (str n "_param") n)))

(def ^:private js-reserved-names
  "Identifiers compiled drone JS must never claim: JS keywords, the
   compiled function's own parameter, and every name the audio runtime
   library destructures into scope."
  (into #{"L" "TIME"
          "let" "const" "var" "return" "function" "if" "else" "for" "while"
          "do" "new" "true" "false" "null" "undefined" "in" "of" "class"
          "this" "typeof" "instanceof" "void" "delete" "switch" "case"
          "default" "break" "continue" "throw" "try" "catch" "finally"
          "yield" "await" "async" "export" "import" "extends" "super"
          "param"
          "add" "sub" "mul" "div" "neg" "sw" "select"
          "eq" "neq" "lt" "gt" "lte" "gte" "and" "or" "not"
          "vec2" "vec3" "vec4" "mat2" "mat3" "mat4" "float" "int"
          "sin" "cos" "tan" "asin" "acos" "atan" "exp" "log" "exp2" "log2"
          "sqrt" "inversesqrt" "abs" "sign" "floor" "ceil" "fract"
          "normalize" "length" "distance" "dot" "cross" "reflect" "refract"
          "pow" "mod" "min" "max" "clamp" "mix" "step" "smoothstep"
          "hash1" "hash2"}
        (map :js-name (vals prelude/audio-functions))))

(def ^:private target-globals
  "How the DSL's input values spell in each target dialect. The audio
   values (bass/mid/treble/level) come from the live drone analyser in the
   app; the Shadertoy export reads them from an iChannel0 audio texture.
   In the drone target, `time` is a signal — seconds since the audio
   engine started."
  {:webgl2
   {'time       {:code "u_time" :type :float}
    'resolution {:code "u_resolution" :type :vec2}
    'mouse      {:code "mouse" :type :vec2}
    'uv         {:code "uv" :type :vec2}
    'uvc        {:code "uvc" :type :vec2}
    'bass       {:code "u_audio.x" :type :float}
    'mid        {:code "u_audio.y" :type :float}
    'treble     {:code "u_audio.z" :type :float}
    'level      {:code "u_audio.w" :type :float}
    'audio      {:code "u_audio" :type :vec4}}
   :shadertoy
   {'time       {:code "iTime" :type :float}
    'resolution {:code "(iResolution.xy)" :type :vec2}
    'mouse      {:code "mouse" :type :vec2}
    'uv         {:code "uv" :type :vec2}
    'uvc        {:code "uvc" :type :vec2}
    'bass       {:code "bass" :type :float}
    'mid        {:code "mid" :type :float}
    'treble     {:code "treble" :type :float}
    'level      {:code "level" :type :float}
    'audio      {:code "audio" :type :vec4}}
   :drone
   {'time       {:code "TIME" :type :float}}})

(defn- parse-defparam
  "Read a (defparam name default [min max]) knob declaration into
   {:default :min :max}. Two-arg form gives a 0..1 knob, widened if needed
   to include the default, so the short form never errors."
  [sym args form]
  (when-not (every? number? args)
    (fail! (str "defparam " sym " takes plain numbers: (defparam " sym " default min max)") form))
  (let [spec (case (count args)
               1 (let [[d] args] {:default d :min (min 0 d) :max (max 1 d)})
               3 (let [[d lo hi] args] {:default d :min lo :max hi})
               (fail! (str "defparam " sym " wants (defparam " sym " default)"
                           " or (defparam " sym " default min max)") form))]
    (when-not (< (:min spec) (:max spec))
      (fail! (str "defparam " sym "'s min must be less than its max") form))
    (when-not (<= (:min spec) (:default spec) (:max spec))
      (fail! (str "defparam " sym "'s default must sit between min and max") form))
    spec))

(defn- collect-toplevel
  "Split top-level forms into named functions, ordered defs, drones, and
   param knobs (in source order)."
  [forms]
  (reduce
   (fn [acc form]
     (when-not (seq? form)
       (fail! "Top-level forms must be (def ...), (defn ...), (defsound ...) or (defparam ...)" form))
     (case (first form)
       defn (let [[_ sym params & body] form]
              (when-not (symbol? sym) (fail! "defn needs a name" form))
              (when-not (and (vector? params) (every? symbol? params))
                (fail! (str "defn " sym " needs a vector of parameter names") form))
              (when (not= 1 (count body))
                (fail! (str "defn " sym " needs exactly one body expression —"
                            " use let to sequence work") form))
              (update acc :fns assoc sym {:params params :body (first body)}))
       def (let [[_ sym init & extra] form]
             (when-not (and (symbol? sym) (some? init) (empty? extra))
               (fail! "def needs a name and one value: (def speed 2.0)" form))
             (update acc :defs conj [sym init]))
       defsound
       (let [[_ sym & body] form]
         (when-not (symbol? sym) (fail! "defsound needs a name: (defsound hum ...)" form))
         (when (not= 1 (count body))
           (fail! (str "defsound " sym " needs exactly one body expression —"
                       " use let to build it up") form))
         (update acc :drones conj [sym {:body (first body)}]))
       defparam
       (let [[_ sym & args] form]
         (when-not (symbol? sym) (fail! "defparam needs a name: (defparam speed 1.0)" form))
         (update acc :params conj [sym (parse-defparam sym args form)]))
       (fail! (str "Only def, defn, defsound and defparam are allowed at the top level, got ("
                   (first form) " ...)") form)))
   {:fns {} :defs [] :drones [] :params []}
   forms))

(defn- compile-defs
  "Compile top-level defs, in order, into declaration statements. A def
   whose expression doesn't compile for this target (say, it uses `uv`
   while we're compiling a sound, or `t` while we're compiling the image)
   is skipped and remembered — the error only surfaces if something in this
   target actually references it."
  [ctx defs]
  (reduce (fn [[ctx stmts] [sym init]]
            (try
              (let [r (expect-value (compile-expr ctx {:vars {}} init) init)
                    var-name (fresh-name ctx sym)]
                [(assoc-in ctx [:globals sym] {:code var-name :type (:type r)})
                 (into stmts (conj (:stmts r)
                                   (emit-decl ctx (:type r) var-name (:code r))))])
              (catch :default e
                [(assoc-in ctx [:failed-defs sym] (ex-message e)) stmts])))
          [ctx []]
          defs))

(def ^:private fallback-image-src
  "When a program defines drones but no image, show a bass/mid/treble
   meter instead of failing — sound-first sketches still get visuals."
  "(defn image [uv]
     (let [band (cond (< (:x uv) 0.333) bass
                      (< (:x uv) 0.666) mid
                      :else treble)
           lit (- 1.0 (smoothstep (- band 0.02) (+ band 0.02) (:y uv)))
           col (palette (:x uv) (vec3 0.5) (vec3 0.5) (vec3 1.0) (vec3 0.0 0.33 0.67))]
       (+ (* col lit) (* col 0.05))))")

(defn- glsl-param-globals
  "Each (defparam ...) becomes a float the shader can read by name: a
   uniform fed live by the knob in the WebGL target, a baked-in const in
   the Shadertoy export (which has no knobs of its own)."
  [params target]
  (into {} (for [[sym _] params]
             [sym {:code (if (= target :shadertoy)
                           (param-const-name sym)
                           (param-uniform-name sym))
                   :type :float}])))

(defn- compile-target
  "Compile collected forms for one GLSL dialect. Returns the statement
   lines and final color expression for the shader body."
  [{:keys [fns defs params]} target media]
  (let [ctx {:names (atom reserved-names)
             :fns fns
             :globals (merge (target-globals target)
                             (glsl-param-globals params target))
             :lang :glsl
             :target target
             :textures (:textures media)}
        [ctx def-stmts] (compile-defs ctx defs)
        image (or (get fns 'image)
                  (fail! "No entry point — define (defn image [uv] ...) returning a color"))
        _ (when (not= 1 (count (:params image)))
            (fail! "(defn image [uv] ...) takes exactly one parameter, the pixel's uv"))
        result (inline-call ctx {:vars {} :depth 0} 'image image
                            [{:stmts [] :code "uv" :type :vec2}] '(image uv))
        color (case (:type result)
                :vec4 (:code result)
                :vec3 (str "vec4(" (:code result) ", 1.0)")
                :float (str "vec4(vec3(" (:code result) "), 1.0)")
                (fail! (str "image must return a color (vec3, vec4, or float for grayscale), got "
                            (type-name (:type result)))))]
    {:stmts (into def-stmts (:stmts result))
     :color color}))

(defn- compile-drone
  "Compile one defsound body to a JS function body over the audio runtime
   library. Running it doesn't produce samples — it produces an Elementary
   node graph: a `:float` is a mono signal, a `:vec2` a stereo pair."
  [{:keys [fns defs params]} sym {:keys [body]} media]
  (let [ctx {:names (atom js-reserved-names)
             :fns fns
             ;; each knob reads live from the runtime by name: param("speed")
             ;; resolves to a smoothed const the engine morphs as the slider moves
             :globals (merge (target-globals :drone)
                             (into {} (for [[psym _] params]
                                        [psym {:code (str "param(" (js/JSON.stringify (name psym)) ")")
                                               :type :float}])))
             :lang :js
             :samples (:samples media)}
        [ctx def-stmts] (compile-defs ctx defs)
        result (expect-value (compile-expr ctx {:vars {} :depth 0} body) body)
        channels (case (:type result)
                   :float 1
                   :vec2 2
                   (fail! (str "defsound " sym " must produce a float (mono signal)"
                               " or vec2 (stereo pair), got "
                               (type-name (:type result)))))
        lines (into def-stmts (:stmts result))]
    {:name (str sym)
     :channels channels
     :js-body (str (str/join "\n" lines) (when (seq lines) "\n")
                   "return " (:code result) ";")}))

(defn- assemble-webgl2 [{:keys [stmts color]} textures params]
  (str "#version 300 es\n"
       "precision highp float;\n\n"
       "uniform float u_time;\n"
       "uniform vec2 u_resolution;\n"
       "uniform vec2 u_mouse;\n"
       "uniform vec4 u_audio; // bass, mid, treble, level (0..1)\n"
       (apply str (for [[sym {:keys [min max]}] params]
                    (str "uniform float " (param-uniform-name sym) "; // knob " (name sym)
                         " (" min ".." max ")\n")))
       (apply str (for [n textures]
                    (str "uniform sampler2D " (texture-uniform-name n) "; // uploaded image \"" n "\"\n")))
       "out vec4 fragColor;\n\n"
       prelude/glsl-helpers "\n\n"
       "void main() {\n"
       "  vec2 uv = gl_FragCoord.xy / u_resolution;\n"
       "  vec2 uvc = (2.0 * gl_FragCoord.xy - u_resolution) / u_resolution.y;\n"
       "  vec2 mouse = u_mouse / u_resolution;\n"
       (str/join "\n" (indent-lines stmts)) (when (seq stmts) "\n")
       "  fragColor = " color ";\n"
       "}\n"))

(defn- assemble-shadertoy [{:keys [stmts color]} textures params]
  (str "// Exported from lispshaders — paste into a new shader at shadertoy.com\n"
       "// For live audio values, add music or a mic to iChannel0.\n"
       (apply str (map-indexed (fn [i n]
                                 (str "// Add your image \"" n "\" as iChannel" (inc i) ".\n"))
                               textures))
       "\n"
       ;; Shadertoy has no knobs, so each (defparam ...) is baked in at its
       ;; default as a file-scope const the reader can tweak by hand.
       (when (seq params)
         (str "// Knobs — these were (defparam ...) sliders in lispshaders. Edit to taste.\n"
              (apply str (for [[sym {:keys [default min max]}] params]
                           (str "const float " (param-const-name sym) " = " (fmt-float default)
                                "; // range " min ".." max "\n")))
              "\n"))
       prelude/glsl-helpers "\n\n"
       "void mainImage(out vec4 fragColor, in vec2 fragCoord) {\n"
       "  vec2 uv = fragCoord / iResolution.xy;\n"
       "  vec2 uvc = (2.0 * fragCoord - iResolution.xy) / iResolution.y;\n"
       "  vec2 mouse = iMouse.xy / iResolution.xy;\n"
       "  float bass = texture(iChannel0, vec2(0.05, 0.25)).x;\n"
       "  float mid = texture(iChannel0, vec2(0.30, 0.25)).x;\n"
       "  float treble = texture(iChannel0, vec2(0.70, 0.25)).x;\n"
       "  float level = (bass + mid + treble) / 3.0;\n"
       "  vec4 audio = vec4(bass, mid, treble, level);\n"
       (str/join "\n" (indent-lines stmts)) (when (seq stmts) "\n")
       "  fragColor = " color ";\n"
       "}\n"))

(defn compile-shader
  "Compile DSL source text into every target. Returns
     {:glsl <webgl2 fragment shader>
      :shadertoy <shadertoy paste>
      :drones [{:name :channels :js-body} ...]}.
   `media` describes the user's uploads for name-checking and uniform
   declarations: {:samples #{names} :textures [names]} — when omitted,
   media names aren't validated (any (sample \"x\") is trusted).
   Throws ex-info with a friendly message on any reader or compile error."
  ([src] (compile-shader src nil))
  ([src media]
   (let [forms (try (reader/read-string (str "[\n" src "\n]"))
                    (catch :default e
                      (fail! (str "Couldn't read the code: " (ex-message e)))))
         collected (collect-toplevel forms)
         params (:params collected)
         _ (let [dupes (->> (map first params) frequencies
                            (filter #(> (val %) 1)) (map key))]
             (when (seq dupes)
               (fail! (str "Two knobs share a name: " (str/join ", " dupes)
                           " — each (defparam ...) needs a unique name"))))
         ;; drone-only sketches get a built-in audio-meter visual
         collected (if (and (nil? (get-in collected [:fns 'image]))
                            (seq (:drones collected)))
                     (update collected :fns merge
                             (:fns (collect-toplevel
                                    (reader/read-string (str "[" fallback-image-src "]")))))
                     collected)]
     {:glsl (assemble-webgl2 (compile-target collected :webgl2 media) (:textures media) params)
      :shadertoy (assemble-shadertoy (compile-target collected :shadertoy media) (:textures media) params)
      :drones (mapv (fn [[sym spec]] (compile-drone collected sym spec media))
                    (:drones collected))
      ;; knob specs for the UI to render sliders from
      :params (mapv (fn [[sym {:keys [default min max]}]]
                      {:name (name sym) :default default :min min :max max})
                    params)})))
