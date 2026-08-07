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
(ns ai.miniforge.evidence-bundle.workflow-integration
  "Integration hooks for automatic evidence collection in workflows.
   Provides the WorkflowObserver implementation for evidence bundle
   generation. Collector construction and the create+attach convenience
   composite live in `workflow-integration-factory` (rule 210: adding
   the WorkflowObserver implementation here is one real layer deeper
   than a bare Object record, and that extra hop is the signal to
   split it).

   Layer 0: Workflow completion handler + attach-to-workflow
   Layer 1: EvidenceCollector record (WorkflowObserver impl)"
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

;; Workflow Completion Handler
(defn ^{:stratum 0} on-workflow-complete
  "Handle workflow completion event.
   Automatically creates evidence bundle for the completed workflow.

   Arguments:
   - collector: EvidenceCollector instance
   - workflow-id: UUID of the workflow
   - workflow-state: Final workflow state

   Returns evidence bundle or nil if bundle creation fails."
  [collector workflow-id workflow-state]
  (try
    (let [{:keys [evidence-manager artifact-store logger]} collector]
      (log/info logger :evidence-bundle :auto-collect-evidence
                {:data {:workflow-id workflow-id
                        :status (:workflow/status workflow-state)}})

      ;; Assemble and create bundle
      (when (evidence/auto-collect-evidence
             workflow-id workflow-state artifact-store)
        (let [bundle (evidence/create-bundle
                      evidence-manager
                      workflow-id
                      {:workflow-state workflow-state})]
          (log/info logger :evidence-bundle :bundle-created
                    {:data {:workflow-id workflow-id
                            :bundle-id (:evidence-bundle/id bundle)}})
          bundle)))
    (catch Exception e
      (log/error (:logger collector) :evidence-bundle :bundle-creation-failed
                 {:data {:workflow-id workflow-id
                         :error (.getMessage e)
                         :trace (vec (.getStackTrace e))}})
      nil)))

;; Integration Utilities
(defn ^{:stratum 0} attach-to-workflow
  "Attach evidence collector to a workflow instance.

   This is a convenience function that adds the evidence collector
   as a workflow observer, enabling automatic evidence collection.

   Arguments:
   - workflow: Workflow instance
   - collector: EvidenceCollector instance

   Returns the workflow instance (for chaining).

  Example:
     (-> (workflow/create-workflow)
         (attach-to-workflow evidence-collector)
         (workflow/start spec context))"
  [workflow-instance collector]
  (workflow/add-observer workflow-instance collector)
  workflow-instance)

;------------------------------------------------------------------------------ Layer 1

;; Observer Record
;;
;; Implements WorkflowObserver so attaching this record via
;; workflow/add-observer doesn't throw IllegalArgumentException the
;; first time the workflow engine invokes any observer callback.
;; on-workflow-complete delegates to the free fn above (defined first
;; in this namespace so the delegating call resolves); the collector
;; only cares about workflow completion, so the other four callbacks
;; are no-ops.
(defrecord ^{:stratum 1} EvidenceCollector [evidence-manager artifact-store logger]
  workflow/WorkflowObserver
  (on-phase-start [_ _workflow-id _phase _context] nil)
  (on-phase-complete [_ _workflow-id _phase _result] nil)
  (on-phase-error [_ _workflow-id _phase _error] nil)
  (on-workflow-complete [this workflow-id final-state]
    (on-workflow-complete this workflow-id final-state))
  (on-rollback [_ _workflow-id _from-phase _to-phase _reason] nil)

  Object
  (toString [_] "EvidenceCollector"))
