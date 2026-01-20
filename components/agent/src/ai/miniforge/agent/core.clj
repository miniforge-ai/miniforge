(ns ai.miniforge.agent.core
  "Agent execution logic and factory.
   Layer 0: Role configurations and defaults
   Layer 1: Agent record implementation
   Layer 2: Agent factory
   Layer 3: Agent executor implementation
   Layer 4: Specialized agent support (create-base-agent, cycle-agent)

   Agents are pure functions: (context, task) -> (artifacts, decisions, signals)"
  (:require
   [ai.miniforge.agent.protocol :as protocol]
   [ai.miniforge.agent.memory :as memory]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]))

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

;------------------------------------------------------------------------------ Layer 1
;; Metrics tracking

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

;------------------------------------------------------------------------------ Layer 2
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
                        :shutdown-at (java.util.Date.)})))

;------------------------------------------------------------------------------ Layer 3
;; Agent factory

(defn create-agent
  "Create an agent by role with optional config overrides.

   Arguments:
   - role    - Agent role keyword (:planner, :implementer, etc.)
   - opts    - Optional config overrides

   Options:
   - :model       - LLM model to use
   - :temperature - Sampling temperature
   - :max-tokens  - Max tokens per response
   - :budget      - Token/cost budget {:tokens int :cost-usd double}
   - :memory-id   - Existing memory ID to use

   Example:
     (create-agent :implementer {:model \"claude-opus-4\" :max-tokens 16000})"
  ([role] (create-agent role {}))
  ([role opts]
   (let [base-config (get default-role-configs role
                          (get default-role-configs :implementer))
        config (merge base-config (select-keys opts [:model :temperature :max-tokens :budget]))
        capabilities (get role-capabilities role #{})
        memory-id (or (:memory-id opts) (random-uuid))]
    (->BaseAgent
     (random-uuid)
     role
     capabilities
     config
     memory-id
     {:status :created
      :created-at (java.util.Date.)}))))

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

;------------------------------------------------------------------------------ Layer 4
;; Agent executor

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
          exec-context (assoc context
                              :memory mem
                              :agent-id agent-id
                              :task-id task-id)

          ;; Initialize agent
          initialized-agent (protocol/init agent (:config agent))

          ;; Execute
          result (try
                   (protocol/invoke initialized-agent task exec-context)
                   (catch Exception e
                     {:success false
                      :error (.getMessage e)
                      :exception-type (type e)
                      :outputs []
                      :decisions [:execution-error]
                      :signals [:task-failed]
                      :metrics (make-metrics)}))

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
                            (assoc :total-duration-ms duration))]

      ;; Save memory if store available
      (when memory-store
        (memory/save-memory memory-store (:memory exec-context)))

      ;; Return result
      (assoc final-result
             :metrics final-metrics
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

;------------------------------------------------------------------------------ Layer 5
;; Mock LLM Backend for testing

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

;------------------------------------------------------------------------------ Layer 6
;; Specialized Agent Support
;; These functions support the specialized agents (planner, implementer, tester)
;; that use a functional approach rather than the protocol-based BaseAgent.

(defprotocol SpecializedAgent
  "Protocol for specialized AI agents with functional invoke/validate/repair.
   This allows specialized agents to define their behavior via functions."

  (invoke [this context input]
    "Execute the agent's primary function on the input.
     Returns {:status :success/:error, :output <result>, :metrics {...}}")

  (validate [this output]
    "Validate the agent's output against its schema.
     Returns {:valid? bool, :errors [...] or nil}")

  (repair [this output errors context]
    "Attempt to repair invalid output based on validation errors.
     Returns {:status :success/:error, :output <repaired-result>}"))

(defrecord FunctionalAgent [role config system-prompt invoke-fn validate-fn repair-fn logger]
  SpecializedAgent
  (invoke [_this context input]
    (let [start-time (System/currentTimeMillis)]
      (try
        (log/debug logger :agent :agent/invoke-started
                   {:data {:role role :input-type (type input)}})
        (let [result (invoke-fn context input)]
          (log/info logger :agent :agent/invoke-completed
                    {:data {:role role
                            :duration-ms (- (System/currentTimeMillis) start-time)
                            :status (:status result)}})
          result)
        (catch Exception e
          (log/error logger :agent :agent/invoke-failed
                     {:message (.getMessage e)
                      :data {:role role
                             :duration-ms (- (System/currentTimeMillis) start-time)}})
          {:status :error
           :error (.getMessage e)
           :metrics {:duration-ms (- (System/currentTimeMillis) start-time)}}))))

  (validate [_this output]
    (validate-fn output))

  (repair [_this output errors context]
    (log/debug logger :agent :agent/repair-started
               {:data {:role role :error-count (count (if (map? errors) (vals errors) errors))}})
    (let [result (repair-fn output errors context)]
      (log/info logger :agent :agent/repair-completed
                {:data {:role role :status (:status result)}})
      result)))

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
  [{:keys [role system-prompt invoke-fn validate-fn repair-fn config logger]
    :or {config {}
         logger (log/create-logger {:min-level :info :output (fn [_])})}}]
  {:pre [(keyword? role)
         (string? system-prompt)
         (fn? invoke-fn)
         (fn? validate-fn)
         (fn? repair-fn)]}
  (->FunctionalAgent role config system-prompt invoke-fn validate-fn repair-fn logger))

(defn make-validator
  "Create a validation function from a Malli schema."
  [malli-schema]
  (fn [output]
    (if (schema/valid? malli-schema output)
      {:valid? true :errors nil}
      {:valid? false :errors (schema/explain malli-schema output)})))

(defn cycle-agent
  "Execute a full invoke-validate-repair cycle on a specialized agent.
   Returns the final result after up to max-iterations of repair attempts.

   Options:
   - :max-iterations - Maximum repair attempts (default 3)"
  [agent context input & {:keys [max-iterations] :or {max-iterations 3}}]
  (loop [iteration 0
         current-output nil]
    (if (zero? iteration)
      ;; First iteration: invoke the agent
      (let [result (invoke agent context input)]
        (if (= :error (:status result))
          result
          (let [validation (validate agent (:output result))]
            (if (:valid? validation)
              result
              (if (>= iteration max-iterations)
                {:status :error
                 :output (:output result)
                 :errors (:errors validation)
                 :message "Max repair iterations reached"}
                (recur (inc iteration) (:output result)))))))
      ;; Subsequent iterations: repair and validate
      (let [validation (validate agent current-output)]
        (if (:valid? validation)
          {:status :success :output current-output}
          (if (>= iteration max-iterations)
            {:status :error
             :output current-output
             :errors (:errors validation)
             :message "Max repair iterations reached"}
            (let [repair-result (repair agent current-output (:errors validation) context)]
              (if (= :error (:status repair-result))
                repair-result
                (recur (inc iteration) (:output repair-result))))))))))

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
