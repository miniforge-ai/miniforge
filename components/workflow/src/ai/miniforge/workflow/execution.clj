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
(ns ai.miniforge.workflow.execution
  "Select the active interceptor, then delegate its guarded lifecycle and transition."
  (:require [ai.miniforge.workflow.execution-step :as step]
            [ai.miniforge.workflow.fsm :as workflow-fsm]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.workflow.runner-outcome :as outcome]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} active-entry [context]
  (when-let [machine (:execution/fsm-machine context)]
    (workflow-fsm/machine-active-phase-entry machine (:execution/fsm-state context))))

(defn- ^{:stratum 0} matching-interceptor [phase interceptor]
  (when (= phase (get-in interceptor [:config :phase])) interceptor))

(defn- ^{:stratum 0} missing-phase [context entry]
  (cond
    (:execution/current-phase context) (:execution/current-phase context)
    (:phase entry) (:phase entry)
    :else :unknown))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} select-interceptor [pipeline context entry]
  (cond
    entry (get pipeline (:index entry))
    (:execution/current-phase context)
    (some (partial matching-interceptor (:execution/current-phase context)) pipeline)
    :else nil))

(defn- ^{:stratum 1} fail-missing-phase [context entry fail]
  (let [phase (missing-phase context entry)
        message (messages/t :status/no-transition-defined {:state phase :event :phase/execute})
        error (outcome/make-execution-error :missing-current-phase message {:phase phase})]
    (-> context (update :execution/errors conj error) fail)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-phase-step
  "Run the active phase only when boundary callbacks accept it.
   Callback anomalies fail execution before phase effects or subsequent transitions.
   The public arity and legacy nil-returning observer callbacks remain supported."
  [pipeline context callbacks merge-metrics-fn transition-to-completed-fn transition-to-failed-fn]
  (let [entry (active-entry context)
        interceptor (select-interceptor pipeline context entry)
        ports {:merge-metrics merge-metrics-fn
               :complete transition-to-completed-fn
               :fail transition-to-failed-fn}]
    (if interceptor
      (step/execute-selected pipeline context callbacks interceptor ports)
      (fail-missing-phase context entry transition-to-failed-fn))))

(comment ::guarded-phase-execution)
