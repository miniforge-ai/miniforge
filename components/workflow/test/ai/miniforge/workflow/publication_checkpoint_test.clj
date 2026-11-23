;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.publication-checkpoint-test
  (:require [ai.miniforge.fsm.interface :as fsm]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.state-publication :as publication]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} definition []
  {:workflow/id :publication-checkpoint
   :workflow/version "1.0.0"
   :workflow/pipeline [{:phase :plan}]})

(defn- ^{:stratum 0} completed-context [workflow opts]
  (-> (context/create-context workflow {} opts)
      (assoc :execution/attempt-phase :plan)
      (assoc-in [:execution/phase-results :plan] {:name :plan :status :completed})
      (context/transition-execution :complete)))

(defn- ^{:stratum 0} reopen-context! [workflow ctx opts]
  (checkpoints/persist-execution-state! ctx)
  (let [{:keys [machine-snapshot phase-results]}
        (checkpoints/load-checkpoint-data (:execution/id ctx) opts)]
    (context/restore-context workflow {} machine-snapshot phase-results opts)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} verify-refusal-reopen [root]
  (let [workflow (definition)
        opts {:checkpoint/root root}
        completed (completed-context workflow opts)
        restored-completion (reopen-context! workflow completed opts)
        refused (context/transition-execution restored-completion publication/refusal-event)
        restored-refusal (reopen-context! workflow refused opts)]
    (is (= :completed (:execution/status restored-completion)))
    (is (= :failed (:execution/status refused) (:execution/status restored-refusal)))
    (is (= :failed (fsm/current-state (:execution/fsm-state restored-refusal))))
    (is (= (:execution/id completed) (:execution/id restored-refusal)))
    (is (= :completed (get-in restored-refusal [:execution/phase-results :plan :status])))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} refusal-overwrites-prior-success-with-an-authoritative-failed-checkpoint
  (fixture/call-with-temp-checkpoint-root verify-refusal-reopen))

(comment ::publication-refusal-checkpoint)
