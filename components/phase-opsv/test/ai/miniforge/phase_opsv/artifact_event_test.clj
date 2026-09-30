;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-event-test
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.interface.opsv :as contracts]
            [ai.miniforge.phase-opsv.artifact-test-support :as fixtures]
            [ai.miniforge.phase-opsv.test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} assert-event-links [ctx directory]
  (let [unconfirmed (random-uuid)
        configured (assoc-in ctx [:execution/input :opsv/policy-diff-artifact-refs] [unconfirmed])
        completed (reduce fixtures/step configured support/handlers)
        output (support/phase-output completed :opsv/actuate)
        ids (:opsv/phase-artifact-ids output)
        grouped (group-by :event/type (events/get-events (:event-stream completed)))
        expected {:opsv/load-step [(:metric-snapshot ids)]
                  :opsv.convergence/iteration [(:metric-snapshot ids)]
                  :opsv.verification/result [(:metric-snapshot ids) (:verification-measurements ids)]}
        policy-event (first (get grouped :opsv.policy/proposed))
        policy-id (:opsv/policy-artifact-ref policy-event)
        policy (artifact/read-published directory policy-id)]
    (doseq [[event-type refs] expected]
      (is (seq (get grouped event-type)))
      (doseq [event (get grouped event-type)]
        (is (nil? (contracts/explain-invalid-event event)))
        (is (= refs (:opsv/metric-snapshot-artifact-refs event)))
        (is (every? (set (:opsv/artifact-refs output)) refs))))
    (is (nil? (contracts/explain-invalid-event policy-event)))
    (is (uuid? policy-id))
    (is (= policy-id (:artifact/id policy)))
    (is (= :policy (get-in policy [:artifact/metadata :opsv/material-kind])))
    (is (empty? (:opsv/diff-artifact-refs policy-event)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} events-link-confirmed-measurement-and-policy-artifacts-test
  (fixtures/with-context assert-event-links))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-opsv.artifact-event-test))
