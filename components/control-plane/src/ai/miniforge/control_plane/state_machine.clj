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
   completed, failed, unreachable, terminated.

   Layer 0: Profile loading
   Layer 1: Transition validation
   Layer 2: Event mapping"
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.control-plane.messages :as messages]))

;------------------------------------------------------------------------------ Layer 0
;; Profile loading

(def ^:const default-profile-path
  "Classpath location of the control-plane state profile."
  "control-plane/state-profiles/control-plane.edn")

(defn load-profile
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
                     {:path path})))))

(def ^:private profile-cache
  "Cached profile instance."
  (delay (load-profile)))

(defn get-profile
  "Get the cached control-plane state profile."
  []
  @profile-cache)

;------------------------------------------------------------------------------ Layer 1
;; Transition validation

(defn valid-statuses
  "Return the set of all valid agent statuses.

   Example:
     (valid-statuses (get-profile))
     ;=> #{:unknown :initializing :running ...}"
  [profile]
  (set (:task-statuses profile)))

(defn terminal?
  "Check if a status is terminal (no further transitions).

   Example:
     (terminal? (get-profile) :completed) ;=> true
     (terminal? (get-profile) :running)   ;=> false"
  [profile status]
  (contains? (set (:terminal-statuses profile)) status))

(defn valid-transition?
  "Check if a transition from `from-status` to `to-status` is valid.

   Example:
     (valid-transition? (get-profile) :running :blocked)   ;=> true
     (valid-transition? (get-profile) :completed :running)  ;=> false"
  [profile from-status to-status]
  (let [transitions (:valid-transitions profile)]
    (contains? (get transitions from-status #{}) to-status)))

(defn validate-transition
  "Validate a state transition. Returns nil on success, throws on invalid.

   Arguments:
   - profile    - State profile map
   - from-status - Current agent status
   - to-status  - Desired agent status

   Throws: ExceptionInfo if transition is invalid."
  [profile from-status to-status]
  (when-not (valid-transition? profile from-status to-status)
    (throw (ex-info (messages/t :state-machine/invalid-transition)
                    {:from from-status
                     :to to-status
                     :valid-targets (get (:valid-transitions profile)
                                        from-status #{})}))))

;------------------------------------------------------------------------------ Layer 2
;; Event mapping

(defn event->transition
  "Map an event type to its configured transition.

   Returns: Transition map or nil if event has no mapping.

   Example:
     (event->transition (get-profile) :agent/decision-needed)
     ;=> {:type :transition :to :blocked}"
  [profile event-type]
  (get (:event-mappings profile) event-type))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def p (load-profile))
  (:profile/id p)
  (valid-statuses p)
  (valid-transition? p :running :blocked)
  (valid-transition? p :completed :running)
  (event->transition p :agent/decision-needed)
  :end)
