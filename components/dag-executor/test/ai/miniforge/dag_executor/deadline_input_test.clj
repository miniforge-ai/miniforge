;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.deadline-input-test
  (:require [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-capture :as capture]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-input :as input]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} success-budget-ms
  "Immediate promise observations must not accidentally expire while scheduled."
  5000)

(def ^{:stratum 0 :private true} successful-output
  "Whole output is not enough to confirm successful stdin delivery."
  {:exit 0
   :out "whole"
   :err ""})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-failed-input-discards-apparent-success-and-output
  (let [writer (deliver (promise) false)
        reply (input/confirm! writer (deadline/after-ms success-budget-ms) successful-output)]
    (is (= (capture/failure :input-error) reply))))

(deftest ^{:stratum 1} test-complete-input-cannot-confirm-a-result-after-the-deadline
  (let [writer (deliver (promise) true)
        reply (input/confirm! writer (System/nanoTime) successful-output)]
    (is (= (capture/failure :deadline) reply))))

(comment (clojure.test/run-tests 'ai.miniforge.dag-executor.deadline-input-test))
