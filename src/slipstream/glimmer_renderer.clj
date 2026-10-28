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

(defn program! [context shader-key]
  (let [result (f/gl-program (context-handle context :program!) (name shader-key))]
    (when (or (nil? result) (zero? result))
      (native-failure :program!))
    result))

(defn paint-texture! [context resource]
  (let [result (f/gl-paint-texture (context-handle context :paint-texture!) resource)
        error (f/last-error)]
    (when (seq error)
      (native-failure :paint-texture!))
    (or result 0)))

(defn capture! [context]
  (let [handle (context-handle context :capture!)]
    (ffi/with-layout [state f/gl-frame-state-layout]
      (when-not (zero? (f/gl-test-frame-state handle state))
        (native-failure :capture!))
      (let [width (ffi/read-field state f/gl-frame-state-layout [:framebuffer-size 0])
            height (ffi/read-field state f/gl-frame-state-layout [:framebuffer-size 1])
            byte-count (* width height 4)]
        (ffi/with-alloc [pixels (max 1 byte-count)]
          (when-not (zero? (f/gl-test-capture handle pixels byte-count))
            (native-failure :capture!))
          {:width width :height height :pixels (ffi/read-array pixels byte-count)})))))

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

(def ^:private draw-modes
  {:visible 0 :hidden 1 :geometry 2 :mask 3})
(def ^:private blend-modes
  {:src-over 0 :src 1 :dst 2 :src-in 3 :dst-in 4 :src-out 5 :dst-out 6
   :src-atop 7 :dst-atop 8 :xor 9 :clear 10 :dst-over 11
   :multiply 20 :screen 21 :overlay 22 :darken 23 :lighten 24
   :color-dodge 25 :color-burn 26 :hard-light 27 :soft-light 28
   :difference 29 :exclusion 30})
(def ^:private mask-types
  {:sdf-tile 0 :circle 1 :rect 2 :capsule 3 :arc 4 :arc-band 5
   :hexagon 6 :octagon 7 :star 8})

(defn- enum-value [value values label]
  (if (integer? value)
    value
    (or (get values value)
        (throw (ex-info (str "unknown " label) {label value})))))

(defn- mask-parameters [mask]
  (get mask :params (if (= :sdf-tile (:type mask)) [0 0 1] [])))

(defn- command-data [command]
  (let [begin? (contains? command :begin-group)
        end? (contains? command :end-group)
        transform (get command :transform [0 0 0])
        color (get command :color [1 1 1 1])
        mask (when begin? (:begin-group command))]
    (when (and begin? end?)
      (throw (ex-info "command cannot both begin and end a group" {:command command})))
    (when-not (<= 2 (count transform) 3)
      (throw (ex-info "draw transform must have two or three components" {:command command})))
    (when-not (= 4 (count color))
      (throw (ex-info "draw color must have four components" {:command command})))
    (when (and mask (not (map? mask)))
      (throw (ex-info "group mask must be a map or nil" {:command command})))
    {:handle (if begin? (get command :mask-handle 0) (get command :handle 0))
     :paint (if (or begin? end?) 0 (get command :paint 0))
     :transform (mapv double (into (vec transform) (repeat (- 3 (count transform)) 0)))
     :color (mapv double color)
     :scale (double (get command :scale 1))
     :effect-id (get command :effect-id 0)
     :effect-param (double (get command :effect-param 0))
     :visible (if (false? (:visible command)) 0 1)
     :kind (cond begin? 1 end? 2 :else 0)
     :draw-mode (enum-value (get command :draw-mode :visible) draw-modes :draw-mode)
     :blend-mode (enum-value (if end? (:end-group command)
                                 (get command :blend-mode :src-over))
                             blend-modes :blend-mode)
     :mask-type (if mask (enum-value (:type mask) mask-types :mask-type) 255)
     :mask-invert (if (and mask (:invert mask)) 1 0)
     :mask-params (mapv double (into (vec (mask-parameters mask))
                                     (repeat (- 6 (count (mask-parameters mask))) 0)))}))

(defn- validate-paint! [context index command]
  (when (zero? (:kind command))
    (when-not (zero? (:effect-id command))
      (throw (ex-info "effect ID requires a custom effect program"
                      {:op :render! :scene-path [index :effect-id]
                       :effect-id (:effect-id command)})))
    (let [expected (paint-texture! context (:handle command))
          actual (:paint command)]
      (when-not (= expected actual)
        (throw (ex-info "draw command paint reference does not match its resource"
                        {:op :render! :scene-path [index :paint]
                         :expected-paint expected :actual-paint actual
                         :native-error "advanced paint reference is missing or invalid"}))))))

(defn render! [context draw-tree]
  (let [handle (context-handle context :render!)
        commands (mapv command-data draw-tree)
        size (ffi/layout-size f/gl-draw-command-layout)]
    (doseq [[i command] (map-indexed vector commands)]
      (validate-paint! context i command))
    (ffi/with-alloc [command-p (max 1 (* size (count commands)))]
      (doseq [[i command] (map-indexed vector commands)]
        (let [p (+ command-p (* i size))]
          (ffi/write-field p f/gl-draw-command-layout :handle (:handle command))
          (ffi/write-field p f/gl-draw-command-layout :paint (:paint command))
          (doseq [[field value] (map vector [:x :y :z] (:transform command))]
            (ffi/write-field p f/gl-draw-command-layout [:transform field] value))
          (doseq [[field value] (map vector [:r :g :b :a] (:color command))]
            (ffi/write-field p f/gl-draw-command-layout [:color field] value))
          (ffi/write-field p f/gl-draw-command-layout :scale (:scale command))
          (ffi/write-field p f/gl-draw-command-layout :effect-id (:effect-id command))
          (ffi/write-field p f/gl-draw-command-layout :effect-param (:effect-param command))
          (ffi/write-field p f/gl-draw-command-layout :visible (:visible command))
          (ffi/write-field p f/gl-draw-command-layout :kind (:kind command))
          (ffi/write-field p f/gl-draw-command-layout :draw-mode (:draw-mode command))
          (ffi/write-field p f/gl-draw-command-layout :blend-mode (:blend-mode command))
          (ffi/write-field p f/gl-draw-command-layout :mask-type (:mask-type command))
          (ffi/write-field p f/gl-draw-command-layout :mask-invert (:mask-invert command))
          (doseq [i (range 6)]
            (ffi/write-field p f/gl-draw-command-layout [:mask-params i]
                             (nth (:mask-params command) i)))))
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
                    (let [native (upload! context cpu-resource)
                          entry (try
                                  {:context context
                                   :cpu cpu-resource
                                   :native native
                                   :paint (paint-texture! context native)
                                   :ref-count (atom 1)}
                                  (catch Throwable error
                                    (release! context native)
                                    (throw error)))]
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

(defn native-paint-handle [resource]
  (get-in resource [:handle :paint] 0))
