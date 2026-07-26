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
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.schema.core :as core]
   [ai.miniforge.schema.logging :as logging]
   [ai.miniforge.schema.supervisory :as supervisory]))

;------------------------------------------------------------------------------ Layer 0

;; Schema re-exports (allow other components to reference schemas)
(def ^{:stratum 0} Agent
  "Malli schema (open `:map` — no `{:closed true}`, so extra keys are accepted)
   for an AI agent: `:agent/id` (uuid) and `:agent/role` (enum) required;
   optional capabilities, memory, config.
   Pass to [[valid?]]/[[validate]]/[[validate-anomaly]] to check agent maps."
  core/Agent)

(def ^{:stratum 0} Task
  "Malli `:map` schema for a unit of work: `:task/id`, `:task/type`,
   `:task/status` required; optional agent, io artifacts, parent/children,
   constraints, result. Use with the validation fns."
  core/Task)

(def ^{:stratum 0} Artifact
  "Malli `:map` schema for a versioned work product with provenance:
   `:artifact/id`, `:artifact/type`, `:artifact/version` required; optional
   content, origin, lineage, metadata, created-at."
  core/Artifact)

(def ^{:stratum 0} Workflow
  "Malli `:map` schema for an outer-loop SDLC delivery instance:
   `:workflow/id` and `:workflow/status` required; optional name, phase,
   priority, checkpoint, budget/consumed, spec-id, meta-agents."
  core/Workflow)

(def ^{:stratum 0} TaskConstraints
  "Malli `:map` schema for task execution constraints: optional budget
   (tokens/cost-usd/duration-ms), deadline, policies, max-iterations.
   Embedded under `:task/constraints` in [[Task]]."
  core/TaskConstraints)

(def ^{:stratum 0} TaskResult
  "Malli `:map` schema for task execution result: required `:outcome`
   (`:success`/`:failure`/`:escalated`); optional error, signals, metrics.
   Embedded under `:task/result` in [[Task]]."
  core/TaskResult)

(def ^{:stratum 0} ArtifactOrigin
  "Malli `:map` schema for artifact provenance: optional `:intent-id`,
   `:agent-id`, `:task-id`. Embedded under `:artifact/origin` in [[Artifact]]."
  core/ArtifactOrigin)

(def ^{:stratum 0} WorkflowBudget
  "Malli `:map` schema for a workflow budget allocation: optional `:tokens`,
   `:cost-usd`, `:duration-ms`. Used for both `:workflow/budget` and
   `:workflow/consumed` in [[Workflow]]."
  core/WorkflowBudget)

(def ^{:stratum 0} Metrics
  "Malli `:map` schema for an agent/phase result's `:metrics`: REQUIRED non-nil
   `:tokens` and `:duration-ms` (consumed by cost/accumulation arithmetic),
   optional `:cost-usd`, `:iterations`. Validate agent-result metrics against
   this at the phase-input boundary so a nil fails loud instead of NPE-ing."
  core/Metrics)

(def ^{:stratum 0} LogEntry
  "Malli `:map` schema for a structured EDN log entry. Required: `:log/id`,
   `:log/timestamp`, `:log/level`, `:log/category`, `:log/event`. Optional:
   message, context (`:ctx/*`), scenario, data, tracing, perf metrics."
  logging/LogEntry)

(def ^{:stratum 0} Scenario
  "Malli `:map` schema for a test scenario definition: `:scenario/id`,
   `:scenario/name`, `:scenario/created-at`, `:scenario/status` required;
   optional tags, created-by, config, expected."
  logging/Scenario)

;; Supervisory entity schemas
(def ^{:stratum 0} WorkflowRun
  "Malli open `:map` schema for a supervisory workflow run: `:workflow/id`,
   `:workflow/key`, `:workflow/status`, `:workflow/phase`,
   `:workflow/started-at` required. Extra keys pass through."
  supervisory/WorkflowRun)

(def ^{:stratum 0} PolicyEvaluation
  "Malli open `:map` schema for a policy evaluation result: `:eval/id`,
   `:eval/pr-id`, `:eval/result`, `:eval/rules-applied`, `:eval/evaluated-at`
   required. Extra keys pass through."
  supervisory/PolicyEvaluation)

(def ^{:stratum 0} PolicyViolation
  "Malli open `:map` schema for a single policy violation: `:violation/id`,
   `:violation/eval-id`, `:violation/rule`, `:violation/category`,
   `:violation/severity` required. Extra keys pass through."
  supervisory/PolicyViolation)

(def ^{:stratum 0} AttentionItem
  "Malli open `:map` schema for an item needing human oversight:
   `:attention/id`, `:attention/severity`, `:attention/summary`,
   `:attention/source-type`, `:attention/source-id` required. Extra keys pass."
  supervisory/AttentionItem)

(def ^{:stratum 0} Waiver
  "Malli open `:map` schema for a human waiver of a violation: `:waiver/id`,
   `:waiver/eval-id`, `:waiver/actor`, `:waiver/reason`, `:waiver/created-at`
   required. Extra keys pass through."
  supervisory/Waiver)

(def ^{:stratum 0} EvidenceBundle
  "Malli open `:map` schema for evidence collected during a workflow:
   `:evidence/id`, `:evidence/workflow-id`, `:evidence/entries` required.
   Extra keys pass through."
  supervisory/EvidenceBundle)

;; Enum value sets for programmatic access
(def ^{:stratum 0} agent-roles
  "Vector of canonical agent role keywords, ordered by implementation priority
   (`:planner`, `:architect`, `:implementer`, ...)."
  core/agent-roles)

(def ^{:stratum 0} task-types
  "Vector of task-type keywords (`:plan`, `:design`, `:implement`, `:test`,
   `:review`, `:deploy`)."
  core/task-types)

(def ^{:stratum 0} task-statuses
  "Vector of task-status keywords (`:pending`, `:running`, `:completed`,
   `:failed`, `:blocked`)."
  core/task-statuses)

(def ^{:stratum 0} artifact-types
  "Vector of artifact-type keywords (`:spec`, `:plan`, `:adr`, `:code`,
   `:test`, `:review`, `:manifest`, `:image`, `:telemetry`, `:incident`)."
  core/artifact-types)

(def ^{:stratum 0} workflow-phases
  "Vector of outer-loop SDLC phase keywords (`:plan`, `:design`, `:implement`,
   `:verify`, `:review`, `:release`, `:observe`)."
  core/workflow-phases)

(def ^{:stratum 0} workflow-statuses
  "Vector of workflow-status keywords (`:pending`, `:running`, `:paused`,
   `:completed`, `:failed`, `:cancelled`)."
  core/workflow-statuses)

(def ^{:stratum 0} severities
  "Canonical severity levels, most to least severe
   (`:critical :high :medium :low :info`). One source of truth for rule,
   violation, attention, and display severity."
  core/severities)

(def ^{:stratum 0} Severity
  "Malli enum schema for a canonical severity level."
  core/Severity)

(def ^{:stratum 0} severity-order
  "Map from severity keyword to rank (0 = most severe), derived from
   `severities`."
  core/severity-order)

(def ^{:stratum 0} normalize-severity
  "Coerce a legacy severity (`:major` → `:high`, `:minor` → `:low`) to the
   canonical enum; canonical/other values pass through unchanged."
  core/normalize-severity)

(def ^{:stratum 0} compare-severity
  "Compare two severities: negative when the first is more severe, positive when
   less, 0 when equal."
  core/compare-severity)

(def ^{:stratum 0} more-severe
  "Return the more severe of two severities."
  core/more-severe)

(def ^{:stratum 0} log-levels
  "Vector of log severity keywords, most to least verbose (`:trace`, `:debug`,
   `:info`, `:warn`, `:error`, `:fatal`)."
  logging/log-levels)

(def ^{:stratum 0} log-categories
  "Vector of log category keywords (`:agent`, `:loop`, `:policy`, `:artifact`,
   `:system`)."
  logging/log-categories)

(def ^{:stratum 0} all-events
  "Vector of all known log-event keywords across the agent, loop, policy,
   artifact, and system taxonomies."
  logging/all-events)

(def ^{:stratum 0} scenario-tags
  "Vector of standard scenario-test tag keywords (`:canary`, `:shadow`,
   `:regression`, ...)."
  logging/scenario-tags)

;; Supervisory enum value sets
(def ^{:stratum 0} eval-results
  "Vector of policy-evaluation outcome keywords (`:pass`, `:fail`, `:warn`,
   `:skip`)."
  supervisory/eval-results)

(def ^{:stratum 0} violation-categories
  "Vector of policy-violation category keywords (`:style`, `:security`,
   `:testing`, `:documentation`, `:architecture`, `:process`, `:budget`)."
  supervisory/violation-categories)

(def ^{:stratum 0} violation-severities
  "Vector of severity keywords for violations and attention items (`:info`,
   `:low`, `:medium`, `:high`, `:critical`)."
  supervisory/violation-severities)

(def ^{:stratum 0} attention-source-types
  "Vector of source-type keywords that can raise attention items (`:workflow`,
   `:policy`, `:agent`, `:system`, `:human`)."
  supervisory/attention-source-types)

;; Validation functions
(defn ^{:stratum 0} valid?
  "Returns true if value validates against schema."
  [schema value]
  (m/validate schema value))

(defn ^{:stratum 0} validate-anomaly
  "Validate `value` against `schema`. Return `value` on success, or an
   `:invalid-input` anomaly map describing the validation failure.

   The anomaly's `:anomaly/data` carries:
   - `:errors` — humanized malli error map
   - `:value`  — the offending value, for diagnostic context

   Prefer this over [[validate]] in non-boundary code: callers can fold the
   anomaly into a response chain or pattern-match on `:anomaly/type` rather
   than wrapping every call in try/catch."
  [schema value]
  (if (m/validate schema value)
    value
    (anomaly/anomaly :invalid-input
                     "Schema validation failed"
                     {:errors (me/humanize (m/explain schema value))
                      :value  value})))

(defn ^{:stratum 0} explain
  "Returns human-readable explanation of validation errors, or nil if valid."
  [schema value]
  (when-let [explanation (m/explain schema value)]
    (me/humanize explanation)))

;; Response helpers
(defn ^{:stratum 0} success
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

(defn ^{:stratum 0} failure
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
             :anomaly (anomaly/anomaly :fault msg {})}
            opts))))

(defn ^{:stratum 0} failure-with-errors
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

(defn ^{:stratum 0} exception-failure
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
             :anomaly (anomaly/exception-anomaly :fault (ex-message ex) ex)}
            opts))))

(defn ^{:stratum 0} succeeded?
  "Returns true if result represents a successful operation.
   Prefer over direct :success? key access."
  [result]
  (true? (:success? result)))

(defn ^{:stratum 0} failed?
  "Returns true if result represents a failed operation.
   Prefer over direct :success? key access."
  [result]
  (not (true? (:success? result))))

;; Validation response helpers
(defn ^{:stratum 0} valid
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

(defn ^{:stratum 0} invalid
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

(defn ^{:stratum 0} invalid-with-errors
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate
  "Returns value if valid, throws ex-info with explanation if invalid.

   DEPRECATED: prefer [[validate-anomaly]], which returns an anomaly map
   rather than throwing. This thrower is retained for backward compatibility
   with existing callsites; new code should consume `validate-anomaly` and
   fold its result into a response chain."
  {:deprecated "exceptions-as-data — prefer validate-anomaly"}
  [schema value]
  (let [result (validate-anomaly schema value)]
    (if (anomaly/anomaly? result)
      (throw (ex-info "Schema validation failed"
                      {:schema schema
                       :value  value
                       :errors (get-in result [:anomaly/data :errors])}))
      result)))

(defn ^{:stratum 1} valid-agent?
  "Returns true if value is a valid Agent."
  [value]
  (valid? Agent value))

(defn ^{:stratum 1} valid-task?
  "Returns true if value is a valid Task."
  [value]
  (valid? Task value))

(defn ^{:stratum 1} valid-artifact?
  "Returns true if value is a valid Artifact."
  [value]
  (valid? Artifact value))

(defn ^{:stratum 1} valid-workflow?
  "Returns true if value is a valid Workflow."
  [value]
  (valid? Workflow value))

(defn ^{:stratum 1} valid-log-entry?
  "Returns true if value is a valid LogEntry."
  [value]
  (valid? LogEntry value))

(defn ^{:stratum 1} valid-scenario?
  "Returns true if value is a valid Scenario."
  [value]
  (valid? Scenario value))

;; Supervisory entity validators
(defn ^{:stratum 1} valid-workflow-run?
  "Returns true if value is a valid WorkflowRun."
  [value]
  (valid? WorkflowRun value))

(defn ^{:stratum 1} valid-policy-evaluation?
  "Returns true if value is a valid PolicyEvaluation."
  [value]
  (valid? PolicyEvaluation value))

(defn ^{:stratum 1} valid-policy-violation?
  "Returns true if value is a valid PolicyViolation."
  [value]
  (valid? PolicyViolation value))

(defn ^{:stratum 1} valid-attention-item?
  "Returns true if value is a valid AttentionItem."
  [value]
  (valid? AttentionItem value))

(defn ^{:stratum 1} valid-waiver?
  "Returns true if value is a valid Waiver."
  [value]
  (valid? Waiver value))

(defn ^{:stratum 1} valid-evidence-bundle?
  "Returns true if value is a valid EvidenceBundle."
  [value]
  (valid? EvidenceBundle value))

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

  ;; Supervisory entity validation
  (valid-workflow-run? {:workflow/id (random-uuid)
                        :workflow/key "feature-auth"
                        :workflow/status :running
                        :workflow/phase :implement
                        :workflow/started-at (java.util.Date.)})
  ;; => true

  ;; Access enum values
  agent-roles
  ;; => [:planner :architect :implementer :tester :reviewer :sre :security :release :historian :operator]

  :leave-this-here)
