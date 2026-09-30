;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-failure-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-boundary :as boundary]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixtures]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} unavailable [& _]
  (anomaly/anomaly :unavailable "publication unavailable" {}))

(defn- ^{:stratum 0} unexpected-failure [& _]
  (throw (java.io.IOException. "publication failed")))

(defn- ^{:stratum 0} finalized-assembly [read-assembly store id]
  (assoc (read-assembly store id) :opsv.assembly/status :finalized))

(defn- ^{:stratum 0} record-transform [calls ctx]
  (swap! calls inc)
  ctx)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-confirmed-failure-refs [ctx _directory]
  (let [synthesized (reduce fixtures/step ctx (take 5 support/handlers))
        verified (phase/verify synthesized)
        baseline (get-in verified [:opsv/phase-artifact-ids :metric-snapshot])]
    (doseq [failure [unavailable unexpected-failure]]
      (let [result (with-redefs [artifact/publish! failure]
                     (boundary/publish-with-exception-handling synthesized :opsv/verify verified))
            retained (get-in result [:anomaly/data :opsv/phase-output])]
        (is (anomaly/anomaly? result))
        (is (= [baseline] (:opsv/metric-snapshot-artifact-refs retained)))
        (is (= (:opsv/verification-run verified) (:opsv/verification-run retained)))))
    (let [result (boundary/publish-with-exception-handling ctx :opsv/verify verified)]
      (is (= [baseline] (get-in result [:anomaly/data :opsv/phase-output :opsv/metric-snapshot-artifact-refs]))))))

(defn- ^{:stratum 1} assert-finalized-preflight [ctx _directory]
  (let [prepared (fixtures/step ctx (first support/handlers))
        calls (atom 0)
        finalized (partial finalized-assembly evidence/get-opsv-assembly)]
    (doseq [phase-key [:opsv/execute :opsv/verify :opsv/actuate]]
      (let [interceptor (lifecycle/interceptor {} phase-key (partial record-transform calls))
            result (with-redefs [evidence/get-opsv-assembly finalized]
                     ((:enter interceptor) prepared))]
        (is (= :error (get-in result [:phase :result :status])))))
    (is (zero? @calls))
    (let [memory-only (update prepared :execution/opts dissoc :opsv/artifact-directory)
          interceptor (lifecycle/interceptor {} :opsv/execute (partial record-transform calls))]
      (with-redefs [evidence/get-opsv-assembly finalized]
        ((:enter interceptor) memory-only))
      (is (= 1 @calls)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} failed-publication-retains-only-confirmed-metric-references-test
  (fixtures/with-context assert-confirmed-failure-refs))

(deftest ^{:stratum 2} finalized-storage-refuses-before-any-phase-effect-test
  (fixtures/with-context assert-finalized-preflight))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.artifact-failure-test))
