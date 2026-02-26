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

(ns ai.miniforge.workflow.context
  "Execution context management.

   Handles creation and manipulation of workflow execution context,
   including FSM state transitions and metrics merging."
  (:require [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.fsm :as fsm]
            [ai.miniforge.workflow.monitoring :as monitoring]
            [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Context operations

(defn create-context
  "Create initial execution context.

   Arguments:
   - workflow: Workflow configuration
   - input: Input data for the workflow
   - opts: Execution options (including :llm-backend, :artifact-store, callbacks)

   Returns execution context map with FSM state initialized."
  [workflow input opts]
  (let [;; Initialize FSM and transition to running state
        fsm-state (-> (fsm/initialize)
                      (fsm/transition-fsm :start))
        ;; Initialize meta-agents and coordinator
        meta-agents (monitoring/create-meta-agents workflow)
        coordinator (agent/create-meta-coordinator meta-agents)]
    (merge
     {:execution/id (random-uuid)
      :execution/workflow-id (:workflow/id workflow)
      :execution/workflow-version (:workflow/version workflow)
      :execution/status (fsm/current-state fsm-state)
      :execution/fsm-state fsm-state
      :execution/input input
      :execution/artifacts []
      :execution/errors []  ; DEPRECATED: Use :execution/response-chain instead
      :execution/response-chain (response/create (:workflow/id workflow))
      :execution/phase-results {}
      :execution/output nil
      :execution/current-phase nil
      :execution/phase-index 0
      :execution/metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}
      :execution/started-at (System/currentTimeMillis)
      :execution/opts opts
      ;; Meta-agent coordinator for workflow health monitoring
      :execution/meta-coordinator coordinator
      ;; Transient state for meta-agent health checks
      :execution/streaming-activity []
      :execution/files-written #{}}
     ;; Merge opts into top-level context so :llm-backend is accessible to agents
     (select-keys opts [:llm-backend :artifact-store :on-phase-start :on-phase-complete
                       :executor :environment-id :sandbox-workdir
                       :on-chunk :event-stream :worktree-path]))))

(defn merge-metrics
  "Merge phase metrics into execution metrics."
  [exec-metrics phase-metrics]
  (merge-with + exec-metrics
              (select-keys phase-metrics [:tokens :cost-usd :duration-ms])))

(defn transition-to-completed
  "Transition workflow to completed state using FSM."
  [ctx]
  (let [fsm-state (:execution/fsm-state ctx)
        new-fsm-state (fsm/transition-fsm fsm-state :complete)]
    (-> ctx
        (assoc :execution/fsm-state new-fsm-state)
        (assoc :execution/status (fsm/current-state new-fsm-state))
        (assoc :execution/ended-at (System/currentTimeMillis)))))

(defn transition-to-failed
  "Transition workflow to failed state using FSM."
  [ctx]
  (let [fsm-state (:execution/fsm-state ctx)
        new-fsm-state (fsm/transition-fsm fsm-state :fail)]
    (-> ctx
        (assoc :execution/fsm-state new-fsm-state)
        (assoc :execution/status (fsm/current-state new-fsm-state))
        (assoc :execution/ended-at (System/currentTimeMillis)))))
