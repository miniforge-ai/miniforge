;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.native-verification-evidence-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :as storage]
            [ai.miniforge.phase-opsv.candidate-verification-fixtures :as verification]
            [ai.miniforge.phase-opsv.environment-comparison-fixtures :as environment]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.native-material-fixtures :as native]
            [ai.miniforge.phase-opsv.native-verification-evidence-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} counted-refusal [calls _request]
  (swap! calls conj :invoked)
  (anomaly/anomaly :unavailable "injected callback refusal" {}))

(defn- ^{:stratum 0} verify-literal-privacy [ctx directory baseline]
  (let [{:keys [result retained final stages]} (fixtures/reports ctx directory baseline "opaque-native-value")]
    (is (not (anomaly/anomaly? result)))
    (is (= [:prepare :verify] stages))
    (doseq [value [result retained final]]
      (is (not (.contains (pr-str value) "opaque-native-value"))))))

(defn- ^{:stratum 0} verify-original-findings [ctx directory baseline]
  (let [{:keys [result retained final]} (fixtures/reports ctx directory baseline "123-45-6789")]
    (is (not (anomaly/anomaly? result)))
    (doseq [value [retained final]]
      (is (not (.contains (pr-str value) "123-45-6789")))
      (is (true? (get-in value [:artifact/metadata :evidence/contains-pii?])))
      (is (seq (get-in value [:artifact/metadata :compliance/sensitive-findings]))))))

(defn- ^{:stratum 0} verify-native-finalization [ctx directory baseline]
  (let [calls (atom [])
        prepared (fixtures/native-observation baseline "123-45-6789")
        measured (fixtures/native-observation (merge baseline (environment/changes)) "opaque-native-value")
        prepare (partial verification/prepare calls prepared)
        measure (partial verification/measure calls directory measured)
        ready (assoc-in (storage/configured ctx) [:execution/opts :opsv/adapter]
                         (phase/functional-adapter identity identity prepare measure))
        verified (storage/step ready [:opsv/verify phase/verify])
        completed (storage/step verified [:opsv/actuate phase/actuate])
        output (support/phase-output completed :opsv/actuate)
        retained (get-in @calls [1 2])
        reloaded (artifact/read-published directory (:artifact/id retained))]
    (is (:valid? (evidence/validate-published-bundle (:opsv/evidence-bundle output))))
    (is (= retained reloaded))
    (is (true? (get-in reloaded [:artifact/metadata :evidence/contains-pii?])))
    (is (contains? (set (:opsv/artifact-refs output)) (:artifact/id retained)))
    (is (not (.contains (pr-str output) "opaque-native-value")))
    (is (not (.contains (pr-str reloaded) "123-45-6789")))))

(defn- ^{:stratum 0} verify-legacy-findings [ctx directory baseline]
  (let [measure #(phase/verification-receipt %
                  (-> support/verification-measurements
                      (assoc :environment-fingerprint baseline)
                      (assoc-in [:observations "native"] (native/frame "person@example.com"))))
        ready (-> (storage/configured ctx)
                  (update :execution/opts dissoc :opsv/verification-environment)
                  (assoc-in [:execution/opts :opsv/adapter]
                            (phase/functional-adapter identity identity identity measure)))
        verified (storage/step ready [:opsv/verify phase/verify])
        completed (storage/step verified [:opsv/actuate phase/actuate])
        output (support/phase-output completed :opsv/actuate)
        record (artifact/read-published directory
                 (get-in output [:opsv/phase-artifact-ids :verification-measurements]))]
    (is (:valid? (evidence/validate-published-bundle (:opsv/evidence-bundle output))))
    (is (not (.contains (pr-str output) "person@example.com")))
    (is (not (.contains (pr-str record) "person@example.com")))
    (is (true? (get-in record [:artifact/metadata :evidence/contains-pii?])))
    (is (contains? (set (map :finding/type (get-in record [:artifact/metadata :compliance/sensitive-findings]))) :email))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-inherited-metadata [ctx _directory _baseline]
  (doseq [configured [ctx (update ctx :execution/opts dissoc :opsv/verification-environment)]]
    (let [calls (atom [])
          hidden (native/frame "opaque-native-value")
          configured (update-in configured [:execution/phase-results :opsv/synthesize :result :output]
                                #(with-meta % hidden))
          callback (partial counted-refusal calls)
          result (verification/verify configured callback callback)]
      (is (anomaly/anomaly? result))
      (is (empty? @calls))
      (is (= {} (:anomaly/data result))))))

(deftest ^{:stratum 1} native-literals-are-protected-before-retention-and-return-test
  (verification/with-context verify-literal-privacy))

(deftest ^{:stratum 1} original-native-pii-findings-survive-retained-material-test
  (verification/with-context verify-original-findings))

(deftest ^{:stratum 1} native-protection-finalizes-with-original-findings-in-its-immutable-lineage-test
  (verification/with-context verify-native-finalization))

(deftest ^{:stratum 1} legacy-native-original-pii-findings-survive-publication-and-finalization-test
  (verification/with-context verify-legacy-findings))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} inherited-language-metadata-cannot-reach-either-adapter-mode-test
  (verification/with-context verify-inherited-metadata))
