(ns ai.miniforge.workflow.protocols.impl.workflow
  "Implementation functions for Workflow protocol.
   Pure functions organized in layers."
  (:require
   [ai.miniforge.workflow.interface.protocols.workflow-observer :as observer-proto]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Phase definitions and transitions

(def phases
  "Ordered SDLC phases."
  [:spec :plan :design :implement :verify :review :release :observe :done])

(def phase-transitions
  "Valid phase transitions."
  {:spec      #{:plan}
   :plan      #{:design :implement}
   :design    #{:implement}
   :implement #{:verify}
   :verify    #{:review :implement}
   :review    #{:release :implement}
   :release   #{:observe}
   :observe   #{:done :implement}
   :done      #{}})

;------------------------------------------------------------------------------ Layer 1
;; Pure state transformation functions

(defn create-workflow-state
  "Create initial workflow state."
  [workflow-id spec]
  {:workflow/id workflow-id
   :workflow/spec spec
   :workflow/phase :spec
   :workflow/status :pending
   :workflow/artifacts []
   :workflow/tool-invocations []
   :workflow/errors []
   :workflow/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
   :workflow/history []
   :workflow/rollback-count 0
   :workflow/created-at (System/currentTimeMillis)})

(defn record-phase-transition
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
  (contains? (get phase-transitions from-phase #{}) to-phase))

(defn next-phase
  "Determine next phase based on current phase and result."
  [current-phase success?]
  (case current-phase
    :spec :plan
    :plan :implement
    :design :implement
    :implement :verify
    :verify (if success? :review :implement)
    :review (if success? :release :implement)
    :release :observe
    :observe :done
    :done :done))

;------------------------------------------------------------------------------ Layer 2
;; Protocol implementation functions

(defn start-workflow-impl
  "Start a new workflow from specification.
   Returns [workflow-id updated-workflows-atom-value]"
  [workflows observers logger spec context]
  (let [workflow-id (random-uuid)
        state (create-workflow-state workflow-id spec)
        new-workflows (assoc @workflows workflow-id state)]

    (log/info logger :workflow :workflow/started
              {:data {:workflow-id workflow-id
                      :spec-title (:title spec)}})

    ;; Notify observers via protocol
    (doseq [obs @observers]
      (observer-proto/on-phase-start obs workflow-id :spec context))

    [workflow-id new-workflows]))

(defn get-state-impl
  "Get workflow state by ID."
  [workflows workflow-id]
  (get @workflows workflow-id))

(defn advance-impl
  "Advance workflow to next phase based on result."
  [workflows observers logger workflow-id phase-result]
  (when-let [state (get @workflows workflow-id)]
    (let [current-phase (:workflow/phase state)
          success? (:success? phase-result)
          new-phase (next-phase current-phase success?)
          new-artifacts (into (:workflow/artifacts state)
                              (:artifacts phase-result []))
          tool-invocations (into (:workflow/tool-invocations state)
                                 (:tool/invocations phase-result []))
          new-errors (into (:workflow/errors state)
                           (:errors phase-result []))
          new-metrics (merge-with + (:workflow/metrics state)
                                  (:metrics phase-result {}))
          new-state (-> state
                        (assoc :workflow/phase new-phase)
                        (assoc :workflow/status (if (= new-phase :done) :completed :running))
                        (assoc :workflow/artifacts new-artifacts)
                        (assoc :workflow/tool-invocations tool-invocations)
                        (assoc :workflow/errors new-errors)
                        (assoc :workflow/metrics new-metrics)
                        (record-phase-transition current-phase new-phase
                                                 (if success? :success :failure)))
          new-workflows (assoc @workflows workflow-id new-state)]

      (log/info logger :workflow :workflow/phase-advanced
                {:data {:workflow-id workflow-id
                        :from-phase current-phase
                        :to-phase new-phase
                        :success? success?}})

      ;; Notify observers
      (doseq [obs @observers]
        (observer-proto/on-phase-complete obs workflow-id current-phase phase-result)
        (when (= new-phase :done)
          (observer-proto/on-workflow-complete obs workflow-id new-state)))

      [new-state new-workflows])))

(defn rollback-impl
  "Rollback workflow to target phase."
  [workflows observers logger config workflow-id target-phase reason]
  (when-let [state (get @workflows workflow-id)]
    (let [current-phase (:workflow/phase state)
          rollback-count (:workflow/rollback-count state)
          max-rollbacks (:max-rollbacks config)]

      (if (>= rollback-count max-rollbacks)
        ;; Too many rollbacks - fail
        (let [failed-state (-> state
                               (assoc :workflow/status :failed)
                               (update :workflow/errors conj
                                       {:type :max-rollbacks-exceeded
                                        :message "Maximum rollback count exceeded"}))
              new-workflows (assoc @workflows workflow-id failed-state)]
          (log/error logger :workflow :workflow/max-rollbacks
                     {:data {:workflow-id workflow-id
                             :rollback-count rollback-count}})
          [failed-state new-workflows])

        ;; Perform rollback
        (let [new-state (-> state
                            (assoc :workflow/phase target-phase)
                            (update :workflow/rollback-count inc)
                            (record-phase-transition current-phase target-phase reason))
              new-workflows (assoc @workflows workflow-id new-state)]
          (log/warn logger :workflow :workflow/rollback
                    {:data {:workflow-id workflow-id
                            :from-phase current-phase
                            :to-phase target-phase
                            :reason reason}})

          ;; Notify observers
          (doseq [obs @observers]
            (observer-proto/on-rollback obs workflow-id current-phase target-phase reason))

          [new-state new-workflows])))))

(defn complete-impl
  "Mark workflow as complete."
  [workflows observers logger workflow-id]
  (when-let [state (get @workflows workflow-id)]
    (let [new-state (-> state
                        (assoc :workflow/phase :done)
                        (assoc :workflow/status :completed)
                        (assoc :workflow/completed-at (System/currentTimeMillis)))
          new-workflows (assoc @workflows workflow-id new-state)]

      (log/info logger :workflow :workflow/completed
                {:data {:workflow-id workflow-id
                        :duration-ms (- (:workflow/completed-at new-state)
                                        (:workflow/created-at new-state))}})

      ;; Notify observers
      (doseq [obs @observers]
        (observer-proto/on-workflow-complete obs workflow-id new-state))

      [new-state new-workflows])))

(defn fail-impl
  "Mark workflow as failed."
  [workflows observers logger workflow-id error]
  (when-let [state (get @workflows workflow-id)]
    (let [current-phase (:workflow/phase state)
          new-state (-> state
                        (assoc :workflow/status :failed)
                        (update :workflow/errors conj error)
                        (assoc :workflow/failed-at (System/currentTimeMillis)))
          new-workflows (assoc @workflows workflow-id new-state)]

      (log/error logger :workflow :workflow/failed
                 {:data {:workflow-id workflow-id
                         :phase current-phase
                         :error error}})

      ;; Notify observers
      (doseq [obs @observers]
        (observer-proto/on-phase-error obs workflow-id current-phase error))

      [new-state new-workflows])))
