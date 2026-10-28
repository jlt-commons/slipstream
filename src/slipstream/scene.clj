(ns slipstream.scene
  (:require [slipstream.core :as core]))

(defn- fail [scene-path node message & [data]]
  (throw (ex-info message (merge {:scene-path scene-path :node node} data))))

(defn- options [node scene-path]
  (when-not (and (vector? node) (keyword? (first node)) (map? (second node)))
    (fail scene-path node "scene node must be a tagged vector with an options map"))
  (second node))

(defn- id [value scene-path node required?]
  (when (and required? (nil? value))
    (fail scene-path node "scene definition requires a keyword :id"))
  (when (and (some? value) (not (keyword? value)))
    (fail scene-path node "scene :id must be a keyword"))
  value)

(defn- numeric-vector [value length scene-path node field]
  (when-not (and (vector? value)
                 (or (nil? length) (= length (count value)))
                 (every? number? value))
    (fail scene-path node (str (name field) " must be a numeric vector of length " length)
          {field value}))
  (mapv double value))

(defn- number-value [value scene-path node field]
  (when-not (number? value)
    (fail scene-path node (str (name field) " must be numeric") {field value}))
  (double value))

(defn- integer-value [value minimum maximum scene-path node field]
  (when-not (and (integer? value) (<= minimum value maximum))
    (fail scene-path node (str (name field) " is outside its valid range") {field value}))
  value)

(defn- enum-value [value values scene-path node field]
  (when-not (contains? values value)
    (fail scene-path node (str "unknown " (name field)) {field value}))
  value)

(def ^:private draw-modes #{:visible :hidden :geometry :mask})
(def ^:private blend-modes
  #{:src-over :src :dst :src-in :dst-in :src-out :dst-out :src-atop :dst-atop
    :xor :clear :dst-over :multiply :screen :overlay :darken :lighten
    :color-dodge :color-burn :hard-light :soft-light :difference :exclusion})

(defn- placement [opts scene-path node]
  (when (and (contains? opts :at) (contains? opts :transform))
    (fail scene-path node "use either :at or :transform, not both"))
  (let [value (if (contains? opts :transform) (:transform opts) (get opts :at [0 0]))]
    (when-not (and (vector? value) (<= 2 (count value) 3) (every? number? value))
      (fail scene-path node "placement must have two or three numeric components"))
    {:transform (mapv double (into (vec value) (repeat (- 3 (count value)) 0)))
     :scale (number-value (get opts :scale 1) scene-path node :scale)}))

(defn- layer-data [opts scene-path node]
  (merge
    (placement opts scene-path node)
    {:color (numeric-vector (get opts :color [0 0 0 1]) 4 scene-path node :color)
     :effect-id (integer-value (get opts :effect-id 0) 0 0xffffffff scene-path node :effect-id)
     :effect-param (number-value (get opts :effect-param 0) scene-path node :effect-param)
     :gradient-id (integer-value (get opts :gradient-id 0) 0 0xffffffff scene-path node :gradient-id)
     :pattern-id (integer-value (get opts :pattern-id 0) 0 0xffffffff scene-path node :pattern-id)
     :bleed (number-value (get opts :bleed 0) scene-path node :bleed)
     :draw-mode (enum-value (get opts :draw-mode :visible) draw-modes scene-path node :draw-mode)
     :blend-mode (enum-value (get opts :blend-mode :src-over) blend-modes scene-path node :blend-mode)}))

(defn- generated-key [scene-path]
  (keyword (str "__slipstream-" (pr-str scene-path))))

(def ^:private command-arities
  {:clear 0 :begin-path 0 :save 0 :restore 0 :reset-transform 0
   :set-transform 6 :transform 6 :translate 2 :rotate 1 :scale 2
   :move-to 2 :line-to 2 :quad-to 4 :bezier-to 6 :close 0
   :rect 4 :rounded-rect 5 :circle 3 :ellipse 4 :arc-to 5})

(defn- path-children [children scene-path node]
  (mapv
    (fn [command]
      (when-not (and (vector? command) (keyword? (first command)))
        (fail scene-path node "path child must be a tagged vector" {:command command}))
      (if (= :path (first command))
        (do
          (when-not (and (= 2 (count command)) (keyword? (second command)))
            (fail scene-path node "path reference expects one keyword ID" {:command command}))
          command)
        (let [tag (first command)
              values (vec (rest command))
              arity (if (= :arc tag)
                      (when (<= 5 (count values) 6) (count values))
                      (get command-arities tag))]
          (when-not (and arity (= arity (count values)))
            (fail scene-path node "unknown path command or wrong argument count"
                  {:command command}))
          (when (and (= :arc tag) (= 6 (count values)) (not (boolean? (last values))))
            (fail scene-path node "arc direction must be boolean" {:command command}))
          (doseq [value (if (and (= :arc tag) (= 6 (count values))) (butlast values) values)]
            (when-not (number? value)
              (fail scene-path node "path command arguments must be numeric"
                    {:command command})))
          (into [tag] values))))
    children))

(defn- paint [value scene-path node field]
  (cond
    (keyword? value) value
    :else (numeric-vector value 4 scene-path node field)))

(defn- origin [value scene-path node]
  (cond
    (keyword? value)
    (enum-value value #{:default :centered} scene-path node :origin)

    (vector? value)
    (numeric-vector value 2 scene-path node :origin)

    (map? value)
    (do
      (enum-value (get value :type :default) #{:default :centered :pivot :custom}
                  scene-path node :origin-type)
      (number-value (get value :x 0) scene-path node :origin-x)
      (number-value (get value :y 0) scene-path node :origin-y)
      value)

    :else
    (fail scene-path node "invalid shape origin")))

(defn- normalize-shape [node scene-path define?]
  (let [opts (options node scene-path)
        children (vec (drop 2 node))
        shape-id (id (:id opts) scene-path node false)
        has-fill (contains? opts :fill)
        has-stroke (contains? opts :stroke)]
    (when (and (nil? (:path opts)) (empty? children))
      (fail scene-path node "shape requires a path reference or inline path commands"))
    (when (and has-fill has-stroke)
      (fail scene-path node "fill and stroke are mutually exclusive"))
    (when (and has-fill (or (contains? opts :paint) (contains? opts :color)))
      (fail scene-path node "fill cannot be combined with :paint or :color"))
    (when (and (not has-stroke) (or (contains? opts :paint) (contains? opts :color)))
      (fail scene-path node ":paint and :color require a stroke"))
    (when (some? (:path opts))
      (id (:path opts) scene-path node true))
    (merge
      {:tag (if define? :define-shape :shape)
       :id shape-id
       :key (or shape-id (generated-key scene-path))
       :path (:path opts)
       :commands (path-children children scene-path node)
       :fill (when-not has-stroke (paint (get opts :fill [0 0 0 1]) scene-path node :fill))
       :stroke (when has-stroke (number-value (:stroke opts) scene-path node :stroke))
       :paint (when has-stroke
                (paint (get opts :paint (get opts :color [0 0 0 1])) scene-path node :paint))
       :join (enum-value (get opts :join :miter) #{:miter :bevel :round}
                         scene-path node :join)
       :cap (enum-value (get opts :cap :butt) #{:butt :round :square}
                        scene-path node :cap)
       :miter-limit (number-value (get opts :miter-limit 4) scene-path node :miter-limit)
       :origin (origin (get opts :origin :default) scene-path node)
       :splits-x (numeric-vector (get opts :splits-x []) nil scene-path node :splits-x)
       :splits-y (numeric-vector (get opts :splits-y []) nil scene-path node :splits-y)
       :sdf (let [value (get opts :sdf false)]
              (when-not (boolean? value)
                (fail scene-path node ":sdf must be boolean"))
              value)
       :sdf-range (number-value (get opts :sdf-range 0.1) scene-path node :sdf-range)
       :auto-metrics (let [value (get opts :auto-metrics true)]
                       (when-not (boolean? value)
                         (fail scene-path node ":auto-metrics must be boolean"))
                       value)
       :clockwise (let [value (get opts :clockwise false)]
                    (when-not (boolean? value)
                      (fail scene-path node ":clockwise must be boolean"))
                    value)}
      (layer-data opts scene-path node)
      {::scene-path scene-path ::source node})))

(defn- normalize-font [node scene-path]
  (let [opts (options node scene-path)
        font-id (id (:id opts) scene-path node true)
        modes (cond-> []
                (contains? opts :codepoints) (conj :codepoints)
                (:ascii opts) (conj :ascii)
                (:all opts) (conj :all))]
    (when-not (= 2 (count node))
      (fail scene-path node "font does not accept children"))
    (when-not (string? (:path opts))
      (fail scene-path node "font requires a string :path"))
    (when (> (count modes) 1)
      (fail scene-path node "font codepoint modes are mutually exclusive"))
    (when (and (contains? opts :codepoints) (not (vector? (:codepoints opts))))
      (fail scene-path node "font :codepoints must be a vector"))
    (let [mode (or (first modes) :inferred)
          codepoints (if (= :codepoints mode)
                       (vec (sort (distinct (:codepoints opts))))
                       [])]
      (doseq [cp codepoints]
        (integer-value cp 0 0x10ffff scene-path node :codepoints))
      {:tag :font :id font-id :path (:path opts) :mode mode :codepoints codepoints
       :uniform (let [value (get opts :uniform false)]
                  (when-not (boolean? value)
                    (fail scene-path node ":uniform must be boolean"))
                  value)
       :mask (integer-value (get opts :mask 0) 0 255 scene-path node :mask)
       ::scene-path scene-path ::source node})))

(defn- normalize-gradient [node scene-path]
  (let [opts (options node scene-path)
        tag (first node)
        type ({:linear-gradient :linear :radial-gradient :radial :sweep-gradient :sweep} tag)
        stops (mapv (fn [stop]
                      (when-not (and (vector? stop) (= 2 (count stop)))
                        (fail scene-path node "gradient stop must contain offset and color"
                              {:stop stop}))
                      [(number-value (first stop) scene-path node :stop-offset)
                       (numeric-vector (second stop) 4 scene-path node :stop-color)])
                    (drop 2 node))]
    (when (empty? stops)
      (fail scene-path node "gradient requires at least one stop"))
    (merge
      {:tag tag :type type :id (id (:id opts) scene-path node true) :stops stops
       ::scene-path scene-path ::source node}
      (case type
        :linear {:from (numeric-vector (:from opts) 2 scene-path node :from)
                 :to (numeric-vector (:to opts) 2 scene-path node :to)}
        :radial {:center (numeric-vector (:center opts) 2 scene-path node :center)
                 :inner-radius (number-value (get opts :inner-radius 0) scene-path node :inner-radius)
                 :outer-radius (number-value (get opts :outer-radius 1) scene-path node :outer-radius)}
        :sweep {:center (numeric-vector (:center opts) 2 scene-path node :center)
                :start-angle (number-value (get opts :start-angle 0) scene-path node :start-angle)
                :end-angle (number-value (get opts :end-angle 1) scene-path node :end-angle)}))))

(def ^:private mask-types
  #{:sdf-tile :circle :rect :capsule :arc :arc-band :hexagon :octagon :star})

(defn- normalize-mask [mask scene-path node]
  (when-not (map? mask)
    (fail scene-path node "mask must be a map"))
  (let [type (enum-value (:type mask) mask-types scene-path node :mask-type)
        params (numeric-vector (get mask :params (if (= :sdf-tile type) [0 0 1] []))
                               nil scene-path node :mask-params)
        mask-key (:key mask)]
    (when (> (count params) 6)
      (fail scene-path node "mask accepts at most six parameters"))
    (when (and (some? mask-key) (not (keyword? mask-key)))
      (fail scene-path node "mask key must be a keyword"))
    (when (and (= :sdf-tile type) (nil? mask-key))
      (fail scene-path node "SDF tile mask requires a key"))
    {:type type :params params :invert (boolean (:invert mask)) :key mask-key}))

(declare normalize-node)

(defn- normalize-group [node scene-path]
  (let [has-options (map? (second node))
        opts (if has-options (options node scene-path) {})
        group-id (id (:id opts) scene-path node false)
        children (mapv (fn [index child]
                         (let [normalized (normalize-node child (conj scene-path :children index))]
                           (when-not (contains? #{:shape :define-shape :text :group :composite :layer}
                                                (:tag normalized))
                             (fail scene-path child "invalid composite child"))
                           normalized))
                       (range) (drop (if has-options 2 1) node))]
    (merge
      {:tag (first node) :id group-id :key (or group-id (generated-key scene-path))
       :children children
       :advance (number-value (get opts :advance 0) scene-path node :advance)
       :mask (when (contains? opts :mask) (normalize-mask (:mask opts) scene-path node))}
      (layer-data opts scene-path node)
      {::scene-path scene-path ::source node})))

(defn- normalize-text [node scene-path]
  (let [opts (options node scene-path)
        children (vec (drop 2 node))]
    (when-not (and (= 1 (count children)) (string? (first children)))
      (fail scene-path node "text expects exactly one string child"))
    (id (:font opts) scene-path node true)
    (when (and (contains? opts :fill) (contains? opts :color))
      (fail scene-path node "text :fill and :color are mutually exclusive"))
    (merge
      {:tag :text :font (:font opts) :text (first children)
       :size (number-value (get opts :size 1) scene-path node :size)
       :color (paint (get opts :fill (get opts :color [0 0 0 1])) scene-path node :color)
       :align (enum-value (get opts :align :left) #{:left :center :right}
                          scene-path node :align)
       :anchor (enum-value (get opts :anchor :baseline)
                           #{:baseline :cap-center :cap-top :x-center}
                           scene-path node :anchor)
       :mask (when (contains? opts :mask)
               (integer-value (:mask opts) 0 255 scene-path node :mask))
       :compatibility (get opts :compatibility false)}
      (select-keys (placement opts scene-path node) [:transform])
      {::scene-path scene-path ::source node})))

(defn- normalize-node [node scene-path]
  (when-not (and (vector? node) (keyword? (first node)))
    (fail scene-path node "scene child must be a tagged vector"))
  (let [tag (first node)]
    (case tag
      :font (normalize-font node scene-path)
      :path (let [opts (options node scene-path)]
              {:tag :path :id (id (:id opts) scene-path node true)
               :commands (path-children (drop 2 node) scene-path node)
               ::scene-path scene-path ::source node})
      (:shape :define-shape) (normalize-shape node scene-path (= :define-shape tag))
      (:group :composite) (normalize-group node scene-path)
      :text (normalize-text node scene-path)
      (:linear-gradient :radial-gradient :sweep-gradient) (normalize-gradient node scene-path)
      :pattern (let [opts (options node scene-path)]
                 (when-not (= 2 (count node))
                   (fail scene-path node "pattern does not accept children"))
                 {:tag :pattern :id (id (:id opts) scene-path node true)
                  :tile (id (:tile opts) scene-path node true)
                  :cell (numeric-vector (:cell opts) 4 scene-path node :cell)
                  :transform (numeric-vector (get opts :transform [1 0 0 1 0 0]) 6
                                             scene-path node :pattern-transform)
                  :repeat-x (enum-value (get opts :repeat-x :repeat) #{:repeat :reflect}
                                        scene-path node :repeat-x)
                  :repeat-y (enum-value (get opts :repeat-y :repeat) #{:repeat :reflect}
                                        scene-path node :repeat-y)
                  ::scene-path scene-path ::source node})
      :layer (let [opts (options node scene-path)]
               (when-not (= 2 (count node))
                 (fail scene-path node "layer does not accept children"))
               (merge {:tag :layer :key (id (:key opts) scene-path node true)}
                      (layer-data opts scene-path node)
                      {::scene-path scene-path ::source node}))
      (fail scene-path node "unknown scene node tag"))))

(defn- descendants [node]
  (cons node (mapcat descendants (if (contains? #{:group :composite} (:tag node))
                                   (:children node)
                                   []))))

(defn- path-references [node]
  (keep (fn [command] (when (= :path (first command)) (second command)))
        (:commands node)))

(defn- references [node]
  (case (:tag node)
    :font []
    :path (path-references node)
    (:shape :define-shape) (cond-> (vec (path-references node))
                             (:path node) (conj (:path node))
                             (keyword? (:fill node)) (conj (:fill node))
                             (keyword? (:paint node)) (conj (:paint node)))
    :text (cond-> [(:font node)] (keyword? (:color node)) (conj (:color node)))
    :pattern [(:tile node)]
    :layer [(:key node)]
    (:group :composite) (cond-> (vec (mapcat references (:children node)))
                          (get-in node [:mask :key]) (conj (get-in node [:mask :key])))
    []))

(def ^:private reference-types
  {:path #{:path} :font #{:font} :fill #{:linear-gradient :radial-gradient :sweep-gradient :pattern}
   :paint #{:linear-gradient :radial-gradient :sweep-gradient :pattern}
   :color #{:linear-gradient :radial-gradient :sweep-gradient :pattern}
   :tile #{:shape :define-shape}
   :key #{:shape :define-shape :group :composite}})

(defn- check-reference [definitions node field reference]
  (let [target (get definitions reference)]
    (when-not target
      (fail (::scene-path node) (::source node) "missing scene definition reference"
            {:reference reference :field field}))
    (when-not (contains? (get reference-types field) (:tag target))
      (fail (::scene-path node) (::source node) "scene reference has the wrong definition type"
            {:reference reference :field field}))))

(defn- check-references [definitions node]
  (case (:tag node)
    :path (doseq [reference (path-references node)]
            (check-reference definitions node :path reference))
    (:shape :define-shape) (do
                             (when (:path node)
                               (check-reference definitions node :path (:path node)))
                             (doseq [reference (path-references node)]
                               (check-reference definitions node :path reference))
                             (doseq [field [:fill :paint]]
                               (when (keyword? (get node field))
                                 (check-reference definitions node field (get node field)))))
    :text (do
            (check-reference definitions node :font (:font node))
            (when (keyword? (:color node))
              (check-reference definitions node :color (:color node))))
    :pattern (check-reference definitions node :tile (:tile node))
    :layer (check-reference definitions node :key (:key node))
    (:group :composite) (do
                          (doseq [child (:children node)] (check-references definitions child))
                          (when (get-in node [:mask :key])
                            (check-reference definitions node :tile (get-in node [:mask :key]))))
    nil))

(defn- check-cycles [definitions]
  (let [states (atom {})]
    (letfn [(visit [definition-id]
              (case (get @states definition-id)
                :done nil
                :visiting (let [node (get definitions definition-id)]
                            (fail (::scene-path node) (::source node) "scene definition cycle detected"
                                  {:cycle definition-id}))
                (do
                  (swap! states assoc definition-id :visiting)
                  (doseq [reference (references (get definitions definition-id))]
                    (visit reference))
                  (swap! states assoc definition-id :done))))]
      (doseq [definition-id (keys definitions)]
        (visit definition-id)))))

(defn normalize [scene-data]
  (when-not (and (vector? scene-data) (= :scene (first scene-data)))
    (fail [0] scene-data "scene must be a vector beginning with :scene"))
  (let [opts (if (map? (second scene-data)) (second scene-data) {})
        children-data (drop (if (map? (second scene-data)) 2 1) scene-data)
        texture-width (get opts :texture-width 512)
        curve-precision (get opts :curve-precision 32)]
    (integer-value texture-width 1 0x7fffffff [0] scene-data :texture-width)
    (enum-value curve-precision #{16 32} [0] scene-data :curve-precision)
    (let [parsed (mapv (fn [index node] (normalize-node node [:children index]))
                       (range) children-data)
          codepoints (reduce (fn [result node]
                               (if (= :text (:tag node))
                                 (update result (:font node) into
                                         (if (:compatibility node)
                                           (map #(bit-and (int %) 0xff)
                                                (.getBytes (:text node) "UTF-8"))
                                           (map int (:text node))))
                                 result))
                             {} (mapcat descendants parsed))
          parsed (mapv (fn [node]
                         (if (and (= :font (:tag node)) (= :inferred (:mode node)))
                           (assoc node :codepoints
                                  (vec (sort (distinct (get codepoints (:id node) [])))))
                           node))
                       parsed)
          normalized {:tag :scene
                      :options {:texture-width texture-width :curve-precision curve-precision}
                      :children parsed}
          nodes (mapcat descendants parsed)
          definitions (reduce (fn [result node]
                                (if-let [definition-id (:id node)]
                                  (do
                                    (when (contains? result definition-id)
                                      (fail (::scene-path node) (::source node)
                                            "duplicate scene definition ID" {:id definition-id}))
                                    (assoc result definition-id node))
                                  result))
                              {} nodes)]
      (doseq [node nodes]
        (check-references definitions node))
      (check-cycles definitions)
      normalized)))

(declare realize)

(defn- definition [context definition-id]
  (get (:definitions context) definition-id))

(defn- realize-reference [context definition-id]
  (realize context (definition context definition-id)))

(defn- tracked-path [context]
  (let [path (core/make-path)]
    (swap! (:paths context) conj path)
    path))

(defn- write-path! [context path node]
  (doseq [command (:commands node)]
    (if (= :path (first command))
      (core/path-command! path [:add-path (realize-reference context (second command))])
      (core/path-command! path command)))
  path)

(defn- shape-path [context node]
  (if (and (:path node) (empty? (:commands node)))
    (realize-reference context (:path node))
    (let [path (tracked-path context)]
      (when (:path node)
        (core/path-command! path [:add-path (realize-reference context (:path node))]))
      (write-path! context path node))))

(defn- native-paint [context value]
  (if (keyword? value)
    (let [node (definition context value)
          paint-id (realize-reference context value)]
      (if (= :pattern (:tag node))
        {:pattern-id paint-id}
        {:gradient-id paint-id}))
    {:color value}))

(defn- layer-overrides [node]
  (select-keys node [:transform :scale :effect-id :effect-param :bleed :draw-mode :blend-mode]))

(defn- realize-shape [context node define?]
  (let [path (shape-path context node)]
    (core/with-canvas [canvas (:atlas context) {:auto-metrics (:auto-metrics node)}]
      (when (:sdf node)
        (core/canvas-command! canvas [:set-sdf true (:sdf-range node)]))
      (when (or (seq (:splits-x node)) (seq (:splits-y node)))
        (core/canvas-command! canvas [:set-splits (:splits-x node) (:splits-y node)]))
      (if define?
        (do
          (core/define-shape! canvas path (:key node)
                              {:scale (:scale node) :origin (:origin node)})
          nil)
        (let [paint-value (if (:stroke node) (:paint node) (:fill node))
              paint (native-paint context paint-value)
              opts {:key (:key node) :scale (:scale node) :origin (:origin node)
                    :join (:join node) :cap (:cap node) :miter-limit (:miter-limit node)
                    :clockwise (:clockwise node)}
              layer (if (:stroke node)
                      (core/stroke! canvas path (:stroke node) paint opts)
                      (core/fill! canvas path paint opts))]
          (merge layer (layer-overrides node)))))))

(defn- realize-text [context node]
  (let [font (realize-reference context (:font node))
        paint (native-paint context (:color node))
        color (get paint :color [0 0 0 1])
        layers (core/text-layers (:atlas context) (:text node)
                                 {:font-size (:size node)
                                  :position (vec (take 2 (:transform node)))
                                  :color color
                                  :metrics (:metrics font)
                                  :align (:align node)
                                  :anchor (:anchor node)
                                  :mask (or (:mask node) (:mask font))
                                  :compatibility (:compatibility node)})]
    (mapv #(merge % (select-keys paint [:gradient-id :pattern-id])) layers)))

(defn- compile-child [context node]
  (case (:tag node)
    :text (realize-text context node)
    :define-shape (do (realize context node) [])
    (:shape :group :composite :layer) [(realize context node)]
    []))

(defn- realize-group [context node]
  (let [layers (vec (mapcat #(compile-child context %) (:children node)))]
    (core/add-composite! (:atlas context) (:key node) layers
                         {:advance (:advance node) :mask (:mask node)})
    (merge {:key (:key node)} (layer-data node (::scene-path node) (::source node)))))

(defn- realize [context node]
  (let [cache-key (when-not (= :layer (:tag node))
                    (or (:id node) (:key node)))
        cache (:cache context)]
    (if (and cache-key (contains? @cache cache-key))
      (get @cache cache-key)
      (let [value (case (:tag node)
                    :font (let [{:keys [metrics]}
                                (case (:mode node)
                                  :ascii (core/load-ascii-font! (:atlas context) (:path node) node)
                                  :all (core/load-all-font! (:atlas context) (:path node) node)
                                  (core/load-font! (:atlas context) (:path node) (:codepoints node) node))]
                            {:metrics metrics :mask (:mask node)})
                    :path (write-path! context (tracked-path context) node)
                    :shape (realize-shape context node false)
                    :define-shape (realize-shape context node true)
                    :text (realize-text context node)
                    :group (realize-group context node)
                    :composite (realize-group context node)
                    :layer (do
                             (realize-reference context (:key node))
                             (merge {:key (:key node)}
                                    (layer-data node (::scene-path node) (::source node))))
                    :pattern (do
                               (realize-reference context (:tile node))
                               (core/add-pattern! (:atlas context) node))
                    (:linear-gradient :radial-gradient :sweep-gradient)
                    (core/add-gradient! (:atlas context) node))]
        (when cache-key
          (swap! cache assoc cache-key value))
        value))))

(defn- root-node? [node]
  (or (contains? #{:shape :text :layer} (:tag node))
      (and (contains? #{:group :composite} (:tag node)) (nil? (:id node)))))

(defn- destroy-temporary-paths [paths initial-error]
  (reduce (fn [error path]
            (try
              (core/destroy! path)
              error
              (catch Throwable cleanup-error
                (or error cleanup-error))))
          initial-error paths))

(defn compile [scene-data]
  (let [normalized (normalize scene-data)
        definitions (reduce (fn [result node]
                              (if-let [definition-id (:id node)]
                                (assoc result definition-id node)
                                result))
                            {} (mapcat descendants (:children normalized)))
        atlas (core/make-atlas (:options normalized))
        context {:atlas atlas :definitions definitions :cache (atom {}) :paths (atom [])}
        outcome (try
                  (doseq [node (mapcat descendants (:children normalized))
                          :when (:id node)]
                    (realize context node))
                  (let [layers (vec (mapcat (fn [node]
                                              (if (root-node? node)
                                                (compile-child context node)
                                                []))
                                            (:children normalized)))]
                    (core/build! atlas)
                    {:scene (core/->Scene atlas normalized layers (atom false))})
                  (catch Throwable error
                    {:error error}))
        error (destroy-temporary-paths @(:paths context) (:error outcome))]
    (when error
      (try
        (core/destroy! atlas)
        (catch Throwable _)))
    (if error
      (throw error)
      (:scene outcome))))

(defmacro with-scene [binding & body]
  `(core/with-scene ~binding ~@body))
