(ns ai.miniforge.workflow.core
  "Core workflow implementation.
   Manages SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe"
  (:require
   [ai.miniforge.workflow.protocol :as proto]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.task.interface :as task]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def default-config
  "Default workflow configuration."
  {:max-iterations-per-phase 5
   :max-rollbacks 3
   :timeout-per-phase-ms (* 10 60 1000)}) ; 10 minutes per phase

;------------------------------------------------------------------------------ Layer 1
;; Workflow state management

(defn create-workflow-state
  "Create initial workflow state."
  [workflow-id spec]
  {:workflow/id workflow-id
   :workflow/spec spec
   :workflow/phase :spec
   :workflow/status :pending
   :workflow/artifacts []
   :workflow/errors []
   :workflow/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
   :workflow/history []
   :workflow/rollback-count 0
   :workflow/created-at (System/currentTimeMillis)})

(defn- record-phase-transition
  "Record a phase transition in workflow history."
  [state from-phase to-phase reason]
  (update state :workflow/history conj
          {:from from-phase
           :to to-phase
           :reason reason
           :timestamp (System/currentTimeMillis)}))

(defn valid-transition?
  "Check if phase transition is valid."
  [from-phase to-phase]
  (contains? (get proto/phase-transitions from-phase #{}) to-phase))

(defn- next-phase
  "Determine next phase based on current phase and result."
  [current-phase success?]
  (case current-phase
    :spec :plan
    :plan :implement  ; Skip design for now (optional phase)
    :design :implement
    :implement :verify
    :verify (if success? :review :implement)
    :review (if success? :release :implement)
    :release :observe
    :observe :done
    :done :done))

;------------------------------------------------------------------------------ Layer 2
;; Core workflow implementation

(defrecord SimpleWorkflow [config workflows observers logger]
  proto/Workflow

  (start [_this spec context]
    (let [workflow-id (random-uuid)
          state (create-workflow-state workflow-id spec)]
      (swap! workflows assoc workflow-id state)

      (log/info logger :workflow :workflow/started
                {:data {:workflow-id workflow-id
                        :spec-title (:title spec)}})

      ;; Notify observers
      (doseq [obs @observers]
        (proto/on-phase-start obs workflow-id :spec context))

      workflow-id))

  (get-state [_this workflow-id]
    (get @workflows workflow-id))

  (advance [this workflow-id phase-result]
    (when-let [state (get @workflows workflow-id)]
      (let [current-phase (:workflow/phase state)
            success? (:success? phase-result)
            new-phase (next-phase current-phase success?)
            new-artifacts (into (:workflow/artifacts state)
                                (:artifacts phase-result []))
            new-errors (into (:workflow/errors state)
                             (:errors phase-result []))
            new-metrics (merge-with + (:workflow/metrics state)
                                    (:metrics phase-result {}))
            new-state (-> state
                          (assoc :workflow/phase new-phase)
                          (assoc :workflow/status (if (= new-phase :done) :completed :running))
                          (assoc :workflow/artifacts new-artifacts)
                          (assoc :workflow/errors new-errors)
                          (assoc :workflow/metrics new-metrics)
                          (record-phase-transition current-phase new-phase
                                                   (if success? :success :failure)))]

        (swap! workflows assoc workflow-id new-state)

        (log/info logger :workflow :workflow/phase-advanced
                  {:data {:workflow-id workflow-id
                          :from-phase current-phase
                          :to-phase new-phase
                          :success? success?}})

        ;; Notify observers
        (doseq [obs @observers]
          (proto/on-phase-complete obs workflow-id current-phase phase-result)
          (when (= new-phase :done)
            (proto/on-workflow-complete obs workflow-id new-state)))

        new-state)))

  (rollback [_this workflow-id target-phase reason]
    (when-let [state (get @workflows workflow-id)]
      (let [current-phase (:workflow/phase state)
            rollback-count (:workflow/rollback-count state)]

        (if (>= rollback-count (:max-rollbacks config))
          ;; Too many rollbacks - fail the workflow
          (let [failed-state (-> state
                                 (assoc :workflow/status :failed)
                                 (update :workflow/errors conj
                                         {:type :max-rollbacks-exceeded
                                          :message "Maximum rollback count exceeded"}))]
            (swap! workflows assoc workflow-id failed-state)

            (log/error logger :workflow :workflow/max-rollbacks
                       {:data {:workflow-id workflow-id
                               :rollback-count rollback-count}})
            failed-state)

          ;; Perform rollback
          (let [new-state (-> state
                              (assoc :workflow/phase target-phase)
                              (update :workflow/rollback-count inc)
                              (record-phase-transition current-phase target-phase reason))]

            (swap! workflows assoc workflow-id new-state)

            (log/warn logger :workflow :workflow/rollback
                      {:data {:workflow-id workflow-id
                              :from-phase current-phase
                              :to-phase target-phase
                              :reason reason}})

            ;; Notify observers
            (doseq [obs @observers]
              (proto/on-rollback obs workflow-id current-phase target-phase reason))

            new-state)))))

  (complete [_this workflow-id]
    (when-let [state (get @workflows workflow-id)]
      (let [new-state (-> state
                          (assoc :workflow/phase :done)
                          (assoc :workflow/status :completed)
                          (assoc :workflow/completed-at (System/currentTimeMillis)))]

        (swap! workflows assoc workflow-id new-state)

        (log/info logger :workflow :workflow/completed
                  {:data {:workflow-id workflow-id
                          :duration-ms (- (:workflow/completed-at new-state)
                                          (:workflow/created-at new-state))}})

        ;; Notify observers
        (doseq [obs @observers]
          (proto/on-workflow-complete obs workflow-id new-state))

        new-state)))

  (fail [_this workflow-id error]
    (when-let [state (get @workflows workflow-id)]
      (let [current-phase (:workflow/phase state)
            new-state (-> state
                          (assoc :workflow/status :failed)
                          (update :workflow/errors conj error)
                          (assoc :workflow/failed-at (System/currentTimeMillis)))]

        (swap! workflows assoc workflow-id new-state)

        (log/error logger :workflow :workflow/failed
                   {:data {:workflow-id workflow-id
                           :phase current-phase
                           :error error}})

        ;; Notify observers
        (doseq [obs @observers]
          (proto/on-phase-error obs workflow-id current-phase error))

        new-state))))

(defn create-workflow
  "Create a workflow manager."
  ([] (create-workflow {}))
  ([config]
   (->SimpleWorkflow
    (merge default-config config)
    (atom {})
    (atom [])
    (log/create-logger {:min-level :info}))))

(defn add-observer
  "Add a workflow observer."
  [workflow observer]
  (swap! (:observers workflow) conj observer)
  workflow)

(defn remove-observer
  "Remove a workflow observer."
  [workflow observer]
  (swap! (:observers workflow) (fn [obs] (remove #(= % observer) obs)))
  workflow)

;------------------------------------------------------------------------------ Layer 3
;; Phase executors

(defrecord PlanPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [spec (:workflow/spec workflow-state)
          task (task/create-task
                {:task/id (random-uuid)
                 :task/type :plan
                 :task/title (str "Plan: " (:title spec))
                 :task/description (:description spec)
                 :task/status :pending})
          planner (agent/create-planner {:llm-backend llm-backend})
          generate-fn (fn [_task _ctx]
                        (let [result (agent/invoke planner task context)]
                          {:artifact (:artifact result)
                           :tokens (or (:tokens result) 0)}))
          result (loop/run-simple task generate-fn
                                  (merge context {:max-iterations 3}))]
      {:success? (:success result)
       :artifacts (when (:success result)
                    [{:type :plan
                      :content (:artifact result)
                      :phase :plan}])
       :errors (when-not (:success result)
                 [{:type :plan-failed
                   :message (or (:error result) "Planning failed")}])
       :metrics (:metrics result)}))

  (can-execute? [_this phase]
    (= phase :plan))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:spec]
     :optional-artifacts []}))

(defrecord ImplementPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [plan-artifacts (->> (:workflow/artifacts workflow-state)
                              (filter #(= :plan (:type %))))
          results (atom {:artifacts [] :errors [] :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})]

      ;; Execute each implementation task from the plan
      (doseq [plan-artifact plan-artifacts]
        (let [task (task/create-task
                    {:task/id (random-uuid)
                     :task/type :implement
                     :task/title "Implement from plan"
                     :task/description (str (:content plan-artifact))
                     :task/status :pending})
              implementer (agent/create-implementer {:llm-backend llm-backend})
              generate-fn (fn [_task _ctx]
                            (let [result (agent/invoke implementer task context)]
                              {:artifact (:artifact result)
                               :tokens (or (:tokens result) 0)}))
              result (loop/run-simple task generate-fn
                                      (merge context {:max-iterations 5}))]

          (swap! results update :artifacts conj
                 {:type :code
                  :content (:artifact result)
                  :phase :implement})
          (when-not (:success result)
            (swap! results update :errors conj
                   {:type :implement-failed
                    :message (or (:error result) "Implementation failed")}))
          (swap! results update :metrics
                 (fn [m] (merge-with + m (or (:metrics result) {}))))))

      (let [{:keys [artifacts errors metrics]} @results]
        {:success? (empty? errors)
         :artifacts artifacts
         :errors errors
         :metrics metrics})))

  (can-execute? [_this phase]
    (= phase :implement))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:plan]
     :optional-artifacts [:design]}))

(defrecord VerifyPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [code-artifacts (->> (:workflow/artifacts workflow-state)
                              (filter #(= :code (:type %))))
          results (atom {:artifacts [] :errors [] :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})]

      ;; Generate and run tests for each code artifact
      (doseq [code-artifact code-artifacts]
        (let [;; Extract artifact ID if available, or generate one
              artifact-id (or (get-in code-artifact [:content :code/id]) (random-uuid))
              task (task/create-task
                    {:task/id (random-uuid)
                     :task/type :test
                     :task/title "Generate tests"
                     :task/description (str "Generate tests for code artifact " artifact-id)
                     :task/status :pending
                     ;; Note: :task/inputs should be vector of UUIDs per schema
                     ;; We'll pass the actual artifact content via context instead
                     :task/inputs [artifact-id]})
              tester (agent/create-tester {:llm-backend llm-backend})
              ;; Pass code content to tester via the task input (code artifact content)
              test-context (assoc context :code-artifact (:content code-artifact))
              generate-fn (fn [_task _ctx]
                            ;; The tester expects code artifact as input
                            (let [result (agent/invoke tester {:code (:content code-artifact)} test-context)]
                              {:artifact (:artifact result)
                               :tokens (or (:tokens result) 0)}))
              result (loop/run-simple task generate-fn
                                      (merge context {:max-iterations 3}))]

          (swap! results update :artifacts conj
                 {:type :test
                  :content (:artifact result)
                  :phase :verify})
          (when-not (:success result)
            (swap! results update :errors conj
                   {:type :verify-failed
                    :message (or (:error result) "Verification failed")}))
          (swap! results update :metrics
                 (fn [m] (merge-with + m (or (:metrics result) {}))))))

      (let [{:keys [artifacts errors metrics]} @results]
        {:success? (empty? errors)
         :artifacts artifacts
         :errors errors
         :metrics metrics})))

  (can-execute? [_this phase]
    (= phase :verify))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:code]
     :optional-artifacts []}))

(defn create-phase-executor
  "Create a phase executor for a specific phase."
  [phase llm-backend]
  (case phase
    :plan (->PlanPhaseExecutor llm-backend)
    :implement (->ImplementPhaseExecutor llm-backend)
    :verify (->VerifyPhaseExecutor llm-backend)
    ;; Other phases return nil - they may need different executors
    nil))

;------------------------------------------------------------------------------ Layer 4
;; Workflow runner

(defn run-workflow
  "Run a complete workflow from spec to completion.

   Arguments:
   - workflow - Workflow instance
   - spec - Specification map {:title :description}
   - context - Execution context {:llm-backend :budget :tags}

   Returns final workflow state."
  [workflow spec context]
  (let [workflow-id (proto/start workflow spec context)
        llm-backend (:llm-backend context)]

    ;; Advance through phases
    (loop [state (proto/get-state workflow workflow-id)]
      (let [current-phase (:workflow/phase state)]
        (cond
          ;; Completed or failed
          (#{:done :completed :failed} (:workflow/status state))
          state

          ;; At done phase
          (= current-phase :done)
          (proto/complete workflow workflow-id)

          ;; Execute current phase
          :else
          (if-let [executor (create-phase-executor current-phase llm-backend)]
            (let [result (proto/execute-phase executor state context)
                  new-state (proto/advance workflow workflow-id result)]
              (if (= :failed (:workflow/status new-state))
                new-state
                (recur new-state)))

            ;; No executor for this phase - skip to next
            (let [new-state (proto/advance workflow workflow-id
                                           {:success? true
                                            :artifacts []
                                            :errors []})]
              (recur new-state))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.llm.interface :as llm])

  ;; Create workflow
  (def wf (create-workflow))

  ;; Mock LLM
  (def llm-client (llm/mock-client {:output "Mock plan response"}))

  ;; Run workflow
  (def result
    (run-workflow wf
                  {:title "Create greeting function"
                   :description "A function that says hello"}
                  {:llm-backend llm-client}))

  ;; Check state
  (:workflow/status result)
  (:workflow/phase result)
  (:workflow/artifacts result)

  :end)
