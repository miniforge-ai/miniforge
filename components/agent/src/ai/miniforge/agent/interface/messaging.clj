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

(def create-message-router
  "Create a message router. No args. Returns a MessageRouter record backed by
   atoms for per-workflow messages and per-agent inboxes."
  msg-records/create-message-router)

(def create-agent-messaging
  "Create messaging capability for an agent. Args: agent-id (keyword),
   instance-id (uuid), workflow-id (uuid), router, optional event-stream.
   Returns an AgentMessaging record (an InterAgentMessaging)."
  msg-records/create-agent-messaging)

(def send-clarification-request
  "Send a :clarification-request to another agent. Args: agent-messaging,
   to-agent (keyword), content (string). Returns {:message map :event map}."
  msg-records/send-clarification-request)

(def send-concern
  "Send a :concern to another agent. Args: agent-messaging, to-agent
   (keyword), content (string). Returns {:message map :event map}."
  msg-records/send-concern)

(def send-suggestion
  "Send a :suggestion to another agent. Args: agent-messaging, to-agent
   (keyword), content (string). Returns {:message map :event map}."
  msg-records/send-suggestion)

(def get-clarification-requests
  "Return received messages of type :clarification-request. Arg:
   agent-messaging. Returns a sequence of message maps."
  msg-records/get-clarification-requests)

(def get-concerns
  "Return received messages of type :concern. Arg: agent-messaging.
   Returns a sequence of message maps."
  msg-records/get-concerns)

(def get-suggestions
  "Return received messages of type :suggestion. Arg: agent-messaging.
   Returns a sequence of message maps."
  msg-records/get-suggestions)

(def Message
  "Malli schema (a [:map ...] vector) for an inter-agent message, per N1
   §6.2.2. Required keys include :message/id :message/type :message/from-agent
   :message/to-agent :message/workflow-id :message/content :message/timestamp."
  msg-impl/Message)

(def MessageType
  "Malli schema (a [:enum ...] vector) of valid message types:
   :clarification-request :clarification-response :concern :suggestion."
  msg-impl/MessageType)

;------------------------------------------------------------------------------ Layer 1
;; Messaging operations (thin pass-throughs)

(defn send-message
  "Send a message from agent-messaging to the target specified in message-data.
   Event emission is handled by the impl layer."
  [agent-messaging message-data]
  (msg-proto/send-message agent-messaging message-data))

(defn receive-messages
  "Return all messages received by this agent. Arg: agent-messaging.
   Returns a sequence of message maps."
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
  "Return messages for a specific agent in a workflow. Args: router, agent-id,
   workflow-id. Returns a sequence of message maps (the agent's inbox)."
  [router agent-id workflow-id]
  (msg-proto/get-messages-for-agent router agent-id workflow-id))

(defn get-messages-by-workflow
  "Return all messages in a workflow, ordered by timestamp. Args: router,
   workflow-id. Returns a sequence of message maps."
  [router workflow-id]
  (msg-proto/get-messages-by-workflow router workflow-id))

(defn clear-workflow-messages
  "Clear all messages for a workflow. Args: router, workflow-id. Returns nil."
  [router workflow-id]
  (msg-proto/clear-messages router workflow-id))

(defn validate-message
  "Validate a message map against the Message schema. Arg: message.
   Returns {:valid? boolean :errors [...]}."
  [message]
  (msg-impl/validate-message-impl message))
