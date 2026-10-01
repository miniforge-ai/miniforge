;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.control-projection-test
  (:require [clojure.test :refer [deftest is]]
            [ai.miniforge.evidence-bundle.collectors :as collectors]
            [ai.miniforge.evidence-bundle.control-fixtures :as fixtures]
            [ai.miniforge.evidence-bundle.control-projection :as projection]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} project [requested executed]
  (first (projection/from-events [requested] executed)))

(deftest ^{:stratum 0} observed-control-metadata-is-preserved
  (let [stream (events/create-event-stream {:sinks []})
        workflow-id (random-uuid)
        record (fixtures/action)
        pending (dissoc record :action/result :action/post-state)
        requested (merge (fixtures/requested stream workflow-id (:action/id record)) pending)
        result {:status :failure :error {:code :example}}
        finished (events/control-action-executed stream workflow-id (:action/id record) result)
        executed (assoc finished :action/post-state (:action/post-state record))]
    (events/publish! stream requested)
    (events/publish! stream executed)
    (is (= [(assoc record :action/result result)]
           (collectors/collect-control-actions stream workflow-id)))
    (is (empty? (collectors/collect-control-actions stream (random-uuid))))))

(defn- ^{:stratum 0} collect-executed [execution-fn]
  (let [stream (events/create-event-stream {:sinks []})
        workflow-id (random-uuid)
        target {:target-type :workflow :target-id workflow-id}
        action (events/create-control-action :pause target (fixtures/requester))]
    (events/execute-control-action! stream action execution-fn)
    (first (collectors/collect-control-actions stream workflow-id))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} pending-is-not-invented-for-malformed-execution
  (let [record (fixtures/action)
        pending (project record [])]
    (is (= {:status :pending} (:action/result pending)))
    (doseq [result [nil false :executed {:status nil}]]
      (let [executed (assoc record :action/result result)
            projected (project record [executed])]
        (is (= result (:action/result projected)))
        (is (false? (fixtures/valid-action? projected)))))))

(deftest ^{:stratum 1} legacy-execution-failure-retains-error-details
  (let [record (fixtures/action)
        failure (response/failure fixtures/justification {:data {:code :example}})
        result (:action/result (project record [(assoc record :action/result failure)]))]
    (is (= :failure (:status result)))
    (is (= failure (dissoc result :status)))))

(deftest ^{:stratum 1} projection-never-fabricates-timestamps-or-state
  (let [stream (events/create-event-stream {:sinks []})
        requested (fixtures/requested stream (random-uuid) (random-uuid))
        result (project requested [])]
    (is (= (:event/timestamp requested) (:action/timestamp result)))
    (is (not (contains? result :action/pre-state)))
    (is (not (contains? result :action/post-state)))
    (is (nil? (:action/timestamp (project (dissoc requested :event/timestamp) []))))))

(deftest ^{:stratum 1} actual-control-execution-reaches-evidence
  (let [record (collect-executed (constantly {:paused true}))]
    (is (fixtures/valid-action? record))
    (is (= :success (get-in record [:action/result :status])))))
