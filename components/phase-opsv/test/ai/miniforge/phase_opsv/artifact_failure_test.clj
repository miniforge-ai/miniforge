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
            [ai.miniforge.phase-opsv.protocol :as port]
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

(defn- ^{:stratum 0} fail-measurement [failure publish directory record]
  (if (= :verification-measurements (get-in record [:artifact/metadata :opsv/material-kind]))
    (throw failure)
    (publish directory record)))

(defn- ^{:stratum 0} assert-late-storage-evidence [ctx _directory]
  (let [unconfirmed [(random-uuid)]
        configured (assoc-in ctx [:execution/input :opsv/evidence-refs] unconfirmed)
        converged (reduce support/run-transformation configured (take 4 support/handlers))
        stored (phase/synthesize converged)
        legacy (phase/synthesize (update converged :execution/opts dissoc :opsv/artifact-directory))]
    (is (= [] (get-in stored [:opsv/operational-policy :operational-policy/evidence-refs])))
    (is (= unconfirmed (get-in legacy [:opsv/operational-policy :operational-policy/evidence-refs])))))

(defn- ^{:stratum 0} unexpected-verification [calls & _]
  (swap! calls inc))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-late-verify-storage [ctx directory]
  (let [legacy (-> ctx (update :execution/opts dissoc :opsv/artifact-directory)
                   (assoc-in [:execution/input :opsv/evidence-refs] [(random-uuid)]))
        synthesized (reduce fixtures/step legacy (take 5 support/handlers))
        enabled (assoc-in synthesized [:execution/opts :opsv/artifact-directory] directory)
        calls (atom 0)]
    (with-redefs [port/run-verification (partial unexpected-verification calls)]
      (is (anomaly/anomaly? (phase/verify enabled))))
    (is (zero? @calls))))

(defn- ^{:stratum 1} assert-partial-publication-exceptions [ctx _directory]
  (let [synthesized (reduce fixtures/step ctx (take 5 support/handlers))
        verified (phase/verify synthesized)]
    (doseq [failure [(java.io.IOException.) (AssertionError.) (InterruptedException.)]]
      (let [publish (partial fail-measurement failure artifact/publish!)
            result (with-redefs [artifact/publish! publish]
                     (boundary/publish-with-exception-handling synthesized :opsv/verify verified))
            interrupted? (Thread/interrupted)
            retained (get-in result [:anomaly/data :opsv/phase-output])
            assembly (evidence/get-opsv-assembly (:opsv/evidence-assembly-store synthesized)
                       (get-in synthesized [:execution/input :opsv/evidence-bundle-id]))]
        (is (= (instance? InterruptedException failure) interrupted?))
        (is (= (if (instance? Error failure) :fatal :unavailable) (:anomaly/type result)))
        (is (= (:opsv/artifact-refs assembly) (set (:opsv/artifact-refs retained))))
        (is (uuid? (get-in retained [:opsv/phase-artifact-ids :verification])))))))

(defn- ^{:stratum 1} assert-storage-preflight [ctx directory]
  (let [calls (atom 0)
        interceptor (lifecycle/interceptor {} :opsv/execute (partial record-transform calls))]
    (doseq [invalid [nil "" "relative" (str directory "/missing") (str directory "/.")]]
      (let [configured (assoc-in ctx [:execution/opts :opsv/artifact-directory] invalid)
            result ((:enter interceptor) configured)]
        (is (= :error (get-in result [:phase :result :status])))))
    (is (zero? @calls))))

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

(deftest ^{:stratum 2} enabling-storage-does-not-promote-caller-evidence-hints-test
  (fixtures/with-context assert-late-storage-evidence)
  (fixtures/with-context assert-late-verify-storage))

(deftest ^{:stratum 2} late-write-exceptions-retain-every-acknowledged-reference-test
  (fixtures/with-context assert-partial-publication-exceptions))

(deftest ^{:stratum 2} invalid-storage-paths-refuse-before-execution-test
  (fixtures/with-context assert-storage-preflight))

(deftest ^{:stratum 2} failed-publication-retains-only-confirmed-metric-references-test
  (fixtures/with-context assert-confirmed-failure-refs))

(deftest ^{:stratum 2} finalized-storage-refuses-before-any-phase-effect-test
  (fixtures/with-context assert-finalized-preflight))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.artifact-failure-test))
