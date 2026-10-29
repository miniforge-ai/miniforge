;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-decision-acknowledgment-test
  "A gated phase advances only after its :gate/decision event is acknowledged."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.gate.interface :as gate]
            [ai.miniforge.workflow.gate-decision-test-support :as support]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} gated-step [gates ctx]
  (support/execute (support/interceptor gates) ctx false))

(defn- ^{:stratum 0} throwing [thrown & _args]
  (throw thrown))

(defn- ^{:stratum 0} other-envelope [_stream event]
  (assoc event :envelope/id (random-uuid)))

(defn- ^{:stratum 0} assert-refused [[result calls] scenario]
  (let [error (last (:execution/errors result))
        recorded (get-in result [:execution/phase-results :review])]
    (testing scenario
      (is (= :failed (:execution/status result)))
      (is (empty? calls) "neither the DAG handoff nor the machine transition ran")
      (is (= :gate-decision (:boundary error)))
      (is (= :review (:phase error)))
      (is (anomaly/any-anomaly? (:anomaly error)))
      (is (= :failed (:status recorded)) "recorded failed, so a resume re-runs the phase")
      (is (= support/output (get-in recorded [:result :output])) "phase output kept")
      (is (some #(= :gate/decision-unacknowledged (:code %)) (:phase/gate-errors recorded))))))

(deftest ^{:stratum 0} unacknowledged-plan-never-reaches-the-dag-executor
  (let [[result calls] (support/execute (support/interceptor [:review-approved])
                                        (support/context nil) true)]
    (is (empty? calls))
    (is (not (:dag/took-over result)))
    (is (= :gate-decision (:boundary (last (:execution/errors result)))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-durable-advance [stream _options]
  (let [[result calls] (gated-step [:review-approved] (support/context stream))
        [decision] (support/decisions stream)]
    (is (= [:dag :transition] calls))
    (is (empty? (:execution/errors result)))
    (is (= :review (:gate/phase decision)))
    (is (= 0 (:event/sequence-number decision)))))

(defn- ^{:stratum 1} verify-closed-journal-refuses [stream _options]
  (events/close-current-event-stream! stream)
  (assert-refused (gated-step [:review-approved] (support/context stream)) "closed current journal"))

(deftest ^{:stratum 1} acknowledged-decision-advances-and-is-published-for-its-phase
  (let [stream (support/legacy-stream)
        [result calls] (gated-step [:review-approved] (support/context stream))
        [decision] (support/decisions stream)]
    (is (= [:dag :transition] calls))
    (is (empty? (:execution/errors result)))
    (is (= :review (:gate/phase decision)) "phase name read from the phase map's :name")
    (is (nat-int? (:event/sequence-number decision)))
    (is (= (get-in result [:execution/phase-results :review :phase/decision-envelope])
           (:gate/decision-envelope decision)))))

(deftest ^{:stratum 1} interrupted-publication-refuses-and-restores-the-interrupt
  (let [[step-result interrupted?]
        (with-redefs [events/publish! (partial throwing (InterruptedException.))]
          (let [step-result (gated-step [:review-approved] (support/context (support/legacy-stream)))]
            ;; Read and clear before any assertion: clojure.test can consume the flag.
            [step-result (Thread/interrupted)]))]
    (is interrupted?)
    (assert-refused step-result "interrupted publication")))

(deftest ^{:stratum 1} fatal-publication-error-propagates
  (with-redefs [events/publish! (partial throwing (AssertionError. "fatal"))]
    (is (thrown? AssertionError
                 (gated-step [:review-approved] (support/context (support/legacy-stream)))))))

(deftest ^{:stratum 1} ungated-phase-advances-without-a-stream
  (let [[result calls] (gated-step [] (support/context nil))]
    (is (= [:dag :transition] calls))
    (is (empty? (:execution/errors result)))
    (is (not (contains? (get-in result [:execution/phase-results :review])
                        :phase/decision-unacknowledged)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} durable-decision-advances-through-a-current-journal
  (journal/with-stream verify-durable-advance))

(deftest ^{:stratum 2} unacknowledged-decision-refuses-before-dag-and-transition
  (assert-refused (gated-step [:review-approved] (support/context nil)) "missing stream")
  (let [stream (support/legacy-stream)
        ctx (support/context stream)]
    (events/quiesce! stream {:workflow-id (:execution/id ctx)})
    (assert-refused (gated-step [:review-approved] ctx) "rejected receipt"))
  (journal/with-stream verify-closed-journal-refuses)
  (with-redefs [events/publish! (partial throwing (ex-info "sink offline" {}))]
    (assert-refused (gated-step [:review-approved] (support/context (support/legacy-stream)))
                    "publication exception"))
  (with-redefs [events/publish! (constantly (anomaly/anomaly :unavailable "refused" {}))]
    (assert-refused (gated-step [:review-approved] (support/context (support/legacy-stream)))
                    "anomaly receipt"))
  (with-redefs [events/publish! other-envelope]
    (assert-refused (gated-step [:review-approved] (support/context (support/legacy-stream)))
                    "mismatched receipt"))
  (with-redefs [gate/gates->envelope (constantly nil)]
    (assert-refused (gated-step [:review-approved] (support/context (support/legacy-stream)))
                    "no envelope minted")))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.gate-decision-acknowledgment-test))
