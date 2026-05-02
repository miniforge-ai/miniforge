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

(ns ai.miniforge.observer.protocol
  "Observer protocol for metrics collection and analysis.

   The Observer is a pure metrics collector (no LLM) that:
   - Observes workflow execution events
   - Collects and aggregates metrics
   - Analyzes patterns and trends
   - Generates performance reports
   - Produces telemetry artifacts for Meta Loop")

;; ============================================================================
;; Observer Protocol
;; ============================================================================

(defprotocol Observer
  "Protocol for observing and analyzing workflow execution metrics."

  ;; Metrics Collection
  (collect-workflow-metrics [this workflow-id workflow-state]
    "Collect metrics from a completed workflow.

     Arguments:
     - workflow-id: Unique workflow identifier
     - workflow-state: Final workflow state with :workflow/metrics and :workflow/history

     Returns: nil (metrics stored internally)")

  (collect-phase-metrics [this workflow-id phase-name phase-result]
    "Collect metrics from a completed phase.

     Arguments:
     - workflow-id: Unique workflow identifier
     - phase-name: Name of the completed phase
     - phase-result: Phase result with :metrics, :success?, :errors

     Returns: nil (metrics stored internally)")

  ;; Query and Analysis
  (get-workflow-metrics [this workflow-id]
    "Get collected metrics for a specific workflow.

     Returns:
     {:workflow-id uuid
      :metrics {:tokens int :cost-usd float :duration-ms long}
      :phases [{:phase string :metrics {...} :success? bool}...]
      :timestamp inst}")

  (get-all-metrics [this opts]
    "Get all collected metrics with optional filtering.

     Options:
     - :limit - Maximum number of results (default: 100)
     - :since - Only metrics after this timestamp
     - :status - Filter by workflow status (:completed, :failed)

     Returns: Vector of workflow metrics maps")

  (analyze-metrics [this analysis-type opts]
    "Analyze collected metrics and generate statistics.

     Analysis types:
     - :duration-stats - Duration statistics (avg, p50, p95, p99)
     - :cost-stats - Cost statistics
     - :token-stats - Token usage statistics
     - :phase-stats - Per-phase performance
     - :failure-patterns - Failure analysis
     - :trends - Metrics over time

     Returns: Map with analysis results")

  ;; Report Generation
  (generate-report [this report-type opts]
    "Generate a performance report.

     Report types:
     - :summary - Overall performance summary
     - :detailed - Detailed metrics breakdown
     - :recommendations - Recommendations for improvements

     Options:
     - :format - Output format (:edn, :markdown, :json)
     - :limit - Number of workflows to analyze

     Returns: Report string or data structure")

  ;; Telemetry Artifacts
  (create-telemetry-artifact [this artifact-store]
    "Create a telemetry artifact from collected metrics.

     Arguments:
     - artifact-store: Artifact store to save telemetry to

     Returns:
     {:artifact/id uuid
      :artifact/type :telemetry
      :artifact/content string
      :artifact/metadata {...}}"))

;; ============================================================================
;; Metrics Data Structures
;; ============================================================================

(defn workflow-metrics
  "Create a workflow metrics record.

   Required fields:
   - :workflow-id - Unique identifier
   - :metrics - Map with :tokens, :cost-usd, :duration-ms
   - :status - Workflow status (:completed, :failed, :error)
   - :timestamp - When metrics were collected

   Optional fields:
   - :phases - Vector of phase metrics
   - :history - Workflow transition history
   - :errors - Error information if failed
   - :dependency-health - Canonical dependency-health projection
   - :failure-attribution - Canonical failure attribution for failed workflows"
  [{:keys [workflow-id metrics status timestamp phases history errors
           dependency-health failure-attribution]}]
  {:workflow-id workflow-id
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})
   :status (or status :unknown)
   :timestamp (or timestamp (java.util.Date.))
   :phases (or phases [])
   :history (or history [])
   :errors errors
   :dependency-health dependency-health
   :failure-attribution failure-attribution})

(defn phase-metrics
  "Create a phase metrics record.

   Required fields:
   - :phase - Phase name
   - :metrics - Map with :tokens, :cost-usd, :duration-ms
   - :success? - Whether phase succeeded

   Optional fields:
   - :errors - Error information if failed
   - :iterations - Number of retry iterations
   - :timestamp - When phase completed"
  [{:keys [phase metrics success? errors iterations timestamp]}]
  {:phase phase
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})
   :success? (boolean success?)
   :errors errors
   :iterations (or iterations 1)
   :timestamp (or timestamp (java.util.Date.))})

(defn analysis-result
  "Create an analysis result record.

   Required fields:
   - :analysis-type - Type of analysis performed
   - :data - Analysis data/statistics

   Optional fields:
   - :summary - Human-readable summary
   - :recommendations - Vector of recommendation strings
   - :timestamp - When analysis was performed"
  [{:keys [analysis-type data summary recommendations timestamp]}]
  {:analysis-type analysis-type
   :data data
   :summary summary
   :recommendations (or recommendations [])
   :timestamp (or timestamp (java.util.Date.))})
