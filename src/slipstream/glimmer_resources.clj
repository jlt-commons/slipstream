(ns slipstream.glimmer-resources)

(defn acquire!
  [factory context cache-key props]
  (let [handle ((:create! factory) context cache-key props)]
    {:factory factory
     :context context
     :cache-key cache-key
     :props props
     :handle handle
     :released? (atom false)}))

(defn release!
  [resource]
  (when (and resource (compare-and-set! (:released? resource) false true))
    ((:release! (:factory resource)) (:handle resource)))
  nil)

(defn update-layers!
  [resource props]
  (when-not @(:released? resource)
    ((:update-layers! (:factory resource)) (:handle resource) props))
  nil)

(defn dispose-tree!
  [widget]
  (when (and widget (not (:disposed? @widget)))
    (swap! widget assoc :disposed? true)
    (doseq [child (:children @widget)]
      (dispose-tree! child))
    (release! (:resource @widget))
    (swap! widget assoc :resource nil))
  nil)
