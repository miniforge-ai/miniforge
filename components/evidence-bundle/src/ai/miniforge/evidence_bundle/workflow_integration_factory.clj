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
(ns ai.miniforge.evidence-bundle.workflow-integration-factory
  "Construction and attachment helpers for `workflow-integration`'s
   EvidenceCollector, split out of `workflow-integration` (rule 210:
   the WorkflowObserver implementation there added one real layer, and
   this factory/composite pair would have tipped the combined file
   over the 3-layer budget).

   Layer 0: Collector factory
   Layer 1: Create+attach convenience composite"
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.evidence-bundle.workflow-integration :as workflow-integration]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

;; Observer Factory
(defn ^{:stratum 0} create-evidence-collector
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
    (workflow-integration/->EvidenceCollector
     evidence-manager
     artifact-store
     (or logger (log/create-logger {:min-level :info})))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-and-attach-evidence-collector
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
    (workflow-integration/attach-to-workflow workflow collector)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.artifact.interface :as artifact]
           '[ai.miniforge.workflow.interface :as workflow])

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
              (workflow-integration/attach-to-workflow collector)))

  ;; Or use the convenience function
  (def wf2 (-> (workflow/create-workflow)
               (create-and-attach-evidence-collector artifact-store)))

  ;; Now evidence bundles will be created automatically on workflow completion

  :end)
