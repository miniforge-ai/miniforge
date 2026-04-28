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

(ns ai.miniforge.adapter-miniforge.interface
  "Miniforge native adapter for the control plane.

   Bridges miniforge's event-stream events to the control plane's
   normalized agent model. This adapter subscribes to the event
   stream and translates native miniforge events into control plane
   agent registration, heartbeats, and state changes.

   Layer 0: Event mapping
   Layer 1: Protocol implementation
   Layer 2: Factory and subscription"
  (:require
   [ai.miniforge.control-plane.interface :as control-plane]
   [ai.miniforge.control-plane-adapter.interface :as proto]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Event → status mapping

(def ^:private event-type->status
  "Map miniforge event types to normalized control plane statuses."
  {:workflow-started   :running
   :phase-started      :running
   :phase-completed    :idle
   :agent-started      :running
   :agent-completed    :idle
   :agent-failed       :failed
   :workflow-completed :completed
   :workflow-failed    :failed})

(defn- event->agent-info
  "Extract agent info from a miniforge event."
  [event]
  (let [wf-id (:workflow/id event)
        metadata (cond-> {:workflow-id wf-id
                          :event-type (:event/type event)}
                   (:workflow/spec event) (assoc :workflow-spec (:workflow/spec event))
                   (:workflow/phase event) (assoc :workflow-phase (:workflow/phase event))
                   (:agent/context event) (assoc :agent-context (:agent/context event))
                   (:phase/context event) (assoc :phase-context (:phase/context event))
                   (:message event) (assoc :message (:message event)))]
    {:agent/vendor :miniforge
     :agent/external-id (str wf-id)
     :agent/name (str (control-plane/t :adapter/miniforge-prefix) (or (:workflow/name event)
                                         (:message event)
                                         wf-id))
     :agent/capabilities #{:code-generation :test-writing :code-review}
     :agent/metadata metadata}))

;------------------------------------------------------------------------------ Layer 1
;; Protocol implementation

(defrecord MiniforgeAdapter [event-stream]
  proto/ControlPlaneAdapter

  (adapter-id [_] :miniforge)

  (discover-agents [_ _config]
    ;; Miniforge agents are discovered via event subscription,
    ;; not polling. Return empty — the subscription bridge handles it.
    [])

  (poll-agent-status [_ agent-record]
    ;; Status comes from event stream subscription, not polling.
    ;; Return current known status.
    {:status (:agent/status agent-record)})

  (deliver-decision [_ _agent-record _decision-resolution]
    ;; For miniforge native agents, decisions flow through
    ;; the approval system in event-stream.
    ;; TODO: Wire to es/submit-approval when control-action integration is ready.
    {:delivered? true})

  (send-command [_ agent-record command]
    (if-let [control-state (:control-state (:agent/metadata agent-record))]
      (let [dispatch {:pause es/pause! :resume es/resume! :cancel es/cancel!}]
        (when-let [f (get dispatch command)]
          (f control-state))
        {:success? true})
      {:success? false :error (control-plane/t :adapter/no-control-state)})))

;------------------------------------------------------------------------------ Layer 2
;; Factory and subscription

(defn create-adapter
  "Create a miniforge native adapter.

   Arguments:
   - event-stream - The event stream instance to subscribe to

   Example:
     (def adapter (create-adapter event-stream))"
  [event-stream]
  (->MiniforgeAdapter event-stream))

(defn create-event-bridge
  "Create a subscription bridge that translates miniforge events
   into control plane agent updates.

   Arguments:
   - event-stream - Event stream to subscribe to
   - on-agent-event - Callback fn called with {:event-type kw :agent-info map :status kw}

   Returns: Subscription key (for unsubscribing later).

   Example:
     (create-event-bridge stream
       (fn [{:keys [agent-info status]}]
         (cp/register-or-update! registry agent-info status)))"
  [event-stream on-agent-event]
  (let [relevant-events (set (keys event-type->status))
        sub-key :control-plane-bridge]
    (es/subscribe! event-stream sub-key
                   (fn [event]
                     (when (contains? relevant-events (:event/type event))
                       (on-agent-event
                        {:event-type (:event/type event)
                         :agent-info (event->agent-info event)
                         :status (get event-type->status (:event/type event))}))))
    sub-key))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Example wiring:
  ;; (def stream (es/create-event-stream))
  ;; (def adapter (create-adapter stream))
  ;; (create-event-bridge stream
  ;;   (fn [{:keys [agent-info status]}]
  ;;     (println "Agent:" (:agent/name agent-info) "Status:" status)))
  :end)
