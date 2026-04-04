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

(ns ai.miniforge.schema.interface
  "Public API for the schema component.
   Provides validation functions and schema access for domain types."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [ai.miniforge.schema.core :as core]
   [ai.miniforge.schema.logging :as logging]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Schema re-exports (allow other components to reference schemas)

(def Agent core/Agent)
(def Task core/Task)
(def Artifact core/Artifact)
(def Workflow core/Workflow)
(def TaskConstraints core/TaskConstraints)
(def TaskResult core/TaskResult)
(def ArtifactOrigin core/ArtifactOrigin)
(def WorkflowBudget core/WorkflowBudget)

(def LogEntry logging/LogEntry)
(def Scenario logging/Scenario)

;; Enum value sets for programmatic access
(def agent-roles core/agent-roles)
(def task-types core/task-types)
(def task-statuses core/task-statuses)
(def artifact-types core/artifact-types)
(def workflow-phases core/workflow-phases)
(def workflow-statuses core/workflow-statuses)
(def log-levels logging/log-levels)
(def log-categories logging/log-categories)
(def all-events logging/all-events)
(def scenario-tags logging/scenario-tags)

;------------------------------------------------------------------------------ Layer 1
;; Validation functions

(defn valid?
  "Returns true if value validates against schema."
  [schema value]
  (m/validate schema value))

(defn validate
  "Returns value if valid, throws ex-info with explanation if invalid."
  [schema value]
  (if (m/validate schema value)
    value
    (throw (ex-info "Schema validation failed"
                    {:schema schema
                     :value value
                     :errors (me/humanize (m/explain schema value))}))))

(defn explain
  "Returns human-readable explanation of validation errors, or nil if valid."
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

(defn valid-agent?
  "Returns true if value is a valid Agent."
  [value]
  (valid? Agent value))

(defn valid-task?
  "Returns true if value is a valid Task."
  [value]
  (valid? Task value))

(defn valid-artifact?
  "Returns true if value is a valid Artifact."
  [value]
  (valid? Artifact value))

(defn valid-workflow?
  "Returns true if value is a valid Workflow."
  [value]
  (valid? Workflow value))

(defn valid-log-entry?
  "Returns true if value is a valid LogEntry."
  [value]
  (valid? LogEntry value))

(defn valid-scenario?
  "Returns true if value is a valid Scenario."
  [value]
  (valid? Scenario value))

;------------------------------------------------------------------------------ Layer 2
;; Response helpers

(defn success
  "Create a success response.

   Arguments:
   - data-key - Keyword for the data field (e.g., :pack, :rule, :artifact)
   - data     - The successful result data
   - opts     - Optional map with additional fields

   Returns:
   - {:success? true data-key data ...opts}

   Example:
     (success :pack my-pack)
     ;; => {:success? true :pack my-pack}

     (success :pack my-pack {:errors nil})
     ;; => {:success? true :pack my-pack :errors nil}"
  ([data-key data]
   (success data-key data nil))
  ([data-key data opts]
   (merge {:success? true data-key data} opts)))

(defn failure
  "Create a failure response with a single error.

   Arguments:
   - data-key   - Keyword for the data field (e.g., :pack, :rule, :artifact)
   - error      - Error message string or error map
   - opts       - Optional map with additional fields

   Returns:
   - {:success? false data-key nil :error error :anomaly anomaly-map ...opts}

   Example:
     (failure :pack \"File not found\")
     ;; => {:success? false :pack nil :error \"File not found\" :anomaly {...}}

     (failure :pack {:message \"Invalid\" :stage :parsing})
     ;; => {:success? false :pack nil :error {:message \"Invalid\" :stage :parsing} :anomaly {...}}"
  ([data-key error]
   (failure data-key error nil))
  ([data-key error opts]
   (let [msg (if (string? error) error (or (:message error) (pr-str error)))]
     (merge {:success? false data-key nil :error error
             :anomaly (response/make-anomaly :anomalies/fault msg)}
            opts))))

(defn failure-with-errors
  "Create a failure response with multiple errors.

   Arguments:
   - data-key - Keyword for the data field (e.g., :pack, :rule)
   - errors   - Vector of error maps
   - opts     - Optional map with additional fields

   Returns:
   - {:success? false data-key nil :errors errors ...opts}

   Example:
     (failure-with-errors :pack [{:file \"pack.edn\" :error \"Not found\"}])
     ;; => {:success? false :pack nil :errors [{:file \"pack.edn\" :error \"Not found\"}]}"
  ([data-key errors]
   (failure-with-errors data-key errors nil))
  ([data-key errors opts]
   (merge {:success? false data-key nil :errors errors} opts)))

(defn exception-failure
  "Create a failure response from an exception.

   Arguments:
   - data-key - Keyword for the data field (e.g., :pack, :rule)
   - ex       - Exception object
   - opts     - Optional map with additional fields (e.g., :stage)

   Returns:
   - {:success? false data-key nil :error {:message ... :data ...} :anomaly anomaly-map ...opts}

   Example:
     (exception-failure :pack (Exception. \"IO error\"))
     ;; => {:success? false :pack nil :error {:message \"IO error\"} :anomaly {...}}

     (exception-failure :pack ex {:stage :extraction})
     ;; => {:success? false :pack nil :error {:message ... :stage :extraction} :anomaly {...}}"
  ([data-key ex]
   (exception-failure data-key ex nil))
  ([data-key ex opts]
   (let [error-map (cond-> {:message (ex-message ex)}
                     (ex-data ex) (assoc :data (ex-data ex)))]
     (merge {:success? false data-key nil :error error-map
             :anomaly (response/from-exception ex)}
            opts))))

(defn succeeded?
  "Returns true if result represents a successful operation.
   Prefer over direct :success? key access."
  [result]
  (true? (:success? result)))

(defn failed?
  "Returns true if result represents a failed operation.
   Prefer over direct :success? key access."
  [result]
  (not (true? (:success? result))))

;------------------------------------------------------------------------------ Layer 3
;; Validation response helpers

(defn valid
  "Create a valid validation response.

   Arguments:
   - opts - Optional map with additional fields (e.g., :graph, :packs)

   Returns:
   - {:valid? true ...opts}

   Example:
     (valid)
     ;; => {:valid? true}

     (valid {:packs [\"pack-a\" \"pack-b\"]})
     ;; => {:valid? true :packs [\"pack-a\" \"pack-b\"]}"
  ([]
   (valid nil))
  ([opts]
   (merge {:valid? true} opts)))

(defn invalid
  "Create an invalid validation response with error.

   Arguments:
   - error - Error message string or error map
   - opts  - Optional map with additional fields (e.g., :tainted-path)

   Returns:
   - {:valid? false :error error ...opts}

   Example:
     (invalid \"Circular dependency detected\")
     ;; => {:valid? false :error \"Circular dependency detected\"}

     (invalid \"Tainted content\" {:tainted-path [\"a\" \"b\"]})
     ;; => {:valid? false :error \"Tainted content\" :tainted-path [\"a\" \"b\"]}"
  ([error]
   (invalid error nil))
  ([error opts]
   (merge {:valid? false :error error} opts)))

(defn invalid-with-errors
  "Create an invalid validation response with multiple errors.

   Arguments:
   - errors - Vector of error strings or error maps
   - opts   - Optional map with additional fields

   Returns:
   - {:valid? false :errors errors ...opts}

   Example:
     (invalid-with-errors [\"Error 1\" \"Error 2\"])
     ;; => {:valid? false :errors [\"Error 1\" \"Error 2\"]}"
  ([errors]
   (invalid-with-errors errors nil))
  ([errors opts]
   (merge {:valid? false :errors errors} opts)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Validate an agent
  (valid-agent? {:agent/id (random-uuid)
                 :agent/role :implementer})
  ;; => true

  ;; Get validation errors
  (explain Agent {:agent/id "not-a-uuid"
                  :agent/role :invalid-role})
  ;; => {:agent/id ["should be a uuid"], :agent/role ["should be either ..."]}

  ;; Validate and return or throw
  (validate Task {:task/id (random-uuid)
                  :task/type :implement
                  :task/status :pending})
  ;; => {:task/id #uuid "...", :task/type :implement, :task/status :pending}

  ;; Access enum values
  agent-roles
  ;; => [:planner :architect :implementer :tester :reviewer :sre :security :release :historian :operator]

  :leave-this-here)
