(ns ai.miniforge.loop.outer
  "Outer loop state machine implementation.
   Manages the SDLC phases: spec -> plan -> design -> implement -> verify -> review -> release -> observe

   NOTE: This is a P1 stub implementation. Full implementation in future iteration.

   Layer 0: Phase definitions and state transitions
   Layer 1: Loop state management
   Layer 2: Phase execution (stub)"
  (:require
   [ai.miniforge.logging.interface :as log]))

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
   :loop/config (or (:config context) {})
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
    (when logger
      (log/info logger :loop :outer/phase-completed
                {:message "Phase completed"
                 :data {:phase current}}))
    (if (and next (can-advance? loop-state))
      (do
        (when logger
          (log/info logger :loop :outer/phase-entered
                    {:message "Entering phase"
                     :data {:phase next}}))
        (-> loop-state
            (add-history-entry current :completed)
            (set-phase next)
            (add-history-entry next :entered)))
      ;; Cannot advance or at end
      (when logger
        (log/warn logger :loop :outer/phase-failed
                  {:message "Cannot advance phase"
                   :data {:current current :next next}})))))

(defn rollback-phase
  "Rollback to a previous phase.
   Stub implementation - just transitions without cleanup.

   Returns updated loop state or nil if invalid rollback."
  [loop-state target-phase context]
  (let [logger (:logger context)
        current (:loop/phase loop-state)]
    (if (can-rollback? loop-state target-phase)
      (do
        (when logger
          (log/warn logger :loop :outer/phase-failed
                    {:message "Rolling back"
                     :data {:from current :to target-phase}}))
        (-> loop-state
            (add-history-entry current :rolled-back
                               :data {:target target-phase})
            (set-phase target-phase)
            (add-history-entry target-phase :entered
                               :message "Entered via rollback")))
      (do
        (when logger
          (log/error logger :loop :outer/phase-failed
                     {:message "Invalid rollback target"
                      :data {:current current :target target-phase}}))
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
    (when logger
      (log/info logger :loop :outer/phase-entered
                {:message "Running phase"
                 :data {:phase phase
                        :agent (:phase/agent definition)}}))
    (if-let [phase-executor (:phase-executor context)]
      ;; Real execution: delegate to the phase executor
      (try
        (let [result (phase-executor phase loop-state context)]
          (if (:success? result)
            (do
              (when logger
                (log/info logger :loop :outer/phase-completed
                          {:message "Phase completed successfully"
                           :data {:phase phase}}))
              result)
            (do
              (when logger
                (log/warn logger :loop :outer/phase-failed
                          {:message "Phase failed"
                           :data {:phase phase :error (:error result)}}))
              result)))
        (catch Exception e
          (when logger
            (log/error logger :loop :outer/phase-failed
                       {:message "Phase execution error"
                        :data {:phase phase :error (ex-message e)}}))
          {:success? false
           :phase phase
           :errors [{:type :execution-error
                     :message (ex-message e)}]}))
      ;; No executor: mock success (development/testing)
      {:success? true
       :artifacts (mapv (fn [type] {:type type :id (random-uuid)})
                        (:phase/artifacts definition))
       :phase phase})))

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
    (when logger
      (log/info logger :loop :outer/phase-entered
                {:message "Starting outer loop"
                 :data {:spec-id (get-in loop-state [:loop/spec :spec/id])}}))
    (loop [state loop-state]
      (if (is-complete? state)
        ;; Complete
        (do
          (when logger
            (log/info logger :loop :outer/workflow-completed
                      {:message "Outer loop completed"
                       :data {:artifacts (keys (:loop/artifacts state))}}))
          {:success? true
           :phase (:loop/phase state)
           :artifacts (:loop/artifacts state)
           :history (:loop/history state)
           :final-state state})
        ;; Run current phase and advance
        (let [result (run-phase state context)]
          (if (:success? result)
            ;; Record artifacts and advance
            (let [updated (reduce (fn [s {:keys [type id]}]
                                    (add-artifact s type id))
                                  state
                                  (:artifacts result))
                  advanced (advance-phase updated context)]
              (if advanced
                (recur advanced)
                ;; Couldn't advance
                {:success? false
                 :phase (:loop/phase state)
                 :artifacts (:loop/artifacts state)
                 :history (:loop/history state)
                 :error "Failed to advance phase"}))
            ;; Phase failed
            {:success? false
             :phase (:loop/phase state)
             :artifacts (:loop/artifacts state)
             :history (:loop/history state)
             :error (or (:error result) "Phase execution failed")}))))))

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
