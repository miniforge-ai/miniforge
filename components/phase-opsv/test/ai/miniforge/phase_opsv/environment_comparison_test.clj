;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.environment-comparison-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} pin-retains-original-baseline-and-is-deterministic-test
  (let [plan (fixtures/plan)
        pinned (phase/pin-verification-environment plan)
        unchanged (phase/pin-verification-environment (assoc plan :comparison/changes {}))]
    (is (not (anomaly/anomaly? pinned)))
    (is (= plan (:comparison/contract pinned)))
    (is (= pinned (phase/pin-verification-environment plan)))
    (is (not= (:comparison/hash pinned) (:comparison/hash unchanged)))))

(deftest ^{:stratum 0} invalid-environment-plans-fail-closed-test
  (let [plan (fixtures/plan)]
    (doseq [invalid [nil {}
                     (assoc plan :unexpected true)
                     (assoc plan :comparison/baseline {})
                     (assoc plan :comparison/changes {:cluster "foreign"})
                     (assoc plan :comparison/changes {:node-pools ["foreign"]})
                     (assoc plan :comparison/changes {:config-hash nil})
                     (assoc-in plan [:comparison/binding :candidate/hash] "stale")
                     (assoc-in plan [:comparison/binding :runtime/target :cluster] "foreign")
                     (assoc-in plan [:comparison/baseline :image-digests] {})]]
      (is (= :invalid-input
             (:anomaly/type (phase/pin-verification-environment invalid)))))))

(comment ::predeclared-canonical-environment-contract)
