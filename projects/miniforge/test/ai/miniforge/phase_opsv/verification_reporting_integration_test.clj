;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-reporting-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.finalization-boundary :as finalization]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-opaque-inherited-report [ctx directory baseline]
  (let [opaque (doto (java.util.HashMap.) (.put "password" "fake-test-password"))
        calls (atom [])
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        measure (partial fixtures/measure calls directory (fixtures/observations baseline))]
    (doseq [configured [ctx (update ctx :execution/opts dissoc :opsv/verification-environment)]]
      (let [configured (assoc-in configured [:execution/phase-results :opsv/synthesize :result :output
                                            :opsv/candidate-drivers] [opaque])
            result (fixtures/verify configured prepare measure)]
        (is (anomaly/anomaly? result))
        (is (= {} (:anomaly/data result)))
        (is (redaction/supported? result))
        (is (empty? @calls))))))

(defn- ^{:stratum 0} verify-sensitive-legacy-drift [ctx _directory baseline]
  (let [ctx (update ctx :execution/opts dissoc :opsv/verification-environment)
        measurements (assoc support/verification-measurements :environment-fingerprint
                             (assoc baseline :config-hash "DB_PASSWORD=fake-test-password"))
        result (fixtures/verify ctx identity #(phase/verification-receipt % measurements))]
    (is (= :conflict (:anomaly/type result)))
    (is (map? (get-in result [:anomaly/data :opsv/environment-drift])))
    (is (redaction/clean? result))))

(defn- ^{:stratum 0} verify-type-sensitive-material [ctx _directory]
  (let [completed (reduce storage/step ctx support/handlers)
        output (support/phase-output completed :opsv/actuate)
        changed (update output :opsv/ramp-steps #(apply list %))
        configured (storage/configured completed)
        refused (finalization/finalize! configured changed)
        original (finalization/finalize! configured output)]
    (is (= (:opsv/ramp-steps output) (:opsv/ramp-steps changed)))
    (is (anomaly/anomaly? refused))
    (is (:valid? (evidence/validate-published-bundle (:opsv/evidence-bundle original))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unsupported-inherited-values-are-refused-before-callbacks-test
  (fixtures/with-context verify-opaque-inherited-report))

(deftest ^{:stratum 1} sensitive-legacy-mismatches-are-protected-after-comparison-test
  (fixtures/with-context verify-sensitive-legacy-drift))

(deftest ^{:stratum 1} equal-valued-different-collection-types-cannot-reuse-material-test
  (storage/with-context verify-type-sensitive-material))
