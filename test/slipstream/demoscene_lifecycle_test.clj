(ns slipstream.demoscene-lifecycle-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [glimmer.core :as ui]
            [jolt.nrepl :as jolt-nrepl]
            [nrepl.core :as nrepl]
            [slipstream.example.demoscene :as demo]
            [slipstream.example.demoscene-shader :as visuals]
            [slipstream.glimmer :as sg]
            [slipstream.glimmer-renderer :as renderer]))

(def ^:private test-font
  (or (System/getenv "SLIPSTREAM_TEST_FONT")
      (throw (ex-info "Set SLIPSTREAM_TEST_FONT for demoscene lifecycle tests" {}))))

(def ^:private rich-middleware
  '[nrepl.middleware/default-middleware cider.nrepl/cider-middleware])

(defn- fake-stop [events port]
  (with-meta
    (fn [] (swap! events conj :stop))
    {:port port :port-file nil}))

(defn- with-clean-lifecycle [test-fn]
  (let [lifecycle (var-get (ns-resolve 'slipstream.example.demoscene 'lifecycle))]
    (reset! lifecycle nil)
    (try
      (test-fn)
      (finally
        (reset! lifecycle nil)))))

(use-fixtures :each with-clean-lifecycle)

(deftest start-is-idempotent-starts-nrepl-first-and-has-unlimited-production-defaults
  (let [events (atom [])
        starts (atom 0)
        caller (Thread/currentThread)
        persistent-state demo/state]
    (is (nil? (demo/stop!)) "stop before start is harmless")
    (with-redefs [jolt-nrepl/start
                  (fn [port middleware _opts]
                    (swap! starts inc)
                    (is (= 7888 port))
                    (is (= rich-middleware middleware))
                    (swap! events conj :server)
                    (fake-stop events 7888))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (swap! events conj :ui)
                    (is (fn? root-fn))
                    (is (identical? caller (Thread/currentThread)))
                    (is (nil? (:max-frames opts)))
                    (is (nil? (:auto-quit-ms opts)))
                    (is (= {:port 7888} (demo/start! {:font-path test-font})))
                    (is (= 1 @starts) "a nested start reuses the running application"))]
      (is (= {:port 7888} (demo/start! {:font-path test-font}))))
    (is (= [:server :ui :stop] @events))
    (is (nil? (demo/stop!)))
    (is (= [:server :ui :stop] @events) "repeated stop does not stop the server twice")
    (is (identical? persistent-state demo/state))))

(deftest start-reports-the-os-assigned-port-from-stop-metadata
  (let [events (atom [])]
    (with-redefs [jolt-nrepl/start
                  (fn [port _middleware _opts]
                    (is (zero? port))
                    (fake-stop events 45678))
                  ui/run (fn [root-fn & _]
                           (is (re-find #"NREPL 127.0.0.1:45678"
                                        (pr-str (root-fn)))))]
      (let [output (with-out-str
                     (is (= {:port 45678}
                            (demo/start! {:font-path test-font :port 0}))))]
        (is (re-find #"45678" output))))
    (is (= [:stop] @events))))

(deftest first-frame-uses-zero-dt-and-complete-bounds-for-scene-and-cursor
  (let [events (atom [])
        updates (atom [])
        custom-bounds [-2 4 3 7]]
    (with-redefs [jolt-nrepl/start (fn [& _] (fake-stop events 45678))
                  sg/current-context (constantly :native-context)
                  renderer/frame-state (fn [context]
                                         (is (= :native-context context))
                                         {})
                  renderer/input-snapshot (fn [_] {})
                  renderer/cursor->bounds
                  (fn [_ bounds]
                    (is (= custom-bounds bounds))
                    [2.5 6.0])
                  visuals/update-state
                  (fn [state dt input]
                    (swap! updates conj {:dt dt :input input})
                    state)
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (let [background (some (fn [node]
                                             (when (and (vector? node)
                                                        (= :shape (first node))
                                                        (= :hybrid-background
                                                           (get-in node [1 :id])))
                                               (second node)))
                                           (tree-seq vector? seq (root-fn)))]
                      (is (= [[:rect 0.0 0.0 6 4]] (:path background)))
                      (is (= [-2 3 0.0] (:at background)))
                      (is (= custom-bounds
                             (get-in background [:effect :uniforms :bounds :value]))))
                    ((:after-render opts)))]
      (demo/start! {:font-path test-font :port 0 :bounds custom-bounds}))
    (is (= 1 (count @updates)))
    (is (= 0.0 (:dt (first @updates))))
    (is (= [2.5 6.0] (get-in @updates [0 :input :mouse])))
    (is (= [:stop] @events))))

(deftest nrepl-startup-failure-prevents-ui-and-preserves-the-original-error
  (let [failure (ex-info "injected nREPL startup failure" {:stage :nrepl})
        ui-started? (atom false)]
    (with-redefs [jolt-nrepl/start (fn [& _] (throw failure))
                  ui/run (fn [& _] (reset! ui-started? true))]
      (let [actual (try
                     (demo/start! {:font-path test-font})
                     nil
                     (catch Throwable error error))]
        (is (identical? failure actual))))
    (is (false? @ui-started?))
    (is (nil? (demo/stop!)))))

(deftest post-start-output-failure-cleans-up-and-allows-a-clean-restart
  (let [events (atom [])
        starts (atom 0)
        ui-runs (atom 0)
        failure (ex-info "injected startup output failure" {:stage :output})]
    (with-redefs [jolt-nrepl/start
                  (fn [& _]
                    (swap! starts inc)
                    (fake-stop events 7888))
                  ui/run (fn [& _] (swap! ui-runs inc))]
      (let [actual (with-redefs [println (fn [& _] (throw failure))]
                     (try
                       (demo/start! {:font-path test-font})
                       nil
                       (catch Throwable error error)))]
        (is (identical? failure actual)))
      (is (= [:stop] @events))
      (is (zero? @ui-runs))
      (is (= {:port 7888} (demo/start! {:font-path test-font}))))
    (is (= 2 @starts))
    (is (= 1 @ui-runs))
    (is (= [:stop :stop] @events))))

(deftest ui-failure-stops-nrepl-once-and-allows-a-clean-restart
  (let [events (atom [])
        starts (atom 0)
        fail-ui? (atom true)
        fail-stop? (atom true)
        failure (ex-info "injected UI startup failure" {:stage :ui})
        cleanup-failure (ex-info "injected cleanup failure" {:stage :cleanup})]
    (with-redefs [jolt-nrepl/start
                  (fn [& _]
                    (swap! starts inc)
                    (with-meta
                      (fn []
                        (swap! events conj :stop)
                        (when @fail-stop? (throw cleanup-failure)))
                      {:port 7888 :port-file nil}))
                  ui/run
                  (fn [& _]
                    (when @fail-ui? (throw failure)))]
      (let [error-output (java.io.StringWriter.)
            actual (binding [*err* error-output]
                     (try
                       (demo/start! {:font-path test-font})
                       nil
                       (catch Throwable error error)))]
        (is (identical? failure actual))
        (is (re-find #"injected cleanup failure" (str error-output))))
      (is (= [:stop] @events))
      (demo/stop!)
      (is (= [:stop] @events))
      (reset! fail-ui? false)
      (reset! fail-stop? false)
      (is (= {:port 7888} (demo/start! {:font-path test-font}))))
    (is (= 2 @starts))
    (is (= [:stop :stop] @events))))

(deftest stop-schedules-one-native-close-and-defers-server-stop-to-ui-teardown
  (let [events (atom [])]
    (with-redefs [jolt-nrepl/start
                  (fn [& _]
                    (swap! events conj :server)
                    (fake-stop events 7888))
                  sg/current-context (constantly :native-context)
                  ui/on-gui (fn [work]
                              (swap! events conj :scheduled)
                              (work))
                  renderer/request-close!
                  (fn [context]
                    (is (= :native-context context))
                    (swap! events conj :close))
                  ui/run
                  (fn [& _]
                    (swap! events conj :ui)
                    (is (nil? (demo/stop!)))
                    (is (nil? (demo/stop!)))
                    (is (= [:server :ui :scheduled :close] @events)
                        "the server remains available until the UI has torn down"))]
      (demo/start! {:font-path test-font}))
    (is (= [:server :ui :scheduled :close :stop] @events))))

(deftest close-requested-before-the-ui-loop-is-honored-on-start
  (let [real-run ui/run
        events (atom [])
        closes (atom 0)]
    (with-redefs [jolt-nrepl/start (fn [& _] (fake-stop events 7888))
                  renderer/request-close! (fn [_] (swap! closes inc))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (demo/stop!)
                    (is (fn? (:on-start opts)))
                    (apply real-run root-fn
                           (mapcat identity (assoc opts :visible false :max-frames 1))))]
      (demo/start! {:font-path test-font :width 320 :height 240}))
    (is (= 1 @closes))
    (is (= [:stop] @events))))

(deftest close-during-scheduled-work-tears-down-and-drops-late-native-work
  (let [real-run ui/run
        events (atom [])
        context (atom nil)
        late-work? (atom false)]
    (with-redefs [jolt-nrepl/start (fn [& _] (fake-stop events 7888))
                  ui/run
                  (fn [root-fn & {:as opts}]
                    (let [opts (assoc opts :visible false :max-frames 30
                                      :on-start
                                      (fn []
                                        (reset! context (sg/current-context))
                                        (ui/on-gui
                                          (fn []
                                            (swap! events conj :stop-requested)
                                            (demo/stop!)))))]
                      (apply real-run root-fn (mapcat identity opts))))]
      (is (= {:port 7888}
             (demo/start! {:font-path test-font :width 320 :height 240}))))
    (is (= [:stop-requested :stop] @events))
    (is (some? @context))
    (renderer/schedule! @context #(reset! late-work? true))
    (is (false? @late-work?))))

(deftest rich-nrepl-server-supports-a-stateful-session-eval
  (let [real-start jolt-nrepl/start
        actual-port (atom nil)]
    (with-redefs [jolt-nrepl/start
                  (fn [port middleware opts]
                    (let [stop (real-start port middleware (assoc opts :port-file nil))]
                      (reset! actual-port (:port (meta stop)))
                      stop))
                  ui/run
                  (fn [& _]
                    (let [transport (nrepl/connect "127.0.0.1" @actual-port)]
                      (try
                        (let [session (nrepl/new-session transport)
                              values (nrepl/response-values
                                       (nrepl/message
                                         transport
                                         {:op "eval" :session session
                                          :code "(do (swap! slipstream.example.demoscene/state assoc :lifecycle-eval 42) (:lifecycle-eval @slipstream.example.demoscene/state))"}))]
                          (is (= [42] values)))
                        (finally
                          (nrepl/close transport)))))]
      (is (pos? (:port (demo/start! {:font-path test-font :port 0}))))
      (is (pos? @actual-port))
      (is (= 42 (:lifecycle-eval @demo/state))))))

(defn- capture-live-frame []
  (let [pending (promise)]
    (ui/on-gui
      (fn []
        (deliver pending
                 (try
                   (let [context (sg/current-context)]
                     {:context context
                      :close-requested? (renderer/should-close? context)
                      :frame (renderer/capture! context)})
                   (catch Throwable error
                     {:error error})))))
    (let [{:keys [error] :as result}
          (deref pending 5000 {:error (ex-info "Timed out capturing the live frame" {})})]
      (when error (throw error))
      result)))

(defn- exercise-live-nrepl [port]
  (let [transport (nrepl/connect "127.0.0.1" port)]
    (try
      (let [session (nrepl/new-session transport)
            request (fn [message]
                      (nrepl/message transport (assoc message :session session)))
            evaluate (fn evaluate
                       ([code] (evaluate code nil))
                       ([code ns]
                        (let [responses (request (cond-> {:op "eval" :code code}
                                                   ns (assoc :ns ns)))
                              status (:status (nrepl/combine-responses responses))]
                          (is (not (some #{"error" "eval-error"} status))
                              (pr-str (nrepl/combine-responses responses)))
                          (nrepl/response-values responses))))
            description (request {:op "describe"})
            completions (request {:op "complete"
                                  :ns "slipstream.example.demoscene"
                                  :prefix "sta"})]
        (is (contains? (:ops (nrepl/combine-responses description)) "eval"))
        (is (contains? (:ops (nrepl/combine-responses description)) "complete"))
        (is (some #(= "start!" (get % "candidate"))
                  (:completions (nrepl/combine-responses completions))))
        (evaluate (str "(defn update-state [state _dt _input] "
                       "(assoc state :live-update :redefined))")
                  "slipstream.example.demoscene-shader")
        (loop [remaining 100]
          (let [value (first (evaluate "(:live-update @slipstream.example.demoscene/state)"))]
            (when-not (= :redefined value)
              (if (zero? remaining)
                (is (= :redefined value))
                (do
                  (Thread/sleep 10)
                  (recur (dec remaining)))))))
        (evaluate "(defn update-state [state _dt _input] state)"
                  "slipstream.example.demoscene-shader")
        (is (= [{:density 2.5 :speed 0.0 :live-eval true}]
               (evaluate (str "(swap! slipstream.example.demoscene/state assoc "
                              ":density 2.5 :speed 0.0 :live-eval true) "
                              "(select-keys @slipstream.example.demoscene/state "
                              "[:density :speed :live-eval])"))))
        (let [{before-context :context before-frame :frame} (capture-live-frame)]
          (is (false? (:close-requested? (capture-live-frame))))
          (evaluate (str "(def palette {:a [1.0 0.0 0.0] :b [0.0 1.0 0.0] "
                         ":c [0.0 0.0 1.0] :d [1.0 0.5 0.25]})")
                    "slipstream.example.demoscene-shader")
          (is (= [[1.0 0.0 0.0]]
                 (evaluate (str "(get-in (slipstream.example.demoscene-shader/shader-effect "
                                "@slipstream.example.demoscene/state) "
                                "[:uniforms :paletteA :value])"))))
          (evaluate "(swap! slipstream.example.demoscene/state update :density + 0.125)")
          (let [{after-context :context after-frame :frame} (capture-live-frame)]
            (is (identical? before-context after-context))
            (is (= [640 400] [(:width before-frame) (:height before-frame)]))
            (is (not= (hash (vec (:pixels before-frame)))
                      (hash (vec (:pixels after-frame))))
                "the redefined palette must render in the existing window")))
        (is (= [nil] (evaluate "(slipstream.example.demoscene/stop!)")))
        {:closed-via-nrepl? true})
      (finally
        (nrepl/close transport)))))

(deftest rich-nrepl-redefines-state-palette-and-update-function-in-one-live-window
  (let [real-start jolt-nrepl/start
        real-run ui/run
        original-update-state visuals/update-state
        original-palette visuals/palette
        original-state @demo/state
        actual-port (atom nil)
        context (atom nil)
        client-result (promise)]
    (try
      (with-redefs [jolt-nrepl/start
                    (fn [port middleware opts]
                      (let [stop (real-start port middleware (assoc opts :port-file nil))]
                        (reset! actual-port (:port (meta stop)))
                        stop))
                    ui/run
                    (fn [root-fn & {:as opts}]
                      (let [original-on-start (:on-start opts)
                            opts (assoc opts :visible false :max-frames nil
                                        :on-start
                                        (fn []
                                          (when original-on-start (original-on-start))
                                          (reset! context (sg/current-context))
                                          (future
                                            (try
                                              (deliver client-result
                                                       (exercise-live-nrepl @actual-port))
                                              (catch Throwable error
                                                (deliver client-result {:error error})
                                                (demo/stop!))))))]
                        (apply real-run root-fn (mapcat identity opts))))]
        (is (pos? (:port (demo/start! {:font-path test-font :port 0
                                      :width 320 :height 200 :auto-quit-ms 10000})))))
      (let [{:keys [error] :as result}
            (deref client-result 1000 {:error (ex-info "Live nREPL client did not finish" {})})]
        (when error (throw error))
        (is (:closed-via-nrepl? result)))
      (finally
        (alter-var-root #'visuals/update-state (constantly original-update-state))
        (alter-var-root #'visuals/palette (constantly original-palette))
        (reset! demo/state original-state)
        (demo/stop!)))))

(deftest capture-dir-writes-a-real-ppm-from-the-running-window
  (let [real-start jolt-nrepl/start
        real-run ui/run
        capture-dir (java.io.File. "native/build/demoscene-lifecycle-captures")]
    (doseq [file (reverse (file-seq capture-dir))] (.delete file))
    (try
      (with-redefs [jolt-nrepl/start
                    (fn [port middleware opts]
                      (real-start port middleware (assoc opts :port-file nil)))
                    ui/run
                    (fn [root-fn & {:as opts}]
                      (apply real-run root-fn
                             (mapcat identity (assoc opts :visible false))))]
        (is (pos? (:port (demo/start! {:font-path test-font :port 0
                                      :width 160 :height 100 :max-frames 2
                                      :capture-dir (str capture-dir)})))))
      (let [files (.listFiles capture-dir)]
        (is (= 1 (count files)))
        (when (= 1 (count files))
          (is (= "frame-000001.ppm" (.getName (first files))))
          (is (< 15 (.length (first files))))
          (with-open [input (java.io.FileInputStream. (first files))]
            (is (= [80 54] [(.read input) (.read input)])))))
      (finally
        (doseq [file (reverse (file-seq capture-dir))] (.delete file))))))

(deftest cli-requires-a-font-and-only-adds-limits-when-requested
  (is (string? (:error (demo/parse-args []))))
  (is (= {:font-path test-font :port 7888 :capture-dir nil
          :max-frames nil :auto-quit-ms nil}
         (demo/parse-args [test-font])))
  (is (= {:font-path test-font :port 0 :capture-dir "native/build/captures"
          :max-frames 2 :auto-quit-ms 15}
         (demo/parse-args [test-font "--port" "0"
                           "--capture-dir" "native/build/captures"
                           "--max-frames" "2" "--auto-quit-ms" "15"]))))
