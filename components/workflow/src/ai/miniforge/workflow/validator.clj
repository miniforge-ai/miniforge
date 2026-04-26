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

(ns ai.miniforge.workflow.validator
  "Validation for workflow configurations.
   Handles schema validation (Malli) and DAG validation (cycles, reachability)."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.workflow.definition :as definition]
   [ai.miniforge.workflow.fsm :as workflow-fsm]))

;------------------------------------------------------------------------------ Layer 0
;; Schema loading

(defn load-schema
  "Load Malli schema from resources/schemas/workflow.edn.
   Returns the schema map or nil if not found."
  []
  (when-let [resource (io/resource "schemas/workflow.edn")]
    (with-open [rdr (io/reader resource)]
      (edn/read (java.io.PushbackReader. rdr)))))

;------------------------------------------------------------------------------ Layer 1
;; Schema validation

(defn valid?
  "Predicate for workflow validation result maps."
  [validation]
  (true? (:valid? validation)))

(defn- validation-result
  [errors]
  {:valid? (empty? errors)
   :errors (vec errors)})

(defn- validation-result-with-warnings
  [errors warnings]
  (assoc (validation-result errors)
         :warnings (vec warnings)))

(defn validate-schema
  "Validate workflow config against Malli schema.

   Supports both old (:workflow/phases) and new (:workflow/pipeline) formats.

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])
        has-phases? (:workflow/phases config)
        has-pipeline? (:workflow/pipeline config)]

    ;; Check required top-level keys
    (when-not (:workflow/id config)
      (swap! errors conj "Missing required key: :workflow/id"))
    (when-not (:workflow/version config)
      (swap! errors conj "Missing required key: :workflow/version"))
    (when-not (:workflow/name config)
      (swap! errors conj "Missing required key: :workflow/name"))
    (when-not (:workflow/description config)
      (swap! errors conj "Missing required key: :workflow/description"))

    ;; created-at and task-types are optional (new pipeline format doesn't require them)

    ;; Must have either phases or pipeline
    (when-not (or has-phases? has-pipeline?)
      (swap! errors conj "Missing required key: :workflow/phases or :workflow/pipeline"))

    ;; Validate phases format (old format)
    (when-let [phases (:workflow/phases config)]
      (when-not (vector? phases)
        (swap! errors conj ":workflow/phases must be a vector"))

      ;; Check each phase
      (doseq [[idx phase] (map-indexed vector phases)]
        (when-not (:phase/id phase)
          (swap! errors conj (str "Phase " idx " missing :phase/id")))
        (when-not (:phase/name phase)
          (swap! errors conj (str "Phase " idx " missing :phase/name")))
        (when-not (:phase/agent phase)
          (swap! errors conj (str "Phase " idx " missing :phase/agent")))
        (when-not (:phase/next phase)
          (swap! errors conj (str "Phase " idx " missing :phase/next")))))

    ;; Validate pipeline format (new format)
    (when-let [pipeline (:workflow/pipeline config)]
      (when-not (vector? pipeline)
        (swap! errors conj ":workflow/pipeline must be a vector"))

      ;; Check each pipeline entry
      (doseq [[idx entry] (map-indexed vector pipeline)]
        (when-not (map? entry)
          (swap! errors conj (str "Pipeline entry " idx " must be a map")))
        (when-not (:phase entry)
          (swap! errors conj (str "Pipeline entry " idx " missing :phase keyword")))))

    ;; Check task-types is a vector if present
    (when-let [task-types (:workflow/task-types config)]
      (when-not (vector? task-types)
        (swap! errors conj ":workflow/task-types must be a vector")))

    (validation-result @errors)))

;------------------------------------------------------------------------------ Layer 2
;; Compiled-machine validation

(defn- machine-validation-messages
  [workflow]
  (let [execution-workflow (definition/execution-workflow workflow)
        validation (workflow-fsm/validate-execution-machine execution-workflow)
        errors (mapv :message (:errors validation))
        warnings (mapv :message (:warnings validation))]
    (validation-result-with-warnings errors warnings)))

(defn validate-dag
  "Validate workflow DAG structure.
   Delegates to the compiled execution-machine validator so legacy
   phase workflows and pipeline workflows share one authority."
  [config]
  (machine-validation-messages config))

;------------------------------------------------------------------------------ Layer 3
;; Budget validation

(defn validate-budgets
  "Validate budget constraints.

   Returns:
   {:valid? boolean
    :errors [string]}"
  [config]
  (let [errors (atom [])
        budgets (:workflow/budgets config)]

    (when budgets
      ;; Check budget values are positive
      (when-let [tokens (:budget/tokens budgets)]
        (when-not (pos? tokens)
          (swap! errors conj "Budget tokens must be positive")))

      (when-let [cost (:budget/cost-usd budgets)]
        (when-not (pos? cost)
          (swap! errors conj "Budget cost must be positive")))

      (when-let [duration (:budget/duration-ms budgets)]
        (when-not (pos? duration)
          (swap! errors conj "Budget duration must be positive"))))

    (validation-result @errors)))

;------------------------------------------------------------------------------ Layer 4
;; Combined validation

(defn validate-workflow
  "Perform full validation of workflow config.
   Combines schema, DAG, and budget validation.

  Returns:
  {:valid? boolean
   :errors [string]
   :warnings [string]}"
  [config]
  (let [schema-result (validate-schema config)
        machine-result (validate-dag config)
        budget-result (validate-budgets config)
        all-errors (concat (:errors schema-result)
                           (:errors machine-result)
                           (:errors budget-result))
        warnings (:warnings machine-result)]
    (validation-result-with-warnings all-errors warnings)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Load schema
  (def schema (load-schema))

  ;; Test validation with sample config
  (def sample-config
    {:workflow/id :test-workflow
     :workflow/version "1.0.0"
     :workflow/type :test
     :workflow/phases
     [{:phase/id :start
       :phase/name "Start"
       :phase/agent-type :planner
       :phase/on-success {:transition/target :end}}
      {:phase/id :end
       :phase/name "End"
       :phase/agent-type :none}]
     :workflow/entry-phase :start
     :workflow/exit-phases [:end]})

  (validate-workflow sample-config)

  :end)
