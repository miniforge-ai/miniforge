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

(ns ai.miniforge.phase.registry
  "Phase interceptor registry using multimethods.

   Phases register via defmethod, providing:
   - Default configuration (agent, gates, budget)
   - Interceptor factory function

   Pattern borrowed from Ixi's interceptors-pedestal component."
  (:require [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def Budget
  "Budget configuration schema"
  [:map
   [:tokens {:optional true} pos-int?]
   [:iterations {:optional true} pos-int?]
   [:time-seconds {:optional true} pos-int?]])

(def PhaseConfig
  "Phase configuration schema"
  [:map
   [:phase keyword?]
   [:agent {:optional true} keyword?]
   [:gates {:optional true} [:vector keyword?]]
   [:budget {:optional true} Budget]
   [:on-fail {:optional true} keyword?]
   [:on-success {:optional true} keyword?]
   [:metadata {:optional true} map?]
   ;; Model selection hints
   [:model-hint {:optional true} keyword?]
   [:require-model {:optional true} keyword?]])

(def Interceptor
  "Interceptor schema (Pedestal-style)"
  [:map
   [:name keyword?]
   [:enter {:optional true} fn?]
   [:leave {:optional true} fn?]
   [:error {:optional true} fn?]])

;; Defaults registry (atom for extensibility)
(defonce defaults-registry (atom {}))

;------------------------------------------------------------------------------ Layer 1
;; Multimethod registry

(defmulti get-phase-interceptor
  "Get interceptor for a phase configuration.

   Dispatches on :phase keyword.

   Arguments:
     config - Map with :phase and optional overrides

   Returns:
     Interceptor map with :name, :enter, :leave, :error"
  :phase)

(defmethod get-phase-interceptor :default
  [{:keys [phase]}]
  (throw (ex-info (str "Unknown phase type: " phase
                       ". Available: " (keys @defaults-registry))
                  {:phase phase
                   :available (keys @defaults-registry)})))

;------------------------------------------------------------------------------ Layer 2
;; Registry management

(defn register-phase-defaults!
  "Register default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword like :plan, :implement
     defaults - Default config map {:agent :gates :budget}"
  [phase-kw defaults]
  (swap! defaults-registry assoc phase-kw defaults))

(defn phase-defaults
  "Get default configuration for a phase type.

   Arguments:
     phase-kw - Phase keyword

   Returns:
     Default config map or nil"
  [phase-kw]
  (get @defaults-registry phase-kw))

(defn list-phases
  "List all registered phase types.

   Returns:
     Set of phase keywords"
  []
  (set (keys @defaults-registry)))

(defn merge-with-defaults
  "Merge user config with phase defaults.

   Arguments:
     config - User-provided phase config

   Returns:
     Merged config with defaults"
  [{:keys [phase] :as config}]
  (let [defaults (phase-defaults phase)]
    (merge defaults config)))

(defn valid-config?
  "Check if phase config is valid against schema."
  [config]
  (m/validate PhaseConfig config))

(defn valid-interceptor?
  "Check if interceptor is valid against schema."
  [interceptor]
  (m/validate Interceptor interceptor))

;------------------------------------------------------------------------------ Layer 3
;; Phase status predicates
;;
;; Accept either a bare keyword (:completed, :failed, etc.) or a map with
;; a status key (:status, :execution/status, :step/status, :chain/status).

(def already-done-statuses
  "Statuses indicating work is already complete — neutral outcome."
  #{:already-satisfied :already-implemented})

(def status-keys
  "Keys to try when extracting status from a map, in priority order."
  [:status :execution/status :step/status :chain/status])

(defn extract-status
  "Extract a status keyword from a map by trying known status keys."
  [m]
  (when m (some m status-keys)))

(defn succeeded?
  "Check if a result map indicates success.
   Looks for :status, :execution/status, :step/status, or :chain/status."
  [m]
  (= :completed (extract-status m)))

(defn already-done?
  "Check if a result map indicates work was already complete.
   This is a neutral outcome — not a failure."
  [m]
  (contains? already-done-statuses (extract-status m)))

(defn succeeded-or-done?
  "Check if a result map indicates success or already-done (neutral).
   Use this for event emission and outcome reporting."
  [m]
  (let [s (extract-status m)]
    (or (= :completed s)
        (contains? already-done-statuses s))))

(defn failed?
  "Check if a result map indicates failure."
  [m]
  (= :failed (extract-status m)))

(defn retrying?
  "Check if a result map indicates retry."
  [m]
  (= :retrying (extract-status m)))

(defn determine-phase-status
  "Determine phase status from agent result status, iteration count, and budget.

   Arguments:
   - agent-status: The :status from the agent result (:success, :error, etc.)
   - iterations: Current iteration count
   - max-iterations: Maximum allowed iterations

   Returns: :completed, :retrying, or :failed"
  [agent-status iterations max-iterations]
  (cond
    (= :success agent-status) :completed
    (and (= :error agent-status)
         (< iterations max-iterations)) :retrying
    (= :error agent-status) :failed
    (not= :success agent-status) :failed
    :else :completed))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Register a phase
  (register-phase-defaults! :test
                            {:agent :tester
                             :gates [:tests-pass]
                             :budget {:tokens 5000}})

  ;; Check defaults
  (phase-defaults :test)

  ;; List phases
  (list-phases)

  ;; Merge with defaults
  (merge-with-defaults {:phase :test :budget {:tokens 10000}})

  :leave-this-here)
