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

(ns ai.miniforge.agent.interface.protocols.messaging
  "Public protocols for inter-agent messaging and communication.

   Enables agents to send messages to each other during execution.
   Message types: :clarification-request, :concern, :suggestion

   Implements N1 Architecture Specification section 6.2.")

;------------------------------------------------------------------------------ Layer 0
;; Inter-Agent Messaging Protocol

(defprotocol InterAgentMessaging
  "Protocol for sending and receiving messages between agents.

   Agents can communicate via clarification requests, concerns, and suggestions."

  (send-message [this message-data]
    "Send a message to another agent.
     Returns {:message message-map :event event-map}")

  (receive-messages [this]
    "Get all messages received by this agent.
     Returns sequence of message maps.")

  (respond-to-message [this original-message response-content]
    "Send a response to a received message.
     Returns {:message response-map :event event-map}"))

(defprotocol MessageRouter
  "Protocol for routing and delivering messages between agents.

   The router maintains message queues and handles delivery."

  (route-message [this message]
    "Route a message to the recipient agent.
     Returns the routed message with delivery metadata.")

  (get-messages-for-agent [this agent-id workflow-id]
    "Get all messages for a specific agent in a workflow.
     Returns sequence of messages.")

  (get-messages-by-workflow [this workflow-id]
    "Get all messages in a workflow.
     Returns sequence of messages ordered by timestamp.")

  (clear-messages [this workflow-id]
    "Clear all messages for a workflow.
     Returns nil."))

(defprotocol MessageValidator
  "Protocol for validating message structure and content."

  (validate-message [this message]
    "Validate message conforms to schema.
     Returns {:valid? boolean :errors [...]}"))
