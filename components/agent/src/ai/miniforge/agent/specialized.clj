(ns ai.miniforge.agent.specialized
  "Specialized agent support for functional-style agents.
   Layer 0: Protocol definitions
   Layer 1: FunctionalAgent implementation
   Layer 2: Orchestration functions (create-base-agent, cycle-agent)

   These functions support specialized agents (planner, implementer, tester)
   that use a functional approach rather than the protocol-based BaseAgent."
  (:require
   [ai.miniforge.agent.protocol :as protocol]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol definitions

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

;------------------------------------------------------------------------------ Layer 1
;; FunctionalAgent implementation

(defrecord FunctionalAgent [role config system-prompt invoke-fn validate-fn repair-fn logger]
  ;; Implement the main Agent protocol for compatibility with workflow/core.clj
  ;; The Agent protocol signature is: (invoke [this task context])
  ;; The invoke-fn expects: (invoke-fn context input)
  ;; So we adapt by swapping the order
  protocol/Agent
  (invoke [_this task context]
    (let [start-time (System/currentTimeMillis)]
      (try
        (log/debug logger :agent :agent/invoke-started
                   {:data {:role role :task-type (:task/type task)}})
        (let [result (invoke-fn context task)]
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

  (validate [_this output _context]
    (validate-fn output))

  (repair [_this output errors context]
    (log/debug logger :agent :agent/repair-started
               {:data {:role role :error-count (count (if (map? errors) (vals errors) errors))}})
    (let [result (repair-fn output errors context)]
      (log/info logger :agent :agent/repair-completed
                {:data {:role role :status (:status result)}})
      result)))

;------------------------------------------------------------------------------ Layer 2
;; Helper functions for cycle-agent

(defn- handle-first-iteration
  "Handle the first iteration: invoke the agent and validate output.
   Returns either the successful result or continues to repair loop."
  [agent context input max-iterations]
  (let [result (invoke agent context input)]
    (if (= :error (:status result))
      result
      (let [validation (validate agent (:output result))]
        (if (:valid? validation)
          result
          (if (>= 0 max-iterations)
            {:status :error
             :output (:output result)
             :errors (:errors validation)
             :message "Max repair iterations reached"}
            {:continue-repair true
             :iteration 1
             :output (:output result)}))))))

(defn- handle-repair-iteration
  "Handle a repair iteration: validate output and attempt repair if needed.
   Returns either success, error, or continuation signal."
  [agent current-output iteration max-iterations context]
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
            {:continue-repair true
             :iteration (inc iteration)
             :output (:output repair-result)}))))))

;; Orchestration functions

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
    (let [result (if (zero? iteration)
                   (handle-first-iteration agent context input max-iterations)
                   (handle-repair-iteration agent current-output iteration max-iterations context))]
      (if (:continue-repair result)
        (recur (:iteration result) (:output result))
        result))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a specialized agent
  (def test-agent
    (create-base-agent
     {:role :test-agent
      :system-prompt "Test agent system prompt"
      :invoke-fn (fn [_ctx input] {:status :success :output input})
      :validate-fn (fn [output] {:valid? (map? output) :errors nil})
      :repair-fn (fn [output _errors _ctx] {:status :success :output output})}))

  ;; Use the cycle-agent function
  (cycle-agent test-agent {} {:test "input"})
  ;; => {:status :success, :output {:test "input"}}

  :leave-this-here)
