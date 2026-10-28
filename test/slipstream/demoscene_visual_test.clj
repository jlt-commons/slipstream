(ns slipstream.demoscene-visual-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.backend :as b]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.ffi :as ffi]
            [slipstream.example.demoscene-shader :as visuals]
            [slipstream.ffi :as f]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources])
  (:import [java.nio ByteBuffer]
           [java.nio.file Files OpenOption]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for demoscene visual tests" {}))))

(def ^:private bounds [0 1.6 0 1])
(def ^:private capture-directory (System/getenv "SLIPSTREAM_DEMO_CAPTURE_DIR"))

(def ^:private golden-hashes
  {:waves 763709567
   :voronoi -1010006093
   :midpoint 583103409
   :mouse-warp -1837935436
   :vectors-early -1575908726
   :vectors-late 952409621})

(defn- base-state []
  (assoc visuals/initial-state
         :time 0.75
         :transition 0.0
         :transition-target 0.0
         :mouse [0.5 0.5]
         :density 1.0
         :speed 1.0
         :aspect 1.6
         :font test-font
         :vectors {}))

(def golden-states
  {:waves (base-state)
   :voronoi (assoc (base-state) :transition 1.0 :transition-target 1.0)
   :midpoint (assoc (base-state) :transition 0.5 :transition-target 0.5)
   :mouse-warp (assoc (base-state) :mouse [0.82 0.3])
   :vectors-early (assoc (base-state) :time 0.0 :transition 0.2)
   :vectors-late (assoc (base-state) :time 4.0 :transition 0.8
                         :mouse [0.68 0.62] :burst 0.7)})

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "frame state failed" {:error (f/last-error)})))
    {:shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)}))

(defn- draw-tree [scene]
  ((var-get #'sg/draw-tree) scene))

(defn- frame-digest [frame]
  (hash (vec (:pixels frame))))

(defn- nio-path [path]
  (java.nio.file.Paths/get (str path) (make-array String 0)))

(defn- write-capture! [label frame]
  (when capture-directory
    (Files/createDirectories (nio-path capture-directory)
                             (make-array java.nio.file.attribute.FileAttribute 0))
    (let [{:keys [width height pixels]} frame
          rgb (byte-array (* width height 3))]
      (doseq [i (range (* width height))]
        (doseq [channel (range 3)]
          (aset rgb (+ (* i 3) channel) (aget pixels (+ (* i 4) channel)))))
      (let [header (.getBytes (format "P6\n%d %d\n255\n" width height) "UTF-8")
            output (ByteBuffer/allocate (+ (alength header) (alength rgb)))]
        (.put output header)
        (.put output rgb)
        (Files/write (nio-path (str capture-directory "/" (name label) ".ppm"))
                     (.array output) (make-array OpenOption 0))))))

(defmacro ^:private with-context [[context] & body]
  `(let [~context (renderer/create-context
                    {:title "slipstream demoscene goldens" :width 160 :height 100
                     :bounds bounds :visible false})]
     (try
       (binding [sg/*context* ~context]
         ~@body)
       (finally
         (renderer/destroy! ~context)))))

(defn- mount! [scene view]
  (let [root (ui/mount scene :scene view)]
    [root scene]))

(defn- unmount! [[root scene]]
  (ui/unmount! root scene :scene)
  (resources/dispose-tree! scene))

(defn- capture-mounted! [context scene]
  (renderer/render! context (draw-tree scene))
  (renderer/capture! context))

(defn- capture-state! [context state]
  (let [scene (b/create! :scene {})
        mounted (mount! scene (visuals/scene-for-state state))]
    (try
      (capture-mounted! context scene)
      (finally
        (unmount! mounted)))))

(defn- walk-widgets [widget]
  (tree-seq (fn [node] (seq (:children @node)))
            (fn [node] (:children @node))
            widget))

(defn- widgets-by-id [scene]
  (into {} (keep (fn [widget]
                   (when-let [id (:id (:props @widget))]
                     [id widget])))
        (walk-widgets scene)))

(deftest deterministic-hybrid-stages-match-exact-golden-frames
  (with-context [context]
    (let [frames (into {}
                       (map (fn [[label state]]
                              [label (capture-state! context state)]))
                       golden-states)]
      (doseq [[label frame] frames]
        (testing (name label)
          (write-capture! label frame)
          (is (= [320 200] [(:width frame) (:height frame)]))
          (is (= (get golden-hashes label) (frame-digest frame))
              (str "golden mismatch for " label))))
      (is (= (count frames) (count (into #{} (map frame-digest) (vals frames))))
          "every stage and control state must be visually distinct"))))

(deftest constellation-has-independent-rich-shapes-and-a-static-geneva-hud
  (let [scene (visuals/scene-for-state (base-state))
        shapes (atom [])
        texts (atom [])]
    (letfn [(visit [node]
              (when (vector? node)
                (let [[tag props] node]
                  (when (= :shape tag) (swap! shapes conj props))
                  (when (= :text tag) (swap! texts conj props))
                  (doseq [child (drop 2 node)] (visit child)))))]
      (visit scene))
    (let [vectors (filter #(= "vector" (namespace (:id %))) @shapes)]
      (is (= 12 (count vectors)))
      (is (= 12 (count (into #{} (map :id) vectors))))
      (is (some #(map? (:fill %)) vectors))
      (is (some #(= :pattern (get-in % [:fill :type])) vectors))
      (is (some #(:sdf %) vectors)))
    (is (seq @texts))
    (is (every? #(= test-font (:font %)) @texts))
    (is (every? #(not (re-find #"\d" (:text %))) @texts)
        "HUD copy stays static so numeric uniforms cannot churn glyph atlases")))

(deftest controls-are-deterministic-edge-triggered-and-clamped
  (let [initial (base-state)
        changed (visuals/update-state initial 0.25
                  {:mouse [0.8 0.25] :scroll-y 2.0 :left true :space true})]
    (is (= 1.0 (:time changed)))
    (is (= [0.8 0.25] (:mouse changed)))
    (is (= 1.2 (:density changed)))
    (is (= 1.0 (:transition-target changed)))
    (is (< 0.0 (:transition changed) 1.0))
    (is (pos? (:burst changed)))
    (is (= 1.0 (:transition-target
                 (visuals/update-state changed 0.1 {:space true})))
        "held space must not repeatedly toggle")
    (is (= 0.25 (:density (visuals/update-state initial 0.1 {:scroll-y -100.0}))))
    (is (= 4.0 (:density (visuals/update-state initial 0.1 {:scroll-y 100.0}))))
    (let [reset (visuals/update-state changed 0.1 {:r true})]
      (is (= (select-keys visuals/initial-state
                          [:time :transition :transition-target :mouse
                           :density :speed :burst :vectors])
             (select-keys reset [:time :transition :transition-target :mouse
                                 :density :speed :burst :vectors])))
      (is (= (select-keys changed [:aspect :font])
             (select-keys reset [:aspect :font]))))))

(deftest orbit-color-and-uniform-updates-do-not-churn-native-resources
  (with-context [context]
    (let [scene-state (r/atom (base-state))
          scene (b/create! :scene {})
          mounted (mount! scene [ (fn [] (visuals/scene-for-state @scene-state)) ])]
      (try
        (capture-mounted! context scene)
        (let [before (frame-state context)
              widgets-before (widgets-by-id scene)]
          (swap! scene-state assoc :time 2.5 :transition 0.65 :transition-target 1.0
                 :mouse [0.77 0.21] :density 2.25 :speed 1.4 :burst 0.8)
          (capture-mounted! context scene)
          (is (= before (frame-state context)))
          (doseq [[id widget] (widgets-by-id scene)]
            (when-let [old-widget (get widgets-before id)]
              (is (identical? old-widget widget) (str "widget replaced: " id))
              (is (identical? (:resource @old-widget) (:resource @widget))
                  (str "resource replaced: " id)))))
        (finally
          (unmount! mounted))))))

(deftest staged-path-edits-rebuild-only-the-intended-vector
  (with-context [context]
    (let [scene-state (r/atom (base-state))
          scene (b/create! :scene {})
          mounted (mount! scene [ (fn [] (visuals/scene-for-state @scene-state)) ])]
      (try
        (let [before (widgets-by-id scene)
              resources-before (into {} (map (fn [[id widget]] [id (:resource @widget)])) before)]
          (swap! scene-state assoc-in [:vectors 3 :path]
                 [[:move-to -0.6 -0.4] [:line-to 0.6 -0.4]
                  [:line-to 0.0 0.6] [:close]])
          (let [after (widgets-by-id scene)]
            (doseq [[id old-resource] resources-before]
              (if (= :vector/3 id)
                (is (not (identical? old-resource (:resource @(get after id)))))
                (is (identical? old-resource (:resource @(get after id)))
                    (str "unintended resource rebuild: " id))))))
        (finally
          (unmount! mounted))))))
