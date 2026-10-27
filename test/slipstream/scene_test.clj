(ns slipstream.scene-test
  (:require [clojure.test :refer [deftest is]]
            [slipstream.core :as slug]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT to a TrueType/OpenType font before running scene tests"
                      {:environment-variable "SLIPSTREAM_TEST_FONT"}))))

(defn- capture-ex-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(defn- normalized-child [normalized id]
  (first (filter #(= id (:id %)) (:children normalized))))

(deftest normalization-applies-scene-and-node-defaults
  (let [normalized (slug/normalize
                     [:scene
                      [:font {:id :body :path test-font}]
                      [:path {:id :rect} [:rect 0 0 1 1]]
                      [:shape {:path :rect}]
                      [:text {:font :body} "A"]])]
    (is (= {:texture-width 512 :curve-precision 32} (:options normalized)))
    (is (= [65] (:codepoints (normalized-child normalized :body))))
    (is (= [0.0 0.0 0.0 1.0] (get-in normalized [:children 2 :fill])))
    (is (= 1.0 (get-in normalized [:children 3 :size])))
    (is (= [0.0 0.0 0.0] (get-in normalized [:children 3 :transform])))
    (is (= [0.0 0.0 0.0 1.0] (get-in normalized [:children 3 :color])))))

(deftest normalization-infers-distinct-unicode-codepoints-across-nested-text
  (let [normalized (slug/normalize
                     [:scene
                      [:font {:id :body :path test-font}]
                      [:group
                       [:text {:font :body} "éA"]
                       [:composite
                        [:text {:font :body} "😀é"]]]])]
    (is (= [65 233 128512] (:codepoints (normalized-child normalized :body))))))

(deftest normalization-accepts-forward-references-and-the-complete-node-vocabulary
  (let [normalized (slug/normalize
                     [:scene {:texture-width 256 :curve-precision 16}
                      [:layer {:key :art :draw-mode :visible :blend-mode :multiply}]
                      [:text {:font :body :align :center :anchor :cap-top} "A"]
                      [:shape {:id :flat :path :rect :fill [1 0 0 1]}]
                      [:shape {:id :outline :path :combined :stroke 0.1 :paint :sky
                               :join :round :cap :square :miter-limit 3
                               :scale 2 :origin [0.5 0.5]
                               :splits-x [0.25 0.75] :splits-y [0.5] :sdf true}]
                      [:composite {:id :art :advance 4
                                   :mask {:type :circle :params [0.5 0.5 0.25]}}
                       [:group
                        [:shape {:path :rect :fill :sky}]
                        [:shape {:path :rect :fill :tiles}]]
                       [:define-shape {:path :combined}]]
                      [:pattern {:id :tiles :tile :tile :cell [0 0 1 1]
                                 :transform [1 0 0 1 0 0]
                                 :repeat-x :reflect :repeat-y :repeat}]
                      [:define-shape {:id :tile :path :rect}]
                      [:sweep-gradient {:id :sweep :center [0 0]
                                        :start-angle 0 :end-angle 1}
                       [0 [1 1 1 1]]]
                      [:radial-gradient {:id :radial :center [0 0]
                                         :inner-radius 0 :outer-radius 1}
                       [0 [1 1 1 1]]]
                      [:linear-gradient {:id :sky :from [0 0] :to [1 0]}
                       [0 [1 0 0 1]] [1 [0 0 1 1]]]
                      [:path {:id :combined}
                       [:path :rect]
                       [:save] [:translate 1 2] [:rotate 0.5] [:scale 2 2]
                       [:transform 1 0 0 1 0 0] [:set-transform 1 0 0 1 0 0]
                       [:reset-transform] [:restore]]
                      [:path {:id :rect}
                       [:move-to 0 0] [:line-to 1 0]
                       [:quad-to 1.2 0.2 1 0.4]
                       [:bezier-to 0.8 0.6 0.6 0.8 0.4 1]
                       [:close] [:rect 0 0 1 1] [:rounded-rect 0 0 1 1 0.1]
                       [:circle 0.5 0.5 0.4] [:ellipse 0.5 0.5 0.4 0.3]
                       [:arc 0.5 0.5 0.4 0 1] [:arc-to 0 0 1 1 0.2]]
                      [:font {:id :body :path test-font :uniform true :mask 7}]])]
    (is (= 13 (count (:children normalized))))
    (is (= {:texture-width 256 :curve-precision 16} (:options normalized)))
    (is (= [65] (:codepoints (normalized-child normalized :body))))))

(deftest normalization-supports-explicit-font-codepoint-ascii-and-all-modes
  (is (= [65 233] (:codepoints (normalized-child
                                 (slug/normalize
                                   [:scene [:font {:id :f :path test-font
                                                   :codepoints [233 65]}]]) :f))))
  (is (= :ascii (:mode (normalized-child
                         (slug/normalize
                           [:scene [:font {:id :f :path test-font :ascii true}]]) :f))))
  (is (= :all (:mode (normalized-child
                       (slug/normalize
                         [:scene [:font {:id :f :path test-font :all true}]]) :f)))))

(deftest normalization-rejects-malformed-nodes-with-scene-context
  (let [invalid-scenes
        [[:not-a-scene]
         [:scene {} [:unknown {}]]
         [:scene [:font]]
         [:scene [:font {:id :f :path test-font} "unexpected"]]
         [:scene [:font {:id :f :path test-font :codepoints nil}]]
         [:scene [:pattern {:id :p :tile :t :cell [0 0 1 1]} "unexpected"]
          [:define-shape {:id :t} [:rect 0 0 1 1]]]
         [:scene {:texture-width 0}]
         [:scene {:curve-precision 24}]
         [:scene [:path {:id :p} [:move-to 0]]]
         [:scene [:path {:id :p} [:transform 1 0 1]]]
         [:scene [:path {:id :p} [:arc 0 0 1 0 1 :bad]]]
         [:scene [:path {:id :p} [:rect 0 0 1 1]]
          [:shape {:path :p :fill [1 0 0]}]]
         [:scene [:path {:id :p} [:rect 0 0 1 1]]
          [:shape {:path :p :stroke 0.1 :join :diamond}]]
         [:scene [:path {:id :p} [:rect 0 0 1 1]]
          [:shape {:path :p :fill [1 0 0 1] :stroke 0.1}]]
         [:scene [:path {:id :p} [:rect 0 0 1 1]]
          [:shape {:path :p :fill [1 0 0 1] :color [0 0 0 1]}]]
         [:scene [:path {:id :dup} [:rect 0 0 1 1]]
          [:path {:id :dup} [:circle 0 0 1]]]
         [:scene [:shape {:path :missing}]]
         [:scene [:text {:font :missing} "A"]]
         [:scene [:path {:id :p} [:rect 0 0 1 1]]
          [:shape {:path :p :fill :missing}]]
         [:scene [:layer {:key :missing}]]
         [:scene [:pattern {:id :p :tile :missing :cell [0 0 1 1]}]]
         [:scene [:linear-gradient {:id :g :from [0 0] :to [1 0]}]]
         [:scene [:linear-gradient {:id :g :from [0] :to [1 0]}
                  [0 [1 1 1 1]]]]
         [:scene [:pattern {:id :p :tile :t :cell [0 0 1]}]
          [:define-shape {:id :t} [:rect 0 0 1 1]]]
         [:scene [:text {:font :f :at [0]} "A"]
          [:font {:id :f :path test-font}]]
         [:scene [:text {:font :f :align :justified} "A"]
          [:font {:id :f :path test-font}]]
         [:scene [:text {:font :f} "A" "B"]
          [:font {:id :f :path test-font}]]
         [:scene [:layer {:key :s :draw-mode :invisible}]
          [:define-shape {:id :s} [:rect 0 0 1 1]]]
         [:scene [:group {:id :a} [:layer {:key :b}]]
          [:group {:id :b} [:layer {:key :a}]]]
         [:scene [:path {:id :a} [:path :b]]
          [:path {:id :b} [:path :a]]]]]
    (doseq [scene invalid-scenes]
      (let [error (capture-ex-info #(slug/normalize scene))
            data (ex-data error)]
        (is (some? error) (pr-str scene))
        (is (contains? data :scene-path) (pr-str scene))
        (is (contains? data :node) (pr-str scene))))))

(deftest compilation-resolves-definitions-and-preserves-root-and-composite-order
  (let [data [:scene {:texture-width 256}
              [:shape {:id :flat :path :rect :fill [1 0 0 1]}]
              [:layer {:key :art :transform [1 2 0]}]
              [:text {:font :body :at [0 -1] :size 1
                      :color [0 1 0 1] :align :left :anchor :baseline} "Aé"]
              [:composite {:id :art :advance 4
                           :mask {:type :circle :params [0.5 0.5 0.25] :invert true}}
               [:shape {:id :gradient-shape :path :rect :fill :sky}]
               [:shape {:id :pattern-shape :path :rect :fill :tiles}]]
              [:pattern {:id :tiles :tile :tile :cell [0 0 1 1]
                         :repeat-x :reflect :repeat-y :repeat}]
              [:linear-gradient {:id :sky :from [0 0] :to [1 0]}
               [0 [1 0 0 1]] [1 [0 0 1 1]]]
              [:define-shape {:id :tile :path :rect}]
              [:path {:id :rect} [:rect 0 0 1 1]]
              [:font {:id :body :path test-font}]]]
    (slug/with-scene [scene data]
      (is (true? (slug/built? scene)))
      (is (= ["flat" :art [:codepoint 65] [:codepoint 233]]
             (mapv :key (:layers scene))))
      (is (= ["flat" "gradient-shape" "pattern-shape" [:codepoint 65] [:codepoint 233]]
             (mapv :key (slug/layers scene))))
      (is (= 400 (alength (:vertices (slug/mesh scene)))))
      (is (= 30 (alength (:indices (slug/mesh scene)))))
      (is (= 80 (alength (:vertices (slug/mesh scene [{:key :flat}])))))
      (is (pos? (:width (slug/shape scene :flat))))
      (is (= {:type :circle :params [0.5 0.5 0.25 0.0 0.0 0.0] :invert true}
             (:mask (slug/composite scene :art))))
      (is (= 4.0 (:advance (slug/composite scene :art)))))))

(deftest compilation-realizes-reusable-definitions-outside-the-root
  (slug/with-scene [scene [:scene
                           [:define-shape {:id :unused} [:rect 0 0 1 1]]
                           [:composite {:id :unused-root} [:layer {:key :unused}]]
                           [:shape {:id :root} [:rect 0 0 1 1]]]]
    (is (some? (slug/shape scene :unused)))
    (is (= 80 (alength (:vertices (slug/mesh scene :unused-root)))))))

(deftest definition-order-does-not-change-scene-geometry
  (let [definitions [[:path {:id :rect} [:rect 0 0 1 1]]
                     [:linear-gradient {:id :paint :from [0 0] :to [1 0]}
                      [0 [1 0 0 1]] [1 [0 0 1 1]]]]
        roots [[:shape {:id :shape :path :rect :fill :paint}]
               [:group [:shape {:path :rect :fill [0 1 0 1] :at [2 3]}]]]
        mesh-data (fn [nodes]
                    (slug/with-scene [scene (into [:scene] (concat nodes roots))]
                      (let [{:keys [vertices indices]} (slug/mesh scene)]
                        [(vec vertices) (vec indices)])))]
    (is (= (mesh-data definitions) (mesh-data (reverse definitions))))))

(deftest scene-validation-precedes-native-mutation
  (let [called (atom false)]
    (with-redefs [slug/make-atlas (fn [& _]
                                    (reset! called true)
                                    (throw (ex-info "must not create atlas" {})))]
      (is (some? (capture-ex-info
                   #(slug/compile [:scene [:shape {:path :missing}]]))))
      (is (false? @called)))))

(deftest with-scene-destroys-native-resources-and-rejects-stale-access
  (let [captured (atom nil)]
    (slug/with-scene [scene [:scene
                             [:path {:id :rect} [:rect 0 0 1 1]]
                             [:shape {:id :rect-shape :path :rect}]]]
      (reset! captured scene)
      (is (some? (slug/shape scene :rect-shape))))
    (is (true? @(:destroyed? @captured)))
    (is (true? @(:destroyed? (:atlas @captured))))
    (is (= :shape (:op (ex-data (capture-ex-info #(slug/shape @captured :rect-shape))))))
    (slug/destroy! @captured)
    (is (true? @(:destroyed? @captured)))))

(deftest mesh-requires-an-explicit-root-without-a-compiled-scene
  (let [atlas (slug/make-atlas)]
    (try
      (is (= :mesh (:op (ex-data (capture-ex-info #(slug/mesh atlas))))))
      (finally
        (slug/destroy! atlas)))))

(defn- cleanup-failure-result [compilation-failure?]
  (let [original-make-atlas slug/make-atlas
        original-make-path slug/make-path
        original-build slug/build!
        original-destroy slug/destroy!
        atlas (atom nil)
        paths (atom [])
        attempts (atom [])
        injected (atom false)]
    (try
      (with-redefs [slug/make-atlas (fn [& args]
                                      (let [resource (apply original-make-atlas args)]
                                        (reset! atlas resource)
                                        resource))
                    slug/make-path (fn [& args]
                                     (let [resource (apply original-make-path args)]
                                       (swap! paths conj resource)
                                       resource))
                    slug/build! (if compilation-failure?
                                  (fn [& _]
                                    (throw (ex-info "injected compilation failure"
                                                    {:injected :compilation})))
                                  original-build)
                    slug/destroy! (fn [resource]
                                    (let [kind (cond
                                                 (instance? slipstream.core/Path resource) :path
                                                 (instance? slipstream.core/Atlas resource) :atlas
                                                 :else :other)]
                                      (swap! attempts conj kind)
                                      (original-destroy resource)
                                      (when (and (= :path kind)
                                                 (compare-and-set! injected false true))
                                        (throw (ex-info "injected path cleanup failure"
                                                        {:injected :path-cleanup})))))]
        (let [error (capture-ex-info
                      #(slug/compile [:scene
                                      [:path {:id :first} [:rect 0 0 1 1]]
                                      [:path {:id :second} [:rect 1 1 2 2]]
                                      [:shape {:path :first}]
                                      [:shape {:path :second}]]))]
          {:error error
           :path-count (count @paths)
           :path-attempts (count (filter #(= :path %) @attempts))
           :atlas-attempts (count (filter #(= :atlas %) @attempts))
           :paths-destroyed (mapv #(deref (:destroyed? %)) @paths)
           :atlas-destroyed (some-> @atlas :destroyed? deref)}))
      (finally
        (doseq [path @paths]
          (original-destroy path))
        (when @atlas
          (original-destroy @atlas))))))

(deftest path-cleanup-failure-destroys-atlas-and-attempts-every-path
  (let [result (cleanup-failure-result false)]
    (is (= :path-cleanup (:injected (ex-data (:error result)))))
    (is (= 2 (:path-count result)))
    (is (= 2 (:path-attempts result)))
    (is (= 1 (:atlas-attempts result)))
    (is (= [true true] (:paths-destroyed result)))
    (is (true? (:atlas-destroyed result)))))

(deftest compilation-failure-is-preserved-when-path-cleanup-also-fails
  (let [result (cleanup-failure-result true)]
    (is (= :compilation (:injected (ex-data (:error result)))))
    (is (= 2 (:path-count result)))
    (is (= 2 (:path-attempts result)))
    (is (= 1 (:atlas-attempts result)))
    (is (= [true true] (:paths-destroyed result)))
    (is (true? (:atlas-destroyed result)))))
