(ns ai.miniforge.orchestrator.interface
  "Public API for the orchestrator component.
   Provides workflow execution, task routing, and knowledge coordination."
  (:require
   [ai.miniforge.orchestrator.core :as core]
   [ai.miniforge.orchestrator.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def Orchestrator proto/Orchestrator)
(def TaskRouter proto/TaskRouter)
(def BudgetManager proto/BudgetManager)
(def KnowledgeCoordinator proto/KnowledgeCoordinator)

;------------------------------------------------------------------------------ Layer 1
;; Orchestrator creation

(def create-control-plane
  "Create a control plane (orchestrator) with all components.

   The control plane is the central coordination layer:
   - Routes tasks to appropriate agents
   - Manages resource budgets
   - Coordinates knowledge injection/capture
   - Delegates workflow execution to workflow component

   Arguments:
   - llm-backend      - LLM backend for agent execution
   - knowledge-store  - Knowledge store for injection/learning
   - artifact-store   - Artifact store for results

   Options:
   - :config   - Override default configuration
   - :logger   - Logger instance
   - :operator - Operator instance for meta-loop integration

   Example:
     (create-control-plane llm-client knowledge-store artifact-store)
     (create-control-plane llm k-store a-store {:operator my-operator})"
  core/create-control-plane)

;; Backward compatibility alias
(def create-orchestrator
  "Alias for create-control-plane (backward compatibility)."
  core/create-orchestrator)

(def default-config
  "Default orchestrator configuration."
  core/default-config)

;------------------------------------------------------------------------------ Layer 2
;; Workflow execution

(defn execute-workflow
  "Execute a complete workflow from specification to results.

   Arguments:
   - orchestrator - Orchestrator instance
   - spec         - Workflow specification map with :title, :description
   - context      - Execution context (optional :budget, :tags)

   Returns:
     {:workflow-id uuid
      :status keyword
      :results {:artifacts [] :learnings []}
      :error (optional string)}

   Example:
     (execute-workflow orch
                       {:title \"Add auth feature\"
                        :description \"Implement OAuth2 authentication\"}
                       {:budget {:max-tokens 50000}})"
  [orchestrator spec context]
  (proto/execute-workflow orchestrator spec context))

(defn get-workflow-status
  "Get the current status of a workflow execution.

   Returns:
     {:workflow-id uuid
      :status keyword (:pending :running :completed :failed :cancelled)
      :phase keyword (:init :planning :implementing :testing :done)
      :task-count int
      :artifact-count int
      :errors []
      :budget {:within-budget? bool :remaining {...}}}"
  [orchestrator workflow-id]
  (proto/get-workflow-status orchestrator workflow-id))

(defn cancel-workflow
  "Cancel a running workflow."
  [orchestrator workflow-id]
  (proto/cancel-workflow orchestrator workflow-id))

(defn get-workflow-results
  "Get the results of a completed workflow.

   Returns:
     {:artifacts [{:type keyword :content any :workflow-id uuid}...]
      :learnings [zettel...]
      :metrics {:tokens int :cost-usd double :duration-ms int}}"
  [orchestrator workflow-id]
  (proto/get-workflow-results orchestrator workflow-id))

(defn pause-workflow
  "Pause a running workflow for human review."
  [orchestrator workflow-id]
  (proto/pause-workflow orchestrator workflow-id))

(defn resume-workflow
  "Resume a paused workflow."
  [orchestrator workflow-id]
  (proto/resume-workflow orchestrator workflow-id))

;------------------------------------------------------------------------------ Layer 3
;; Task routing

(def create-router
  "Create a task router.

   Example:
     (create-router)
     (create-router {:custom-routes {...}})"
  core/create-router)

(defn route-task
  "Determine which agent should handle a task.

   Returns:
     {:agent-role keyword
      :reason string}"
  [router task context]
  (proto/route-task router task context))

(defn can-handle?
  "Check if an agent role can handle a task type."
  [router task agent-role]
  (proto/can-handle? router task agent-role))

;------------------------------------------------------------------------------ Layer 4
;; Budget management

(def create-budget-manager
  "Create a budget manager for tracking resource usage."
  core/create-budget-manager)

(defn track-usage
  "Record resource usage for a workflow.

   usage: {:tokens int :cost-usd double :duration-ms int}"
  [budget-mgr workflow-id usage]
  (proto/track-usage budget-mgr workflow-id usage))

(defn check-budget
  "Check if workflow is within budget.

   Returns:
     {:within-budget? bool
      :remaining {:tokens int :cost-usd double :time-ms int}
      :used {:tokens int :cost-usd double :duration-ms int}
      :budget {:max-tokens int :max-cost-usd double :timeout-ms int}}"
  [budget-mgr workflow-id]
  (proto/check-budget budget-mgr workflow-id))

(defn set-budget
  "Set budget limits for a workflow."
  [budget-mgr workflow-id budget]
  (proto/set-budget budget-mgr workflow-id budget))

;------------------------------------------------------------------------------ Layer 5
;; Knowledge coordination

(def create-knowledge-coordinator
  "Create a knowledge coordinator.

   Arguments:
   - knowledge-store - Knowledge store instance

   Options:
   - config - Override default configuration

   Example:
     (create-knowledge-coordinator k-store)
     (create-knowledge-coordinator k-store {:knowledge-injection? true})"
  core/create-knowledge-coordinator)

(defn inject-for-agent
  "Get knowledge to inject for an agent working on a task.

   Returns:
     {:formatted string  ; Markdown block for context
      :zettels [...]     ; Raw zettels
      :count int}        ; Number of zettels"
  [coordinator agent-role task context]
  (proto/inject-for-agent coordinator agent-role task context))

(defn capture-execution-learning
  "Capture learnings from an agent execution.

   execution-result should include:
   - :repaired? bool
   - :agent-role keyword
   - :task map
   - :repair-history []

   Returns created learning zettel if applicable."
  [coordinator execution-result]
  (proto/capture-execution-learning coordinator execution-result))

(defn should-promote-learning?
  "Check if a learning should be promoted to rule.

   Returns:
     {:promote? bool
      :confidence float
      :reason string}"
  [coordinator learning]
  (proto/should-promote-learning? coordinator learning))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm]
           '[ai.miniforge.knowledge.interface :as knowledge]
           '[ai.miniforge.artifact.interface :as artifact])

  ;; Create all stores
  (def llm-client (llm/create-client :echo))
  (def k-store (knowledge/create-store))
  (def a-store (artifact/create-store))

  ;; Add some knowledge
  (knowledge/put-zettel k-store
                        (knowledge/create-zettel "210-clojure" "Clojure Conventions"
                                                 "Follow Polylith structure..." :rule
                                                 :dewey "210" :tags [:clojure]))

  ;; Create orchestrator
  (def orch (create-orchestrator llm-client k-store a-store))

  ;; Execute a simple workflow
  (def result
    (execute-workflow orch
                      {:title "Create greeting function"
                       :description "A function that says hello"}
                      {:tags [:clojure]}))

  ;; Check status
  (get-workflow-status orch (:workflow-id result))

  ;; Get results
  (get-workflow-results orch (:workflow-id result))

  :leave-this-here)
