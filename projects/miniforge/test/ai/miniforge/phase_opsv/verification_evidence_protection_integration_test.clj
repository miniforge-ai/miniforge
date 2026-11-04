;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-evidence-protection-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.redaction.interface :as redaction]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sensitive-observation [fingerprint]
  (assoc-in (fixtures/observations fingerprint)
            [:environment/raw-observations :password] "fake-test-password"))

(defn- ^{:stratum 0} assert-protected [value]
  (is (redaction/clean? value))
  (is (true? (get-in value [:artifact/metadata :compliance/sensitive-data])))
  (is (= :redacted (get-in value [:artifact/metadata :compliance/pii-handling]))))

(defn- ^{:stratum 0} verify-unsupported-preparation [ctx directory baseline]
  (let [calls (atom [])
        observation (assoc-in (fixtures/observations baseline)
                              [:environment/raw-observations :opaque] (Object.))
        result (fixtures/verify ctx (partial fixtures/prepare calls observation)
                 (partial fixtures/measure calls directory (fixtures/observations baseline)))]
    (is (anomaly/any-anomaly? result))
    (is (= [:prepare] (mapv first @calls)))
    (is (redaction/clean? result))))

(defn- ^{:stratum 0} verify-adapter-failures [ctx directory baseline]
  (let [calls (atom [])
        failure (anomaly/anomaly :unavailable "injected adapter failure" {:password "fake-test-password"})
        fail (constantly failure)
        prepare (partial fixtures/prepare calls (fixtures/observations baseline))
        measure (partial fixtures/measure calls directory
                         (fixtures/observations (merge baseline (environment/changes))))]
    (doseq [[prepare measure] [[fail measure] [prepare fail]]]
      (let [result (fixtures/verify ctx prepare measure)]
        (is (= :unavailable (:anomaly/type result)))
        (is (redaction/clean? result))))))

(defn- ^{:stratum 0} verify-legacy-reporting [ctx _directory baseline]
  (let [baseline (assoc baseline :config-hash "DB_PASSWORD=fake-inherited")
        ctx (-> ctx
                (update :execution/opts dissoc :opsv/verification-environment)
                (assoc-in [:execution/phase-results :opsv/synthesize :result :output :opsv/environment-fingerprint] baseline))
        measurements (-> support/verification-measurements
                         (assoc :environment-fingerprint baseline)
                         (assoc-in [:observations "private"] "DB_PASSWORD=fake-test-password"))
        measure #(phase/verification-receipt % measurements)
        result (fixtures/verify ctx identity measure)]
    (is (not (anomaly/anomaly? result)))
    (is (true? (get-in result [:opsv/verification-result :passed?])))
    (is (redaction/clean? result))))

(defn- ^{:stratum 0} criterion-context [ctx expected]
  (let [pack (assoc-in (:opsv/experiment-pack (fixtures/synthesized ctx))
                       [:experiment-pack/success-criteria :criteria]
                       [{:criterion/id "private" :criterion/expected expected}])]
    (-> ctx
        (assoc-in [:execution/phase-results :opsv/synthesize :result :output :opsv/experiment-pack] pack)
        (assoc-in [:execution/opts :opsv/verification-environment :experiment-pack/hash] (hash/content-hash pack)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-original-criteria [ctx directory baseline]
  (let [ctx (criterion-context ctx "DB_PASSWORD=fake-expected")
        measured (assoc (fixtures/observations (merge baseline (environment/changes)))
                         :observations {"private" "DB_PASSWORD=fake-observed"})
        calls (atom [])
        result (fixtures/verify ctx (partial fixtures/prepare calls (fixtures/observations baseline))
                 (partial fixtures/measure calls directory measured))]
    (is (not (anomaly/anomaly? result)))
    (is (false? (get-in result [:opsv/verification-result :passed?])))
    (is (redaction/clean? result))))

(defn- ^{:stratum 1} verify-protected-finalization [ctx directory baseline]
  (let [calls (atom [])
        prepare (partial fixtures/prepare calls (sensitive-observation baseline))
        measure (partial fixtures/measure calls directory
                         (sensitive-observation (merge baseline (environment/changes))))
        ready (assoc-in (storage/configured ctx) [:execution/opts :opsv/adapter]
                         (phase/functional-adapter identity identity prepare measure))
        verified (storage/step ready [:opsv/verify phase/verify])
        completed (storage/step verified [:opsv/actuate phase/actuate])
        output (support/phase-output completed :opsv/actuate)
        bundle (:opsv/evidence-bundle output)
        final (artifact/read-published directory
                (get-in output [:opsv/phase-artifact-ids :verification-environment]))]
    (is (:valid? (evidence/validate-published-bundle bundle)))
    (is (redaction/clean? output))
    (assert-protected final)))

(defn- ^{:stratum 1} verify-protected-readback [ctx directory baseline]
  (let [calls (atom [])
        result (fixtures/verify ctx
                 (partial fixtures/prepare calls (sensitive-observation baseline))
                 (partial fixtures/measure calls directory
                          (sensitive-observation (merge baseline (environment/changes)))))
        [_ request retained] (second @calls)
        final (artifact/read-published directory
                (get-in result [:opsv/phase-artifact-ids :verification-environment]))]
    (is (not (anomaly/anomaly? result)))
    (is (= [:prepare :verify] (mapv first @calls)))
    (assert-protected retained)
    (assert-protected final)
    (is (redaction/clean? result))
    (is (= (redaction/marker)
           (get-in retained [:artifact/content :comparison/preparation :environment/raw-observations :password])))
    (is (= (:comparison/hash (:comparison/pin request))
           (get-in retained [:artifact/content :comparison/pin :comparison/hash])))))

(defn- ^{:stratum 1} verify-original-comparison [ctx directory baseline]
  (let [expected "DB_PASSWORD=fake-expected"
        observed "DB_PASSWORD=fake-observed"
        ctx (assoc-in ctx [:execution/opts :opsv/verification-environment :comparison/changes :config-hash] expected)
        fingerprint (assoc (merge baseline (environment/changes)) :config-hash observed)
        calls (atom [])
        result (fixtures/verify ctx
                 (partial fixtures/prepare calls (sensitive-observation baseline))
                 (partial fixtures/measure calls directory (sensitive-observation fingerprint)))
        output (get-in result [:anomaly/data :opsv/phase-output])
        report (artifact/read-published directory
                 (get-in output [:opsv/phase-artifact-ids :verification-environment]))]
    (is (= (redaction/redact expected) (redaction/redact observed)))
    (is (= :conflict (:anomaly/type result)))
    (is (redaction/clean? result))
    (assert-protected report)
    (is (= :conflict (get-in report [:artifact/content :comparison/outcome :anomaly/type])))))

(deftest ^{:stratum 1} unsupported-preparation-cannot-reach-candidate-effects-test
  (fixtures/with-context verify-unsupported-preparation))

(deftest ^{:stratum 1} adapter-failures-are-protected-before-returning-test
  (fixtures/with-context verify-adapter-failures))

(deftest ^{:stratum 1} legacy-verification-reporting-is-protected-after-evaluation-test
  (fixtures/with-context verify-legacy-reporting))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} preparation-and-final-evidence-are-protected-before-storage-test
  (fixtures/with-context verify-protected-readback))

(deftest ^{:stratum 2} redacted-reporting-cannot-conceal-original-drift-test
  (fixtures/with-context verify-original-comparison))

(deftest ^{:stratum 2} redacted-reporting-cannot-make-distinct-criterion-values-pass-test
  (fixtures/with-context verify-original-criteria))

(deftest ^{:stratum 2} protected-evidence-finalizes-with-original-bound-findings-test
  (fixtures/with-context verify-protected-finalization))
