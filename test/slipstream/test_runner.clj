(ns slipstream.test-runner
  (:require [clojure.test :as t]
            [slipstream.core-test]
            [slipstream.canvas-test]))

(defn- exit [code]
  (cond
    (resolve 'jolt.host/exit) ((resolve 'jolt.host/exit) code)
    (resolve 'System/exit) ((resolve 'System/exit) code)
    :else nil))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'slipstream.core-test 'slipstream.canvas-test)]
    (exit (if (zero? (+ fail error)) 0 1))))
