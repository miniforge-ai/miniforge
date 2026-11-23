;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.dag-terminal-checkpoint-test
  (:require [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution-dag :as dag]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} verify-terminal-dag [root]
  (let [workflow {:workflow/id :terminal-dag :workflow/version "1.0.0"
                  :workflow/pipeline [{:phase :plan} {:phase :implement}]}
        opts {:checkpoint/root root}
        planned (assoc (context/create-context workflow {} opts) :execution/attempt-phase :plan)
        completed (dag/apply-dag-success planned {:artifacts [] :worktree-paths [] :metrics {}}
                                         nil context/transition-to-completed context/transition-to-failed)]
    (is (= :completed (:execution/status completed)))
    (is (= :implement (get-in (outcome/extract-output completed) [:execution/output :last-phase-result :name])))
    (checkpoints/persist-execution-state! completed)
    (let [{:keys [machine-snapshot phase-results]} (checkpoints/load-checkpoint-data (:execution/id completed) opts)
          restored (context/restore-context workflow {} machine-snapshot phase-results opts)]
      (is (= :implement (:execution/attempt-phase restored)))
      (is (= :implement (get-in phase-results [:implement :name])))
      (is (= :implement (get-in (outcome/extract-output restored) [:execution/output :last-phase-result :name]))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} terminal-dag-preserves-synthetic-implementation-output-and-checkpoint
  (fixture/call-with-temp-checkpoint-root verify-terminal-dag))
