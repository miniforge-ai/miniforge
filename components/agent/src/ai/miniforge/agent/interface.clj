(ns ai.miniforge.agent.interface
  "Public API for the agent component.
   Provides agent creation, execution, and memory management.

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.interface.protocols.memory :as mem-proto]
   [ai.miniforge.agent.protocols.records.memory :as mem-records]
   [ai.miniforge.agent.protocols.records.specialized :as specialized-records]
   [ai.miniforge.agent.protocols.impl.memory :as mem-impl]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.reviewer :as reviewer]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports (allow other components to reference protocols)

(def Agent agent-proto/Agent)
(def AgentLifecycle agent-proto/AgentLifecycle)
(def AgentExecutor agent-proto/AgentExecutor)
(def LLMBackend agent-proto/LLMBackend)
(def Memory mem-proto/Memory)
(def MemoryStore mem-proto/MemoryStore)

;; Configuration re-exports
(def default-role-configs core/default-role-configs)
(def role-capabilities core/role-capabilities)
(def role-system-prompts core/role-system-prompts)

;------------------------------------------------------------------------------ Layer 1
;; Agent creation

(defn create-agent
  "Create an agent by role with optional config overrides.

   Arguments:
   - role    - Agent role keyword (:planner, :implementer, :tester, etc.)
   - opts    - Optional config overrides

   Options:
   - :model       - LLM model to use (default: claude-sonnet-4)
   - :temperature - Sampling temperature (0.0-1.0)
   - :max-tokens  - Max tokens per response
   - :budget      - Token/cost budget {:tokens int :cost-usd double}
   - :memory-id   - Existing memory ID to use

   Example:
     (create-agent :implementer)
     (create-agent :implementer {:model \"claude-opus-4\" :max-tokens 16000})"
  ([role] (core/create-agent role))
  ([role opts] (core/create-agent role opts)))

(defn create-agent-map
  "Create an agent and return as a map conforming to Agent schema.
   Useful for serialization and storage.

   Example:
     (create-agent-map :implementer {:max-tokens 8000})
     ;; => {:agent/id #uuid \"...\" :agent/role :implementer ...}"
  ([role] (core/create-agent-map role))
  ([role opts] (core/create-agent-map role opts)))

;------------------------------------------------------------------------------ Layer 2
;; Agent execution

(defn execute
  "Execute an agent on a task with given context.
   Handles full lifecycle: init -> invoke -> validate -> repair -> complete/fail

   Arguments:
   - executor - Agent executor instance
   - agent    - Agent instance or agent map
   - task     - Task map with :task/id, :task/type, :task/status, etc.
   - context  - Execution context with :llm-backend, etc.

   Returns:
     {:success bool
      :outputs [artifact...]
      :decisions [keyword...]
      :signals [keyword...]
      :metrics {:tokens-input int :tokens-output int :duration-ms int ...}
      :error (optional string)}

   Example:
     (execute executor agent task {:llm-backend llm})"
  [executor agent task context]
  (agent-proto/execute executor agent task context))

(defn create-executor
  "Create an agent executor.

   Options:
   - :logger       - Logger instance for structured logging
   - :memory-store - Memory store for persistence

   Example:
     (create-executor)
     (create-executor {:memory-store (create-memory-store)})"
  ([] (core/create-executor))
  ([opts] (core/create-executor opts)))

;------------------------------------------------------------------------------ Layer 3
;; Agent protocol operations

(defn invoke
  "Execute the agent's main logic on a task.
   Lower-level than execute - does not handle full lifecycle.

   Returns {:success bool :outputs [...] :decisions [...] :signals [...] :metrics {...}}"
  [agent task context]
  (agent-proto/invoke agent task context))

(defn validate
  "Check if agent output meets requirements.

   Returns {:valid? bool :errors [...] :warnings [...]}"
  [agent output context]
  (agent-proto/validate agent output context))

(defn repair
  "Attempt to fix validation failures in agent output.

   Returns {:repaired output :changes [...] :success bool}"
  [agent output errors context]
  (agent-proto/repair agent output errors context))

(defn init
  "Initialize agent with configuration.

   Returns initialized agent instance."
  [agent config]
  (agent-proto/init agent config))

(defn agent-status
  "Return current agent status.

   Returns {:state keyword :metrics {...} :last-activity inst}"
  [agent]
  (agent-proto/status agent))

(defn shutdown
  "Clean up agent resources.

   Returns {:success bool}"
  [agent]
  (agent-proto/shutdown agent))

;------------------------------------------------------------------------------ Layer 4
;; Memory operations

(defn create-memory
  "Create a new memory instance.

   Options:
   - :scope     - Memory scope (:agent, :task, :workflow)
   - :scope-id  - ID of the scope (agent-id, task-id, or workflow-id)
   - :metadata  - Additional metadata map

   Example:
     (create-memory)
     (create-memory {:scope :task :scope-id task-id})"
  ([] (mem-records/create-memory))
  ([opts] (mem-records/create-memory opts)))

(defn add-to-memory
  "Add a message to memory.

   Arguments:
   - memory  - Memory instance
   - role    - Message role (:system, :user, :assistant)
   - content - Message content string

   Returns updated memory."
  [memory role content]
  (mem-proto/add-message memory role content {}))

(defn add-system-message
  "Add a system message to memory. Convenience function."
  [memory content]
  (mem-records/add-system-message memory content))

(defn add-user-message
  "Add a user message to memory. Convenience function."
  [memory content]
  (mem-records/add-user-message memory content))

(defn add-assistant-message
  "Add an assistant message to memory. Convenience function."
  [memory content]
  (mem-records/add-assistant-message memory content))

(defn get-messages
  "Get all messages from memory in chronological order."
  [memory]
  (mem-proto/get-messages memory))

(defn get-memory-window
  "Get messages that fit within token limit, prioritizing recent.

   Returns {:messages [...] :total-tokens int :trimmed-count int}"
  [memory token-limit]
  (mem-proto/get-window memory token-limit))

(defn clear-memory
  "Clear all messages from memory. Returns empty memory."
  [memory]
  (mem-proto/clear-messages memory))

(defn memory-metadata
  "Get memory metadata (scope, created-at, etc.)."
  [memory]
  (mem-proto/get-metadata memory))

;------------------------------------------------------------------------------ Layer 5
;; Memory store operations

(defn create-memory-store
  "Create an in-memory store for agent memories."
  []
  (mem-records/create-memory-store))

(defn get-memory
  "Retrieve a memory by ID from store."
  [store memory-id]
  (mem-proto/get-memory store memory-id))

(defn save-memory
  "Save/update a memory in store. Returns the memory."
  [store memory]
  (mem-proto/save-memory store memory))

(defn delete-memory
  "Delete a memory by ID from store."
  [store memory-id]
  (mem-proto/delete-memory store memory-id))

(defn list-memories
  "List all memories for a given scope."
  [store scope scope-id]
  (mem-proto/list-memories store scope scope-id))

;------------------------------------------------------------------------------ Layer 6
;; Utility functions

(defn estimate-tokens
  "Estimate token count for content.
   Uses rough heuristic: ~4 chars per token."
  [content]
  (mem-impl/estimate-tokens content))

(defn estimate-cost
  "Estimate cost in USD for tokens used.
   Uses approximate Claude pricing."
  [input-tokens output-tokens model]
  (core/estimate-cost input-tokens output-tokens model))

(defn create-mock-llm
  "Create a mock LLM backend for testing.

   Arguments:
   - responses - Single response map or sequence of responses

   Response format:
     {:content string
      :usage {:input-tokens int :output-tokens int}
      :model string}

   Example:
     (create-mock-llm {:content \"Hello\" :usage {:input-tokens 10 :output-tokens 5}})"
  ([] (core/create-mock-llm))
  ([responses] (core/create-mock-llm responses)))

;------------------------------------------------------------------------------ Layer 7
;; Specialized agent support

(defn create-base-agent
  "Create a base agent with the given configuration.
   Used by specialized agents (planner, implementer, tester).

   Required options:
   - :role          - Agent role keyword (:planner, :implementer, :tester, etc.)
   - :system-prompt - System prompt for the agent
   - :invoke-fn     - Function (fn [context input] -> result)
   - :validate-fn   - Function (fn [output] -> {:valid? bool, :errors [...]})
   - :repair-fn     - Function (fn [output errors context] -> repaired-result)

   Optional:
   - :config - Agent configuration map (model, temperature, etc.)
   - :logger - Logger instance (defaults to silent logger)"
  [opts]
  (specialized-records/create-base-agent opts))

(defn make-validator
  "Create a validation function from a Malli schema."
  [malli-schema]
  (specialized-records/make-validator malli-schema))

(defn cycle-agent
  "Execute a full invoke-validate-repair cycle on a specialized agent.
   Returns the final result after up to max-iterations of repair attempts.

   Options:
   - :max-iterations - Maximum repair attempts (default 3)"
  [agent context input & {:keys [max-iterations] :or {max-iterations 3}}]
  (specialized-records/cycle-agent agent context input :max-iterations max-iterations))

;------------------------------------------------------------------------------ Layer 8
;; Specialized agent creation

(def create-planner
  "Create a Planner agent with optional configuration overrides.
   The Planner analyzes specifications and creates detailed implementation plans."
  planner/create-planner)

(def create-implementer
  "Create an Implementer agent with optional configuration overrides.
   The Implementer generates code from plans and task descriptions."
  implementer/create-implementer)

(def create-tester
  "Create a Tester agent with optional configuration overrides.
   The Tester generates tests for code artifacts and validates coverage."
  tester/create-tester)

(def create-reviewer
  "Create a Reviewer agent with optional configuration overrides.
   The Reviewer runs static analysis gates without using an LLM."
  reviewer/create-reviewer)

;------------------------------------------------------------------------------ Layer 8
;; Specialized agent schemas

;; Planner schemas
(def Plan planner/Plan)
(def PlanTask planner/PlanTask)

;; Implementer schemas
(def CodeArtifact implementer/CodeArtifact)
(def CodeFile implementer/CodeFile)

;; Tester schemas
(def TestArtifact tester/TestArtifact)
(def TestFile tester/TestFile)
(def Coverage tester/Coverage)

;; Reviewer schemas
(def ReviewArtifact reviewer/ReviewArtifact)
(def GateFeedback reviewer/GateFeedback)

;------------------------------------------------------------------------------ Layer 9
;; Specialized agent utilities

;; Planner utilities
(def plan-summary
  "Get a summary of a plan for logging/display."
  planner/plan-summary)

(def task-dependency-order
  "Return tasks in dependency order (topological sort)."
  planner/task-dependency-order)

(def validate-plan
  "Validate a plan against the Plan schema and check for structural issues."
  planner/validate-plan)

;; Implementer utilities
(def code-summary
  "Get a summary of a code artifact for logging/display."
  implementer/code-summary)

(def files-by-action
  "Group files by their action type (:create, :modify, :delete)."
  implementer/files-by-action)

(def total-lines
  "Count total lines of code in the artifact."
  implementer/total-lines)

(def validate-code-artifact
  "Validate a code artifact against the schema and check for issues."
  implementer/validate-code-artifact)

;; Tester utilities
(def test-summary
  "Get a summary of a test artifact for logging/display."
  tester/test-summary)

(def coverage-meets-threshold?
  "Check if coverage meets the specified thresholds."
  tester/coverage-meets-threshold?)

(def tests-by-path
  "Get a map of test file paths to their content."
  tester/tests-by-path)

(def validate-test-artifact
  "Validate a test artifact against the schema and check for issues."
  tester/validate-test-artifact)

;; Reviewer utilities
(def review-summary
  "Get a summary of a review artifact for logging/display."
  reviewer/review-summary)

(def approved?
  "Check if a review artifact represents approval."
  reviewer/approved?)

(def rejected?
  "Check if a review artifact represents rejection."
  reviewer/rejected?)

(def conditionally-approved?
  "Check if a review artifact is conditionally approved."
  reviewer/conditionally-approved?)

(def get-blocking-issues
  "Extract blocking issues from review artifact."
  reviewer/get-blocking-issues)

(def get-review-warnings
  "Extract warnings from review artifact."
  reviewer/get-warnings)

(def get-recommendations
  "Extract recommendations from review artifact."
  reviewer/get-recommendations)

(def validate-review-artifact
  "Validate a review artifact against the schema."
  reviewer/validate-review-artifact)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create agents by role
  (def planner (create-agent :planner))
  (def implementer (create-agent :implementer {:model "claude-opus-4"}))

  ;; Create agent as map (for storage/validation)
  (create-agent-map :tester {:max-tokens 4000})
  ;; => {:agent/id #uuid "..." :agent/role :tester ...}

  ;; Memory operations
  (def mem (create-memory {:scope :task :scope-id (random-uuid)}))

  (def mem2
    (-> mem
        (add-system-message "You are a helpful assistant.")
        (add-user-message "Write a factorial function")
        (add-assistant-message "(defn factorial [n] ...)")))

  (get-messages mem2)
  (get-memory-window mem2 100)
  (memory-metadata mem2)

  ;; Execute agent on task
  (def executor (create-executor))
  (def mock-llm (create-mock-llm {:content "(defn hello [] \"world\")"
                                   :usage {:input-tokens 200 :output-tokens 100}
                                   :model "mock"}))

  (def task {:task/id (random-uuid)
             :task/type :implement
             :task/status :pending
             :task/inputs []})

  (execute executor implementer task {:llm-backend mock-llm})
  ;; => {:success true :outputs [...] :metrics {...}}

  ;; Cost estimation
  (estimate-cost 1000 500 "claude-sonnet-4")
  ;; => 0.0105 USD

  ;; Role configurations
  default-role-configs
  role-capabilities
  role-system-prompts

  :leave-this-here)
