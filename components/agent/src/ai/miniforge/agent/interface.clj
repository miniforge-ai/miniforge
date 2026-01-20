(ns ai.miniforge.agent.interface
  "Public API for the agent component.
   Provides agent creation, execution, and memory management.

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.memory :as mem]
   [ai.miniforge.agent.protocol :as proto]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports (allow other components to reference protocols)

(def Agent proto/Agent)
(def AgentLifecycle proto/AgentLifecycle)
(def AgentExecutor proto/AgentExecutor)
(def LLMBackend proto/LLMBackend)
(def Memory mem/Memory)
(def MemoryStore mem/MemoryStore)

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
  (proto/execute executor agent task context))

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
  (proto/invoke agent task context))

(defn validate
  "Check if agent output meets requirements.

   Returns {:valid? bool :errors [...] :warnings [...]}"
  [agent output context]
  (proto/validate agent output context))

(defn repair
  "Attempt to fix validation failures in agent output.

   Returns {:repaired output :changes [...] :success bool}"
  [agent output errors context]
  (proto/repair agent output errors context))

(defn init
  "Initialize agent with configuration.

   Returns initialized agent instance."
  [agent config]
  (proto/init agent config))

(defn agent-status
  "Return current agent status.

   Returns {:state keyword :metrics {...} :last-activity inst}"
  [agent]
  (proto/status agent))

(defn shutdown
  "Clean up agent resources.

   Returns {:success bool}"
  [agent]
  (proto/shutdown agent))

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
  ([] (mem/create-memory))
  ([opts] (mem/create-memory opts)))

(defn add-to-memory
  "Add a message to memory.

   Arguments:
   - memory  - Memory instance
   - role    - Message role (:system, :user, :assistant)
   - content - Message content string

   Returns updated memory."
  [memory role content]
  (mem/add-message memory role content {}))

(defn add-system-message
  "Add a system message to memory. Convenience function."
  [memory content]
  (mem/add-system-message memory content))

(defn add-user-message
  "Add a user message to memory. Convenience function."
  [memory content]
  (mem/add-user-message memory content))

(defn add-assistant-message
  "Add an assistant message to memory. Convenience function."
  [memory content]
  (mem/add-assistant-message memory content))

(defn get-messages
  "Get all messages from memory in chronological order."
  [memory]
  (mem/get-messages memory))

(defn get-memory-window
  "Get messages that fit within token limit, prioritizing recent.

   Returns {:messages [...] :total-tokens int :trimmed-count int}"
  [memory token-limit]
  (mem/get-window memory token-limit))

(defn clear-memory
  "Clear all messages from memory. Returns empty memory."
  [memory]
  (mem/clear memory))

(defn memory-metadata
  "Get memory metadata (scope, created-at, etc.)."
  [memory]
  (mem/get-metadata memory))

;------------------------------------------------------------------------------ Layer 5
;; Memory store operations

(defn create-memory-store
  "Create an in-memory store for agent memories."
  []
  (mem/create-memory-store))

(defn get-memory
  "Retrieve a memory by ID from store."
  [store memory-id]
  (mem/get-memory store memory-id))

(defn save-memory
  "Save/update a memory in store. Returns the memory."
  [store memory]
  (mem/save-memory store memory))

(defn delete-memory
  "Delete a memory by ID from store."
  [store memory-id]
  (mem/delete-memory store memory-id))

(defn list-memories
  "List all memories for a given scope."
  [store scope scope-id]
  (mem/list-memories store scope scope-id))

;------------------------------------------------------------------------------ Layer 6
;; Utility functions

(defn estimate-tokens
  "Estimate token count for content.
   Uses rough heuristic: ~4 chars per token."
  [content]
  (mem/estimate-tokens content))

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
