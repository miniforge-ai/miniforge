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

(ns ai.miniforge.reporting.protocol
  "Reporting protocols for system status and monitoring.

   The reporting component aggregates data from orchestrator, workflow,
   and operator components to provide unified views of system state.")

;------------------------------------------------------------------------------ Layer 0
;; Core reporting protocol

(defprotocol ReportingService
  "Protocol for reporting and monitoring service.
   Aggregates data from multiple components for unified status views."

  (get-system-status [this]
    "Get overall system status.
     Returns:
       {:workflows {:active int :pending int :completed int :failed int}
        :resources {:tokens-used int :cost-usd double}
        :meta-loop {:status keyword :pending-improvements int}
        :alerts []}")

  (get-workflow-list [this criteria]
    "Get list of workflows with optional filtering.
     criteria: {:status keyword :phase keyword :limit int}
     Returns sequence of workflow summary maps.")

  (get-workflow-detail [this workflow-id]
    "Get detailed information about a specific workflow.
     Returns:
       {:header {:id :status :phase :created-at :updated-at}
        :timeline [{:phase :status :started-at :completed-at}]
        :current-task {:description :agent :status}
        :artifacts [{:id :type :created-at}]
        :logs [{:timestamp :level :event :message}]}")

  (get-meta-loop-status [this]
    "Get meta-loop (operator) status.
     Returns:
       {:signals [{:type :timestamp :data}]
        :pending-improvements [{:id :type :rationale :confidence}]
        :recent-improvements [{:id :type :status :applied-at}]}")

  (subscribe [this topics callback]
    "Subscribe to events for polling-based updates.
     topics: #{:workflow-events :meta-loop-events :system-events}
     callback: (fn [event] ...)
     Returns subscription-id (uuid).
     
     Note: Uses polling mechanism for Babashka compatibility (no threads).")

  (unsubscribe [this subscription-id]
    "Unsubscribe from events.
     Returns true if subscription was removed.")

  (poll-events [this subscription-id]
    "Poll for events on a subscription.
     Returns sequence of events since last poll."))
