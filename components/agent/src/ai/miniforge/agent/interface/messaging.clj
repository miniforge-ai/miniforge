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
  "Inter-agent messaging public API.

   Thin pass-through interface per Polylith convention. Event emission
   is handled in the impl layer (send-message-impl), not here."
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

;------------------------------------------------------------------------------ Layer 1
;; Messaging operations (thin pass-throughs)

(defn send-message
  "Send a message from agent-messaging to the target specified in message-data.
   Event emission is handled by the impl layer."
  [agent-messaging message-data]
  (msg-proto/send-message agent-messaging message-data))

(defn receive-messages
  [agent-messaging]
  (msg-proto/receive-messages agent-messaging))

(defn respond-to-message
  "Respond to a message. Event emission is handled by the impl layer."
  [agent-messaging original-message response-content]
  (msg-proto/respond-to-message agent-messaging original-message response-content))

(defn route-message
  "Route a message to its recipient."
  [router message]
  (msg-proto/route-message router message))

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
