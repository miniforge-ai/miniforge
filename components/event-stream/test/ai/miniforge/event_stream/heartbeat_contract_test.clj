;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.heartbeat-contract-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.event-type-registry :as registry]
            [ai.miniforge.event-stream.heartbeat-contract-fixtures :as fixture]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.publication-identity :as identity]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} missing-fields [draft]
  (for [field (keys draft), remove-field [dissoc #(assoc %1 %2 nil)]]
    (remove-field draft field)))

(defn- ^{:stratum 0} contradictions [draft]
  (for [[field value] [[:event/version "1.0.0"] [:scope/type :system]
                       [:heartbeat/activation-id "not-a-uuid"]
                       [:heartbeat/sequence 0] [:heartbeat/interval-ms 0]
                       [:heartbeat/elapsed-ms -1] [:phase/progress-status :healthy]
                       [:phase/progress-gap-ms -1] [:phase/progress-gap-ms 31]
                       [:phase/progress-count -1] [:phase/events-emitted 1]
                       [:phase/last-event-at nil] [:phase/gap-since-last-event-ms nil]]]
    (assoc draft field value)))

(defn- ^{:stratum 0} verify-reopened [options scope receipts]
  (let [stream (events/create-current-event-stream options)]
    (is (not (anomaly/anomaly? stream)))
    (when-not (anomaly/anomaly? stream)
      (try+ (is (= receipts (events/get-events stream {:scope scope})))
        (finally (events/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} verify-provenance [draft]
  (doseq [field (keys (fixture/payload (:phase/progress-status draft)))]
    (is (not= (identity/projection draft) (identity/projection (dissoc draft field))))))

(defn- ^{:stratum 0} verify-allocation-refusal [stream _]
  (let [failure (anomaly/anomaly :unavailable "fixture allocation failure" {})]
    (swap! stream assoc :snowflake-generator failure)
    (is (identical? failure (fixture/draft stream (random-uuid) :unknown)))
    (is (empty? (events/get-events stream)))))

(deftest ^{:stratum 0} heartbeat-registration-preserves-ephemeral-retention-contract
  (let [entries (filter #(= :workflow/phase-heartbeat (:event-type %)) registry/event-type-registry)
        entry (first entries)]
    (is (= 1 (count entries)))
    (is (= :workflow (:scope entry)))
    (is (= :ephemeral (:retention-class entry)))
    (is (= 24 (:minimum-retention-hours entry)))
    (is (= "2.0.0" (:payload-version entry)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-refusals [stream _]
  (let [workflow-id (random-uuid)
        none (fixture/draft stream workflow-id :none)
        observed (fixture/draft stream workflow-id :observed)
        unknown (fixture/draft stream workflow-id :unknown)
        bad-unknown (map #(assoc unknown % nil) (keys (fixture/progress :observed)))]
    (doseq [draft (concat (missing-fields observed) (contradictions observed)
                          bad-unknown [(assoc none :phase/progress-gap-ms 10)
                                       (assoc none :phase/last-progress-at nil)
                                       (assoc observed :phase/progress-count 0)])]
      (is (anomaly/anomaly? (events/publish! stream draft)) (pr-str draft)))
    (is (empty? (events/get-events stream)))
    (is (= 0 (:event/sequence-number (events/publish! stream none))))))

(defn- ^{:stratum 1} verify-current [stream options]
  (let [workflow-id (random-uuid)
        drafts (mapv (partial fixture/draft stream workflow-id) [:none :observed :unknown])
        drafts (update drafts 0 assoc :extension/fixture true)]
    (is (empty? (events/get-events stream)))
    (doseq [draft drafts]
      (is (= "2.0.0" (:event/version draft)))
      (is (= :workflow (:scope/type draft)))
      (is (= workflow-id (:workflow/id draft)))
      (is (not (contains? draft :event/sequence-number)))
      (verify-provenance draft))
    (let [receipts (mapv (partial events/publish! stream) drafts)]
      (is (= [0 1 2] (mapv :event/sequence-number receipts)))
      (is (= receipts (mapv (partial events/publish! stream) drafts)))
      (events/close-current-event-stream! stream)
      (is (anomaly/anomaly? (events/publish! stream (first drafts))))
      (verify-reopened options [:workflow workflow-id] receipts))))

(deftest ^{:stratum 1} current-heartbeat-identity-allocation-fails-without-decoration
  (journal/with-stream verify-allocation-refusal))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-heartbeats-preserve-distinct-progress-states-and-replay
  (journal/with-stream verify-current))

(deftest ^{:stratum 2} invalid-heartbeats-do-not-consume-journal-positions
  (journal/with-stream verify-refusals))

(comment ::heartbeat-contract-acceptance)
