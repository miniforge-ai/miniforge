(ns ai.miniforge.workflow.core
  "Core workflow implementation.
   Manages SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe

   Architecture:
   - Layer 0: Configuration and pure functions
   - Layer 1: Workflow state management and phase execution logic
   - Layer 2: Workflow orchestration (SimpleWorkflow, phase executors, run-workflow)"
  (:require
   [ai.miniforge.workflow.protocol :as proto]
   [ai.miniforge.workflow.release :as release]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.task.interface :as task]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def default-config
  "Default workflow configuration."
  {:max-iterations-per-phase 5
   :max-rollbacks 3
   :timeout-per-phase-ms (* 10 60 1000)}) ; 10 minutes per phase

;------------------------------------------------------------------------------ Layer 1
;; Workflow state management and phase execution helpers

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

;; Phase execution helpers to reduce nesting

(defn- persist-artifact
  "Persist an artifact to the store if available.
   Returns nil on success or error (side effect only)."
  [artifact-store artifact]
  (when artifact-store
    (try
      (artifact/save! artifact-store artifact)
      (catch Exception _e nil))))

(defn- persist-and-link-artifact
  "Persist an artifact and link it to a parent.
   Returns nil on success or error (side effect only)."
  [artifact-store artifact parent-id]
  (when artifact-store
    (try
      (artifact/save! artifact-store artifact)
      (artifact/link! artifact-store parent-id (:artifact/id artifact))
      (catch Exception _e nil))))

(defn- build-standard-artifact
  "Build a standard artifact from type-specific content."
  [artifact-type content-map metadata]
  (let [artifact-id (or (get content-map (keyword (name artifact-type) "id"))
                        (random-uuid))]
    (artifact/build-artifact
     {:id artifact-id
      :type artifact-type
      :version "1.0.0"
      :content content-map
      :metadata metadata})))

(defn- extract-artifact-content
  "Extract artifact content from either old or new format."
  [artifact]
  (or (:artifact/content artifact)
      (:content artifact)))

(defn- extract-artifact-id
  "Extract artifact ID from either old or new format."
  [artifact]
  (or (:artifact/id artifact)
      (get-in artifact [:artifact/content :code/id])
      (get-in artifact [:content :code/id])
      (random-uuid)))

(defn- filter-artifacts-by-type
  "Filter workflow artifacts by type (handles both old and new format)."
  [workflow-state artifact-type]
  (->> (:workflow/artifacts workflow-state)
       (filter #(or (= artifact-type (:type %))
                    (= artifact-type (:artifact/type %))))))

(defn- run-agent-with-loop
  "Run an agent with simple loop and return result.
   Pure function that orchestrates agent execution."
  [agent-constructor task context max-iterations]
  (let [agent-instance (agent-constructor)
        invoke-args (or (:invoke-args context) task)
        generate-fn (fn [_task _ctx]
                      (let [result (agent/invoke agent-instance invoke-args context)]
                        {:artifact (:artifact result)
                         :tokens (or (:tokens result) 0)}))]
    (loop/run-simple task generate-fn
                     (merge context {:max-iterations max-iterations}))))

;------------------------------------------------------------------------------ Layer 2
;; Workflow orchestration: SimpleWorkflow, phase executors, run-workflow

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

  (advance [_this workflow-id phase-result]
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

;; Phase executors

(defrecord PlanPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [spec (:workflow/spec workflow-state)
          artifact-store (:artifact-store context)
          task (task/create-task
                {:task/id (random-uuid)
                 :task/type :plan
                 :task/title (str "Plan: " (:title spec))
                 :task/description (:description spec)
                 :task/status :pending})
          result (run-agent-with-loop
                  #(agent/create-planner {:llm-backend llm-backend})
                  task context 3)]
      (if (:success result)
        (let [plan-artifact (:artifact result)
              standard-artifact (build-standard-artifact
                                 :plan
                                 plan-artifact
                                 {:phase :plan :spec-title (:title spec)})]
          (persist-artifact artifact-store standard-artifact)
          {:success? true
           :artifacts [standard-artifact]
           :errors []
           :metrics (:metrics result)})
        {:success? false
         :artifacts []
         :errors [{:type :plan-failed
                   :message (or (:error result) "Planning failed")}]
         :metrics (:metrics result)})))

  (can-execute? [_this phase]
    (= phase :plan))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:spec]
     :optional-artifacts []}))

(defn- process-implementation-plan
  "Process a single plan artifact and return implementation result.
   Pure function that coordinates implementation for one plan."
  [plan-artifact llm-backend context]
  (let [plan-content (extract-artifact-content plan-artifact)
        task (task/create-task
              {:task/id (random-uuid)
               :task/type :implement
               :task/title "Implement from plan"
               :task/description (str plan-content)
               :task/status :pending})
        result (run-agent-with-loop
                #(agent/create-implementer {:llm-backend llm-backend})
                task context 5)]
    (if (:success result)
      {:artifact (:artifact result)
       :metrics (:metrics result)}
      {:error {:type :implement-failed
               :message (or (:error result) "Implementation failed")}
       :metrics (:metrics result)})))

(defn- code-artifact->standard-artifact
  "Convert a CodeArtifact to standard artifact format."
  [code-artifact]
  (build-standard-artifact
   :code
   code-artifact
   {:phase :implement
    :language (:code/language code-artifact)
    :file-count (count (:code/files code-artifact))}))

(defrecord ImplementPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [plan-artifacts (filter-artifacts-by-type workflow-state :plan)
          artifact-store (:artifact-store context)
          results (map #(process-implementation-plan % llm-backend context)
                       plan-artifacts)
          artifacts (keep (fn [r]
                            (when-let [art (:artifact r)]
                              (let [standard-art (code-artifact->standard-artifact art)]
                                (persist-artifact artifact-store standard-art)
                                standard-art)))
                          results)
          errors (keep :error results)
          metrics (reduce (fn [m r] (merge-with + m (:metrics r)))
                          {:tokens 0 :cost-usd 0.0 :duration-ms 0}
                          results)]
      {:success? (empty? errors)
       :artifacts artifacts
       :errors errors
       :metrics metrics}))

  (can-execute? [_this phase]
    (= phase :implement))

  (get-phase-requirements [_this _phase]
    {:required-artifacts [:plan]
     :optional-artifacts [:design]}))

(defn- process-verification-for-code
  "Process verification (test generation) for a single code artifact.
   Pure function that coordinates test generation."
  [code-artifact llm-backend context]
  (let [artifact-id (extract-artifact-id code-artifact)
        code-content (extract-artifact-content code-artifact)
        task (task/create-task
              {:task/id (random-uuid)
               :task/type :test
               :task/title "Generate tests"
               :task/description (str "Generate tests for code artifact " artifact-id)
               :task/status :pending
               :task/inputs [artifact-id]})
        test-context (assoc context :code-artifact code-content)
        result (run-agent-with-loop
                #(agent/create-tester {:llm-backend llm-backend})
                task
                (merge test-context {:invoke-args {:code code-content}})
                3)]
    (if (:success result)
      {:artifact (:artifact result)
       :parent-id artifact-id
       :metrics (:metrics result)}
      {:error {:type :verify-failed
               :message (or (:error result) "Verification failed")}
       :metrics (:metrics result)})))

(defrecord VerifyPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [code-artifacts (filter-artifacts-by-type workflow-state :code)
          artifact-store (:artifact-store context)
          results (map #(process-verification-for-code % llm-backend context)
                       code-artifacts)
          artifacts (keep (fn [r]
                            (when-let [test-artifact (:artifact r)]
                              (let [parent-id (:parent-id r)
                                    standard-artifact (build-standard-artifact
                                                       :test
                                                       test-artifact
                                                       {:phase :verify
                                                        :parents [parent-id]})]
                                (persist-and-link-artifact artifact-store standard-artifact parent-id)
                                standard-artifact)))
                          results)
          errors (keep :error results)
          metrics (reduce (fn [m r] (merge-with + m (:metrics r)))
                          {:tokens 0 :cost-usd 0.0 :duration-ms 0}
                          results)]
      {:success? (empty? errors)
       :artifacts artifacts
       :errors errors
       :metrics metrics}))

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
    :release (release/->ReleasePhaseExecutor)
    ;; Other phases return nil - they may need different executors
    nil))

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
