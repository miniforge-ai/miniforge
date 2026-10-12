;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-event-draft-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.chain-spec :as spec]
            [ai.miniforge.event-stream.chain-test-support :as support]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.snowflake :as snowflake]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fields [event-type]
  (dissoc (support/payload event-type) :event/type :event/version :scope/type))

(defn- ^{:stratum 0} allocate-id [calls _]
  (swap! calls inc)
  (random-uuid))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} all-chain-types-preserve-domain-identity-without-positions
  (let [stream (events/create-event-stream {:sinks []})
        before @stream
        org-id (random-uuid)]
    (doseq [event-type (keys support/required-fields)]
      (let [payload (assoc (fields event-type) :extension/retained :value)
            draft (events/create-chain-event-draft stream event-type payload {:org/id org-id})]
        (is (m/validate spec/Payload draft))
        (is (= payload (select-keys draft (keys payload))))
        (is (= org-id (:org/id draft)))
        (is (uuid? (:event/id draft)))
        (is (inst? (:event/timestamp draft)))
        (is (string? (:message draft)))
        (is (not (contains? draft :event/sequence-number)))))
    (is (= before @stream))))

(deftest ^{:stratum 1} invalid-payloads-never-allocate-event-identity
  (let [calls (atom 0)
        stream (events/create-event-stream {:sinks [] :snowflake-generator :fixture})
        payload (fields :chain/started)]
    (with-redefs [snowflake/next-id! (partial allocate-id calls)]
      (doseq [bad [nil [] {} (assoc payload :chain/id nil)
                   (assoc payload :chain/run-id :not-a-uuid)
                   (assoc payload :chain/definition-version "latest")]]
        (is (anomaly/anomaly? (events/create-chain-event-draft stream :chain/started bad))))
      (is (anomaly/anomaly? (events/create-chain-event-draft stream :chain/unknown payload)))
      (doseq [[event-type required] support/required-fields
              key required]
        (is (anomaly/anomaly? (events/create-chain-event-draft stream event-type
                                                              (dissoc (fields event-type) key)))))
      (doseq [key [:event/id :event/type :event/timestamp :event/version :event/sequence-number
                   :scope/type :message :org/id :auth/context]]
        (is (anomaly/anomaly? (events/create-chain-event-draft stream :chain/started (assoc payload key nil)))))
      (is (zero? @calls)))))

(deftest ^{:stratum 1} allocation-failures-are-not-merged-with-chain-fields
  (doseq [failure [(anomaly/anomaly :fault "canonical generator failure" {})
                   (response/make-anomaly :anomalies/fault "legacy generator failure" {})]]
    (let [stream (events/create-event-stream {:sinks [] :snowflake-generator failure})]
      (is (identical? failure (events/create-chain-event-draft stream :chain/started (fields :chain/started)))))))

(comment
  (fields :chain/started))
