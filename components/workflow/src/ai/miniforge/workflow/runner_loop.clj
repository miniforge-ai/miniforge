;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under Apache 2.0.
(ns ai.miniforge.workflow.runner-loop
  "Bound pipeline iteration independently of the phase-step implementation."
  (:require [ai.miniforge.workflow.runner-defaults :as defaults]
            [ai.miniforge.workflow.runner-iteration :as iteration]
            [ai.miniforge.workflow.runner-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} execute-pipeline-loop
  "Execute the phase pipeline loop. Returns final context."
  [pipeline initial-ctx callbacks control-state max-phases]
  (cond
    (outcome/terminal-state? initial-ctx)
    initial-ctx

    (empty? pipeline)
    (outcome/handle-empty-pipeline initial-ctx)

    :else
    (let [max-phase-retries (defaults/max-consecutive-phase-retries)]
      ;; `consecutive` counts how many times the phase about to run has been
      ;; the active phase in immediately consecutive iterations. A healthy
      ;; workflow advances to a new phase each iteration, so a count > the cap
      ;; means one phase is stuck retrying in place — abort before it grinds
      ;; up to `max-phases` (the runaway that burned ~17h overnight).
      (loop [context     initial-ctx
             iteration   0
             prev-phase  nil
             consecutive 1]
        (let [phase       (:execution/current-phase context)
              consecutive (if (= phase prev-phase) (inc consecutive) 1)]
          (cond
            (outcome/terminal-state? context)      context
            (>= iteration max-phases)      (outcome/handle-max-phases-exceeded context max-phases)
            (and phase (> consecutive max-phase-retries))
            (outcome/handle-phase-retry-exhausted context phase max-phase-retries)
            :else (recur (iteration/execute-single-iteration pipeline context callbacks iteration control-state)
                         (inc iteration)
                         phase
                         consecutive)))))))

(comment ::runner-loop)
