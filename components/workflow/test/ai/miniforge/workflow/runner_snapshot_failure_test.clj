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
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.fsm :as fsm]
            [ai.miniforge.workflow.runner-checkpoint :as checkpoint]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pending-context [root stream logger]
  (let [workflow {:workflow/id :snapshot-failure
                  :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase :plan}]}
        options {:checkpoint/root root :event-stream stream :logger logger}
        ctx (context/create-context workflow {} options)
        initial (fsm/initialize-execution (:execution/fsm-machine ctx))]
    (context/sync-machine-projections (assoc ctx :execution/fsm-state initial))))

(defn- ^{:stratum 0} with-journal [operation root]
  (let [directory (str (fs/real-path (fs/create-dirs (fs/path root "journal"))))
        stream (events/create-current-event-stream {:journal-directory directory})]
    (is (events/current-event-stream? stream) (pr-str stream))
    (when (events/current-event-stream? stream)
      (try+ (operation root stream)
        (finally (events/close-current-event-stream! stream))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-pre-phase-failure [root stream]
  (let [[logger entries] (log/collecting-logger {})
        ctx (pending-context (fixture/unwritable-checkpoint-root root) stream logger)
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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-real-pre-phase-write-failure-is-observable-without-failing-the-run
  (fixture/call-with-temp-checkpoint-root (partial with-journal verify-pre-phase-failure)))

(comment ::snapshot-failure-facts)
