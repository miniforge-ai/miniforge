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
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.checkpoint-store-gc :as checkpoint-gc]
   [ai.miniforge.workflow.checkpoint-store-spec :as checkpoint-spec]
   [ai.miniforge.workflow.fsm :as fsm]
   [ai.miniforge.workflow.interface.checkpoints :as checkpoints]
   [ai.miniforge.workflow.interface.configurable :as configurable]
   [ai.miniforge.workflow.interface.pipeline :as pipeline]
   [ai.miniforge.workflow.interface.profiles :as profiles]
   [ai.miniforge.workflow.interface.protocols :as protocols]
   [ai.miniforge.workflow.interface.registry :as registry]
   [ai.miniforge.workflow.interface.runtime :as runtime]
   [ai.miniforge.workflow.interface.supervision :as supervision]
   [ai.miniforge.workflow.run-owner-lock :as run-owner-lock]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.visualize :as visualize]))

;------------------------------------------------------------------------------ Layer 0

;; Protocols and workflow topology
(def ^{:stratum 0} Workflow
  "Protocol governing workflow execution: start/get-state/advance/rollback/
   complete/fail. Main extensibility point for custom workflow
   implementations."
  protocols/Workflow)

(def ^{:stratum 0} PhaseExecutor
  "Protocol for executing an individual SDLC phase: execute-phase/
   can-execute?/get-phase-requirements. Extensibility point for custom
   phase executors."
  protocols/PhaseExecutor)

(def ^{:stratum 0} WorkflowObserver
  "Protocol for observing workflow events (phase start/complete/error,
   workflow complete, rollback). Extensibility point for operator/
   meta-loop integration."
  protocols/WorkflowObserver)

(def ^{:stratum 0} phases
  "Ordered vector of SDLC phase keywords:
   [:spec :plan :design :implement :verify :review :release :observe :done]."
  protocols/phases)

(def ^{:stratum 0} phase-transitions
  "Map of phase keyword -> set of allowed next-phase keywords. The empty
   set for :done marks the terminal phase."
  protocols/phase-transitions)

;; Workflow lifecycle and execution
(def ^{:stratum 0} create-workflow
  "Construct a new in-memory SimpleWorkflow instance (Workflow protocol).
   Args: [] or [config]. The optional config map is merged over the defaults
   {:max-iterations-per-phase 5, :max-rollbacks 3, :timeout-per-phase-ms 600000};
   omit it to use the defaults. Returns the workflow object used as `this` in
   the lifecycle fns."
  runtime/create-workflow)

(def ^{:stratum 0} add-observer
  "Register a WorkflowObserver on a workflow. Args: [workflow observer].
   Returns the workflow with the observer attached."
  runtime/add-observer)

(def ^{:stratum 0} remove-observer
  "Detach a previously-registered WorkflowObserver. Args: [workflow observer].
   Returns the workflow without that observer."
  runtime/remove-observer)

(def ^{:stratum 0} start
  "Start a new run from a spec. Args: [workflow spec context]; spec is
   {:title :description}, context is {:llm-backend :budget :tags}. Returns
   the workflow-id (UUID)."
  runtime/start)

(def ^{:stratum 0} get-state
  "Read current run state. Args: [workflow workflow-id]. Returns the state
   map {:workflow/phase :workflow/status :workflow/artifacts ...}, or nil
   when the id is unknown."
  runtime/get-state)

(def ^{:stratum 0} advance
  "Advance a run to its next phase from a phase result. Args:
   [workflow workflow-id phase-result] where phase-result is
   {:success? :artifacts :errors :metrics}. Returns the updated state map."
  runtime/advance)

(def ^{:stratum 0} rollback
  "Roll a run back to an earlier phase. Args:
   [workflow workflow-id target-phase reason]. Returns the updated state
   map; transitions to :failed status once max-rollbacks is exceeded."
  runtime/rollback)

(def ^{:stratum 0} complete
  "Mark a run complete (phase :done, status :completed). Args:
   [workflow workflow-id]. Returns the final state map."
  runtime/complete)

(def ^{:stratum 0} fail
  "Mark a run failed and append the error. Args: [workflow workflow-id error].
   Returns the final state map with status :failed."
  runtime/fail)

(def ^{:stratum 0} create-phase-executor
  "Build a PhaseExecutor for a phase keyword. Args: [phase llm-backend].
   Returns an executor for :plan/:implement/:verify/:release, or nil for
   phases with no dedicated executor."
  runtime/create-phase-executor)

(def ^{:stratum 0} execute-phase
  "Run the current phase via a PhaseExecutor. Args:
   [executor workflow-state context]. Returns
   {:success? :artifacts :errors :metrics}."
  runtime/execute-phase)

(def ^{:stratum 0} can-execute?
  "Test whether a PhaseExecutor handles a phase. Args: [executor phase].
   Returns boolean."
  runtime/can-execute?)

(def ^{:stratum 0} get-phase-requirements
  "Required/optional inputs for a phase from a PhaseExecutor. Args:
   [executor phase]. Returns
   {:required-artifacts [...] :optional-artifacts [...]}."
  runtime/get-phase-requirements)

(def ^{:stratum 0} run-workflow
  "Drive a run end-to-end from spec to a terminal state. Args:
   [workflow spec context]. Loops execute-phase/advance until the run is
   :completed or :failed. Returns the final state map."
  runtime/run-workflow)

;; Supervision machine
(def ^{:stratum 0} supervision-states
  "Set of all per-run supervision state keywords (keys of the machine config)."
  supervision/supervision-states)

(def ^{:stratum 0} supervision-terminal-states
  "Set of supervision state keywords whose config type is :final."
  supervision/terminal-states)

(def ^{:stratum 0} supervision-transitions
  "Map of [from-state event] -> to-state for the supervision machine."
  supervision/supervision-transitions)

(def ^{:stratum 0} valid-supervision-state?
  "Predicate: is the keyword a known supervision state? Args: [state].
   Returns boolean."
  supervision/valid-state?)

(def ^{:stratum 0} supervision-terminal-state?
  "Predicate: is the supervision state terminal? Args: [state]. Returns
   boolean."
  supervision/terminal-state?)

(def ^{:stratum 0} valid-supervision-transition?
  "Predicate: is [current-state event] a defined transition? Args:
   [current-state event]. Returns boolean."
  supervision/valid-transition?)

(def ^{:stratum 0} next-supervision-state
  "Resolve the target state for a transition. Args: [current-state event].
   Returns the to-state keyword, or nil when no such transition exists."
  supervision/next-state)

(def ^{:stratum 0} transition-supervision
  "Attempt a pure supervision transition (optionally guarded). Args:
   [current-state event] or [current-state event guard-fn]. Returns
   {:success? true :state new-state} on success, or
   {:success? false :error keyword :message string} on rejection."
  supervision/transition)

(def ^{:stratum 0} get-supervision-events
  "List events available from a state. Args: [current-state]. Returns a
   vector of event keywords."
  supervision/get-available-events)

(def ^{:stratum 0} supervision-machine-graph
  "Graph-style view of the supervision machine. No args. Returns
   {:states set :transitions [{:from :event :to} ...] :terminal-states set}."
  supervision/machine-graph)

(def ^{:stratum 0} initialize-supervision
  "Initialize a compiled-FSM supervision state. No args. Returns the
   shared-FSM state map at the machine's initial state."
  supervision/initialize)

(def ^{:stratum 0} transition-supervision-fsm
  "Transition the compiled-FSM supervision state. Args: [state event].
   Returns the new shared-FSM state map."
  supervision/transition-fsm)

(def ^{:stratum 0} current-supervision-state
  "Read the current state keyword from a compiled-FSM state map. Args:
   [state]. Returns the state keyword."
  supervision/current-state)

(def ^{:stratum 0} final-supervision-state?
  "Predicate: is the compiled-FSM state map in a final state? Args:
   [state]. Returns boolean."
  supervision/is-final?)

;; Pipeline, configurable workflows, persistence, and registry helpers
(def ^{:stratum 0} run-pipeline
  "Execute a workflow's interceptor pipeline end-to-end. Args:
   [workflow input] or [workflow input opts]. Acquires the execution
   environment, runs phases, validates, publishes, and always cleans up.
   Returns the final execution context map; throws a slingshot anomaly on
   governed-mode misconfiguration.

   Options an embedder supplies (see docs/architecture/workflow-component.md):
   - :event-stream - required when any phase has gates. Each gate decision
     is published as a :gate/decision event, and only an acknowledged
     publication lets the phase advance. With no stream the decision is
     unacknowledged and the gated phase fails closed. A process that keeps
     its stream for days creates it with `:retention` (event-stream
     `bounded-retention`) so the in-memory history stays bounded; sinks and
     subscribers still see every event.
   - :logger - the run's structured logger, kept as :execution/logger; the
     default writes human-readable lines.
   - :policy-packs - the packs the pack gates evaluate. Without it,
     :policy-verify and :policy-review use the shipped standards pack, and
     :policy-pack blocks.
   - :checkpoint/root - where checkpoints are written; the default is the
     configured root under the user's home."
  pipeline/run-pipeline)

(def ^{:stratum 0} build-pipeline
  "Build the interceptor pipeline from a workflow config. Args: [workflow].
   Returns a vector of phase interceptors."
  pipeline/build-pipeline)

(def ^{:stratum 0} validate-pipeline
  "Validate a workflow's pipeline (phase + execution-machine checks). Args:
   [workflow]. Returns {:valid? boolean :errors [...] :warnings [...]}."
  pipeline/validate-pipeline)

(def ^{:stratum 0} execute-plan-as-dag
  "Execute a normalized plan through the DAG orchestrator. Args:
   [plan context]. Returns an aggregate DAG execution summary with
   :success?, :tasks-completed, :tasks-failed, :tasks-unreached,
   :artifacts, and :metrics."
  pipeline/execute-plan-as-dag)

(def ^{:stratum 0} checkpoint-present?
  "True when anything is stored under a run's checkpoint directory,
   including phase checkpoints with no snapshot yet and files that do not
   load. Args: [workflow-run-id] or [workflow-run-id opts]. The check that
   a run id is free: `load-checkpoint-data` answers nil or an anomaly for
   those."
  checkpoints/checkpoint-present?)

(def ^{:stratum 0} load-checkpoint-data
  "Load durable checkpoint data for a run and validate it at the boundary.
   Args: [workflow-run-id] or [workflow-run-id opts]. Returns the validated
   checkpoint-data map, or nil when no checkpoint exists; throws ex-info
   when a found checkpoint fails schema validation. A phase checkpoint
   that does not read is left out and named, as an anomaly, under
   `:checkpoint/read-failures`; an unreadable snapshot or manifest
   returns an `:unavailable` anomaly naming every unreadable file."
  checkpoints/load-checkpoint-data)

(def ^{:stratum 0} run-checkpoint-root
  "The checkpoint root a run was checkpointed under: the default root when
   it holds the run, else the root its events announce, else nil. Args:
   [workflow-run-id events-dir]."
  checkpoint-gc/run-checkpoint-root)

(defn ^{:stratum 0} run-owner
  "The process running a run, from the owner lock run-pipeline holds for
   the run's lifetime: its record ({:owner/pid :owner/started-at ...}, {}
   when unreadable), nil when no process runs it, or an :unavailable
   anomaly on an I/O error. Args: [workflow-run-id] or [workflow-run-id
   checkpoint-root], nil root meaning the default."
  ([workflow-run-id] (run-owner workflow-run-id nil))
  ([workflow-run-id checkpoint-root]
   (run-owner-lock/owner (or checkpoint-root (checkpoint-paths/resolve-checkpoint-root))
                         workflow-run-id)))

(def ^{:stratum 0} pin-checkpoint!
  "Pin a run's checkpoint: `:pinned? true` in its manifest, which the
   checkpoint GC never collects and later checkpoints keep. Args:
   [workflow-run-id] or [workflow-run-id opts]. Returns the manifest path,
   or an anomaly (:not-found without a manifest, :unavailable on I/O)."
  checkpoints/pin-checkpoint!)

(def ^{:stratum 0} run-chain
  "Run a sequence of workflows, feeding each step's output into the next.
   Args: [chain-def chain-input opts]. Invalid definitions return an anomaly before execution.
   Publication failure returns an anomaly instead of a completed result.
   Shorthand input bindings remain optional. Wrap a predecessor path in
   {:binding/expression path :binding/required? true} to fail before dependent loading when unresolved.
   Binding reports are redacted; child execution receives original resolved values.
   Set :chain/retention-directory in opts to require durable admission before the executor starts.
   Set :chain/event-stream to a caller-owned current stream for typed durable chain events; retention must use a separate directory.
   The optional :event-stream remains a separate legacy child stream. The caller closes the current stream.
   Results contain status, step results, duration, fresh :chain/run-id, resolved definition
   identity/version, and :chain/definition-snapshot. Without the directory option, retention remains in-memory only."
  pipeline/run-chain)

(def ^{:stratum 0} redact-chain-binding
  "Redact a chain binding report using target/source names, not its generic value key."
  pipeline/redact-chain-binding)

(def ^{:stratum 0} load-chain
  "Load a chain definition from classpath resources. Args: [chain-id version].
   Returns the chain-def result map; throws a :not-found anomaly when no
   matching chain resource exists."
  pipeline/load-chain)

(def ^{:stratum 0} persist-chain-run!
  "Retain an admission record durably. Args: [controlled-directory record].
   Reject unsafe data and immutable identity conflicts; does not execute the chain."
  pipeline/persist-chain-run!)

(def ^{:stratum 0} load-chain-run
  "Restore original chain identity and composition. Args: [controlled-directory run-uuid].
   Missing or conflicting records return anomalies; this does not resume execution."
  pipeline/load-chain-run)

(def ^{:stratum 0} list-chains
  "List all chain definitions on the classpath. No args. Returns a vector of
   summary maps {:id :version :description :steps}."
  pipeline/list-chains)

(def ^{:stratum 0} load-workflow
  "Load a configurable workflow config (cache -> resource -> heuristic store),
   validating unless skipped. Args: [workflow-id version] or
   [workflow-id version opts]. Returns
   {:workflow :source (:resource|:store|:cache) :validation}; throws an
   anomaly on parse failure, validation failure, or not-found."
  configurable/load-workflow)

(def ^{:stratum 0} execute-workflow
  "Execute a loaded configurable workflow against an input. Args:
   [workflow input] or [workflow input opts]. Returns the final execution
   context map."
  configurable/execute-workflow)

(def ^{:stratum 0} get-active-workflow
  "Read the active workflow selection for a task type. Args: [task-type].
   Returns {:workflow-id :version}, or nil when none is set."
  configurable/get-active-workflow)

(def ^{:stratum 0} set-active-workflow
  "Persist the active workflow selection for a task type. Args:
   [task-type workflow-id version]. Returns true on success, false on I/O
   error."
  configurable/set-active-workflow)

(def ^{:stratum 0} append-event
  "Append a log event to a run's event log file. Args: [workflow-id event].
   Returns true on success, false on I/O error."
  configurable/append-event)

(def ^{:stratum 0} load-event-log
  "Load all events for a run from its event log. Args: [workflow-id].
   Returns a vector of event maps, empty when the log is absent."
  configurable/load-event-log)

(def ^{:stratum 0} replay-workflow
  "Reconstruct run state by replaying an event stream. Args:
   [events & {:keys [workflow-id until]}]. Returns
   {:state :events-applied :final-status}."
  configurable/replay-workflow)

(def ^{:stratum 0} verify-determinism
  "Replay events and compare to an expected state. Args:
   [events expected-state & {:keys [workflow-id until]}]. Returns
   {:deterministic? boolean :differences [...] :replayed-state :events-applied}."
  configurable/verify-determinism)

(def ^{:stratum 0} save-workflow
  "Persist a workflow config to the heuristic store. Args: [workflow] or
   [workflow opts]. Returns the saved workflow's UUID."
  configurable/save-workflow)

(def ^{:stratum 0} list-workflows
  "List workflow metadata discovered on the classpath. No args. Returns a
   vector of maps {:workflow/id :workflow/version :workflow/type
   :workflow/description ...}."
  configurable/list-workflows)

(def ^{:stratum 0} compare-workflows
  "Compare multiple execution states side by side. Args: [execution-states].
   Returns {:executions [summary ...] :comparison {:total-executions
   :completed-count :failed-count :avg-tokens :avg-cost :avg-duration}}."
  configurable/compare-workflows)

(def ^{:stratum 0} create-directory-publisher
  "Build a directory publisher descriptor for workflow outputs. Args:
   [output-dir] or [output-dir opts]. Returns a publisher map
   {:publisher/type :directory :publisher/output-dir :publisher/default-format}."
  configurable/create-directory-publisher)

(def ^{:stratum 0} publish-output!
  "Write a publication via a publisher. Args: [publisher publication] or
   [publisher publication logger]. Returns
   {:success? true :publication/path ...} on success, or
   {:success? false :error string} for an unknown publisher type."
  configurable/publish-output!)

(def ^{:stratum 0} create-event-trigger
  "Subscribe to an event stream and fire workflows on matching events. Args:
   [event-stream trigger-config opts]. Returns
   {:subscriber-id keyword :stop-fn (fn [])}."
  configurable/create-event-trigger)

(def ^{:stratum 0} create-merge-trigger
  "Compatibility alias for PR-merge triggered workflows; same contract as
   create-event-trigger. Args: [event-stream trigger-config opts]. Returns
   {:subscriber-id keyword :stop-fn (fn [])}."
  configurable/create-merge-trigger)

(def ^{:stratum 0} stop-trigger!
  "Stop a trigger: unsubscribe and cancel pending work. Args:
   [trigger-handle] (the map returned by create-event-trigger). Returns nil."
  configurable/stop-trigger!)

(def ^{:stratum 0} load-state-profile-provider
  "Build the state-profile provider by merging app-owned registry.edn
   resources on the classpath. No args. Returns a provider object, or nil
   when no profiles are contributed."
  profiles/load-state-profile-provider)

(def ^{:stratum 0} available-state-profile-ids
  "List the profile ids the active provider exposes. No args. Returns a
   vector of profile ids (empty when no provider is present)."
  profiles/available-state-profile-ids)

(def ^{:stratum 0} default-state-profile-id
  "Read the provider's default profile id. No args. Returns the id keyword,
   or nil when no provider/default is configured."
  profiles/default-state-profile-id)

(def ^{:stratum 0} resolve-state-profile
  "Resolve a profile to its concrete config. Args: [profile] (nil for the
   default, a keyword id, or an inline profile). Returns the resolved
   profile map, or nil when no provider is present."
  profiles/resolve-state-profile)

(def ^{:stratum 0} register-workflow!
  "Validate and register a workflow definition. Args: [workflow]. Returns
   the registered workflow; throws an anomaly when :workflow/id is missing
   or validation fails."
  registry/register-workflow!)

(def ^{:stratum 0} get-workflow
  "Fetch a registered workflow by id. Args: [workflow-id]. Returns the
   workflow definition map, or nil when absent."
  registry/get-workflow)

(def ^{:stratum 0} list-workflow-ids
  "List ids of all registered workflows. No args. Returns a seq of workflow
   id keywords."
  registry/list-workflow-ids)

(def ^{:stratum 0} workflow-exists?
  "Predicate: is a workflow id registered? Args: [workflow-id]. Returns
   boolean."
  registry/workflow-exists?)

(def ^{:stratum 0} workflow-characteristics
  "Derive selection characteristics from a workflow, validated against the
   WorkflowCharacteristics schema. Args: [workflow]. Returns
   {:id :version :name :description :phases :max-iterations :task-types
   :complexity (:simple|:medium|:complex) :has-review :has-testing}; throws
   an anomaly when the computed characteristics fail validation."
  registry/workflow-characteristics)

(def ^{:stratum 0} ensure-initialized!
  "Idempotently load workflows from resources into the registry. No args.
   Returns the count of workflows in the registry."
  registry/ensure-initialized!)

(def ^{:stratum 0} valid-recommendation?
  "Predicate: does a value satisfy the WorkflowRecommendation schema? Args:
   [value]. Returns boolean."
  registry/valid-recommendation?)

(def ^{:stratum 0} explain-recommendation
  "Humanize WorkflowRecommendation schema errors for a value. Args: [value].
   Returns the humanized malli explanation, or nil when the value is valid."
  registry/explain-recommendation)

;; FSM compile + visualization (Phase 6 of the FSM RFC)
(def ^{:stratum 0} compile-execution-machine
  "Compile a workflow map into an execution machine. Re-exports the
   internal `fsm/compile-execution-machine` so external callers (CLI
   inspect, evidence-bundle export) can produce a machine without
   reaching into the brick's internals."
  fsm/compile-execution-machine)

(def ^{:stratum 0} validate-execution-machine
  "Validate a workflow map against the structural/guard/action
   invariants. Returns `{:valid? :errors :warnings :machine}`."
  fsm/validate-execution-machine)

(def ^{:stratum 0} machine->mermaid
  "Render a compiled execution machine as a Mermaid stateDiagram-v2
   block — Phase 6 visual-audit export."
  visualize/machine->mermaid)

;; Checkpoint retention
(def ^{:stratum 0} checkpoint-usage
  "Read-only summary of the checkpoint root for `mf doctor`: {:checkpoint/root
   :runs :older :oldest-days :max-age-days}, ages from run directory mtimes.
   Args: [opts], as `gc-checkpoints!`. Deletes nothing."
  checkpoint-gc/checkpoint-usage)

(def ^{:stratum 0} gc-checkpoints!
  "Delete checkpoint directories unchanged for the retention period
   (default 30 days), keeping pinned runs, runs whose manifest does not
   read, and runs whose events are still live. Args: [opts] with optional
   :checkpoint/root, :max-age-days, :events-dir. Returns {:checkpoint/root
   :deleted [run-id] :kept {reason [run-id]} :failures [anomaly]}."
  checkpoint-gc/gc-checkpoints!)

;; Spec integrity on resume
(def ^{:stratum 0} spec-drift
  "How the spec file a run was started from has changed since its first
   checkpoint, or nil when it has not (or none was recorded). Args: the
   run's checkpoint manifest. Returns {:workflow/spec-path
   :spec/expected-hash :spec/actual-hash}; actual is nil when the file is
   gone."
  checkpoint-spec/spec-drift)

(def ^{:stratum 0} spec-content-hash
  "The digest `spec-file-hash` records, of spec text already read. Args:
   [content]."
  checkpoint-spec/spec-content-hash)

(def ^{:stratum 0} spec-file-hash
  "The SHA-256 digest a run's manifest records for its spec file, or nil
   when `path` is not a readable file. Args: [path]."
  checkpoint-spec/spec-file-hash)

(def ^{:stratum 0} read-checkpoint-manifest
  "A run's checkpoint manifest alone, readable even when its snapshot is
   not. Args: [workflow-run-id] or [workflow-run-id opts]. Returns the
   manifest, nil when absent, or an anomaly naming an unreadable file."
  checkpoint-store/read-checkpoint-manifest)

(def ^{:stratum 0} record-spec-path!
  "Point a run's manifest at where its spec file now lives, keeping the
   recorded hash. Args: [workflow-run-id spec-path] or [... opts]."
  checkpoint-store/record-spec-path!)
