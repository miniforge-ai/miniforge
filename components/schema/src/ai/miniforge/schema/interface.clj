(ns ai.miniforge.schema.interface
  "Public API for the schema component.
   Provides validation functions and schema access for domain types."
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [ai.miniforge.schema.core :as core]
   [ai.miniforge.schema.logging :as logging]))

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
