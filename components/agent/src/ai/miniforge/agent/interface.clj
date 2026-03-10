(ns ai.miniforge.agent.interface
  "Public API for the agent component.
   Provides agent creation, execution, memory management, and inter-agent messaging.

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  (:require
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.agent.interface.protocols.agent :as agent-proto]
   [ai.miniforge.agent.interface.protocols.memory :as mem-proto]
   [ai.miniforge.agent.interface.protocols.messaging :as msg-proto]
   [ai.miniforge.agent.protocols.records.memory :as mem-records]
   [ai.miniforge.agent.protocols.records.specialized :as specialized-records]
   [ai.miniforge.agent.protocols.records.messaging :as msg-records]
   [ai.miniforge.agent.protocols.impl.memory :as mem-impl]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]
   [ai.miniforge.agent.planner :as planner]
   [ai.miniforge.agent.implementer :as implementer]
   [ai.miniforge.agent.tester :as tester]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.releaser :as releaser]
   [ai.miniforge.agent.meta-coordinator :as meta-coord]
   [ai.miniforge.agent.meta.progress-monitor :as progress-monitor]
   [ai.miniforge.agent.task-classifier :as classifier]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports (allow other components to reference protocols)

(def Agent agent-proto/Agent)
(def AgentLifecycle agent-proto/AgentLifecycle)
(def AgentExecutor agent-proto/AgentExecutor)
(def LLMBackend agent-proto/LLMBackend)
(def Memory mem-proto/Memory)
(def MemoryStore mem-proto/MemoryStore)
(def InterAgentMessaging msg-proto/InterAgentMessaging)
(def MessageRouter msg-proto/MessageRouter)
(def MessageValidator msg-proto/MessageValidator)

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
   - :model       - LLM model to use (default: configured agent model)
   - :temperature - Sampling temperature (0.0-1.0)
   - :max-tokens  - Max tokens per response
   - :budget      - Token/cost budget {:tokens int :cost-usd double}
   - :memory-id   - Existing memory ID to use

   Example:
     (create-agent :implementer)
     (create-agent :implementer {:model \"claude-opus-4-6\" :max-tokens 16000})"
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
;; Inter-Agent Messaging

(defn create-message-router
  "Create a message router for inter-agent communication.

   The router maintains message queues and handles delivery.

   Example:
     (create-message-router)"
  []
  (msg-records/create-message-router))

(defn create-agent-messaging
  "Create messaging capability for an agent.

   Required:
   - agent-id    - Agent role keyword (:planner, :implementer, etc.)
   - instance-id - Agent instance UUID
   - workflow-id - Workflow UUID
   - router      - MessageRouter instance

   Example:
     (create-agent-messaging :implementer instance-id workflow-id router)"
  [agent-id instance-id workflow-id router]
  (msg-records/create-agent-messaging agent-id instance-id workflow-id router))

(defn send-message
  "Send a message from one agent to another.

   Arguments:
   - agent-messaging - AgentMessaging instance
   - message-data    - Map with :type, :to-agent, :content

   Returns:
     {:message message-map :event event-map}

   Example:
     (send-message messaging
                   {:type :clarification-request
                    :to-agent :planner
                    :content \"Should we create new security group?\"})"
  [agent-messaging message-data]
  (msg-proto/send-message agent-messaging message-data))

(defn receive-messages
  "Get all messages received by an agent.

   Returns sequence of message maps."
  [agent-messaging]
  (msg-proto/receive-messages agent-messaging))

(defn respond-to-message
  "Send a response to a received message.

   Arguments:
   - agent-messaging  - AgentMessaging instance
   - original-message - The message being responded to
   - response-content - Response content string

   Example:
     (respond-to-message messaging original-msg \"Yes, create new sg.\")"
  [agent-messaging original-message response-content]
  (msg-proto/respond-to-message agent-messaging original-message response-content))

;; Convenience functions for specific message types

(defn send-clarification-request
  "Send a clarification request to another agent.

   Example:
     (send-clarification-request messaging :planner \"Clarify X?\")"
  [agent-messaging to-agent content]
  (msg-records/send-clarification-request agent-messaging to-agent content))

(defn send-concern
  "Send a concern to another agent.

   Example:
     (send-concern messaging :implementer \"Security concern: ...\")"
  [agent-messaging to-agent content]
  (msg-records/send-concern agent-messaging to-agent content))

(defn send-suggestion
  "Send a suggestion to another agent.

   Example:
     (send-suggestion messaging :tester \"Consider adding edge case test\")"
  [agent-messaging to-agent content]
  (msg-records/send-suggestion agent-messaging to-agent content))

(defn get-clarification-requests
  "Get all clarification requests received by agent."
  [agent-messaging]
  (msg-records/get-clarification-requests agent-messaging))

(defn get-concerns
  "Get all concerns received by agent."
  [agent-messaging]
  (msg-records/get-concerns agent-messaging))

(defn get-suggestions
  "Get all suggestions received by agent."
  [agent-messaging]
  (msg-records/get-suggestions agent-messaging))

;; Router operations

(defn route-message
  "Route a message to its recipient (internal use).

   Returns the routed message with delivery metadata."
  [router message]
  (msg-proto/route-message router message))

(defn get-messages-for-agent
  "Get all messages for a specific agent in a workflow.

   Example:
     (get-messages-for-agent router :implementer workflow-id)"
  [router agent-id workflow-id]
  (msg-proto/get-messages-for-agent router agent-id workflow-id))

(defn get-messages-by-workflow
  "Get all messages in a workflow, ordered by timestamp.

   Example:
     (get-messages-by-workflow router workflow-id)"
  [router workflow-id]
  (msg-proto/get-messages-by-workflow router workflow-id))

(defn clear-workflow-messages
  "Clear all messages for a workflow.

   Example:
     (clear-workflow-messages router workflow-id)"
  [router workflow-id]
  (msg-proto/clear-messages router workflow-id))

;; Message validation and schemas

(defn validate-message
  "Validate a message against the schema.

   Returns {:valid? boolean :errors [...]}"
  [message]
  (msg-impl/validate-message-impl message))

(def Message
  "Message schema for validation.
   See ai.miniforge.agent.protocols.impl.messaging/Message"
  msg-impl/Message)

(def MessageType
  "Valid message types: :clarification-request, :clarification-response, :concern, :suggestion"
  msg-impl/MessageType)

;------------------------------------------------------------------------------ Layer 7
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

;------------------------------------------------------------------------------ Layer 8
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

;------------------------------------------------------------------------------ Layer 9
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
   The Reviewer performs LLM-backed semantic code review plus deterministic
   gate validation. Falls back to gate-only review when no LLM backend
   is available."
  reviewer/create-reviewer)

(def create-releaser
  "Create a Releaser agent with optional configuration overrides.
   The Releaser generates branch names, commit messages, PR titles and descriptions."
  releaser/create-releaser)

;------------------------------------------------------------------------------ Layer 10
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
(def ReviewIssue reviewer/ReviewIssue)
(def GateFeedback reviewer/GateFeedback)

;; Releaser schemas
(def ReleaseArtifact releaser/ReleaseArtifact)

;------------------------------------------------------------------------------ Layer 11
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

(def changes-requested?
  "Check if a review artifact has changes requested."
  reviewer/changes-requested?)

(def get-review-issues
  "Extract LLM review issues from review artifact."
  reviewer/get-issues)

(def get-review-strengths
  "Extract strengths noted by the LLM from review artifact."
  reviewer/get-strengths)

(def validate-review-artifact
  "Validate a review artifact against the schema."
  reviewer/validate-review-artifact)

;; Releaser utilities
(def release-summary
  "Get a summary of a release artifact for logging/display."
  releaser/release-summary)

(def validate-release-artifact
  "Validate a release artifact against the schema."
  releaser/validate-release-artifact)

;------------------------------------------------------------------------------ Layer 12
;; Meta-agent operations

(def create-progress-monitor-agent
  "Create a Progress Monitor meta-agent for workflow health monitoring."
  progress-monitor/create-progress-monitor-agent)

(def create-meta-coordinator
  "Create a meta-agent coordinator for managing multiple meta-agents."
  meta-coord/create-coordinator)

(def check-all-meta-agents
  "Check health of all meta-agents in the coordinator."
  meta-coord/check-all-agents)

(def reset-all-meta-agents!
  "Reset state of all meta-agents in the coordinator."
  meta-coord/reset-all-agents!)

(def get-meta-check-history
  "Get health check history from the coordinator."
  meta-coord/get-check-history)

(def get-meta-agent-stats
  "Get statistics for all meta-agents."
  meta-coord/get-agent-stats)

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create agents by role
  (def planner (create-agent :planner))
  (def implementer (create-agent :implementer {:model "claude-opus-4-6"}))

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
  (estimate-cost 1000 500 "claude-sonnet-4-6")
  ;; => 0.0105 USD

  ;; Role configurations
  default-role-configs
  role-capabilities
  role-system-prompts

  ;; Inter-Agent Messaging
  ;; ---------------------

  ;; 1. Create message router and agent messaging capabilities
  (def router (create-message-router))
  (def workflow-id (random-uuid))
  (def planner-id (random-uuid))
  (def implementer-id (random-uuid))

  (def planner-messaging
    (create-agent-messaging :planner planner-id workflow-id router))

  (def implementer-messaging
    (create-agent-messaging :implementer implementer-id workflow-id router))

  ;; 2. Send clarification request from implementer to planner
  (def {:keys [message event]}
    (send-clarification-request
     implementer-messaging
     :planner
     "Should we create a new security group or reuse sg-prod-rds?"))

  ;; The event can be emitted to the event stream
  ;; event => {:event/type :agent/message-sent
  ;;          :from-agent/id :implementer
  ;;          :to-agent/id :planner
  ;;          :message-type :clarification-request
  ;;          ...}

  ;; 3. Planner receives messages
  (def planner-inbox (receive-messages planner-messaging))
  ;; => [{:message/id #uuid "..."
  ;;      :message/type :clarification-request
  ;;      :message/from-agent :implementer
  ;;      :message/to-agent :planner
  ;;      :message/content "Should we create..."
  ;;      ...}]

  ;; 4. Get specific types of messages
  (def clarifications (get-clarification-requests planner-messaging))

  ;; 5. Planner responds to the clarification request
  (def {:keys [message event]}
    (respond-to-message
     planner-messaging
     (first planner-inbox)
     "Reuse the existing security group sg-prod-rds to maintain consistency."))

  ;; 6. Implementer checks for responses
  (def implementer-inbox (receive-messages implementer-messaging))
  ;; Will contain the response from planner

  ;; 7. Send concern from reviewer to implementer
  (def reviewer-messaging
    (create-agent-messaging :reviewer (random-uuid) workflow-id router))

  (send-concern
   reviewer-messaging
   :implementer
   "Policy violation: Missing required tags on S3 bucket resource.")

  ;; 8. Send suggestion from tester to implementer
  (def tester-messaging
    (create-agent-messaging :tester (random-uuid) workflow-id router))

  (send-suggestion
   tester-messaging
   :implementer
   "Consider adding error handling for network timeout scenarios.")

  ;; 9. Query all messages in workflow
  (def all-workflow-messages (get-messages-by-workflow router workflow-id))
  ;; Returns all messages ordered by timestamp

  ;; 10. Validate message structure
  (def example-message {:type :clarification-request :to-agent :planner :content "..."})
  (validate-message example-message)
  ;; => {:valid? true :errors []}

  ;; 11. Filter messages by type
  (def concerns (get-concerns implementer-messaging))
  (def suggestions (get-suggestions implementer-messaging))

  ;; 12. Clean up workflow messages
  (clear-workflow-messages router workflow-id)

  :leave-this-here)

;------------------------------------------------------------------------------ Layer 9
;; Task Classification (for intelligent model selection)

(defn classify-task
  "Classify a task to determine optimal model type.

   Arguments:
   - task - Map with :phase, :agent-type, :description, :title, etc.

   Returns:
   - {:type :thinking-heavy|:execution-focused|:simple-validation|...
      :confidence 0.0-1.0
      :reason \"...\"
      :alternative-types [...]
      :all-reasons [...]}

   Example:
     (classify-task {:phase :plan
                     :agent-type :planner-agent
                     :description \"Design architecture\"})"
  [task]
  (classifier/classify-task task))

(defn get-task-characteristics
  "Extract task characteristics for debugging/transparency.

   Example:
     (get-task-characteristics task)"
  [task]
  (classifier/get-task-characteristics task))
