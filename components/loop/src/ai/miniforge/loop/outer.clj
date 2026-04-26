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

(ns ai.miniforge.loop.outer
  "Outer loop state machine implementation.
   Manages the SDLC phases: spec -> plan -> design -> implement -> verify -> review -> release -> observe

   NOTE: This is a P1 stub implementation. Full implementation in future iteration.

   Layer 0: Phase definitions and state transitions
   Layer 1: Loop state management
   Layer 2: Phase execution (stub)"
  (:require
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.loop.messages :as loop-messages]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Phase definitions

(def phases
  "Ordered sequence of outer loop phases."
  [:spec :plan :design :implement :verify :review :release :observe])

(def ^:private initial-phase
  "Initial outer-loop phase."
  :spec)

(def ^:private final-phase
  "Terminal outer-loop phase."
  :observe)

(def phase-definitions
  "Phase metadata definitions."
  {:spec     {:phase/id :spec
              :phase/description (loop-messages/t :outer/spec-description)
              :phase/agent nil  ; No agent, external input
              :phase/artifacts [:spec]
              :phase/requires []}

   :plan     {:phase/id :plan
              :phase/description (loop-messages/t :outer/plan-description)
              :phase/agent :planner
              :phase/artifacts [:plan]
              :phase/requires [:spec]}

   :design   {:phase/id :design
              :phase/description (loop-messages/t :outer/design-description)
              :phase/agent :architect
              :phase/artifacts [:adr :design-doc]
              :phase/requires [:plan]}

   :implement {:phase/id :implement
               :phase/description (loop-messages/t :outer/implement-description)
               :phase/agent :implementer
               :phase/artifacts [:code]
               :phase/requires [:design]}

   :verify   {:phase/id :verify
              :phase/description (loop-messages/t :outer/verify-description)
              :phase/agent :tester
              :phase/artifacts [:test :test-report]
              :phase/requires [:code]}

   :review   {:phase/id :review
              :phase/description (loop-messages/t :outer/review-description)
              :phase/agent :reviewer
              :phase/artifacts [:review]
              :phase/requires [:code :test-report]}

   :release  {:phase/id :release
              :phase/description (loop-messages/t :outer/release-description)
              :phase/agent :release
              :phase/artifacts [:manifest :image]
              :phase/requires [:review]}

   :observe  {:phase/id :observe
              :phase/description (loop-messages/t :outer/observe-description)
              :phase/agent :sre
              :phase/artifacts [:telemetry]
              :phase/requires [:release]}})

(defn- rollback-event
  "Build the rollback event for a target phase."
  [target-phase]
  (keyword "loop" (str "rollback-to-" (name target-phase))))

(defn- rollback-transitions
  "Build rollback transitions for a phase."
  [phase]
  (->> phases
       (take-while #(not= % phase))
       (map (fn [target-phase]
              [(rollback-event target-phase) target-phase]))
       (into {})))

(defn- phase-transition-map
  "Build the transition map for a phase state."
  [phase]
  (let [next-phase (second (drop-while #(not= % phase) phases))
        advance-transition (when next-phase {:loop/advance next-phase})
        rollback-transition (rollback-transitions phase)
        transitions (merge advance-transition rollback-transition)]
    (cond-> {}
      (seq transitions) (assoc :on transitions)
      (= phase final-phase) (assoc :type :final))))

(def ^:private phase-machine-config
  "Outer-loop phase machine configuration."
  {:fsm/id :outer-loop-phases
   :fsm/initial initial-phase
   :fsm/context {}
   :fsm/states (into {}
                     (map (fn [phase]
                            [phase (phase-transition-map phase)]))
                     phases)})

(def ^:private phase-machine
  "Compiled outer-loop phase machine."
  (fsm/define-machine phase-machine-config))

(defn- phase-fsm-state
  "Get the authoritative phase machine snapshot."
  [loop-state]
  (get loop-state :loop/fsm-state (fsm/initialize phase-machine)))

(defn- current-phase
  "Get the authoritative phase from the FSM snapshot."
  [loop-state]
  (fsm/current-state (phase-fsm-state loop-state)))

(defn- transition-phase
  "Apply an outer-loop phase transition.

   Returns updated loop state or nil if the transition is forbidden."
  [loop-state event]
  (let [current-state (phase-fsm-state loop-state)
        transitioned (fsm/transition phase-machine current-state event)
        next-phase (fsm/current-state transitioned)]
    (when (not= (fsm/current-state current-state) next-phase)
      (assoc loop-state
             :loop/fsm-state transitioned
             :loop/phase next-phase
             :loop/updated-at (java.util.Date.)))))

(defn valid-phase-transition?
  "Check whether a phase transition event is allowed."
  [phase event]
  (some? (transition-phase {:loop/fsm-state {:_state phase}
                            :loop/phase phase}
                           event)))

;------------------------------------------------------------------------------ Layer 1
;; Outer loop state management

(defn create-outer-loop
  "Create a new outer loop state.

   Arguments:
   - spec - Map with :spec/id and spec content
   - context - Optional context map with :config, :budget, etc."
  [spec context]
  (let [created-at (java.util.Date.)
        fsm-state (fsm/initialize phase-machine)
        loop-phase (fsm/current-state fsm-state)]
    {:loop/id (random-uuid)
     :loop/type :outer
     :loop/fsm-state fsm-state
     :loop/phase loop-phase
     :loop/spec {:spec/id (or (:spec/id spec) (random-uuid))}
     :loop/artifacts {}
     :loop/history [{:phase loop-phase
                     :timestamp created-at
                     :outcome :entered}]
     :loop/config (get context :config {})
     :loop/created-at created-at
     :loop/updated-at created-at}))

(defn get-current-phase
  "Get the current phase of the outer loop."
  [loop-state]
  (current-phase loop-state))

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
      (response/success? result)))

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
        current (current-phase loop-state)
        advanced-state (transition-phase loop-state :loop/advance)
        next-phase (some-> advanced-state current-phase)]
    (log-phase logger :info :outer/phase-completed current (loop-messages/t :outer/phase-completed))
    (if advanced-state
      (do
        (log-phase logger :info :outer/phase-entered next-phase (loop-messages/t :outer/phase-entered))
        (-> advanced-state
            (add-history-entry current :completed)
            (add-history-entry next-phase :entered)))
      ;; Cannot advance or at end
      (log-phase logger :warn :outer/phase-failed current (loop-messages/t :outer/cannot-advance-phase)
                 {:next next-phase}))))

(defn rollback-phase
  "Rollback to a previous phase.
   Stub implementation - just transitions without cleanup.

   Returns updated loop state or nil if invalid rollback."
  [loop-state target-phase context]
  (let [logger (:logger context)
        current (current-phase loop-state)
        rollback-state (transition-phase loop-state (rollback-event target-phase))]
    (if rollback-state
      (do
        (log-phase logger :warn :outer/phase-failed current (loop-messages/t :outer/rolling-back)
                   {:to target-phase})
        (-> rollback-state
            (add-history-entry current :rolled-back
                               :data {:target target-phase})
            (add-history-entry target-phase :entered
                               :message (loop-messages/t :outer/entered-via-rollback))))
      (do
        (log-phase logger :error :outer/phase-failed current (loop-messages/t :outer/invalid-rollback-target)
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
    (log-phase logger :info :outer/phase-entered phase (loop-messages/t :outer/running-phase)
               {:agent (:phase/agent definition)})
    (if-let [phase-executor (:phase-executor context)]
      ;; Real execution: delegate to the phase executor
      (try
        (let [result (phase-executor phase loop-state context)]
          (if (phase-succeeded? result)
            (do
              (log-phase logger :info :outer/phase-completed phase (loop-messages/t :outer/phase-completed-successfully))
              result)
            (do
              (log-phase logger :warn :outer/phase-failed phase (loop-messages/t :outer/phase-failed)
                         {:error (:error result)})
              result)))
        (catch Exception e
          (log-phase logger :error :outer/phase-failed phase (loop-messages/t :outer/phase-execution-error)
                     {:error (ex-message e)})
          (response/failure (ex-message e) {:data {:phase phase}})))
      ;; No executor: mock success (development/testing)
      (response/success {:artifacts (mapv (fn [type] {:type type :id (random-uuid)})
                                          (:phase/artifacts definition))
                         :phase phase}))))

(defn is-complete?
  "Check if the outer loop has completed all phases."
  [loop-state]
  (fsm/final? phase-machine (phase-fsm-state loop-state)))

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
    (log-phase logger :info :outer/phase-entered :outer-loop (loop-messages/t :outer/starting-loop)
               {:spec-id (get-in loop-state [:loop/spec :spec/id])})
    (loop [state loop-state]
      (if (is-complete? state)
        ;; Complete
        (do
          (log-phase logger :info :outer/workflow-completed :outer-loop (loop-messages/t :outer/completed)
                     {:artifacts (keys (:loop/artifacts state))})
          (response/success {:phase (:loop/phase state)
                            :artifacts (:loop/artifacts state)
                            :history (:loop/history state)
                            :final-state state}))
        ;; Run current phase and advance
        (let [result (run-phase state context)
              result-data (get result :output result)]
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
                (response/failure (loop-messages/t :outer/failed-to-advance-phase)
                                  {:data {:phase (:loop/phase state)
                                          :artifacts (:loop/artifacts state)
                                          :history (:loop/history state)}})))
            ;; Phase failed
            (response/failure (get result-data :error (loop-messages/t :outer/phase-execution-failed))
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
