;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.gate-test-support :as f]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [throw+ try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} validate [ctx]
  (transition/apply-gate-validation f/interceptor (f/phase-result :approved) ctx))

(defn- ^{:stratum 0} assert-blocked [result]
  (is (= :failed (:status result)))
  (is (= :error (get-in result [:result :status])))
  (is (= :phase/fail (transition/determine-phase-event {} result)))
  (is (anomaly/any-anomaly? (:phase/gate-publication-failure result)))
  (is (seq (:phase/gate-errors result))))

(defn- ^{:stratum 0} raised [value & _]
  (throw+ value))

(defn- ^{:stratum 0} rewrite-receipt [transform _stream event]
  (transform event))

(defn- ^{:stratum 0} decision-only [override publish stream event]
  (if (= :gate/decision (:event/type event))
    (override stream event)
    (publish stream event)))

(defn- ^{:stratum 0} record-leave [calls ctx]
  (swap! calls inc)
  ctx)

(deftest ^{:stratum 0} acknowledged-denial-is-a-real-phase-failure
  (let [ctx (f/context)
        phase-result (f/phase-result :rejected)
        result (transition/apply-gate-validation f/interceptor phase-result ctx)
        event (first (filter #(= :gate/decision (:event/type %)) (events/get-events (:event-stream ctx))))]
    (is (= :phase/fail (transition/determine-phase-event {} result)))
    (is (= :failed (:status result)))
    (is (= :deny (get-in event [:gate/decision-envelope :envelope/decision])))
    (is (= (get-in phase-result [:result :output]) (get-in result [:result :output])))
    (is (seq (:phase/gate-failures result)))
    (is (nil? (:phase/gate-publication-failure result)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} missing-stream-cannot-allow-a-gated-phase
  (assert-blocked (validate {})))

(deftest ^{:stratum 1} unsuccessful-envelope-minting-blocks-advancement
  (doseq [minted [nil {} (anomaly/anomaly :unavailable "mint failed" {})]]
    (with-redefs [gate/gates->envelope (constantly minted)]
      (assert-blocked (validate (f/context))))))

(deftest ^{:stratum 1} acknowledged-decision-preserves-the-runtime-envelope
  (doseq [location [[:event-stream] [:execution/event-stream] [:execution/opts :event-stream]]]
    (let [ctx (f/context)
          stream (:event-stream ctx)
          result (validate (assoc-in (dissoc ctx :event-stream) location stream))
          event (first (filter #(= :gate/decision (:event/type %)) (events/get-events stream)))]
      (is (= :completed (:status result)))
      (is (= :review (:gate/phase event)))
      (is (= (:execution/id ctx) (:workflow/id event)))
      (is (= (:phase/decision-envelope result) (:gate/decision-envelope event))))))

(deftest ^{:stratum 1} rejected-or-mismatched-receipts-block-advancement
  (doseq [transform [(constantly nil) #(assoc % :rejected? true)
                     #(assoc % :event/sequence-number -1) #(assoc % :event/sequence-number 17)
                     #(assoc % :envelope/id (random-uuid)) #(assoc % :gate/phase :release)
                     #(assoc-in % [:gate/decision-envelope :envelope/decision] :deny)
                     (constantly (anomaly/anomaly :unavailable "refused" {}))
                     (constantly (response/make-anomaly :anomalies/unavailable "refused"))]]
    (with-redefs [events/publish! (partial decision-only (partial rewrite-receipt transform) events/publish!)]
      (assert-blocked (validate (f/context))))))

(deftest ^{:stratum 1} publication-exceptions-block-without-swallowing-fatal-errors
  (doseq [failure [(Exception. "failed") (anomaly/anomaly :unavailable "refused" {})]]
    (with-redefs [events/publish! (partial decision-only (partial raised failure) events/publish!)]
      (assert-blocked (validate (f/context)))))
  (with-redefs [events/publish! (partial decision-only (partial raised (AssertionError. "fatal")) events/publish!)]
    (is (thrown? AssertionError (validate (f/context)))))
  (try+
    (with-redefs [events/publish! (partial decision-only (partial raised (InterruptedException.)) events/publish!)]
      (let [result (validate (f/context))
            interrupted? (.isInterrupted (Thread/currentThread))]
        (is interrupted?)
        (assert-blocked result)))
    (finally (Thread/interrupted))))

(deftest ^{:stratum 1} unavailable-audit-prevents-dependent-leave-processing
  (let [calls (atom 0)
        interceptor (f/lifecycle-interceptor (partial record-leave calls))
        [ctx result] (with-redefs [events/publish! (partial decision-only (constantly nil) events/publish!)]
                       (lifecycle/execute-phase-lifecycle interceptor (f/context)))]
    (assert-blocked result)
    (is (= result (:phase ctx)))
    (is (zero? @calls))))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.gate-publication-test))
