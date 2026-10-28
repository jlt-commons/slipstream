(ns slipstream.test-runner
  (:require [clojure.test :as t]
            [slipstream.core-test]
            [slipstream.canvas-test]
            [slipstream.text-test]
            [slipstream.scene-test]
            [slipstream.glfw-test]
            [slipstream.glimmer-test]
            [slipstream.glimmer-resources-test]
            [slipstream.parity-test]))

(defn- exit [code]
  (System/exit code))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-tests 'slipstream.core-test 'slipstream.canvas-test
                                          'slipstream.text-test 'slipstream.scene-test
                                          'slipstream.glfw-test 'slipstream.glimmer-test
                                          'slipstream.glimmer-resources-test 'slipstream.parity-test)]
    (exit (if (zero? (+ fail error)) 0 1))))
