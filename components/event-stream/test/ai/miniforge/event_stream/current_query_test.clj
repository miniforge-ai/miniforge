;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.current-query-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.boundary.current-query :as query]
            [ai.miniforge.event-stream.chain-test-support :as chain]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} chain-cross-references-do-not-enroll-events-in-workflow-scope
  (let [workflow-id (random-uuid)
        event (assoc (chain/payload :chain/started) :workflow/id workflow-id)
        other (chain/payload :chain/started)
        scope [:chain (:chain/run-id event)]]
    (is (= [event] (query/events [event other] {:scope scope})))
    (is (= [] (query/events [event other] {:workflow-id workflow-id})))
    (is (= [] (query/events [event other] {:scope [:workflow workflow-id]})))
    (is (= [] (query/events [event other] {:scope scope :workflow-id workflow-id})))
    (is (= [other] (query/events [event other] {:offset 1 :limit 1})))
    (is (= [] (query/events [event other] {:event-type :chain/completed})))
    (is (= [event other] (query/events [event other] nil)))))

(deftest ^{:stratum 0} invalid-query-options-cannot-fall-back-to-all-events
  (doseq [opts [{:scope nil} {:scope [:chain nil]} {:limit -1} {:offset -1}
                {:workflow-id nil} {:scope [:unknown (random-uuid)]} {:unknown true}]]
    (is (anomaly/anomaly? (query/events [(chain/payload :chain/started)] opts)))))

(comment
  (query/events [] {}))
