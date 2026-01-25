(ns ai.miniforge.loop.inner
  "Inner loop state machine implementation.
   Manages the Generate -> Validate -> Repair cycle for artifact production.

   Layer 0: Pure state transition functions
   Layer 1: Step functions (generate, validate, repair)
   Layer 2: Loop runner"
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.loop.escalation :as escalation]
   [ai.miniforge.loop.gates :as gates]
   [ai.miniforge.loop.repair :as repair]))

;------------------------------------------------------------------------------ Layer 0
;; State machine transitions (pure functions)

(def valid-transitions
  "Valid state transitions in the inner loop state machine."
  {:pending     #{:generating}
   :generating  #{:validating :failed}
   :validating  #{:complete :repairing :failed :escalated}
   :repairing   #{:generating :escalated :failed}
   :complete    #{}  ; terminal state
   :failed      #{}  ; terminal state
   :escalated   #{}}) ; terminal state

(defn valid-transition?
  "Check if a state transition is valid."
  [from-state to-state]
  (contains? (get valid-transitions from-state #{}) to-state))

(defn terminal-state?
  "Check if a state is terminal (no further transitions possible)."
  [state]
  (empty? (get valid-transitions state)))

(defn transition
  "Attempt a state transition. Returns updated loop state or throws if invalid."
  [loop-state new-state]
  (let [current-state (:loop/state loop-state)]
    (if (valid-transition? current-state new-state)
      (-> loop-state
          (assoc :loop/state new-state)
          (assoc :loop/updated-at (java.util.Date.)))
      (throw (ex-info "Invalid state transition"
                      {:from current-state
                       :to new-state
                       :valid-targets (get valid-transitions current-state)})))))

;------------------------------------------------------------------------------ Layer 0
;; Loop state constructors (pure functions)

(defn create-inner-loop
  "Create a new inner loop state.

   Arguments:
   - task - Map with :task/id and :task/type
   - context - Optional context map

   Options (via context):
   - :max-iterations - Maximum generate/repair cycles (default 5)
   - :budget - Budget constraints map"
  [task context]
  (let [max-iterations (or (:max-iterations context) 5)
        budget (:budget context)]
    {:loop/id (random-uuid)
     :loop/type :inner
     :loop/state :pending
     :loop/iteration 0
     :loop/task {:task/id (:task/id task)
                 :task/type (:task/type task)}
     :loop/config (cond-> {:max-iterations max-iterations}
                    budget (assoc :budget budget))
     :loop/gate-results []
     :loop/repair-history []
     :loop/metrics {:tokens 0
                    :cost-usd 0.0
                    :duration-ms 0
                    :generate-calls 0
                    :repair-calls 0}
     :loop/created-at (java.util.Date.)
     :loop/updated-at (java.util.Date.)}))

(defn update-metrics
  "Update loop metrics with new values."
  [loop-state metric-updates]
  (update loop-state :loop/metrics
          (fn [metrics]
            (merge-with (fn [old new]
                          (if (number? old)
                            (+ old new)
                            new))
                        metrics
                        metric-updates))))

(defn add-gate-results
  "Add gate results to loop state."
  [loop-state results]
  (update loop-state :loop/gate-results into results))

(defn add-repair-attempt
  "Add a repair attempt to loop history."
  [loop-state attempt]
  (update loop-state :loop/repair-history conj attempt))

(defn set-artifact
  "Set the artifact in loop state."
  [loop-state artifact]
  (assoc loop-state :loop/artifact artifact))

(defn set-errors
  "Set the current errors in loop state."
  [loop-state errors]
  (assoc loop-state :loop/errors errors))

(defn increment-iteration
  "Increment the iteration counter."
  [loop-state]
  (update loop-state :loop/iteration inc))

(defn set-termination
  "Set termination reason and message."
  [loop-state reason & {:keys [message]}]
  (assoc loop-state :loop/termination
         (cond-> {:reason reason}
           message (assoc :message message))))

;------------------------------------------------------------------------------ Layer 0
;; Termination checks (pure functions)

(defn max-iterations-exceeded?
  "Check if maximum iterations have been exceeded."
  [loop-state]
  (let [max-iter (get-in loop-state [:loop/config :max-iterations] 5)
        current (get loop-state :loop/iteration 0)]
    (>= current max-iter)))

(defn budget-exhausted?
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

(defn should-terminate?
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

;------------------------------------------------------------------------------ Layer 1
;; Step functions

(defn generate-step
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
                {:message "Starting generation"
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
                       {:message "Generation complete"
                        :data {:tokens (:tokens result 0)
                               :duration-ms duration}}))
          (-> loop-state
              (set-artifact artifact)
              (update-metrics {:tokens (:tokens result 0)
                               :duration-ms duration
                               :generate-calls 1})
              (transition :validating)))
        (catch Exception e
          (when logger
            (log/error logger :loop :inner/validation-failed
                       {:message "Generation failed"
                        :data {:error (.getMessage e)}}))
          (-> loop-state
              (set-errors [{:code :generation-error
                            :message (.getMessage e)}])
              (set-termination :unrecoverable-error
                               :message (.getMessage e))
              (transition :failed)))))))

(defn validate-step
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
                 {:message "Starting validation"
                  :data {:gate-count (count gates)}}))

    (let [results (gates/run-gates gates artifact context)
          loop-state (add-gate-results loop-state (:results results))]
      (if (:passed? results)
        ;; All gates passed
        (do
          (when logger
            (log/info logger :loop :inner/validation-passed
                      {:message "All gates passed"
                       :data {:iteration (:loop/iteration loop-state)}}))
          (-> loop-state
              (set-termination :gates-passed)
              (transition :complete)))
        ;; Validation failed
        (do
          (when logger
            (log/warn logger :loop :inner/validation-failed
                      {:message "Validation failed"
                       :data {:failed-gates (:failed-gates results)
                              :error-count (count (:errors results))}}))
          (-> loop-state
              (set-errors (:errors results))
              (transition :repairing)))))))

(defn repair-step
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
                {:message "Attempting repair"
                 :data {:iteration iteration
                        :error-count (count errors)}}))

    ;; Check for termination before attempting repair
    (if-let [termination (should-terminate? loop-state)]
      ;; Terminate with escalation
      (do
        (when logger
          (log/warn logger :loop :inner/escalated
                    {:message "Escalating due to termination condition"
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
            loop-state (-> loop-state
                           (add-repair-attempt attempt)
                           (update-metrics {:tokens (:tokens-used result 0)
                                            :repair-calls 1}))]
        (cond
          ;; Repair succeeded
          (:success? result)
          (do
            (when logger
              (log/info logger :loop :inner/repair-attempted
                        {:message "Repair successful"
                         :data {:strategy (:strategy result)}}))
            (-> loop-state
                (set-artifact (:artifact result))
                (transition :generating)))

          ;; Escalation requested
          (:escalate? result)
          (do
            (when logger
              (log/warn logger :loop :inner/escalated
                        {:message "Repair requested escalation"}))
            (-> loop-state
                (set-termination :max-iterations
                                 :message "Repair strategies exhausted")
                (transition :escalated)))

          ;; Repair failed, try again or escalate
          :else
          (do
            (when logger
              (log/warn logger :loop :inner/validation-failed
                        {:message "Repair failed"
                         :data {:attempts (:attempts result)}}))
            ;; Check if we should escalate
            (if (max-iterations-exceeded? loop-state)
              (-> loop-state
                  (set-termination :max-iterations)
                  (transition :escalated))
              ;; Loop back to try generating again
              (transition loop-state :generating))))))))

;------------------------------------------------------------------------------ Layer 2
;; Loop runner helpers

(defn- handle-terminal-state
  "Handle terminal states (complete, failed, escalated).
   Returns the final result map."
  [state logger]
  (let [current-state (:loop/state state)
        success? (= current-state :complete)]
    (when logger
      (if success?
        (log/info logger :loop :inner/validation-passed
                  {:message "Inner loop completed successfully"
                   :data {:iterations (:loop/iteration state)}})
        (log/warn logger :loop :inner/escalated
                  {:message "Inner loop terminated"
                   :data {:state current-state
                          :reason (get-in state [:loop/termination :reason])}})))
    {:success success?
     :artifact (when success? (:loop/artifact state))
     :iterations (:loop/iteration state)
     :metrics (:loop/metrics state)
     :termination (:loop/termination state)
     :final-state state}))

(defn- handle-pre-termination
  "Handle pre-termination conditions (budget exhausted, max iterations).
   Returns updated state transitioned to appropriate terminal state."
  [state termination-result]
  (let [{:keys [reason]} termination-result]
    (-> state
        (set-termination reason)
        (transition (if (= reason :gates-passed)
                      :complete
                      :escalated)))))

(defn- resume-after-escalation
  "Resume the loop after human escalation with provided hints."
  [state hints]
  (let [current-iter (:loop/iteration state)
        current-max (get-in state [:loop/config :max-iterations] 5)
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

(defn- handle-pending-state
  "Handle pending state - initiate generation.
   Returns updated state."
  [state generate-fn context]
  (generate-step state generate-fn context))

(defn- handle-generating-state
  "Handle generating state - continue generation.
   Returns updated state."
  [state generate-fn context]
  (generate-step state generate-fn context))

(defn- handle-validating-state
  "Handle validating state - run validation.
   Returns updated state."
  [state gates context]
  (validate-step state gates context))

(defn- handle-repairing-state
  "Handle repairing state - attempt repair.
   Returns updated state."
  [state strategies context]
  (repair-step state strategies context))

(defn run-inner-loop
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
                {:message "Starting inner loop"
                 :data {:task-id (get-in loop-state [:loop/task :task/id])
                        :max-iterations (get-in loop-state [:loop/config :max-iterations])}}))

    (loop [state loop-state
           ctx context]
      (let [current-state (:loop/state state)
            override-termination? (:loop/override-termination? state)
            state (if override-termination?
                    (dissoc state :loop/override-termination?)
                    state)
            termination-check (when-not override-termination?
                                (should-terminate? state))
            termination-check (if (and (= current-state :validating)
                                       (= :max-iterations (:reason termination-check)))
                                nil
                                termination-check)]
        (cond
          ;; Escalated state - prompt user for guidance
          (= current-state :escalated)
          (let [escalation-count (get-in state [:loop/escalation :count] 0)]
            (if (>= escalation-count 1)
              (handle-terminal-state
               (set-termination state :max-iterations
                                :message "Escalation already handled")
               logger)
              (let [result (escalation/handle-escalation state ctx)]
                (if (= :continue (:action result))
                  (let [hints (:hints result)
                        resumed-state (resume-after-escalation state hints)
                        next-ctx (assoc ctx :escalation-hints hints)]
                    (when logger
                      (log/info logger :loop :inner/escalated
                                {:message "Resuming after escalation"
                                 :data {:hints-provided? (boolean (seq hints))}}))
                    (recur resumed-state next-ctx))
                  (handle-terminal-state state logger)))))

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
          (throw (ex-info "Unknown loop state"
                          {:state current-state
                           :loop-state state})))))))

(defn run-simple
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
