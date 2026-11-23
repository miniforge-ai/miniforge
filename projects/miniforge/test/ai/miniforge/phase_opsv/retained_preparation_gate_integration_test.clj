;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.retained-preparation-gate-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.retained-preparation-test-support :as storage]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-withdrawn-preparation [ctx directory baseline]
  (let [calls (atom [])
        publish artifact/publish!
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        candidate (fixtures/observations (merge baseline (environment/changes)))
        measure (partial fixtures/measure calls directory candidate)
        result (with-redefs [artifact/publish! (partial storage/publish-then-withdraw! publish)]
                 (fixtures/verify ctx prepare measure))]
    (is (anomaly/anomaly? result))
    (is (= [:prepare] (mapv first @calls)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} acknowledged-but-no-longer-retained-baseline-cannot-reach-candidate-effects-test
  (fixtures/with-context verify-withdrawn-preparation))
