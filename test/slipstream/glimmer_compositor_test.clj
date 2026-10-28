(ns slipstream.glimmer-compositor-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private tolerance 5)
(def ^:private background [38 38 38 255])
(def ^:private destination-color [0.25 0.5 0.75 1.0])
(def ^:private source-color [0.75 0.5 0.25 0.5])
(def ^:private porter-duff-modes
  [:src-over :src :dst :src-in :dst-in :src-out :dst-out
   :src-atop :dst-atop :xor :clear :dst-over])
(def ^:private photoshop-modes
  [:multiply :screen :overlay :darken :lighten :color-dodge :color-burn
   :hard-light :soft-light :difference :exclusion])

(defn- upload
  ([context color] (upload context color {}))
  ([context color options]
   (let [cpu (compiler/compile-shape
               (merge {:path [[:rect 0 0 1 1]] :fill color :texture-width 128}
                      options))
         handle (renderer/upload! context cpu)]
     {:cpu cpu :handle handle :paint (renderer/paint-texture! context handle)
      :color color})))

(defn- release [context resource]
  (renderer/release! context (:handle resource))
  (resources/release! (:cpu resource)))

(defn- draw
  ([resource] (draw resource :src-over))
  ([resource blend-mode]
   {:handle (:handle resource)
    :paint (:paint resource)
    :color (:color resource)
    :blend-mode blend-mode}))

(defn- capture [context commands]
  (renderer/render! context commands)
  (renderer/capture! context))

(defn- pixel [frame [x y]]
  (let [x (min (dec (:width frame)) (* x (/ (:width frame) 64)))
        y (min (dec (:height frame)) (* y (/ (:height frame) 64)))
        offset (* 4 (+ (* y (:width frame)) x))]
    (mapv #(bit-and 0xff (nth (:pixels frame) (+ offset %))) (range 4))))

(defn- check-pixel [frame position expected]
  (let [actual (pixel frame position)]
    (is (every? true? (map #(<= (Math/abs (- (int %1) (int %2))) tolerance)
                           actual expected))
        (str "pixel " position " expected " expected " but got " actual))))

(defn- bytes [color]
  (mapv #(long (Math/round (* 255.0 (double %)))) color))

(defn- factors [mode sa da]
  (case mode
    :src-over [1.0 (- 1.0 sa)]
    :src [1.0 0.0]
    :dst [0.0 1.0]
    :src-in [da 0.0]
    :dst-in [0.0 sa]
    :src-out [(- 1.0 da) 0.0]
    :dst-out [0.0 (- 1.0 sa)]
    :src-atop [da (- 1.0 sa)]
    :dst-atop [(- 1.0 da) sa]
    :xor [(- 1.0 da) (- 1.0 sa)]
    :clear [0.0 0.0]
    :dst-over [(- 1.0 da) 1.0]))

(defn- photoshop-channel [mode cb cs]
  (case mode
    :multiply (* cb cs)
    :screen (+ cb cs (- (* cb cs)))
    :overlay (if (<= cb 0.5)
               (* 2.0 cb cs)
               (- 1.0 (* 2.0 (- 1.0 cb) (- 1.0 cs))))
    :darken (min cb cs)
    :lighten (max cb cs)
    :color-dodge (if (>= cs 1.0) 1.0 (min 1.0 (/ cb (- 1.0 cs))))
    :color-burn (if (<= cs 0.0) 0.0 (- 1.0 (min 1.0 (/ (- 1.0 cb) cs))))
    :hard-light (if (<= cs 0.5)
                  (* 2.0 cb cs)
                  (- 1.0 (* 2.0 (- 1.0 cb) (- 1.0 cs))))
    :soft-light (if (<= cs 0.5)
                  (- cb (* (- 1.0 (* 2.0 cs)) cb (- 1.0 cb)))
                  (let [d (if (<= cb 0.25)
                            (* (+ (* (- (* 16.0 cb) 12.0) cb) 4.0) cb)
                            (Math/sqrt cb))]
                    (+ cb (* (- (* 2.0 cs) 1.0) (- d cb)))))
    :difference (Math/abs (- cb cs))
    :exclusion (+ cb cs (* -2.0 cb cs))))

(defn- expected-blend [mode]
  (let [sa (nth source-color 3)
        da (nth destination-color 3)
        source (mapv #(* % sa) (take 3 source-color))
        destination (mapv #(* % da) (take 3 destination-color))]
    (if (some #{mode} porter-duff-modes)
      (let [[fs fd] (factors mode sa da)]
        (bytes (conj (mapv #(+ (* fs %1) (* fd %2)) source destination)
                     (+ (* fs sa) (* fd da)))))
      (let [ blended (mapv #(photoshop-channel mode %1 %2)
                           (take 3 destination-color) (take 3 source-color))
            rgb (mapv (fn [s d b]
                        (+ (* (- 1.0 da) s) (* (- 1.0 sa) d) (* sa da b)))
                      source destination blended)]
        (bytes (conj rgb (+ sa da (- (* sa da)))))))))

(defmacro ^:private with-context [[context] & body]
  `(let [~context (renderer/create-context
                    {:title "slipstream compositor goldens" :width 64 :height 64
                     :bounds [0 1 0 1] :visible false})]
     (try
       ~@body
       (finally
         (renderer/destroy! ~context)))))

(deftest porter-duff-and-photoshop-golden-frames
  (with-context [context]
    (let [destination (upload context destination-color)
          source (upload context source-color)]
      (try
        (doseq [mode (concat porter-duff-modes photoshop-modes)]
          (testing (str mode)
            (let [frame (capture context [(draw destination :src) (draw source mode)])]
              (check-pixel frame [32 32] (expected-blend mode)))))
        (finally
          (release context destination)
          (release context source))))))

(deftest coverage-correct-antialiased-multiply-edge
  (with-context [context]
    (let [destination (upload context [0.25 0.5 0.75 1.0])
          source (upload context [0.5 0.5 0.5 1.0]
                         {:path [[:rect 0 0 0.5 1]]})]
      (try
        (doseq [window-size [64 80]]
          (when-not (= 64 window-size)
            (is (zero? (f/gl-test-resize (:handle context) window-size window-size))))
          (let [width (:width (capture context []))
                offset (/ 0.5 width)
                edge-source (assoc (draw source :src) :transform [offset 0 0])]
            (check-pixel (capture context [edge-source]) [32 32] [64 64 64 128])
            (let [frame (capture context [(draw destination :src)
                                          (assoc (draw source :multiply)
                                                 :transform [offset 0 0])])]
              (check-pixel frame [32 32] [48 96 144 255]))))
        (finally
          (release context destination)
          (release context source))))))

(deftest draw-modes-and-per-draw-opacity
  (with-context [context]
    (let [destination (upload context [0 0 1 1])
          source (upload context [1 0 0 0.5])]
      (try
        (doseq [mode [:hidden :geometry :mask]]
          (testing (str mode " emits no visible draw")
            (check-pixel (capture context [(draw destination :src)
                                           (assoc (draw source) :draw-mode mode)])
                         [32 32] [0 0 255 255])))
        (check-pixel (capture context [(draw destination :src)
                                       (assoc (draw source) :draw-mode :visible)])
                     [32 32] [128 0 128 255])
        (finally
          (release context destination)
          (release context source))))))

(def ^:private procedural-masks
  [{:type :circle :params [0.5 0.5 0.25] :inside [32 32] :outside [8 32]}
   {:type :rect :params [0.25 0.25 0.5 0.5] :inside [32 32] :outside [8 32]}
   {:type :capsule :params [0.25 0.5 0.75 0.5 0.125] :inside [32 32] :outside [4 32]}
   {:type :arc :params [0.5 0.5 0.4 0 (/ Math/PI 2.0)] :inside [28 48] :outside [16 16]}
   {:type :arc-band :params [0.5 0.5 0.25 0 Math/PI 0.05] :inside [48 32] :outside [32 32]}
   {:type :hexagon :params [0.5 0.5 0.3 0] :inside [32 32] :outside [8 8]}
   {:type :octagon :params [0.5 0.5 0.3 0] :inside [32 32] :outside [8 8]}
   {:type :star :params [0.5 0.5 0.35 5 0.4 0] :inside [32 32] :outside [8 8]}])

(deftest procedural-mask-inversion-and-whole-group-goldens
  (with-context [context]
    (let [source (upload context [1 0 0 1])]
      (try
        (doseq [{:keys [inside outside] :as mask} procedural-masks]
          (testing (str (:type mask))
            (let [commands [{:begin-group mask} (draw source) {:end-group :src-over}]
                  frame (capture context commands)]
              (check-pixel frame inside [255 0 0 255])
              (check-pixel frame outside background))
            (let [frame (capture context [{:begin-group (assoc mask :invert true)}
                                          (draw source) {:end-group :src-over}])]
              (check-pixel frame inside background)
              (check-pixel frame outside [255 0 0 255]))))
        (finally
          (release context source))))))

(deftest procedural-mask-antialiased-edge-golden
  (with-context [context]
    (let [source (upload context [1 0 0 1])]
      (try
        (doseq [window-size [64 80]]
          (when-not (= 64 window-size)
            (is (zero? (f/gl-test-resize (:handle context) window-size window-size))))
          (let [width (:width (capture context []))
                frame (capture context
                        [{:begin-group {:type :rect
                                        :params [0 0 (+ 0.5 (/ 0.5 width)) 1]}}
                         (draw source) {:end-group :src-over}])]
            (check-pixel frame [32 32] [147 19 19 255])))
        (finally
          (release context source))))))

(deftest sdf-tile-mask-and-inversion-goldens
  (with-context [context]
    (let [source (upload context [0 1 0 1])
          sdf (upload context [1 1 1 1]
                      {:path [[:rect 0 0 0.5 1]] :sdf true :sdf-range 0.1})]
      (try
        (let [commands [{:begin-group {:type :sdf-tile :params [0 0 1]}
                         :mask-handle (:handle sdf)}
                        (draw source) {:end-group :src-over}]
              frame (capture context commands)]
          (check-pixel frame [16 32] [0 255 0 255])
          (check-pixel frame [48 32] background))
        (let [frame (capture context [{:begin-group {:type :sdf-tile}
                                       :mask-handle (:handle sdf)}
                                      (draw source) {:end-group :src-over}])]
          (check-pixel frame [16 32] [0 255 0 255])
          (check-pixel frame [48 32] background))
        (let [frame (capture context [{:begin-group {:type :sdf-tile :params [0 0 1]
                                                     :invert true}
                                       :mask-handle (:handle sdf)}
                                      (draw source) {:end-group :src-over}])]
          (check-pixel frame [16 32] background)
          (check-pixel frame [48 32] [0 255 0 255]))
        (finally
          (release context source)
          (release context sdf))))))

(deftest nested-groups-opacity-and-empty-groups-are-isolated
  (with-context [context]
    (let [destination (upload context [0 0 1 1])
          source (upload context [1 0 0 1])]
      (try
        (let [frame (capture context
                      [(draw destination :src)
                       {:begin-group {:type :circle :params [0.5 0.5 0.4]}
                        :color [1 1 1 0.5]}
                       {:begin-group {:type :rect :params [0.25 0.25 0.5 0.5]}}
                       (draw source)
                       {:end-group :src-over}
                       {:end-group :src-over}])]
          (check-pixel frame [32 32] [128 0 128 255])
          (check-pixel frame [8 32] [0 0 255 255]))
        (let [before (capture context [(draw destination :src)])
              after (capture context [(draw destination :src)
                                      {:begin-group nil}
                                      {:begin-group nil}
                                      {:end-group :src-over}
                                      {:end-group :src-over}])]
          (is (= (vec (:pixels before)) (vec (:pixels after)))))
        (finally
          (release context destination)
          (release context source))))))

(defn- resize! [context width height]
  (when-not (zero? (f/gl-test-resize (:handle context) width height))
    (throw (ex-info "resize failed" {:error (f/last-error)}))))

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "frame state failed" {:error (f/last-error)})))
    {:framebuffer-binding (ffi/read-field out f/gl-frame-state-layout :framebuffer-binding)
     :viewport (mapv #(ffi/read-field out f/gl-frame-state-layout [:viewport %]) (range 4))
     :framebuffer-size (mapv #(ffi/read-field out f/gl-frame-state-layout [:framebuffer-size %])
                             (range 2))
     :shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)}))

(defn- bind-nondefault! [context viewport]
  (let [[x y width height] viewport]
    (when-not (zero? (f/gl-test-bind-nondefault (:handle context) x y width height))
      (throw (ex-info "bind nondefault failed" {:error (f/last-error)})))))

(defn- restore-default! [context]
  (when-not (zero? (f/gl-test-restore-default (:handle context)))
    (throw (ex-info "restore default failed" {:error (f/last-error)}))))

(defn- fail-next! [context point]
  (when-not (zero? (f/gl-test-fail-next (:handle context) point))
    (throw (ex-info "failure injection failed" {:error (f/last-error)}))))

(defn- restoration-state [context]
  (select-keys (frame-state context) [:framebuffer-binding :viewport]))

(deftest group-render-restores-nondefault-fbo-and-viewport
  (with-context [context]
    (let [source (upload context [1 0 0 1])
          commands [{:begin-group {:type :rect :params [0 0 1 1]}}
                    (draw source) {:end-group :src-over}]]
      (try
        (bind-nondefault! context [3 5 17 19])
        (let [before (restoration-state context)]
          (is (pos? (:framebuffer-binding before)))
          (is (= [3 5 17 19] (:viewport before)))
          (renderer/render! context commands)
          (is (= before (restoration-state context))))
        (restore-default! context)
        (check-pixel (renderer/capture! context) [32 32] [255 0 0 255])
        (finally
          (restore-default! context)
          (release context source))))))

(deftest active-draw-exception-restores-nondefault-fbo-and-viewport
  (with-context [context]
    (let [source (upload context [1 0 0 1])]
      (try
        (bind-nondefault! context [2 4 13 15])
        (let [before (restoration-state context)]
          (fail-next! context 2)
          (is (thrown-with-msg? Exception #"injected compositor draw"
                (renderer/render! context [(draw source)])))
          (is (= before (restoration-state context)))
          (renderer/render! context [(draw source :src)])
          (is (= before (restoration-state context))))
        (restore-default! context)
        (check-pixel (renderer/capture! context) [32 32] [255 0 0 255])
        (finally
          (restore-default! context)
          (release context source))))))

(deftest failed-initialization-restores-state-and-same-size-retry-reallocates
  (with-context [context]
    (let [source (upload context [1 0 0 1])
          commands [{:begin-group {:type :rect :params [0 0 1 1]}}
                    (draw source) {:end-group :src-over}]]
      (try
        (bind-nondefault! context [1 3 11 14])
        (let [before (restoration-state context)]
          (fail-next! context 1)
          (is (thrown-with-msg? Exception #"injected compositor resize"
                (renderer/render! context commands)))
          (is (= before (restoration-state context)))
          (let [retry-error (try
                              (renderer/render! context commands)
                              nil
                              (catch Throwable error error))]
            (is (nil? retry-error) (str "same-size retry failed: " retry-error))
            (when-not retry-error
              (is (= before (restoration-state context)))
              (restore-default! context)
              (check-pixel (renderer/capture! context) [32 32] [255 0 0 255]))))
        (finally
          (restore-default! context)
          (release context source))))))

(deftest framebuffer-resize-reuses-resources-and-restores-default-fbo
  (with-context [context]
    (let [source (upload context [1 0 0 1])
          commands [{:begin-group {:type :circle :params [0.5 0.5 0.3]}}
                    (draw source) {:end-group :src-over}]]
      (try
        (capture context commands)
        (let [before (frame-state context)]
          (is (= 6 (:shader-compilations before)))
          (is (zero? (:framebuffer-binding before)))
          (resize! context 96 80)
          (let [frame (capture context commands)
                after (frame-state context)]
            (is (= (:framebuffer-size after) [(:width frame) (:height frame)]))
            (is (not= [64 64] (:framebuffer-size after)))
            (check-pixel frame [32 32] [255 0 0 255])
            (check-pixel frame [4 32] background)
            (is (zero? (:framebuffer-binding after)))
            (is (= (:resource-uploads before) (:resource-uploads after)))
            (is (= (:shader-compilations before) (:shader-compilations after)))))
        (finally
          (release context source))))))

(deftest invalid-group-structure-restores-the-next-frame
  (with-context [context]
    (let [source (upload context [1 0 0 1])]
      (try
        (is (thrown-with-msg? Exception #"group"
              (renderer/render! context [{:end-group :src-over}])))
        (check-pixel (capture context [(draw source :src)]) [32 32] [255 0 0 255])
        (is (zero? (:framebuffer-binding (frame-state context))))
        (finally
          (release context source))))))

(deftest two-contexts-restore-fbo-and-remain-independent
  (let [a (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})
        b (renderer/create-context {:width 64 :height 64 :bounds [0 1 0 1] :visible false})]
    (try
      (let [red-a (upload a [1 0 0 1])
            green-a (upload a [0 1 0 1])
            blue-b (upload b [0 0 1 1])]
        (try
          (check-pixel (capture a [{:begin-group {:type :rect :params [0 0 0.5 1]}}
                                   (draw red-a) {:end-group :src-over}])
                       [16 32] [255 0 0 255])
          (check-pixel (capture b [(draw blue-b :src)]) [32 32] [0 0 255 255])
          (check-pixel (capture a [(draw green-a :src)]) [32 32] [0 255 0 255])
          (is (zero? (:framebuffer-binding (frame-state a))))
          (is (zero? (:framebuffer-binding (frame-state b))))
          (renderer/destroy! a)
          (check-pixel (capture b [(draw blue-b :src)]) [32 32] [0 0 255 255])
          (finally
            (when-not @(:destroyed? a)
              (release a red-a)
              (release a green-a))
            (release b blue-b))))
      (finally
        (renderer/destroy! a)
        (renderer/destroy! b)))))
