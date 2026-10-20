;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.restored-failure-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution :as execution]
            [ai.miniforge.workflow.workflow-failure-test-support :as support]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition []
  {:workflow/id :restored-failure :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :done}]})

(defn- ^{:stratum 0} enter-failure [ctx]
  (let [cause (anomaly/anomaly :timeout "Socket stalled" {})
        result (phase/error nil "Failed" "Socket stalled" {})]
    (assoc ctx :phase {:name :done :status :failed :error cause :result result})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed-context [root]
  (let [ctx (context/create-context (definition) {} {:checkpoint/root root})
        interceptor {:name :done :config {:phase :done} :enter enter-failure}]
    (execution/execute-phase-step [interceptor] ctx {} context/merge-metrics
                                  context/transition-to-completed context/transition-to-failed)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-restored [root stream options]
  (let [failed (failed-context root)
        opts {:checkpoint/root root}]
    (checkpoints/persist-execution-state! failed)
    (let [{:keys [machine-snapshot phase-results]} (checkpoints/load-checkpoint-data (:execution/id failed) opts)
          restored (context/restore-context (definition) {} machine-snapshot phase-results opts)
          rewound (context/restore-context (definition) {} machine-snapshot phase-results
                                            (assoc opts :resume-reset-terminal? true))
          expected {:workflow/failure-phase :done :failure/class :failure.class/timeout
                    :workflow/failure-reason "Socket stalled"}]
      (is (= :failed (:execution/status failed) (:execution/status restored)))
      (is (nil? (:execution/current-phase restored)))
      (is (not (contains? restored :phase)))
      (is (empty? (:execution/errors restored)))
      (is (= :done (:execution/attempt-phase restored)))
      (is (not (contains? rewound :execution/attempt-phase)))
      (support/verify-context restored expected stream options))))
