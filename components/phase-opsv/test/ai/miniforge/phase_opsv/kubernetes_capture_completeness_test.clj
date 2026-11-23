;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-capture-completeness-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.kubernetes-capture-test-support :as fixture]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} remaining-item-count-cannot-claim-a-complete-selection-test
  (doseq [remaining [1 -1 "1" 0.5]]
    (let [routes (assoc-in (fixture/routes false)
                           [fixture/pods-path "metadata" "remainingItemCount"] remaining)
          [result calls] (fixture/observe! routes {})]
      (is (anomaly/anomaly? result))
      (is (= 1 (count calls))))))

(deftest ^{:stratum 0} zero-remaining-count-retains-complete-selection-support-test
  (let [routes (assoc-in (fixture/routes false)
                         [fixture/pods-path "metadata" "remainingItemCount"] 0)
        [result calls] (fixture/observe! routes {})]
    (is (not (anomaly/anomaly? result)))
    (is (= 6 (count calls)))))

(comment ::no-derived-reads-from-an-incomplete-or-contradictory-selection)
