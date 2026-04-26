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

(ns ai.miniforge.workflow.fsm
  "Finite State Machine for workflow execution.

   Uses the fsm component (clj-statecharts wrapper) for state machine implementation.
   Defines explicit states, valid transitions, and guards for workflow execution.

   States:
   - :pending   - Initial state, workflow not started
   - :running   - Workflow is executing
   - :completed - Workflow completed successfully
   - :failed    - Workflow failed
   - :paused    - Workflow execution paused
   - :cancelled - Workflow cancelled by user

   Events:
   - :start   - Begin workflow execution
   - :complete - Mark workflow as completed
   - :fail    - Mark workflow as failed
   - :pause   - Pause execution
   - :resume  - Resume paused execution
   - :cancel  - Cancel workflow"
  (:require
   [clojure.set :as set]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.workflow.definition :as definition]
   [ai.miniforge.workflow.messages :as messages]))

(declare current-state first-phase-index reachable-states unreachable-states)

;; ============================================================================
;; FSM Definition using clj-statecharts
;; ============================================================================

(def redirect-event-namespace
  "Namespace used for redirect events in the compiled execution machine."
  "workflow.event")

(def phase-state-prefix
  "Prefix used for active phase state identifiers."
  "phase")

(def paused-phase-state-prefix
  "Prefix used for paused phase state identifiers."
  "paused-phase")

(defn- phase-state-name
  [prefix index phase]
  (str prefix "-" index "-" (name phase)))

(def workflow-machine-config
  "Workflow execution state machine configuration."
  {:fsm/id :workflow-execution
   :fsm/initial :pending
   :fsm/context {}
   :fsm/states
   {:pending   {:on {:start :running}}
    :running   {:on {:complete :completed
                     :fail :failed
                     :pause :paused
                     :cancel :cancelled}}
    :paused    {:on {:resume :running
                     :cancel :cancelled}}
    :completed {:type :final}
    :failed    {:type :final}
    :cancelled {:type :final}}})

(def workflow-machine
  "Compiled workflow state machine."
  (fsm/define-machine workflow-machine-config))

;------------------------------------------------------------------------------
; Layer 0.5: Compiled execution machine

(defn phase-state-id
  "Build the active execution-machine state id for a pipeline phase."
  [index phase]
  (keyword (phase-state-name phase-state-prefix index phase)))

(defn paused-phase-state-id
  "Build the paused execution-machine state id for a pipeline phase."
  [index phase]
  (keyword (phase-state-name paused-phase-state-prefix index phase)))

(defn redirect-event
  "Build the event keyword used to redirect to a named phase."
  [phase]
  (keyword redirect-event-namespace (str "redirect-to-" (name phase))))

(defn- increment-redirect-count
  [redirect-count]
  (inc (or redirect-count 0)))

(defn- matching-phase-index
  [phase [index config]]
  (when (= phase (:phase config))
    index))

(defn- duplicate-phase?
  [[_phase count]]
  (> count 1))

(defn- unresolved-target-error
  [error-key phase target]
  {:error error-key
   :phase phase
   :target target
   :message (messages/t error-key
                        {:phase phase
                         :target target})})

(defn- unknown-success-target
  [pipeline {:keys [phase on-success]}]
  (when (and on-success
             (nil? (first-phase-index pipeline on-success)))
    (unresolved-target-error :unknown-on-success-target phase on-success)))

(defn- unknown-fail-target
  [pipeline {:keys [phase on-fail]}]
  (when (and on-fail
             (nil? (first-phase-index pipeline on-fail)))
    (unresolved-target-error :unknown-on-fail-target phase on-fail)))

(defn- duplicate-phase-warning
  [duplicate-phases]
  {:warning :duplicate-phase-identifiers
   :phases duplicate-phases
   :message (messages/t :status/duplicate-phases)})

(defn- empty-pipeline-error
  []
  {:error :empty-pipeline
   :message (messages/t :status/empty-pipeline)})

(defn- first-phase-index
  [pipeline phase]
  (some (partial matching-phase-index phase)
        (map-indexed vector pipeline)))

(defn- build-phase-entry
  [index config]
  (let [phase (:phase config)]
    {:index index
     :phase phase
     :config config
     :state-id (phase-state-id index phase)
     :paused-state-id (paused-phase-state-id index phase)}))

(defn- state-target
  [phase-entries index]
  (some-> (get phase-entries index) :state-id))

(defn- redirect-transition
  [phase-entries {:keys [config]}]
  (when-let [target-phase (:on-fail config)]
    (when-let [target-index (first-phase-index phase-entries target-phase)]
      {(redirect-event target-phase)
       {:target (state-target phase-entries target-index)}})))

(defn redirect-event?
  "True when an event keyword represents a workflow phase redirect."
  [event-key]
  (= redirect-event-namespace (namespace event-key)))

(defn- machine-transition-defined?
  [machine state event-key]
  (contains? (get-in machine [:states (current-state state) :on] {})
             event-key))

(defn transition-execution
  "Apply an event to a compiled execution machine snapshot."
  [machine state event]
  (let [event-key (if (keyword? event) event (:type event))
        transition-defined? (machine-transition-defined? machine state event-key)
        next-state (fsm/transition machine state event)]
    (if (and transition-defined?
             (redirect-event? event-key))
      (fsm/update-context next-state update :redirect-count increment-redirect-count)
      next-state)))

(defn- terminal-state-message
  [current-state]
  (messages/t :status/terminal-state-transition {:state current-state}))

(defn- invalid-state-message
  [current-state]
  (messages/t :status/invalid-current-state {:state current-state}))

(defn- no-transition-message
  [current-state event]
  (messages/t :status/no-transition-defined
              {:state current-state
               :event event}))

(defn- build-phase-state
  [phase-entries {:keys [index config state-id paused-state-id]}]
  (let [next-index (when (< (inc index) (count phase-entries))
                     (inc index))
        on-success-index (or (when-let [target-phase (:on-success config)]
                               (first-phase-index phase-entries target-phase))
                             next-index)
        on-fail-index (when-let [target-phase (:on-fail config)]
                        (first-phase-index phase-entries target-phase))
        done-index (first-phase-index phase-entries :done)
        success-target (or (state-target phase-entries on-success-index)
                           :completed)
        failure-target (or (state-target phase-entries on-fail-index)
                           :failed)
        already-done-target (or (state-target phase-entries done-index)
                                :completed)]
    [state-id
     {:on (merge
           {:phase/retry state-id
            :phase/succeed success-target
            :phase/already-done already-done-target
            :phase/fail failure-target
            :pause paused-state-id
            :cancel :cancelled
            :fail :failed
            :complete :completed}
           (redirect-transition phase-entries {:config config}))}]))

(defn- build-paused-state
  [{:keys [state-id paused-state-id]}]
  [paused-state-id
   {:on {:resume state-id
         :cancel :cancelled
         :fail :failed
         :complete :completed}}])

(defn- projection-entry
  [entry paused?]
  [(:state-id entry) {:phase (:phase entry)
                      :index (:index entry)
                      :paused? paused?}])

(defn- paused-projection-entry
  [entry]
  [(:paused-state-id entry)
   {:phase (:phase entry)
    :index (:index entry)
    :paused? true}])

(defn- state-entry
  [entry state-id]
  [state-id entry])

(defn- paused-state-entry
  [entry]
  [(:paused-state-id entry) entry])

(defn- transition-target
  [transition]
  (cond
    (keyword? transition) transition
    (map? transition) (:target transition)
    :else nil))

(defn- state-transition-targets
  [state-config]
  (->> (get state-config :on {})
       vals
       (keep transition-target)
       set))

(defn- build-state-graph
  [states]
  (into {}
        (map (fn [[state-id state-config]]
               [state-id (state-transition-targets state-config)]))
        states))

(defn- bfs-reachable-states
  [state-graph start-state]
  (loop [to-visit [start-state]
         visited #{}]
    (if (empty? to-visit)
      visited
      (let [current-state (first to-visit)
            remaining (rest to-visit)]
        (if (contains? visited current-state)
          (recur remaining visited)
          (recur (concat remaining (get state-graph current-state #{}))
                 (conj visited current-state)))))))

(defn- unreachable-phase-error
  [phases]
  {:error :unreachable-phase-states
   :phases phases
   :message (messages/t :status/unreachable-machine-phases
                        {:phases phases})})

(defn- unreachable-state-error
  [states]
  {:error :unreachable-machine-states
   :states states
   :message (messages/t :status/unreachable-machine-states
                        {:states states})})

(defn compile-execution-machine
  "Compile a per-run execution machine from a workflow pipeline.

   The compiled machine is the authoritative source of truth for workflow
   progression. Coarse lifecycle status and phase/index projections are derived
   from the resulting machine snapshot."
  [workflow]
  (let [pipeline (definition/execution-pipeline workflow)
        phase-entries (mapv build-phase-entry (range) pipeline)
        first-active-state (some-> phase-entries first :state-id)
        no-phase-machine? (empty? phase-entries)
        states (merge
                {:pending {:on (cond-> {:fail :failed
                                        :cancel :cancelled}
                                 no-phase-machine? (assoc :start :running)
                                 first-active-state (assoc :start first-active-state))}
                 :running (when no-phase-machine?
                            {:on {:complete :completed
                                  :fail :failed
                                  :pause :paused
                                  :cancel :cancelled}})
                 :paused (when no-phase-machine?
                           {:on {:resume :running
                                 :cancel :cancelled}})
                 :completed {:type :final}
                 :failed {:type :final}
                 :cancelled {:type :final}}
                (into {} (map (partial build-phase-state phase-entries)) phase-entries)
                (into {} (map build-paused-state) phase-entries))
        compiled-states (into {} (remove (comp nil? val)) states)
        state-graph (build-state-graph compiled-states)
        state->phase (into {}
                           (concat
                            (map #(projection-entry % false) phase-entries)
                            (map paused-projection-entry phase-entries)))
        state->entry (into {}
                           (concat
                            (map #(state-entry % (:state-id %)) phase-entries)
                            (map paused-state-entry phase-entries)))]
    (assoc (fsm/define-machine
            {:fsm/id :workflow-execution
             :fsm/initial :pending
             :fsm/context {:redirect-count 0}
             :fsm/states compiled-states})
           :workflow/phase-entries phase-entries
           :workflow/state->phase state->phase
           :workflow/state->entry state->entry
           :workflow/state-graph state-graph
           :workflow/state-ids (set (keys compiled-states))
           :workflow/initial-state :pending)))

(defn validate-execution-machine
  "Validate that a workflow pipeline can compile into an execution machine."
  [workflow]
  (let [normalized-workflow (definition/ensure-execution-pipeline workflow)
        pipeline (definition/execution-pipeline normalized-workflow)
        duplicate-phases (->> pipeline
                              (map :phase)
                              frequencies
                              (filter duplicate-phase?)
                              (map first)
                              vec)
        unresolved-success (->> pipeline
                                (keep (partial unknown-success-target pipeline))
                                vec)
        unresolved-fail (->> pipeline
                             (keep (partial unknown-fail-target pipeline))
                             vec)
        machine (when (and (seq pipeline)
                           (empty? unresolved-success)
                           (empty? unresolved-fail))
                  (compile-execution-machine normalized-workflow))
        unreachable-state-set (when machine
                                (unreachable-states machine))
        unreachable-phases (when machine
                             (->> (:workflow/phase-entries machine)
                                  (keep (fn [{:keys [phase state-id]}]
                                          (when (contains? unreachable-state-set state-id)
                                            phase)))
                                  vec))
        errors (vec (concat
                     (when (empty? pipeline)
                       [(empty-pipeline-error)])
                     unresolved-success
                     unresolved-fail
                     (when (seq unreachable-phases)
                       [(unreachable-phase-error unreachable-phases)])
                     (when (seq unreachable-state-set)
                       [(unreachable-state-error (vec unreachable-state-set))])))
        warnings (vec (concat
                       (when (seq duplicate-phases)
                         [(duplicate-phase-warning duplicate-phases)])))]
    {:valid? (empty? errors)
     :errors errors
     :warnings warnings
     :machine (when (empty? errors)
                machine)}))

(defn state-graph
  "Return the compiled state graph for a workflow execution machine."
  [machine]
  (:workflow/state-graph machine))

(defn compiled-state-ids
  "Return the set of states defined by a compiled workflow execution machine."
  [machine]
  (:workflow/state-ids machine))

(defn reachable-states
  "Return the set of states reachable in a compiled workflow machine.

   With one arg, traversal starts from the machine's initial state.
   With two args, traversal starts from the provided state id."
  ([machine]
   (reachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (if start-state
     (bfs-reachable-states (state-graph machine) start-state)
     #{})))

(defn unreachable-states
  "Return the set of defined states that are not reachable in a compiled workflow machine."
  ([machine]
   (unreachable-states machine (:workflow/initial-state machine)))
  ([machine start-state]
   (set/difference (compiled-state-ids machine)
                   (reachable-states machine start-state))))

(defn machine-phase-entry
  "Get phase metadata for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->phase (current-state state)]))

(defn machine-active-phase-entry
  "Get the active workflow phase entry for the current compiled machine state."
  [machine state]
  (get-in machine [:workflow/state->entry (current-state state)]))

(defn execution-status
  "Project coarse execution status from a compiled machine snapshot."
  [machine state]
  (let [state-id (current-state state)]
    (cond
      (= :pending state-id) :pending
      (= :completed state-id) :completed
      (= :failed state-id) :failed
      (= :cancelled state-id) :cancelled
      (:paused? (machine-phase-entry machine state)) :paused
      :else :running)))

(defn current-phase-id
  "Project the current phase keyword from a compiled machine snapshot."
  [machine state]
  (:phase (machine-phase-entry machine state)))

(defn current-phase-index
  "Project the current phase index from a compiled machine snapshot."
  [machine state]
  (:index (machine-phase-entry machine state)))

(defn execution-projection
  "Project execution fields from a compiled machine snapshot."
  [machine state]
  (let [ctx (fsm/context state)]
    {:execution/status (execution-status machine state)
     :execution/current-phase (current-phase-id machine state)
     :execution/phase-index (current-phase-index machine state)
     :execution/redirect-count (get ctx :redirect-count 0)}))

(defn initialize-execution
  "Initialize a compiled execution machine."
  [machine]
  (fsm/initialize machine))

(defn start-execution
  "Start a compiled execution machine from :pending."
  [machine state]
  (fsm/transition machine state :start))

;; ============================================================================
;; Legacy API - for backward compatibility with state.clj
;; ============================================================================

(def workflow-states
  "Valid states for workflow execution."
  #{:pending :running :completed :failed :paused :cancelled})

(def workflow-transitions
  "Valid state transitions. Map of [from-state event] -> to-state."
  {[:pending :start]      :running
   [:running :complete]   :completed
   [:running :fail]       :failed
   [:running :pause]      :paused
   [:running :cancel]     :cancelled
   [:paused :resume]      :running
   [:paused :cancel]      :cancelled})

(def terminal-states
  "States that are terminal (no further transitions)."
  #{:completed :failed :cancelled})

;; ============================================================================
;; FSM Operations (legacy API backed by clj-statecharts)
;; ============================================================================

(defn valid-transition?
  "Check if a transition from current state via event is valid.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: boolean"
  [current-state event]
  (contains? workflow-transitions [current-state event]))

(defn terminal-state?
  "Check if state is terminal (no further transitions allowed).

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? terminal-states state))

(defn succeeded?
  "Check if a transition result indicates success."
  [result]
  (boolean (:success? result)))

(defn next-state
  "Get next state for a transition.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition

   Returns: Next state keyword or nil if transition invalid"
  [current-state event]
  (get workflow-transitions [current-state event]))

(defn transition
  "Attempt state transition with guard checking.

   Uses clj-statecharts internally for proper FSM semantics.

   Arguments:
   - current-state: Current FSM state keyword
   - event: Event keyword triggering transition
   - guard-fn: Optional guard function (fn [current-state event] -> boolean)

   Returns:
   - {:success? true :state new-state} if transition valid
   - {:success? false :error reason} if transition invalid"
  ([current-state event]
   (transition current-state event (constantly true)))

  ([current-state event guard-fn]
   (cond
     ;; State already terminal
     (terminal-state? current-state)
     {:success? false
      :error :terminal-state
      :message (terminal-state-message current-state)}

     ;; Invalid state
     (not (contains? workflow-states current-state))
     {:success? false
      :error :invalid-state
      :message (invalid-state-message current-state)}

     ;; Guard function failed
     (not (guard-fn current-state event))
     {:success? false
      :error :guard-failed
      :message (messages/t :status/guard-rejected-transition)}

     ;; Invalid transition
     (not (valid-transition? current-state event))
     {:success? false
      :error :invalid-transition
      :message (no-transition-message current-state event)}

     ;; Valid transition - use clj-statecharts for actual transition
     :else
     (let [;; Create a temporary state with the current state value
           state-map {:_state current-state}
           new-state-map (fsm/transition workflow-machine state-map event)
           new-state (fsm/current-state new-state-map)]
       {:success? true
        :state new-state}))))

(defn valid-state?
  "Check if a state keyword is valid.

   Arguments:
   - state: State keyword

   Returns: boolean"
  [state]
  (contains? workflow-states state))

;; ============================================================================
;; FSM Visualization
;; ============================================================================

(defn get-available-events
  "Get available events from current state.

   Arguments:
   - current-state: Current FSM state keyword

   Returns: Vector of event keywords"
  [current-state]
  (vec (keep (fn [[[from-state event] _to-state]]
               (when (= from-state current-state)
                 event))
             workflow-transitions)))

(defn fsm-graph
  "Get FSM as a graph structure for visualization.

   Returns: Map with :states and :transitions"
  []
  {:states workflow-states
   :transitions (vec (map (fn [[[from event] to]]
                           {:from from :event event :to to})
                         workflow-transitions))
   :terminal-states terminal-states})

;; ============================================================================
;; New clj-statecharts API
;; ============================================================================

(defn get-machine
  "Get the compiled workflow state machine for direct clj-statecharts usage.

   Returns: Compiled state machine definition"
  []
  workflow-machine)

(defn initialize
  "Initialize workflow FSM state.

   Returns: Initial FSM state map with :_state = :pending"
  []
  (fsm/initialize workflow-machine))

(defn transition-fsm
  "Transition the workflow FSM using full statecharts semantics.

   Arguments:
   - state: Current FSM state map (from initialize or previous transition)
   - event: Event keyword or map {:type :event-type :data ...}

   Returns: New FSM state map"
  [state event]
  (fsm/transition workflow-machine state event))

(defn current-state
  "Get the current state keyword from FSM state map.

   Arguments:
   - state: FSM state map

   Returns: State keyword"
  [state]
  (fsm/current-state state))

(defn is-final?
  "Check if the FSM is in a final (terminal) state.

   Arguments:
   - state: FSM state map

   Returns: boolean"
  [state]
  (fsm/final? workflow-machine state))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Using the legacy API (for backward compatibility)
  (valid-transition? :pending :start)  ;; => true
  (valid-transition? :pending :fail)   ;; => false

  (transition :pending :start)
  ;; => {:success? true :state :running}

  (transition :completed :start)
  ;; => {:success? false :error :terminal-state ...}

  (get-available-events :running)
  ;; => [:complete :fail :pause :cancel]

  ;; Using the new clj-statecharts API
  (def s0 (initialize))
  (current-state s0)  ;; => :pending

  (def s1 (transition-fsm s0 :start))
  (current-state s1)  ;; => :running

  (def s2 (transition-fsm s1 :complete))
  (current-state s2)  ;; => :completed
  (is-final? s2)      ;; => true

  :leave-this-here)
