(ns ai.miniforge.loop.outer
  "Outer loop state machine implementation.
   Manages the SDLC phases: spec -> plan -> design -> implement -> verify -> review -> release -> observe

   NOTE: This is a P1 stub implementation. Full implementation in future iteration.

   Layer 0: Phase definitions and state transitions
   Layer 1: Loop state management
   Layer 2: Phase execution (stub)"
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Phase definitions

(def phases
  "Ordered sequence of outer loop phases."
  [:spec :plan :design :implement :verify :review :release :observe])

(def phase-definitions
  "Phase metadata definitions."
  {:spec     {:phase/id :spec
              :phase/description "Specification received and validated"
              :phase/agent nil  ; No agent, external input
              :phase/artifacts [:spec]
              :phase/requires []}

   :plan     {:phase/id :plan
              :phase/description "Create implementation plan from spec"
              :phase/agent :planner
              :phase/artifacts [:plan]
              :phase/requires [:spec]}

   :design   {:phase/id :design
              :phase/description "Create architecture and design decisions"
              :phase/agent :architect
              :phase/artifacts [:adr :design-doc]
              :phase/requires [:plan]}

   :implement {:phase/id :implement
               :phase/description "Generate code artifacts"
               :phase/agent :implementer
               :phase/artifacts [:code]
               :phase/requires [:design]}

   :verify   {:phase/id :verify
              :phase/description "Run tests and validation"
              :phase/agent :tester
              :phase/artifacts [:test :test-report]
              :phase/requires [:code]}

   :review   {:phase/id :review
              :phase/description "Code review and quality checks"
              :phase/agent :reviewer
              :phase/artifacts [:review]
              :phase/requires [:code :test-report]}

   :release  {:phase/id :release
              :phase/description "Build and deploy artifacts"
              :phase/agent :release
              :phase/artifacts [:manifest :image]
              :phase/requires [:review]}

   :observe  {:phase/id :observe
              :phase/description "Monitor deployment and collect telemetry"
              :phase/agent :sre
              :phase/artifacts [:telemetry]
              :phase/requires [:release]}})

(defn phase-index
  "Get the index of a phase in the sequence."
  [phase]
  (.indexOf phases phase))

(defn next-phase
  "Get the next phase after the given phase, or nil if at end."
  [phase]
  (let [idx (phase-index phase)]
    (when (< idx (dec (count phases)))
      (nth phases (inc idx)))))

(defn prev-phase
  "Get the previous phase before the given phase, or nil if at start."
  [phase]
  (let [idx (phase-index phase)]
    (when (pos? idx)
      (nth phases (dec idx)))))

(defn can-advance?
  "Check if advancement from current phase is possible.
   Stub: always returns true for now."
  [_loop-state]
  true)

(defn can-rollback?
  "Check if rollback to target phase is valid."
  [loop-state target-phase]
  (let [current (get loop-state :loop/phase :spec)
        current-idx (phase-index current)
        target-idx (phase-index target-phase)]
    (and (>= target-idx 0)
         (< target-idx current-idx))))

;------------------------------------------------------------------------------ Layer 1
;; Outer loop state management

(defn create-outer-loop
  "Create a new outer loop state.

   Arguments:
   - spec - Map with :spec/id and spec content
   - context - Optional context map with :config, :budget, etc."
  [spec context]
  {:loop/id (random-uuid)
   :loop/type :outer
   :loop/phase :spec
   :loop/spec {:spec/id (or (:spec/id spec) (random-uuid))}
   :loop/artifacts {}
   :loop/history [{:phase :spec
                   :timestamp (java.util.Date.)
                   :outcome :entered}]
   :loop/config (get context :config {})
   :loop/created-at (java.util.Date.)
   :loop/updated-at (java.util.Date.)})

(defn get-current-phase
  "Get the current phase of the outer loop."
  [loop-state]
  (:loop/phase loop-state))

(defn get-phase-definition
  "Get the definition for a phase."
  [phase]
  (get phase-definitions phase))

(defn add-history-entry
  "Add an entry to the loop history."
  [loop-state phase outcome & {:keys [message data]}]
  (update loop-state :loop/history conj
          (cond-> {:phase phase
                   :timestamp (java.util.Date.)
                   :outcome outcome}
            message (assoc :message message)
            data (assoc :data data))))

(defn set-phase
  "Set the current phase."
  [loop-state phase]
  (-> loop-state
      (assoc :loop/phase phase)
      (assoc :loop/updated-at (java.util.Date.))))

(defn add-artifact
  "Add an artifact reference to the loop state."
  [loop-state artifact-type artifact-id]
  (assoc-in loop-state [:loop/artifacts artifact-type] artifact-id))

;------------------------------------------------------------------------------ Layer 1.5
;; Result helpers

(defn phase-succeeded?
  "Check if a phase result indicates success (supports both :success? and :status patterns)."
  [result]
  (or (:success? result)
      (= :success (:status result))))

;; Logging helpers

(defn log-phase
  "Log a phase event at the specified level, only when logger is available."
  [logger level event-kw phase message & [extra-data]]
  (when logger
    (let [data (cond-> {:phase phase} extra-data (merge extra-data))
          entry {:message message :data data}]
      (case level
        :info (log/info logger :loop event-kw entry)
        :warn (log/warn logger :loop event-kw entry)
        :error (log/error logger :loop event-kw entry)))))

;------------------------------------------------------------------------------ Layer 2
;; Phase execution (stubs)

(defn advance-phase
  "Advance to the next phase.
   Stub implementation - just transitions without running inner loops.

   Returns updated loop state or nil if cannot advance."
  [loop-state context]
  (let [logger (:logger context)
        current (:loop/phase loop-state)
        next (next-phase current)]
    (log-phase logger :info :outer/phase-completed current "Phase completed")
    (if (and next (can-advance? loop-state))
      (do
        (log-phase logger :info :outer/phase-entered next "Entering phase")
        (-> loop-state
            (add-history-entry current :completed)
            (set-phase next)
            (add-history-entry next :entered)))
      ;; Cannot advance or at end
      (log-phase logger :warn :outer/phase-failed current "Cannot advance phase"
                 {:next next}))))

(defn rollback-phase
  "Rollback to a previous phase.
   Stub implementation - just transitions without cleanup.

   Returns updated loop state or nil if invalid rollback."
  [loop-state target-phase context]
  (let [logger (:logger context)
        current (:loop/phase loop-state)]
    (if (can-rollback? loop-state target-phase)
      (do
        (log-phase logger :warn :outer/phase-failed current "Rolling back"
                   {:to target-phase})
        (-> loop-state
            (add-history-entry current :rolled-back
                               :data {:target target-phase})
            (set-phase target-phase)
            (add-history-entry target-phase :entered
                               :message "Entered via rollback")))
      (do
        (log-phase logger :error :outer/phase-failed current "Invalid rollback target"
                   {:target target-phase})
        nil))))

(defn run-phase
  "Run the current phase using the appropriate agent.

   Delegates to the phase interceptor registry for real execution.
   Falls back to mock success if no phase executor is available.

   Arguments:
   - loop-state: Current outer loop state
   - context: Execution context map with :logger, :phase-executor, :event-stream, etc.

   Returns:
   {:success? boolean
    :artifacts [artifact-id...]
    :errors [...] (if failure)}"
  [loop-state context]
  (let [logger (:logger context)
        phase (:loop/phase loop-state)
        definition (get-phase-definition phase)]
    (log-phase logger :info :outer/phase-entered phase "Running phase"
               {:agent (:phase/agent definition)})
    (if-let [phase-executor (:phase-executor context)]
      ;; Real execution: delegate to the phase executor
      (try
        (let [result (phase-executor phase loop-state context)]
          (if (phase-succeeded? result)
            (do
              (log-phase logger :info :outer/phase-completed phase "Phase completed successfully")
              result)
            (do
              (log-phase logger :warn :outer/phase-failed phase "Phase failed"
                         {:error (:error result)})
              result)))
        (catch Exception e
          (log-phase logger :error :outer/phase-failed phase "Phase execution error"
                     {:error (ex-message e)})
          (response/failure (ex-message e) {:data {:phase phase}})))
      ;; No executor: mock success (development/testing)
      (response/success {:artifacts (mapv (fn [type] {:type type :id (random-uuid)})
                                          (:phase/artifacts definition))
                         :phase phase}))))

(defn is-complete?
  "Check if the outer loop has completed all phases."
  [loop-state]
  (= (:loop/phase loop-state) :observe))

(defn run-outer-loop
  "Run the outer loop through all phases.
   Stub implementation - advances through phases without real execution.

   Returns:
   {:success? boolean
    :phase keyword (final phase)
    :artifacts map
    :history [...]}"
  [loop-state context]
  (let [logger (:logger context)]
    (log-phase logger :info :outer/phase-entered :outer-loop "Starting outer loop"
               {:spec-id (get-in loop-state [:loop/spec :spec/id])})
    (loop [state loop-state]
      (if (is-complete? state)
        ;; Complete
        (do
          (log-phase logger :info :outer/workflow-completed :outer-loop "Outer loop completed"
                     {:artifacts (keys (:loop/artifacts state))})
          (response/success {:phase (:loop/phase state)
                            :artifacts (:loop/artifacts state)
                            :history (:loop/history state)
                            :final-state state}))
        ;; Run current phase and advance
        (let [result (run-phase state context)
              result-data (or (:output result) result)]
          (if (phase-succeeded? result)
            ;; Record artifacts and advance
            (let [updated (reduce (fn [s {:keys [type id]}]
                                    (add-artifact s type id))
                                  state
                                  (:artifacts result-data))
                  advanced (advance-phase updated context)]
              (if advanced
                (recur advanced)
                ;; Couldn't advance
                (response/failure "Failed to advance phase"
                                  {:data {:phase (:loop/phase state)
                                          :artifacts (:loop/artifacts state)
                                          :history (:loop/history state)}})))
            ;; Phase failed
            (response/failure (or (:error result-data) "Phase execution failed")
                              {:data {:phase (:loop/phase state)
                                      :artifacts (:loop/artifacts state)
                                      :history (:loop/history state)}})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create outer loop
  (def spec {:spec/id (random-uuid)
             :description "Implement user authentication"})

  (def outer-loop (create-outer-loop spec {}))

  ;; Check phase
  (get-current-phase outer-loop)
  ;; => :spec

  ;; Advance phase
  (def advanced (advance-phase outer-loop {:logger nil}))
  (get-current-phase advanced)
  ;; => :plan

  ;; Get phase definition
  (get-phase-definition :implement)
  ;; => {:phase/id :implement, :phase/agent :implementer, ...}

  ;; Run full loop (stub)
  (def result (run-outer-loop outer-loop {:logger nil}))
  (:success? result)
  ;; => true

  (:phase result)
  ;; => :observe

  ;; Rollback
  (def at-implement (-> outer-loop
                        (advance-phase {})
                        (advance-phase {})
                        (advance-phase {})))
  (get-current-phase at-implement)
  ;; => :implement

  (def rolled-back (rollback-phase at-implement :plan {}))
  (get-current-phase rolled-back)
  ;; => :plan

  :leave-this-here)
