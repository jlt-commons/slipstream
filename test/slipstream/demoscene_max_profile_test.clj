(ns slipstream.demoscene-max-profile-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [slipstream.example.demoscene-profiles :as profiles]
            [slipstream.example.demoscene-shader :as visuals]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for demoscene profile tests" {}))))

(def ^:private bounds [0 1.6 0 1])

(defn- profile-state [profile]
  (assoc visuals/initial-state
         :profile profile
         :intensity 16
         :sdf true
         :sdf-type :sdf
         :font test-font
         :bounds bounds
         :aspect 1.6
         :time 0.75
         :nrepl-port 45678))

(defn- draw-tree [scene]
  ((var-get #'sg/draw-tree) scene))

(defn- walk-widgets [widget]
  (tree-seq (fn [node] (seq (:children @node)))
            (fn [node] (:children @node))
            widget))

(defn- widgets-by-id [scene]
  (into {} (keep (fn [widget]
                   (when-let [id (:id (:props @widget))]
                     [id widget])))
        (walk-widgets scene)))

(deftest native-max-intensity-sdf-smoke-releases-geometry-and-all-resources
  (let [context (renderer/create-context
                  {:title "slipstream max intensity smoke" :width 96 :height 60
                   :bounds bounds :visible false})
        scene-state (r/atom (profile-state :showcase))
        scene (b/create! :scene {})]
    (binding [sg/*context* context]
      (let [root (ui/mount scene :scene [(fn [] (profiles/profile-scene @scene-state))])]
        (try
          (doseq [profile [:geometry :all]]
            (testing (name profile)
              (reset! scene-state (profile-state profile))
              (let [commands (draw-tree scene)]
                (renderer/render! context commands)
                (let [frame (renderer/capture! context)
                      native-state (renderer/frame-state context)]
                  (is (= [192 120] [(:width frame) (:height frame)]))
                  (is (some (complement zero?) (:pixels frame)))
                  (is (= (count commands) (:draw-commands native-state)))
                  (is (pos? (:geometry-commands native-state)))
                  (is (= (if (= :all profile) 128 0)
                         (:group-commands native-state)))
                  (is (pos? (:live-resources native-state)))
                  (doseq [[id widget] (widgets-by-id scene)]
                    (when-let [resource (:resource @widget)]
                      (is (false? @(:released? resource)) (str "stale handle: " id))
                      (is (pos? (renderer/native-handle resource)))))
                  (println (pr-str
                             {:profile profile :intensity 16 :sdf true :sdf-type :sdf
                              :counts (select-keys native-state
                                                   [:draw-commands :geometry-commands
                                                    :group-commands])
                              :counters (select-keys native-state
                                                     [:shader-compilations :resource-uploads
                                                      :paint-texture-uploads
                                                      :live-resources :live-effects])}))))))
          (ui/unmount! root scene :scene)
          (resources/dispose-tree! scene)
          (is (zero? (:live-resources (renderer/frame-state context))))
          (finally
            (when-not (:disposed? @scene)
              (ui/unmount! root scene :scene)
              (resources/dispose-tree! scene))
            (renderer/destroy! context)))))))

(deftest native-small-msdf-smoke-releases-resources
  (let [context (renderer/create-context
                  {:title "slipstream small MSDF smoke" :width 96 :height 60
                   :bounds bounds :visible false})
        scene-state (r/atom (assoc (profile-state :geometry)
                                   :intensity 1 :sdf-type :msdf))
        scene (b/create! :scene {})]
    (binding [sg/*context* context]
      (let [root (ui/mount scene :scene [(fn [] (profiles/profile-scene @scene-state))])]
        (try
          (let [commands (draw-tree scene)]
            (renderer/render! context commands)
            (let [frame (renderer/capture! context)
                  native-state (renderer/frame-state context)]
              (is (= [192 120] [(:width frame) (:height frame)]))
              (is (some (complement zero?) (:pixels frame)))
              (is (= (count commands) (:draw-commands native-state)))
              (is (pos? (:geometry-commands native-state)))
              (is (pos? (:live-resources native-state)))
              (doseq [[id widget] (widgets-by-id scene)]
                (when-let [resource (:resource @widget)]
                  (is (false? @(:released? resource)) (str "stale handle: " id))
                  (is (pos? (renderer/native-handle resource)))))
              (println (pr-str
                         {:profile :geometry :intensity 1 :sdf true :sdf-type :msdf
                          :counts (select-keys native-state
                                               [:draw-commands :geometry-commands
                                                :group-commands])
                          :counters (select-keys native-state
                                                 [:shader-compilations :resource-uploads
                                                  :paint-texture-uploads
                                                  :live-resources :live-effects])}))))
          (ui/unmount! root scene :scene)
          (resources/dispose-tree! scene)
          (is (zero? (:live-resources (renderer/frame-state context))))
          (finally
            (when-not (:disposed? @scene)
              (ui/unmount! root scene :scene)
              (resources/dispose-tree! scene))
            (renderer/destroy! context)))))))
