(ns slipstream.core
  (:require [jolt.ffi :as ffi]
            [slipstream.ffi :as f]))

(defrecord Atlas [handle destroyed?])
(defrecord Path [handle destroyed?])
(defrecord Canvas [handle atlas destroyed?])

(defn- native-failure [op]
  (let [message (or (f/last-error) "unknown native error")]
    (throw (ex-info (str (name op) " failed: " message)
                    {:op op :native-error message}))))

(defn- live-handle [^Atlas atlas op]
  (when (or @(:destroyed? atlas)
            (nil? (:handle atlas))
            (zero? (:handle atlas)))
    (throw (ex-info "atlas is destroyed" {:op op})))
  (:handle atlas))

(defn make-atlas [& [{:keys [texture-width] :or {texture-width 512}}]]
  (let [handle (f/atlas-new texture-width)]
    (when (or (nil? handle) (zero? handle))
      (native-failure :make-atlas))
    (->Atlas handle (atom false))))

(defn destroy! [resource]
  (when (compare-and-set! (:destroyed? resource) false true)
    (let [result (cond
                   (instance? Path resource) (f/path-destroy (:handle resource))
                   (instance? Canvas resource) (f/canvas-destroy (:handle resource))
                   :else (f/atlas-destroy (:handle resource)))]
      (when-not (zero? result)
        (native-failure :destroy!))))
  resource)

(defn build! [^Atlas atlas]
  (let [handle (live-handle atlas :build!)]
    (when-not (zero? (f/atlas-build handle))
      (native-failure :build!)))
  atlas)

(defn built? [^Atlas atlas]
  (let [result (f/atlas-built? (live-handle atlas :built?))]
    (when (and (false? result) (seq (f/last-error)))
      (native-failure :built?))
    result))

(defmacro with-atlas [[atlas opts] & body]
  `(let [~atlas (make-atlas ~opts)]
     (try
       ~@body
       (finally
         (destroy! ~atlas)))))

(defn- key-data [key]
  (cond
    (or (keyword? key) (string? key))
    [1 0 (if (keyword? key) (subs (str key) 1) key)]

    (and (vector? key) (= :codepoint (first key)) (<= 2 (count key) 3))
    (let [cp (second key)
          mask (or (nth key 2 nil) 0)]
      (when-not (and (integer? cp) (<= 0 cp (if (zero? mask) 0xffffffff 0x1fffff))
                     (integer? mask) (<= 0 mask 255))
        (throw (ex-info "invalid codepoint key" {:key key})))
      [0 (bit-or cp (bit-shift-left mask 21)) nil])

    :else
    (throw (ex-info "invalid atlas key" {:key key}))))

(defn- key-identity [key]
  (let [[type cp name] (key-data key)]
    (if (zero? type) [:codepoint cp] [:name name])))

(defn- call-with-key [key f]
  (let [[type cp name] (key-data key)]
    (ffi/with-layout [p f/key-layout]
      (let [name-p (when name (ffi/string->ptr name))]
        (try
          (ffi/write-field p f/key-layout :type type)
          (ffi/write-field p f/key-layout :codepoint cp)
          (ffi/write-field p f/key-layout :name (or name-p 0))
          (f p)
          (finally
            (when name-p (ffi/free name-p))))))))

(def ^:private draw-modes
  {:visible 0 :hidden 1 :geometry 2 :mask 3})
(def ^:private blend-modes
  {:src-over 0 :src 1 :dst 2 :src-in 3 :dst-in 4 :src-out 5 :dst-out 6
   :src-atop 7 :dst-atop 8 :xor 9 :clear 10 :dst-over 11
   :multiply 20 :screen 21 :overlay 22 :darken 23 :lighten 24
   :color-dodge 25 :color-burn 26 :hard-light 27 :soft-light 28
   :difference 29 :exclusion 30})

(defn- enum-value [value values label]
  (cond
    (integer? value) value
    (contains? values value) (get values value)
    :else (throw (ex-info (str "unknown " label) {label value}))))

(defn- normalize-layer [layer]
  (when-not (and (map? layer) (contains? layer :key))
    (throw (ex-info "layer must be a map with a key" {:layer layer})))
  (let [color (get layer :color [0 0 0 1])
        transform (get layer :transform [0 0 0])
        transform (if (map? transform)
                    [(get transform :x 0) (get transform :y 0) (get transform :z 0)]
                    transform)]
    (when-not (and (vector? color) (= 4 (count color)))
      (throw (ex-info "layer color must have four components" {:layer layer})))
    (when-not (and (vector? transform) (<= 2 (count transform) 3))
      (throw (ex-info "layer transform must have two or three components" {:layer layer})))
    {:key (:key layer)
     :color (mapv double color)
     :transform (mapv double (into (vec transform) (repeat (- 3 (count transform)) 0)))
     :scale (double (get layer :scale 1))
     :effect-id (get layer :effect-id 0)
     :effect-param (double (get layer :effect-param 0))
     :gradient-id (get layer :gradient-id 0)
     :pattern-id (get layer :pattern-id 0)
     :bleed (double (get layer :bleed 0))
     :draw-mode (enum-value (get layer :draw-mode :visible) draw-modes :draw-mode)
     :blend-mode (enum-value (get layer :blend-mode :src-over) blend-modes :blend-mode)}))

(defn- write-layer! [p layer owned-names]
  (let [layer (normalize-layer layer)
        {:keys [key color transform]} layer
        [type cp name] (key-data key)
        name-p (when name (ffi/string->ptr name))]
    (when name-p (swap! owned-names conj name-p))
    (ffi/write-field p f/layer-layout [:key :type] type)
    (ffi/write-field p f/layer-layout [:key :codepoint] cp)
    (ffi/write-field p f/layer-layout [:key :name] (or name-p 0))
    (doseq [[field value] (map vector [:r :g :b :a] color)]
      (ffi/write-field p f/layer-layout [:color field] value))
    (doseq [[field value] (map vector [:x :y :z] transform)]
      (ffi/write-field p f/layer-layout [:transform field] value))
    (doseq [field [:effect-param :bleed]]
      (ffi/write-field p f/layer-layout field (get layer field)))
    (ffi/write-field p f/layer-layout :scale (double (get layer :scale 1)))
    (doseq [field [:effect-id :gradient-id :pattern-id :draw-mode :blend-mode]]
      (ffi/write-field p f/layer-layout field
                       (get layer field (case field
                                          :draw-mode 0
                                          :blend-mode 0
                                          0))))))

(defn- call-with-layers [layers f]
  (let [layers (vec layers)
        n (count layers)
        size (ffi/layout-size f/layer-layout)
        owned-names (atom [])]
    (ffi/with-alloc [p (max 1 (* n size))]
      (try
        (doseq [[i layer] (map-indexed vector layers)]
          (write-layer! (+ p (* i size)) layer owned-names))
        (f p n)
        (finally
          (doseq [name-p @owned-names] (ffi/free name-p)))))))

(defn- read-key [p]
  (let [type (ffi/read-field p f/key-layout :type)]
    (if (zero? type)
      (let [packed (ffi/read-field p f/key-layout :codepoint)
            mask (bit-shift-right (bit-and packed 0x1fe00000) 21)]
        (if (zero? mask)
          [:codepoint packed]
          [:codepoint (bit-and packed 0x1fffff) mask]))
      (ffi/ptr->string (ffi/read-field p f/key-layout :name)))))

(def ^:private shape-fields
  [:band-tex-x :band-tex-y :band-max-x :band-max-y
   :band-scale-x :band-scale-y :band-offset-x :band-offset-y
   :bearing-x :bearing-y :width :height :advance :origin-x :origin-y
   :origin-type :origin-data-x :origin-data-y])

(defn add-shape!
  [atlas key curves
   {:keys [auto-metrics bearing-x bearing-y width height advance
           num-bands-x num-bands-y splits-x splits-y origin]
    :or {auto-metrics true bearing-x 0 bearing-y 0 width 0 height 0 advance 0
         num-bands-x 0 num-bands-y 0 splits-x [] splits-y [] origin {}}}]
  (let [handle (live-handle atlas :add-shape!)
        curves (vec curves)
        curve-values (mapcat (fn [curve]
                               (when-not (= 6 (count curve))
                                 (throw (ex-info "curve must have six coordinates"
                                                 {:curve curve})))
                               curve)
                             curves)
        curve-array (float-array curve-values)
        splits-x-array (float-array splits-x)
        splits-y-array (float-array splits-y)
        origin (cond
                 (keyword? origin) {:type origin}
                 (vector? origin) {:type :pivot :x (first origin) :y (second origin)}
                 :else origin)
        origin-type (enum-value (get origin :type :default)
                                {:default 0 :centered 1 :pivot 2 :custom 3}
                                :origin-type)]
    (ffi/with-alloc [curve-p (max 1 (* 4 (count curve-values)))]
      (ffi/with-alloc [split-x-p (max 1 (* 4 (count splits-x)))]
        (ffi/with-alloc [split-y-p (max 1 (* 4 (count splits-y)))]
          (ffi/write-array curve-p :float curve-array)
          (ffi/write-array split-x-p :float splits-x-array)
          (ffi/write-array split-y-p :float splits-y-array)
          (call-with-key key
            (fn [key-p]
              (when-not (zero?
                         (f/atlas-add-shape
                           handle key-p curve-p (count curves) auto-metrics
                           (double bearing-x) (double bearing-y) (double width)
                           (double height) (double advance) num-bands-x num-bands-y
                           split-x-p (count splits-x) split-y-p (count splits-y)
                           origin-type (double (get origin :x 0))
                           (double (get origin :y 0))))
                (native-failure :add-shape!)))))))
  atlas))

(defn shape [atlas key]
  (let [handle (live-handle atlas :shape)]
    (call-with-key key
      (fn [key-p]
        (ffi/with-layout [out f/shape-layout]
          (let [result (f/atlas-shape handle key-p out)]
            (cond
              (neg? result) (native-failure :shape)
              (zero? result) nil
              :else (into {} (map (fn [field]
                                    [field (ffi/read-field out f/shape-layout field)])
                                  shape-fields)))))))))

(defn add-composite! [atlas key layers {:keys [advance] :or {advance 0}}]
  (let [handle (live-handle atlas :add-composite!)]
    (call-with-key key
      (fn [key-p]
        (call-with-layers layers
          (fn [layer-p n]
            (when-not (zero? (f/atlas-add-composite handle key-p layer-p n
                                                    (double advance)))
              (native-failure :add-composite!)))))))
  atlas)

(defn- read-layer [p]
  {:key (read-key p)
   :color (mapv #(ffi/read-field p f/layer-layout [:color %]) [:r :g :b :a])
   :transform (mapv #(ffi/read-field p f/layer-layout [:transform %]) [:x :y :z])
   :scale (ffi/read-field p f/layer-layout :scale)
   :effect-id (ffi/read-field p f/layer-layout :effect-id)
   :effect-param (ffi/read-field p f/layer-layout :effect-param)
   :gradient-id (ffi/read-field p f/layer-layout :gradient-id)
   :pattern-id (ffi/read-field p f/layer-layout :pattern-id)
   :bleed (ffi/read-field p f/layer-layout :bleed)
   :draw-mode (ffi/read-field p f/layer-layout :draw-mode)
   :blend-mode (ffi/read-field p f/layer-layout :blend-mode)})

(def ^:private mask-types
  [:sdf-tile :circle :rect :capsule :arc :arc-band :hexagon :octagon :star])

(defn- read-mask [p]
  (let [has-key (not (zero? (ffi/read-field p f/mask-layout :has-key)))
        type (ffi/read-field p f/mask-layout :type)]
    (when (or has-key (not (zero? type)))
      (cond-> {:type (get mask-types type type)
               :params (mapv #(ffi/read-field p f/mask-layout [:params %]) (range 6))
               :invert (not (zero? (ffi/read-field p f/mask-layout :invert)))}
        has-key (assoc :key (read-key p))))))

(defn composite [atlas key]
  (let [handle (live-handle atlas :composite)]
    (call-with-key key
      (fn [key-p]
        (ffi/with-out [advance-p :float]
          (let [found (f/atlas-composite handle key-p advance-p)]
            (cond
              (neg? found) (native-failure :composite)
              (zero? found) nil
              :else
              (let [mask (ffi/with-layout [mask-p f/mask-layout]
                           (when-not (= 1 (f/atlas-composite-mask handle key-p mask-p))
                             (native-failure :composite))
                           (read-mask mask-p))]
                (ffi/with-out [count-p :uint64]
                  (when-not (= 1 (f/atlas-composite-layers handle key-p 0 0 count-p))
                    (native-failure :composite))
                  (let [n (ffi/read count-p :uint64 0)
                        size (ffi/layout-size f/layer-layout)]
                    (ffi/with-alloc [layer-p (max 1 (* n size))]
                      (when-not (= 1 (f/atlas-composite-layers handle key-p layer-p n count-p))
                        (native-failure :composite))
                      {:key key
                       :advance (ffi/read advance-p :float 0)
                       :mask mask
                       :layers (mapv #(read-layer (+ layer-p (* % size))) (range n))})))))))))))

(defn- compose-layer [parent child]
  (let [child (normalize-layer child)
        parent-scale (:scale parent)
        [px py pz] (:transform parent)
        [cx cy cz] (:transform child)]
    (assoc child
           :transform [(+ px (* parent-scale cx))
                       (+ py (* parent-scale cy))
                       (+ pz (* parent-scale cz))]
           :scale (* parent-scale (:scale child)))))

(defn- flatten-layer [atlas layer seen]
  (let [layer (normalize-layer layer)
        key (:key layer)]
    (if-let [c (composite atlas key)]
      (let [id (key-identity key)]
        (when (contains? seen id)
          (throw (ex-info "composite cycle detected"
                          {:op :layers :cycle (get seen id)})))
        (let [seen (assoc seen id key)]
          (reduce
            (fn [out child]
              (into out (flatten-layer atlas (compose-layer layer child) seen)))
            [] (:layers c))))
      [layer])))

(defn layers [atlas root]
  (live-handle atlas :layers)
  (if (and (vector? root)
           (not (and (= :codepoint (first root)) (<= 2 (count root) 3))))
    (reduce (fn [out layer] (into out (flatten-layer atlas layer {}))) [] root)
    (if (composite atlas root)
      (flatten-layer atlas {:key root} {})
      [])))

(def ^:private texture-formats
  [:rgba32f :rgba16f :rgba16ui :rg16ui :rgba8 :rgb32f :r32f])

(defn texture
  "Returns texture metadata and a byte-array copy by default. With {:copy? false},
  :bytes is a borrowed pointer valid only while the atlas remains alive."
  [atlas kind & [{:keys [copy?] :or {copy? true}}]]
  (let [handle (live-handle atlas :texture)
        kind-id (enum-value kind {:curve 0 :band 1} :texture-kind)]
    (ffi/with-layout [out f/texture-layout]
      (when-not (zero? (f/atlas-texture handle kind-id out))
        (native-failure :texture))
      (let [byte-p (ffi/read-field out f/texture-layout :bytes)
            byte-count (ffi/read-field out f/texture-layout :byte-count)
            format-id (ffi/read-field out f/texture-layout :format)]
        {:bytes (if copy?
                  (if (zero? byte-count) (byte-array 0)
                      (ffi/read-array byte-p byte-count))
                  byte-p)
         :byte-count byte-count
         :width (ffi/read-field out f/texture-layout :width)
         :height (ffi/read-field out f/texture-layout :height)
         :depth (ffi/read-field out f/texture-layout :depth)
         :format (get texture-formats format-id format-id)}))))

(def ^:private mesh-attributes
  {:position {:location 0 :offset 0 :size 3 :type :float}
   :color {:location 1 :offset 12 :size 4 :type :float}
   :em-coord {:location 2 :offset 28 :size 4 :type :float}
   :band-transform {:location 3 :offset 44 :size 4 :type :float}
   :shape-data {:location 4 :offset 60 :size 4 :type :float}
   :effect-id {:location 5 :offset 76 :size 1 :type :float}})

(defn mesh [atlas root]
  (let [handle (live-handle atlas :mesh)
        flattened (layers atlas root)]
    (call-with-layers flattened
      (fn [layer-p n]
        (let [mesh-p (f/mesh-new handle layer-p n)]
          (when (or (nil? mesh-p) (zero? mesh-p))
            (native-failure :mesh))
          (try
            (ffi/with-layout [out f/mesh-layout]
              (when-not (zero? (f/mesh-descriptor mesh-p out))
                (native-failure :mesh))
              (let [vertex-count (ffi/read-field out f/mesh-layout :vertex-count)
                    index-count (ffi/read-field out f/mesh-layout :index-count)
                    vertex-p (ffi/read-field out f/mesh-layout :vertices)
                    index-p (ffi/read-field out f/mesh-layout :indices)]
                {:vertices (if (zero? vertex-count) (float-array 0)
                               (ffi/read-array vertex-p :float (* 20 vertex-count)))
                 :indices (if (zero? index-count) (short-array 0)
                              (ffi/read-array index-p :uint16 index-count))
                 :stride 80
                 :attributes mesh-attributes}))
            (finally
              (when-not (zero? (f/mesh-destroy mesh-p))
                (native-failure :mesh-destroy)))))))))

(defn write! [atlas path]
  (let [handle (live-handle atlas :write!)]
    (when-not (zero? (f/atlas-write handle (str path)))
      (native-failure :write!)))
  atlas)

(defn read-atlas
  "Reads a built upstream .slug or .slugb atlas. No implicit mesh root is assigned;
  callers must pass a composite key or explicit layers to mesh."
  [path]
  (let [handle (f/atlas-read (str path))]
    (when (or (nil? handle) (zero? handle))
      (native-failure :read-atlas))
    (->Atlas handle (atom false))))

(defn- live-path [path op]
  (when (or @(:destroyed? path) (nil? (:handle path)) (zero? (:handle path)))
    (throw (ex-info "path is destroyed" {:op op})))
  (:handle path))

(defn- live-canvas [canvas op]
  (when (or @(:destroyed? canvas) (nil? (:handle canvas)) (zero? (:handle canvas)))
    (throw (ex-info "canvas is destroyed" {:op op})))
  (live-handle (:atlas canvas) op)
  (:handle canvas))

(defn make-path []
  (let [handle (f/path-new)]
    (when (or (nil? handle) (zero? handle))
      (native-failure :make-path))
    (->Path handle (atom false))))

(defmacro with-path [[path] & body]
  `(let [~path (make-path)]
     (try
       ~@body
       (finally
         (destroy! ~path)))))

(declare canvas-command!)

(defn make-canvas [atlas {:keys [key-start auto-metrics]
                          :or {key-start 0x20000000 auto-metrics true}}]
  (let [atlas-handle (live-handle atlas :make-canvas)
        handle (f/canvas-new atlas-handle key-start)]
    (when (or (nil? handle) (zero? handle))
      (native-failure :make-canvas))
    (let [canvas (->Canvas handle atlas (atom false))]
      (when-not auto-metrics
        (canvas-command! canvas [:set-auto-metrics false]))
      canvas)))

(defmacro with-canvas [[canvas atlas opts] & body]
  `(let [~canvas (make-canvas ~atlas ~opts)]
     (try
       ~@body
       (finally
         (destroy! ~canvas)))))

(defn- call-with-floats [values callback]
  (let [values (vec values)
        array (float-array values)]
    (ffi/with-alloc [p (max 1 (* 4 (count values)))]
      (ffi/write-array p :float array)
      (callback p (count values)))))

(defn- call-with-optional-key [key callback]
  (if (some? key)
    (call-with-key key callback)
    (callback 0)))

(def ^:private path-commands
  {:clear 0 :begin-path 0 :save 1 :restore 2 :reset-transform 3
   :set-transform 4 :transform 5 :translate 6 :rotate 7 :scale 8
   :move-to 9 :line-to 10 :quad-to 11 :bezier-to 12 :close 13
   :rect 14 :rounded-rect 15 :circle 16 :ellipse 17 :arc 18 :arc-to 19})

(def ^:private canvas-only-commands
  {:begin-composite 20 :set-advance 21 :set-auto-metrics 22 :set-sdf 23
   :clear-splits 24})

(def ^:private command-arities
  {0 0 1 0 2 0 3 0 4 6 5 6 6 2 7 1 8 2 9 2 10 2 11 4 12 6 13 0
   14 4 15 5 16 3 17 4 18 6 19 5 20 0 21 1 22 1 23 2 24 0})

(defn- command-data [command canvas?]
  (when-not (and (vector? command) (keyword? (first command)))
    (throw (ex-info "path command must be a tagged vector" {:command command})))
  (let [tag (first command)
        values (vec (rest command))
        id (get (if canvas? (merge path-commands canvas-only-commands) path-commands) tag)
        values (if (and (= tag :arc) (= 5 (count values))) (conj values false) values)]
    (when-not id
      (throw (ex-info "unknown path command" {:command command})))
    (when-not (= (get command-arities id) (count values))
      (throw (ex-info "wrong path command argument count" {:command command})))
    [id (mapv #(if (boolean? %) (if % 1 0) %) values)]))

(defn- add-path-command [target source matrix canvas? op]
  (let [target-p ((if canvas? live-canvas live-path) target op)
        source-p (live-path source op)
        result (if matrix
                 (do
                   (when-not (= 6 (count matrix))
                     (throw (ex-info "path transform must have six components" {:matrix matrix})))
                   (call-with-floats matrix
                     (fn [matrix-p _]
                       ((if canvas? f/canvas-add-path f/path-add) target-p source-p matrix-p))))
                 ((if canvas? f/canvas-add-path f/path-add) target-p source-p 0))]
    (when-not (zero? result)
      (native-failure op))
    target))

(defn path-command! [path command]
  (if (= :add-path (first command))
    (do
      (when-not (<= 2 (count command) 3)
        (throw (ex-info "add-path expects a path and optional transform" {:command command})))
      (add-path-command path (second command) (nth command 2 nil) false :path-command!))
    (let [handle (live-path path :path-command!)
          [id values] (command-data command false)]
      (call-with-floats values
        (fn [values-p n]
          (when-not (zero? (f/path-command handle id values-p n))
            (native-failure :path-command!))))
      path)))

(defn canvas-command! [canvas command]
  (cond
    (= :add-path (first command))
    (do
      (when-not (<= 2 (count command) 3)
        (throw (ex-info "add-path expects a path and optional transform" {:command command})))
      (add-path-command canvas (second command) (nth command 2 nil) true :canvas-command!))

    (= :set-splits (first command))
    (let [handle (live-canvas canvas :canvas-command!)]
      (when-not (= 3 (count command))
        (throw (ex-info "set-splits expects x and y sequences" {:command command})))
      (call-with-floats (nth command 1)
        (fn [x-p nx]
          (call-with-floats (nth command 2)
            (fn [y-p ny]
              (when-not (zero? (f/canvas-set-splits handle x-p nx y-p ny))
                (native-failure :canvas-command!))))))
      canvas)

    :else
    (let [handle (live-canvas canvas :canvas-command!)
          [id values] (command-data command true)]
      (call-with-floats values
        (fn [values-p n]
          (when-not (zero? (f/canvas-command handle id values-p n))
            (native-failure :canvas-command!))))
      canvas)))

(defn sample [path t]
  (let [handle (live-path path :sample)]
    (ffi/with-layout [out f/path-sample-layout]
      (when-not (zero? (f/path-sample handle (double t) out))
        (native-failure :sample))
      (into {} (map (fn [field] [field (ffi/read-field out f/path-sample-layout field)])
                    [:x :y :angle :arc-length])))))

(defn arc-length [path]
  (let [handle (live-path path :arc-length)]
    (ffi/with-out [out :float]
      (when-not (zero? (f/path-arc-length handle out))
        (native-failure :arc-length))
      (ffi/read out :float 0))))

(def ^:private stroke-joins {:miter 0 :bevel 1 :round 2})
(def ^:private stroke-caps {:butt 0 :round 1 :square 2})

(defn stroke-path! [path width {:keys [clockwise join cap miter-limit]
                                :or {clockwise false join :miter cap :butt miter-limit 4}}]
  (let [handle (live-path path :stroke-path!)]
    (when-not (zero? (f/path-stroke handle (double width) clockwise
                                    (enum-value join stroke-joins :join)
                                    (enum-value cap stroke-caps :cap)
                                    (double miter-limit)))
      (native-failure :stroke-path!)))
  path)

(defn- normalize-paint [paint op]
  (when-not (map? paint)
    (throw (ex-info "paint must be a map" {:op op :paint paint})))
  (let [present (filterv #(contains? paint %) [:color :gradient-id :pattern-id])]
    (when-not (= 1 (count present))
      (throw (ex-info "paint must contain exactly one of color, gradient-id, or pattern-id"
                      {:op op :paint paint})))
    (let [{:keys [color gradient-id pattern-id]} paint]
      (when (and (contains? paint :color)
                 (not (and (vector? color) (= 4 (count color)))))
        (throw (ex-info "paint color must have four components" {:op op :paint paint})))
      (doseq [[field id] [[:gradient-id gradient-id] [:pattern-id pattern-id]]]
        (when (and (contains? paint field)
                   (not (and (integer? id) (pos? id))))
          (throw (ex-info "paint ID must be a positive integer" {:op op :paint paint}))))
      {:color (mapv double (or color [0 0 0 1]))
       :gradient-id (or gradient-id 0)
       :pattern-id (or pattern-id 0)})))

(defn- write-color! [p color]
  (doseq [[field value] (map vector [:r :g :b :a] color)]
    (ffi/write-field p f/color-layout field value)))

(defn- call-with-color [color callback]
  (ffi/with-layout [p f/color-layout]
    (write-color! p color)
    (callback p)))

(defn- commit-data [{:keys [key scale origin] :or {scale 1 origin {}}}]
  (let [origin (cond
                 (keyword? origin) {:type origin}
                 (vector? origin) {:type :pivot :x (first origin) :y (second origin)}
                 :else origin)]
    {:key key
     :scale (double scale)
     :origin-type (enum-value (get origin :type :default)
                              {:default 0 :centered 1 :pivot 2 :custom 3} :origin-type)
     :origin-x (double (get origin :x 0))
     :origin-y (double (get origin :y 0))}))

(defn fill! [canvas path paint opts]
  (let [handle (live-canvas canvas :fill!)
        path-p (if path (live-path path :fill!) 0)
        {:keys [color gradient-id pattern-id]} (normalize-paint paint :fill!)
        {:keys [key scale origin-type origin-x origin-y]} (commit-data opts)]
    (call-with-optional-key key
      (fn [key-p]
        (call-with-color color
          (fn [color-p]
            (ffi/with-layout [out f/layer-layout]
              (when-not (zero? (f/canvas-fill handle path-p key-p color-p scale
                                              origin-type origin-x origin-y
                                              gradient-id pattern-id out))
                (native-failure :fill!))
              (read-layer out))))))))

(defn stroke! [canvas path width paint opts]
  (let [handle (live-canvas canvas :stroke!)
        path-p (if path (live-path path :stroke!) 0)
        {:keys [color gradient-id pattern-id]} (normalize-paint paint :stroke!)
        {:keys [key scale origin-type origin-x origin-y]} (commit-data opts)
        {:keys [join cap miter-limit] :or {join :miter cap :butt miter-limit 4}} opts]
    (call-with-optional-key key
      (fn [key-p]
        (call-with-color color
          (fn [color-p]
            (ffi/with-layout [out f/layer-layout]
              (when-not (zero? (f/canvas-stroke
                                  handle path-p key-p (double width) color-p scale
                                  origin-type origin-x origin-y
                                  (enum-value join stroke-joins :join)
                                  (enum-value cap stroke-caps :cap)
                                  (double miter-limit) gradient-id pattern-id out))
                (native-failure :stroke!))
              (read-layer out))))))))

(defn define-shape! [canvas path key opts]
  (let [handle (live-canvas canvas :define-shape!)
        path-p (if path (live-path path :define-shape!) 0)
        {:keys [scale origin-type origin-x origin-y]} (commit-data opts)]
    (call-with-key key
      (fn [key-p]
        (when-not (zero? (f/canvas-define-shape handle path-p key-p scale
                                                origin-type origin-x origin-y))
          (native-failure :define-shape!)))))
  canvas)

(def ^:private text-anchors {:baseline 0 :cap-center 1 :cap-top 2 :x-center 3})
(def ^:private text-alignments {:left 0 :center 1 :right 2})

(defn- call-with-codepoints [codepoints callback]
  (let [codepoints (vec codepoints)
        n (count codepoints)]
    (ffi/with-alloc [p (max 1 (* 4 n))]
      (ffi/write-array p :uint32 (int-array codepoints))
      (callback p n))))

(defn- read-font-load-result [p]
  {:loaded (ffi/read-field p f/font-load-layout :loaded)
   :metrics (into {}
              (map (fn [field]
                     [field (ffi/read-field p f/font-load-layout [:metrics field])])
                   [:units-per-em :cap-height-ratio :x-height-ratio
                    :ascender-ratio :descender-ratio]))
   :family (ffi/ptr->string (ffi/read-field p f/font-load-layout :family))
   :style (ffi/ptr->string (ffi/read-field p f/font-load-layout :style))})

(defn- load-font* [atlas path codepoints mode {:keys [uniform mask]
                                               :or {uniform false mask 0}} op]
  (let [handle (live-handle atlas op)]
    (when-not (and (integer? mask) (<= 0 mask 255))
      (throw (ex-info "font mask must be a byte" {:op op :mask mask})))
    (call-with-codepoints codepoints
      (fn [codepoint-p n]
        (ffi/with-layout [out f/font-load-layout]
          (when-not (zero? (f/font-load handle (str path) codepoint-p n mode
                                        (boolean uniform) mask out))
            (native-failure op))
          (read-font-load-result out))))))

(defn load-font! [atlas path codepoints opts]
  (doseq [cp codepoints]
    (when-not (and (integer? cp) (<= 0 cp 0x10ffff))
      (throw (ex-info "font codepoint must be a Unicode scalar value"
                      {:op :load-font! :codepoint cp}))))
  (load-font* atlas path codepoints 0 opts :load-font!))

(defn load-ascii-font! [atlas path opts]
  (load-font* atlas path [] 1 opts :load-ascii-font!))

(defn load-all-font! [atlas path opts]
  (load-font* atlas path [] 2 opts :load-all-font!))

(defn- text-codepoints [text compatibility?]
  (let [text (str text)]
    (if compatibility?
      (mapv #(bit-and (int %) 0xff) (.getBytes text "UTF-8"))
      (mapv int text))))

(defn- text-layout-input [text {:keys [font-size position color metrics align anchor mask
                                       compatibility byte-wise byte-wise?]
                                :or {font-size 1 position [0 0] color [0 0 0 1]
                                     metrics {} align :left anchor :baseline mask 0}}]
  (when-not (and (vector? position) (= 2 (count position))
                 (vector? color) (= 4 (count color)))
    (throw (ex-info "text requires a two-component position and four-component color"
                    {:op :text-layers :position position :color color})))
  (when-not (and (integer? mask) (<= 0 mask 255))
    (throw (ex-info "text mask must be a byte" {:op :text-layers :mask mask})))
  {:codepoints (text-codepoints text (or compatibility byte-wise byte-wise?))
   :font-size (double font-size)
   :x (double (first position))
   :y (double (second position))
   :color (mapv double color)
   :metrics metrics
   :align (enum-value align text-alignments :align)
   :anchor (enum-value anchor text-anchors :anchor)
   :mask mask})

(defn- write-font-metrics! [p metrics]
  (doseq [field [:units-per-em :cap-height-ratio :x-height-ratio
                 :ascender-ratio :descender-ratio]]
    (ffi/write-field p f/font-metrics-layout field (double (get metrics field 0)))))

(defn text-layers [atlas text opts]
  (let [handle (live-handle atlas :text-layers)
        {:keys [codepoints font-size x y color metrics align anchor mask]}
        (text-layout-input text opts)]
    (call-with-codepoints codepoints
      (fn [codepoint-p n]
        (call-with-color color
          (fn [color-p]
            (ffi/with-layout [metrics-p f/font-metrics-layout]
              (write-font-metrics! metrics-p metrics)
              (ffi/with-out [count-p :uint64]
                (when-not (zero? (f/text-layout handle codepoint-p n font-size x y
                                                color-p metrics-p anchor align mask
                                                0 0 count-p))
                  (native-failure :text-layers))
                (let [layer-count (ffi/read count-p :uint64 0)
                      size (ffi/layout-size f/layer-layout)]
                  (ffi/with-alloc [layer-p (max 1 (* layer-count size))]
                    (when-not (zero? (f/text-layout handle codepoint-p n font-size x y
                                                    color-p metrics-p anchor align mask
                                                    layer-p layer-count count-p))
                      (native-failure :text-layers))
                    (mapv #(read-layer (+ layer-p (* % size))) (range layer-count))))))))))))

(defn layout-text! [canvas atlas text opts]
  (let [handle (live-canvas canvas :layout-text!)]
    (live-handle atlas :layout-text!)
    (let [{:keys [font-size x y color metrics align anchor mask]}
          (text-layout-input text opts)]
      (call-with-color color
        (fn [color-p]
          (ffi/with-layout [metrics-p f/font-metrics-layout]
            (write-font-metrics! metrics-p metrics)
            (when-not (zero? (f/canvas-text handle (str text) font-size x y color-p
                                            metrics-p anchor align mask))
              (native-failure :layout-text!))))))
  canvas))

(defn text! [canvas text {:keys [size at color metrics anchor-y align-x mask]
                          :or {size 1 at [0 0] color [0 0 0 1] metrics {}
                               anchor-y :baseline align-x :left mask 0}}]
  (let [handle (live-canvas canvas :text!)]
    (when-not (and (vector? at) (= 2 (count at)) (vector? color) (= 4 (count color)))
      (throw (ex-info "text requires a two-component position and four-component color"
                      {:op :text! :at at :color color})))
    (when-not (and (integer? mask) (<= 0 mask 255))
      (throw (ex-info "text mask must be a byte" {:op :text! :mask mask})))
    (call-with-color (mapv double color)
      (fn [color-p]
        (ffi/with-layout [metrics-p f/font-metrics-layout]
          (doseq [field [:units-per-em :cap-height-ratio :x-height-ratio
                         :ascender-ratio :descender-ratio]]
            (ffi/write-field metrics-p f/font-metrics-layout field
                             (double (get metrics field 0))))
          (when-not (zero? (f/canvas-text handle (str text) (double size)
                                          (double (first at)) (double (second at)) color-p
                                          metrics-p
                                          (enum-value anchor-y text-anchors :anchor-y)
                                          (enum-value align-x text-alignments :align-x)
                                          mask))
            (native-failure :text!))))))
  canvas)

(defn finalize! [canvas key]
  (let [handle (live-canvas canvas :finalize!)]
    (call-with-key key
      (fn [key-p]
        (when-not (zero? (f/canvas-finalize handle key-p))
          (native-failure :finalize!)))))
  canvas)

(defn- normalize-mask-params [mask]
  (let [params (get mask :params (if (= :sdf-tile (:type mask)) [0 0 1] []))]
    (when-not (and (vector? params) (<= (count params) 6))
      (throw (ex-info "mask params must be a vector of at most six numbers" {:mask mask})))
    (into (mapv double params) (repeat (- 6 (count params)) 0.0))))

(defn set-mask! [canvas mask]
  (let [handle (live-canvas canvas :set-mask!)]
    (cond
      (nil? mask)
      (when-not (zero? (f/canvas-set-mask handle 0))
        (native-failure :set-mask!))

      (= :sdf-path (:type mask))
      (ffi/with-layout [out f/mask-layout]
        (when-not (zero? (f/canvas-mask-path handle (double (get mask :range 0.1))
                                             (boolean (:invert mask)) out))
          (native-failure :set-mask!)))

      :else
      (let [type (:type mask)
            type-id (enum-value type (zipmap mask-types (range)) :mask-type)
            params (normalize-mask-params mask)
            key (:key mask)]
        (when (and (= type :sdf-tile) (nil? key))
          (throw (ex-info "SDF tile mask requires a key" {:op :set-mask! :mask mask})))
        (letfn [(write-and-call [key-p]
                  (ffi/with-layout [mask-p f/mask-layout]
                    (doseq [field [:type :codepoint :name]]
                      (ffi/write-field mask-p f/mask-layout [:key field]
                                       (if key-p (ffi/read-field key-p f/key-layout field) 0)))
                    (doseq [i (range 6)]
                      (ffi/write-field mask-p f/mask-layout [:params i] (nth params i)))
                    (ffi/write-field mask-p f/mask-layout :type type-id)
                    (ffi/write-field mask-p f/mask-layout :invert (if (:invert mask) 1 0))
                    (ffi/write-field mask-p f/mask-layout :has-key (if key-p 1 0))
                    (when-not (zero? (f/canvas-set-mask handle mask-p))
                      (native-failure :set-mask!))))]
          (if key (call-with-key key write-and-call) (write-and-call nil))))))
  canvas)

(defn add-gradient! [atlas {:keys [type from to center inner-radius outer-radius
                                   start-angle end-angle stops]
                            :or {type :linear inner-radius 0 outer-radius 1
                                 start-angle 0 end-angle 1}}]
  (let [handle (live-handle atlas :add-gradient!)
        [type-id geometry] (case type
                             :linear [0 (into (vec from) (vec to))]
                             :radial [1 (into (vec center) [inner-radius outer-radius])]
                             :sweep [2 (into (vec center) [start-angle end-angle])]
                             (throw (ex-info "unknown gradient type" {:type type})))
        stops (vec stops)]
    (when-not (= 4 (count geometry))
      (throw (ex-info "gradient geometry is incomplete" {:type type})))
    (when (empty? stops)
      (throw (ex-info "gradient requires at least one stop" {:type type})))
    (doseq [[t color] stops]
      (when-not (and (vector? color) (= 4 (count color)))
        (throw (ex-info "gradient stop color must have four components" {:stop [t color]}))))
    (call-with-floats geometry
      (fn [geometry-p n]
        (let [size (ffi/layout-size f/gradient-stop-layout)]
          (ffi/with-alloc [stops-p (* size (count stops))]
            (doseq [[i [t color]] (map-indexed vector stops)]
              (let [stop-p (+ stops-p (* i size))]
                (ffi/write-field stop-p f/gradient-stop-layout :t (double t))
                (doseq [[field value] (map vector [:r :g :b :a] color)]
                  (ffi/write-field stop-p f/gradient-stop-layout [:color field] (double value)))))
            (let [id (f/atlas-add-gradient handle type-id geometry-p n stops-p (count stops))]
              (when (neg? id) (native-failure :add-gradient!))
              id)))))))

(defn add-pattern! [atlas {:keys [tile cell transform repeat-x repeat-y]
                           :or {transform [1 0 0 1 0 0]
                                repeat-x :repeat repeat-y :repeat}}]
  (let [handle (live-handle atlas :add-pattern!)]
    (when-not (and (vector? cell) (= 4 (count cell))
                   (vector? transform) (= 6 (count transform)))
      (throw (ex-info "pattern requires a four-component cell and six-component transform"
                      {:cell cell :transform transform})))
    (call-with-key tile
      (fn [key-p]
        (call-with-floats cell
          (fn [cell-p _]
            (call-with-floats transform
              (fn [matrix-p _]
                (let [id (f/atlas-add-pattern
                            handle key-p cell-p matrix-p
                            (enum-value repeat-x {:repeat 0 :reflect 1} :repeat-x)
                            (enum-value repeat-y {:repeat 0 :reflect 1} :repeat-y))]
                  (when (neg? id) (native-failure :add-pattern!))
                  id)))))))))
