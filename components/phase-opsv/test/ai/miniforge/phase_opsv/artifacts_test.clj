;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifacts-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-test-support :refer [with-context step]]
            [ai.miniforge.phase-opsv.artifact-boundary :as boundary]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-lifecycle-publication [ctx directory]
  (let [completed (reduce step ctx support/handlers)
        output (support/phase-output completed :opsv/actuate)
        refs (:opsv/artifact-refs output)
        records (mapv (partial artifact/read-published directory) refs)
        ids (:opsv/phase-artifact-ids output)
        measurements (artifact/read-published directory (:verification-measurements ids))
        assembly (get-in completed [:execution/input :opsv/evidence-assembly])]
    (is (= 8 (count refs)))
    (is (every? map? records))
    (is (not-any? anomaly/anomaly? records))
    (is (= (set refs) (:opsv/artifact-refs assembly)))
    (is (every? #(= (:execution/id ctx) (get-in % [:artifact/metadata :workflow/id])) records))
    (is (= (:opsv/experiment-pack output) (:artifact/content (first records))))
    (is (= (mapv ids [:metric-snapshot :verification-measurements])
           (:opsv/metric-snapshot-artifact-refs output)))
    (is (= (:opsv/verification-run output) (:artifact/content measurements)))
    (is (every? (set refs) (get-in output [:opsv/operational-policy :operational-policy/evidence-refs])))))

(defn- ^{:stratum 0} assert-idempotent-publication [ctx directory]
  (let [prepared (runtime/ensure-assembly ctx)
        output {:opsv/experiment-pack (get-in ctx [:execution/input :opsv/experiment-pack])}
        first (boundary/publish-with-exception-handling prepared :opsv/discover output)
        retry (boundary/publish-with-exception-handling prepared :opsv/discover output)]
    (is (= first retry))
    (is (= 1 (count (.listFiles (io/file directory)))))))

(defn- ^{:stratum 0} assert-failed-publication [ctx _directory]
  (let [prepared (runtime/ensure-assembly ctx)
        output {:opsv/actuation-record {:effective-actuation-mode :pr-only}}
        failed (with-redefs [artifact/publish! (constantly (anomaly/anomaly :unavailable "disk failed" {}))]
                 (boundary/publish-with-exception-handling prepared :opsv/actuate output))
        bundle-id (get-in prepared [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store prepared) bundle-id)]
    (is (anomaly/anomaly? failed))
    (is (= output (get-in failed [:anomaly/data :opsv/phase-output])))
    (is (empty? (:opsv/artifact-refs assembly)))))

(defn- ^{:stratum 0} assert-unavailable-assembly [ctx directory]
  (is (anomaly/anomaly? (boundary/publish-with-exception-handling ctx :opsv/discover {})))
  (is (empty? (seq (.listFiles (io/file directory))))))

(defn- ^{:stratum 0} fatal-publication [& _]
  (throw (AssertionError. "fatal publication")))

(defn- ^{:stratum 0} reject-verification-publication [publish directory record]
  (if (= :verification-measurements (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable "measurement storage failed" {})
    (publish directory record)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-fatal-publication [ctx _directory]
  (let [prepared (runtime/ensure-assembly ctx)
        output {:opsv/actuation-record {:effective-actuation-mode :none}}
        result (with-redefs [artifact/publish! fatal-publication]
                 (boundary/publish-with-exception-handling prepared :opsv/actuate output))]
    (is (= :fatal (:anomaly/type result)))
    (is (= output (get-in result [:anomaly/data :opsv/phase-output])))))

(defn- ^{:stratum 1} assert-verification-publication-recovery [ctx directory]
  (let [synthesized (reduce step ctx (take 5 support/handlers))
        verified (phase/verify synthesized)
        refuse-measurements (partial reject-verification-publication artifact/publish!)
        failed (with-redefs [artifact/publish! refuse-measurements]
                 (boundary/publish-with-exception-handling synthesized :opsv/verify verified))
        retained (get-in failed [:anomaly/data :opsv/phase-output])
        recovered (boundary/publish-with-exception-handling synthesized :opsv/verify retained)
        id (get-in recovered [:opsv/phase-artifact-ids :verification-measurements])
        stored (artifact/read-published directory id)]
    (is (anomaly/anomaly? failed))
    (is (nil? (get-in retained [:opsv/phase-artifact-ids :verification-measurements])))
    (is (= (:opsv/verification-run verified) (:opsv/verification-run retained)))
    (is (= (:opsv/verification-run verified) (:artifact/content stored)))
    (is (some #{id} (:opsv/metric-snapshot-artifact-refs recovered)))))

(deftest ^{:stratum 1} lifecycle-publishes-real-content-before-evidence-references-test
  (with-context assert-lifecycle-publication))

(deftest ^{:stratum 1} identical-phase-publication-is-idempotent-test
  (with-context assert-idempotent-publication))

(deftest ^{:stratum 1} failed-publication-does-not-claim-a-reference-test
  (with-context assert-failed-publication))

(deftest ^{:stratum 1} unavailable-assembly-refuses-before-artifact-write-test
  (with-context assert-unavailable-assembly))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} fatal-publication-error-is-not-retryable-test
  (with-context assert-fatal-publication))

(deftest ^{:stratum 2} verification-measurements-retry-without-reexecuting-the-candidate-test
  (with-context assert-verification-publication-recovery))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.artifacts-test))
