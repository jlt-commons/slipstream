(ns slipstream.glimmer-effects
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]))

(def ^:private uniform-types
  {:float [0 1 "float"] :vec2 [1 2 "vec2"] :vec3 [2 3 "vec3"] :vec4 [3 4 "vec4"]
   :int [4 1 "int"] :ivec2 [5 2 "ivec2"] :ivec3 [6 3 "ivec3"] :ivec4 [7 4 "ivec4"]
   :uint [8 1 "uint"] :uvec2 [9 2 "uvec2"] :uvec3 [10 3 "uvec3"] :uvec4 [11 4 "uvec4"]
   :bool [12 1 "bool"] :bvec2 [13 2 "bvec2"] :bvec3 [14 3 "bvec3"] :bvec4 [15 4 "bvec4"]
   :mat3 [16 9 "mat3"] :mat4 [17 16 "mat4"]})

(defn- fail [effect message data]
  (throw (ex-info message (merge {:scene-path (get effect :scene-path [:effect])} data))))

(defn- normalize-uniform [effect [uniform-name spec]]
  (let [uniform-name (name uniform-name)
        {:keys [type value]} spec]
    (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_]*" uniform-name)
      (fail effect "invalid effect uniform name" {:uniform uniform-name}))
    (when-not (map? spec)
      (fail effect "effect uniform must be a typed map" {:uniform uniform-name :value spec}))
    (let [[type-id components glsl-type] (get uniform-types type)]
      (when-not type-id
        (fail effect "unsupported effect uniform type" {:uniform uniform-name :type type}))
      (let [scalar? (= 1 components)
            boolean-type? (contains? #{:bool} type)
            values (if scalar? [value] value)]
        (when-not (and (sequential? values) (= components (count values)))
          (fail effect "effect uniform value has the wrong component count"
                {:uniform uniform-name :type type :value value}))
        (when (and (= :mat3 type) (not= 9 (count values)))
          (fail effect "mat3 uniforms require nine values" {:uniform uniform-name}))
        (doseq [component values]
          (when-not (if boolean-type? (boolean? component) (number? component))
            (fail effect "effect uniform value does not match its type"
                  {:uniform uniform-name :type type :value value})))
        {:name uniform-name :glsl-name (str "u_" uniform-name) :glsl-type glsl-type
         :type type :type-id type-id :components components
         :values (mapv #(if (boolean? %) (if % 1.0 0.0) (double %)) values)}))))

(defn normalize-effect [effect]
  (when-not (map? effect)
    (fail {} "effect must be a map" {:effect effect}))
  (let [{:keys [id param vertex-hook fragment-hook uniforms]} effect]
    (when-not (and (integer? id) (<= 0 id 0xffffffff))
      (fail effect "effect :id must be an unsigned 32-bit integer" {:id id}))
    (when-not (number? (get effect :param 0))
      (fail effect "effect :param must be numeric" {:param param}))
    (doseq [[key hook] [[:vertex-hook vertex-hook] [:fragment-hook fragment-hook]]]
      (when-not (or (nil? hook) (string? hook))
        (fail effect "effect hooks must be GLSL strings" {:hook key :value hook})))
    (when-not (or (nil? uniforms) (map? uniforms))
      (fail effect "effect :uniforms must be a map" {:uniforms uniforms}))
    (when-not (or (nil? (:bleed effect)) (and (number? (:bleed effect))
                                             (<= 0 (:bleed effect))))
      (fail effect "effect :bleed must be a nonnegative number" {:bleed (:bleed effect)}))
    (let [uniforms (mapv #(normalize-uniform effect %)
                         (sort-by (comp name key) (or uniforms {})))
          declarations (str/join "\n"
                         (map #(str "uniform " (:glsl-type %) " " (:glsl-name %) ";")
                              uniforms))
          requires-sdf (true? (:sdf effect))
          program-key (pr-str [vertex-hook fragment-hook declarations requires-sdf])
          effect-key (pr-str [program-key (mapv #(select-keys % [:name :type :values]) uniforms)])]
      (assoc effect
             :param (double (get effect :param 0))
             :bleed (double (get effect :bleed 0))
             :uniforms (into {} (map (fn [uniform]
                                       [(keyword (:name uniform))
                                        {:type (:type uniform)
                                         :value (if (= 1 (:components uniform))
                                                  (let [value (first (:values uniform))]
                                                    (if (= :bool (:type uniform))
                                                      (pos? value)
                                                      value))
                                                  (mapv #(if (= :bool (:type uniform))
                                                           (pos? %)
                                                           %)
                                                        (:values uniform)))}])
                                     uniforms))
             :normalized-uniforms uniforms
             :declarations declarations
             :program-key program-key
             :effect-key effect-key
             :requires-sdf requires-sdf))))

(defn effect-program!
  "Returns context-owned effect data with a native :handle. Linked programs are
  cached per context by both complete hook sources and the typed uniform interface.
  A vertex hook defines `SlipstreamVertex slipstream_vertex(SlipstreamVertex, float)`;
  a fragment hook defines `vec4 slipstream_fragment(vec4, float, float, vec2, float)`
  and returns straight (not premultiplied) color/alpha. Hooks can read `u_effectId`,
  `u_effectParam`, and call `slipstream_sdf_sample`/`slipstream_sdf_coverage`.
  Set `:sdf true` to require a baked tile and `:bleed` to expand the effect quad."
  [context effect]
  (let [effect (normalize-effect effect)
        uniforms (:normalized-uniforms effect)
        size (ffi/layout-size f/gl-uniform-layout)]
    (ffi/with-arena [arena]
      (ffi/with-alloc [uniform-p (max 1 (* size (count uniforms)))]
        (doseq [[i uniform] (map-indexed vector uniforms)]
          (let [p (+ uniform-p (* i size))]
            (ffi/write-field p f/gl-uniform-layout :name
                             (ffi/string->ptr arena (:glsl-name uniform)))
            (ffi/write-field p f/gl-uniform-layout :type (:type-id uniform))
            (ffi/write-field p f/gl-uniform-layout :components (:components uniform))
            (doseq [[j value] (map-indexed vector (:values uniform))]
              (ffi/write-field p f/gl-uniform-layout [:values j] value))))
        (let [handle (f/gl-effect-program
                       (:handle context)
                       (ffi/string->ptr arena (:program-key effect))
                       (ffi/string->ptr arena (:effect-key effect))
                       (ffi/string->ptr arena (:declarations effect))
                       (ffi/string->ptr arena (or (:vertex-hook effect) ""))
                       (ffi/string->ptr arena (or (:fragment-hook effect) ""))
                       (if (:requires-sdf effect) 1 0)
                       uniform-p (count uniforms))]
          (when (or (nil? handle) (zero? handle))
            (let [message (or (f/last-error) "unknown native error")]
              (fail effect (str "effect-program! failed: " message)
                    {:op :effect-program! :native-error message})))
          (-> effect
              (dissoc :normalized-uniforms :declarations :program-key :effect-key :requires-sdf)
              (assoc :handle handle)))))))
