;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-constructor-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.recovery-constructor-fixtures :as fixture]
            [ai.miniforge.event-stream.recovery-draft-fixtures :as contract]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-payload [event]
  (let [payload (get (contract/payloads) (:event/type event))]
    (is (= payload (select-keys event (keys payload))))
    (is (string? (:message event)))
    (is (not (re-find #"\{[^}]+\}" (:message event))))))

(defn- ^{:stratum 0} verify-reopened [options scope receipts]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)))
    (when-not (anomaly/anomaly? stream)
      (try+ (is (= receipts (events/get-events stream {:scope scope})))
        (finally (events/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} verify-allocation-refusal [stream _options]
  (let [failure (anomaly/anomaly :unavailable "Identity allocation unavailable" {})]
    (swap! stream assoc :snowflake-generator failure)
    (doseq [case fixture/cases]
      (is (identical? failure (fixture/construct stream (random-uuid) case))))
    (is (empty? (events/get-events stream)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-current [stream options]
  (let [workflow-id (random-uuid)
        drafts (mapv (partial fixture/construct stream workflow-id) fixture/cases)]
    (is (empty? (events/get-events stream)))
    (doseq [draft drafts]
      (verify-payload draft)
      (is (= workflow-id (:workflow/id draft)))
      (is (= :workflow (:scope/type draft)))
      (is (not (contains? draft :event/sequence-number))))
    (let [receipts (mapv (partial events/publish! stream) drafts)]
      (is (= (range 6) (map :event/sequence-number receipts)))
      (is (= receipts (mapv (partial events/publish! stream) drafts)))
      (events/close-current-event-stream! stream)
      (verify-reopened options [:workflow workflow-id] receipts))))

(deftest ^{:stratum 1} legacy-constructors-retain-envelope-sequencing
  (let [stream (events/create-event-stream {:sinks []})
        drafts (mapv (partial fixture/construct stream (random-uuid)) fixture/cases)]
    (doseq [draft drafts] (verify-payload draft))
    (is (= (range 6) (map :event/sequence-number drafts)))
    (is (empty? (events/get-events stream)))))

(deftest ^{:stratum 1} identity-allocation-anomalies-are-not-decorated
  (journal/with-stream verify-allocation-refusal))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-constructors-produce-durable-sequence-free-drafts
  (journal/with-stream verify-current))
