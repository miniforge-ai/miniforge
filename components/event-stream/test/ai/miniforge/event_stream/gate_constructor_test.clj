;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.gate-constructor-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.decision-envelope.interface :as decision]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.publication-store :as store]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} current-stream []
  (with-redefs [store/durable-store (fn [_] (store/volatile-store))]
    (events/create-current-event-stream {:journal-directory "gate-constructor-fixture"})))

(defn- ^{:stratum 0} envelope
  ([obligations] (envelope [] obligations))
  ([reasons obligations]
   (decision/envelope reasons obligations
                      {:pins/pack-revision nil :pins/rule-ids [:fixture/rule] :pins/event-watermark nil})))

(defn- ^{:stratum 0} violation-reason []
  {:reason/code :reason/rule-violation
   :reason/rule-id :fixture/rule
   :reason/severity :high
   :reason/detail "fixture violation"})

(defn- ^{:stratum 0} rule-options [decision]
  {:envelope-id (:envelope/id decision)
   :severity :high
   :enforcement :hard-halt
   :violation {:message "fixture violation" :artifact-path nil :match-count 1}})

(defn- ^{:stratum 0} payload [event]
  (dissoc event :event/id :event/timestamp :event/sequence-number :scope/type))

(defn- ^{:stratum 0} construct-both [stream workflow-id decision options]
  [(events/phase-decision stream workflow-id :implement decision)
   (events/gate-rule-applied stream workflow-id :implement :fixture/rule :failed options)])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} current-constructors-defer-positions-and-preserve-legacy-payloads
  (let [stream (current-stream)
        legacy (events/create-event-stream {:sinks []})
        workflow-id (random-uuid)
        decision (envelope [(violation-reason)] [])
        options (rule-options decision)
        drafts (construct-both stream workflow-id decision options)
        old-events (construct-both legacy workflow-id decision options)
        receipts (mapv (partial events/publish! stream) drafts)]
    (is (= (mapv payload old-events) (mapv payload drafts)))
    (is (= decision (:gate/decision-envelope (first drafts))))
    (is (= (:violation options) (:rule/violation (second drafts))))
    (is (= [0 1] (mapv :event/sequence-number old-events)))
    (is (every? #(not (contains? % :event/sequence-number)) drafts))
    (is (empty? (:sequence-numbers @stream)))
    (is (= [:workflow :workflow] (mapv :scope/type drafts)))
    (is (= [0 1] (mapv :event/sequence-number receipts)))
    (is (= receipts (mapv (partial events/publish! stream) drafts)))
    (is (= receipts (events/get-events stream)))
    (events/close-current-event-stream! stream)))

(deftest ^{:stratum 1} each-rule-status-and-runtime-decision-is-admitted
  (let [stream (current-stream)
        workflow-id (random-uuid)]
    (doseq [obligations [[] [{:obligation/type :obligation/approval-required}]
                         [{:obligation/type :obligation/audit-recorded}]]]
      (let [value (envelope obligations)
            event (events/phase-decision stream workflow-id :implement value)
            receipt (events/publish! stream event)]
        (is (= value (:gate/decision-envelope receipt)))))
    (doseq [status [:passed :failed :skipped-by-phase :not-applicable :guidance]]
      (let [event (events/gate-rule-applied stream workflow-id :implement :fixture/rule status)
            receipt (events/publish! stream event)]
        (is (= status (:rule/status receipt)))))
    (is (= (range 8) (map :event/sequence-number (events/get-events stream))))
    (events/close-current-event-stream! stream)))

(deftest ^{:stratum 1} invalid-options-remain-invalid-and-cannot-overwrite-event-identity
  (let [stream (current-stream)
        workflow-id (random-uuid)
        options {:scope/type :chain :workflow/id (random-uuid) :event/sequence-number 42}
        event (events/gate-rule-applied stream workflow-id :implement :fixture/rule :passed options)]
    (is (= workflow-id (:workflow/id event)))
    (is (= :workflow (:scope/type event)))
    (is (not (contains? event :event/sequence-number)))
    (doseq [extra [{:severity false} {:enforcement :unknown} {:envelope-id "not-a-uuid"}]]
      (let [draft (events/gate-rule-applied stream workflow-id :implement :fixture/rule :passed extra)]
        (is (anomaly/anomaly? (events/publish! stream draft)))))
    (is (= 0 (:event/sequence-number (events/publish! stream event))))
    (events/close-current-event-stream! stream)))

(deftest ^{:stratum 1} identity-failure-remains-an-anomaly-in-both-profiles
  (doseq [failure [(anomaly/anomaly :unavailable "fixture generation failure" {})
                  (response/make-anomaly :anomalies/unavailable "fixture generation failure" {})]
          stream [(current-stream) (events/create-event-stream {:sinks []})]]
    (swap! stream assoc :snowflake-generator failure)
    (let [decision (envelope [])
          results (construct-both stream (random-uuid) decision (rule-options decision))]
      (is (every? #(identical? failure %) results))
      (is (empty? (:sequence-numbers @stream)))
      (is (empty? (events/get-events stream))))
    (when (events/current-event-stream? stream)
      (events/close-current-event-stream! stream))))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.gate-constructor-test))
