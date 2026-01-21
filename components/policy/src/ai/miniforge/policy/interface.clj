;; Copyright 2025 miniforge.ai
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.policy.interface
  "Public API for the policy component.

   The policy component provides:
   - Gate evaluation for validation checkpoints
   - Budget tracking (tokens, cost, time)
   - Governance rule enforcement

   All external access to policy functionality goes through this interface."
  (:require
   [ai.miniforge.policy.core :as core]
   [ai.miniforge.policy.gates :as gates]))

;; ============================================================================
;; Layer 0 - Public API Delegation
;; ============================================================================

(defn evaluate-gates
  "Evaluate validation gates for an artifact at a given phase.

   Parameters:
   - artifact: The artifact to validate
   - phase: The workflow phase (e.g., :implement, :verify)
   - gate-configs: Vector of gate configurations

   Returns:
   {:passed? boolean
    :failures [{:gate keyword
                :reason string
                :details map}]}

   Example:
   (evaluate-gates code-artifact :implement
     [{:type :syntax :config {...}}
      {:type :lint :config {...}}])"
  [artifact phase gate-configs]
  (gates/evaluate artifact phase gate-configs))

(defn check-budget
  "Check if the current context is within budget constraints.

   Parameters:
   - context: The execution context (includes usage metrics)
   - budget-type: Type of budget to check (:tokens, :cost, :time)

   Returns:
   {:within-budget? boolean
    :used number
    :limit number
    :remaining number
    :percentage-used number}

   Example:
   (check-budget {:tokens-used 5000} :tokens)"
  [context budget-type]
  (core/check-budget context budget-type))

(defn enforce-policy
  "Enforce governance policies on an artifact.

   Parameters:
   - artifact: The artifact to check
   - policy-rules: Vector of policy rules to enforce

   Returns:
   {:compliant? boolean
    :violations [{:rule keyword
                  :severity keyword
                  :message string
                  :location map}]}

   Example:
   (enforce-policy code-artifact
     [{:type :max-file-size :limit 500}
      {:type :required-tests :coverage 0.8}])"
  [artifact policy-rules]
  (core/enforce-policy artifact policy-rules))

(defn create-gate
  "Create a gate configuration.

   Parameters:
   - gate-type: Type of gate (:syntax, :lint, :test, etc.)
   - config: Gate-specific configuration

   Returns:
   Gate configuration map

   Example:
   (create-gate :syntax {:language :clojure})"
  [gate-type config]
  (gates/create-gate gate-type config))

(defn create-budget
  "Create a budget constraint.

   Parameters:
   - budget-type: Type of budget (:tokens, :cost, :time)
   - limit: Maximum allowed value

   Returns:
   Budget configuration map

   Example:
   (create-budget :tokens 50000)"
  [budget-type limit]
  (core/create-budget budget-type limit))

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Example usage

  ;; Create and evaluate gates
  (def syntax-gate (create-gate :syntax {:language :clojure}))
  (def lint-gate (create-gate :lint {:config ".clj-kondo/config.edn"}))

  (evaluate-gates {:artifact/type :code
                   :artifact/content "(defn foo [] 42)"}
                  :implement
                  [syntax-gate lint-gate])

  ;; Check budgets
  (def token-budget (create-budget :tokens 50000))
  (check-budget {:tokens-used 5000
                 :budget token-budget}
                :tokens)

  ;; Enforce policies
  (enforce-policy {:artifact/type :code
                   :artifact/lines 600}
                  [{:type :max-file-size :limit 500}])

  :end)
