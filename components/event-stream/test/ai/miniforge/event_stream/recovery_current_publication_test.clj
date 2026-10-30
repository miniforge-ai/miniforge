;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-current-publication-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.recovery-draft-fixtures :as fixture]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-reopen [options drafts expected]
  (let [stream (events/create-current-event-stream options)]
    (try+
      (is (= expected (events/get-events stream)))
      (is (= expected (mapv (partial events/publish! stream) drafts)))
      (finally (events/close-current-event-stream! stream)))))

(defn- ^{:stratum 0} assert-no-reservations! [stream workflow-id]
  (is (empty? (events/get-events stream)))
  (let [draft (fixture/draft workflow-id :workflow/resumed)
        receipt (events/publish! stream draft)]
    (is (= 0 (:event/sequence-number receipt)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-required-fields [stream _options]
  (let [workflow-id (random-uuid)]
    (doseq [[event-type payload] (fixture/payloads)
            :let [draft (fixture/draft workflow-id event-type)]
            field (remove #{:checkpoint/root} (keys payload))]
      (is (anomaly/any-anomaly? (events/publish! stream (dissoc draft field))))
      (is (anomaly/any-anomaly? (events/publish! stream (assoc draft field nil)))))
    (assert-no-reservations! stream workflow-id)))

(defn- ^{:stratum 1} verify-invalid-values [stream _options]
  (let [workflow-id (random-uuid)]
    (doseq [[event-type field invalid]
          [[:workflow/checkpoint-written :checkpoint/size-bytes -1]
           [:workflow/machine-snapshot-written :snapshot/size-bytes -1]
           [:workflow/checkpoint-write-failed :failure/class :disk-full]
           [:workflow/machine-snapshot-write-failed :machine/state "failed"]
           [:workflow/resumed :skipping [:plan "implement"]]
           [:workflow/checkpoint-written :checkpoint/path "AKIAIOSFODNN7EXAMPLE"]
           [:workflow/checkpoint-written :checkpoint/root "AKIAIOSFODNN7EXAMPLE"]
           [:workflow/machine-snapshot-written :snapshot/path "AKIAIOSFODNN7EXAMPLE"]
           [:workflow/spec-hash-mismatch :expected-hash "AKIAIOSFODNN7EXAMPLE"]]]
      (let [draft (fixture/draft workflow-id event-type)]
        (is (anomaly/any-anomaly? (events/publish! stream (assoc draft field invalid))))))
    (assert-no-reservations! stream workflow-id)))

(defn- ^{:stratum 1} verify-durability [stream options]
  (let [workflow-id (random-uuid)
        drafts (mapv (partial fixture/draft workflow-id) (keys (fixture/payloads)))
        receipts (mapv (partial events/publish! stream) drafts)]
    (is (= (vec (range (count drafts))) (mapv :event/sequence-number receipts)))
    (is (= receipts (events/get-events stream {:scope [:workflow workflow-id]})))
    (events/close-current-event-stream! stream)
    (verify-reopen options drafts receipts)))

(defn- ^{:stratum 1} verify-root-less-history [stream options]
  ;; Announcements written before they carried :checkpoint/root.
  (let [workflow-id (random-uuid)
        drafts (mapv #(dissoc (fixture/draft workflow-id %) :checkpoint/root)
                     [:workflow/checkpoint-written :workflow/machine-snapshot-written])
        receipts (mapv (partial events/publish! stream) drafts)]
    (is (not-any? anomaly/any-anomaly? receipts))
    (events/close-current-event-stream! stream)
    (verify-reopen options drafts receipts)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} malformed-recovery-facts-do-not-consume-sequences
  (journal/with-stream verify-required-fields)
  (journal/with-stream verify-invalid-values))

(deftest ^{:stratum 2} recovery-receipts-survive-reopen-and-duplicate-publication
  (journal/with-stream verify-durability))

(deftest ^{:stratum 2} announcements-without-a-root-still-recover
  (journal/with-stream verify-root-less-history))

(comment ::recovery-admission-regressions)
