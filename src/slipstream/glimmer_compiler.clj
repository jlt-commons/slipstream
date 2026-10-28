(ns slipstream.glimmer-compiler
  (:require [slipstream.core :as core]
            [slipstream.glimmer-resources :as resources]))

(def ^:private layer-only-keys
  #{:id :key :tag :children :at :transform :color :visible :visibility :opacity
    :effect-id :effect-param :gradient-id :pattern-id :bleed :draw-mode :blend-mode})

(defn- layer-only-prop? [k]
  (or (contains? layer-only-keys k)
      (.startsWith (name k) "on-")))

(defn- immutable [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(immutable k) (immutable v)]) value))
    (vector? value) (mapv immutable value)
    (set? value) (into #{} (map immutable value))
    (sequential? value) (mapv immutable value)
    :else value))

(defn- node-props [node]
  (if (vector? node)
    (assoc (second node) :tag (first node) :commands (vec (drop 2 node)))
    node))

(defn- flat-color? [value]
  (and (vector? value) (= 4 (count value)) (every? number? value)))

(defn- canonical-paint [paint]
  (if (flat-color? paint) :flat-color paint))

(defn geometry-key
  [node]
  (let [props (node-props node)
        native (into {} (remove (comp layer-only-prop? key) props))]
    (immutable
      (cond-> native
        (:paints native) (update :paints
                                 (fn [paints]
                                   (into {} (map (fn [[id paint]]
                                                   [id (canonical-paint paint)])
                                                 paints))))
        (contains? native :fill) (update :fill canonical-paint)
        (contains? native :paint) (update :paint canonical-paint)))))

(defn- write-path! [path commands]
  (doseq [command commands]
    (core/path-command! path command))
  path)

(defn- shape-commands [node]
  (or (:commands node)
      (when (sequential? (:path node)) (:path node))
      []))

(defn- define-pattern-tile! [canvas paint]
  (when-let [commands (:tile-path paint)]
    (core/with-path [path]
      (write-path! path commands)
      (core/define-shape! canvas path (:tile paint) {:scale 1 :origin :default}))))

(defn- native-paint [atlas canvas node paint]
  (cond
    (keyword? paint)
    (if-let [definition (get-in node [:paints paint])]
      (native-paint atlas canvas node definition)
      (throw (ex-info "shape paint reference requires a local paint definition"
                      {:paint paint})))

    (and (map? paint) (= :pattern (:type paint)))
    (do
      (define-pattern-tile! canvas paint)
      {:pattern-id (core/add-pattern! atlas paint)})

    (map? paint)
    {:gradient-id (core/add-gradient! atlas paint)}

    :else
    {:color (or paint [0 0 0 1])}))

(defn- destroy-after-failure [atlas error]
  (try
    (core/destroy! atlas)
    (catch Throwable _))
  (throw error))

(defn- build-shape [node cache-key]
  (let [atlas (core/make-atlas (select-keys node [:texture-width :curve-precision]))]
    (try
      (let [root (core/with-path [path]
                   (write-path! path (shape-commands node))
                   (core/with-canvas [canvas atlas {:auto-metrics (get node :auto-metrics true)}]
                     (when (:sdf node)
                       (core/canvas-command! canvas [:set-sdf true (get node :sdf-range 0.1)]))
                     (when (or (seq (:splits-x node)) (seq (:splits-y node)))
                       (core/canvas-command! canvas [:set-splits (get node :splits-x [])
                                                 (get node :splits-y [])]))
                     (let [stroke? (some? (:stroke node))
                           paint-value (if stroke?
                                         (get node :paint (get node :color [0 0 0 1]))
                                         (get node :fill [0 0 0 1]))
                           paint (native-paint atlas canvas node paint-value)
                           opts {:key :node :scale (get node :scale 1)
                                 :origin (get node :origin :default)
                                 :join (get node :join :miter) :cap (get node :cap :butt)
                                 :miter-limit (get node :miter-limit 4)
                                 :clockwise (get node :clockwise false)}]
                       (if stroke?
                         (core/stroke! canvas path (:stroke node) paint opts)
                         (core/fill! canvas path paint opts)))))]
        (core/build! atlas)
        (resources/->CPUResource atlas root (core/mesh atlas [root]) cache-key (atom 1)))
      (catch Throwable error
        (destroy-after-failure atlas error)))))

(defn compile-shape
  [node]
  (let [node (node-props node)
        cache-key [:shape (geometry-key node)]]
    (resources/acquire-cpu! cache-key #(build-shape node cache-key))))

(defn- font-options [font opts]
  (merge (if (map? font) (select-keys font [:uniform :mask]) {})
         (select-keys opts [:texture-width :curve-precision :uniform :mask])))

(defn- font-path [font]
  (if (map? font) (:path font) font))

(defn- glyph-cache-key [font codepoint opts]
  [:glyph (immutable {:font (font-path font) :codepoint codepoint
                      :options (font-options font opts)})])

(defn- codepoint-string [codepoint]
  (String. (Character/toChars codepoint)))

(defn- build-glyph [font codepoint opts cache-key]
  (let [options (font-options font opts)
        atlas (core/make-atlas options)]
    (try
      (let [{:keys [metrics]} (core/load-font! atlas (font-path font) [codepoint] options)
            root (first (core/text-layers atlas (codepoint-string codepoint)
                                          {:font-size 1 :position [0 0]
                                           :color [0 0 0 1] :metrics metrics
                                           :mask (get options :mask 0)}))]
        (core/build! atlas)
        (resources/->CPUResource atlas root (core/mesh atlas [root]) cache-key (atom 1)))
      (catch Throwable error
        (destroy-after-failure atlas error)))))

(defn compile-glyph
  [font codepoint opts]
  (let [cache-key (glyph-cache-key font codepoint opts)]
    (resources/acquire-cpu! cache-key #(build-glyph font codepoint opts cache-key))))

(defn- text-codepoints [props]
  (let [text (str (:text props))]
    (if (or (:compatibility props) (:byte-wise props) (:byte-wise? props))
      (mapv #(bit-and (int %) 0xff) (.getBytes text "UTF-8"))
      (mapv int text))))

(defn- update-text! [widget _old-props new-props]
  (let [old-resources (vec (:resources @widget))
        pools (reduce (fn [result resource]
                        (update result (:cache-key resource) (fnil conj []) resource))
                      {} old-resources)
        new-codepoints (text-codepoints new-props)
        new-keys (mapv #(glyph-cache-key (:font new-props) % new-props)
                       new-codepoints)
        acquired (atom [])
        [resources* remaining]
        (try
          (reduce
            (fn [[out pools] [cache-key codepoint]]
              (if-let [resource (first (get pools cache-key))]
                [(conj out resource) (update pools cache-key rest)]
                (let [resource (compile-glyph (:font new-props) codepoint new-props)]
                  (swap! acquired conj resource)
                  [(conj out resource) pools])))
            [[] pools] (mapv vector new-keys new-codepoints))
          (catch Throwable error
            (doseq [resource @acquired]
              (resources/release! resource))
            (throw error)))
        released (vec (mapcat val remaining))]
    (swap! widget assoc :props new-props :resources resources*)
    (doseq [resource released]
      (resources/release! resource))
    {:acquired @acquired :released released}))

(defn- update-shape! [widget old-props new-props]
  (if (= (geometry-key old-props) (geometry-key new-props))
    (do
      (swap! widget assoc :props new-props)
      {:acquired [] :released []})
    (let [old-resource (:resource @widget)
          replacement (compile-shape new-props)]
      (swap! widget assoc :props new-props :resource replacement)
      (resources/release! old-resource)
      {:acquired [replacement] :released (if old-resource [old-resource] [])})))

(defn update-node!
  [widget old-props new-props]
  (case (:tag @widget)
    :text (update-text! widget old-props new-props)
    (:shape :path) (update-shape! widget old-props new-props)
    (do
      (swap! widget assoc :props new-props)
      {:acquired [] :released []})))
