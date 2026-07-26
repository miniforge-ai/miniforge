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
(ns ai.miniforge.pr-lifecycle.fsm
  "Finite State Machine for PR lifecycle controller status transitions.

   Keeps the controller's public status contract stable while making the
   transition policy explicit, testable, and backed by the shared FSM
   foundation component."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

;; FSM definition
(def ^{:stratum 0} PrLifecycleFsmConfig
  [:map
   [:pr-lifecycle/fsm
    [:map
     [:initial-status keyword?]
     [:statuses [:vector keyword?]]
     [:terminal-statuses [:set keyword?]]
     [:status-transition-events [:map-of [:tuple keyword? keyword?] keyword?]]]]])

(def ^{:stratum 0} ^:private fsm-config-resource
  "Classpath resource holding the PR lifecycle FSM definition."
  "config/pr-lifecycle/fsm.edn")

(defn- ^{:stratum 0} validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn ^{:stratum 0} succeeded?
  "Check whether a controller transition result succeeded."
  [result]
  (schema/succeeded? result))

(defn ^{:stratum 0} failed?
  "Check whether a controller transition result failed."
  [result]
  (schema/failed? result))

;; Transition execution
(def ^{:stratum 0} ^:private transition-data-key
  :transition)

(defn- ^{:stratum 0} transition-error
  "Create a structured controller transition error map."
  [error-code from-status to-status]
  (let [message (case error-code
                  :invalid-state
                  (messages/t :fsm/invalid-state {:status from-status})

                  :invalid-target-status
                  (messages/t :fsm/invalid-target-status {:status to-status})

                  :terminal-state
                  (messages/t :fsm/terminal-state {:status from-status})

                  :invalid-transition
                  (messages/t :fsm/invalid-transition
                              {:from-status from-status
                               :to-status to-status}))]
    {:code error-code
     :message message}))

(defn ^{:stratum 0} transition-error-code
  "Return the transition error code from a failed result."
  [result]
  (get-in result [:error :code]))

(defn ^{:stratum 0} transition-error-message
  "Return the transition error message from a failed result."
  [result]
  (get-in result [:error :message]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} load-fsm-config
  []
  (validate! PrLifecycleFsmConfig
             (config/load-config-resource fsm-config-resource
                                          [:pr-lifecycle/fsm])))

(defn- ^{:stratum 1} transition-result
  "Create a successful controller transition result."
  [state event]
  (let [transition {:state state
                    :event event}]
    (schema/success transition-data-key transition)))

(defn ^{:stratum 1} transition-state
  "Return the target controller state from a transition result."
  [result]
  (get-in result [transition-data-key :state]))

(defn ^{:stratum 1} transition-event
  "Return the transition event from a transition result."
  [result]
  (get-in result [transition-data-key :event]))

(defn- ^{:stratum 1} transition-failure
  "Return a consistent controller transition failure result."
  [error-code from-status to-status]
  (let [error (transition-error error-code from-status to-status)]
    (schema/failure transition-data-key error)))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ^:private fsm-config
  "Validated PR lifecycle FSM configuration loaded eagerly at require time."
  (load-fsm-config))

;------------------------------------------------------------------------------ Layer 3

(def ^{:stratum 3} initial-status
  "Initial controller status."
  (get-in fsm-config [:pr-lifecycle/fsm :initial-status]))

(def ^{:stratum 3} controller-status-order
  "Ordered controller statuses for machine compilation."
  (get-in fsm-config [:pr-lifecycle/fsm :statuses]))

(def ^{:stratum 3} status-transition-events
  "Configured controller transitions keyed by `[from-status to-status]`."
  (get-in fsm-config [:pr-lifecycle/fsm :status-transition-events]))

(def ^{:stratum 3} terminal-statuses
  "Terminal controller statuses."
  (get-in fsm-config [:pr-lifecycle/fsm :terminal-statuses]))

;------------------------------------------------------------------------------ Layer 4

(def ^{:stratum 4} controller-statuses
  "Valid controller statuses."
  (set controller-status-order))

(defn ^{:stratum 4} terminal-status?
  "Check whether `status` is terminal."
  [status]
  (contains? terminal-statuses status))

(defn- ^{:stratum 4} transition-targets
  "Return all configured targets from `from-status`."
  [from-status]
  (->> status-transition-events
       (keep (fn [[[source-status target-status] _event]]
               (when (= source-status from-status)
                 target-status)))
       set))

;------------------------------------------------------------------------------ Layer 5

(defn ^{:stratum 5} valid-status?
  "Check whether `status` is a recognized controller status."
  [status]
  (contains? controller-statuses status))

(defn- ^{:stratum 5} machine-state-definition
  "Return the machine state definition for a controller status."
  [status]
  (if (terminal-status? status)
    {:type :final}
    {:on (into {}
               (for [[[source-status target-status] event] status-transition-events
                     :when (= source-status status)]
                 [event target-status]))}))

;------------------------------------------------------------------------------ Layer 6

;; Transition validation
(defn ^{:stratum 6} valid-transition?
  "Check whether the controller may move from `from-status` to `to-status`.

   Same-state transitions are treated as valid idempotent updates."
  [from-status to-status]
  (and (valid-status? from-status)
       (valid-status? to-status)
       (or (= from-status to-status)
           (contains? status-transition-events [from-status to-status]))))

(defn ^{:stratum 6} valid-targets
  "Return the valid target statuses from `from-status`, including itself."
  [from-status]
  (if (valid-status? from-status)
    (conj (transition-targets from-status) from-status)
    #{}))

(def ^{:stratum 6} controller-state-definitions
  "Machine state definitions keyed by controller status."
  (zipmap controller-status-order
          (map machine-state-definition controller-status-order)))

;------------------------------------------------------------------------------ Layer 7

(def ^{:stratum 7} controller-machine-config
  "PR lifecycle controller machine configuration."
  {:fsm/id :pr-lifecycle-controller
   :fsm/initial initial-status
   :fsm/context {}
   :fsm/states controller-state-definitions})

;------------------------------------------------------------------------------ Layer 8

(def ^{:stratum 8} controller-machine
  "Compiled controller machine."
  (fsm/define-machine controller-machine-config))

;------------------------------------------------------------------------------ Layer 9

(defn ^{:stratum 9} transition
  "Attempt a controller status transition.

   Returns:
   - `(schema/success :transition {:state new-status :event event-or-nil})` on success
   - `(schema/failure :transition {:code keyword :message string})` on failure"
  [from-status to-status]
  (cond
    (not (valid-status? from-status))
    (transition-failure :invalid-state from-status to-status)

    (not (valid-status? to-status))
    (transition-failure :invalid-target-status from-status to-status)

    (= from-status to-status)
    (transition-result from-status nil)

    (terminal-status? from-status)
    (transition-failure :terminal-state from-status to-status)

    :else
    (if-let [event (get status-transition-events [from-status to-status])]
      (let [state-map {:_state from-status}
            new-state-map (fsm/transition controller-machine state-map event)
            new-state (fsm/current-state new-state-map)]
        (transition-result new-state event))
      (transition-failure :invalid-transition from-status to-status))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid-transition? :pending :creating-pr)
  (valid-transition? :merged :monitoring-ci)
  (transition :monitoring-ci :monitoring-review)
  (transition :ready-to-merge :merged)
  (valid-targets :fixing)
  :end)
