(ns slipstream.glimmer-renderer-test
  (:require [clojure.test :refer [deftest is]]
            [glimmer.backend :as b]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for reactive text tests" {}))))

(defn- shape-resource []
  (compiler/compile-shape
    {:path [[:rect 0.1 0.1 0.8 0.8]]
     :fill [1 0 0 1]
     :texture-width 128}))

(defn- fake-native []
  (let [next-context (atom 0)
        next-handle (atom 10)
        owners (atom {})
        destroyed (atom #{})
        close (atom #{})
        error (atom "")
        calls (atom [])
        fail (fn [message]
               (reset! error message)
               1)]
    {:calls calls
     :close close
     :error error
     :bindings
     {#'f/last-error (fn [] @error)
      #'f/gl-context-create
      (fn [_]
        (reset! error "")
        (let [handle (swap! next-context inc)]
          (swap! calls conj [:create handle])
          handle))
      #'f/gl-context-destroy
      (fn [context]
        (reset! error "")
        (if (contains? @destroyed context)
          (fail "context is destroyed")
          (do
            (swap! destroyed conj context)
            (swap! owners into (for [[handle owner] @owners
                                     :when (= context owner)]
                                 [handle :destroyed]))
            (swap! calls conj [:destroy context])
            0)))
      #'f/gl-poll
      (fn [context]
        (reset! error "")
        (if (contains? @destroyed context)
          (fail "context is destroyed")
          (do (swap! calls conj [:poll context]) 0)))
      #'f/gl-should-close
      (fn [context]
        (reset! error "")
        (contains? @close context))
      #'f/gl-upload
      (fn [context _ _]
        (reset! error "")
        (if (contains? @destroyed context)
          (fail "context is destroyed")
          (let [handle (swap! next-handle inc)]
            (swap! owners assoc handle context)
            (swap! calls conj [:upload context handle])
            handle)))
      #'f/gl-paint-texture
      (fn [context handle]
        (reset! error "")
        (cond
          (contains? @destroyed context) (fail "context is destroyed")
          (not= context (get @owners handle)) (fail "resource belongs to a different GL context")
          :else 0))
      #'f/gl-release
      (fn [context handle]
        (reset! error "")
        (cond
          (contains? @destroyed context) (fail "context is destroyed")
          (not= context (get @owners handle)) (fail "resource belongs to a different GL context")
          :else (do
                  (swap! owners dissoc handle)
                  (swap! calls conj [:release context handle])
                  0)))
      #'f/gl-render
      (fn [context commands count]
        (reset! error "")
        (cond
          (contains? @destroyed context) (fail "context is destroyed")
          (some (fn [i]
                  (let [handle (ffi/read-field (+ commands (* i (ffi/layout-size f/gl-draw-command-layout)))
                                               f/gl-draw-command-layout :handle)]
                    (not= context (get @owners handle))))
                (range count))
          (fail "resource belongs to a different GL context")
          :else (do (swap! calls conj [:render context count]) 0)))
      #'f/gl-swap
      (fn [context]
        (reset! error "")
        (if (contains? @destroyed context)
          (fail "context is destroyed")
          (do (swap! calls conj [:swap context]) 0)))}}))

(defmacro with-fake-native [[binding-form fake] & body]
  `(let [~binding-form ~fake]
     (with-redefs-fn (:bindings ~binding-form)
       (fn [] ~@body))))

(deftest native-renderer-entry-points-reject-null-handles-headlessly
  (is (not (zero? (f/gl-context-destroy 0))))
  (is (not (zero? (f/gl-poll 0))))
  (is (false? (f/gl-should-close 0)))
  (is (seq (f/last-error)))
  (is (zero? (f/gl-program 0 "paint")))
  (is (zero? (f/gl-upload 0 0 0)))
  (is (zero? (f/gl-paint-texture 0 0)))
  (is (not (zero? (f/gl-release 0 0))))
  (is (not (zero? (f/gl-render 0 0 0))))
  (is (not (zero? (f/gl-swap 0))))
  (is (not (zero? (f/gl-test-capture 0 0 0)))))

(deftest two-contexts-reject-cross-handles-and-tear-down-independently
  (let [cpu (shape-resource)]
    (try
      (with-fake-native [native (fake-native)]
        (let [a (renderer/create-context {:title "a"})
              b (renderer/create-context {:title "b"})]
          (try
            (let [handle-a (renderer/upload! a cpu)
                  handle-b (renderer/upload! b cpu)]
              (is (not= handle-a handle-b))
              (is (thrown-with-msg? Exception #"different GL context"
                    (renderer/release! b handle-a)))
              (is (thrown-with-msg? Exception #"different GL context"
                    (renderer/render! b [{:handle handle-a}])))
              (renderer/render! a [{:handle handle-a :color [1 0 0 1]}])
              (renderer/render! b [{:handle handle-b :color [0 1 0 1]}])
              (renderer/destroy! a)
              (is (thrown-with-msg? Exception #"destroyed"
                    (renderer/release! a handle-a)))
              (renderer/render! b [{:handle handle-b}])
              (renderer/release! b handle-b)
              (is (= [[:create 1] [:create 2]
                      [:upload 1 11] [:upload 2 12]
                      [:render 1 1] [:render 2 1] [:destroy 1]
                      [:render 2 1] [:release 2 12]]
                     @(:calls native))))
            (finally
              (renderer/destroy! b)))))
      (finally
        (resources/release! cpu)))))

(deftest gpu-cache-is-context-local-and-releases-cpu-references-exactly-once
  (let [cpu (shape-resource)]
    (try
      (with-fake-native [_native (fake-native)]
        (let [a (renderer/create-context {})
              b (renderer/create-context {})
              a1 (renderer/acquire-resource! a (shape-resource))
              a2 (renderer/acquire-resource! a (shape-resource))
              b1 (renderer/acquire-resource! b (shape-resource))]
          (is (= 4 @(:ref-count cpu)))
          (is (= (renderer/native-handle a1) (renderer/native-handle a2)))
          (is (not= (renderer/native-handle a1) (renderer/native-handle b1)))
          (resources/release! a1)
          (is (= 3 @(:ref-count cpu)))
          (resources/release! a2)
          (is (= 2 @(:ref-count cpu)))
          (resources/release! b1)
          (is (= 1 @(:ref-count cpu)))
          (renderer/destroy! a)
          (renderer/destroy! b)))
      (finally
        (resources/release! cpu)))
    (is (zero? @(:ref-count cpu)))))

(deftest failed-upload-releases-the-new-cpu-reference
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})
          cpu (shape-resource)]
      (with-redefs [renderer/upload!
                    (fn [_ _]
                      (throw (ex-info "injected upload failure" {})))]
        (is (thrown-with-msg? Exception #"injected upload failure"
              (renderer/acquire-resource! context cpu))))
      (is (zero? @(:ref-count cpu)))
      (is @(:destroyed? (:atlas cpu)))
      (renderer/destroy! context))))

(defn- check! [value message data]
  (when-not value
    (throw (ex-info message data))))

(defn- close? [a b]
  (< (Math/abs (- (double a) (double b))) 0.00001))

(defn- frame-state [context]
  (ffi/with-layout [out f/gl-frame-state-layout]
    (when-not (zero? (f/gl-test-frame-state (:handle context) out))
      (throw (ex-info "gl-test-frame-state failed" {:error (f/last-error)})))
    {:viewport (mapv #(ffi/read-field out f/gl-frame-state-layout [:viewport %]) (range 4))
     :framebuffer-size (mapv #(ffi/read-field out f/gl-frame-state-layout [:framebuffer-size %]) (range 2))
     :window-size (mapv #(ffi/read-field out f/gl-frame-state-layout [:window-size %]) (range 2))
     :projection (mapv #(ffi/read-field out f/gl-frame-state-layout [:projection %]) (range 16))
     :shader-compilations (ffi/read-field out f/gl-frame-state-layout :shader-compilations)
     :resource-uploads (ffi/read-field out f/gl-frame-state-layout :resource-uploads)}))

(defn- native-resize! [context width height]
  (when-not (zero? (f/gl-test-resize (:handle context) width height))
    (throw (ex-info "gl-test-resize failed" {:error (f/last-error)}))))

(defn- check-projection! [actual expected label]
  (doseq [[i expected-value] (map-indexed vector expected)]
    (check! (close? (nth actual i) expected-value)
            "native orthographic projection mismatch"
            {:label label :index i :actual (nth actual i) :expected expected-value})))

(defn resize-smoke! []
  (let [cpu (shape-resource)
        context (renderer/create-context
                  {:title "slipstream native resize" :width 320 :height 240})
        handle (atom nil)
        result (atom nil)]
    (try
      (reset! handle (renderer/upload! context cpu))
      (let [commands [{:handle @handle :color [1 1 1 1]}]]
        (renderer/render! context commands)
        (let [initial (frame-state context)]
          (native-resize! context 517 313)
          (renderer/render! context commands)
          (let [resized (frame-state context)
                [width height] (:framebuffer-size resized)
                aspect (/ (double width) height)
                expected [(/ 2.0 aspect) 0 0 0
                          0 2 0 0
                          0 0 -0.01 0
                          -1 -1 0 1]]
            (check! (= [0 0 (first (:framebuffer-size initial))
                        (second (:framebuffer-size initial))]
                       (:viewport initial))
                    "initial native viewport does not match framebuffer" initial)
            (check! (= [0 0 width height] (:viewport resized))
                    "resized native viewport does not match framebuffer" resized)
            (check! (not= (:framebuffer-size initial) (:framebuffer-size resized))
                    "native resize did not change framebuffer dimensions"
                    {:initial initial :resized resized})
            (check-projection! (:projection resized) expected :default-y-up)
            (check! (= 1 (:resource-uploads initial) (:resource-uploads resized))
                    "resize reuploaded native resources"
                    {:initial initial :resized resized})
            (check! (and (pos? (:shader-compilations initial))
                         (= (:shader-compilations initial)
                            (:shader-compilations resized)))
                    "resize recompiled shaders"
                    {:initial initial :resized resized})
            (reset! result
                    {:initial-viewport (:viewport initial)
                     :resized-viewport (:viewport resized)
                     :window-size (:window-size resized)
                     :framebuffer-size (:framebuffer-size resized)
                     :high-dpi (not= (:window-size resized) (:framebuffer-size resized))
                     :default-projection-verified true
                     :shader-compilations (:shader-compilations resized)
                     :resource-uploads (:resource-uploads resized)}))))
      (finally
        (when @handle (renderer/release! context @handle))
        (renderer/destroy! context)
        (resources/release! cpu)))
    (let [explicit (renderer/create-context
                     {:title "slipstream explicit bounds" :width 320 :height 240
                      :bounds [-2 4 3 7]})]
      (try
        (renderer/render! explicit [])
        (let [state (frame-state explicit)]
          (check-projection! (:projection state)
                             [(/ 1.0 3.0) 0 0 0
                              0 0.5 0 0
                              0 0 -0.01 0
                              (/ -1.0 3.0) -2.5 0 1]
                             :explicit-y-up)
          (check! (= [0 0 (first (:framebuffer-size state))
                      (second (:framebuffer-size state))]
                     (:viewport state))
                  "explicit-bounds viewport mismatch" state)
          (swap! result assoc :explicit-projection-verified true))
        (finally
          (renderer/destroy! explicit))))
    (println @result)))

(deftest reactive-text-layout-honors-align-anchor-and-advanced-paint
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})
          props {:font test-font :text "AA" :size 2 :align :center :anchor :cap-top
                 :fill {:type :linear :from [0 0] :to [1 0]
                        :stops [[0 [1 0 0 1]] [1 [0 0 1 1]]]}
                 :texture-width 128 :scene-path [:children 5 :text]}]
      (binding [sg/*context* context]
        (let [text (b/create! :text props)
              scene (atom {:children [text]})]
          (try
            (let [glyphs (:resources @text)
                  advances (map #(get-in % [:shape :advance]) glyphs)
                  total (* 2 (reduce + advances))
                  expected-y (* -2 (get-in (first glyphs) [:metrics :cap-height-ratio]))
                  commands ((var-get #'sg/draw-tree) scene)]
              (is (= 2 (count commands)))
              (is (every? #(pos? (get-in % [:root :gradient-id])) glyphs))
              (is (close? (- (/ total 2)) (get-in commands [0 :transform 0])))
              (is (close? expected-y (get-in commands [0 :transform 1])))
              (is (close? (+ (- (/ total 2)) (* 2 (first advances)))
                          (get-in commands [1 :transform 0])))
              (is (every? #(= 2 (:scale %)) commands)))
            (finally
              (resources/dispose-tree! text)))))
      (renderer/destroy! context))))

(deftest reactive-shape-scale-is-emitted-on-the-draw-command
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})]
      (binding [sg/*context* context]
        (let [shape (b/create! :shape {:path [[:rect 0 0 1 1]] :scale 2
                                      :texture-width 128})
              scene (atom {:children [shape]})]
          (is (= 2 (:scale (first ((var-get #'sg/draw-tree) scene)))))
          (resources/dispose-tree! shape)))
      (renderer/destroy! context))))

(deftest sdf-mask-replacement-and-disposal-have-exact-ownership
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})
          props {:mask {:type :sdf-tile :params [0 0 1]
                        :path [[:rect 0 0 0.5 1]] :sdf true}
                 :texture-width 128 :scene-path [:children 0 :group]}]
      (binding [sg/*context* context]
        (let [group (b/create! :group props)
              original (:mask-resource @group)
              updated (assoc-in props [:mask :params] [0 0 2])]
          (is (some? original))
          (b/apply-props! :group group updated)
          (let [replacement (:mask-resource @group)
                command (first ((var-get #'sg/draw-tree) (atom {:children [group]})))]
            (is (not (identical? original replacement)))
            (is @(:released? original))
            (is (= (renderer/native-handle replacement) (:mask-handle command)))
            (resources/dispose-tree! group)
            (is @(:released? replacement)))))
      (renderer/destroy! context))))

(deftest scheduled-work-runs-on-the-ui-thread-and-disposal-drops-the-queue
  (with-fake-native [native (fake-native)]
    (let [context (renderer/create-context {})
          scheduled-thread (atom nil)
          posted (promise)
          post-disposal (atom false)
          close-posted? (atom false)
          frames (atom 0)]
      (renderer/run-loop! context
        {:max-frames 30
         :draw-tree
         (fn []
           (when (= 1 (swap! frames inc))
             (future
               (renderer/schedule! context
                 #(reset! scheduled-thread (Thread/currentThread)))
               (deliver posted true))
             (when-not (deref posted 1000 false)
               (throw (ex-info "Timed out scheduling work from the producer thread" {}))))
           (when (and @scheduled-thread (not @close-posted?))
             (renderer/schedule! context #(reset! post-disposal true))
             (reset! close-posted? true)
             (swap! (:close native) conj (:handle context)))
           (when (> @frames 25)
             (swap! (:close native) conj (:handle context)))
           [])})
      (is (true? (deref posted 1000 false)))
      (is (some? @scheduled-thread))
      (is (identical? @scheduled-thread (Thread/currentThread)))
      (is (true? @close-posted?))
      (is (false? @post-disposal))
      (renderer/destroy! context)
      (renderer/schedule! context #(reset! post-disposal true))
      (is (false? @post-disposal)))))

(deftest terminal-loop-transition-drops-late-scheduled-work
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})
          late-work (atom false)
          finished (promise)]
      (renderer/run-loop! context
        {:max-frames 1
         :draw-tree []
         :after-render #(renderer/schedule! context #(reset! late-work true))})
      (deliver finished true)
      (is (true? (deref finished 1000 false)))
      (renderer/schedule! context #(reset! late-work true))
      (is (false? @late-work))
      (renderer/destroy! context))))

(deftest nested-scheduled-work-runs-on-the-next-frame
  (with-fake-native [_native (fake-native)]
    (let [context (renderer/create-context {})
          events (atom [])]
      (renderer/run-loop! context
        {:max-frames 2
         :on-start #(renderer/schedule! context
                      (fn []
                        (swap! events conj :first)
                        (renderer/schedule! context #(swap! events conj :second))))
         :draw-tree (fn []
                      (swap! events conj :frame)
                      [])
         :after-render #(swap! events conj :after-render)})
      (is (= [:first :frame :after-render :second :frame :after-render] @events))
      (renderer/destroy! context))))

(deftest lifecycle-unmounts-and-disposes-before-context-destruction
  (let [calls (atom [])
        factory {:create! (fn [_ _ _]
                            (swap! calls conj :acquire)
                            :resource)
                 :release! (fn [_] (swap! calls conj :release))
                 :update-layers! (fn [_ _] nil)}]
    (binding [sg/*factory* factory]
      (with-redefs [renderer/create-context
                    (fn [_] (swap! calls conj :create) :headless)
                    renderer/run-loop!
                    (fn [_ _] (swap! calls conj :loop))
                    renderer/stop-scheduling!
                    (fn [_] nil)
                    renderer/destroy!
                    (fn [_] (swap! calls conj :destroy))]
        ((:run-lifecycle (b/current)) {}
          {:mount! (fn [_ _] (swap! calls conj :mount))
           :unmount! (fn [] (swap! calls conj :unmount))
           :abandon! (fn [] (swap! calls conj :abandon))})))
    (is (= [:create :acquire :mount :loop :unmount :release :destroy]
           @calls))))

(deftest lifecycle-drops-work-scheduled-during-unmount
  (with-fake-native [_native (fake-native)]
    (let [ran (atom false)]
      (with-redefs [renderer/run-loop! (fn [_ _] nil)]
        ((:run-lifecycle (b/current)) {}
          {:mount! (fn [_ _] nil)
           :unmount! #(b/schedule (fn [] (reset! ran true)))
           :abandon! (fn [] nil)}))
      (is (false? @ran)))))

(defn gui-smoke! []
  (let [cpu (shape-resource)
        a (renderer/create-context {:title "slipstream smoke A" :width 320 :height 240})
        b (renderer/create-context {:title "slipstream smoke B" :width 320 :height 240})]
    (try
      (let [handle-a (renderer/upload! a cpu)
            handle-b (renderer/upload! b cpu)
            cross-context-rejected
            (try
              (renderer/release! b handle-a)
              false
              (catch Exception error
                (boolean (re-find #"different GL context" (.getMessage error)))))]
        (renderer/render! a [{:handle handle-a :color [1 0 0 1]}])
        (renderer/swap-buffers! a)
        (renderer/render! b [{:handle handle-b :color [0 1 0 1]}])
        (renderer/swap-buffers! b)
        (renderer/destroy! a)
        (renderer/render! b [{:handle handle-b :color [0 0 1 1]}])
        (renderer/swap-buffers! b)
        (renderer/release! b handle-b)
        (println {:first-frame true :second-frame true
                  :cross-context-rejected cross-context-rejected
                  :independent-teardown true})
        (when-not cross-context-rejected
          (throw (ex-info "cross-context release was not rejected" {}))))
      (finally
        (renderer/destroy! a)
        (renderer/destroy! b)
        (resources/release! cpu)))))
