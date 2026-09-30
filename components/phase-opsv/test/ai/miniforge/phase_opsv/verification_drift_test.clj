;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.verification-drift-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as f]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.lifecycle :as lifecycle]
            [ai.miniforge.phase-opsv.test-support :as support]
            [ai.miniforge.phase-opsv.verification-drift-fixtures :refer [changed-environment stale-measurement]]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-phase [ctx]
  (let [interceptor (lifecycle/interceptor {} :opsv/verify phase/verify)]
    ((:leave interceptor) ((:enter interceptor) ctx))))

(defn- ^{:stratum 0} drift-events [ctx]
  (filterv #(= :opsv.drift/detected (:event/type %)) (events/get-events (:event-stream ctx))))

(defn- ^{:stratum 0} prepared [callback ctx]
  (let [ready (reduce f/step ctx (take 5 support/handlers))
        adapter (phase/functional-adapter identity identity callback)]
    (assoc-in ready [:execution/opts :opsv/adapter] adapter)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-correlated-drift [ctx _directory]
  (let [completed (verify-phase (prepared changed-environment ctx))
        failure (get-in completed [:phase :result :output])
        observed (drift-events completed)
        event (first observed)
        refs (get-in completed [:execution/input :opsv/evidence-assembly :opsv/event-refs])]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (= :conflict (:anomaly/type failure)))
    (is (= 1 (count observed)))
    (is (= (:execution/id completed) (:workflow/id event)))
    (is (= (get-in completed [:execution/input :opsv/evidence-bundle-id]) (:opsv/evidence-bundle-id event)))
    (is (= :environment-fingerprint (:opsv/signal event)))
    (is (= "changed" (get-in event [:opsv/deviation :observed :config-hash])))
    (is (uuid? (get-in event [:opsv/deviation :verification/id])))
    (is (true? (:opsv/suggested-rerun? event)))
    (is (contains? refs (:event/id event)))))

(defn- ^{:stratum 1} assert-publication-failure [ctx _directory]
  (let [ready (prepared changed-environment ctx)
        refused (anomaly/anomaly :unavailable "test publication refused" {})
        completed (with-redefs [events/publish! (constantly refused)] (verify-phase ready))
        failure (get-in completed [:phase :result :output])]
    (is (= :conflict (:anomaly/type failure)))
    (is (= refused (get-in failure [:anomaly/data :opsv/drift-publication-failure])))
    (is (empty? (drift-events completed)))))

(defn- ^{:stratum 1} assert-stale-receipt [ctx _directory]
  (let [completed (verify-phase (prepared stale-measurement ctx))]
    (is (= :error (get-in completed [:phase :result :status])))
    (is (empty? (drift-events completed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} correlated-environment-change-emits-drift-and-fails-verification
  (f/with-context assert-correlated-drift))

(deftest ^{:stratum 2} publication-refusal-preserves-drift-and-failure
  (f/with-context assert-publication-failure))

(deftest ^{:stratum 2} stale-receipt-does-not-claim-drift
  (f/with-context assert-stale-receipt))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.verification-drift-test))
