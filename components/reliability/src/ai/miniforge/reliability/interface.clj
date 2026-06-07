;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

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
   [ai.miniforge.reliability.dependency-health :as dependency-health]
   [ai.miniforge.reliability.engine :as engine]
   [ai.miniforge.reliability.degradation :as degradation]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def WorkflowTier
  "Malli `[:enum ...]` schema of workflow tiers (:best-effort :standard
   :critical). Tier determines SLO targets and degradation behavior."
  schema/WorkflowTier)

(def SliName
  "Malli `[:enum ...]` schema of the seven SLI identifiers (:SLI-1 through
   :SLI-7)."
  schema/SliName)

(def Window
  "Malli `[:enum ...]` schema of rolling measurement windows (:1h :7d :30d)."
  schema/Window)

(def DegradationMode
  "Malli `[:enum ...]` schema of degradation modes (:nominal :degraded
   :safe-mode)."
  schema/DegradationMode)

(def SliResult
  "Malli `[:map ...]` schema for a single computed SLI result. Required keys:
   :sli/name (SliName), :sli/value (double), :sli/window (Window). Optional:
   :sli/tier (WorkflowTier), :sli/dimensions (map)."
  schema/SliResult)

(def SloCheck
  "Malli `[:map ...]` schema for an SLO check outcome. Keys: :breached?
   (boolean), :sli/name (SliName), :slo/target (double), :slo/actual (double),
   :slo/tier (WorkflowTier), :slo/window (Window)."
  schema/SloCheck)

(def ErrorBudget
  "Malli `[:map ...]` schema for a computed error budget. Keys:
   :error-budget/tier (WorkflowTier), :error-budget/sli (SliName),
   :error-budget/window (Window), :error-budget/remaining (double),
   :error-budget/burn-rate (double), :error-budget/computed-at (inst)."
  schema/ErrorBudget)

(def DependencyKind
  "Malli `[:enum ...]` schema of dependency kinds (:provider :platform
   :environment)."
  schema/DependencyKind)

(def DependencyHealthStatus
  "Malli `[:enum ...]` schema of dependency health statuses (:healthy
   :degraded :unavailable :misconfigured :operator-action-required)."
  schema/DependencyHealthStatus)

(def DependencyHealth
  "Malli `[:map ...]` schema for a projected dependency-health entity.
   Required keys include :dependency/id, :dependency/source, :dependency/kind
   (DependencyKind), :dependency/status (DependencyHealthStatus),
   :dependency/failure-count (int), :dependency/window-size (int),
   :dependency/incident-counts (map of DependencyHealthStatus to int). Several
   optional keys carry vendor/class/retryability and observation timestamps."
  schema/DependencyHealth)

;------------------------------------------------------------------------------ Layer 1
;; SLI computation (pure)

(def compute-workflow-success-rate
  "SLI-1. (workflow-metrics window) -> double in [0.0, 1.0]: fraction of
   terminal workflows that completed or escalated. Returns 0.0 when no
   terminal workflows fall in the window. Pure."
  sli/compute-workflow-success-rate)

(def compute-phase-latency
  "SLI-2. (phase-metrics window) -> map {:p50 :p95 :p99} of phase duration-ms
   percentiles (doubles). Percentiles are 0.0 when no durations fall in the
   window. Pure."
  sli/compute-phase-latency)

(def compute-inner-loop-convergence
  "SLI-3. (phase-metrics window) -> double in [0.0, 1.0]: fraction of inner
   loops (entries carrying :iterations) that converged (:success?). Returns
   0.0 when no loops fall in the window. Pure."
  sli/compute-inner-loop-convergence)

(def compute-gate-pass-rate
  "SLI-4. (gate-metrics window) -> double in [0.0, 1.0]: fraction of gate
   evaluations that :passed?. Returns 0.0 when no gate events fall in the
   window. Pure."
  sli/compute-gate-pass-rate)

(def compute-tool-success-rate
  "SLI-5. (tool-metrics window) -> double in [0.0, 1.0]: fraction of tool
   invocations that report :success?. Returns 0.0 when no invocations fall in
   the window. Pure."
  sli/compute-tool-success-rate)

(def compute-failure-distribution
  "SLI-6 (full). (failure-events window) -> map keyed by each distinct
   :failure/class VALUE found in the windowed events (e.g.
   :failure.class/unknown), with that class's fraction (double) of all
   in-window failures as the value. Returns {} when no failures fall in the
   window. The unknown-class fraction is the key meta-reliability indicator.
   Pure."
  sli/compute-failure-distribution)

(def compute-unknown-failure-rate
  "SLI-6 (scalar). (failure-events window) -> double in [0.0, 1.0]: the
   :failure.class/unknown fraction from the distribution, or 0.0 if absent. A
   high rate signals insufficient failure instrumentation. Pure."
  sli/compute-unknown-failure-rate)

(def compute-context-staleness-rate
  "SLI-7. (context-metrics window) -> double in [0.0, 1.0]: fraction of
   context packs flagged :stale?. Returns 0.0 when no records fall in the
   window. Pure."
  sli/compute-context-staleness-rate)

(def compute-all-slis
  "(metrics window) -> vector of seven SliResult maps (one per SLI), each
   {:sli/name :sli/value :sli/window}. metrics is a map of per-source metric
   vectors (:workflow-metrics :phase-metrics :gate-metrics :tool-metrics
   :failure-events :context-metrics); missing sources default to empty. Pure."
  sli/compute-all-slis)

;------------------------------------------------------------------------------ Layer 2
;; SLO checking (pure)

(def default-slo-targets
  "Nested map of SLO targets {SliName -> {WorkflowTier -> target-double}},
   loaded from config/reliability/slo-targets.edn. A nil entry means
   advisory-only (no enforcement for that SLI/tier)."
  slo/default-targets)

(def check-slo
  "(sli-result tier [targets]) -> SloCheck map, or nil if the SLI is
   advisory-only for the tier. :breached? is true when the actual value
   crosses the target (below a floor, or above the ceiling for inverted SLIs
   such as SLI-6). Pure."
  slo/check-slo)

(def check-all-slos
  "(sli-results tier [targets]) -> vector of SloCheck maps. Excludes
   advisory-only SLIs (those with no target for the tier). Pure."
  slo/check-all-slos)

(def breached-slos
  "(slo-checks) -> lazy seq of the SloCheck maps whose :breached? is true.
   Pure."
  slo/breached-slos)

;------------------------------------------------------------------------------ Layer 3
;; Error budgets (pure)

(def error-budget
  "Factory: create an error budget map.
   (error-budget remaining burn-rate) or
   (error-budget remaining burn-rate tier sli window)"
  budget/error-budget)

(def compute-error-budget
  "(sli-value slo-target inverted?) -> ErrorBudget map with
   :error-budget/remaining (double 0.0-1.0 fraction left) and
   :error-budget/burn-rate (double; 1.0 = nominal, >1.0 = over-consuming).
   remaining 0.0 means exhausted. inverted? true for lower-is-better SLIs.
   Pure."
  budget/compute-error-budget)

(def budget-exhausted?
  "(budget) -> boolean: true when :error-budget/remaining is at or below 0.0."
  budget/budget-exhausted?)

(def budget-critical?
  "(budget [threshold]) -> boolean: true when :error-budget/remaining is below
   threshold (default 0.25, i.e. under 25% remaining)."
  budget/budget-critical?)

;------------------------------------------------------------------------------ Layer 3b
;; Dependency health projection (pure)

(def dependency-incident?
  "Returns true when the classified failure contributes to dependency-health
   projection."
  dependency-health/dependency-incident?)

(def apply-dependency-signals
  "Apply classified dependency incidents and recovery signals to rolling
   dependency-health state."
  dependency-health/apply-signals)

(def project-dependency-health
  "Project rolling dependency-health state into canonical dependency entities."
  dependency-health/project-health)

;------------------------------------------------------------------------------ Layer 4
;; Engine (stateful)

(def create-engine
  "Create a ReliabilityEngine.

   Arguments:
     event-stream - event stream atom
     config       - optional {:windows [:7d]
                              :tiers [:standard :critical]
                              :dependency-health {...}
                              :degradation-policy {...}}"
  engine/create-engine)

(def compute-cycle!
  "Execute one reliability computation cycle.
   Computes SLIs, checks SLOs, updates budgets, emits events.

   Returns:
   {:slis
    :slo-checks
    :budgets
    :dependency-health
    :breaches
    :recommendation}"
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
     dependency-health - optional dependency health projection

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
