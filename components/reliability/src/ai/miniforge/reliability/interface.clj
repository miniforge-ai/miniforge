;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.interface
  "Public API for the reliability component per N1 §5.5.

   Computes SLIs, checks SLOs, manages error budgets, and recommends
   degradation mode transitions.

   Layer 0: Schemas and taxonomy
   Layer 1: Pure SLI computation
   Layer 2: SLO checking
   Layer 3: Error budget computation
   Layer 4: Engine (stateful)"
  (:require
   [ai.miniforge.reliability.schema :as schema]
   [ai.miniforge.reliability.sli :as sli]
   [ai.miniforge.reliability.slo :as slo]
   [ai.miniforge.reliability.budget :as budget]
   [ai.miniforge.reliability.engine :as engine]
   [ai.miniforge.reliability.degradation :as degradation]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def WorkflowTier schema/WorkflowTier)
(def SliName schema/SliName)
(def Window schema/Window)
(def DegradationMode schema/DegradationMode)
(def SliResult schema/SliResult)
(def SloCheck schema/SloCheck)
(def ErrorBudget schema/ErrorBudget)

;------------------------------------------------------------------------------ Layer 1
;; SLI computation (pure)

(def compute-workflow-success-rate sli/compute-workflow-success-rate)
(def compute-phase-latency sli/compute-phase-latency)
(def compute-inner-loop-convergence sli/compute-inner-loop-convergence)
(def compute-gate-pass-rate sli/compute-gate-pass-rate)
(def compute-tool-success-rate sli/compute-tool-success-rate)
(def compute-failure-distribution sli/compute-failure-distribution)
(def compute-unknown-failure-rate sli/compute-unknown-failure-rate)
(def compute-context-staleness-rate sli/compute-context-staleness-rate)
(def compute-all-slis sli/compute-all-slis)

;------------------------------------------------------------------------------ Layer 2
;; SLO checking (pure)

(def default-slo-targets slo/default-targets)
(def check-slo slo/check-slo)
(def check-all-slos slo/check-all-slos)
(def breached-slos slo/breached-slos)

;------------------------------------------------------------------------------ Layer 3
;; Error budgets (pure)

(def compute-error-budget budget/compute-error-budget)
(def budget-exhausted? budget/budget-exhausted?)
(def budget-critical? budget/budget-critical?)

;------------------------------------------------------------------------------ Layer 4
;; Engine (stateful)

(def create-engine
  "Create a ReliabilityEngine.

   Arguments:
     event-stream - event stream atom
     config       - optional {:windows [:7d] :tiers [:standard :critical]}"
  engine/create-engine)

(def compute-cycle!
  "Execute one reliability computation cycle.
   Computes SLIs, checks SLOs, updates budgets, emits events.

   Returns: {:slis :slo-checks :budgets :breaches :recommendation}"
  engine/compute-cycle!)

(def current-state
  "Get the current reliability state."
  engine/current-state)

(def current-mode
  "Get the current degradation mode recommendation."
  engine/current-mode)

;------------------------------------------------------------------------------ Layer 5
;; Degradation mode manager (stateful, N1 §5.5.5, N8 §3.4)

(def create-degradation-manager
  "Create a DegradationManager with FSM for :nominal → :degraded → :safe-mode.

   Arguments:
     event-stream - event stream atom
     config       - optional {:unknown-failure-threshold 3}"
  degradation/create-manager)

(def degradation-mode
  "Get the current degradation mode (:nominal, :degraded, or :safe-mode)."
  degradation/current-mode)

(def evaluate-degradation!
  "Evaluate budget state and trigger mode transition if warranted.

   Arguments:
     manager      - DegradationManager
     budget-state - map of budgets from compute-cycle!

   Returns: current degradation mode."
  degradation/evaluate-and-transition!)

(def enter-safe-mode!
  "Force entry to safe-mode from any state.

   Arguments:
     manager - DegradationManager
     trigger - :error-budget | :emergency-stop | :unknown-failures | :manual
     details - string"
  degradation/enter-safe-mode!)

(def exit-safe-mode!
  "Exit safe-mode (requires justification per N8 §3.4.3).

   Arguments:
     manager       - DegradationManager
     justification - string
     principal     - string (who is exiting)"
  degradation/exit-safe-mode!)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (compute-workflow-success-rate
   [{:status :completed :timestamp (java.util.Date.)}
    {:status :failed :timestamp (java.util.Date.)}]
   :7d)
  ;; => 0.5

  (check-slo {:sli/name :SLI-1 :sli/value 0.80 :sli/window :7d} :standard)
  ;; => {:breached? true ...}

  :leave-this-here)
