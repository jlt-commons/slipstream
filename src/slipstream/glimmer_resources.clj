(ns slipstream.glimmer-resources
  (:require [slipstream.core :as core]))

(defrecord CPUResource [atlas root mesh cache-key ref-count])

(defonce ^:private cpu-cache (atom {}))

(defn acquire-cpu!
  [cache-key build]
  (locking cpu-cache
    (if-let [resource (get @cpu-cache cache-key)]
      (do
        (swap! (:ref-count resource) inc)
        resource)
      (let [resource (build)]
        (swap! cpu-cache assoc cache-key resource)
        resource))))

(defn- release-cpu!
  [resource]
  (when resource
    (locking cpu-cache
      (when (pos? @(:ref-count resource))
        (if (= 1 @(:ref-count resource))
          (do
            (swap! (:ref-count resource) dec)
            (swap! cpu-cache dissoc (:cache-key resource))
            (core/destroy! (:atlas resource)))
          (swap! (:ref-count resource) dec)))))
  nil)

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
  (if (instance? CPUResource resource)
    (release-cpu! resource)
    (when (and resource (compare-and-set! (:released? resource) false true))
      ((:release! (:factory resource)) (:handle resource))))
  nil)

(defn update-layers!
  [resource props]
  (when (and resource (not (instance? CPUResource resource))
             (not @(:released? resource)))
    ((:update-layers! (:factory resource)) (:handle resource) props))
  nil)

(defn dispose-tree!
  [widget]
  (when (and widget (not (:disposed? @widget)))
    (swap! widget assoc :disposed? true)
    (doseq [child (:children @widget)]
      (dispose-tree! child))
    (release! (:resource @widget))
    (doseq [resource (:resources @widget)]
      (release! resource))
    (swap! widget assoc :resource nil :resources nil))
  nil)
