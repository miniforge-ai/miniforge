;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-snapshot-failure-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.schema.interface :as schema]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.runner-checkpoint :as checkpoint]
            [ai.miniforge.workflow.snapshot-failure-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-pre-phase-failure [root stream]
  (let [[logger entries] (log/collecting-logger {})
        ctx (support/pending-context (fixture/unwritable-checkpoint-root root) stream logger)
        result (checkpoint/checkpoint! ctx)
        history (events/get-events stream)
        fact (first history)]
    (is (nil? (:execution/current-phase ctx)))
    (is (anomaly/any-anomaly? result))
    (is (= [:workflow/machine-snapshot-write-failed] (mapv :event/type history)))
    (is (= :pending (:machine/state fact)))
    (is (= (:execution/id ctx) (:workflow/id fact)))
    (is (= "1.0.0" (:event/version fact)))
    (is (schema/valid? failure/FailureClass (:failure/class fact)))
    (is (string? (:error/message fact)))
    (is (some #(= :warn (:log/level %)) @entries))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-real-pre-phase-write-failure-is-observable-without-failing-the-run
  (fixture/call-with-temp-checkpoint-root (partial support/with-journal verify-pre-phase-failure)))

(comment ::snapshot-failure-facts)
