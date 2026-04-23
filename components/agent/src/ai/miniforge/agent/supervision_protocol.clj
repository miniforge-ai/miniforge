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

(ns ai.miniforge.agent.supervision-protocol
  "Protocol and helpers for workflow supervisors.

   Supervisors are runtime governance agents that observe in-flight workflow
   execution and can warn, block, or halt when they detect safety or liveness
   issues. They are distinct from the learning loop, which analyzes executions
   across runs and proposes improvements."
  (:require [ai.miniforge.response.interface :as response]))

(defprotocol Supervisor
  "Protocol for supervisors that monitor workflow execution.

   Supervisors receive workflow state updates and decide whether
   the workflow should continue or halt."

  (check-health [this workflow-state]
    "Check workflow health and return a decision.

     Arguments:
     - workflow-state: Current workflow state map including:
       {:workflow/id UUID
        :workflow/phase :plan|:implement|:verify|:review|:release
        :workflow/iterations number
        :workflow/artifacts {...}
        :workflow/metrics {...}
        :workflow/streaming-activity [...]
        :workflow/files-written [...]}

     Returns:
     {:status :healthy|:warning|:halt
      :agent/id keyword (e.g., :progress-monitor)
      :message string (human-readable explanation)
      :data {} (diagnostic data)
      :checked-at inst}

     Status meanings:
     - :healthy - Workflow is progressing normally
     - :warning - Issue detected but not critical yet
     - :halt - Workflow must stop immediately")

  (get-supervisor-config [this]
    "Get supervisor configuration.

     Returns:
     {:agent/id :progress-monitor
      :agent/name \"Progress Monitor\"
      :agent/can-halt? true
      :agent/check-interval-ms 30000
      :agent/priority :high|:medium|:low}")

  (reset-state! [this]
    "Reset supervisor internal state. Called when workflow restarts."))

(defn healthy?
  "Check if health status indicates workflow can continue."
  [health-check]
  (= :healthy (:status health-check)))

(defn halt?
  "Check if health status indicates workflow must stop."
  [health-check]
  (= :halt (:status health-check)))

(defn warning?
  "Check if health status indicates a warning."
  [health-check]
  (= :warning (:status health-check)))

(defn create-health-check
  "Helper to create a health check result.

   Delegates to response/status-check for consistent timestamped responses.

   Usage:
   (create-health-check :progress-monitor :healthy \"Making progress\")
   (create-health-check :test-quality :halt \"No tests found\" {:files-checked 10})"
  ([agent-id status message]
   (create-health-check agent-id status message nil))
  ([agent-id status message data]
   (response/status-check status
                          (cond-> {:agent/id agent-id
                                   :message message}
                            data (assoc :data data)))))

(defrecord SupervisorConfig
  [id                    ; Keyword ID (:progress-monitor, :test-quality, etc.)
   name                  ; Human-readable name
   can-halt?             ; Can this agent halt the workflow?
   check-interval-ms     ; How often to run health checks
   priority              ; :high, :medium, :low - affects check ordering
   enabled?])            ; Is this supervisor active?

(defn create-supervisor-config
  "Create a supervisor configuration.

   Options:
   - :id (required) - Keyword identifier
   - :name (required) - Human-readable name
   - :can-halt? (default: true) - Can halt workflow
   - :check-interval-ms (default: 30000) - Check frequency
   - :priority (default: :medium) - :high, :medium, :low
   - :enabled? (default: true) - Is active"
  [{:keys [id name can-halt? check-interval-ms priority enabled?]
    :or {can-halt? true
         check-interval-ms 30000
         priority :medium
         enabled? true}
    :as opts}]
  (when-not (and id name)
    (response/throw-anomaly! :anomalies/incorrect
                            "Supervisor config requires :id and :name"
                            {:provided opts}))
  (map->SupervisorConfig
   {:id id
    :name name
    :can-halt? can-halt?
    :check-interval-ms check-interval-ms
    :priority priority
    :enabled? enabled?}))

(comment
  ;; Example supervisor implementation
  #_(defrecord ProgressMonitorAgent [monitor-state]
    Supervisor
    (check-health [this workflow-state]
      (let [timeout-check (check-timeout @monitor-state)]
        (if timeout-check
          (create-health-check
           :progress-monitor
           :halt
           (:message timeout-check)
           (:stats timeout-check))
          (create-health-check
           :progress-monitor
           :healthy
           "Workflow making progress"
           (get-stats @monitor-state)))))

    (get-supervisor-config [this]
      (create-supervisor-config
       {:id :progress-monitor
        :name "Progress Monitor"
        :can-halt? true
        :check-interval-ms 30000
        :priority :high}))

    (reset-state! [this]
      (reset! monitor-state (create-progress-monitor {})))))
