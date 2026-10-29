(ns slipstream.demoscene-profiles-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.nrepl :as jolt-nrepl]
            [slipstream.example.demoscene :as demo]
            [slipstream.example.demoscene-profiles :as profiles]
            [slipstream.example.demoscene-shader :as visuals]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for demoscene profile tests" {}))))

(def ^:private bounds [0 1.6 0 1])

(defn- profile-state
  ([profile] (profile-state profile 1))
  ([profile intensity]
   (assoc visuals/initial-state
          :profile profile
          :intensity intensity
          :font test-font
          :bounds bounds
          :aspect 1.6
          :time 0.75
          :nrepl-port 45678)))

(defn- scene-elements [scene]
  (let [elements (atom [])]
    (letfn [(visit [node]
              (when (vector? node)
                (let [[tag props] node]
                  (when (map? props)
                    (swap! elements conj (assoc props :tag tag)))
                  (doseq [child (drop 2 node)] (visit child)))))]
      (visit scene))
    @elements))

(defn- elements-by-id [elements id]
  (filter #(= id (:id %)) elements))

(defn- id-namespace? [expected-namespace]
  #(= expected-namespace (some-> (:id %) namespace)))

(defn- error-data [work]
  (try
    (work)
    nil
    (catch Throwable error
      (or (ex-data error)
          (throw error)))))

(deftest profile-configuration-validates-names-intensity-sdf-and-mesh-budget
  (doseq [profile [:showcase :geometry :text :compositor :shader :all]]
    (testing (name profile)
      (is (= {:profile profile :intensity 1 :sdf nil}
             (profiles/profile-config profile {})))
      (is (= {:profile profile :intensity 2 :sdf true}
             (profiles/profile-config profile {:intensity 2 :sdf true})))))
  (is (= :unknown-profile
         (:reason (error-data #(profiles/profile-config :unknown {})))))
  (doseq [intensity [0 -1 1.5 "1" 17]]
    (testing (pr-str intensity)
      (is (= :invalid-intensity
             (:reason (error-data #(profiles/profile-config :all
                                                            {:intensity intensity})))))))
  (is (= :invalid-sdf
         (:reason (error-data #(profiles/profile-config :geometry {:sdf "true"})))))
  (let [overflow (error-data #(profiles/profile-scene (profile-state :geometry 17)))]
    (is (= :mesh-limit (:reason overflow)))
    (is (= 65536 (:mesh-limit overflow)))
    (is (< 65536 (:estimated-vertices overflow)))))

(deftest sdf-type-defaults-to-msdf-and-validates-explicit-choice
  (is (= {:profile :geometry :intensity 1 :sdf true :sdf-type :sdf}
         (profiles/profile-config :geometry {:sdf true :sdf-type :sdf})))
  (doseq [sdf-type [:sdf :msdf]]
    (is (= sdf-type
           (:sdf-type (profiles/profile-config :geometry {:sdf-type sdf-type})))))
  (doseq [sdf-type [false :unknown "sdf"]]
    (is (= :invalid-sdf-type
           (:reason (error-data #(profiles/profile-config :geometry
                                                          {:sdf-type sdf-type}))))))
  (let [geometry-elements (fn [sdf-type]
                            (scene-elements
                              (profiles/profile-scene
                                (assoc (profile-state :geometry) :sdf true
                                       :sdf-type sdf-type))))]
    (is (every? #(= :msdf (:sdf-type %))
                (filter #(= :shape (:tag %)) (geometry-elements nil))))
    (is (every? #(= :sdf (:sdf-type %))
                (filter #(= :shape (:tag %)) (geometry-elements :sdf))))))

(deftest showcase-profile-is-the-existing-scene-without-new-workload
  (let [state (profile-state :showcase)]
    (is (= (visuals/scene-for-state state) (profiles/profile-scene state)))
    (is (= 12 (count (filter (id-namespace? "vector")
                             (scene-elements (profiles/profile-scene state))))))))

(deftest geometry-profile-has-bounded-independent-unique-and-shared-work
  (let [elements (scene-elements (profiles/profile-scene (profile-state :geometry)))
        unique (vec (filter (id-namespace? "geometry-unique") elements))
        shared (vec (filter (id-namespace? "geometry-shared") elements))]
    (is (= 4 (count unique)))
    (is (= 4 (count shared)))
    (is (= 4 (count (into #{} (map :path) unique))))
    (is (= 8 (count (into #{} (map :id) (concat unique shared)))))
    (doseq [i (range 4)]
      (is (= (select-keys (first (elements-by-id elements
                                                 (keyword "geometry-unique" (str i))))
                          [:path :fill])
             (select-keys (first (elements-by-id elements
                                                 (keyword "geometry-shared" (str i))))
                          [:path :fill]))))
    (is (= 8 (count (filter (id-namespace? "geometry-unique")
                            (scene-elements
                              (profiles/profile-scene (profile-state :geometry 2)))))))
    (let [sdf (filter #(contains? #{"geometry-unique" "geometry-shared"}
                                  (some-> (:id %) namespace))
                      (scene-elements
                        (profiles/profile-scene
                          (assoc (profile-state :geometry) :sdf true))))]
      (is (seq sdf))
      (is (every? :sdf sdf)))))

(deftest text-profile-has-long-repeated-unicode-and-bounded-lines
  (let [texts (filter (id-namespace? "stress-text")
                      (scene-elements (profiles/profile-scene (profile-state :text))))]
    (is (= 4 (count texts)))
    (is (every? #(<= 120 (count (:text %))) texts))
    (is (every? #(<= 2 (count (re-seq #"SLIPSTREAM" (:text %)))) texts))
    (is (every? #(re-find #"À.*λ.*¡" (:text %)) texts))
    (is (= 8 (count (filter (id-namespace? "stress-text")
                            (scene-elements
                              (profiles/profile-scene (profile-state :text 2)))))))))

(deftest compositor-profile-nests-masks-blends-and-rich-paints
  (let [elements (scene-elements (profiles/profile-scene (profile-state :compositor)))
        outer (filter (id-namespace? "compositor-outer") elements)
        nested (filter (id-namespace? "compositor-nested") elements)
        paints (filter #(= :shape (:tag %)) elements)]
    (is (= 2 (count outer)))
    (is (= 2 (count nested)))
    (is (every? :mask (concat outer nested)))
    (is (some #{:multiply :screen} (map :blend-mode outer)))
    (is (some #(= :linear (get-in % [:fill :type])) paints))
    (is (some #(= :pattern (get-in % [:fill :type])) paints))
    (let [sdf (filter (id-namespace? "compositor-outer")
                      (scene-elements
                        (profiles/profile-scene
                          (assoc (profile-state :compositor) :sdf true))))]
      (is (every? #(= :sdf-tile (get-in % [:mask :type])) sdf)))))

(deftest shader-profile-uses-fullscreen-passes-and-all-combines-every-workload
  (let [shader (scene-elements (profiles/profile-scene (profile-state :shader 3)))
        passes (filter (id-namespace? "shader-pass") shader)]
    (is (= 3 (count passes)))
    (is (every? #(= [[:rect 0.0 0.0 1.6 1]] (:path %)) passes))
    (is (every? #(= 4.0 (get-in % [:effect :uniforms :density :value])) passes))
    (let [all (scene-elements (profiles/profile-scene (profile-state :all)))]
      (doseq [namespace ["geometry-unique" "geometry-shared" "stress-text"
                         "compositor-outer" "compositor-nested" "shader-pass"]]
        (is (seq (filter (id-namespace? namespace) all)) namespace)))))

(deftest profile-hud-is-aware-of-workload-and-metrics-text-is-change-driven
  (let [state (assoc (profile-state :geometry) :metrics true
                     :metrics-text "FPS=42.0 resources=8")
        scene (profiles/profile-scene state)
        status (first (elements-by-id (scene-elements scene) :hud-status))
        metrics (first (elements-by-id (scene-elements scene) :hud-metrics))]
    (is (re-find #"PROFILE GEOMETRY" (:text status)))
    (is (re-find #"INTENSITY 1" (:text status)))
    (is (= "FPS=42.0 resources=8" (:text metrics)))
    (let [updated (profiles/profile-scene
                    (assoc state :time 99.0 :burst 0.5 :geometry-stage 0))]
      (is (= (map :text (filter #(#{:hud-title :hud-status :hud-controls :hud-metrics}
                                    (:id %))
                                (scene-elements scene)))
             (map :text (filter #(#{:hud-title :hud-status :hud-controls :hud-metrics}
                                    (:id %))
                                (scene-elements updated))))
          "draw-only animation must not rewrite HUD text")
      (is (= (mapv :path (filter (id-namespace? "geometry-unique") (scene-elements scene)))
             (mapv :path (filter (id-namespace? "geometry-unique") (scene-elements updated))))
          "draw-only animation must not rewrite geometry"))))

(deftest cli-parses-profile-workload-metrics-and-summary-options
  (is (= {:font-path test-font :port 7888 :capture-dir nil
          :max-frames nil :auto-quit-ms nil}
         (demo/parse-args [test-font])))
  (is (= {:font-path test-font :port 0 :capture-dir nil
          :max-frames 2 :auto-quit-ms nil
          :profile :all :intensity 3 :sdf true :sdf-type :sdf
          :warmup-ms 0 :metrics false :summary false}
         (demo/parse-args [test-font "--profile" "all" "--intensity" "3"
                           "--sdf" "true" "--sdf-type" "sdf" "--warmup-ms" "0"
                           "--metrics" "false" "--summary" "false" "--port" "0"
                           "--max-frames" "2"])))
  (doseq [args [[test-font "--profile" "unknown"]
                [test-font "--intensity" "0"]
                [test-font "--sdf" "yes"]
                [test-font "--sdf-type" "yes"]
                [test-font "--metrics" "yes"]
                [test-font "--summary" "yes"]
                [test-font "--warmup-ms" "-1"]]]
    (is (map? (error-data #(demo/parse-args args))) (pr-str args))))

(deftest load-profile-updates-the-existing-ratom-and-rejects-invalid-changes
  (let [original @demo/state
        state-object demo/state]
    (try
      (is (= {:profile :text :intensity 2 :sdf false}
             (demo/load-profile! :text {:intensity 2 :sdf false})))
      (is (= {:profile :text :intensity 2 :sdf false}
             (select-keys @demo/state [:profile :intensity :sdf])))
      (is (identical? state-object demo/state))
      (is (= :unknown-profile
             (:reason (error-data #(demo/load-profile! :unknown {})))))
      (is (= :invalid-intensity
             (:reason (error-data #(demo/load-profile! :text {:intensity false})))))
      (is (= {:profile :text :intensity 2 :sdf false :sdf-type :sdf}
             (demo/load-profile! :text {:intensity 2 :sdf false :sdf-type :sdf})))
      (is (= :invalid-sdf-type
             (:reason (error-data #(demo/load-profile! :text {:sdf-type false})))))
      (is (= {:profile :text :intensity 2 :sdf false :sdf-type :sdf}
             (select-keys @demo/state [:profile :intensity :sdf :sdf-type])))
      (finally
        (reset! demo/state original)))))

(deftest start-rejects-false-intensity-before-starting-services
  (let [original @demo/state
        starts (atom 0)]
    (try
      (with-redefs [jolt-nrepl/start
                    (fn [& _]
                      (swap! starts inc)
                      (with-meta (fn [] nil) {:port 7888 :port-file nil}))
                    ui/run (fn [& _] nil)]
        (is (= :invalid-intensity
               (:reason (error-data #(demo/start! {:font-path test-font
                                                   :intensity false}))))))
      (is (zero? @starts))
      (finally
        (reset! demo/state original)))))

(deftest start-rejects-false-profile-and-warmup-before-starting-services
  (let [original @demo/state
        starts (atom 0)]
    (try
      (with-redefs [jolt-nrepl/start
                    (fn [& _]
                      (swap! starts inc)
                      (with-meta (fn [] nil) {:port 7888 :port-file nil}))
                    ui/run (fn [& _] nil)]
        (is (= :unknown-profile
               (:reason (error-data #(demo/start! {:font-path test-font
                                                   :profile false})))))
        (is (= :warmup-ms
               (:option (error-data #(demo/start! {:font-path test-font
                                                   :warmup-ms false}))))))
      (is (zero? @starts))
      (finally
        (reset! demo/state original)))))

(deftest default-showcase-start-does-not-sample-or-append-metrics
  (let [original @demo/state
        scenes (atom [])
        samples (atom 0)]
    (try
      (with-redefs [jolt-nrepl/start
                    (fn [& _] (with-meta (fn [] nil) {:port 7888 :port-file nil}))
                    sg/current-context (constantly :native-context)
                    renderer/input-snapshot (fn [_] {})
                    renderer/cursor->bounds (fn [_ _] [0.75 0.5])
                    renderer/frame-state (fn [_] (swap! samples inc) {})
                    ui/run
                    (fn [root-fn & {:as opts}]
                      (swap! scenes conj (root-fn))
                      ((:after-render opts))
                      (swap! scenes conj (root-fn)))]
        (demo/start! {:font-path test-font}))
      (is (= 2 (count @scenes)))
      (is (every? #(empty? (elements-by-id (scene-elements %) :hud-metrics)) @scenes))
      (is (zero? @samples))
      (is (nil? (:metrics-text @demo/state)))
      (is (false? (:metrics @demo/state)))
      (finally
        (reset! demo/state original)))))

(defn- draw-tree [scene]
  ((var-get #'sg/draw-tree) scene))

(defn- command-counts [commands]
  {:draw-commands (count (filter :handle commands))
   :geometry-commands (count (filter :handle commands))
   :group-commands (+ (count (filter #(contains? % :begin-group) commands))
                      (count (filter #(contains? % :end-group) commands)))})

(defn- walk-widgets [widget]
  (tree-seq (fn [node] (seq (:children @node)))
            (fn [node] (:children @node))
            widget))

(defn- widgets-by-id [scene]
  (into {} (keep (fn [widget]
                   (when-let [id (:id (:props @widget))]
                     [id widget])))
        (walk-widgets scene)))

(deftest native-smoke-renders-every-profile-in-one-context-without-resource-churn
  (let [context (renderer/create-context
                  {:title "slipstream profile smoke" :width 96 :height 60
                   :bounds bounds :visible false})
        scene-state (r/atom (profile-state :showcase))
        scene (b/create! :scene {})]
    (binding [sg/*context* context]
      (let [root (ui/mount scene :scene [(fn [] (profiles/profile-scene @scene-state))])]
        (try
          (doseq [profile [:showcase :geometry :text :compositor :shader :all]]
            (testing (name profile)
              (reset! scene-state (profile-state profile))
              (let [before (System/nanoTime)
                    commands (draw-tree scene)
                    _ (renderer/render! context commands)
                    frame (renderer/capture! context)
                    elapsed-ms (/ (- (System/nanoTime) before) 1000000.0)
                    initial-state (renderer/frame-state context)]
                (is (= [192 120] [(:width frame) (:height frame)]))
                (is (some (complement zero?) (:pixels frame)))
                (is (pos? (:draw-commands (command-counts commands))))
                (is (pos? (:live-resources initial-state)))
                (is (pos? (:shader-compilations initial-state)))
                (doseq [[id widget] (widgets-by-id scene)]
                  (when-let [resource (:resource @widget)]
                    (is (false? @(:released? resource)) (str "stale handle: " id))
                    (is (pos? (renderer/native-handle resource)))))
                (swap! scene-state update :time + 0.01)
                (let [next-commands (draw-tree scene)]
                  (renderer/render! context next-commands)
                  (is (= (select-keys initial-state
                                      [:shader-compilations :resource-uploads
                                       :paint-texture-uploads])
                         (select-keys (renderer/frame-state context)
                                      [:shader-compilations :resource-uploads
                                       :paint-texture-uploads]))
                      "draw-only updates must reuse native caches"))
                (println (pr-str
                           {:profile profile
                            :frame-ms elapsed-ms
                            :counts (command-counts commands)
                            :counters (select-keys initial-state
                                                   [:shader-compilations :resource-uploads
                                                    :paint-texture-uploads
                                                    :live-resources :live-effects])})))
              (when (= :geometry profile)
                (let [widgets (widgets-by-id scene)]
                  (doseq [i (range 4)]
                    (is (= (renderer/native-handle
                             (:resource @(get widgets (keyword "geometry-unique" (str i)))))
                           (renderer/native-handle
                             (:resource @(get widgets (keyword "geometry-shared" (str i))))))
                        "shared geometry must reuse one native handle"))))))
          (ui/unmount! root scene :scene)
          (resources/dispose-tree! scene)
          (is (zero? (:live-resources (renderer/frame-state context))))
          (finally
            (when-not (:disposed? @scene)
              (ui/unmount! root scene :scene)
              (resources/dispose-tree! scene))
            (renderer/destroy! context)))))))
