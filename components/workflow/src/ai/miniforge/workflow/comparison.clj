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

(ns ai.miniforge.workflow.comparison
  "Workflow execution comparison utilities."
  (:require
   [ai.miniforge.workflow.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Summary creation

(defn execution-summary
  "Create a summary map from an execution state."
  [{:execution/keys [id workflow-id status metrics artifacts errors] :as exec-state}]
  {:execution/id id
   :execution/workflow-id workflow-id
   :execution/status status
   :execution/metrics metrics
   :execution/duration-ms (state/get-duration-ms exec-state)
   :artifact-count (count artifacts)
   :error-count (count errors)})

;------------------------------------------------------------------------------ Layer 1
;; Aggregation helpers

(defn count-by-status
  "Count execution states with a given status."
  [status execution-states]
  (count (filter #(= status (:execution/status %)) execution-states)))

(defn avg-metric
  "Calculate average of a metric across summaries."
  [summaries metric-path]
  (if (seq summaries)
    (/ (reduce + (map #(get-in % metric-path) summaries))
       (count summaries))
    0))

;------------------------------------------------------------------------------ Layer 2
;; Comparison

(defn compare-workflows
  "Compare execution results from multiple workflow runs.

   Arguments:
   - execution-states: Vector of execution states to compare

   Returns comparison map with:
   - :executions - Vector of execution summaries
   - :comparison - Side-by-side metrics comparison"
  [execution-states]
  (let [summaries (mapv execution-summary execution-states)]
    {:executions summaries
     :comparison {:total-executions (count execution-states)
                  :completed-count (count-by-status :completed execution-states)
                  :failed-count (count-by-status :failed execution-states)
                  :avg-tokens (avg-metric summaries [:execution/metrics :tokens])
                  :avg-cost (avg-metric summaries [:execution/metrics :cost-usd])
                  :avg-duration (avg-metric summaries [:execution/duration-ms])}}))

;------------------------------------------------------------------------------ Layer 3
;; Workflow persistence

(defn save-workflow
  "Save a workflow configuration to the heuristic store.

   Arguments:
   - workflow: Workflow configuration map
   - opts: Optional map with :store

   Returns UUID of saved workflow."
  ([workflow]
   (save-workflow workflow {}))
  ([workflow opts]
   (let [heuristic-type (keyword "workflow" (name (:workflow/id workflow)))
         version (:workflow/version workflow)
         save-heuristic (requiring-resolve 'ai.miniforge.heuristic.interface/save-heuristic)]
     (save-heuristic heuristic-type version {:data workflow} opts))))
