;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-evidence-protection-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
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

;------------------------------------------------------------------------------ Layer 1

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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} preparation-and-final-evidence-are-protected-before-storage-test
  (fixtures/with-context verify-protected-readback))

(deftest ^{:stratum 2} redacted-reporting-cannot-conceal-original-drift-test
  (fixtures/with-context verify-original-comparison))
