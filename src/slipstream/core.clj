(ns slipstream.core
  (:require [jolt.ffi :as ffi]
            [slipstream.ffi :as f]))

(defrecord Atlas [handle destroyed?])

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

(defn destroy! [^Atlas atlas]
  (when (compare-and-set! (:destroyed? atlas) false true)
    (when-not (zero? (f/atlas-destroy (:handle atlas)))
      (native-failure :destroy!)))
  atlas)

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
      (when-not (and (integer? cp) (<= 0 cp 0x1fffff)
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
            cp (bit-and packed 0x1fffff)
            mask (bit-shift-right (bit-and packed 0x1fe00000) 21)]
        (if (zero? mask) [:codepoint cp] [:codepoint cp mask]))
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
                     :layers (mapv #(read-layer (+ layer-p (* % size))) (range n))}))))))))))

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
