;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.envelope-draft-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} drafts-do-not-reserve-or-publish
  (let [stream (events/create-event-stream {:sinks []})
        before @stream
        workflow-id (random-uuid)
        draft (events/create-event-draft stream :example/created workflow-id "example")]
    (is (= before @stream))
    (is (uuid? (:event/id draft)))
    (is (inst? (:event/timestamp draft)))
    (is (= workflow-id (:workflow/id draft)))
    (is (not (contains? draft :event/sequence-number)))
    (is (zero? (:event/sequence-number (events/create-envelope stream :example/created workflow-id "legacy"))))))

(deftest ^{:stratum 0} identity-options-cannot-overwrite-envelope-fields
  (let [stream (events/create-event-stream {:sinks []})
        org-id (random-uuid)
        opts {:org/id org-id :workspace/id nil :auth/context false
              :event/id :spoofed :event/sequence-number 99}
        draft (events/create-event-draft stream :example/created nil "example" opts)]
    (is (= org-id (:org/id draft)))
    (is (uuid? (:event/id draft)))
    (is (not (contains? draft :event/sequence-number)))
    (is (not (contains? draft :workspace/id)))
    (is (not (contains? draft :auth/context)))))

(deftest ^{:stratum 0} generator-failures-remain-unchanged
  (doseq [failure [(anomaly/anomaly :fault "canonical failure" {})
                   (response/make-anomaly :anomalies/fault "legacy failure" {})]]
    (let [stream (events/create-event-stream {:sinks [] :snowflake-generator failure})
          draft (events/create-event-draft stream :example/created nil "example" {:org/id (random-uuid)})]
      (is (identical? failure draft))
      (is (identical? failure (events/create-envelope stream :example/created nil "legacy")))
      (is (empty? (:sequence-numbers @stream))))))

(comment
  ::uncommitted-envelope)
