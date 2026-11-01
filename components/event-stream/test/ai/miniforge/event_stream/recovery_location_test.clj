;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.recovery-location-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.recovery-draft-fixtures :as recovery]
            [ai.miniforge.event-stream.workflow-draft-fixtures :as journal]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} location []
  (events/checkpoint-location "/checkpoints" "/outside/physical-run"))

(defn- ^{:stratum 0} constructors []
  [[events/checkpoint-written :plan "/outside/phase/plan.edn"]
   [events/machine-snapshot-written :running "/outside/physical-run/machine.edn"]])

(defn- ^{:stratum 0} construct [stream id locator [constructor state path]]
  (if locator
    (constructor stream id state path 1 locator)
    (constructor stream id state path 1)))

(defn- ^{:stratum 0} verify-reopened [expected stream _options]
  (is (= expected (events/get-events stream))))

(defn- ^{:stratum 0} invalid-locations [locator]
  (let [sensitive-path "AKIAIOSFODNN7EXAMPLE"]
    [nil {} (dissoc locator :checkpoint/root) (dissoc locator :checkpoint/run-directory)
     (assoc locator :checkpoint/root "") (assoc locator :checkpoint/run-directory 42)
     (assoc locator :checkpoint/root sensitive-path)
     (assoc locator :checkpoint/run-directory sensitive-path)]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-profiles [stream options]
  (let [id (random-uuid)
        locator (location)
        legacy (mapv (partial construct stream id nil) (constructors))
        rooted (mapv (partial construct stream id "/checkpoints") (constructors))
        located (mapv (partial construct stream id locator) (constructors))
        drafts (into (into legacy rooted) located)
        receipts (mapv (partial events/publish! stream) drafts)]
    (is (= ["1.0.0" "1.0.0" "1.0.0" "1.0.0" "1.1.0" "1.1.0"]
           (mapv :event/version receipts)))
    (is (= [nil nil nil nil locator locator] (mapv :checkpoint/location receipts)))
    (is (= [nil nil "/checkpoints" "/checkpoints" nil nil] (mapv :checkpoint/root receipts)))
    (is (= (repeat (count drafts) id) (map :workflow/id receipts)))
    (is (= receipts (mapv (partial events/publish! stream) drafts)))
    (events/close-current-event-stream! stream)
    (journal/with-options options (partial verify-reopened receipts))))

(defn- ^{:stratum 1} verify-invalid [stream _options]
  (let [id (random-uuid)
        draft (recovery/draft id :workflow/checkpoint-written)
        located (assoc draft :event/version "1.1.0")]
    (doseq [locator (invalid-locations (location))]
      (is (anomaly/any-anomaly? (events/publish! stream (assoc located :checkpoint/location locator)))))
    (is (anomaly/any-anomaly? (events/publish! stream (assoc located :event/version "2.0.0"))))
    (doseq [kind [:workflow/checkpoint-write-failed :workflow/machine-snapshot-write-failed
                  :workflow/resumed :workflow/spec-hash-mismatch]]
      (is (anomaly/any-anomaly?
           (events/publish! stream (assoc (recovery/draft id kind) :event/version "1.1.0")))))
    (is (= 0 (:event/sequence-number (events/publish! stream draft))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} located-and-legacy-write-receipts-survive-reopen-and-retry
  (journal/with-stream verify-profiles))

(deftest ^{:stratum 2} invalid-locations-and-unrelated-version-profiles-reserve-nothing
  (journal/with-stream verify-invalid))

(comment ::located-recovery-receipts)
