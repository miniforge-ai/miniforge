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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} completed-status
  "The :status a :leave handler records for a phase that finished its own
   work: :completed, unless the phase's gates failed."
  [phase]
  (if (gate-failed? phase) :failed :completed))

(defn ^{:stratum 1} count-completed
  "Add phase-kw to [:execution :phases-completed] unless the phase in ctx
   failed its gates."
  [ctx phase-kw]
  (cond-> ctx
    (not (gate-failed? (:phase ctx)))
    (update-in [:execution :phases-completed] (fnil conj []) phase-kw)))

(comment
  (gate-failed? {:phase/status :failed})
  (completed-status {:phase/status :failed}))
