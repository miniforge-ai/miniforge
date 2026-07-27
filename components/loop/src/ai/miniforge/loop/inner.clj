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
(ns ai.miniforge.loop.inner
  "Inner loop state machine implementation.
   Manages the Generate -> Validate -> Repair cycle for artifact production.

   10 real layers (over the 3-layer budget; a namespace split is
   deferred to Wave 2, see work/stratum-lint-baseline-2026-07-24.md):
   Layer 0: FSM config/derived helpers (default-max-iterations,
     initial-loop-state, default-loop-metrics, invalid-transition-message,
     inner-loop-machine-definition, final-state-definition?,
     transition-targets, derive-transition-events, add-metric-values,
     sum-repair-result-metrics) and pure state accessors
     (add-gate-results, add-repair-attempt, set-artifact, set-errors,
     increment-iteration, set-termination, budget-exhausted?,
     handle-terminal-state) — no same-file dependents among them
   Layer 1: inner-loop-machine, derive-valid-transitions,
     transition-events, terminal-states (derived from the Layer 0 machine
     definition/helpers), create-inner-loop, update-metrics,
     max-iterations-exceeded?, resume-after-escalation
   Layer 2: valid-transitions (derived from derive-valid-transitions),
     terminal-state? (calls terminal-states), handle-escalated-state
     (calls handle-terminal-state and resume-after-escalation)
   Layer 3: invalid-transition-data and valid-transition? (call
     valid-transitions), should-terminate? (calls
     max-iterations-exceeded?, budget-exhausted?, terminal-state?)
   Layer 4: transition-result (calls transition-events and
     invalid-transition-data), compute-termination-check (calls
     should-terminate?)
   Layer 5: transition (calls transition-result)
   Layer 6: generate-step, validate-step, repair-step, and
     handle-pre-termination (all call transition)
   Layer 7: handle-pending-state, handle-generating-state,
     handle-validating-state, handle-repairing-state (call the matching
     Layer 6 step fn)
   Layer 8: run-inner-loop (calls the Layer 7 handle-*-state fns and
     handle-escalated-state)
   Layer 9: run-simple (calls run-inner-loop)"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.loop.escalation :as escalation]
   [ai.miniforge.loop.inner-config :as inner-config]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.messages :as loop-messages]
   [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0

;; State machine definition and derived transition helpers
(def ^{:stratum 0} default-max-iterations
  (inner-config/default-max-iterations))

(def ^{:stratum 0} initial-loop-state
  (inner-config/initial-loop-state))

(def ^{:stratum 0} default-loop-metrics
  (inner-config/default-loop-metrics))

(def ^{:stratum 0} invalid-transition-message
  (loop-messages/t :inner/invalid-transition))

(def ^{:stratum 0} inner-loop-machine-definition
  (inner-config/machine-definition))

(defn- ^{:stratum 0} final-state-definition?
  [[_ state-definition]]
  (= :final (:type state-definition)))

(defn- ^{:stratum 0} transition-targets
  [state-definition]
  (->> (get state-definition :on {})
       vals
       (map (fn transition-target [target-or-config]
              (if (keyword? target-or-config)
                target-or-config
                (:target target-or-config))))
       set))

(defn- ^{:stratum 0} derive-transition-events
  [machine-definition]
  (reduce-kv
   (fn [event-map state state-definition]
     (reduce-kv
      (fn [state-events event target-or-config]
        (let [target-state (if (keyword? target-or-config)
                             target-or-config
                             (:target target-or-config))]
          (assoc state-events [state target-state] event)))
      event-map
      (get state-definition :on {})))
   {}
   (:fsm/states machine-definition)))

(defn- ^{:stratum 0} add-metric-values
  [metrics metric-updates]
  (merge-with (fn sum-metric-values [old new]
                (if (number? old)
                  (+ old new)
                  new))
              metrics
              metric-updates))

(defn- ^{:stratum 0} sum-repair-result-metrics
  "repair/attempt-repair returns top-level shapes that hold per-attempt
   results in `:results`. The metrics fields (`:tokens-used`,
   `:cost-usd`) are on those entries, not on the outer map. Sum
   them so the loop's :cost-usd / :tokens accumulators see what
   each repair iteration actually cost.

   Returns a `{:tokens N :cost-usd N}` map ready to thread into
   `update-metrics`. Empty / missing :results yields zeros."
  [repair-result]
  (let [results (:results repair-result [])]
    {:tokens   (->> results (map #(or (:tokens-used %) 0))  (reduce + 0))
     :cost-usd (->> results (map #(or (:cost-usd %) 0.0))   (reduce + 0.0))}))

(defn ^{:stratum 0} add-gate-results
  "Add gate results to loop state."
  [loop-state results]
  (update loop-state :loop/gate-results into results))

(defn ^{:stratum 0} add-repair-attempt
  "Add a repair attempt to loop history."
  [loop-state attempt]
  (update loop-state :loop/repair-history conj attempt))

(defn ^{:stratum 0} set-artifact
  "Set the artifact in loop state."
  [loop-state artifact]
  (assoc loop-state :loop/artifact artifact))

(defn ^{:stratum 0} set-errors
  "Set the current errors in loop state."
  [loop-state errors]
  (assoc loop-state :loop/errors errors))

(defn ^{:stratum 0} increment-iteration
  "Increment the iteration counter."
  [loop-state]
  (update loop-state :loop/iteration inc))

(defn ^{:stratum 0} set-termination
  "Set termination reason and message."
  [loop-state reason & {:keys [message]}]
  (assoc loop-state :loop/termination
         (cond-> {:reason reason}
           message (assoc :message message))))

(defn ^{:stratum 0} budget-exhausted?
  "Check if budget constraints have been exceeded."
  [loop-state]
  (let [budget (get-in loop-state [:loop/config :budget])
        metrics (:loop/metrics loop-state)]
    (when budget
      (or (and (:max-tokens budget)
               (>= (:tokens metrics 0) (:max-tokens budget)))
          (and (:max-cost-usd budget)
               (>= (:cost-usd metrics 0.0) (:max-cost-usd budget)))
          (and (:max-duration-ms budget)
               (>= (:duration-ms metrics 0) (:max-duration-ms budget)))))))

;; Loop runner helpers
(defn ^{:stratum 0} handle-terminal-state
  "Handle terminal states (complete, failed, escalated).
   Returns the final result map."
  [state logger]
  (let [current-state (:loop/state state)
        success? (= current-state :complete)]
    (when logger
      (if success?
        (log/info logger :loop :inner/validation-passed
                  {:message (loop-messages/t :inner/completed-successfully)
                   :data {:iterations (:loop/iteration state)}})
        (log/warn logger :loop :inner/escalated
                  {:message (loop-messages/t :inner/terminated)
                   :data {:state current-state
                          :reason (get-in state [:loop/termination :reason])}})))
    {:success success?
     :artifact (when success? (:loop/artifact state))
     :iterations (:loop/iteration state)
     :metrics (:loop/metrics state)
     :termination (:loop/termination state)
     :final-state state}))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} inner-loop-machine
  (fsm/define-machine inner-loop-machine-definition))

(defn- ^{:stratum 1} derive-valid-transitions
  [machine-definition]
  (reduce-kv
   (fn [transitions state state-definition]
     (assoc transitions state (transition-targets state-definition)))
   {}
   (:fsm/states machine-definition)))

(def ^{:stratum 1} transition-events
  (derive-transition-events inner-loop-machine-definition))

(def ^{:stratum 1} terminal-states
  (into #{}
        (comp (filter final-state-definition?)
              (map first))
        (:fsm/states inner-loop-machine-definition)))

;; Loop state constructors (pure functions)
(defn ^{:stratum 1} create-inner-loop
  "Create a new inner loop state.

   Arguments:
   - task - Map with :task/id and :task/type
   - context - Optional context map

  Options (via context):
  - :max-iterations - Maximum generate/repair cycles (default 5)
  - :budget - Budget constraints map"
  [task context]
  (let [max-iterations (get context :max-iterations default-max-iterations)
        budget (:budget context)]
    {:loop/id (random-uuid)
     :loop/type :inner
     :loop/state initial-loop-state
     :loop/iteration 0
     :loop/task task
     :loop/config (cond-> {:max-iterations max-iterations}
                    budget (assoc :budget budget))
     :loop/gate-results []
     :loop/repair-history []
     :loop/metrics default-loop-metrics
     :loop/created-at (java.util.Date.)
     :loop/updated-at (java.util.Date.)}))

(defn ^{:stratum 1} update-metrics
  "Update loop metrics with new values."
  [loop-state metric-updates]
  (update loop-state :loop/metrics add-metric-values metric-updates))

;; Termination checks (pure functions)
(defn ^{:stratum 1} max-iterations-exceeded?
  "Check if maximum iterations have been exceeded."
  [loop-state]
  (let [max-iter (get-in loop-state [:loop/config :max-iterations]
                         default-max-iterations)
        current (get loop-state :loop/iteration 0)]
    (>= current max-iter)))

(defn ^{:stratum 1} resume-after-escalation
  "Resume the loop after human escalation with provided hints."
  [state hints]
  (let [current-iter (:loop/iteration state)
        current-max (get-in state [:loop/config :max-iterations]
                            default-max-iterations)
        current-count (get-in state [:loop/escalation :count] 0)
        target-max (max (inc current-max) (inc current-iter))]
    (-> state
        (dissoc :loop/termination)
        (assoc :loop/state :generating
               :loop/override-termination? true
               :loop/escalation {:hints hints
                                 :resumed-at (java.util.Date.)
                                 :count (inc current-count)})
        (assoc-in [:loop/config :max-iterations] target-max)
        (assoc :loop/updated-at (java.util.Date.)))))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} valid-transitions
  "Valid state transitions in the inner loop state machine."
  (derive-valid-transitions inner-loop-machine-definition))

(defn ^{:stratum 2} terminal-state?
  "Check if a state is terminal (no further transitions possible)."
  [state]
  (contains? terminal-states state))

(defn ^{:stratum 2} handle-escalated-state
  "Handle escalated state - prompt user or terminate.
   Returns {:next-state state :next-ctx ctx} or {:terminal result}."
  [state ctx logger]
  (let [escalation-count (get-in state [:loop/escalation :count] 0)]
    (if (>= escalation-count 1)
      {:terminal (handle-terminal-state
                  (set-termination state :max-iterations
                                   :message (loop-messages/t :inner/escalation-already-handled))
                  logger)}
      (let [result (escalation/handle-escalation state ctx)]
        (if (= :continue (:action result))
          (let [hints (:hints result)
                resumed-state (resume-after-escalation state hints)
                next-ctx (assoc ctx :escalation-hints hints)]
            (when logger
              (log/info logger :loop :inner/escalated
                        {:message (loop-messages/t :inner/resuming-after-escalation)
                         :data {:hints-provided? (boolean (seq hints))}}))
            {:next-state resumed-state
             :next-ctx next-ctx})
          {:terminal (handle-terminal-state state logger)})))))

;------------------------------------------------------------------------------ Layer 3

(defn- ^{:stratum 3} invalid-transition-data
  [from-state to-state]
  {:from from-state
   :to to-state
   :valid-targets (get valid-transitions from-state #{})})

(defn ^{:stratum 3} valid-transition?
  "Check if a state transition is valid."
  [from-state to-state]
  (contains? (get valid-transitions from-state #{}) to-state))

(defn ^{:stratum 3} should-terminate?
  "Check if the loop should terminate.
   Returns {:terminate? bool :reason keyword} or nil."
  [loop-state]
  (cond
    (terminal-state? (:loop/state loop-state))
    {:terminate? true :reason (:loop/state loop-state)}

    (max-iterations-exceeded? loop-state)
    {:terminate? true :reason :max-iterations}

    (budget-exhausted? loop-state)
    {:terminate? true :reason :budget-exhausted}

    :else nil))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} transition-result
  "Attempt a state transition.

   Returns the updated loop state on success, or an `:invalid-input` anomaly
   when the FSM rejects the transition."
  [loop-state new-state]
  (let [current-state (:loop/state loop-state)
        transition-event (get transition-events [current-state new-state])]
    (if-not transition-event
      (anomaly/anomaly :invalid-input
                       invalid-transition-message
                       (invalid-transition-data current-state new-state))
      (assoc loop-state
             :loop/state new-state
             :loop/updated-at (java.util.Date.)))))

(defn ^{:stratum 4} compute-termination-check
  "Compute the termination result, allowing validation to complete once."
  [state current-state override-termination?]
  (when-not override-termination?
    (let [termination (should-terminate? state)]
      (if (and (= current-state :validating)
               (= :max-iterations (:reason termination)))
        nil
        termination))))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} transition
  "Boundary compatibility wrapper around [[transition-result]].

   Returns updated loop state on success. On FSM rejection, throws `ex-info`
   to preserve the existing caller contract. Prefer the anomaly-returning
   [[transition-result]] in non-boundary code when callers can branch on
   anomaly data."
  [loop-state new-state]
  (let [result (transition-result loop-state new-state)]
    (if (anomaly/anomaly? result)
      (throw (ex-info invalid-transition-message
                      (:anomaly/data result)))
      result)))

;------------------------------------------------------------------------------ Layer 6

;; Step functions
(defn ^{:stratum 6} generate-step
  "Execute the generate step using the provided agent/generate function.

   Arguments:
   - loop-state - Current loop state (must be :pending or after repair)
   - generate-fn - Function (fn [task context] -> {:artifact map :tokens int :duration-ms int})
   - context - Context map with :logger, etc.

   Returns updated loop state with artifact and :validating state."
  [loop-state generate-fn context]
  (let [logger (:logger context)
        start (System/currentTimeMillis)]
    (when logger
      (log/info logger :loop :inner/iteration-started
                {:message (loop-messages/t :inner/starting-generation)
                 :data {:iteration (:loop/iteration loop-state)
                        :task-id (get-in loop-state [:loop/task :task/id])}}))

    ;; Transition to generating (skip if already in generating state from repair)
    (let [loop-state (-> loop-state
                         (cond-> (not= (:loop/state loop-state) :generating)
                           (transition :generating))
                         (increment-iteration))]
      (try
        (let [result (generate-fn (:loop/task loop-state) context)
              duration (- (System/currentTimeMillis) start)
              artifact (:artifact result)]
          (when logger
            (log/debug logger :loop :inner/iteration-started
                       {:message (loop-messages/t :inner/generation-complete)
                        :data {:tokens (:tokens result 0)
                               :duration-ms duration}}))
          (-> loop-state
              (set-artifact artifact)
              ;; :cost-usd carries through the same way :tokens does
              ;; — generate-fn is expected to return it at top level
              ;; (default 0.0 when absent) so add-metric-values'
              ;; merge-with + can sum across iterations. Without this
              ;; the :loop/metrics map keeps :cost-usd at its initial
              ;; 0.0 and the workflow runner banner reports $0.0000.
              (update-metrics {:tokens (:tokens result 0)
                               :cost-usd (:cost-usd result 0.0)
                               :duration-ms duration
                               :generate-calls 1})
              (transition :validating)))
        (catch Exception e
          (when logger
            (log/error logger :loop :inner/validation-failed
                       {:message (loop-messages/t :inner/generation-failed)
                        :data {:error (.getMessage e)}}))
          (let [anom (anomaly/exception-anomaly :fault (.getMessage e) e)]
            (-> loop-state
                (set-errors [{:code :generation-error
                              :message (.getMessage e)
                              :anomaly anom}])
                (set-termination :unrecoverable-error
                                 :message (.getMessage e))
                (transition :failed))))))))

(defn ^{:stratum 6} validate-step
  "Execute the validate step using provided gates.

   Arguments:
   - loop-state - Current loop state (must be :validating)
   - gates - Sequence of Gate implementations
   - context - Context map with :logger, etc.

   Returns updated loop state with :complete or :repairing state."
  [loop-state gates context]
  (let [logger (:logger context)
        artifact (:loop/artifact loop-state)]
    (when logger
      (log/debug logger :loop :inner/validation-passed
                 {:message (loop-messages/t :inner/starting-validation)
                  :data {:gate-count (count gates)}}))

    (let [results (gates/run-gates gates artifact context)
          loop-state (add-gate-results loop-state (:results results))]
      (if (gates/passed? results)
        ;; All gates passed
        (do
          (when logger
            (log/info logger :loop :inner/validation-passed
                      {:message (loop-messages/t :inner/all-gates-passed)
                       :data {:iteration (:loop/iteration loop-state)}}))
          (-> loop-state
              (set-termination :gates-passed)
              (transition :complete)))
        ;; Validation failed
        (do
          (when logger
            (log/warn logger :loop :inner/validation-failed
                      {:message (loop-messages/t :inner/validation-failed)
                       :data {:failed-gates (:failed-gates results)
                              :error-count (count (:errors results))}}))
          (-> loop-state
              (set-errors (:errors results))
              (transition :repairing)))))))

(defn ^{:stratum 6} repair-step
  "Execute the repair step using provided strategies.

   Arguments:
   - loop-state - Current loop state (must be :repairing)
   - strategies - Sequence of RepairStrategy implementations
   - context - Context map with :logger, :repair-fn, :escalation-fn, etc.
     If escalation resumes, :escalation-hints is added to context for generation.

   Returns updated loop state with :generating (success) or :escalated/:failed."
  [loop-state strategies context]
  (let [logger (:logger context)
        artifact (:loop/artifact loop-state)
        errors (:loop/errors loop-state)
        iteration (:loop/iteration loop-state)]
    (when logger
      (log/info logger :loop :inner/repair-attempted
                {:message (loop-messages/t :inner/attempting-repair)
                 :data {:iteration iteration
                        :error-count (count errors)}}))

    ;; Check for termination before attempting repair
    (if-let [termination (should-terminate? loop-state)]
      ;; Terminate with escalation
      (do
        (when logger
          (log/warn logger :loop :inner/escalated
                    {:message (loop-messages/t :inner/escalating-termination)
                     :data {:reason (:reason termination)}}))
        (-> loop-state
            (set-termination (:reason termination))
            (transition :escalated)))

      ;; Attempt repair
      (let [result (repair/attempt-repair strategies artifact errors context)
            attempt (repair/create-repair-attempt
                     (:strategy result)
                     iteration
                     errors
                     result)
            ;; repair/attempt-repair returns aggregate shapes
            ;; (make-success-result / make-max-attempts-result /
            ;; make-exhausted-strategies-result / make-escalation-
            ;; result) that put per-attempt metrics under :results,
            ;; not at the top level. Reading (:tokens-used result)
            ;; would always default to zero and starve the loop's
            ;; :cost-usd / :tokens accumulation. Sum the per-attempt
            ;; entries before threading them into update-metrics.
            repair-totals (sum-repair-result-metrics result)
            loop-state (-> loop-state
                           (add-repair-attempt attempt)
                           (update-metrics
                            (assoc repair-totals :repair-calls 1)))]
        (cond
          ;; Repair succeeded
          (repair/succeeded? result)
          (do
            (when logger
              (log/info logger :loop :inner/repair-attempted
                        {:message (loop-messages/t :inner/repair-successful)
                         :data {:strategy (:strategy result)}}))
            (-> loop-state
                (set-artifact (:artifact result))
                (transition :generating)))

          ;; Escalation requested
          (:escalate? result)
          (do
            (when logger
              (log/warn logger :loop :inner/escalated
                        {:message (loop-messages/t :inner/repair-requested-escalation)}))
            (-> loop-state
                (set-termination :max-iterations
                                 :message (loop-messages/t :inner/repair-strategies-exhausted))
                (transition :escalated)))

          ;; Repair failed, try again or escalate
          :else
          (do
            (when logger
              (log/warn logger :loop :inner/validation-failed
                        {:message (loop-messages/t :inner/repair-failed)
                         :data {:attempts (:attempts result)}}))
            ;; Check if we should escalate
            (if (max-iterations-exceeded? loop-state)
              (-> loop-state
                  (set-termination :max-iterations)
                  (transition :escalated))
              ;; Loop back to try generating again
              (transition loop-state :generating))))))))

(defn ^{:stratum 6} handle-pre-termination
  "Handle pre-termination conditions (budget exhausted, max iterations).
   Returns updated state transitioned to appropriate terminal state."
  [state termination-result]
  (let [{:keys [reason]} termination-result]
    (-> state
        (set-termination reason)
        (transition (if (= reason :gates-passed)
                      :complete
                      :escalated)))))

;------------------------------------------------------------------------------ Layer 7

(defn ^{:stratum 7} handle-pending-state
  "Handle pending state - initiate generation.
   Returns updated state."
  [state generate-fn context]
  (generate-step state generate-fn context))

(defn ^{:stratum 7} handle-generating-state
  "Handle generating state - continue generation.
   Returns updated state."
  [state generate-fn context]
  (generate-step state generate-fn context))

(defn ^{:stratum 7} handle-validating-state
  "Handle validating state - run validation.
   Returns updated state."
  [state gates context]
  (validate-step state gates context))

(defn ^{:stratum 7} handle-repairing-state
  "Handle repairing state - attempt repair.
   Returns updated state."
  [state strategies context]
  (repair-step state strategies context))

;------------------------------------------------------------------------------ Layer 8

(defn ^{:stratum 8} run-inner-loop
  "Run the inner loop to completion.

   Arguments:
   - loop-state - Initial loop state (should be :pending)
   - generate-fn - Function (fn [task context] -> {:artifact map ...})
   - gates - Sequence of Gate implementations
   - strategies - Sequence of RepairStrategy implementations
   - context - Context map with :logger, :repair-fn, etc.

   Returns:
   {:success boolean
    :artifact artifact-map (if success)
    :iterations int
    :metrics metrics-map
    :termination {:reason keyword :message string}}"
  [loop-state generate-fn gates strategies context]
  (let [logger (:logger context)]
    (when logger
      (log/info logger :loop :inner/iteration-started
                {:message (loop-messages/t :inner/starting-loop)
                 :data {:task-id (get-in loop-state [:loop/task :task/id])
                        :max-iterations (get-in loop-state [:loop/config :max-iterations])}}))

    (loop [state loop-state
           ctx context]
      (let [current-state (:loop/state state)
            override-termination? (:loop/override-termination? state)
            state (if override-termination?
                    (dissoc state :loop/override-termination?)
                    state)
            termination-check (compute-termination-check
                               state
                               current-state
                               override-termination?)]
        (cond
          ;; Escalated state - prompt user for guidance
          (= current-state :escalated)
          (let [result (handle-escalated-state state ctx logger)]
            (if-let [next-state (:next-state result)]
              (recur next-state (:next-ctx result))
              (:terminal result)))

          ;; Terminal states - return result
          (terminal-state? current-state)
          (handle-terminal-state state logger)

          ;; Pre-termination check - cache result to avoid calling twice
          termination-check
          (recur (handle-pre-termination state termination-check) ctx)

          ;; State-based dispatch
          (= current-state :pending)
          (recur (handle-pending-state state generate-fn ctx) ctx)

          (= current-state :generating)
          (recur (handle-generating-state state generate-fn ctx) ctx)

          (= current-state :validating)
          (recur (handle-validating-state state gates ctx) ctx)

          (= current-state :repairing)
          (recur (handle-repairing-state state strategies ctx) ctx)

          ;; Unknown state (shouldn't happen)
          :else
          (throw (ex-info (loop-messages/t :inner/unknown-state)
                          {:state current-state
                           :loop-state state})))))))

;------------------------------------------------------------------------------ Layer 9

(defn ^{:stratum 9} run-simple
  "Simplified runner for basic use cases.
   Uses default gates and strategies.

   Arguments:
   - task - Map with :task/id and :task/type
   - generate-fn - Generation function
   - context - Context map

   Options (via context):
   - :max-iterations - Max iterations (default 5)
   - :logger - Logger instance
   - :repair-fn - Repair function for LLM strategy"
  [task generate-fn context]
  (let [loop-state (create-inner-loop task context)
        gates (gates/default-gates)
        strategies (repair/default-strategies)]
    (run-inner-loop loop-state generate-fn gates strategies context)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a loop state
  (def task {:task/id (random-uuid)
             :task/type :implement})

  (def loop-state (create-inner-loop task {:max-iterations 3}))

  ;; Check state
  (:loop/state loop-state)
  ;; => :pending

  ;; Test transitions
  (-> loop-state
      (transition :generating)
      (transition :validating)
      :loop/state)
  ;; => :validating

  ;; Invalid transition throws
  (try
    (transition loop-state :complete)
    (catch Exception e
      (.getMessage e)))
  ;; => "Invalid state transition"

  ;; Mock generate function
  (defn mock-generate [_task _ctx]
    {:artifact {:artifact/id (random-uuid)
                :artifact/type :code
                :artifact/content "(defn hello [] \"world\")"}
     :tokens 100})

  ;; Run the loop with mock
  (def result
    (run-simple task mock-generate
                {:max-iterations 3
                 :logger nil}))

  (:success result)
  ;; => true (if syntax is valid)

  (:iterations result)
  ;; => 1

  ;; Test with failing generation
  (defn failing-generate [_task _ctx]
    {:artifact {:artifact/id (random-uuid)
                :artifact/type :code
                :artifact/content "(defn broken ["}  ; invalid syntax
     :tokens 50})

  (def fail-result
    (run-simple task failing-generate
                {:max-iterations 2
                 :logger nil
                 :repair-fn (fn [artifact _errors _ctx]
                              ;; Mock repair that fixes the artifact
                              {:success? true
                               :artifact (assoc artifact
                                                :artifact/content
                                                "(defn fixed [] :ok)")
                               :tokens-used 100})}))

  fail-result

  :leave-this-here)
