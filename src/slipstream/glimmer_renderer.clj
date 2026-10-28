(ns slipstream.glimmer-renderer
  (:require [jolt.ffi :as ffi]
            [slipstream.core :as core]
            [slipstream.ffi :as f]
            [slipstream.glimmer-resources :as resources]))

(defrecord GLContext [handle options destroyed? cache work-queue running?])

(defn- native-failure [op]
  (let [message (or (f/last-error) "unknown native error")]
    (throw (ex-info (str (name op) " failed: " message)
                    {:op op :native-error message}))))

(defn- context-handle [context op]
  (when-not (instance? GLContext context)
    (throw (ex-info "not a Slipstream GL context" {:op op :context context})))
  (when @(:destroyed? context)
    (throw (ex-info "GL context is destroyed" {:op op})))
  (:handle context))

(defn create-context
  [& [{:keys [title width height bounds visible]
       :or {title "glimmer" width 400 height 300 visible true}
       :as options}]]
  (when (and bounds (not (and (vector? bounds) (= 4 (count bounds))
                              (every? number? bounds))))
    (throw (ex-info ":bounds must be [left right bottom top]" {:bounds bounds})))
  (ffi/with-arena [arena]
    (ffi/with-layout [opts f/gl-context-options-layout]
      (ffi/write-field opts f/gl-context-options-layout :title
                       (ffi/string->ptr arena (str title)))
      (ffi/write-field opts f/gl-context-options-layout :width width)
      (ffi/write-field opts f/gl-context-options-layout :height height)
      (doseq [i (range 4)]
        (ffi/write-field opts f/gl-context-options-layout [:bounds i]
                         (double (get bounds i 0))))
      (ffi/write-field opts f/gl-context-options-layout :has-bounds (if bounds 1 0))
      (ffi/write-field opts f/gl-context-options-layout :visible (if visible 1 0))
      (let [handle (f/gl-context-create opts)]
        (when (or (nil? handle) (zero? handle))
          (native-failure :create-context))
        (->GLContext handle options (atom false) (atom {}) (atom []) (atom false))))))

(defn destroy! [context]
  (when (and (instance? GLContext context)
             (compare-and-set! (:destroyed? context) false true))
    (reset! (:running? context) false)
    (reset! (:work-queue context) [])
    (try
      (when-not (zero? (f/gl-context-destroy (:handle context)))
        (native-failure :destroy!))
      (finally
        (let [entries (vals @(:cache context))]
          (reset! (:cache context) {})
          (doseq [entry entries]
            (dotimes [_ @(:ref-count entry)]
              (resources/release! (:cpu entry))))))))
  context)

(defn upload! [context cpu-resource]
  (let [handle (context-handle context :upload!)
        atlas (:atlas cpu-resource)]
    (when (or (nil? atlas) @(:destroyed? atlas))
      (throw (ex-info "CPU resource atlas is destroyed" {:op :upload!})))
    (core/call-with-layers [(:root cpu-resource)]
      (fn [root _]
        (let [resource (f/gl-upload handle (:handle atlas) root)]
          (when (or (nil? resource) (zero? resource))
            (native-failure :upload!))
          resource)))))

(defn release! [context handle]
  (let [context-handle (context-handle context :release!)]
    (when-not (zero? (f/gl-release context-handle handle))
      (native-failure :release!)))
  nil)

(defn- command-data [command]
  (let [transform (get command :transform [0 0 0])
        color (get command :color [1 1 1 1])]
    (when-not (<= 2 (count transform) 3)
      (throw (ex-info "draw transform must have two or three components" {:command command})))
    (when-not (= 4 (count color))
      (throw (ex-info "draw color must have four components" {:command command})))
    {:handle (:handle command)
     :transform (mapv double (into (vec transform) (repeat (- 3 (count transform)) 0)))
     :color (mapv double color)
     :scale (double (get command :scale 1))
     :visible (if (false? (:visible command)) 0 1)}))

(defn render! [context draw-tree]
  (let [handle (context-handle context :render!)
        commands (mapv command-data draw-tree)
        size (ffi/layout-size f/gl-draw-command-layout)]
    (ffi/with-alloc [command-p (max 1 (* size (count commands)))]
      (doseq [[i command] (map-indexed vector commands)]
        (let [p (+ command-p (* i size))]
          (ffi/write-field p f/gl-draw-command-layout :handle (:handle command))
          (doseq [[field value] (map vector [:x :y :z] (:transform command))]
            (ffi/write-field p f/gl-draw-command-layout [:transform field] value))
          (doseq [[field value] (map vector [:r :g :b :a] (:color command))]
            (ffi/write-field p f/gl-draw-command-layout [:color field] value))
          (ffi/write-field p f/gl-draw-command-layout :scale (:scale command))
          (ffi/write-field p f/gl-draw-command-layout :visible (:visible command))))
      (when-not (zero? (f/gl-render handle command-p (count commands)))
        (native-failure :render!))))
  nil)

(defn poll! [context]
  (when-not (zero? (f/gl-poll (context-handle context :poll!)))
    (native-failure :poll!))
  nil)

(defn should-close? [context]
  (let [result (f/gl-should-close (context-handle context :should-close?))]
    (when (and (false? result) (seq (f/last-error)))
      (native-failure :should-close?))
    result))

(defn swap-buffers! [context]
  (when-not (zero? (f/gl-swap (context-handle context :swap-buffers!)))
    (native-failure :swap-buffers!))
  nil)

(defn schedule! [context work]
  (cond
    @(:destroyed? context) nil
    @(:running? context) (swap! (:work-queue context) conj work)
    :else (work))
  nil)

(defn- drain-scheduled! [context]
  (loop []
    (let [work (atom nil)]
      (swap! (:work-queue context)
             (fn [works]
               (when (seq works) (reset! work (first works)))
               (if (seq works) (subvec works 1) works)))
      (when @work
        (@work)
        (recur)))))

(defn run-loop! [context {:keys [draw-tree max-frames auto-quit-ms]
                          :or {draw-tree []}}]
  (let [started (System/currentTimeMillis)]
    (reset! (:running? context) true)
    (try
      (loop [frames 0]
        (if (or (and max-frames (>= frames max-frames))
                (and auto-quit-ms (>= (- (System/currentTimeMillis) started) auto-quit-ms)))
          frames
          (do
            (poll! context)
            (if (should-close? context)
              frames
              (do
                (drain-scheduled! context)
                (render! context (if (fn? draw-tree) (draw-tree) draw-tree))
                (swap-buffers! context)
                (recur (inc frames)))))))
      (finally
        (reset! (:running? context) false)
        (reset! (:work-queue context) [])))))

(declare release-resource!)

(def ^:private gpu-factory
  {:release! (fn [entry] (release-resource! entry))})

(defn- release-resource! [entry]
  (let [context (:context entry)]
    (when-not @(:destroyed? context)
      (locking (:cache context)
        (when (pos? @(:ref-count entry))
          (let [last? (zero? (swap! (:ref-count entry) dec))]
            (when last?
              (release! context (:native entry))
              (swap! (:cache context) dissoc (:cpu entry)))
            (resources/release! (:cpu entry)))))))
  nil)

(defn acquire-resource! [context cpu-resource]
  (context-handle context :acquire-resource!)
  (locking (:cache context)
    (let [cache (:cache context)
          entry (if-let [existing (get @cache cpu-resource)]
                  (do
                    (swap! (:ref-count existing) inc)
                    existing)
                  (try
                    (let [entry {:context context
                                 :cpu cpu-resource
                                 :native (upload! context cpu-resource)
                                 :ref-count (atom 1)}]
                      (swap! cache assoc cpu-resource entry)
                      entry)
                    (catch Throwable error
                      (resources/release! cpu-resource)
                      (throw error))))]
      {:factory gpu-factory
       :handle entry
       :context context
       :cache-key (:cache-key cpu-resource)
       :root (:root cpu-resource)
       :atlas (:atlas cpu-resource)
       :mesh (:mesh cpu-resource)
       :released? (atom false)})))

(defn native-handle [resource]
  (get-in resource [:handle :native]))
