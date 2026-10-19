;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.workflow-constructor-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.snowflake :as snowflake]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as fixture]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} start [stream workflow-id]
  (let [payload (:workflow/started fixture/payloads)]
    (events/workflow-started stream workflow-id (:workflow/spec payload)
                             (select-keys payload [:workflow/intent]))))

(defn- ^{:stratum 0} phase-start [stream workflow-id]
  (events/phase-started stream workflow-id :opsv/plan {}
                       {:phase/expected-agent :agent/none}))

(defn- ^{:stratum 0} phase-complete [stream workflow-id]
  (events/phase-completed stream workflow-id :opsv/plan
                         {:outcome :success :duration-ms 0 :artifacts []}))

(defn- ^{:stratum 0} assert-reopened [options receipts]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)))
    (when-not (anomaly/anomaly? stream)
      (try+ (is (= receipts (events/get-events stream)))
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-incomplete [stream _]
  (let [workflow-id (random-uuid)
        incomplete [(events/workflow-started stream workflow-id)
                    (events/workflow-started stream workflow-id {})
                    (events/phase-started stream workflow-id :opsv/plan {})
                    (events/phase-completed stream workflow-id :opsv/plan)
                    (events/phase-completed stream workflow-id :opsv/plan {:outcome :success})
                    (events/phase-completed stream workflow-id :opsv/plan {:duration-ms 0 :artifacts []})]]
    (doseq [draft incomplete]
      (is (not (contains? draft :event/sequence-number)))
      (is (anomaly/anomaly? (events/publish! stream draft))))
    (is (empty? (events/get-events stream)))
    (is (= 0 (:event/sequence-number (events/publish! stream (start stream workflow-id)))))))

(defn- ^{:stratum 1} verify-identity-failure [stream _]
  (let [failure (anomaly/anomaly :unavailable "fixture identity allocation failed" {})
        workflow-id (random-uuid)]
    (swap! stream assoc :snowflake-generator :fixture)
    (with-redefs [snowflake/next-id! (constantly failure)]
      (is (= [failure failure failure]
             [(start stream workflow-id) (phase-start stream workflow-id)
              (phase-complete stream workflow-id)])))
    (is (empty? (events/get-events stream)))))

(defn- ^{:stratum 1} verify-current [stream options]
  (let [workflow-id (random-uuid)
        drafts [(start stream workflow-id) (phase-start stream workflow-id)
                (phase-complete stream workflow-id)]]
    (is (empty? (events/get-events stream)))
    (doseq [draft drafts]
      (is (not (contains? draft :event/sequence-number)))
      (is (vector? (events/prepare-current-publication draft))))
    (let [receipts (mapv (partial events/publish! stream) drafts)]
      (is (= [0 1 2] (mapv :event/sequence-number receipts)))
      (is (= :agent/none (:phase/expected-agent (second receipts))))
      (is (= 0 (:phase/duration-ms (last receipts))))
      (is (= [] (:phase/artifacts (last receipts))))
      (is (= receipts (events/get-events stream)))
      (is (= receipts (mapv (partial events/publish! stream) drafts)))
      (events/close-current-event-stream! stream)
      (is (anomaly/anomaly? (events/publish! stream (start stream workflow-id))))
      (assert-reopened options receipts))))

(deftest ^{:stratum 1} legacy-call-shapes-and-sequence-reservations-remain-compatible
  (let [stream (events/create-event-stream {:sinks []})
        workflow-id (random-uuid)
        bare (events/workflow-started stream workflow-id)
        specified (start stream workflow-id)
        started (phase-start stream workflow-id)
        completed (phase-complete stream workflow-id)]
    (is (= [0 1 2 3] (mapv :event/sequence-number [bare specified started completed])))
    (is (not (contains? bare :workflow/spec)))
    (is (not (contains? specified :workflow/intent)))
    (is (not (contains? started :phase/expected-agent)))
    (is (= {} (:phase/context started)))
    (is (= :success (:phase/outcome completed)))
    (is (= 0 (:phase/duration-ms completed)))
    (is (= [] (:phase/artifacts completed)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} incomplete-current-facts-never-reserve-positions
  (fixture/with-stream verify-incomplete))

(deftest ^{:stratum 2} current-constructors-yield-durable-replayable-facts
  (fixture/with-stream verify-current))

(deftest ^{:stratum 2} identity-allocation-failures-are-not-decorated-as-events
  (fixture/with-stream verify-identity-failure))

(comment
  (clojure.test/run-tests 'ai.miniforge.event-stream.workflow-constructor-test))
