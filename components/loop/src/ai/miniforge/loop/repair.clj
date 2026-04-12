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

(ns ai.miniforge.loop.repair
  "Repair strategy protocol and built-in implementations.

   Note: The RepairStrategy protocol has been moved to:
   - ai.miniforge.loop.interface.protocols.repair-strategy (RepairStrategy protocol)

   This namespace contains the built-in repair strategy implementations.
   Layer 0: Pure functions for repair results
   Layer 1: Built-in strategies (llm-fix, retry, escalate)
   Layer 2: Repair orchestration"
  (:require
   [ai.miniforge.loop.interface.protocols.repair-strategy :as p]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.loop.messages :as messages]))

;; Re-export protocol for backward compatibility
(def RepairStrategy p/RepairStrategy)
(def can-repair? p/can-repair?)
(def repair p/repair)

;------------------------------------------------------------------------------ Layer 0
;; Repair result predicates

(defn succeeded?
  "Check if a repair result indicates success."
  [result]
  (boolean (:success? result)))

;; Repair result constructors (pure functions)

(defn repair-success
  "Create a successful repair result."
  [strategy artifact & {:keys [tokens-used duration-ms message]}]
  (cond-> {:success? true
           :artifact artifact
           :strategy strategy}
    tokens-used (assoc :tokens-used tokens-used)
    duration-ms (assoc :duration-ms duration-ms)
    message (assoc :message message)))

(defn repair-failure
  "Create a failed repair result."
  [strategy errors & {:keys [tokens-used duration-ms message]}]
  (cond-> {:success? false
           :errors errors
           :strategy strategy}
    tokens-used (assoc :tokens-used tokens-used)
    duration-ms (assoc :duration-ms duration-ms)
    message (assoc :message message)))

;------------------------------------------------------------------------------ Layer 1
;; Built-in repair strategies

(defrecord LLMFixStrategy [config]
  p/RepairStrategy
  (can-repair? [_this errors _context]
    ;; LLM can attempt to fix most errors except hardware/infrastructure issues
    (let [unfixable-codes #{:out-of-memory :disk-full :network-error}]
      (not-any? #(unfixable-codes (:code %)) errors)))

  (repair [_this artifact errors context]
    (let [start (System/currentTimeMillis)
          logger (:logger context)
          repair-fn (:repair-fn context)  ; Agent-provided repair function
          max-tokens (:max-tokens config 4000)]
      (when logger
        (log/info logger :loop :inner/repair-attempted
                  {:message "Attempting LLM repair"
                   :data {:strategy :llm-fix
                          :error-count (count errors)
                          :error-codes (mapv :code errors)}}))
      (if repair-fn
        ;; Call the agent's repair function
        (try
          (let [result (repair-fn artifact errors
                                  (assoc context :max-tokens max-tokens))
                duration (- (System/currentTimeMillis) start)]
            (if (succeeded? result)
              (repair-success :llm-fix (:artifact result)
                              :tokens-used (:tokens-used result)
                              :duration-ms duration
                              :message "LLM successfully repaired artifact")
              (repair-failure :llm-fix (get result :errors errors)
                              :tokens-used (:tokens-used result)
                              :duration-ms duration
                              :message "LLM repair attempt failed")))
          (catch Exception e
            (repair-failure :llm-fix
                            [{:code :repair-exception
                              :message (.getMessage e)}]
                            :duration-ms (- (System/currentTimeMillis) start))))
        ;; No repair function provided
        (repair-failure :llm-fix
                        [{:code :no-repair-fn
                          :message "No repair function provided in context"}]
                        :duration-ms (- (System/currentTimeMillis) start))))))

(defn llm-fix-strategy
  "Create an LLM-based repair strategy.
   Options:
   - :max-tokens - Maximum tokens for repair attempt (default 4000)"
  ([] (llm-fix-strategy {}))
  ([config] (->LLMFixStrategy config)))


(defrecord RetryStrategy [config]
  p/RepairStrategy
  (can-repair? [_this errors _context]
    ;; Retry is suitable for transient errors
    (let [transient-codes #{:timeout :rate-limit :service-unavailable :network-error}]
      (every? #(transient-codes (:code %)) errors)))

  (repair [_this artifact _errors context]
    (let [start (System/currentTimeMillis)
          logger (:logger context)
          delay-ms (:delay-ms config 1000)]
      (when logger
        (log/info logger :loop :inner/repair-attempted
                  {:message "Attempting retry"
                   :data {:strategy :retry
                          :delay-ms delay-ms}}))
      ;; Simple retry: wait and return the same artifact for re-validation
      (Thread/sleep delay-ms)
      ;; Retry doesn't modify the artifact, just allows re-running
      (repair-success :retry artifact
                      :duration-ms (- (System/currentTimeMillis) start)
                      :message "Retry delay completed"))))

(defn retry-strategy
  "Create a simple retry strategy.
   Options:
   - :delay-ms - Delay before retry in milliseconds (default 1000)"
  ([] (retry-strategy {}))
  ([config] (->RetryStrategy config)))


(defrecord EscalateStrategy [_config]
  p/RepairStrategy
  (can-repair? [_this _errors _context]
    ;; Escalate can always be used as a last resort
    true)

  (repair [_this artifact errors context]
    (let [start (System/currentTimeMillis)
          logger (:logger context)]
      (when logger
        (log/warn logger :loop :inner/escalated
                  {:message "Escalating to outer loop"
                   :data {:error-count (count errors)
                          :error-codes (mapv :code errors)}}))
      ;; Escalation signals that the inner loop cannot handle this
      {:success? false
       :escalate? true
       :artifact artifact
       :errors errors
       :strategy :escalate
       :duration-ms (- (System/currentTimeMillis) start)
       :message "Escalating to outer loop for resolution"})))

(defn escalate-strategy
  "Create an escalation strategy.
   This strategy always signals escalation to the outer loop."
  ([] (escalate-strategy {}))
  ([config] (->EscalateStrategy config)))

;------------------------------------------------------------------------------ Layer 2
;; Repair orchestration

(defn select-strategy
  "Select the first applicable repair strategy for the given errors.
   Returns nil if no strategy can handle the errors."
  [strategies errors context]
  (first (filter #(can-repair? % errors context) strategies)))

(defn make-max-attempts-result
  "Create a result map for max attempts exceeded."
  [attempt results errors]
  {:success? false
   :attempts attempt
   :results results
   :errors errors
   :message (messages/t :repair/max-attempts-exceeded)})

(defn make-exhausted-strategies-result
  "Create a result map for exhausted strategies."
  [attempt results errors]
  {:success? false
   :attempts attempt
   :results results
   :errors errors
   :message (messages/t :repair/strategies-exhausted)})

(defn make-success-result
  "Create a result map for successful repair."
  [result attempt results]
  {:success? true
   :artifact (:artifact result)
   :attempts (inc attempt)
   :results (conj results result)
   :strategy (:strategy result)})

(defn make-escalation-result
  "Create a result map for escalation."
  [result attempt results errors]
  {:success? false
   :escalate? true
   :attempts (inc attempt)
   :results (conj results result)
   :errors errors
   :message (messages/t :repair/escalated)})

(defn try-repair-with-strategy
  "Try to repair using a single strategy.
   Returns result map or nil if strategy can't handle errors."
  [strategy artifact errors context]
  (when (can-repair? strategy errors context)
    (repair strategy artifact errors context)))

(defn process-repair-result
  "Process the result of a repair attempt.
   Returns a map with :action (:success, :escalate, or :continue) and :result."
  [result attempt results errors]
  (cond
    (succeeded? result)
    {:action :success
     :result (make-success-result result attempt results)}

    (:escalate? result)
    {:action :escalate
     :result (make-escalation-result result attempt results errors)}

    :else
    {:action :continue
     :result result}))

(defn attempt-repair
  "Attempt to repair an artifact using available strategies.
   Tries strategies in order until one succeeds or all fail.

   Options:
   - :max-attempts - Maximum repair attempts across all strategies (default 3)
   - :logger - Logger instance

   Returns repair result with :success?, :artifact/:errors, :attempts."
  [strategies artifact errors context & {:keys [max-attempts] :or {max-attempts 3}}]
  (let [logger (:logger context)]
    (when logger
      (log/debug logger :loop :inner/repair-attempted
                 {:message "Starting repair orchestration"
                  :data {:strategy-count (count strategies)
                         :max-attempts max-attempts}}))
    (loop [remaining-strategies strategies
           attempt 0
           results []]
      (cond
        ;; Max attempts reached
        (>= attempt max-attempts)
        (make-max-attempts-result attempt results errors)

        ;; No more strategies
        (empty? remaining-strategies)
        (make-exhausted-strategies-result attempt results errors)

        :else
        (let [strategy (first remaining-strategies)
              repair-result (try-repair-with-strategy strategy artifact errors context)]
          (if repair-result
            ;; Strategy attempted repair
            (let [processed (process-repair-result repair-result attempt results errors)]
              (case (:action processed)
                :success (:result processed)
                :escalate (:result processed)
                :continue (recur (rest remaining-strategies)
                                (inc attempt)
                                (conj results repair-result))))
            ;; Strategy can't handle these errors, skip to next
            (recur (rest remaining-strategies)
                   attempt
                   results)))))))

(defn default-strategies
  "Create a default ordered list of repair strategies.
   Order: LLM fix -> Retry -> Escalate"
  []
  [(llm-fix-strategy)
   (retry-strategy)
   (escalate-strategy)])

(defn create-repair-attempt
  "Create a repair attempt record for loop history."
  [strategy iteration errors result]
  {:repair/id (random-uuid)
   :repair/strategy strategy
   :repair/iteration iteration
   :repair/errors errors
   :repair/success? (:success? result false)
   :repair/duration-ms (:duration-ms result)
   :repair/tokens-used (:tokens-used result)})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create strategies
  (def llm-fix (llm-fix-strategy {:max-tokens 2000}))
  (def retry (retry-strategy {:delay-ms 500}))
  (def escalate (escalate-strategy))

  ;; Test can-repair?
  (can-repair? llm-fix [{:code :syntax-error :message "Invalid syntax"}] {})
  ;; => true

  (can-repair? retry [{:code :timeout :message "Request timed out"}] {})
  ;; => true

  (can-repair? retry [{:code :syntax-error :message "Invalid syntax"}] {})
  ;; => false (syntax errors aren't transient)

  ;; Test repair with mock repair function
  (def mock-context
    {:logger nil
     :repair-fn (fn [artifact _errors _ctx]
                  {:success? true
                   :artifact (assoc artifact :artifact/content "(defn fixed [] :ok)")
                   :tokens-used 500})})

  (repair llm-fix
          {:artifact/id (random-uuid)
           :artifact/type :code
           :artifact/content "(defn broken ["}
          [{:code :syntax-error :message "Unmatched delimiter"}]
          mock-context)
  ;; => {:success? true, :artifact {...}, :strategy :llm-fix, ...}

  ;; Test orchestration
  (attempt-repair (default-strategies)
                  {:artifact/id (random-uuid)
                   :artifact/type :code
                   :artifact/content "(defn broken ["}
                  [{:code :syntax-error :message "Unmatched delimiter"}]
                  mock-context)

  :leave-this-here)
