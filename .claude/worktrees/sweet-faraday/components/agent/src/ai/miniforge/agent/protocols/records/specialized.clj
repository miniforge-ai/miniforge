(ns ai.miniforge.agent.protocols.records.specialized
  "Record that implements Agent protocol with functional callbacks.

   FunctionalAgent - Agent implementation that uses functions for invoke/validate/repair"
  (:require
   [ai.miniforge.agent.interface.protocols.agent :as p]
   [ai.miniforge.agent.protocols.impl.specialized :as impl]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]))

(defrecord FunctionalAgent [role config system-prompt invoke-fn validate-fn repair-fn logger]
  ;; Implement the main Agent protocol for compatibility with workflow/core.clj
  ;; The Agent protocol signature is: (invoke [this task context])
  ;; The invoke-fn expects: (invoke-fn context input)
  ;; So we adapt by swapping the order
  p/Agent
  (invoke [this task context]
    (impl/invoke-impl this task context))

  (validate [this output context]
    (impl/validate-impl this output context))

  (repair [this output errors context]
    (impl/repair-impl this output errors context)))

;------------------------------------------------------------------------------ Layer 1
;; Factory functions

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
  (impl/cycle-agent-impl agent context input max-iterations))
