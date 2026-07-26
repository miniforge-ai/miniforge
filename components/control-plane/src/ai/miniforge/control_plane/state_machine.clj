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
(ns ai.miniforge.control-plane.state-machine
  "Normalized agent lifecycle state machine for the control plane.

   Loads state profiles from EDN and validates transitions.
   Agents from any vendor are mapped to a common set of states:
   unknown, initializing, running, idle, blocked, paused,
   completed, failed, unreachable, terminated."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.control-plane.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0

;; Profile loading
(def ^{:stratum 0} ^:const default-profile-path
  "Classpath location of the control-plane state profile."
  "control-plane/state-profiles/control-plane.edn")

;; Transition validation
(defn ^{:stratum 0} valid-statuses
  "Return the set of all valid agent statuses.

   Example:
     (valid-statuses (get-profile))
     ;=> #{:unknown :initializing :running ...}"
  [profile]
  (set (:task-statuses profile)))

(defn ^{:stratum 0} terminal?
  "Check if a status is terminal (no further transitions).

   Example:
     (terminal? (get-profile) :completed) ;=> true
     (terminal? (get-profile) :running)   ;=> false"
  [profile status]
  (contains? (set (:terminal-statuses profile)) status))

(defn ^{:stratum 0} valid-transition?
  "Check if a transition from `from-status` to `to-status` is valid.

   Example:
     (valid-transition? (get-profile) :running :blocked)   ;=> true
     (valid-transition? (get-profile) :completed :running)  ;=> false"
  [profile from-status to-status]
  (let [transitions (:valid-transitions profile)]
    (contains? (get transitions from-status #{}) to-status)))

;; Event mapping
(defn ^{:stratum 0} event->transition
  "Map an event type to its configured transition.

   Returns: Transition map or nil if event has no mapping.

   Example:
     (event->transition (get-profile) :agent/decision-needed)
     ;=> {:type :transition :to :blocked}"
  [profile event-type]
  (get (:event-mappings profile) event-type))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load-profile
  "Load the control-plane state profile from classpath.

   Returns: State profile map with :task-statuses, :valid-transitions, etc.

   Example:
     (load-profile)
     ;=> {:profile/id :control-plane :task-statuses [...] ...}"
  ([]
   (load-profile default-profile-path))
  ([path]
   (if-let [resource (io/resource path)]
     (edn/read-string (slurp resource))
     (throw (ex-info (messages/t :state-machine/profile-not-found)
                     {:path path
                      :config/error :invalid-config})))))

(defn ^{:stratum 1} validate-transition-result
  "Validate a state transition. Returns nil on success or an anomaly on invalid.

   Arguments:
   - profile    - State profile map
   - from-status - Current agent status
   - to-status  - Desired agent status"
  [profile from-status to-status]
  (when-not (valid-transition? profile from-status to-status)
    (anomaly/anomaly :invalid-input
                     (messages/t :state-machine/invalid-transition)
                     {:from from-status
                      :to to-status
                      :valid-targets (get (:valid-transitions profile)
                                         from-status #{})})))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} ^:private profile-cache
  "Cached profile instance."
  (delay (load-profile)))

(defn ^{:stratum 2} validate-transition
  "Boundary wrapper around the canonical anomaly-returning
   [[validate-transition-result]]. Returns nil on success and throws only for
   legacy callers. Prefer `validate-transition-result` in non-boundary code."
  [profile from-status to-status]
  (let [result (validate-transition-result profile from-status to-status)]
    (when (anomaly/anomaly? result)
      (throw (ex-info (:anomaly/message result)
                      (:anomaly/data result))))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} get-profile
  "Get the cached control-plane state profile."
  []
  @profile-cache)

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def p (load-profile))
  (:profile/id p)
  (valid-statuses p)
  (valid-transition? p :running :blocked)
  (valid-transition? p :completed :running)
  (event->transition p :agent/decision-needed)
  :end)
