;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.deliberation-lab.test-runner
  (:require [clojure.test :as test]))

(def suites
  (conj (mapv #(symbol (str "ai.miniforge.deliberation-workspace." % "-test"))
              '[object transaction validation guards commit conflict projection scheduler termination run admission])
        'ai.miniforge.deliberation-lab.experiment-test))
(defn -main [& _]
  (doseq [suite suites] (require suite))
  (let [{:keys [fail error]} (apply test/run-tests suites)]
    (System/exit (if (zero? (+ fail error)) 0 1))))
