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
(ns ai.miniforge.cli.workflow-recommender.prompt
  "Prompt construction for LLM-based workflow recommendation.

   Builds per-workflow summaries and assembles the full LLM analysis
   prompt from a task spec and the available workflows."
  (:require
   [clojure.string :as str]
   [ai.miniforge.cli.workflow-recommendation-config :as recommendation-config]
   [ai.miniforge.workflow.interface :as workflow]))

;------------------------------------------------------------------------------ Layer 0

;; Prompt templates
(defn ^{:stratum 0} build-workflow-summary
  "Build a concise summary of a workflow for LLM prompt.

   Arguments:
     workflow - Workflow definition map

   Returns: String summary"
  [workflow]
  (let [chars (workflow/workflow-characteristics workflow)
        summary-labels (:summary-labels
                        (recommendation-config/recommendation-prompt-config))]
    (str "- " (name (:id chars)) " (v" (:version chars) ")"
         "\n  " (:name chars)
         "\n  Complexity: " (name (:complexity chars))
         "\n  Phases: " (:phases chars)
         "\n  Task types: " (str/join ", " (map name (:task-types chars)))
         (when (:has-review chars)
           (str "\n  " (:has-review summary-labels)))
         (when (:has-testing chars)
           (str "\n  " (:has-testing summary-labels))))))

(defn ^{:stratum 0} extract-spec-summary
  "Extract key information from spec for LLM analysis.

   Arguments:
     spec - Task specification map

   Returns: String summary"
  [spec]
  (let [title (:spec/title spec)
        description (:spec/description spec)
        intent (:spec/intent spec)
        constraints (:spec/constraints spec)
        workflow-type (:spec/workflow-type spec)]
    (str "Title: " title "\n\n"
         (when description (str "Description: " description "\n\n"))
         (when intent (str "Intent: " (pr-str intent) "\n\n"))
         (when constraints (str "Constraints: " (pr-str constraints) "\n\n"))
         (when workflow-type (str "Preferred workflow: " workflow-type)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-workflow-summaries
  "Build summaries for all available workflows.

   Arguments:
     workflows - Sequence of workflow definition maps

   Returns: String with workflow summaries"
  [workflows]
  (str/join "\n\n" (map build-workflow-summary workflows)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-recommendation-prompt
  "Build LLM prompt for workflow recommendation.

   Arguments:
     spec - Task specification map
     workflows - Sequence of available workflows

   Returns: String prompt"
  [spec workflows]
  (let [prompt-config (recommendation-config/recommendation-prompt-config)
        analysis-dimensions (:analysis-dimensions prompt-config)]
    (str (:system-intro prompt-config) "\n\n"
         (:task-instruction prompt-config) "\n\n"
         (:available-workflows-header prompt-config) "\n\n"
         (build-workflow-summaries workflows)
         "\n\n"
         (:task-spec-header prompt-config) "\n\n"
         (extract-spec-summary spec)
         "\n\n"
         (:analysis-intro prompt-config) "\n"
         (->> analysis-dimensions
              (map-indexed (fn [idx dimension]
                             (str (inc idx) ". " dimension)))
              (str/join "\n"))
         "\n\n"
         (:json-instruction prompt-config))))
