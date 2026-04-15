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

(ns ai.miniforge.agent.interface.messaging
  "Inter-agent messaging public API."
  (:require
   [ai.miniforge.agent.interface.protocols.messaging :as msg-proto]
   [ai.miniforge.agent.protocols.impl.messaging :as msg-impl]
   [ai.miniforge.agent.protocols.records.messaging :as msg-records]))

;------------------------------------------------------------------------------ Layer 0
;; Messaging operations

(def create-message-router msg-records/create-message-router)
(def create-agent-messaging msg-records/create-agent-messaging)
(def send-clarification-request msg-records/send-clarification-request)
(def send-concern msg-records/send-concern)
(def send-suggestion msg-records/send-suggestion)
(def get-clarification-requests msg-records/get-clarification-requests)
(def get-concerns msg-records/get-concerns)
(def get-suggestions msg-records/get-suggestions)
(def Message msg-impl/Message)
(def MessageType msg-impl/MessageType)

;------------------------------------------------------------------------------ Layer 0
;; N3-conformant event emission via requiring-resolve (no hard compile-time dep)

(defn- emit-messaging-event!
  "Emit an inter-agent messaging event to the event stream.
   Uses requiring-resolve so the agent component has no hard compile-time
   dependency on event-stream. Safe no-op when stream or workflow-id is absent.

   event-kw  - :sent or :received
   from      - source agent keyword/id
   to        - destination agent keyword/id
   msg-type  - optional message type keyword"
  [stream workflow-id event-kw from to msg-type]
  (when (and stream workflow-id)
    (try
      (when-let [publish-fn (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (let [constructor-sym (case event-kw
                                :sent     'ai.miniforge.event-stream.interface/inter-agent-message-sent
                                :received 'ai.miniforge.event-stream.interface/inter-agent-message-received)]
          (when-let [event-fn (requiring-resolve constructor-sym)]
            (publish-fn stream (event-fn stream workflow-id from to msg-type)))))
      (catch Exception _
        ;; Never let observability break the messaging path
        nil))))

;------------------------------------------------------------------------------ Layer 1
;; Messaging operations with N3 event emission

(defn send-message
  "Send a message from agent-messaging to the target specified in message-data.
   Emits a :agent/message-sent N3 event when the messaging object carries
   an :event-stream and :workflow-id."
  [agent-messaging message-data]
  (let [result (msg-proto/send-message agent-messaging message-data)]
    (emit-messaging-event!
     (:event-stream agent-messaging)
     (:workflow-id agent-messaging)
     :sent
     (:agent-id agent-messaging)
     (:to-agent message-data)
     (:type message-data))
    result))

(defn receive-messages
  [agent-messaging]
  (msg-proto/receive-messages agent-messaging))

(defn respond-to-message
  "Respond to a message, emitting a :agent/message-sent N3 event.
   The response is treated as a sent message from this agent back to the
   original sender."
  [agent-messaging original-message response-content]
  (let [result (msg-proto/respond-to-message agent-messaging original-message response-content)]
    (emit-messaging-event!
     (:event-stream agent-messaging)
     (:workflow-id agent-messaging)
     :sent
     (:agent-id agent-messaging)
     (or (:message/from-agent original-message) (:from-agent original-message))
     :response)
    result))

(defn route-message
  "Route a message to its recipient, emitting a :agent/message-received N3 event.
   The event-stream and workflow-id are read from the message map itself — they
   must be present for event emission to occur (safe no-op otherwise)."
  [router message]
  (let [result (msg-proto/route-message router message)]
    (emit-messaging-event!
     (or (:event-stream message) (:message/event-stream message))
     (or (:workflow/id message) (:workflow-id message))
     :received
     (or (:message/from-agent message) (:from-agent message))
     (or (:message/to-agent message) (:to-agent message))
     (or (:message/type message) (:type message)))
    result))

(defn get-messages-for-agent
  [router agent-id workflow-id]
  (msg-proto/get-messages-for-agent router agent-id workflow-id))

(defn get-messages-by-workflow
  [router workflow-id]
  (msg-proto/get-messages-by-workflow router workflow-id))

(defn clear-workflow-messages
  [router workflow-id]
  (msg-proto/clear-messages router workflow-id))

(defn validate-message
  [message]
  (msg-impl/validate-message-impl message))
