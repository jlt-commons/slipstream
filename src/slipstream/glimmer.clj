(ns slipstream.glimmer
  (:require [glimmer.backend :as b]
            [slipstream.glimmer-resources :as resources]))

(def ^:private default-factory
  {:create! (fn [_ cache-key props]
              {:cache-key cache-key :props props})
   :release! (fn [_] nil)
   :update-layers! (fn [_ _] nil)})

(def ^:dynamic *factory* default-factory)
(def ^:dynamic *context* nil)

(def ^:private layer-props
  #{:at :transform :scale :color :visible :visibility :opacity
    :effect-id :effect-param :gradient-id :pattern-id :bleed
    :draw-mode :blend-mode})

(defn- layer-prop? [k]
  (or (contains? layer-props k)
      (.startsWith (name k) "on-")))

(defn- cache-key [tag props]
  [tag (into {} (remove (comp layer-prop? key) props))])

(defn- create! [tag props]
  (atom {:tag tag
         :props props
         :children []
         :context *context*
         :resource (resources/acquire! *factory* *context* (cache-key tag props) props)
         :disposed? false}))

(defn- apply-props! [tag widget props]
  (when-not (:disposed? @widget)
    (let [{old-props :props resource :resource context :context} @widget
          old-key (cache-key tag old-props)
          new-key (cache-key tag props)]
      (cond
        (= old-props props)
        nil

        (= old-key new-key)
        (do
          (resources/update-layers! resource props)
          (swap! widget assoc :props props))

        :else
        (let [replacement (resources/acquire! (:factory resource) context new-key props)]
          (swap! widget assoc :props props :resource replacement)
          (resources/release! resource)))))
  nil)

(defn- index-of [children child]
  (first (keep-indexed (fn [i candidate]
                         (when (identical? candidate child) i))
                       children)))

(defn- append-child! [_parent-tag parent child]
  (swap! parent update :children conj child)
  nil)

(defn- remove-child! [_parent-tag parent child]
  (swap! parent update :children
         (fn [children]
           (vec (remove #(identical? % child) children))))
  (resources/dispose-tree! child)
  nil)

(defn- replace-child! [_parent-tag parent old-child new-child]
  (swap! parent update :children
         (fn [children]
           (if-let [i (index-of children old-child)]
             (assoc children i new-child)
             (conj children new-child))))
  (resources/dispose-tree! old-child)
  nil)

(defn- reorder-child! [_parent-tag parent child sibling]
  (swap! parent update :children
         (fn [children]
           (let [without (vec (remove #(identical? % child) children))
                 position (if sibling
                            (if-let [i (index-of without sibling)] (inc i) (count without))
                            0)]
             (vec (concat (subvec without 0 position)
                          [child]
                          (subvec without position))))))
  nil)

(defn- schedule [work]
  (work)
  nil)

(defn- run-lifecycle [opts {:keys [mount! unmount!]}]
  (let [scene (create! :scene opts)]
    (try
      (mount! scene :scene)
      (finally
        (try
          (unmount!)
          (finally
            (resources/dispose-tree! scene)))))))

(b/register!
 {:name :slipstream
  :create! create!
  :apply-props! apply-props!
  :append-child! append-child!
  :remove-child! remove-child!
  :replace-child! replace-child!
  :reorder-child! reorder-child!
  :dispose! resources/dispose-tree!
  :schedule schedule
  :run-lifecycle run-lifecycle})
