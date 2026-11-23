;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-retention-failures-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.recovery-plan-retention-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.retained-preparation-test-support :as storage]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} publication-failure [& _]
  (anomaly/anomaly :unavailable "injected publication failure" {}))

(defn- ^{:stratum 0} fail-plan-read [original id failure directory selected]
  (if (= id selected) (throw failure) (original directory selected)))

(defn- ^{:stratum 0} check-uncached-read [{:keys [ctx plan-id] :as fixture}]
  (is (map? (:recovery/plan (fixtures/read-plan fixture))))
  (storage/withdraw! (get-in ctx [:execution/opts :opsv/artifact-directory]) plan-id)
  (is (= :unavailable (:anomaly/type (fixtures/read-plan fixture)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-publish-failure [fixture]
  (let [result (with-redefs [artifact/publish! publication-failure] (fixtures/retain fixture))]
    (is (anomaly/anomaly? result))
    (is (not (contains? result :recovery/artifact-ref)))
    (is (empty? (get-in result [:anomaly/data :opsv/phase-output :opsv/phase-produced-artifact-refs])))))

(defn- ^{:stratum 1} check-ack-failure [fixture]
  (let [result (with-redefs [evidence/accumulate-opsv-evidence! publication-failure] (fixtures/retain fixture))
        id (get-in result [:anomaly/data :artifact/id])
        selected (assoc fixture :plan-id id)]
    (is (anomaly/anomaly? result))
    (is (uuid? id))
    (is (map? (:artifact/content (fixtures/published selected))))
    (is (not (contains? result :recovery/artifact-ref)))
    (is (anomaly/anomaly? (fixtures/read-plan selected)))))

(defn- ^{:stratum 1} check-io-errors [{:keys [plan-id] :as fixture}]
  (let [original artifact/read-published]
    (with-redefs [artifact/read-published (partial fail-plan-read original plan-id (java.io.IOException.))]
      (is (= :unavailable (:anomaly/type (fixtures/read-plan fixture)))))
    (with-redefs [artifact/read-published (partial fail-plan-read original plan-id (AssertionError.))]
      (is (thrown? AssertionError (fixtures/read-plan fixture))))
    (let [interrupted (InterruptedException.)]
      (try
        (with-redefs [artifact/read-published (partial fail-plan-read original plan-id interrupted)]
          (fixtures/read-plan fixture)
          (is false "interruption must propagate"))
        (catch InterruptedException caught
          (let [flagged? (.isInterrupted (Thread/currentThread))]
            (is (identical? interrupted caught))
            (is flagged?)))
        (finally (Thread/interrupted))))))

(deftest ^{:stratum 1} withdrawn-immutable-plan-file-is-not-replaced-by-a-success-cache-test
  (fixtures/with-retained check-uncached-read))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} failed-publication-returns-no-retained-reference-test
  (fixtures/with-input check-publish-failure))

(deftest ^{:stratum 2} actual-published-but-unacknowledged-report-remains-unusable-test
  (fixtures/with-input check-ack-failure))

(deftest ^{:stratum 2} retained-plan-io-preserves-fatal-and-interruption-semantics-test
  (fixtures/with-retained check-io-errors))

(comment ::faults-apply-only-after-the-real-original-and-metric-producers-finish)
