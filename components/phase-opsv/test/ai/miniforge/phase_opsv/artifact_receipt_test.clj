;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-receipt-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-boundary :as boundary]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixtures]
            [ai.miniforge.phase-opsv.evidence-runtime :as runtime]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} discovery-output [ctx]
  (assoc (fixtures/discovery-output ctx) :opsv/phase-produced-artifact-refs [(random-uuid)]))

(defn- ^{:stratum 0} refuse [& _args]
  (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed) {}))

(defn- ^{:stratum 0} reject-measurements [publish directory record]
  (if (= :verification-measurements (get-in record [:artifact/metadata :opsv/material-kind]))
    (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed) {})
    (publish directory record)))

(defn- ^{:stratum 0} produced [output] (:opsv/phase-produced-artifact-refs output))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-attempt-isolation [ctx _directory]
  (let [prepared (runtime/ensure-assembly ctx)
        output (discovery-output ctx)
        discovered (boundary/publish-with-exception-handling prepared :opsv/discover output)
        planned (boundary/publish-with-exception-handling prepared :opsv/plan discovered)
        retried (boundary/publish-with-exception-handling prepared :opsv/discover discovered)
        id (get-in discovered [:opsv/phase-artifact-ids :experiment-pack])]
    (is (uuid? id))
    (is (= [id] (produced discovered)))
    (is (not= (produced output) (produced discovered)))
    (is (= [] (produced planned)))
    (is (= [id] (:opsv/artifact-refs planned)))
    (is (= [id] (produced retried)))))

(defn- ^{:stratum 1} verify-unacknowledged-write [ctx directory]
  (let [prepared (runtime/ensure-assembly ctx)
        result (with-redefs [evidence/accumulate-opsv-evidence! refuse]
                 (boundary/publish-with-exception-handling prepared :opsv/discover (discovery-output ctx)))
        retained (get-in result [:anomaly/data :opsv/phase-output])
        id (get-in result [:anomaly/data :artifact/id])]
    (is (anomaly/any-anomaly? result))
    (is (= [] (produced retained)))
    (is (= id (:artifact/id (artifact/read-published directory id))))))

(defn- ^{:stratum 1} verify-partial-receipts [ctx _directory]
  (let [synthesized (reduce fixtures/step ctx (take 5 support/handlers))
        verified (phase/verify synthesized)
        reject (partial reject-measurements artifact/publish!)
        result (with-redefs [artifact/publish! reject]
                 (boundary/publish-with-exception-handling synthesized :opsv/verify verified))
        retained (get-in result [:anomaly/data :opsv/phase-output])
        ids (:opsv/phase-artifact-ids retained)]
    (is (anomaly/any-anomaly? result))
    (is (= (mapv ids [:policy :verification]) (produced retained)))
    (is (not (some #{(:metric-snapshot ids)} (produced retained))))))

(deftest ^{:stratum 1} skipped-publication-does-not-reuse-prior-attempt-receipts
  (let [output (discovery-output {})
        failure (anomaly/anomaly :unavailable (msg/t :evidence/artifact-publication-failed)
                                 {:opsv/phase-output output})
        skipped (boundary/publish-with-exception-handling {} :opsv/plan output)
        failed (boundary/publish-with-exception-handling {} :opsv/plan failure)]
    (is (not (contains? skipped :opsv/phase-produced-artifact-refs)))
    (is (not (contains? (get-in failed [:anomaly/data :opsv/phase-output])
                       :opsv/phase-produced-artifact-refs)))))

(deftest ^{:stratum 1} legacy-failure-is-not-treated-as-publishable-output
  (let [output (discovery-output {})
        failure (response/make-anomaly :anomalies/unavailable (msg/t :evidence/artifact-publication-failed)
                                      {:anomaly/data {:opsv/phase-output output}})
        ctx {:execution/opts {:opsv/artifact-directory "unused-for-refused-output"}}
        result (boundary/publish-with-exception-handling ctx :opsv/discover failure)]
    (is (= (:anomaly/category failure) (:anomaly/category result)))
    (is (nil? (produced (get-in result [:anomaly/data :opsv/phase-output]))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} receipts-are-per-attempt-not-cumulative
  (fixtures/with-context verify-attempt-isolation))

(deftest ^{:stratum 2} stored-but-unacknowledged-artifact-is-not-a-produced-receipt
  (fixtures/with-context verify-unacknowledged-write))

(deftest ^{:stratum 2} failed-publication-retains-only-this-attempts-acknowledgments
  (fixtures/with-context verify-partial-receipts))
