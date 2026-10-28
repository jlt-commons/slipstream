(ns slipstream.glimmer-effects-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer :as sg]
            [slipstream.core :as core]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-effects :as effects]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private background [38 38 38 255])
(def ^:private tolerance 6)

(def ^:private inside-fragment
  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
     return vec4(color.rgb, color.a * coverage * effectParam);
   }")

(def ^:private outside-fragment
  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
     return vec4(color.rgb, color.a * (1.0 - coverage) * effectParam);
   }")

(defn- upload
  ([context] (upload context {}))
  ([context options]
   (let [cpu (compiler/compile-shape
               (merge {:path [[:rect 0.25 0.25 0.5 0.5]]
                       :fill [1 0 0 1] :texture-width 128}
                      options))
         handle (renderer/upload! context cpu)]
     {:cpu cpu :handle handle :paint (renderer/paint-texture! context handle)})))

(defn- release [context resource]
  (renderer/release! context (:handle resource))
  (resources/release! (:cpu resource)))

(defn- draw [resource effect]
  {:handle (:handle resource)
   :paint (:paint resource)
   :transform [0.25 0.25 0]
   :color [1 0 0 1]
   :effect (:handle effect)
   :effect-id (get effect :id 1)
   :effect-param (get effect :param 1)
   :bleed (get effect :bleed 0)})

(defn- capture [context commands]
  (renderer/render! context commands)
  (renderer/capture! context))

(defn- pixel [frame [x y]]
  (let [x (min (dec (:width frame)) (* x (/ (:width frame) 64)))
        y (min (dec (:width frame)) (* y (/ (:height frame) 64)))
        offset (* 4 (+ (* y (:width frame)) x))]
    (mapv #(bit-and 0xff (nth (:pixels frame) (+ offset %))) (range 4))))

(defn- check-pixel [frame position expected]
  (let [actual (pixel frame position)]
    (is (every? true? (map #(<= (Math/abs (- (int %1) (int %2))) tolerance)
                           actual expected))
        (str "pixel " position " expected " expected " but got " actual))))

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "frame state failed" {:error (f/last-error)})))
    {:shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)}))

(defmacro ^:private with-context [[context] & body]
  `(let [~context (renderer/create-context
                    {:title "slipstream effect goldens" :width 64 :height 64
                     :bounds [0 1 0 1] :visible false})]
     (try
       ~@body
       (finally
         (renderer/destroy! ~context)))))

(deftest real-sdf-and-msdf-inside-and-outside-golden-frames
  (with-context [context]
    (doseq [sdf-type [:sdf :msdf]]
      (testing (str sdf-type)
        (let [resource (upload context {:sdf true :sdf-type sdf-type :sdf-range 0.2})
              inside (effects/effect-program!
                       context {:id 1 :param 1 :sdf true :fragment-hook inside-fragment})
              outside (effects/effect-program!
                        context {:id 2 :param 1 :sdf true :fragment-hook outside-fragment
                                 :bleed 0.25})]
          (try
            (let [frame (capture context [(draw resource inside)])]
              (check-pixel frame [32 32] [255 0 0 255])
              (check-pixel frame [8 32] background))
            (let [frame (capture context [(draw resource outside)])]
              (check-pixel frame [32 32] background)
              (check-pixel frame [8 32] [255 0 0 255]))
            (finally
              (release context resource))))))))

(deftest effect-id-and-parameter-updates-do-not-rebuild-atlases-or-programs
  (with-context [context]
    (let [resource (upload context)
          effect {:id 7 :param 0.5 :fragment-hook
                  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
                     float idGain = u_effectId == 7 ? 1.0 : 2.0;
                     return vec4(color.rgb, color.a * fill * effectParam * idGain);
                   }"}
          program (effects/effect-program! context effect)
          before (frame-state context)]
      (try
        (check-pixel (capture context [(draw resource program)]) [32 32] [147 19 19 255])
        (let [updated (effects/effect-program! context (assoc effect :id 8 :param 1))
              after (frame-state context)]
          (is (= (:handle program) (:handle updated)))
          (is (= before after))
          (check-pixel (capture context [(draw resource updated)]) [32 32] [255 0 0 255]))
        (finally
          (release context resource))))))

(deftest typed-uniforms-and-vertex-fragment-hooks-affect-real-frames
  (with-context [context]
    (let [resource (upload context)
          effect (effects/effect-program!
                   context
                   {:id 9 :param 1
                    :vertex-hook
                    "SlipstreamVertex slipstream_vertex(SlipstreamVertex vertex, float effectParam) {
                       vertex.position.x += u_shift;
                       vertex.emCoord.x -= u_shift;
                       return vertex;
                     }"
                    :fragment-hook
                    "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
                       vec4 tint = u_enabled && u_mode == 3 ? u_tint : color;
                       return vec4(tint.rgb, tint.a * fill * u_alpha);
                     }"
                    :uniforms {:shift {:type :float :value 0.125}
                               :tint {:type :vec4 :value [0 1 0 1]}
                               :alpha {:type :float :value 1.0}
                               :mode {:type :int :value 3}
                               :enabled {:type :bool :value true}}})]
      (try
        (let [frame (capture context [(draw resource effect)])]
          (check-pixel frame [40 32] [0 255 0 255])
          (check-pixel frame [16 32] background))
        (finally
          (release context resource))))))

(deftest integer-unsigned-and-boolean-vector-uniforms-preserve-exact-values
  (with-context [context]
    (let [resource (upload context)
          effect (effects/effect-program!
                   context
                   {:id 10 :param 1
                    :fragment-hook
                    "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
                       bool exact = u_intOdd == 16777217 &&
                                    u_uintHigh == 4294967295u &&
                                    u_intMin == (-2147483647 - 1) &&
                                    u_flags.x && !u_flags.y;
                       vec3 rgb = exact ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
                       return vec4(rgb, fill);
                     }"
                    :uniforms {:intOdd {:type :int :value 16777217}
                               :uintHigh {:type :uint :value 4294967295}
                               :intMin {:type :int :value -2147483648}
                               :flags {:type :bvec2 :value [true false]}}})]
      (try
        (check-pixel (capture context [(draw resource effect)]) [32 32] [0 255 0 255])
        (finally
          (release context resource))))))

(deftest integer-unsigned-and-boolean-vector-uniforms-reject-invalid-values
  (doseq [spec [{:type :int :value 1.5}
                {:type :int :value 2147483648}
                {:type :int :value -2147483649}
                {:type :uint :value -1}
                {:type :uint :value 1.5}
                {:type :uint :value 4294967296}
                {:type :bvec2 :value [true 1]}
                {:type :bvec3 :value [true false 0]}
                {:type :bvec4 :value [true false true nil]}]]
    (let [error (try
                  (effects/normalize-effect {:id 1 :uniforms {:bad spec}})
                  nil
                  (catch Throwable error error))]
      (is (some? error) (pr-str spec))
      (is (vector? (:scene-path (ex-data error)))))))

(deftest effect-program-cache-is-complete-and-context-local
  (let [a (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})
        b (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})
        effect {:id 1 :param 1 :fragment-hook inside-fragment :sdf true
                :uniforms {:gain {:type :float :value 1}}}]
    (try
      (let [a1 (effects/effect-program! a effect)
            before (frame-state a)
            a2 (effects/effect-program! a effect)
            after-same (frame-state a)
            a3 (effects/effect-program! a (assoc-in effect [:uniforms :gain :value] 2))
            after-values (frame-state a)
            b1 (effects/effect-program! b effect)]
        (is (= (:handle a1) (:handle a2)))
        (is (not= (:handle a1) (:handle b1)))
        (is (= before after-same after-values))
        (is (= (:shader-compilations before) (:shader-compilations (frame-state b))))
        (is (some? a3)))
      (finally
        (renderer/destroy! a)
        (renderer/destroy! b)))))

(deftest malformed-hooks-compile-and-link-failures-report-scene-paths
  (with-context [context]
    (doseq [effect [{:id 1 :scene-path [:children 2 :effect]
                     :fragment-hook "not a valid hook"}
                    {:id 1 :scene-path [:children 3 :effect]
                     :vertex-hook
                     "out vec2 hookMismatch;
                      SlipstreamVertex slipstream_vertex(SlipstreamVertex vertex, float effectParam) {
                        hookMismatch = vec2(0.0);
                        vertex.position.x += hookMismatch.x;
                        return vertex;
                      }"
                     :fragment-hook
                     "in vec4 hookMismatch;
                      vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
                        return color + hookMismatch;
                      }"}
                    {:id 1 :scene-path [:children 4 :effect]
                     :fragment-hook
                     "vec4 wrong_signature(vec4 color) { return color; }"}]]
      (let [error (try
                    (effects/effect-program! context effect)
                    nil
                    (catch Throwable error error))]
        (is (some? error))
        (is (= (:scene-path effect) (:scene-path (ex-data error))))
        (is (seq (:native-error (ex-data error))))))))

(deftest missing-sdf-data-and-invalid-effect-data-report-scene-paths
  (with-context [context]
    (let [resource (upload context)
          effect (effects/effect-program! context {:id 1 :param 1 :sdf true})]
      (try
        (let [error (try
                      (capture context [(draw resource effect)])
                      nil
                      (catch Throwable error error))]
          (is (some? error))
          (is (vector? (:scene-path (ex-data error))))
          (is (re-find #"SDF" (.getMessage error))))
        (finally
          (release context resource))))
    (doseq [effect [{:id -1}
                    {:id 1 :param "large"}
                    {:id 1 :uniforms {:bad {:type :sampler2D :value 0}}}
                    {:id 1 :uniforms {:bad {:type :vec4 :value [1 2]}}}]]
      (let [error (try
                    (effects/effect-program! context effect)
                    nil
                    (catch Throwable error error))]
        (is (some? error))
        (is (vector? (:scene-path (ex-data error))))))))

(deftest missing-sdf-error-identifies-the-exact-failing-draw-command
  (with-context [context]
    (let [valid (upload context {:sdf true})
          missing (upload context)
          effect (effects/effect-program! context {:id 1 :param 1 :sdf true})]
      (try
        (let [error (try
                      (capture context [(draw valid effect) (draw missing effect)])
                      nil
                      (catch Throwable error error))]
          (is (some? error))
          (is (= [1 :effect] (:scene-path (ex-data error))))
          (is (re-find #"SDF" (.getMessage error))))
        (finally
          (release context valid)
          (release context missing))))))

(deftest baked-sdf-texture-format-matches-the-requested-generator
  (doseq [[sdf-type format] [[:sdf :r32f] [:msdf :rgb32f]]]
    (let [cpu (compiler/compile-shape {:path [[:rect 0 0 1 1]] :sdf true
                                       :sdf-type sdf-type :texture-width 128})]
      (try
        (let [texture (core/texture (:atlas cpu) :sdf)]
          (is (= format (:format texture)))
          (is (pos? (:byte-count texture))))
        (finally
          (resources/release! cpu))))))

(deftest effect-data-and-parameters-are-layer-only-reactive-inputs
  (let [base {:path [[:rect 0 0 1 1]] :fill [1 0 0 1] :sdf true}]
    (is (= (compiler/geometry-key base)
           (compiler/geometry-key
             (assoc base :effect {:id 9 :param 0.5 :fragment-hook inside-fragment}
                    :effect-id 8 :effect-param 0.25 :bleed 0.2))))))

(deftest authored-widget-path-reaches-effect-compilation
  (let [seen (atom nil)
        shape (atom {:tag :shape
                     :props {:scene-path [:children 7 :shape]
                             :effect {:id 9 :fragment-hook "broken"}}
                     :resource {:handle {:native 1 :paint 0}
                                :root {:color [1 0 0 1]}}})
        scene (atom {:children [shape]})]
    (with-redefs [slipstream.glimmer-effects/effect-program!
                  (fn [_ data]
                    (reset! seen (:scene-path data))
                    (throw (ex-info "injected effect failure"
                                    {:scene-path (:scene-path data)})))]
      (is (thrown-with-msg? Exception #"injected effect failure"
            ((var-get #'sg/draw-tree) scene))))
    (is (= [:children 7 :shape :effect] @seen))))

(deftest offscreen-group-opacity-is-applied-only-at-the-composite-boundary
  (let [shape (atom {:tag :shape :props {:fill [1 0 0 1]}
                     :resource {:handle {:native 1 :paint 0}
                                :root {:color [1 0 0 1]}}})
        group (atom {:tag :group :props {:opacity 0.5 :mask {:type :circle}}
                     :children [shape]})
        scene (atom {:children [group]})
        commands ((var-get #'sg/draw-tree) scene)]
    (is (= 3 (count commands)))
    (is (= [1 1 1 0.5] (get-in commands [0 :color])))
    (is (= [1 0 0 1] (get-in commands [1 :color])))))
