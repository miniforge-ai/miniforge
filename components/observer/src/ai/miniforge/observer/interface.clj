(ns ai.miniforge.observer.interface
  "Public API for the Observer component.

   The Observer is a pure metrics collector (no LLM) that observes
   workflow execution, collects metrics, and generates performance reports.

   Usage:
     (require '[ai.miniforge.observer.interface :as observer])

     ;; Create observer
     (def obs (observer/create-observer))

     ;; Observer implements WorkflowObserver - attach to workflow execution
     ;; Metrics are collected automatically via callbacks

     ;; Query metrics
     (observer/get-workflow-metrics obs workflow-id)
     (observer/get-all-metrics obs {:limit 100})

     ;; Analyze metrics
     (observer/analyze-metrics obs :duration-stats {})
     (observer/analyze-metrics obs :cost-stats {})
     (observer/analyze-metrics obs :failure-patterns {})

     ;; Generate reports
     (observer/generate-report obs :summary {:format :markdown})
     (observer/generate-report obs :recommendations {:format :markdown})

     ;; Create telemetry artifact
     (observer/create-telemetry-artifact obs artifact-store)"
  (:require
   [ai.miniforge.observer.protocol :as proto]
   [ai.miniforge.observer.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Observer creation

(def create-observer
  "Create a new Observer instance.

   The Observer collects workflow metrics and generates performance reports.
   It implements both Observer and WorkflowObserver protocols.

   Options:
   - :initial-state - Initial state map (optional)

   Returns: Observer instance

   Example:
     (def obs (create-observer))"
  core/create-observer)

;------------------------------------------------------------------------------ Layer 1
;; Metrics collection

(defn collect-workflow-metrics
  "Collect metrics from a completed workflow.

   Arguments:
   - observer: Observer instance
   - workflow-id: Unique workflow identifier
   - workflow-state: Final workflow state with :workflow/metrics and :workflow/history

   Returns: nil (metrics stored in observer)

   Example:
     (collect-workflow-metrics obs workflow-id final-state)"
  [observer workflow-id workflow-state]
  (proto/collect-workflow-metrics observer workflow-id workflow-state))

(defn collect-phase-metrics
  "Collect metrics from a completed phase.

   Arguments:
   - observer: Observer instance
   - workflow-id: Unique workflow identifier
   - phase-name: Name of the completed phase
   - phase-result: Phase result with :metrics, :success?, :errors

   Returns: nil (metrics stored in observer)

   Example:
     (collect-phase-metrics obs workflow-id :implement phase-result)"
  [observer workflow-id phase-name phase-result]
  (proto/collect-phase-metrics observer workflow-id phase-name phase-result))

;------------------------------------------------------------------------------ Layer 2
;; Query and retrieval

(defn get-workflow-metrics
  "Get collected metrics for a specific workflow.

   Arguments:
   - observer: Observer instance
   - workflow-id: Unique workflow identifier

   Returns: Workflow metrics map or nil if not found

   Example:
     (get-workflow-metrics obs workflow-id)
     ;; => {:workflow-id uuid
     ;;     :metrics {:tokens 1500 :cost-usd 0.15 :duration-ms 5000}
     ;;     :phases [...]
     ;;     :timestamp #inst \"...\"}"
  [observer workflow-id]
  (proto/get-workflow-metrics observer workflow-id))

(defn get-all-metrics
  "Get all collected metrics with optional filtering.

   Arguments:
   - observer: Observer instance
   - opts: Options map
     - :limit - Maximum number of results (default: 100)
     - :since - Only metrics after this timestamp
     - :status - Filter by workflow status (:completed, :failed)

   Returns: Vector of workflow metrics maps, sorted by timestamp (newest first)

   Example:
     (get-all-metrics obs {:limit 50 :status :completed})"
  [observer opts]
  (proto/get-all-metrics observer opts))

;------------------------------------------------------------------------------ Layer 3
;; Analysis

(defn analyze-metrics
  "Analyze collected metrics and generate statistics.

   Arguments:
   - observer: Observer instance
   - analysis-type: Type of analysis to perform
   - opts: Analysis options

   Analysis types:
   - :duration-stats - Duration statistics (avg, p50, p95, p99)
   - :cost-stats - Cost statistics
   - :token-stats - Token usage statistics
   - :phase-stats - Per-phase performance breakdown
   - :failure-patterns - Failure analysis and patterns
   - :trends - Metrics trends over time

   Options:
   - :limit - Number of workflows to analyze (default: 100)

   Returns: Analysis result map with :analysis-type, :data, :summary

   Example:
     (analyze-metrics obs :duration-stats {:limit 100})
     ;; => {:analysis-type :duration-stats
     ;;     :data {:count 100 :avg 5000 :p50 4500 :p95 8000 ...}
     ;;     :summary \"Analyzed 100 workflows: avg=5000ms, p50=4500ms...\"}"
  [observer analysis-type opts]
  (proto/analyze-metrics observer analysis-type opts))

;------------------------------------------------------------------------------ Layer 4
;; Report generation

(defn generate-report
  "Generate a performance report from collected metrics.

   Arguments:
   - observer: Observer instance
   - report-type: Type of report to generate
   - opts: Report options

   Report types:
   - :summary - Overall performance summary
   - :detailed - Detailed metrics breakdown
   - :recommendations - Recommendations for improvements

   Options:
   - :format - Output format (:edn, :markdown, :json) - default :markdown
   - :limit - Number of workflows to analyze (default: 100 for summary, 50 for detailed)

   Returns: Report string (markdown/json) or data structure (edn)

   Example:
     (generate-report obs :summary {:format :markdown})
     ;; => \"# Workflow Performance Summary\\n\\n...\"

     (generate-report obs :recommendations {:format :edn})
     ;; => {:recommendations [...] :failure-analysis {...} ...}"
  [observer report-type opts]
  (proto/generate-report observer report-type opts))

(defn create-telemetry-artifact
  "Create a telemetry artifact from collected metrics.

   Arguments:
   - observer: Observer instance
   - artifact-store: Artifact store to save telemetry to (optional for now)

   Returns: Telemetry artifact map
     {:artifact/id uuid
      :artifact/type :telemetry
      :artifact/content string (EDN)
      :artifact/metadata {:format :edn :workflows int}}

   Example:
     (create-telemetry-artifact obs artifact-store)
     ;; => {:artifact/id #uuid \"...\"
     ;;     :artifact/type :telemetry
     ;;     :artifact/content \"{:type :telemetry ...}\"
     ;;     :artifact/metadata {:format :edn :workflows 100}}"
  [observer artifact-store]
  (proto/create-telemetry-artifact observer artifact-store))

;------------------------------------------------------------------------------ Layer 5
;; Data structure constructors (re-export from protocol)

(def workflow-metrics
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

   Example:
     (workflow-metrics
       {:workflow-id (random-uuid)
        :metrics {:tokens 1500 :cost-usd 0.15 :duration-ms 5000}
        :status :completed
        :timestamp (java.util.Date.)})"
  proto/workflow-metrics)

(def phase-metrics
  "Create a phase metrics record.

   Required fields:
   - :phase - Phase name
   - :metrics - Map with :tokens, :cost-usd, :duration-ms
   - :success? - Whether phase succeeded

   Optional fields:
   - :errors - Error information if failed
   - :iterations - Number of retry iterations
   - :timestamp - When phase completed

   Example:
     (phase-metrics
       {:phase :implement
        :metrics {:tokens 800 :cost-usd 0.08 :duration-ms 3000}
        :success? true})"
  proto/phase-metrics)

(def analysis-result
  "Create an analysis result record.

   Required fields:
   - :analysis-type - Type of analysis performed
   - :data - Analysis data/statistics

   Optional fields:
   - :summary - Human-readable summary
   - :recommendations - Vector of recommendation strings
   - :timestamp - When analysis was performed

   Example:
     (analysis-result
       {:analysis-type :duration-stats
        :data {:count 100 :avg 5000 ...}
        :summary \"Analyzed 100 workflows...\"})"
  proto/analysis-result)
