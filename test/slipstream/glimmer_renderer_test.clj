(ns slipstream.glimmer-renderer-test
  (:require [clojure.test :refer [deftest is]]
            [glimmer.backend :as b]
            [jolt.ffi :as ffi]
            [slipstream.ffi :as f]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-compiler :as compiler]
            [slipstream.glimmer-renderer :as renderer]
            [slipstream.glimmer-resources :as resources]))

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
  (is (zero? (f/gl-upload 0 0 0)))
  (is (not (zero? (f/gl-release 0 0))))
  (is (not (zero? (f/gl-render 0 0 0))))
  (is (not (zero? (f/gl-swap 0)))))

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

(deftest resize-renders-reuse-context-and-never-reupload
  (with-fake-native [native (fake-native)]
    (let [context (renderer/create-context
                    {:width 640 :height 480 :bounds [0 2 0 1]})]
      (renderer/render! context [])
      (renderer/render! context [])
      (renderer/swap-buffers! context)
      (is (= [[:create 1] [:render 1 0] [:render 1 0] [:swap 1]]
             @(:calls native)))
      (renderer/destroy! context))))

(deftest scheduled-work-runs-on-the-ui-thread-and-disposal-drops-the-queue
  (with-fake-native [native (fake-native)]
    (let [context (renderer/create-context {})
          scheduled-thread (atom nil)
          post-disposal (atom false)
          posted? (atom false)
          frames (atom 0)]
      (renderer/run-loop! context
        {:max-frames 30
         :draw-tree
         (fn []
           (let [frame (swap! frames inc)]
             (when (= frame 1)
               (future
                 (renderer/schedule! context
                   #(reset! scheduled-thread (Thread/currentThread)))))
             (when (and @scheduled-thread (not @posted?))
               (renderer/schedule! context #(reset! post-disposal true))
               (reset! posted? true)
               (swap! (:close native) conj (:handle context)))
             (when (> frame 25)
               (swap! (:close native) conj (:handle context)))
             []))})
      (is (some? @scheduled-thread))
      (is (identical? @scheduled-thread (Thread/currentThread)))
      (is (true? @posted?))
      (is (false? @post-disposal))
      (renderer/destroy! context)
      (renderer/schedule! context #(reset! post-disposal true))
      (is (false? @post-disposal)))))

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
                    renderer/destroy!
                    (fn [_] (swap! calls conj :destroy))]
        ((:run-lifecycle (b/current)) {}
          {:mount! (fn [_ _] (swap! calls conj :mount))
           :unmount! (fn [] (swap! calls conj :unmount))
           :abandon! (fn [] (swap! calls conj :abandon))})))
    (is (= [:create :acquire :mount :loop :unmount :release :destroy]
           @calls))))

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
