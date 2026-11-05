;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.candidate-verification-population-test
  (:require [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.replica-comparison-fixtures :as replicas]
            [ai.miniforge.phase-opsv.replica-contract-fixtures :as contracts]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} observed-population [fingerprint expected ids]
  (assoc (fixtures/observations fingerprint) :comparison/replica-observations
         (mapv (partial replicas/replica expected) ids)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-populations [ctx directory baseline]
  (let [declaration (get-in ctx [:execution/opts :opsv/verification-environment])
        plan {:comparison/binding {:runtime/target (:runtime/target declaration)}
              :comparison/baseline baseline :comparison/changes (:comparison/changes declaration)}
        contract (into {} (map (fn [stage] [stage (contracts/expectation plan stage)]) [:candidate :rollback]))
        ctx (assoc-in ctx [:execution/opts :opsv/verification-environment :comparison/replica-contract] contract)
        calls (atom [])
        prepare (partial fixtures/prepare calls (observed-population baseline (:rollback contract) ["original-instance"]))
        candidate (merge baseline (environment/changes))
        measure (fn [observed] (partial fixtures/measure calls directory observed))
        valid (observed-population candidate (:candidate contract) ["replacement-a" "replacement-b" "replacement-c"])
        result (fixtures/verify ctx prepare (measure valid))]
    (is (true? (get-in result [:opsv/verification-result :passed?])))
    (doseq [invalid [(assoc valid :comparison/replica-observations [])
                     (observed-population candidate (:candidate contract) ["a" "b" "c" "d"])
                     (observed-population candidate (:candidate contract) ["duplicate" "duplicate"])
                     (assoc-in valid [:comparison/replica-observations 0 :replica/node-pool] "foreign")]]
      (is (= :conflict (:anomaly/type (fixtures/verify ctx prepare (measure invalid))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} host-keeps-predeclared-replacement-count-and-membership-bounds-test
  (fixtures/with-context verify-populations))
