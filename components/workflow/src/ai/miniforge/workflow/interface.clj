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

(ns ai.miniforge.workflow.interface
  "Canonical public boundary for the workflow component.
   Public API groups live under ai.miniforge.workflow.interface.* namespaces,
   while this namespace remains the Polylith entrypoint."
  (:require
   [ai.miniforge.workflow.fsm :as fsm]
   [ai.miniforge.workflow.interface.checkpoints :as checkpoints]
   [ai.miniforge.workflow.interface.configurable :as configurable]
   [ai.miniforge.workflow.interface.pipeline :as pipeline]
   [ai.miniforge.workflow.interface.profiles :as profiles]
   [ai.miniforge.workflow.interface.protocols :as protocols]
   [ai.miniforge.workflow.interface.registry :as registry]
   [ai.miniforge.workflow.interface.runtime :as runtime]
   [ai.miniforge.workflow.interface.supervision :as supervision]
   [ai.miniforge.workflow.visualize :as visualize]))

;------------------------------------------------------------------------------ Layer 0
;; Protocols and workflow topology

(def Workflow
  "Protocol governing workflow execution: start/get-state/advance/rollback/
   complete/fail. Main extensibility point for custom workflow
   implementations."
  protocols/Workflow)

(def PhaseExecutor
  "Protocol for executing an individual SDLC phase: execute-phase/
   can-execute?/get-phase-requirements. Extensibility point for custom
   phase executors."
  protocols/PhaseExecutor)

(def WorkflowObserver
  "Protocol for observing workflow events (phase start/complete/error,
   workflow complete, rollback). Extensibility point for operator/
   meta-loop integration."
  protocols/WorkflowObserver)

(def phases
  "Ordered vector of SDLC phase keywords:
   [:spec :plan :design :implement :verify :review :release :observe :done]."
  protocols/phases)

(def phase-transitions
  "Map of phase keyword -> set of allowed next-phase keywords. The empty
   set for :done marks the terminal phase."
  protocols/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Workflow lifecycle and execution

(def create-workflow
  "Construct a new in-memory SimpleWorkflow instance (Workflow protocol).
   Args: [] or [config]. The optional config map is merged over the defaults
   {:max-iterations-per-phase 5, :max-rollbacks 3, :timeout-per-phase-ms 600000};
   omit it to use the defaults. Returns the workflow object used as `this` in
   the lifecycle fns."
  runtime/create-workflow)

(def add-observer
  "Register a WorkflowObserver on a workflow. Args: [workflow observer].
   Returns the workflow with the observer attached."
  runtime/add-observer)

(def remove-observer
  "Detach a previously-registered WorkflowObserver. Args: [workflow observer].
   Returns the workflow without that observer."
  runtime/remove-observer)

(def start
  "Start a new run from a spec. Args: [workflow spec context]; spec is
   {:title :description}, context is {:llm-backend :budget :tags}. Returns
   the workflow-id (UUID)."
  runtime/start)

(def get-state
  "Read current run state. Args: [workflow workflow-id]. Returns the state
   map {:workflow/phase :workflow/status :workflow/artifacts ...}, or nil
   when the id is unknown."
  runtime/get-state)

(def advance
  "Advance a run to its next phase from a phase result. Args:
   [workflow workflow-id phase-result] where phase-result is
   {:success? :artifacts :errors :metrics}. Returns the updated state map."
  runtime/advance)

(def rollback
  "Roll a run back to an earlier phase. Args:
   [workflow workflow-id target-phase reason]. Returns the updated state
   map; transitions to :failed status once max-rollbacks is exceeded."
  runtime/rollback)

(def complete
  "Mark a run complete (phase :done, status :completed). Args:
   [workflow workflow-id]. Returns the final state map."
  runtime/complete)

(def fail
  "Mark a run failed and append the error. Args: [workflow workflow-id error].
   Returns the final state map with status :failed."
  runtime/fail)

(def create-phase-executor
  "Build a PhaseExecutor for a phase keyword. Args: [phase llm-backend].
   Returns an executor for :plan/:implement/:verify/:release, or nil for
   phases with no dedicated executor."
  runtime/create-phase-executor)

(def execute-phase
  "Run the current phase via a PhaseExecutor. Args:
   [executor workflow-state context]. Returns
   {:success? :artifacts :errors :metrics}."
  runtime/execute-phase)

(def can-execute?
  "Test whether a PhaseExecutor handles a phase. Args: [executor phase].
   Returns boolean."
  runtime/can-execute?)

(def get-phase-requirements
  "Required/optional inputs for a phase from a PhaseExecutor. Args:
   [executor phase]. Returns
   {:required-artifacts [...] :optional-artifacts [...]}."
  runtime/get-phase-requirements)

(def run-workflow
  "Drive a run end-to-end from spec to a terminal state. Args:
   [workflow spec context]. Loops execute-phase/advance until the run is
   :completed or :failed. Returns the final state map."
  runtime/run-workflow)

;------------------------------------------------------------------------------ Layer 2
;; Supervision machine

(def supervision-states
  "Set of all per-run supervision state keywords (keys of the machine config)."
  supervision/supervision-states)

(def supervision-terminal-states
  "Set of supervision state keywords whose config type is :final."
  supervision/terminal-states)

(def supervision-transitions
  "Map of [from-state event] -> to-state for the supervision machine."
  supervision/supervision-transitions)

(def valid-supervision-state?
  "Predicate: is the keyword a known supervision state? Args: [state].
   Returns boolean."
  supervision/valid-state?)

(def supervision-terminal-state?
  "Predicate: is the supervision state terminal? Args: [state]. Returns
   boolean."
  supervision/terminal-state?)

(def valid-supervision-transition?
  "Predicate: is [current-state event] a defined transition? Args:
   [current-state event]. Returns boolean."
  supervision/valid-transition?)

(def next-supervision-state
  "Resolve the target state for a transition. Args: [current-state event].
   Returns the to-state keyword, or nil when no such transition exists."
  supervision/next-state)

(def transition-supervision
  "Attempt a pure supervision transition (optionally guarded). Args:
   [current-state event] or [current-state event guard-fn]. Returns
   {:success? true :state new-state} on success, or
   {:success? false :error keyword :message string} on rejection."
  supervision/transition)

(def get-supervision-events
  "List events available from a state. Args: [current-state]. Returns a
   vector of event keywords."
  supervision/get-available-events)

(def supervision-machine-graph
  "Graph-style view of the supervision machine. No args. Returns
   {:states set :transitions [{:from :event :to} ...] :terminal-states set}."
  supervision/machine-graph)

(def initialize-supervision
  "Initialize a compiled-FSM supervision state. No args. Returns the
   shared-FSM state map at the machine's initial state."
  supervision/initialize)

(def transition-supervision-fsm
  "Transition the compiled-FSM supervision state. Args: [state event].
   Returns the new shared-FSM state map."
  supervision/transition-fsm)

(def current-supervision-state
  "Read the current state keyword from a compiled-FSM state map. Args:
   [state]. Returns the state keyword."
  supervision/current-state)

(def final-supervision-state?
  "Predicate: is the compiled-FSM state map in a final state? Args:
   [state]. Returns boolean."
  supervision/is-final?)

;------------------------------------------------------------------------------ Layer 3
;; Pipeline, configurable workflows, persistence, and registry helpers

(def run-pipeline
  "Execute a workflow's interceptor pipeline end-to-end. Args:
   [workflow input] or [workflow input opts]. Acquires the execution
   environment, runs phases, validates, publishes, and always cleans up.
   Returns the final execution context map; throws a slingshot anomaly on
   governed-mode misconfiguration."
  pipeline/run-pipeline)

(def build-pipeline
  "Build the interceptor pipeline from a workflow config. Args: [workflow].
   Returns a vector of phase interceptors."
  pipeline/build-pipeline)

(def validate-pipeline
  "Validate a workflow's pipeline (phase + execution-machine checks). Args:
   [workflow]. Returns {:valid? boolean :errors [...] :warnings [...]}."
  pipeline/validate-pipeline)

(def execute-plan-as-dag
  "Execute a normalized plan through the DAG orchestrator. Args:
   [plan context]. Returns an aggregate DAG execution summary with
   :success?, :tasks-completed, :tasks-failed, :tasks-unreached,
   :artifacts, and :metrics."
  pipeline/execute-plan-as-dag)

(def load-checkpoint-data
  "Load durable checkpoint data for a run and validate it at the boundary.
   Args: [workflow-run-id] or [workflow-run-id opts]. Returns the validated
   checkpoint-data map, or nil when no checkpoint exists; throws ex-info
   when a found checkpoint fails schema validation."
  checkpoints/load-checkpoint-data)

(def run-chain
  "Run a sequence of workflows, feeding each step's output into the next.
   Args: [chain-def chain-input opts]. Returns
   {:chain/id :chain/status (:completed|:failed) :chain/step-results
   :chain/step-count :chain/duration-ms}."
  pipeline/run-chain)

(def load-chain
  "Load a chain definition from classpath resources. Args: [chain-id version].
   Returns the chain-def result map; throws a :not-found anomaly when no
   matching chain resource exists."
  pipeline/load-chain)

(def list-chains
  "List all chain definitions on the classpath. No args. Returns a vector of
   summary maps {:id :version :description :steps}."
  pipeline/list-chains)

(def load-workflow
  "Load a configurable workflow config (cache -> resource -> heuristic store),
   validating unless skipped. Args: [workflow-id version] or
   [workflow-id version opts]. Returns
   {:workflow :source (:resource|:store|:cache) :validation}; throws an
   anomaly on parse failure, validation failure, or not-found."
  configurable/load-workflow)

(def execute-workflow
  "Execute a loaded configurable workflow against an input. Args:
   [workflow input] or [workflow input opts]. Returns the final execution
   context map."
  configurable/execute-workflow)

(def get-active-workflow
  "Read the active workflow selection for a task type. Args: [task-type].
   Returns {:workflow-id :version}, or nil when none is set."
  configurable/get-active-workflow)

(def set-active-workflow
  "Persist the active workflow selection for a task type. Args:
   [task-type workflow-id version]. Returns true on success, false on I/O
   error."
  configurable/set-active-workflow)

(def append-event
  "Append a log event to a run's event log file. Args: [workflow-id event].
   Returns true on success, false on I/O error."
  configurable/append-event)

(def load-event-log
  "Load all events for a run from its event log. Args: [workflow-id].
   Returns a vector of event maps, empty when the log is absent."
  configurable/load-event-log)

(def replay-workflow
  "Reconstruct run state by replaying an event stream. Args:
   [events & {:keys [workflow-id until]}]. Returns
   {:state :events-applied :final-status}."
  configurable/replay-workflow)

(def verify-determinism
  "Replay events and compare to an expected state. Args:
   [events expected-state & {:keys [workflow-id until]}]. Returns
   {:deterministic? boolean :differences [...] :replayed-state :events-applied}."
  configurable/verify-determinism)

(def save-workflow
  "Persist a workflow config to the heuristic store. Args: [workflow] or
   [workflow opts]. Returns the saved workflow's UUID."
  configurable/save-workflow)

(def list-workflows
  "List workflow metadata discovered on the classpath. No args. Returns a
   vector of maps {:workflow/id :workflow/version :workflow/type
   :workflow/description ...}."
  configurable/list-workflows)

(def compare-workflows
  "Compare multiple execution states side by side. Args: [execution-states].
   Returns {:executions [summary ...] :comparison {:total-executions
   :completed-count :failed-count :avg-tokens :avg-cost :avg-duration}}."
  configurable/compare-workflows)

(def create-directory-publisher
  "Build a directory publisher descriptor for workflow outputs. Args:
   [output-dir] or [output-dir opts]. Returns a publisher map
   {:publisher/type :directory :publisher/output-dir :publisher/default-format}."
  configurable/create-directory-publisher)

(def publish-output!
  "Write a publication via a publisher. Args: [publisher publication] or
   [publisher publication logger]. Returns
   {:success? true :publication/path ...} on success, or
   {:success? false :error string} for an unknown publisher type."
  configurable/publish-output!)

(def create-event-trigger
  "Subscribe to an event stream and fire workflows on matching events. Args:
   [event-stream trigger-config opts]. Returns
   {:subscriber-id keyword :stop-fn (fn [])}."
  configurable/create-event-trigger)

(def create-merge-trigger
  "Compatibility alias for PR-merge triggered workflows; same contract as
   create-event-trigger. Args: [event-stream trigger-config opts]. Returns
   {:subscriber-id keyword :stop-fn (fn [])}."
  configurable/create-merge-trigger)

(def stop-trigger!
  "Stop a trigger: unsubscribe and cancel pending work. Args:
   [trigger-handle] (the map returned by create-event-trigger). Returns nil."
  configurable/stop-trigger!)

(def load-state-profile-provider
  "Build the state-profile provider by merging app-owned registry.edn
   resources on the classpath. No args. Returns a provider object, or nil
   when no profiles are contributed."
  profiles/load-state-profile-provider)

(def available-state-profile-ids
  "List the profile ids the active provider exposes. No args. Returns a
   vector of profile ids (empty when no provider is present)."
  profiles/available-state-profile-ids)

(def default-state-profile-id
  "Read the provider's default profile id. No args. Returns the id keyword,
   or nil when no provider/default is configured."
  profiles/default-state-profile-id)

(def resolve-state-profile
  "Resolve a profile to its concrete config. Args: [profile] (nil for the
   default, a keyword id, or an inline profile). Returns the resolved
   profile map, or nil when no provider is present."
  profiles/resolve-state-profile)

(def register-workflow!
  "Validate and register a workflow definition. Args: [workflow]. Returns
   the registered workflow; throws an anomaly when :workflow/id is missing
   or validation fails."
  registry/register-workflow!)

(def get-workflow
  "Fetch a registered workflow by id. Args: [workflow-id]. Returns the
   workflow definition map, or nil when absent."
  registry/get-workflow)

(def list-workflow-ids
  "List ids of all registered workflows. No args. Returns a seq of workflow
   id keywords."
  registry/list-workflow-ids)

(def workflow-exists?
  "Predicate: is a workflow id registered? Args: [workflow-id]. Returns
   boolean."
  registry/workflow-exists?)

(def workflow-characteristics
  "Derive selection characteristics from a workflow, validated against the
   WorkflowCharacteristics schema. Args: [workflow]. Returns
   {:id :version :name :description :phases :max-iterations :task-types
   :complexity (:simple|:medium|:complex) :has-review :has-testing}; throws
   an anomaly when the computed characteristics fail validation."
  registry/workflow-characteristics)

(def ensure-initialized!
  "Idempotently load workflows from resources into the registry. No args.
   Returns the count of workflows in the registry."
  registry/ensure-initialized!)

(def valid-recommendation?
  "Predicate: does a value satisfy the WorkflowRecommendation schema? Args:
   [value]. Returns boolean."
  registry/valid-recommendation?)

(def explain-recommendation
  "Humanize WorkflowRecommendation schema errors for a value. Args: [value].
   Returns the humanized malli explanation, or nil when the value is valid."
  registry/explain-recommendation)

;------------------------------------------------------------------------------ Layer 4
;; FSM compile + visualization (Phase 6 of the FSM RFC)

(def compile-execution-machine
  "Compile a workflow map into an execution machine. Re-exports the
   internal `fsm/compile-execution-machine` so external callers (CLI
   inspect, evidence-bundle export) can produce a machine without
   reaching into the brick's internals."
  fsm/compile-execution-machine)

(def validate-execution-machine
  "Validate a workflow map against the structural/guard/action
   invariants. Returns `{:valid? :errors :warnings :machine}`."
  fsm/validate-execution-machine)

(def machine->mermaid
  "Render a compiled execution machine as a Mermaid stateDiagram-v2
   block — Phase 6 visual-audit export."
  visualize/machine->mermaid)
