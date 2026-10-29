;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.phase-completion-facts-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.phase-event-test-support :as support]
            [ai.miniforge.workflow.runner-events :as publishers]
            [ai.miniforge.workflow.runner-phase-event :as projection]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} completed-phase [artifacts]
  {:status :completed
   :duration-ms 7
   :result {:status :success :artifacts artifacts}})

(defn- ^{:stratum 0} context [] {:execution/id (random-uuid)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-receipts [stream options]
  (let [ctx (context)
        ids [(random-uuid)]]
    (doseq [artifacts [[] ids]]
      (let [result (completed-phase artifacts)
            receipt (publishers/publish-phase-completed! stream ctx :opsv/plan result)]
        (is (= artifacts (:phase/artifacts receipt)))
        (is (= (:duration-ms result) (:phase/duration-ms receipt)))
        (is (= :success (:phase/outcome receipt)))))
    (let [history (events/get-events stream)]
      (events/close-current-event-stream! stream)
      (is (= history (support/reopened-events options))))))

(defn- ^{:stratum 1} verify-refused-facts [stream _options]
  (doseq [artifacts [nil false ["not-an-artifact-id"]]]
    (is (anomaly/any-anomaly?
          (publishers/publish-phase-completed! stream (context) :opsv/plan
                                               (completed-phase artifacts)))))
  (is (empty? (events/get-events stream))))

(deftest ^{:stratum 1} cumulative-output-does-not-supply-missing-completion-facts
  (let [result (-> (completed-phase nil)
                   (assoc-in [:result :output :opsv/artifact-refs] [(random-uuid)]))]
    (is (not (contains? (projection/data result) :artifacts)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} declared-completion-facts-survive-current-journal-reopen
  (support/with-stream verify-receipts))

(deftest ^{:stratum 2} missing-or-invalid-artifacts-are-not-replaced-with-empty-vectors
  (support/with-stream verify-refused-facts))
