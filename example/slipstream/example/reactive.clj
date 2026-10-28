(ns slipstream.example.reactive
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer])
  (:import [java.io File FileOutputStream]))

(def sdf-fragment
  "vec4 slipstream_fragment(vec4 color, float fill, float sd, vec2 emCoord, float effectParam) {
     float coverage = slipstream_sdf_coverage(sd, fwidth(emCoord));
     return vec4(color.rgb, color.a * coverage * effectParam);
   }")

(defonce scene-state
  (r/atom {:text "SLIP"
           :position [0.15 0.15 0]
           :color [1 1 1 1]
           :path [[:rect 0 0 0.45 0.45]]
           :stops [[0 [1 0.2 0.1 1]] [1 [0.1 0.3 1 1]]]
           :mask {:type :circle :params [0.38 0.38 0.34]}
           :blend-mode :src-over}))
(defonce effect-param (r/atom 1.0))

(defn app [font-path]
  (let [{:keys [text position color path stops mask blend-mode]} @scene-state]
    [:group {}
     [:shape {:path [[:rect 0.02 0.02 0.96 0.96]]
              :fill [0.08 0.12 0.25 1]}]
     [:text {:font font-path :text text :at [0.08 0.78 0] :size 0.16
             :color color}]
     [:group {:mask mask :blend-mode blend-mode}
      [:shape {:path path
               :fill {:type :linear :from [0 0] :to [0.45 0.45] :stops stops}
               :transform position
               :sdf true :sdf-type :msdf :sdf-range 0.2
               :effect {:id 7 :param @effect-param :sdf true
                        :fragment-hook sdf-fragment :bleed 0.1}}]]]))

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "frame state failed" {:error (f/last-error)})))
    {:shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)}))

(defn- write-ppm! [path {:keys [width height pixels]}]
  (let [rgb (byte-array (* width height 3))]
    (doseq [y (range height)
            x (range width)]
      (let [source (* 4 (+ (* (- height 1 y) width) x))
            target (* 3 (+ (* y width) x))]
        (doseq [channel (range 3)]
          (aset rgb (+ target channel) (nth pixels (+ source channel))))))
    (with-open [out (FileOutputStream. (File. (str path)))]
      (.write out (.getBytes (str "P6\n" width " " height "\n255\n") "UTF-8"))
      (.write out rgb))))

(defn- frame-hash [pixels]
  (reduce (fn [value byte]
            (unchecked-multiply (bit-xor value (long byte)) 1099511628211))
          -3750763034362895579 pixels))

(def ^:private golden-frame-hashes
  {:initial -7456958573527989925
   :text -7870320409726486553
   :position-color -6933438049585219359
   :path -3097736242844332688
   :gradient -3075831904602540550
   :mask 7903284231896236728
   :blend -959613850239315786
   :effect-param -8312363664093175917
   :repl 6025851901073379397})

(defn- checkpoint! [results label capture-dir]
  (let [context (sg/current-context)
        _ (when-not context (throw (ex-info "reactive scene has no native context" {})))
        frame (renderer/capture! context)
        state (frame-state context)
        checkpoint (merge state
                          {:label label
                           :context (:handle context)
                           :scene (select-keys @scene-state [:text :position :color])
                           :effect-param @effect-param
                           :frame-hash (frame-hash (:pixels frame))
                           :width (:width frame) :height (:height frame)})]
    (when capture-dir
      (.mkdirs (File. (str capture-dir)))
      (write-ppm! (str capture-dir "/" (name label) ".ppm") frame))
    (swap! results update :checkpoints conj checkpoint)
    checkpoint))

(defn- update-and-check! [results label _capture-dir update]
  (swap! results assoc :pending {:label label :update update}))

(defn- capture-pending! [results capture-dir]
  (when-let [{:keys [label update]} (:pending @results)]
    (swap! results dissoc :pending)
    (checkpoint! results label capture-dir)
    (when update (update))))

(defn smoke-updates! [results capture-dir]
  (let [steps [[:initial #(swap! scene-state assoc :text "LIVE")]
               [:text #(swap! scene-state assoc :position [0.35 0.25 0]
                              :color [0.2 1 0.4 1])]
               [:position-color #(swap! scene-state assoc
                                        :path [[:circle 0.225 0.225 0.225]])]
               [:path #(swap! scene-state assoc :stops
                              [[0 [1 1 0.1 1]] [0.5 [0.8 0.1 0.5 1]] [1 [0.1 1 0.8 1]]])]
               [:gradient #(swap! scene-state assoc :mask
                                  {:type :rect :params [0.15 0.15 0.65 0.65]})]
               [:mask #(swap! scene-state assoc :blend-mode :screen)]
               [:blend #(reset! effect-param 0.35)]
               [:effect-param
                #(ui/on-gui
                   (fn []
                     (swap! scene-state assoc
                            :text "REPL" :position [0.2 0.3 0] :color [1 0.4 0.8 1]
                            :path [[:rect 0 0 0.5 0.35]]
                            :stops [[0 [0.1 0.8 1 1]] [1 [1 0.5 0.1 1]]]
                            :mask {:type :circle :params [0.45 0.45 0.4]}
                            :blend-mode :src-over)
                     (reset! effect-param 1.0)))]]
        index (atom 0)
        wait (atom 0)
        step (atom nil)]
    (reset! step
      (fn []
        (try
          (if-let [[label update] (nth steps @index nil)]
            (do
              (update-and-check! results label capture-dir update)
              (swap! index inc)
              (renderer/schedule! (sg/current-context) @step))
            (if (< @wait 2)
              (do
                (swap! wait inc)
                (renderer/schedule! (sg/current-context) @step))
              (swap! results assoc :pending {:label :repl})))
          (catch Throwable error
            (swap! results assoc :error
                   (ex-info "reactive smoke update failed"
                            {:checkpoints (:checkpoints @results)
                             :cause (ex-message error) :cause-data (ex-data error)} error))))))
    #(renderer/schedule! (sg/current-context)
       (fn [] (renderer/schedule! (sg/current-context) @step)))))

(defn- verify-smoke! [{:keys [checkpoints error]}]
  (when error (throw error))
  (let [by-label (into {} (map (juxt :label identity) checkpoints))]
    (when-not (= 9 (count checkpoints))
      (throw (ex-info "reactive smoke missed checkpoints" {:checkpoints checkpoints})))
    (when-not (apply = (map :context checkpoints))
      (throw (ex-info "reactive smoke replaced the native window" {:checkpoints checkpoints})))
    (doseq [[label expected] golden-frame-hashes]
      (let [actual (get-in by-label [label :frame-hash])]
        (when-not (= expected actual)
          (throw (ex-info "reactive golden frame hash mismatch"
                          {:label label :expected expected :actual actual
                           :checkpoints checkpoints})))))
    (doseq [[before after] [[:text :position-color]
                            [:gradient :mask] [:mask :blend] [:blend :effect-param]]]
      (when-not (= (select-keys (by-label before) [:resource-uploads :shader-compilations])
                   (select-keys (by-label after) [:resource-uploads :shader-compilations]))
        (throw (ex-info "layer-only update rebuilt native resources"
                        {:before (by-label before) :after (by-label after)}))))
    (doseq [[before after] [[:position-color :path] [:path :gradient]]]
      (when-not (= (inc (:resource-uploads (by-label before)))
                   (:resource-uploads (by-label after)))
        (throw (ex-info "geometry/paint update did not replace exactly one node resource"
                        {:before (by-label before) :after (by-label after)}))))
    (mapv #(dissoc % :context) checkpoints)))

(defn run! [font-path {:keys [auto-quit-ms capture-dir smoke?]}]
  (let [results (atom {:checkpoints []})]
    (ui/run #(app font-path)
            :title "Slipstream reactive SDF and effects"
            :width 320 :height 320 :bounds [0 1 0 1]
            :auto-quit-ms auto-quit-ms
            :on-start (when smoke? (smoke-updates! results capture-dir))
            :after-render (when smoke? #(capture-pending! results capture-dir)))
    (when smoke?
      (let [checkpoints (verify-smoke! @results)]
        (println {:status :pass :windows 1 :checkpoints checkpoints})
        checkpoints))))

(defn parse-args [args]
  (loop [remaining (vec (rest args))
         result {:font-path (first args) :smoke? false}]
    (if (empty? remaining)
      (if (:font-path result) result {:error "Usage: slipstream.example.reactive <font.ttf> [--auto-quit-ms N] [--capture-dir PATH]\n"})
      (case (first remaining)
        "--auto-quit-ms" (recur (vec (drop 2 remaining))
                                (assoc result :auto-quit-ms (parse-long (second remaining))
                                       :smoke? true))
        "--capture-dir" (recur (vec (drop 2 remaining))
                               (assoc result :capture-dir (second remaining)))
        (recur (vec (rest remaining)) result)))))

(defn -main [& args]
  (let [{:keys [error font-path] :as options} (parse-args args)]
    (if error
      (do (binding [*out* *err*] (print error)) (System/exit 1))
      (try
        (run! font-path options)
        (System/exit 0)
        (catch Throwable error
          (binding [*out* *err*] (println (ex-message error) (ex-data error)))
          (System/exit 1))))))
