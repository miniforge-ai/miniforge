(ns ai.miniforge.workflow.core
  "Core workflow implementation.
   Manages SDLC phase execution: Plan → Design → Implement → Verify → Review → Release → Observe"
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

;------------------------------------------------------------------------------ Layer 3
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
          planner (agent/create-planner {:llm-backend llm-backend})
          generate-fn (fn [_task _ctx]
                        (let [result (agent/invoke planner task context)]
                          {:artifact (:artifact result)
                           :tokens (or (:tokens result) 0)}))
          result (loop/run-simple task generate-fn
                                  (merge context {:max-iterations 3}))]
      (if (:success result)
        ;; Build standard artifact format for plan
        (let [plan-artifact (:artifact result)
              plan-id (or (:plan/id plan-artifact) (random-uuid))
              standard-artifact (artifact/build-artifact
                                 {:id plan-id
                                  :type :plan
                                  :version "1.0.0"
                                  :content plan-artifact
                                  :metadata {:phase :plan
                                             :spec-title (:title spec)}})]
          ;; Persist to store if available
          (when artifact-store
            (try
              (artifact/save! artifact-store standard-artifact)
              (catch Exception _e nil)))
          {:success? true
           :artifacts [standard-artifact]
           :errors []
           :metrics (:metrics result)})
        ;; Planning failed
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

(defn- code-artifact->standard-artifact
  "Convert a CodeArtifact to standard artifact format and persist to store."
  [code-artifact artifact-store phase]
  (let [artifact-id (or (:code/id code-artifact) (random-uuid))
        standard-artifact (artifact/build-artifact
                           {:id artifact-id
                            :type :code
                            :version "1.0.0"
                            :content code-artifact
                            :metadata {:phase phase
                                       :language (:code/language code-artifact)
                                       :file-count (count (:code/files code-artifact))}})]
    ;; Persist to store if available
    (when artifact-store
      (try
        (artifact/save! artifact-store standard-artifact)
        (catch Exception _e
          ;; Log but don't fail - persistence is optional
          nil)))
    standard-artifact))

(defrecord ImplementPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [plan-artifacts (->> (:workflow/artifacts workflow-state)
                              (filter #(or (= :plan (:type %))
                                           (= :plan (:artifact/type %)))))
          artifact-store (:artifact-store context)
          results (atom {:artifacts [] :errors [] :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})]

      ;; Execute each implementation task from the plan
      (doseq [plan-artifact plan-artifacts]
        (let [plan-content (or (:content plan-artifact)
                               (:artifact/content plan-artifact))
              task (task/create-task
                    {:task/id (random-uuid)
                     :task/type :implement
                     :task/title "Implement from plan"
                     :task/description (str plan-content)
                     :task/status :pending})
              implementer (agent/create-implementer {:llm-backend llm-backend})
              generate-fn (fn [_task _ctx]
                            (let [result (agent/invoke implementer task context)]
                              {:artifact (:artifact result)
                               :tokens (or (:tokens result) 0)}))
              result (loop/run-simple task generate-fn
                                      (merge context {:max-iterations 5}))]

          (if (:success result)
            ;; Convert CodeArtifact to standard format and persist
            (let [code-artifact (:artifact result)
                  standard-artifact (code-artifact->standard-artifact
                                     code-artifact artifact-store :implement)]
              (swap! results update :artifacts conj standard-artifact))
            ;; Record error
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

(defn- extract-code-content
  "Extract code content from either old or new artifact format."
  [artifact]
  (or
   ;; New standard format: {:artifact/type :code :artifact/content {...}}
   (:artifact/content artifact)
   ;; Old format: {:type :code :content {...}}
   (:content artifact)))

(defn- extract-artifact-id
  "Extract artifact ID from either old or new format."
  [artifact]
  (or
   ;; New standard format
   (:artifact/id artifact)
   ;; Old format - try to get from content
   (get-in artifact [:artifact/content :code/id])
   (get-in artifact [:content :code/id])
   ;; Fallback
   (random-uuid)))

(defrecord VerifyPhaseExecutor [llm-backend]
  proto/PhaseExecutor

  (execute-phase [_this workflow-state context]
    (let [;; Handle both old and new artifact format
          code-artifacts (->> (:workflow/artifacts workflow-state)
                              (filter #(or (= :code (:type %))
                                           (= :code (:artifact/type %)))))
          artifact-store (:artifact-store context)
          results (atom {:artifacts [] :errors [] :metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}})]

      ;; Generate and run tests for each code artifact
      (doseq [code-artifact code-artifacts]
        (let [artifact-id (extract-artifact-id code-artifact)
              code-content (extract-code-content code-artifact)
              task (task/create-task
                    {:task/id (random-uuid)
                     :task/type :test
                     :task/title "Generate tests"
                     :task/description (str "Generate tests for code artifact " artifact-id)
                     :task/status :pending
                     :task/inputs [artifact-id]})
              tester (agent/create-tester {:llm-backend llm-backend})
              test-context (assoc context :code-artifact code-content)
              generate-fn (fn [_task _ctx]
                            (let [result (agent/invoke tester {:code code-content} test-context)]
                              {:artifact (:artifact result)
                               :tokens (or (:tokens result) 0)}))
              result (loop/run-simple task generate-fn
                                      (merge context {:max-iterations 3}))]

          (if (:success result)
            ;; Build standard artifact format for test
            (let [test-artifact (:artifact result)
                  test-id (or (:test/id test-artifact) (random-uuid))
                  standard-artifact (artifact/build-artifact
                                     {:id test-id
                                      :type :test
                                      :version "1.0.0"
                                      :content test-artifact
                                      :parents [artifact-id]
                                      :metadata {:phase :verify}})]
              ;; Persist and link
              (when artifact-store
                (try
                  (artifact/save! artifact-store standard-artifact)
                  (artifact/link! artifact-store artifact-id test-id)
                  (catch Exception _e nil)))
              (swap! results update :artifacts conj standard-artifact))
            ;; Record error
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
    :release (release/->ReleasePhaseExecutor)
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
