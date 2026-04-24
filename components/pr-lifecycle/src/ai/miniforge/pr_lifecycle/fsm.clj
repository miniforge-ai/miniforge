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
   [ai.miniforge.fsm.interface :as fsm]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.schema.interface :as schema]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; FSM definition

(def PrLifecycleFsmConfig
  [:map
   [:pr-lifecycle/fsm
    [:map
     [:initial-status keyword?]
     [:statuses [:vector keyword?]]
     [:terminal-statuses [:set keyword?]]
     [:status-transition-events [:map-of [:tuple keyword? keyword?] keyword?]]]]])

(def ^:private fsm-config-resource
  "Classpath resource holding the PR lifecycle FSM definition."
  "config/pr-lifecycle/fsm.edn")

(defn- validate!
  [result-schema value]
  (schema/validate result-schema value))

(defn- load-fsm-config
  []
  (if-let [resource (io/resource fsm-config-resource)]
    (->> resource slurp edn/read-string (validate! PrLifecycleFsmConfig))
    (throw (ex-info (messages/t :config/missing-resource
                                {:resource fsm-config-resource})
                    {:hint (messages/t :config/classpath-hint)}))))

(def ^:private fsm-config
  (delay (load-fsm-config)))

(def initial-status
  "Initial controller status."
  (get-in @fsm-config [:pr-lifecycle/fsm :initial-status]))

(def controller-status-order
  "Ordered controller statuses for machine compilation."
  (get-in @fsm-config [:pr-lifecycle/fsm :statuses]))

(def status-transition-events
  "Configured controller transitions keyed by `[from-status to-status]`."
  (get-in @fsm-config [:pr-lifecycle/fsm :status-transition-events]))

(def controller-statuses
  "Valid controller statuses."
  (set controller-status-order))

(def terminal-statuses
  "Terminal controller statuses."
  (get-in @fsm-config [:pr-lifecycle/fsm :terminal-statuses]))

(defn valid-status?
  "Check whether `status` is a recognized controller status."
  [status]
  (contains? controller-statuses status))

(defn terminal-status?
  "Check whether `status` is terminal."
  [status]
  (contains? terminal-statuses status))

(defn- transition-targets
  "Return all configured targets from `from-status`."
  [from-status]
  (->> status-transition-events
       (keep (fn [[[source-status target-status] _event]]
               (when (= source-status from-status)
                 target-status)))
       set))

(defn- machine-state-definition
  "Return the machine state definition for a controller status."
  [status]
  (if (terminal-status? status)
    {:type :final}
    {:on (into {}
               (for [[[source-status target-status] event] status-transition-events
                     :when (= source-status status)]
                 [event target-status]))}))

;------------------------------------------------------------------------------ Layer 1
;; Transition validation

(defn valid-transition?
  "Check whether the controller may move from `from-status` to `to-status`.

   Same-state transitions are treated as valid idempotent updates."
  [from-status to-status]
  (and (valid-status? from-status)
       (valid-status? to-status)
       (or (= from-status to-status)
           (contains? status-transition-events [from-status to-status]))))

(defn valid-targets
  "Return the valid target statuses from `from-status`, including itself."
  [from-status]
  (if (valid-status? from-status)
    (conj (transition-targets from-status) from-status)
    #{}))

;------------------------------------------------------------------------------ Layer 2
;; Transition execution

(declare transition-failure)

(def controller-state-definitions
  "Machine state definitions keyed by controller status."
  (zipmap controller-status-order
          (map machine-state-definition controller-status-order)))

(def controller-machine-config
  "PR lifecycle controller machine configuration."
  {:fsm/id :pr-lifecycle-controller
   :fsm/initial initial-status
   :fsm/context {}
   :fsm/states controller-state-definitions})

(def controller-machine
  "Compiled controller machine."
  (fsm/define-machine controller-machine-config))

(defn transition
  "Attempt a controller status transition.

   Returns:
   - `{:success? true :state new-status :event event-or-nil}` on success
   - `{:success? false :error keyword :message string}` on failure"
  [from-status to-status]
  (cond
    (not (valid-status? from-status))
    (transition-failure :invalid-state from-status to-status)

    (not (valid-status? to-status))
    (transition-failure :invalid-target-status from-status to-status)

    (= from-status to-status)
    {:success? true
     :state from-status
     :event nil}

    (terminal-status? from-status)
    (transition-failure :terminal-state from-status to-status)

    :else
    (if-let [event (get status-transition-events [from-status to-status])]
      (let [state-map {:_state from-status}
            new-state-map (fsm/transition controller-machine state-map event)
            new-state (fsm/current-state new-state-map)]
        {:success? true
         :state new-state
         :event event})
      (transition-failure :invalid-transition from-status to-status))))

(defn- transition-failure
  "Return a consistent controller transition failure map."
  [error from-status to-status]
  (let [message (case error
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
    {:success? false
     :error error
     :message message}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (valid-transition? :pending :creating-pr)
  (valid-transition? :merged :monitoring-ci)
  (transition :monitoring-ci :monitoring-review)
  (transition :ready-to-merge :merged)
  (valid-targets :fixing)
  :end)
