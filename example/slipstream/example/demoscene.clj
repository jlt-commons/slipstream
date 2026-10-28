(ns slipstream.example.demoscene
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [jolt.nrepl :as nrepl]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]))

(def ^:private rich-middleware
  '[nrepl.middleware/default-middleware cider.nrepl/cider-middleware])

(defonce state
  (r/atom {:time 0.0
           :transition 0.0
           :mouse [0.5 0.5]
           :density 1.0
           :speed 1.0
           :left false
           :right false
           :middle false
           :space false
           :r false
           :vectors {}}))

(defonce ^:private lifecycle (atom nil))

(defn app [font-path]
  (let [{:keys [transition density speed]} @state]
    [:group {}
     [:shape {:path [[:rect 0 0 1 1]]
              :fill [0.015 0.02 0.05 1]}]
     [:text {:font font-path
             :text (str "SLIPSTREAM  transition " transition
                        "  density " density "  speed " speed)
             :at [0.04 0.08 0]
             :size 0.035
             :color [0.7 0.9 1 1]}]]))

(defn- stop-server! [{:keys [server-stopped? stop-server]}]
  (when (compare-and-set! server-stopped? false true)
    (stop-server))
  nil)

(defn- request-native-close! []
  (when-let [context (sg/current-context)]
    (renderer/request-close! context)))

(defn stop! []
  (when-let [{:keys [close-requested?]} @lifecycle]
    (when (compare-and-set! close-requested? false true)
      (ui/on-gui request-native-close!)))
  nil)

(defn- cleanup-run! [run primary-error]
  (try
    (stop-server! run)
    (catch Throwable cleanup-error
      (if-let [primary @primary-error]
        (binding [*out* *err*]
          (println (str "Demoscene cleanup failed after " (ex-message primary)
                        ": " (ex-message cleanup-error))
                   (ex-data cleanup-error)))
        (throw cleanup-error)))
    (finally
      (reset! lifecycle nil))))

(defn- run-with-cleanup [run work]
  (let [primary-error (atom nil)]
    (try
      (work)
      (catch Throwable error
        (reset! primary-error error)
        (throw error))
      (finally
        (cleanup-run! run primary-error)))))

(defn start!
  [& [{:keys [font-path port width height bounds capture-dir max-frames auto-quit-ms]}]]
  (when-not font-path
    (throw (ex-info "A TTF/OTF font path is required" {:option :font-path})))
  (let [run (locking lifecycle
              (if-let [existing @lifecycle]
                existing
                (let [stop-server (nrepl/start (if (nil? port) 7888 port)
                                               rich-middleware {})
                      actual-port (or (:port (meta stop-server)) port)
                      run {:port actual-port
                           :stop-server stop-server
                           :server-stopped? (atom false)
                           :close-requested? (atom false)}]
                  (try
                    (reset! lifecycle run)
                    (assoc run :owner? true)
                    (catch Throwable error
                      (cleanup-run! run (atom error))
                      (throw error))))))]
    (if-not (:owner? run)
      {:port (:port run)}
      (run-with-cleanup
        run
        (fn []
          (println (str "Demoscene nREPL: 127.0.0.1:" (:port run)
                        " — connect your editor"))
          (ui/run #(app font-path)
                  :title "Slipstream demoscene"
                  :width (or width 960)
                  :height (or height 640)
                  :bounds bounds
                  :capture-dir capture-dir
                  :on-start (fn []
                              (when @(:close-requested? run)
                                (request-native-close!)))
                  :max-frames max-frames
                  :auto-quit-ms auto-quit-ms)
          {:port (:port run)})))))

(def usage-text
  (str "Usage: slipstream.example.demoscene <font.ttf> [--port N] "
       "[--capture-dir PATH] [--max-frames N] [--auto-quit-ms N]\n"))

(defn- option-integer [option value]
  (or (some-> value parse-long)
      (throw (ex-info (str option " requires an integer") {:option option :value value}))))

(defn parse-args [args]
  (if (empty? args)
    {:error usage-text}
    (loop [remaining (vec (rest args))
           result {:font-path (first args)
                   :port 7888
                   :capture-dir nil
                   :max-frames nil
                   :auto-quit-ms nil}]
      (if (empty? remaining)
        result
        (let [[option value] remaining]
          (case option
            "--port" (recur (vec (drop 2 remaining))
                            (assoc result :port (option-integer option value)))
            "--capture-dir" (if value
                              (recur (vec (drop 2 remaining))
                                     (assoc result :capture-dir value))
                              {:error (str "--capture-dir requires a path\n" usage-text)})
            "--max-frames" (recur (vec (drop 2 remaining))
                                  (assoc result :max-frames (option-integer option value)))
            "--auto-quit-ms" (recur (vec (drop 2 remaining))
                                    (assoc result :auto-quit-ms (option-integer option value)))
            {:error (str "Unknown option: " option "\n" usage-text)}))))))

(defn- exit [code]
  (System/exit code))

(defn -main [& args]
  (let [{:keys [error] :as options} (parse-args args)]
    (if error
      (do
        (binding [*out* *err*] (print error))
        (exit 1))
      (try
        (start! options)
        (exit 0)
        (catch Throwable error
          (binding [*out* *err*]
            (println (ex-message error) (ex-data error)))
          (exit 1))))))
