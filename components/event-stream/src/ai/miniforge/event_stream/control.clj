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
(ns ai.miniforge.event-stream.control
  "Structured control actions with RBAC for N8 OCI compliance.

   Control actions are commands issued by authorized listeners to affect
   workflow execution (pause, resume, retry, rollback, etc.). Each action
   carries requester identity, justification, and authorization context.

   RBAC roles define which action types are permitted per target category."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.approval :as approval]
   [ai.miniforge.event-stream.control-authorization :as authorization]
   [ai.miniforge.event-stream.control-events :as control-events]
   [ai.miniforge.event-stream.control-results :as results]))

;------------------------------------------------------------------------------ Layer 0

;; Control state management
(defn ^{:stratum 0} create-control-state
  "Create a canonical control-state atom for workflow execution.
   Used by both CLI dashboard poller and TUI to drive pause/resume/cancel."
  []
  (atom {:paused false :stopped false :adjustments {}}))

(defn ^{:stratum 0} pause!
  "Pause workflow execution."
  [control-state]
  (swap! control-state assoc :paused true))

(defn ^{:stratum 0} resume!
  "Resume paused workflow execution."
  [control-state]
  (swap! control-state assoc :paused false))

(defn ^{:stratum 0} cancel!
  "Cancel workflow execution."
  [control-state]
  (swap! control-state assoc :stopped true))

(defn ^{:stratum 0} paused?
  "Check if workflow is paused."
  [control-state]
  (:paused @control-state))

(defn ^{:stratum 0} cancelled?
  "Check if workflow is cancelled."
  [control-state]
  (:stopped @control-state))

;; RBAC role definitions
(def ^{:stratum 0} default-roles
  "Default RBAC roles mapping role -> target-category -> permitted actions."
  {:operator {:workflows #{:pause :resume :retry :cancel}
              :agents #{:quarantine :adjust-budget}
              :fleet #{}
              :approvals #{}}
   :admin {:workflows #{:pause :resume :retry :cancel :rollback}
           :agents #{:quarantine :adjust-budget}
           :fleet #{:emergency-stop :drain}
           :approvals #{:gate-override :budget-escalation}}})

(def ^{:stratum 0} target-categories
  "Valid target types for control actions."
  #{:workflow :agent :fleet})

;; Control action creation
(defn ^{:stratum 0} create-control-action
  "Create a structured control action.

   Arguments:
   - action-type: Keyword — :pause, :resume, :retry, :rollback, :cancel,
                  :quarantine, :adjust-budget, :emergency-stop, :gate-override
   - target: Map with :target-type (:workflow, :agent, :fleet) and :target-id
   - requester: Map with :principal (string), :role (keyword), :listener-id (optional)
   - opts: Optional map with :justification (string) and :parameters (map)

   Returns: Control action map."
  [action-type target requester & [opts]]
  (cond-> {:action/id (random-uuid)
           :action/type action-type
           :action/target target
           :action/requester requester
           :action/status :pending
           :action/created-at (java.util.Date.)}
    (:justification opts) (assoc :action/justification (:justification opts))
    (:parameters opts) (assoc :action/parameters (:parameters opts))))

(def ^{:stratum 0} authorize-action
  "Check the requester's RBAC role against the action and target."
  authorization/authorize-action)

;; Approval-aware control action execution
(def ^{:stratum 0} actions-requiring-approval
  "Action types that require multi-party approval before execution."
  #{:gate-override :budget-escalation})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} requires-approval?
  "Check if a control action type requires multi-party approval."
  [action-type]
  (contains? actions-requiring-approval action-type))

;; Control action execution
(defn ^{:stratum 1} execute-control-action!
  "Execute a control action with RBAC authorization.

   Calls authorize-action before executing. Returns authorization failure
   if the requester lacks permission.

   Arguments:
   - stream: Event stream atom
   - action: Control action map
   - execution-fn: (fn [action] -> result-map) that performs the actual action
   - opts: Optional map with :roles (RBAC roles, defaults to default-roles)

   Emits :control-action/requested before execution and
   :control-action/executed after. Returns result map with :status and :result."
  [stream action execution-fn & [opts]]
  (let [workflow-id (get-in action [:action/target :target-id])
        action-id (:action/id action)
        roles (get opts :roles default-roles)
        requester (:action/requester action)
        auth-result (authorize-action roles action requester)]
    (core/publish! stream
                   (control-events/requested stream action))
    (let [result (if (:authorized? auth-result)
                   (results/invoke! execution-fn action)
                   (results/denied auth-result))]
      (core/publish! stream (core/control-action-executed stream workflow-id action-id result))
      result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-control-action-with-approval!
  "Execute a control action, checking approval requirements first.

   If the action type requires approval (:gate-override, :budget-escalation),
   creates an approval request and returns {:status :awaiting-approval}.
   Otherwise delegates to execute-control-action!.

   Arguments:
   - stream: Event stream atom
   - action: Control action map
   - execution-fn: (fn [action] -> result-map)
   - approval-opts: Map with :required-signers, :quorum, :approval-manager

   Returns: Result map with :status key."
  [stream action execution-fn & [approval-opts]]
  (if (requires-approval? (:action/type action))
    (let [action-id (:action/id action)
          signers (get approval-opts :required-signers ["admin"])
          quorum (get approval-opts :quorum 1)
          mgr (get approval-opts :approval-manager)
          req (approval/create-approval-request action-id signers quorum)
          workflow-id (get-in action [:action/target :target-id])]
      ;; Store in manager if provided
      (when mgr
        (approval/store-approval! mgr req))
      ;; Emit approval requested event
      (core/publish! stream
                     (approval/approval-requested
                      stream workflow-id (:approval/id req)
                      action-id signers))
      {:status :awaiting-approval
       :approval/id (:approval/id req)
       :approval/required-signers signers
       :approval/quorum quorum})
    ;; No approval needed — execute directly
    (execute-control-action! stream action execution-fn)))
