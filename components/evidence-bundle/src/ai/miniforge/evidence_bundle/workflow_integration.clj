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
   Provides WorkflowObserver implementation for evidence bundle generation."
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Observer Record

(defrecord EvidenceCollector [evidence-manager artifact-store logger]
  ;; Implement WorkflowObserver protocol if it exists in workflow component
  ;; This enables automatic evidence collection on workflow completion

  Object
  (toString [_] "EvidenceCollector"))

;------------------------------------------------------------------------------ Layer 1
;; Observer Factory

(defn create-evidence-collector
  "Create an evidence collector for workflow integration.

   The evidence collector acts as a workflow observer that automatically
   creates evidence bundles when workflows complete (success or failure).

   Options:
   - :evidence-manager - Evidence bundle manager (required)
   - :artifact-store - Artifact store for provenance (required)
   - :logger - Optional logger instance

   Example:
     (def collector (create-evidence-collector
                     {:evidence-manager mgr
                      :artifact-store store}))
     (workflow/add-observer workflow collector)"
  [opts]
  (let [{:keys [evidence-manager artifact-store logger]} opts]
    (when-not evidence-manager
      (throw (ex-info "evidence-manager required" {:opts opts})))
    (when-not artifact-store
      (throw (ex-info "artifact-store required" {:opts opts})))
    (->EvidenceCollector
     evidence-manager
     artifact-store
     (or logger (log/create-logger {:min-level :info})))))

;------------------------------------------------------------------------------ Layer 2
;; Workflow Completion Handler

(defn on-workflow-complete
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

;------------------------------------------------------------------------------ Layer 3
;; Integration Utilities

(defn attach-to-workflow
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
  [workflow collector]
  (when-let [add-observer (try
                            (requiring-resolve 'ai.miniforge.workflow.interface/add-observer)
                            (catch Exception _ nil))]
    (add-observer workflow collector))
  workflow)

(defn create-and-attach-evidence-collector
  "Create evidence collector and attach to workflow in one step.

   This is the simplest way to enable automatic evidence collection.

   Arguments:
   - workflow: Workflow instance
   - artifact-store: Artifact store instance

   Returns the workflow instance (for chaining).

   Example:
     (-> (workflow/create-workflow)
         (create-and-attach-evidence-collector artifact-store)
         (workflow/start spec context))"
  [workflow artifact-store]
  (let [evidence-manager (evidence/create-evidence-manager
                          {:artifact-store artifact-store})
        collector (create-evidence-collector
                   {:evidence-manager evidence-manager
                    :artifact-store artifact-store})]
    (attach-to-workflow workflow collector)))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (require '[ai.miniforge.workflow.interface :as workflow]
           '[ai.miniforge.artifact.interface :as artifact])

  ;; Create artifact store
  (def artifact-store (artifact/create-transit-store))

  ;; Create evidence manager
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))

  ;; Create evidence collector
  (def collector (create-evidence-collector
                  {:evidence-manager evidence-mgr
                   :artifact-store artifact-store}))

  ;; Attach to workflow
  (def wf (-> (workflow/create-workflow)
              (attach-to-workflow collector)))

  ;; Or use the convenience function
  (def wf2 (-> (workflow/create-workflow)
               (create-and-attach-evidence-collector artifact-store)))

  ;; Now evidence bundles will be created automatically on workflow completion

  :end)
