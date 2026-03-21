;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
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

(ns ai.miniforge.policy.core
  "Core policy enforcement logic.

   Layer 0: Pure functions for budget calculations and policy checks
   Layer 1: Coordinated policy enforcement

   Follows stratified design principles."
  (:require
   [clojure.string :as str]))

;; ============================================================================
;; Layer 0 - Pure Functions
;; ============================================================================

(defn calculate-budget-usage
  "Calculate budget usage percentage and remaining amount.

   Pure function - no side effects."
  [used limit]
  (when (and used limit)
    (let [percentage (if (zero? limit)
                       100.0
                       (* 100.0 (/ used limit)))
          remaining (- limit used)]
      {:used used
       :limit limit
       :remaining remaining
       :percentage-used percentage
       :within-budget? (<= used limit)})))

(defn evaluate-policy-rule
  "Evaluate a single policy rule against an artifact.

   Returns violation map if rule is violated, nil otherwise."
  [artifact rule]
  (let [{:keys [type]} rule]
    (case type
      :max-file-size
      (when-let [lines (:artifact/lines artifact)]
        (when (> lines (:limit rule))
          {:rule :max-file-size
           :severity :error
           :message (str "File exceeds maximum size: " lines " > " (:limit rule))
           :location {:lines lines :limit (:limit rule)}}))

      :required-tests
      (when (= (:artifact/type artifact) :code)
        (let [coverage (get-in artifact [:artifact/metadata :test-coverage] 0)]
          (when (< coverage (:coverage rule 0.8))
            {:rule :required-tests
             :severity :error
             :message (str "Test coverage below required: " coverage " < " (:coverage rule))
             :location {:coverage coverage :required (:coverage rule)}})))

      :no-todos
      (when-let [content (:artifact/content artifact)]
        (when (str/includes? (str/lower-case content) "todo")
          {:rule :no-todos
           :severity :warning
           :message "File contains TODO comments"
           :location {:pattern "TODO"}}))

      ;; Unknown rule type - ignore
      nil)))

;; ============================================================================
;; Layer 1 - Coordinated Operations
;; ============================================================================

(defn check-budget
  "Check budget constraints for a context.

   Returns budget status including usage and remaining amounts."
  [context budget-type]
  (let [used (get context (keyword (str (name budget-type) "-used")) 0)
        limit (get-in context [:budget budget-type] ##Inf)]
    (calculate-budget-usage used limit)))

(defn enforce-policy
  "Enforce all policy rules on an artifact.

   Returns compliance status and any violations found."
  [artifact policy-rules]
  (let [violations (keep #(evaluate-policy-rule artifact %) policy-rules)]
    {:compliant? (empty? violations)
     :violations violations}))

(defn create-budget
  "Create a budget configuration."
  [budget-type limit]
  {:budget/type budget-type
   :budget/limit limit
   :budget/created-at (java.time.Instant/now)})

;; ============================================================================
;; Rich Comment
;; ============================================================================

(comment
  ;; Test budget calculation
  (calculate-budget-usage 5000 50000)
  ;; => {:used 5000, :limit 50000, :remaining 45000, :percentage-used 10.0, :within-budget? true}

  (calculate-budget-usage 60000 50000)
  ;; => {:used 60000, :limit 50000, :remaining -10000, :percentage-used 120.0, :within-budget? false}

  ;; Test policy rules
  (evaluate-policy-rule {:artifact/type :code
                         :artifact/lines 600}
                        {:type :max-file-size :limit 500})

  (enforce-policy {:artifact/type :code
                   :artifact/lines 600
                   :artifact/content "(defn foo [] ;; TODO: implement\n  42)"}
                  [{:type :max-file-size :limit 500}
                   {:type :no-todos}])

  :end)
