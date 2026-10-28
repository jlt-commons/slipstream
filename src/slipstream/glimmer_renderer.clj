(ns slipstream.glimmer-renderer
  (:require [jolt.ffi :as ffi]
            [slipstream.core :as core]
            [slipstream.ffi :as f]
            [slipstream.glimmer-resources :as resources]))

(defrecord GLContext [handle options destroyed? cache work-queue running? accepting-work?])

(defn- native-failure
  ([op] (native-failure op {}))
  ([op data]
   (let [message (or (f/last-error) "unknown native error")]
     (throw (ex-info (str (name op) " failed: " message)
                     (merge {:op op :native-error message} data))))))

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

(defn frame-state [context]
  (let [handle (context-handle context :frame-state)]
    (ffi/with-layout [state f/gl-frame-state-layout]
      (when-not (zero? (f/gl-test-frame-state handle state))
        (native-failure :frame-state))
      {:viewport (mapv #(ffi/read-field state f/gl-frame-state-layout [:viewport %]) (range 4))
       :framebuffer-size (mapv #(ffi/read-field state f/gl-frame-state-layout [:framebuffer-size %])
                              (range 2))
       :window-size (mapv #(ffi/read-field state f/gl-frame-state-layout [:window-size %]) (range 2))
       :projection (mapv #(ffi/read-field state f/gl-frame-state-layout [:projection %]) (range 16))
       :shader-compilations (ffi/read-field state f/gl-frame-state-layout :shader-compilations)
       :resource-uploads (ffi/read-field state f/gl-frame-state-layout :resource-uploads)
       :paint-texture-uploads (ffi/read-field state f/gl-frame-state-layout :paint-texture-uploads)
       :live-resources (ffi/read-field state f/gl-frame-state-layout :live-resources)
       :live-effects (ffi/read-field state f/gl-frame-state-layout :live-effects)
       :draw-commands (ffi/read-field state f/gl-frame-state-layout :draw-commands)
       :geometry-commands (ffi/read-field state f/gl-frame-state-layout :geometry-commands)
       :group-commands (ffi/read-field state f/gl-frame-state-layout :group-commands)})))

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
        (->GLContext handle options (atom false) (atom {}) (atom []) (atom false) (atom true))))))

(defn stop-scheduling! [context]
  (locking (:work-queue context)
    (reset! (:accepting-work? context) false)
    (reset! (:running? context) false)
    (reset! (:work-queue context) []))
  nil)

(defn destroy! [context]
  (when (and (instance? GLContext context)
             (compare-and-set! (:destroyed? context) false true))
    (stop-scheduling! context)
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
     :effect (if (or begin? end?) 0 (get command :effect 0))
     :transform (mapv double (into (vec transform) (repeat (- 3 (count transform)) 0)))
     :color (mapv double color)
     :scene-path (:scene-path command)
     :scale (double (get command :scale 1))
     :effect-id (get command :effect-id 0)
     :effect-param (double (get command :effect-param 0))
     :bleed (double (get command :bleed 0))
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
  (let [scene-path (or (:scene-path command) [index])]
    (when (zero? (:kind command))
      (when (and (not (zero? (:effect-id command))) (zero? (:effect command)))
        (throw (ex-info "effect ID requires a custom effect program"
                        {:op :render! :scene-path (conj scene-path :effect-id)
                         :effect-id (:effect-id command)})))
      (let [expected (paint-texture! context (:handle command))
            actual (:paint command)]
        (when-not (= expected actual)
          (throw (ex-info "draw command paint reference does not match its resource"
                          {:op :render! :scene-path (conj scene-path :paint)
                           :expected-paint expected :actual-paint actual
                           :native-error "advanced paint reference is missing or invalid"})))))))

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
          (ffi/write-field p f/gl-draw-command-layout :effect (:effect command))
          (doseq [[field value] (map vector [:x :y :z] (:transform command))]
            (ffi/write-field p f/gl-draw-command-layout [:transform field] value))
          (doseq [[field value] (map vector [:r :g :b :a] (:color command))]
            (ffi/write-field p f/gl-draw-command-layout [:color field] value))
          (ffi/write-field p f/gl-draw-command-layout :scale (:scale command))
          (ffi/write-field p f/gl-draw-command-layout :effect-id (:effect-id command))
          (ffi/write-field p f/gl-draw-command-layout :effect-param (:effect-param command))
          (ffi/write-field p f/gl-draw-command-layout :bleed (:bleed command))
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
        (let [native-index (f/gl-error-command handle)
              command-index (if (and (integer? native-index) (<= 0 native-index))
                              native-index
                              (or (first (keep-indexed (fn [i command]
                                                        (when-not (zero? (:effect command)) i))
                                                      commands))
                                  0))
              field (if (and (< command-index (count commands))
                             (not (zero? (:effect (nth commands command-index)))))
                      :effect
                      :render)
              scene-path (or (:scene-path (nth commands command-index nil))
                             [command-index])]
          (native-failure :render! {:scene-path (conj scene-path field)})))))
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

(defn input-snapshot [context]
  (let [handle (context-handle context :input-snapshot)]
    (ffi/with-layout [input f/gl-input-layout]
      (when-not (zero? (f/gl-input-snapshot handle input))
        (native-failure :input-snapshot))
      {:x (ffi/read-field input f/gl-input-layout :x)
       :y (ffi/read-field input f/gl-input-layout :y)
       :scroll-x (ffi/read-field input f/gl-input-layout :scroll-x)
       :scroll-y (ffi/read-field input f/gl-input-layout :scroll-y)
       :left (not (zero? (ffi/read-field input f/gl-input-layout :left)))
       :right (not (zero? (ffi/read-field input f/gl-input-layout :right)))
       :middle (not (zero? (ffi/read-field input f/gl-input-layout :middle)))
       :space (not (zero? (ffi/read-field input f/gl-input-layout :space)))
       :r (not (zero? (ffi/read-field input f/gl-input-layout :r)))})))

(defn request-close! [context]
  (when-not (zero? (f/gl-request-close (context-handle context :request-close!)))
    (native-failure :request-close!))
  nil)

(defn- cursor-position [context]
  (ffi/with-layout [cursor f/gl-cursor-layout]
    (when-not (zero? (f/gl-cursor-snapshot context cursor))
      (native-failure :cursor->bounds))
    [(ffi/read-field cursor f/gl-cursor-layout :x)
     (ffi/read-field cursor f/gl-cursor-layout :y)]))

(defn cursor->bounds [context bounds]
  (let [[x y] (cursor-position (context-handle context :cursor->bounds))
        handle (context-handle context :cursor->bounds)]
    (ffi/with-layout [state f/gl-frame-state-layout]
      (when-not (zero? (f/gl-test-frame-state handle state))
        (native-failure :cursor->bounds))
      (let [window-width (ffi/read-field state f/gl-frame-state-layout [:window-size 0])
            window-height (ffi/read-field state f/gl-frame-state-layout [:window-size 1])
            framebuffer-width (ffi/read-field state f/gl-frame-state-layout [:framebuffer-size 0])
            framebuffer-height (ffi/read-field state f/gl-frame-state-layout [:framebuffer-size 1])]
        (when (or (zero? window-width) (zero? window-height)
                  (zero? framebuffer-width) (zero? framebuffer-height))
          (throw (ex-info "cannot map cursor with an empty window or framebuffer"
                          {:op :cursor->bounds})))
        (let [[left right bottom top]
              (or bounds [0 (/ (double framebuffer-width) framebuffer-height) 0 1])]
          [(+ left (* (/ x window-width) (- right left)))
           (+ bottom (* (- 1 (/ y window-height)) (- top bottom)))])))))

(defn swap-buffers! [context]
  (when-not (zero? (f/gl-swap (context-handle context :swap-buffers!)))
    (native-failure :swap-buffers!))
  nil)

(defn schedule! [context work]
  (let [run-now? (locking (:work-queue context)
                   (cond
                     (or @(:destroyed? context) (not @(:accepting-work? context))) false
                     @(:running? context) (do (swap! (:work-queue context) conj work) false)
                     :else true))]
    (when run-now? (work)))
  nil)

(defn- drain-scheduled! [context]
  (let [works (locking (:work-queue context)
                (let [works @(:work-queue context)]
                  (reset! (:work-queue context) [])
                  works))]
    (doseq [work works]
      (work))))

(defn run-loop! [context {:keys [draw-tree max-frames auto-quit-ms on-start after-render]
                          :or {draw-tree []}}]
  (when-not @(:accepting-work? context)
    (throw (ex-info "GL context scheduling is terminal" {:op :run-loop!})))
  (let [started (System/currentTimeMillis)]
    (reset! (:running? context) true)
    (try
      (when on-start (on-start))
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
                (when after-render (after-render))
                (swap-buffers! context)
                (recur (inc frames)))))))
      (finally
        (stop-scheduling! context)))))

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
       :metrics (:metrics cpu-resource)
       :shape (:shape cpu-resource)
       :released? (atom false)})))

(defn native-handle [resource]
  (get-in resource [:handle :native]))

(defn native-paint-handle [resource]
  (get-in resource [:handle :paint] 0))
