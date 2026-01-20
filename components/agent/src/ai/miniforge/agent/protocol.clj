(ns ai.miniforge.agent.protocol
  "Agent protocol definitions.
   Layer 0: Core protocols for agent execution and lifecycle management.

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)")

;------------------------------------------------------------------------------ Layer 0
;; Core Agent Protocol

(defprotocol Agent
  "Protocol for agent behavior and lifecycle operations.
   Agents process tasks and produce artifacts, decisions, and signals."

  (invoke [this task context]
    "Execute the agent's main logic on a task.
     Returns {:success bool :outputs [...] :decisions [...] :signals [...] :metrics {...}}")

  (validate [this output context]
    "Check if output meets requirements.
     Returns {:valid? bool :errors [...] :warnings [...]}")

  (repair [this output errors context]
    "Attempt to fix validation failures.
     Returns {:repaired output :changes [...] :success bool}"))

(defprotocol AgentLifecycle
  "Protocol for agent lifecycle management."

  (init [this config]
    "Initialize agent with configuration.
     Returns initialized agent instance.")

  (status [this]
    "Return current agent status.
     Returns {:state keyword :metrics {...} :last-activity inst}")

  (shutdown [this]
    "Clean up agent resources.
     Returns {:success bool}"))

;------------------------------------------------------------------------------ Layer 1
;; Agent Executor Protocol

(defprotocol AgentExecutor
  "Protocol for executing agents on tasks."

  (execute [this agent task context]
    "Run agent on task with given context.
     Handles full lifecycle: init -> invoke -> validate -> (repair if needed) -> complete/fail
     Returns {:success bool :outputs [...] :metrics {...} :error (optional)}"))

;------------------------------------------------------------------------------ Layer 2
;; LLM Backend Protocol (for dependency injection)

(defprotocol LLMBackend
  "Protocol for LLM interaction.
   Allows mocking in tests and swapping implementations."

  (complete [this messages opts]
    "Send messages to LLM and get completion.
     Returns {:content string :usage {:input-tokens int :output-tokens int} :model string}"))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example agent implementation sketch
  (defrecord ImplementerAgent [config memory-id]
    Agent
    (invoke [_this _task _context]
      {:success true
       :outputs [{:artifact/type :code
                  :artifact/content "..."}]
       :decisions [:implemented-feature]
       :signals [:ready-for-review]
       :metrics {:tokens-used 1500}})

    (validate [_this _output _context]
      {:valid? true :errors [] :warnings []})

    (repair [_this output _errors _context]
      {:repaired output :changes [] :success true}))

  :leave-this-here)
