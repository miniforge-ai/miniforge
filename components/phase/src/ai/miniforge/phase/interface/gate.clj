;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.interface.gate
  "How a phase handler reads the workflow's gate outcome for the current attempt.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} gate-failed?
  "True when the workflow's gate validation failed this phase map: its gates
   denied the decision, or the decision was not acknowledged. Gate validation
   runs between :enter and :leave, so a :leave handler or verdict function
   checks this before it records or publishes success, or performs an effect
   the gates were meant to authorize."
  [phase]
  (or (= :failed (:phase/status phase))
      (contains? phase :phase/decision-unacknowledged)))

(comment
  (gate-failed? {:phase/status :failed}))
