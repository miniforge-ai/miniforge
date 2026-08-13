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
(ns ai.miniforge.cli.workflow-recommender
  "LLM-based workflow recommendation system.

   Provides semantic analysis of task specifications to recommend
   the most appropriate workflow, complementing rule-based selection.

   Returns follow the anomaly system pattern - successful recommendations
   are plain maps, failures are anomaly maps with :anomaly/category.

   Prompt construction and LLM invocation/parsing live in sibling
   `ai.miniforge.cli.workflow-recommender.*` namespaces (rule 210: the
   combined namespace measured 5 real layers, max 3). Splitting them out
   also shortened this namespace's own in-file call chain — those hops
   no longer count toward its local layer depth, so the remaining
   fallback/orchestration code here now measures 2 real layers on its
   own, within budget."
  (:require
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-recommender.llm :as llm]
   [ai.miniforge.cli.workflow-recommender.prompt :as prompt]
   [ai.miniforge.cli.workflow-selection-config :as selection-config]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

;; Fallback recommendations
(defn ^{:stratum 0} recommend-by-task-type
  "Fallback recommendation based on task type.

   Arguments:
     spec - Task specification map
     available-workflows - Sequence of workflows

   Returns: Workflow recommendation map"
  [spec available-workflows]
  (let [task-type (get spec :spec/workflow-type :simple)
        matching-workflows (filter #(some #{task-type} (:workflow/task-types %)) available-workflows)]
    (if (seq matching-workflows)
      {:workflow (:workflow/id (first matching-workflows))
       :confidence 0.5
       :reasoning (messages/t :recommender/fallback-task-type {:task-type task-type})
       :source :fallback}
      {:workflow (selection-config/resolve-selection-profile :default available-workflows)
       :confidence 0.3
       :reasoning (messages/t :recommender/fallback-default)
       :source :fallback})))

;; Recommendation logic
(defn ^{:stratum 0} recommend-workflow
  "Recommend a workflow using LLM semantic analysis.

   Arguments:
     spec - Task specification map
     available-workflows - Sequence of workflow definition maps
     llm-client - Optional LLM client

   Returns: WorkflowRecommendation map (validated against schema) or anomaly map
     Success: {:workflow keyword, :confidence float, :reasoning string, :source :llm, ...}
     Failure: {:anomaly/category keyword, :anomaly/message string, ...}"
  [spec available-workflows llm-client]
  (if-not llm-client
    (response/make-anomaly :anomalies/unavailable
                           (messages/t :recommender/llm-unavailable)
                           {:operation :recommend-workflow
                            :spec-title (:spec/title spec)})
    (try
      (let [prompt-text (prompt/build-recommendation-prompt spec available-workflows)
            response-text (llm/call-llm-for-recommendation llm-client prompt-text)]
        (if-not response-text
          (response/make-anomaly :anomalies/unavailable
                                 (messages/t :recommender/no-response)
                                 {:operation :recommend-workflow
                                  :spec-title (:spec/title spec)})
          (if-let [parsed (llm/parse-llm-response response-text)]
            (let [recommendation (assoc parsed :source :llm)]
              ;; Validate against schema
              (if (workflow/valid-recommendation? recommendation)
                recommendation
                (response/make-anomaly :anomalies/incorrect
                                       (messages/t :recommender/invalid-format)
                                       {:operation :recommend-workflow
                                        :validation-errors (workflow/explain-recommendation recommendation)
                                        :recommendation recommendation})))
            (response/make-anomaly :anomalies/fault
                                   (messages/t :recommender/parse-failed)
                                   {:operation :recommend-workflow
                                    :raw-response response-text}))))
      (catch Exception e
        (response/from-exception e)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} recommend-workflow-with-fallback
  "Recommend workflow with LLM, falling back to rule-based if needed.

   Arguments:
     spec - Task specification map
     llm-client - Optional LLM client

   Returns: Workflow recommendation map"
  [spec llm-client]
  (workflow/ensure-initialized!)
  (let [available-workflows (workflow/list-workflows)
        llm-recommendation (when llm-client
                             (recommend-workflow spec available-workflows llm-client))]
    (if (and llm-recommendation (:workflow llm-recommendation))
      llm-recommendation
      (recommend-by-task-type spec available-workflows))))
