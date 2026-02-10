(ns ai.miniforge.agent.core
  "Agent execution logic and factory.
   Layer 0: Role configurations, defaults, and utility functions
   Layer 1: Agent record implementation and metrics
   Layer 2: Agent factory and executor

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  (:require
   [ai.miniforge.agent.protocol :as protocol]
   [ai.miniforge.agent.memory :as memory]
   [ai.miniforge.agent.task-classifier :as classifier]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.tool.interface :as tool]
   [ai.miniforge.logging.interface :as log]))

;; Module-level logger for agent operations
(def ^:private default-logger
  "Default logger for agent module-level operations."
  (log/create-logger {:min-level :info :output :edn}))

;------------------------------------------------------------------------------ Layer 0
;; Role configurations and defaults

(def default-role-configs
  "Default configurations for each agent role.
   Temperature ranges: 0.0 (deterministic) to 1.0 (creative)
   Tokens: appropriate context window for task type."
  {:planner     {:model "claude-sonnet-4"
                 :temperature 0.7
                 :max-tokens 16000
                 :budget {:tokens 100000 :cost-usd 5.0}}

   :architect   {:model "claude-sonnet-4"
                 :temperature 0.5
                 :max-tokens 16000
                 :budget {:tokens 80000 :cost-usd 4.0}}

   :implementer {:model "claude-sonnet-4"
                 :temperature 0.3
                 :max-tokens 8000
                 :budget {:tokens 50000 :cost-usd 2.5}}

   :tester      {:model "claude-sonnet-4"
                 :temperature 0.2
                 :max-tokens 8000
                 :budget {:tokens 40000 :cost-usd 2.0}}

   :reviewer    {:model "claude-sonnet-4"
                 :temperature 0.4
                 :max-tokens 12000
                 :budget {:tokens 60000 :cost-usd 3.0}}

   :sre         {:model "claude-sonnet-4"
                 :temperature 0.2
                 :max-tokens 8000
                 :budget {:tokens 40000 :cost-usd 2.0}}

   :security    {:model "claude-sonnet-4"
                 :temperature 0.1
                 :max-tokens 8000
                 :budget {:tokens 40000 :cost-usd 2.0}}

   :release     {:model "claude-sonnet-4"
                 :temperature 0.1
                 :max-tokens 4000
                 :budget {:tokens 20000 :cost-usd 1.0}}

   :historian   {:model "claude-sonnet-4"
                 :temperature 0.3
                 :max-tokens 8000
                 :budget {:tokens 30000 :cost-usd 1.5}}

   :operator    {:model "claude-sonnet-4"
                 :temperature 0.1
                 :max-tokens 4000
                 :budget {:tokens 20000 :cost-usd 1.0}}})

(def role-capabilities
  "Default capabilities for each agent role."
  {:planner     #{:plan :decompose :estimate}
   :architect   #{:design :adr :diagram}
   :implementer #{:code :refactor :debug}
   :tester      #{:test :coverage :property-test}
   :reviewer    #{:review :suggest :approve}
   :sre         #{:deploy :monitor :alert}
   :security    #{:audit :scan :remediate}
   :release     #{:tag :changelog :publish}
   :historian   #{:document :summarize :trace}
   :operator    #{:execute :schedule :report}})

(def role-system-prompts
  "System prompts for each agent role."
  {:planner
   "You are a Planning Agent for an autonomous software development team.
Your role is to analyze requirements and create detailed implementation plans.
Break down tasks into small, testable units. Estimate complexity and dependencies.
Output structured plans with clear success criteria."

   :architect
   "You are an Architecture Agent for an autonomous software development team.
Your role is to design system components and document architectural decisions.
Consider scalability, maintainability, and security. Create ADRs for significant decisions.
Output diagrams and specifications in standard formats."

   :implementer
   "You are an Implementation Agent for an autonomous software development team.
Your role is to write clean, idiomatic code following best practices.
Write testable code with clear interfaces. Follow the existing codebase conventions.
Output working code with appropriate documentation."

   :tester
   "You are a Testing Agent for an autonomous software development team.
Your role is to ensure code quality through comprehensive testing.
Write unit tests, integration tests, and property-based tests as appropriate.
Output test code and coverage reports."

   :reviewer
   "You are a Code Review Agent for an autonomous software development team.
Your role is to review code for correctness, style, and best practices.
Provide constructive feedback with specific suggestions for improvement.
Output review comments and approval/rejection decisions."

   :sre
   "You are an SRE Agent for an autonomous software development team.
Your role is to ensure system reliability, performance, and operability.
Configure monitoring, alerting, and deployment pipelines.
Output infrastructure configurations and runbooks."

   :security
   "You are a Security Agent for an autonomous software development team.
Your role is to identify and remediate security vulnerabilities.
Perform code audits, dependency scans, and threat modeling.
Output security findings and remediation recommendations."

   :release
   "You are a Release Agent for an autonomous software development team.
Your role is to manage version control and release processes.
Create changelogs, manage version bumps, and coordinate releases.
Output release notes and version tags."

   :historian
   "You are a Historian Agent for an autonomous software development team.
Your role is to document decisions, changes, and system evolution.
Maintain accurate records and create summaries for stakeholders.
Output documentation and historical analysis."

   :operator
   "You are an Operator Agent for an autonomous software development team.
Your role is to execute commands and coordinate system operations.
Schedule tasks, run scripts, and report on execution status.
Output execution logs and status reports."})

(defn make-metrics
  "Create initial metrics map for agent execution."
  []
  {:tokens-input 0
   :tokens-output 0
   :tokens-total 0
   :cost-usd 0.0
   :duration-ms 0
   :iterations 0
   :llm-calls 0})

(defn update-metrics
  "Update metrics with new values."
  [metrics {:keys [input-tokens output-tokens duration-ms cost]}]
  (-> metrics
      (update :tokens-input + (or input-tokens 0))
      (update :tokens-output + (or output-tokens 0))
      (update :tokens-total + (or input-tokens 0) (or output-tokens 0))
      (update :cost-usd + (or cost 0.0))
      (update :duration-ms + (or duration-ms 0))
      (update :llm-calls inc)))

(defn estimate-cost
  "Estimate cost in USD for tokens used.
   Uses approximate Claude pricing."
  [input-tokens output-tokens model]
  (let [;; Prices per 1M tokens (approximate)
        prices {"claude-sonnet-4" {:input 3.0 :output 15.0}
                "claude-opus-4" {:input 15.0 :output 75.0}
                "claude-haiku" {:input 0.25 :output 1.25}}
        {:keys [input output]} (get prices model {:input 3.0 :output 15.0})]
    (+ (* input-tokens (/ input 1000000))
       (* output-tokens (/ output 1000000)))))

(defn task-type->artifact-type
  "Map task types to their corresponding artifact types."
  [task-type]
  (case task-type
    :plan :plan
    :design :adr
    :implement :code
    :test :test
    :review :review
    :deploy :manifest
    :code))

;------------------------------------------------------------------------------ Layer 1
;; Agent implementation

(defrecord BaseAgent [id role capabilities config memory-id state]
  protocol/Agent
  (invoke [_this task context]
    ;; Default implementation - subclasses should override
    ;; This provides the structure for agent execution
    (let [{:keys [llm-backend]} context
          {:keys [max-tokens temperature]} config
          system-prompt (get role-system-prompts role)
          task-prompt (str "Task: " (:task/type task) "\n\n"
                           "Inputs: " (pr-str (:task/inputs task)) "\n\n"
                           "Execute this task and provide structured output.")]
      (if llm-backend
        ;; If we have an LLM backend, use it
        (let [start-time (System/currentTimeMillis)
              messages [{:role :system :content system-prompt}
                        {:role :user :content task-prompt}]
              result (protocol/complete llm-backend messages
                                        {:max-tokens max-tokens
                                         :temperature temperature})
              duration (- (System/currentTimeMillis) start-time)
              usage (:usage result)]
          {:success true
           :outputs [{:artifact/id (random-uuid)
                      :artifact/type (task-type->artifact-type (:task/type task))
                      :artifact/content (:content result)
                      :artifact/version "1.0.0"}]
           :decisions [(keyword (str (name role) "-completed"))]
           :signals [:task-completed]
           :metrics {:tokens-input (:input-tokens usage 0)
                     :tokens-output (:output-tokens usage 0)
                     :duration-ms duration
                     :llm-calls 1}})
        ;; No LLM backend - return placeholder for testing
        {:success true
         :outputs []
         :decisions [:no-llm-backend]
         :signals [:mock-execution]
         :metrics (make-metrics)})))

  (validate [_this output _context]
    ;; Default validation - check basic structure
    (let [errors (cond-> []
                   (not (map? output))
                   (conj "Output must be a map")

                   (and (map? output) (not (contains? output :success)))
                   (conj "Output must contain :success key")

                   (and (map? output) (not (vector? (:outputs output))))
                   (conj "Output must contain :outputs vector"))]
      {:valid? (empty? errors)
       :errors errors
       :warnings []}))

  (repair [_this output errors _context]
    ;; Default repair - wrap invalid output
    (if (empty? errors)
      {:repaired output :changes [] :success true}
      {:repaired {:success false
                  :outputs []
                  :decisions [:repair-failed]
                  :signals [:validation-error]
                  :metrics (make-metrics)
                  :original output
                  :errors errors}
       :changes [:wrapped-invalid-output]
       :success false}))

  protocol/AgentLifecycle
  (init [this new-config]
    (assoc this
           :config (merge config new-config)
           :state {:initialized-at (java.util.Date.)
                   :status :ready}))

  (status [_this]
    (merge state
           {:agent-id id
            :role role}))

  (shutdown [this]
    (assoc this :state {:status :shutdown
                        :shutdown-at (java.util.Date.)}))

  (abort [this reason]
    ;; Idempotent abort - if already aborted, return info about existing abort
    (if (= :aborted (:status state))
      ;; Already aborted, return info
      {:aborted true
       :reason (:abort-reason state)
       :aborted-at (:aborted-at state)
       :already-aborted true}
      ;; Not yet aborted, perform abort
      (let [now (java.util.Date.)]
        {:aborted true
         :reason reason
         :aborted-at now
         :agent (assoc this :state {:status :aborted
                                    :abort-reason reason
                                    :aborted-at now
                                    :previous-state state})}))))

;------------------------------------------------------------------------------ Layer 2
;; Agent factory and executor

(defn select-model-for-agent
  "Select optimal model for agent based on role and task.
   Returns {:model model-id :selection selection-info} or nil if disabled."
  [role opts]
  (when-not (false? (:model-selection-enabled opts true))
    (try
      (let [;; Build task description from role and options
            task {:phase (:phase opts)
                  :agent-type (keyword (str (name role) "-agent"))
                  :description (:description opts)
                  :title (:title opts)
                  :context-tokens (:context-tokens opts)
                  :privacy-required (:privacy-required opts)
                  :cost-limit (:cost-limit opts)}

            ;; Classify task
            classification (classifier/classify-task task)

            ;; Select model (unless explicitly overridden)
            selection (when-not (:model opts)
                        (llm/select-model
                         classification
                         {:strategy (:model-selection-strategy opts :automatic)}
                         {:context-size (:context-tokens opts)
                          :cost-limit (:cost-limit opts)
                          :require-local (:privacy-required opts)}))]

        (when selection
          (log/info default-logger :agent ::model-auto-selected
                    {:message "Model automatically selected for agent"
                     :data {:role role
                            :task-type (:task-type selection)
                            :model (:model selection)
                            :confidence (:confidence selection)}})
          {:model (:model-id selection)
           :selection selection}))
      (catch Exception e
        (log/warn default-logger :agent ::model-selection-failed
                  {:message "Model selection failed, using default"
                   :data {:role role
                          :error (ex-message e)}})
        nil))))

(defn create-agent
  "Create an agent by role with optional config overrides.

   Arguments:
   - role    - Agent role keyword (:planner, :implementer, etc.)
   - opts    - Optional config overrides

   Options:
   - :model       - LLM model to use (explicit override)
   - :temperature - Sampling temperature
   - :max-tokens  - Max tokens per response
   - :budget      - Token/cost budget {:tokens int :cost-usd double}
   - :memory-id   - Existing memory ID to use
   - :phase       - Workflow phase (for model selection)
   - :model-selection-enabled - Enable automatic model selection (default: true)
   - :model-selection-strategy - Selection strategy (:automatic, :cost-optimized, :speed)
   - :privacy-required - Require local model
   - :cost-limit   - Maximum cost per task

   Example:
     (create-agent :implementer {:model \"claude-opus-4\" :max-tokens 16000})
     (create-agent :planner {:phase :plan}) ; Auto-selects Opus for planning"
  ([role] (create-agent role {}))
  ([role opts]
   (let [;; Try intelligent model selection first
         model-selection (select-model-for-agent role opts)
         selected-model (or (:model model-selection)
                            (:model opts))

         base-config (get default-role-configs role
                          (get default-role-configs :implementer))
         config (merge base-config
                       (select-keys opts [:temperature :max-tokens :budget])
                       (when selected-model {:model selected-model}))
         capabilities (get role-capabilities role #{})
         memory-id (or (:memory-id opts) (random-uuid))]

     ;; Store selection info in agent state for debugging/transparency
     (->BaseAgent
      (random-uuid)
      role
      capabilities
      config
      memory-id
      (cond-> {:status :created
               :created-at (java.util.Date.)}
        model-selection (assoc :model-selection (:selection model-selection)))))))

(defn create-agent-map
  "Create an agent and return as a map conforming to Agent schema.
   Useful for serialization and storage.

   Example:
     (create-agent-map :implementer {:max-tokens 8000})"
  ([role] (create-agent-map role {}))
  ([role opts]
   (let [agent (create-agent role opts)]
     {:agent/id (:id agent)
      :agent/role (:role agent)
      :agent/capabilities (:capabilities agent)
      :agent/memory (:memory-id agent)
      :agent/config (:config agent)})))

(defrecord DefaultExecutor [logger memory-store]
  protocol/AgentExecutor
  (execute [_this agent task context]
    (let [start-time (System/currentTimeMillis)
          agent-id (:id agent)
          task-id (:task/id task)

          ;; Initialize memory for this execution
          mem (or (when-let [mid (:memory-id agent)]
                    (memory/get-memory memory-store mid))
                  (memory/create-memory {:scope :task :scope-id task-id}))

          ;; Add context to execution
          exec-context (-> context
                           (assoc :memory mem
                                  :agent-id agent-id
                                  :task-id task-id)
                           (tool/attach-invocation-tracking))

          ;; Initialize agent
          initialized-agent (protocol/init agent (:config agent))

          ;; Execute
          result (try
                   (protocol/invoke initialized-agent task exec-context)
                   (catch Exception e
                     (let [;; Try to classify the error if agent-runtime is available
                           error-classification (try
                                                 (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                                   (when classifier
                                                     (classifier e {:task-id task-id})))
                                                 (catch Exception _ nil))]
                       {:success false
                        :error (.getMessage e)
                        :exception-type (type e)
                        :anomaly (response/from-exception e)
                        :error-classification error-classification
                        :outputs []
                        :decisions [:execution-error]
                        :signals [:task-failed]
                        :metrics (make-metrics)})))

          ;; Validate output
          validation (protocol/validate agent result exec-context)

          ;; Repair if needed
          final-result (if (:valid? validation)
                         result
                         (let [repaired (protocol/repair agent result (:errors validation) exec-context)]
                           (if (:success repaired)
                             (:repaired repaired)
                             (assoc result :validation-errors (:errors validation)))))

          duration (- (System/currentTimeMillis) start-time)

          ;; Update metrics with total duration
          final-metrics (-> (or (:metrics final-result) (make-metrics))
                            (assoc :total-duration-ms duration))
          tool-invocations (tool/tool-invocations exec-context)]

      ;; Save memory if store available
      (when memory-store
        (memory/save-memory memory-store (:memory exec-context)))

      ;; Return result
      (assoc final-result
             :metrics final-metrics
             :tool/invocations tool-invocations
             :agent-id agent-id
             :task-id task-id
             :executed-at (java.util.Date.)))))

(defn create-executor
  "Create an agent executor.

   Options:
   - :logger       - Logger instance for structured logging
   - :memory-store - Memory store for persistence

   Example:
     (create-executor {:memory-store (memory/create-memory-store)})"
  ([] (create-executor {}))
  ([{:keys [logger memory-store]}]
   (->DefaultExecutor logger (or memory-store (memory/create-memory-store)))))

(defrecord MockLLMBackend [responses]
  protocol/LLMBackend
  (complete [_this _messages _opts]
    (let [current @responses
          response (if (sequential? current)
                     (first current)
                     current)]
      (when (sequential? current)
        (swap! responses rest))
      (or response
          {:content "Mock response"
           :usage {:input-tokens 100 :output-tokens 50}
           :model "mock"}))))

(defn create-mock-llm
  "Create a mock LLM backend for testing.

   Arguments:
   - responses - Single response map or sequence of responses

   Example:
     (create-mock-llm {:content \"Hello\" :usage {:input-tokens 10 :output-tokens 5}})"
  ([] (create-mock-llm nil))
  ([responses]
   (->MockLLMBackend (atom (or responses
                               {:content "Mock response"
                                :usage {:input-tokens 100 :output-tokens 50}
                                :model "mock"})))))

(defn invoke
  "Convenience wrapper for invoking specialized agents.
   Supports both protocol-based agents and map-based agents with :invoke-fn.

   For protocol-based agents:
     (invoke agent context input)

   For functional agents (e.g., from specialized/create-base-agent):
     (invoke agent context input)

   Example:
     (invoke planner-agent {} \"Build user login\")"
  [agent context input]
  (if (fn? (:invoke-fn agent))
    ;; Map-based agent with invoke-fn (e.g., reviewer)
    ((:invoke-fn agent) context input)
    ;; Protocol-based agent
    (protocol/invoke agent input context)))

(defn cycle-agent
  "Convenience wrapper for cycle-agent function from specialized namespace.
   Execute a full invoke-validate-repair cycle on a specialized agent.

   Options:
   - :max-iterations - Maximum repair attempts (default 3)

   Example:
     (cycle-agent planner-agent {} \"Build user login\" :max-iterations 5)"
  [agent context input & {:keys [max-iterations] :or {max-iterations 3}}]
  (let [cycle-fn (requiring-resolve 'ai.miniforge.agent.specialized/cycle-agent)]
    (cycle-fn agent context input :max-iterations max-iterations)))

(defn repair
  "Convenience wrapper for repairing agent output.
   Supports both protocol-based agents and map-based agents with :repair-fn.

   Example:
     (repair agent output errors context)"
  [agent output errors context]
  (if (fn? (:repair-fn agent))
    ;; Map-based agent with repair-fn (e.g., reviewer)
    ((:repair-fn agent) output errors context)
    ;; Protocol-based agent
    (protocol/repair agent output errors context)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create an agent
  (def agent (create-agent :implementer))
  agent
  ;; => #ai.miniforge.agent.core.BaseAgent{:id #uuid "...", :role :implementer, ...}

  ;; Create agent as map
  (create-agent-map :planner {:model "claude-opus-4"})
  ;; => {:agent/id #uuid "...", :agent/role :planner, ...}

  ;; Initialize agent
  (def initialized (protocol/init agent {:max-tokens 4000}))
  (protocol/status initialized)

  ;; Create executor
  (def executor (create-executor))

  ;; Create a task
  (def task {:task/id (random-uuid)
             :task/type :implement
             :task/status :pending
             :task/inputs []})

  ;; Execute with mock LLM
  (def mock-llm (create-mock-llm {:content "(defn hello [] \"world\")"
                                   :usage {:input-tokens 200 :output-tokens 100}
                                   :model "mock"}))

  (protocol/execute executor agent task {:llm-backend mock-llm})
  ;; => {:success true, :outputs [...], :metrics {...}}

  ;; Role configs
  (get default-role-configs :planner)
  (get role-capabilities :tester)
  (get role-system-prompts :reviewer)

  ;; Cost estimation
  (estimate-cost 1000 500 "claude-sonnet-4")
  ;; => 0.0105 USD

  :leave-this-here)
