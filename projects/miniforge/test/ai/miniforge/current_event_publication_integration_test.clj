;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.current-event-publication-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-journal-test-support :as support]
            [ai.miniforge.event-stream.chain-test-support :as chain]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.journal-storage :as storage]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} draft [stream]
  (let [payload (chain/payload :chain/started)
        fields (dissoc payload :event/type :event/version :scope/type)]
    (assoc (events/create-chain-event-draft stream :chain/started fields)
           :extension/data '(one two))))

(defn- ^{:stratum 0} reject-contaminated! [event directory]
  (let [store (storage/open! directory)
        scope [:chain (:chain/run-id event)]]
    (try+
      (is (not (anomaly/anomaly? (storage/commit! store scope event))))
      (finally (storage/close! store)))
    (is (anomaly/anomaly? (events/create-current-event-stream {:journal-directory directory})))
    (let [reopened (storage/open! directory)]
      (is (not (anomaly/anomaly? reopened)) "failed typed recovery releases journal ownership")
      (storage/close! reopened))))

(defn- ^{:stratum 0} reopened! [directory original receipt]
  (let [observed (atom [])
        stream (events/create-current-event-stream
                {:journal-directory directory :sinks [(partial swap! observed conj)]})]
    (is (not (anomaly/anomaly? stream)))
    (try+
      (is (= [receipt] (events/get-events stream)))
      (is (= receipt (events/publish! stream original)))
      (is (empty? @observed))
      (let [next-event (assoc original :event/id (random-uuid))
            next-receipt (events/publish! stream next-event)]
        (is (= 1 (:event/sequence-number next-receipt)))
        (is (= [next-receipt] @observed)))
      (finally (events/close-current-event-stream! stream)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} non-current-journals-are-refused-without-retaining-ownership
  (let [stream (events/create-event-stream {:sinks []})
        event (draft stream)]
    (doseq [bad [(assoc event :event/version "1.0.0")
                 (assoc event :password "unredacted")]]
      (support/with-directory (partial reject-contaminated! bad)))))

(defn- ^{:stratum 1} publish-and-recover! [directory]
  (let [stream (events/create-current-event-stream {:journal-directory directory})
        original (draft stream)
        receipt (events/publish! stream original)]
    (try+
      (is (= 0 (:event/sequence-number receipt)))
      (is (= '(one two) (:extension/data receipt)))
      (is (anomaly/anomaly? (events/create-current-event-stream {:journal-directory directory})))
      (finally (events/close-current-event-stream! stream)))
    (is (anomaly/anomaly? (events/publish! stream (draft stream))))
    (reopened! directory original receipt)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} durable-publication-survives-close-and-reopen
  (support/with-directory publish-and-recover!))

(comment
  ::durable-current-stream-integration)
