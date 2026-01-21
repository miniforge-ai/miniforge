(ns ai.miniforge.workflow.interface
  "Public API for the workflow component.
   Handles SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe"
  (:require
   [ai.miniforge.workflow.core :as core]
   [ai.miniforge.workflow.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Workflow proto/Workflow)
(def PhaseExecutor proto/PhaseExecutor)
(def WorkflowObserver proto/WorkflowObserver)

;; Phase definitions
(def phases proto/phases)
(def phase-transitions proto/phase-transitions)

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
  (proto/start workflow spec context))

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
  (proto/get-state workflow workflow-id))

(defn advance
  "Advance workflow based on phase execution result.

   phase-result:
     {:success? bool
      :artifacts []
      :errors []
      :metrics {}}

   Returns updated workflow state."
  [workflow workflow-id phase-result]
  (proto/advance workflow workflow-id phase-result))

(defn rollback
  "Rollback workflow to a previous phase.

   Arguments:
   - workflow - Workflow instance
   - workflow-id - Workflow identifier
   - target-phase - Phase to rollback to
   - reason - Reason for rollback

   Returns updated workflow state."
  [workflow workflow-id target-phase reason]
  (proto/rollback workflow workflow-id target-phase reason))

(defn complete
  "Mark workflow as complete.
   Returns final workflow state."
  [workflow workflow-id]
  (proto/complete workflow workflow-id))

(defn fail
  "Mark workflow as failed.
   Returns final workflow state."
  [workflow workflow-id error]
  (proto/fail workflow workflow-id error))

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
  (proto/execute-phase executor workflow-state context))

(defn can-execute?
  "Check if an executor can handle a phase."
  [executor phase]
  (proto/can-execute? executor phase))

(defn get-phase-requirements
  "Get required inputs for a phase."
  [executor phase]
  (proto/get-phase-requirements executor phase))

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
;; Configurable workflow API (Layer 2)

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
  ((requiring-resolve 'ai.miniforge.workflow.core/get-active-workflow-id)
   task-type))

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
  ((requiring-resolve 'ai.miniforge.workflow.core/set-active-workflow)
   task-type workflow-id version))

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
   (let [heuristic-type (keyword "workflow" (name (:workflow/id workflow)))
         version (:workflow/version workflow)
         heuristic (requiring-resolve 'ai.miniforge.heuristic.interface/save-heuristic)]
     (heuristic heuristic-type version {:data workflow} opts))))

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
  (let [summaries (mapv (fn [exec-state]
                          {:execution/id (:execution/id exec-state)
                           :execution/workflow-id (:execution/workflow-id exec-state)
                           :execution/status (:execution/status exec-state)
                           :execution/metrics (:execution/metrics exec-state)
                           :execution/duration-ms ((requiring-resolve 'ai.miniforge.workflow.state/get-duration-ms)
                                                   exec-state)
                           :artifact-count (count (:execution/artifacts exec-state))
                           :error-count (count (:execution/errors exec-state))})
                        execution-states)]
    {:executions summaries
     :comparison {:total-executions (count execution-states)
                  :completed-count (count (filter #(= :completed (:execution/status %))
                                                  execution-states))
                  :failed-count (count (filter #(= :failed (:execution/status %))
                                               execution-states))
                  :avg-tokens (if (seq summaries)
                                (/ (reduce + (map #(get-in % [:execution/metrics :tokens]) summaries))
                                   (count summaries))
                                0)
                  :avg-cost (if (seq summaries)
                              (/ (reduce + (map #(get-in % [:execution/metrics :cost-usd]) summaries))
                                 (count summaries))
                              0.0)
                  :avg-duration (if (seq summaries)
                                  (/ (reduce + (map :execution/duration-ms summaries))
                                     (count summaries))
                                  0)}}))

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
