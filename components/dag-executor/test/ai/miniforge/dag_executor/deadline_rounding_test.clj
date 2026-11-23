;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-rounding-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-positive-remaining-time-never-rounds-down
  (let [one-ms deadline/nanos-per-ms]
    (doseq [[remaining expected] [[0 0] [1 1] [(dec one-ms) 1] [one-ms 1] [(inc one-ms) 2]]]
      (is (= expected (deadline/ceil-ms remaining))))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-rounding-test))
