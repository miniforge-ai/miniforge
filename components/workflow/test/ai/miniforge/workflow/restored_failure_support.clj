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
   :workflow/pipeline [{:phase :implement} {:phase :review :on-fail :implement}]})

(defn- ^{:stratum 0} enter-failure [ctx]
  (let [cause (anomaly/anomaly :timeout "Socket stalled" {})
        result (phase/error nil "Failed" "Socket stalled" {})]
    (assoc ctx :phase {:name :implement :status :failed :error cause :result result})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} failed-context [root]
  (let [ctx (context/create-context (definition) {} {:checkpoint/root root})
        earlier (assoc ctx :execution/phase-results
                       {:implement {:name :implement :status :success}
                        :review {:name :review :status :failed}})
        review (context/transition-execution earlier :phase/succeed)
        redirected (context/transition-execution review
                                                 {:type :phase/fail :phase/verdict :repair-requested})
        interceptor {:name :implement :config {:phase :implement} :enter enter-failure}]
    (checkpoints/persist-execution-state! earlier)
    (checkpoints/persist-execution-state! review)
    (is (= :implement (:execution/current-phase redirected)))
    (is (= 1 (:execution/redirect-count redirected)))
    (execution/execute-phase-step [interceptor] redirected {} context/merge-metrics
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
          expected {:workflow/failure-phase :implement :failure/class :failure.class/timeout
                    :workflow/failure-reason "Socket stalled"}]
      (is (= :failed (:execution/status failed) (:execution/status restored)))
      (is (nil? (:execution/current-phase restored)))
      (is (not (contains? restored :phase)))
      (is (empty? (:execution/errors restored)))
      (is (= :implement (:execution/attempt-phase restored)))
      (is (not (contains? rewound :execution/attempt-phase)))
      (support/verify-context restored expected stream options))))
