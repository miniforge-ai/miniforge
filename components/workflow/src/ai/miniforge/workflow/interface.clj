(ns ai.miniforge.workflow.interface
  "Public API for the workflow component.
   Handles SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe"
  (:require
   [ai.miniforge.workflow.core :as core]
   [ai.miniforge.workflow.persistence :as persist]
   [ai.miniforge.workflow.replay :as replay]
   [ai.miniforge.workflow.registry :as registry]
   [ai.miniforge.workflow.schemas :as schemas]
   [ai.miniforge.workflow.interface.protocols.workflow :as workflow-proto]
   [ai.miniforge.workflow.interface.protocols.phase-executor :as executor-proto]
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as observer-proto]
   [ai.miniforge.workflow.protocols.impl.workflow :as workflow-impl]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Workflow workflow-proto/Workflow)
(def PhaseExecutor executor-proto/PhaseExecutor)
(def WorkflowObserver observer-proto/WorkflowObserver)

;; Phase definitions
(def phases workflow-impl/phases)
(def phase-transitions workflow-impl/phase-transitions)

;------------------------------------------------------------------------------ Layer 1
;; Workflow creation

(def create-workflow
  "Create a workflow manager.

   Options:
   - :max-iterations-per-phase - Max inner loop iterations per phase (default 5)
   - :max-rollbacks - Max rollbacks before failure (default 3)
   - :timeout-per-phase-ms - Timeout per phase in ms (default 10 minutes)

   Example:
     (create-workflow)
     (create-workflow {:max-rollbacks 5})"
  core/create-workflow)

(def add-observer
  "Add a workflow observer for meta-loop integration.
   Observers receive callbacks on phase transitions and completions."
  core/add-observer)

(def remove-observer
  "Remove a workflow observer."
  core/remove-observer)

;------------------------------------------------------------------------------ Layer 2
;; Workflow operations

(defn start
  "Start a new workflow from a specification.

   Arguments:
   - workflow - Workflow instance
   - spec - Map with :title and :description
   - context - Execution context

   Returns workflow-id."
  [workflow spec context]
  (workflow-proto/start workflow spec context))

(defn get-state
  "Get current workflow state.

   Returns:
     {:workflow/id uuid
      :workflow/phase keyword
      :workflow/status keyword (:pending :running :completed :failed)
      :workflow/artifacts []
      :workflow/errors []
      :workflow/metrics {:tokens :cost-usd :duration-ms}
      :workflow/history []}"
  [workflow workflow-id]
  (workflow-proto/get-state workflow workflow-id))

(defn advance
  "Advance workflow based on phase execution result.

   phase-result:
     {:success? bool
      :artifacts []
      :errors []
      :metrics {}}

   Returns updated workflow state."
  [workflow workflow-id phase-result]
  (workflow-proto/advance workflow workflow-id phase-result))

(defn rollback
  "Rollback workflow to a previous phase.

   Arguments:
   - workflow - Workflow instance
   - workflow-id - Workflow identifier
   - target-phase - Phase to rollback to
   - reason - Reason for rollback

   Returns updated workflow state."
  [workflow workflow-id target-phase reason]
  (workflow-proto/rollback workflow workflow-id target-phase reason))

(defn complete
  "Mark workflow as complete.
   Returns final workflow state."
  [workflow workflow-id]
  (workflow-proto/complete workflow workflow-id))

(defn fail
  "Mark workflow as failed.
   Returns final workflow state."
  [workflow workflow-id error]
  (workflow-proto/fail workflow workflow-id error))

;------------------------------------------------------------------------------ Layer 3
;; Phase execution

(def create-phase-executor
  "Create a phase executor for a specific phase.

   Arguments:
   - phase - Phase keyword (:plan :implement :verify etc.)
   - llm-backend - LLM backend for agent execution

   Returns PhaseExecutor or nil if phase not supported."
  core/create-phase-executor)

(defn execute-phase
  "Execute a phase using an executor.

   Returns:
     {:success? bool
      :artifacts []
      :errors []
      :metrics {}}"
  [executor workflow-state context]
  (executor-proto/execute-phase executor workflow-state context))

(defn can-execute?
  "Check if an executor can handle a phase."
  [executor phase]
  (executor-proto/can-execute? executor phase))

(defn get-phase-requirements
  "Get required inputs for a phase."
  [executor phase]
  (executor-proto/get-phase-requirements executor phase))

;------------------------------------------------------------------------------ Layer 4
;; Workflow runner

(def run-workflow
  "Run a complete workflow from spec to completion.

   Arguments:
   - workflow - Workflow instance
   - spec - Specification map {:title :description}
   - context - Execution context {:llm-backend :budget :tags}

   Returns final workflow state.

   Example:
     (def wf (create-workflow))
     (run-workflow wf
                   {:title \"Create greeting function\"
                    :description \"A function that says hello\"}
                   {:llm-backend llm-client})"
  core/run-workflow)

;------------------------------------------------------------------------------ Layer 5
;; Interceptor-based workflow API (Simplified)

(defn run-pipeline
  "Execute a workflow using interceptor pipeline.

   This is the new simplified API using phase and gate interceptors.
   Workflows are defined as vectors of phase configurations.

   Arguments:
   - workflow: Workflow configuration with :workflow/pipeline
   - input: Input data for the workflow
   - opts: Execution options
     - :max-phases - Max phases to execute (default 50)
     - :on-phase-start - Callback fn [ctx interceptor]
     - :on-phase-complete - Callback fn [ctx interceptor result]

   Returns execution context with:
   - :execution/status - :completed or :failed
   - :execution/phase-results - Results per phase
   - :execution/artifacts - All artifacts produced
   - :execution/metrics - Accumulated metrics

   Example:
     (def workflow
       {:workflow/id :simple
        :workflow/version \"2.0.0\"
        :workflow/pipeline
        [{:phase :plan}
         {:phase :implement}
         {:phase :done}]})
     (run-pipeline workflow {:task \"Build feature\"} {})"
  ([workflow input]
   (run-pipeline workflow input {}))
  ([workflow input opts]
   ((requiring-resolve 'ai.miniforge.workflow.runner/run-pipeline)
    workflow input opts)))

(defn build-pipeline
  "Build interceptor pipeline from workflow config.

   Arguments:
   - workflow: Workflow configuration with :workflow/pipeline

   Returns vector of interceptor maps.

   Example:
     (build-pipeline {:workflow/pipeline [{:phase :plan} {:phase :implement}]})"
  [workflow]
  ((requiring-resolve 'ai.miniforge.workflow.runner/build-pipeline) workflow))

(defn validate-pipeline
  "Validate a workflow pipeline configuration.

   Arguments:
   - workflow: Workflow configuration with :workflow/pipeline

   Returns {:valid? bool :errors []}"
  [workflow]
  ((requiring-resolve 'ai.miniforge.workflow.runner/validate-pipeline) workflow))

(defn run-chain
  "Execute a chain of workflows sequentially.
   See workflow.chain for details."
  [chain-def chain-input opts]
  ((requiring-resolve 'ai.miniforge.workflow.chain/run-chain)
   chain-def chain-input opts))

;------------------------------------------------------------------------------ Layer 6
;; Configurable workflow API (Legacy)

(defn load-workflow
  "Load a workflow configuration by ID and version.
   Loads from resources or heuristic store with caching and validation.

   Arguments:
   - workflow-id: Workflow identifier (keyword)
   - version: Version string or :latest
   - opts: Options map
     - :store - Optional heuristic store
     - :skip-cache? - Skip cache lookup
     - :skip-validation? - Skip validation

   Returns:
   {:workflow workflow-config
    :source :resource | :store | :cache
    :validation {:valid? boolean :errors []}}

   Example:
     (load-workflow :canonical-sdlc-v1 \"1.0.0\" {})
     (load-workflow :custom-workflow \"2.0.0\" {:store my-store})"
  ([workflow-id version]
   (load-workflow workflow-id version {}))
  ([workflow-id version opts]
   ((requiring-resolve 'ai.miniforge.workflow.loader/load-workflow)
    workflow-id version opts)))

(defn execute-workflow
  "Execute a configurable workflow.

   Arguments:
   - workflow: Workflow configuration (from load-workflow)
   - input: Input data for the workflow
   - opts: Execution options
     - :max-phases - Max phases to execute (default 50)
     - :on-phase-start - Callback fn [exec-state phase]
     - :on-phase-complete - Callback fn [exec-state phase result]

   Returns execution state with:
   - :execution/status - :completed or :failed
   - :execution/phase-results - Results per phase
   - :execution/artifacts - All artifacts produced
   - :execution/metrics - Accumulated metrics

   Example:
     (def workflow-result (load-workflow :canonical-sdlc-v1 \"1.0.0\" {}))
     (execute-workflow (:workflow workflow-result)
                       {:task \"Build feature\"}
                       {})"
  ([workflow input]
   (execute-workflow workflow input {}))
  ([workflow input opts]
   ((requiring-resolve 'ai.miniforge.workflow.configurable/run-configurable-workflow)
    workflow input opts)))

(defn get-active-workflow
  "Get the active workflow configuration for a task type.

   Arguments:
   - task-type: Task type keyword (e.g., :feature, :bugfix)

   Returns map {:workflow-id :version} or nil if not set.

   Example:
     (get-active-workflow :feature)
     ;; => {:workflow-id :canonical-sdlc-v1 :version \"1.0.0\"}"
  [task-type]
  (persist/get-active-workflow-id task-type))

(defn set-active-workflow
  "Set the active workflow for a task type.

   Arguments:
   - task-type: Task type keyword (e.g., :feature, :bugfix)
   - workflow-id: Workflow identifier
   - version: Workflow version

   Returns true on success, false on error.

   Example:
     (set-active-workflow :feature :canonical-sdlc-v1 \"1.0.0\")
     (set-active-workflow :bugfix :simple-test-v1 \"1.0.0\")"
  [task-type workflow-id version]
  (persist/set-active-workflow task-type workflow-id version))

;; Event log persistence

(defn append-event
  "Append an event to a workflow's event log.
   Events are stored in ~/.miniforge/workflows/events/<workflow-id>.edn
   
   Arguments:
   - workflow-id - Workflow UUID
   - event - Log event map
   
   Returns true on success."
  [workflow-id event]
  (persist/append-event workflow-id event))

(defn load-event-log
  "Load all events for a workflow.
   
   Arguments:
   - workflow-id - Workflow UUID
   
   Returns vector of events, or empty vector if no log exists."
  [workflow-id]
  (persist/load-event-log workflow-id))

;; Event stream replay

(defn replay-workflow
  "Reconstruct workflow state from event stream.
   
   Arguments:
   - events - All events for this workflow
   - opts   - Options map:
     :workflow-id - Filter to specific workflow (optional)
     :until       - Replay only up to this timestamp (optional)
   
   Returns:
   {:state workflow-state
    :events-applied int
    :final-status keyword}
   
   Example:
     (replay-workflow (load-event-log wf-id) :workflow-id wf-id)"
  [events & {:keys [workflow-id until] :as _opts}]
  (replay/replay-workflow events :workflow-id workflow-id :until until))

(defn verify-determinism
  "Verify that replaying events produces the expected state.
   
   Arguments:
   - events - Event stream
   - expected-state - Expected final state
   - & opts - Keyword options for replay
   
   Returns:
   {:deterministic? boolean
    :differences [...]
    :replayed-state map}
   
   Example:
     (verify-determinism events final-state :workflow-id wf-id)"
  [events expected-state & {:keys [workflow-id until] :as _opts}]
  (replay/verify-determinism events expected-state :workflow-id workflow-id :until until))

(defn save-workflow
  "Save a workflow configuration to the heuristic store.

   Arguments:
   - workflow: Workflow configuration map
   - opts: Optional map with :store

   Returns UUID of saved workflow.

   Example:
     (def my-workflow {...})
     (save-workflow my-workflow {})"
  ([workflow]
   (save-workflow workflow {}))
  ([workflow opts]
   ((requiring-resolve 'ai.miniforge.workflow.comparison/save-workflow)
    workflow opts)))

(defn list-workflows
  "List available workflow configurations.

   Returns vector of workflow metadata maps:
   [{:workflow/id keyword
     :workflow/version string
     :workflow/type keyword
     :workflow/description string}]

   Example:
     (list-workflows)
     ;; => [{:workflow/id :canonical-sdlc-v1
     ;;      :workflow/version \"1.0.0\"
     ;;      :workflow/type :feature
     ;;      :workflow/description \"...\"}]"
  []
  ((requiring-resolve 'ai.miniforge.workflow.loader/list-available-workflows)))

(defn compare-workflows
  "Compare execution results from multiple workflow runs.

   Arguments:
   - execution-states: Vector of execution states to compare

   Returns comparison map with:
   - :executions - Vector of execution summaries
   - :comparison - Side-by-side metrics comparison

   Example:
     (def exec1 (execute-workflow workflow1 input {}))
     (def exec2 (execute-workflow workflow2 input {}))
     (compare-workflows [exec1 exec2])"
  [execution-states]
  ((requiring-resolve 'ai.miniforge.workflow.comparison/compare-workflows)
   execution-states))

;------------------------------------------------------------------------------ Layer 6b
;; Event-driven triggers

(defn create-merge-trigger
  "Create a merge trigger that subscribes to an event stream.

   Arguments:
   - event-stream: Event stream to subscribe to
   - trigger-config: {:triggers [{:on :pr/merged :repo ... :run {...}}]}
   - opts: Options map, passed to workflow execution

   Returns {:subscriber-id keyword, :stop-fn (fn [])}

   Example:
     (def trigger (create-merge-trigger stream
                    {:triggers [{:on :pr/merged
                                 :repo \"my-org/my-repo\"
                                 :run {:workflow-id :deploy-v1
                                       :version \"1.0.0\"
                                       :input-from-event {:branch :pr/branch}}}]}
                    {}))
     ;; Later:
     (stop-trigger! trigger)"
  [event-stream trigger-config opts]
  ((requiring-resolve 'ai.miniforge.workflow.trigger/create-merge-trigger)
   event-stream trigger-config opts))

(defn stop-trigger!
  "Stop a merge trigger. Unsubscribes and cancels pending work."
  [trigger]
  ((requiring-resolve 'ai.miniforge.workflow.trigger/stop-trigger!) trigger))

;------------------------------------------------------------------------------ Layer 7
;; Workflow registry

(def register-workflow!
  "Register a workflow in the registry.
   See workflow.registry for details."
  registry/register-workflow!)

(def get-workflow
  "Get a workflow by ID from the registry.
   See workflow.registry for details."
  registry/get-workflow)

(def list-workflow-ids
  "List all registered workflow IDs.
   See workflow.registry for details."
  registry/list-workflow-ids)

(def workflow-exists?
  "Check if a workflow is registered.
   See workflow.registry for details."
  registry/workflow-exists?)

(def workflow-characteristics
  "Extract characteristics from workflow for selection.
   See workflow.registry for details."
  registry/workflow-characteristics)

(def ensure-initialized!
  "Ensure the registry is initialized (idempotent).
   See workflow.registry for details."
  registry/ensure-initialized!)

;------------------------------------------------------------------------------ Layer 8
;; Workflow schemas

(def valid-recommendation?
  "Check if a value is a valid workflow recommendation.
   See workflow.schemas for details."
  schemas/valid-recommendation?)

(def explain-recommendation
  "Get human-readable explanation of validation errors for recommendation.
   See workflow.schemas for details."
  schemas/explain-recommendation)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm])

  ;; Create workflow
  (def wf (create-workflow))

  ;; Mock LLM
  (def llm-client (llm/mock-client {:output "Mock response"}))

  ;; Start workflow
  (def wf-id (start wf {:title "Test" :description "Test workflow"} {}))

  ;; Get state
  (get-state wf wf-id)

  ;; Run full workflow
  (run-workflow wf
                {:title "Create greeting function"
                 :description "A function that says hello"}
                {:llm-backend llm-client})

  ;; Layer 2 API - Configurable workflows

  ;; List available workflows
  (list-workflows)

  ;; Load workflow
  (def workflow-result (load-workflow :canonical-sdlc-v1 "1.0.0" {}))
  (:source workflow-result)
  (:workflow workflow-result)

  ;; Execute workflow
  (def exec-state
    (execute-workflow (:workflow workflow-result)
                      {:task "Build feature"}
                      {}))

  (:execution/status exec-state)
  (:execution/metrics exec-state)

  ;; Active workflow management
  (set-active-workflow :feature :canonical-sdlc-v1 "1.0.0")
  (get-active-workflow :feature)

  ;; Compare workflows
  (def exec1 (execute-workflow (:workflow workflow-result) {:task "Test 1"} {}))
  (def exec2 (execute-workflow (:workflow workflow-result) {:task "Test 2"} {}))
  (compare-workflows [exec1 exec2])

  :end)
