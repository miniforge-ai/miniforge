;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.fsm.core
  "Core FSM implementation wrapping clj-statecharts.

   Provides a thin wrapper around clj-statecharts with miniforge conventions:
   - Data-driven machine definitions
   - Integration with response chains for action results
   - Guards that can inspect execution context

   Machine definition format:
   {:fsm/id       :workflow
    :fsm/initial  :idle
    :fsm/context  {}
    :fsm/states
    {:idle     {:on {:start :running}}
     :running  {:on {:complete :completed
                     :fail     :failed}}
     :completed {:type :final}
     :failed    {:type :final}}}"
  (:require
   [statecharts.core :as sc]))

;------------------------------------------------------------------------------ Layer 0
;; Internal helpers

(defn- compile-transitions
  "Compile transition definitions.

   Supports shorthand and full forms:
   - :event :target           -> simple transition
   - :event {:target :state}  -> full form
   - :event {:target :state :guard fn :actions [...]} -> with guard/actions"
  [transitions]
  (reduce-kv
   (fn [acc event target-or-config]
     (assoc acc event
            (if (keyword? target-or-config)
              {:target target-or-config}
              target-or-config)))
   {}
   transitions))

(defn- compile-state
  "Compile a state definition to clj-statecharts format.

   Supports:
   - :on - Event/transition map
   - :entry - Entry actions
   - :exit - Exit actions
   - :type - :final for terminal states (tracked separately, not passed to clj-statecharts)"
  [state-def]
  (let [{:keys [on entry exit]} state-def]
    ;; Note: :type :final is handled separately - clj-statecharts doesn't support it
    (cond-> {}
      on    (assoc :on (compile-transitions on))
      entry (assoc :entry entry)
      exit  (assoc :exit exit))))

;------------------------------------------------------------------------------ Layer 1
;; Machine definition

(defn- extract-final-states
  "Extract the set of states marked as :type :final."
  [states]
  (into #{}
        (comp (filter (fn [[_ v]] (= :final (:type v))))
              (map first))
        states))

(defn define-machine
  "Create a state machine definition from miniforge config.

   Arguments:
     config - Machine configuration map with:
       :fsm/id       - Machine identifier
       :fsm/initial  - Initial state keyword
       :fsm/context  - Initial context data
       :fsm/states   - State definitions map

   Returns:
     Compiled state machine definition with :miniforge/final-states metadata."
  [{:fsm/keys [id initial context states]}]
  (let [final-states (extract-final-states states)
        machine (sc/machine
                 {:id      id
                  :initial initial
                  :context (or context {})
                  :states  (reduce-kv
                            (fn [acc state-id state-def]
                              (assoc acc state-id (compile-state state-def)))
                            {}
                            states)})]
    ;; Attach final states as metadata
    (assoc machine :miniforge/final-states final-states)))

;------------------------------------------------------------------------------ Layer 2
;; State operations

(defn initialize
  "Initialize a state machine, returning the initial state.

   Arguments:
     machine - Compiled machine definition

   Returns:
     Initial state map with :_state and context."
  [machine]
  (sc/initialize machine))

(defn transition
  "Transition the machine given an event.

   Arguments:
     machine - Compiled machine definition
     state   - Current state
     event   - Event keyword or {:type :event-type :data ...}

   Returns:
     New state after transition (or same state if no valid transition)."
  [machine state event]
  (let [event-map (if (keyword? event)
                    {:type event}
                    event)]
    (try
      (sc/transition machine state event-map)
      (catch clojure.lang.ExceptionInfo e
        ;; If it's an unknown event error, return current state unchanged
        (if (re-find #"got unknown event" (ex-message e))
          state
          (throw e))))))

(defn current-state
  "Get the current state value from a state map.

   Arguments:
     state - State map from initialize or transition

   Returns:
     Current state keyword."
  [state]
  (:_state state))

(defn context
  "Get the context from a state map.

   Arguments:
     state - State map

   Returns:
     Context map (everything except internal state keys)."
  [state]
  (dissoc state :_state))

(defn in-state?
  "Check if the machine is in a specific state.

   Arguments:
     state    - State map
     state-id - State keyword to check

   Returns:
     Boolean."
  [state state-id]
  (= state-id (current-state state)))

(defn final?
  "Check if the current state is a final state.

   Arguments:
     machine - Compiled machine definition
     state   - Current state map

   Returns:
     Boolean."
  [machine state]
  (let [current (current-state state)
        final-states (:miniforge/final-states machine #{})]
    (contains? final-states current)))

;------------------------------------------------------------------------------ Layer 3
;; Context manipulation

(defn assign
  "Create an action that assigns values to context.

   Arguments:
     assignments - Map of context keys to values or functions
                   Functions receive (context, event) and return new value

   Returns:
     Action function for use in :entry/:exit/:actions."
  [assignments]
  (fn [state event]
    (reduce-kv
     (fn [s k v]
       (assoc s k (if (fn? v)
                    (v (context s) event)
                    v)))
     state
     assignments)))

(defn update-context
  "Update context in a state map.

   Arguments:
     state - Current state map
     f     - Function to apply to context
     args  - Additional args to f

   Returns:
     Updated state map."
  [state f & args]
  (let [ctx (context state)
        new-ctx (apply f ctx args)]
    (merge state new-ctx)))

;------------------------------------------------------------------------------ Layer 4
;; Guard helpers

(defn guard
  "Create a guard function from a predicate.

   Arguments:
     pred - Predicate fn (context, event) -> boolean

   Returns:
     Guard function for use in transitions."
  [pred]
  (fn [state event]
    (pred (context state) event)))

(defn all-guards
  "Combine multiple guards with AND logic.

   Arguments:
     guards - Sequence of guard functions

   Returns:
     Combined guard that passes only if all pass."
  [& guards]
  (fn [state event]
    (every? #(% state event) guards)))

(defn any-guard
  "Combine multiple guards with OR logic.

   Arguments:
     guards - Sequence of guard functions

   Returns:
     Combined guard that passes if any pass."
  [& guards]
  (fn [state event]
    (some #(% state event) guards)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Define a simple workflow machine
  (def workflow-machine
    (define-machine
     {:fsm/id :workflow
      :fsm/initial :idle
      :fsm/context {:phase-index 0}
      :fsm/states
      {:idle      {:on {:start :running}}
       :running   {:on {:phase-complete {:target :running
                                         :actions [(assign {:phase-index inc})]}
                        :complete :completed
                        :fail :failed}}
       :completed {:type :final}
       :failed    {:type :final}}}))

  ;; Use the machine
  (def s0 (initialize workflow-machine))
  (current-state s0) ;; => :idle

  (def s1 (transition workflow-machine s0 :start))
  (current-state s1) ;; => :running

  (def s2 (transition workflow-machine s1 :phase-complete))
  (current-state s2) ;; => :running
  (context s2)       ;; => {:phase-index 1}

  (def s3 (transition workflow-machine s2 :complete))
  (current-state s3)                    ;; => :completed
  (final? workflow-machine s3)          ;; => true

  :leave-this-here)
