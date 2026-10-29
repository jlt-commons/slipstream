(ns slipstream.demoscene-visual-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
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
  {:waves -485840326
   :voronoi 1243398668
   :midpoint 843891352
   :mouse-warp -1703998915
   :vectors-early -1430381162
   :vectors-late 540888545
   :custom-bounds 621095928})

(def ^:private golden-signatures
  (edn/read-string (slurp "test/slipstream/demoscene_golden_signatures.edn")))

(defn- base-state []
  (assoc visuals/initial-state
         :time 0.75
         :transition 0.0
         :transition-target 0.0
         :mouse [0.8 0.5]
         :attraction [0.8 0.5]
         :density 1.0
         :speed 1.0
         :aspect 1.6
         :bounds bounds
         :nrepl-port 45678
         :font test-font
         :vectors {}))

(def golden-states
  {:waves (base-state)
   :voronoi (assoc (base-state) :transition 1.0 :transition-target 1.0)
   :midpoint (assoc (base-state) :transition 0.5 :transition-target 0.5)
   :mouse-warp (assoc (base-state) :mouse [1.312 0.3] :attraction [1.312 0.3])
   :vectors-early (assoc (base-state) :time 0.0 :transition 0.2)
   :vectors-late (assoc (base-state) :time 4.0 :transition 0.8
                         :mouse [1.088 0.62] :attraction [1.088 0.62] :burst 0.7)})

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

(defn- frame-scale [context frame]
  (let [[window-width window-height] (:window-size (renderer/frame-state context))
        scale-x (/ (:width frame) window-width)
        scale-y (/ (:height frame) window-height)]
    (is (contains? #{1 2} scale-x))
    (is (= scale-x scale-y))
    scale-x))

(defn- frame-signature [{:keys [width height pixels]}]
  (vec
    (for [grid-y (range 10)
          grid-x (range 16)]
      (let [x0 (quot (* grid-x width) 16)
            x1 (quot (* (inc grid-x) width) 16)
            y0 (quot (* grid-y height) 10)
            y1 (quot (* (inc grid-y) height) 10)
            sample-count (* (- x1 x0) (- y1 y0))
            sums (reduce (fn [channels y]
                           (reduce (fn [channels x]
                                     (let [offset (* 4 (+ x (* y width)))]
                                       (mapv (fn [sum channel]
                                               (+ sum (bit-and (aget pixels (+ offset channel)) 0xff)))
                                             channels (range 3))))
                                   channels (range x0 x1)))
                         [0 0 0] (range y0 y1))]
        (mapv #(Math/round (/ (double %) sample-count)) sums)))))

(defn- signature-matches? [expected actual]
  (and (= (count expected) (count actual))
       (every? (fn [[expected-cell actual-cell]]
                 (every? (fn [[expected-channel actual-channel]]
                           (<= (Math/abs (- expected-channel actual-channel)) 12))
                         (map vector expected-cell actual-cell)))
               (map vector expected actual))))

(defn- assert-golden-frame! [context label frame]
  (if (= 2 (frame-scale context frame))
    (is (= (get golden-hashes label) (frame-digest frame))
        (str "exact golden mismatch for " label))
    (let [actual (frame-signature frame)]
      (is (signature-matches? (get golden-signatures label) actual)
          (str "visual golden mismatch for " label "\nactual signature:\n" actual)))))

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

(deftest deterministic-hybrid-stages-match-golden-frames
  (with-context [context]
    (let [frames (into {}
                       (map (fn [[label state]]
                              [label (capture-state! context state)]))
                       golden-states)]
      (doseq [[label frame] frames]
        (testing (name label)
          (write-capture! label frame)
          (assert-golden-frame! context label frame)))
      (is (= (count frames) (count (into #{} (map frame-digest) (vals frames))))
          "every stage and control state must be visually distinct"))))

(deftest noncanonical-bounds-render-the-complete-scene
  (let [context (renderer/create-context
                  {:title "slipstream demoscene custom bounds" :width 160 :height 100
                   :bounds [-2 4 3 7] :visible false})]
    (try
      (binding [sg/*context* context]
        (let [state (assoc (base-state) :bounds [-2 4 3 7] :aspect 1.5
                           :mouse [1.0 5.0] :attraction [1.0 5.0])
              frame (capture-state! context state)]
          (write-capture! :custom-bounds frame)
          (assert-golden-frame! context :custom-bounds frame)))
      (finally
        (renderer/destroy! context)))))

(defn- scene-elements [scene]
  (let [elements (atom {:shapes [] :texts []})]
    (letfn [(visit [node]
              (when (vector? node)
                (let [[tag props] node]
                  (when (= :shape tag) (swap! elements update :shapes conj props))
                  (when (= :text tag) (swap! elements update :texts conj props))
                  (doseq [child (drop 2 node)] (visit child)))))]
      (visit scene))
    @elements))

(defn- element-by-id [elements id]
  (some #(when (= id (:id %)) %) (concat (:shapes elements) (:texts elements))))

(deftest constellation-has-independent-rich-shapes-and-a-change-driven-geneva-hud
  (let [{:keys [shapes texts] :as elements} (scene-elements
                                              (visuals/scene-for-state (base-state)))
        vectors (filter #(= "vector" (namespace (:id %))) shapes)
        status (:text (element-by-id elements :hud-status))]
    (is (= 12 (count vectors)))
    (is (= 12 (count (into #{} (map :id) vectors))))
    (is (some #(map? (:fill %)) vectors))
    (is (some #(= :pattern (get-in % [:fill :type])) vectors))
    (is (some #(:sdf %) vectors))
    (is (= 3 (count texts)))
    (is (every? #(= test-font (:font %)) texts))
    (is (re-find #"MODE WAVES" status))
    (is (re-find #"DENSITY 1.00" status))
    (is (re-find #"SPEED 1.00" status))
    (is (re-find #"NREPL 127.0.0.1:45678" status))
    (is (= status
           (:text (element-by-id
                    (scene-elements (visuals/scene-for-state
                                      (assoc (base-state) :time 99.0 :burst 0.8)))
                    :hud-status)))
        "animation-only changes must not rewrite HUD text")))

(deftest right-button-and-space-toggle-on-press-but-not-while-held
  (let [initial (base-state)
        right-down (visuals/update-state initial 0.1 {:right true})
        right-held (visuals/update-state right-down 0.1 {:right true})
        right-up (visuals/update-state right-held 0.1 {})
        right-again (visuals/update-state right-up 0.1 {:right true})
        space-down (visuals/update-state right-up 0.1 {:space true})
        space-held (visuals/update-state space-down 0.1 {:space true})
        both-down (visuals/update-state initial 0.1 {:right true :space true})]
    (is (= 1.0 (:transition-target right-down)))
    (is (= 1.0 (:transition-target right-held)) "held right must not repeatedly toggle")
    (is (= 1.0 (:transition-target right-up)))
    (is (false? (:right right-up)))
    (is (= 0.0 (:transition-target right-again)) "a second right press toggles back")
    (is (= 0.0 (:transition-target space-down)))
    (is (= 0.0 (:transition-target space-held)) "held Space must not repeatedly toggle")
    (is (= 1.0 (:transition-target both-down)) "simultaneous inputs are one logical toggle")))

(deftest left-button-boosts-through-press-hold-release-and-repeated-input
  (let [initial (base-state)
        pressed (visuals/update-state initial 0.25 {:left true})
        held (visuals/update-state pressed 0.25 {:left true})
        released (visuals/update-state held 0.25 {})
        still-released (visuals/update-state released 0.25 {})
        pressed-again (visuals/update-state still-released 0.25 {:left true})]
    (is (= 1.0 (:burst pressed)))
    (is (= 1.0 (:burst held)) "held left must sustain rather than decay energy")
    (is (= 0.65 (:burst released)))
    (is (< (Math/abs (- 0.3 (:burst still-released))) 0.000000000001))
    (is (= 1.0 (:burst pressed-again)))))

(deftest density-clamps-reset-and-time-remain-deterministic
  (let [initial (base-state)
        changed (visuals/update-state initial 0.25
                  {:mouse [0.8 0.25] :scroll-y 2.0 :left true :space true})]
    (is (= 1.0 (:time changed)))
    (is (= [0.8 0.25] (:mouse changed)))
    (is (= 1.2 (:density changed)))
    (is (= 1.0 (:transition-target changed)))
    (is (< 0.0 (:transition changed) 1.0))
    (is (= 0.25 (:density (visuals/update-state initial 0.1 {:scroll-y -100.0}))))
    (is (= 4.0 (:density (visuals/update-state initial 0.1 {:scroll-y 100.0}))))
    (let [reset (visuals/update-state changed 0.1 {:r true})]
      (is (= (select-keys visuals/initial-state
                          [:time :transition :transition-target :mouse :attraction
                           :density :speed :burst :geometry-stage :vectors])
             (select-keys reset [:time :transition :transition-target :mouse :attraction
                                 :density :speed :burst :geometry-stage :vectors])))
      (is (= (select-keys changed [:aspect :bounds :nrepl-port :font])
             (select-keys reset [:aspect :bounds :nrepl-port :font]))))))

(deftest constellation-attraction-is-dt-based-and-frame-rate-independent
  (let [initial (base-state)
        input {:mouse [1.2 0.25]}
        single (visuals/update-state initial 0.25 input)
        half-1 (visuals/update-state initial 0.125 input)
        half-2 (visuals/update-state half-1 0.125 input)]
    (is (not= (:mouse single) (:attraction single)))
    (doseq [axis (range 2)]
      (is (< (Math/abs (- (nth (:attraction single) axis)
                          (nth (:attraction half-2) axis)))
             0.000000000001)))
    (is (= (:mouse single) (:mouse half-2)))))

(deftest automatic-geometry-stages-cross-explicit-time-thresholds
  (let [initial (assoc (base-state) :time 0.0 :geometry-stage 0)
        before (visuals/update-state initial 4.49 {})
        first-stage (visuals/update-state initial 4.5 {})
        second-stage (visuals/update-state initial 7.5 {})]
    (is (= 0 (:geometry-stage before)))
    (is (= 1 (:geometry-stage first-stage)))
    (is (= 2 (:geometry-stage second-stage)))
    (let [scene-elements #(scene-elements (visuals/scene-for-state %))
          before-path (:path (element-by-id (scene-elements before) :vector/0))
          first-path (:path (element-by-id (scene-elements first-stage) :vector/0))
          second-path (:path (element-by-id (scene-elements second-stage) :vector/5))]
      (is (not= before-path first-path))
      (is (not= before-path second-path)))))

(deftest complete-bounds-drive-background-placement-and-mouse-normalization
  (let [custom-bounds [-2 4 3 7]
        state (assoc (base-state) :bounds custom-bounds :aspect 1.5
                     :mouse [1.0 5.0] :attraction [1.0 5.0])
        {:keys [shapes] :as elements} (scene-elements (visuals/scene-for-state state))
        background (element-by-id elements :hybrid-background)
        title (element-by-id elements :hud-title)]
    (is (= [[:rect 0.0 0.0 6 4]] (:path background)))
    (is (= [-2 3 0.0] (:at background)))
    (is (= [-2 4 3 7] (get-in background [:effect :uniforms :bounds :value])))
    (is (= [0.5 0.5]
           (get-in (visuals/shader-effect state) [:uniforms :mouse :value])))
    (is (= [-1.84 6.76 0.0] (:at title)))
    (is (= 0.112 (:size title)))
    (is (every? (fn [{[x y _] :at}]
                  (and (<= -2 x 4) (<= 3 y 7)))
                (filter #(= "vector" (namespace (:id %))) shapes)))))

(deftest orbit-color-and-uniform-updates-do-not-churn-native-resources
  (with-context [context]
    (let [scene-state (r/atom (base-state))
          scene (b/create! :scene {})
          mounted (mount! scene [ (fn [] (visuals/scene-for-state @scene-state)) ])]
      (try
        (capture-mounted! context scene)
        (let [before (frame-state context)
              widgets-before (widgets-by-id scene)]
          (swap! scene-state assoc :time 2.5 :transition 0.65
                 :mouse [1.232 0.21] :attraction [1.232 0.21] :burst 0.8)
          (capture-mounted! context scene)
          (is (= before (frame-state context)))
          (doseq [[id widget] (widgets-by-id scene)]
            (when-let [old-widget (get widgets-before id)]
              (is (identical? old-widget widget) (str "widget replaced: " id))
              (is (identical? (:resource @old-widget) (:resource @widget))
                  (str "resource replaced: " id)))))
        (finally
          (unmount! mounted))))))

(deftest automatic-staged-path-changes-render-and-rebuild-only-stage-members
  (with-context [context]
    (let [scene-state (r/atom (assoc (base-state) :time 4.0 :geometry-stage 0))
          scene (b/create! :scene {})
          mounted (mount! scene [ (fn [] (visuals/scene-for-state @scene-state)) ])]
      (try
        (let [before-frame (capture-mounted! context scene)
              before (widgets-by-id scene)
              resources-before (into {} (keep (fn [[id widget]]
                                                (when (:resource @widget)
                                                  [id (:resource @widget)])))
                                        before)
              staged (visuals/update-state @scene-state 0.5 {})]
          (is (= 1 (:geometry-stage staged)))
          (reset! scene-state staged)
          (let [after-frame (capture-mounted! context scene)
                after (widgets-by-id scene)
                changed (into #{}
                              (keep (fn [[id old-resource]]
                                      (when-not (identical? old-resource
                                                            (:resource @(get after id)))
                                        id)))
                              resources-before)]
            (is (not= (frame-digest before-frame) (frame-digest after-frame)))
            (is (= #{:vector/0 :vector/7} changed))))
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
