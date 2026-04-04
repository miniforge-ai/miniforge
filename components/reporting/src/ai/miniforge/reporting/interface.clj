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

(ns ai.miniforge.reporting.interface
  "Public API for the reporting component.
   Provides system status monitoring and workflow reporting."
  (:require
   [ai.miniforge.reporting.core :as core]
   [ai.miniforge.reporting.protocol :as proto]
   [ai.miniforge.reporting.views.system :as view-system]
   [ai.miniforge.reporting.views.workflow :as view-workflow]
   [ai.miniforge.reporting.views.meta :as view-meta]
   [ai.miniforge.reporting.views.edn :as view-edn]))

;------------------------------------------------------------------------------ Layer 0
;; Protocol re-exports

(def ReportingService proto/ReportingService)

;------------------------------------------------------------------------------ Layer 1
;; Service creation

(def create-reporting-service
  "Create a reporting service.

   The reporting service aggregates data from multiple components
   to provide unified views of system state and workflow execution.

   Options:
   - :workflow-component - Workflow component instance
   - :orchestrator-component - Orchestrator component instance
   - :operator-component - Operator component instance
   - :artifact-store - Artifact store instance
   - :logger - Logger instance

   Example:
     (def reporting (create-reporting-service
                     {:workflow-component wf
                      :operator-component op
                      :logger logger}))"
  core/create-reporting-service)

;------------------------------------------------------------------------------ Layer 2
;; Status queries

(defn get-system-status
  "Get overall system status.

   Returns:
     {:workflows {:active int :pending int :completed int :failed int}
      :resources {:tokens-used int :cost-usd double}
      :meta-loop {:status keyword :pending-improvements int}
      :alerts [{:type :severity :message}]}

   Example:
     (get-system-status reporting)"
  [reporting-service]
  (proto/get-system-status reporting-service))

(defn get-workflow-list
  "Get list of workflows with optional filtering.

   criteria:
   - :status - Filter by status (:pending :running :completed :failed)
   - :phase - Filter by phase (:spec :plan :design :implement etc.)
   - :limit - Max results (default 100)

   Returns sequence of workflow summary maps.

   Example:
     (get-workflow-list reporting {:status :running})
     (get-workflow-list reporting {:phase :implement :limit 10})"
  ([reporting-service] (get-workflow-list reporting-service {}))
  ([reporting-service criteria]
   (proto/get-workflow-list reporting-service criteria)))

(defn get-workflow-detail
  "Get detailed information about a specific workflow.

   Returns:
     {:header {:id :status :phase :created-at :updated-at}
      :timeline [{:phase :status :started-at :completed-at}]
      :current-task {:description :agent :status}
      :artifacts [{:id :type :created-at}]
      :logs [{:timestamp :level :event :message}]}

   Returns nil if workflow not found.

   Example:
     (get-workflow-detail reporting workflow-id)"
  [reporting-service workflow-id]
  (proto/get-workflow-detail reporting-service workflow-id))

(defn get-meta-loop-status
  "Get meta-loop (operator) status.

   Returns:
     {:signals [{:type :timestamp :data}]
      :pending-improvements [{:id :type :rationale :confidence}]
      :recent-improvements [{:id :type :status :applied-at}]}

   Example:
     (get-meta-loop-status reporting)"
  [reporting-service]
  (proto/get-meta-loop-status reporting-service))

;------------------------------------------------------------------------------ Layer 3
;; Event subscriptions (polling-based for BB compatibility)

(defn subscribe
  "Subscribe to events for polling-based updates.

   topics: Set of topic keywords
   - :workflow-events - Workflow state changes
   - :meta-loop-events - Operator signals and improvements
   - :system-events - System-level events

   callback: Function that receives event map when polled
   - Event format: {:event/topic :event/type :event/data :event/timestamp}

   Returns subscription-id (uuid).

   Note: This uses polling rather than push notifications for
   Babashka compatibility (no background threads). Call poll-events
   periodically to receive events.

   Example:
     (def sub-id (subscribe reporting #{:workflow-events}
                            (fn [event] (println \"Event:\" event))))"
  [reporting-service topics callback]
  (proto/subscribe reporting-service topics callback))

(defn unsubscribe
  "Unsubscribe from events.

   Returns true if subscription was removed.

   Example:
     (unsubscribe reporting sub-id)"
  [reporting-service subscription-id]
  (proto/unsubscribe reporting-service subscription-id))

(defn poll-events
  "Poll for events on a subscription.

   Returns sequence of events since last poll.
   Also invokes the callback for each event.

   Example:
     (poll-events reporting sub-id)"
  [reporting-service subscription-id]
  (proto/poll-events reporting-service subscription-id))

;------------------------------------------------------------------------------ Layer 4
;; Rendering functions

(defn render-system-overview
  "Render system overview with status information.

   See ai.miniforge.reporting.views.system for full documentation.

   Example:
     (render-system-overview {:status :running :version \"dev\"})"
  [system-data]
  (view-system/render-system-overview system-data))

(defn render-workflow-list
  "Render a list of workflows as a table.

   See ai.miniforge.reporting.views.workflow for full documentation.

   Example:
     (render-workflow-list [{:id 1 :name \"Pipeline\" :status :running}])"
  [workflows]
  (view-workflow/render-workflow-list workflows))

(defn render-workflow-detail
  "Render detailed workflow information.

   See ai.miniforge.reporting.views.workflow for full documentation.

   Example:
     (render-workflow-detail {:id 1 :name \"Pipeline\" :status :running})"
  [workflow]
  (view-workflow/render-workflow-detail workflow))

(defn render-meta-loop
  "Render meta-loop status and optimization information.

   See ai.miniforge.reporting.views.meta for full documentation.

   Example:
     (render-meta-loop {:status :running :cycle 42})"
  [meta-data]
  (view-meta/render-meta-loop meta-data))

(defn render-edn
  "Render arbitrary data as pretty-printed EDN.

   See ai.miniforge.reporting.views.edn for full documentation.

   Example:
     (render-edn {:status :running})"
  [data]
  (view-edn/render-edn data))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.interface :as wf])
  (require '[ai.miniforge.operator.interface :as op])
  (require '[ai.miniforge.logging.interface :as log])

  ;; Create components
  (def logger (log/create-logger {:min-level :debug :output :human}))
  (def wf-mgr (wf/create-workflow))
  (def operator (op/create-operator))

  ;; Create reporting service
  (def reporting (create-reporting-service
                  {:workflow-component wf-mgr
                   :operator-component operator
                   :logger logger}))

  ;; Get system status
  (get-system-status reporting)

  ;; Get workflow list
  (get-workflow-list reporting)
  (get-workflow-list reporting {:status :running})

  ;; Get workflow detail
  (def wf-id (wf/start wf-mgr {:title "Test" :description "Test workflow"} {}))
  (get-workflow-detail reporting wf-id)

  ;; Get meta-loop status
  (get-meta-loop-status reporting)

  ;; Subscribe to events
  (def sub-id (subscribe reporting #{:workflow-events}
                         (fn [event] (println "Event:" event))))

  ;; Poll for events
  (poll-events reporting sub-id)

  ;; Unsubscribe
  (unsubscribe reporting sub-id)

  :end)
