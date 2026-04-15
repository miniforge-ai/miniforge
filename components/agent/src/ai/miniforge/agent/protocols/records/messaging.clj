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

(ns ai.miniforge.agent.protocols.records.messaging
  "Records that implement inter-agent messaging protocols.

   AgentMessaging - Messaging capability for an agent
   MessageRouter - Routes and delivers messages between agents
   MessageValidatorImpl - Validates message structure"
  (:require
   [ai.miniforge.agent.interface.protocols.messaging :as p]
   [ai.miniforge.agent.protocols.impl.messaging :as impl]))

;------------------------------------------------------------------------------ Layer 0
;; Records

(defrecord AgentMessaging [agent-id instance-id workflow-id router event-stream]
  p/InterAgentMessaging
  (send-message [this message-data]
    (impl/send-message-impl this message-data))

  (receive-messages [this]
    (impl/receive-messages-impl this))

  (respond-to-message [this original-message response-content]
    (impl/respond-to-message-impl this original-message response-content)))

(defrecord MessageRouter [messages inboxes]
  p/MessageRouter
  (route-message [this message]
    (impl/route-message-impl this message))

  (get-messages-for-agent [this agent-id workflow-id]
    (impl/get-messages-for-agent-impl this agent-id workflow-id))

  (get-messages-by-workflow [this workflow-id]
    (impl/get-messages-by-workflow-impl this workflow-id))

  (clear-messages [this workflow-id]
    (impl/clear-messages-impl this workflow-id)))

(defrecord MessageValidatorImpl []
  p/MessageValidator
  (validate-message [_ message]
    (impl/validate-message-impl message)))

;------------------------------------------------------------------------------ Layer 1
;; Factory Functions

(defn create-message-router
  "Create a new message router instance.

   The router maintains:
   - messages: atom with map of workflow-id -> [messages]
   - inboxes: atom with map of [workflow-id agent-id] -> [messages]

   Example:
     (create-message-router)"
  []
  (->MessageRouter
   (atom {})  ;; messages by workflow
   (atom {}))) ;; inboxes by [workflow-id agent-id]

(defn create-agent-messaging
  "Create messaging capability for an agent.

   Required:
   - agent-id         - Agent role keyword
   - instance-id      - Agent instance UUID
   - workflow-id      - Workflow UUID
   - router           - MessageRouter instance

   Optional:
   - event-stream     - Event stream atom for N3-compliant event emission.
                        When provided, message-sent/received events are
                        published via the event-stream component.

   Example:
     (create-agent-messaging :implementer agent-instance-id workflow-id router)
     (create-agent-messaging :implementer agent-instance-id workflow-id router stream)"
  ([agent-id instance-id workflow-id router]
   (->AgentMessaging agent-id instance-id workflow-id router nil))
  ([agent-id instance-id workflow-id router event-stream]
   (->AgentMessaging agent-id instance-id workflow-id router event-stream)))

(defn create-message-validator
  "Create a message validator instance.

   Example:
     (create-message-validator)"
  []
  (->MessageValidatorImpl))

;------------------------------------------------------------------------------ Layer 2
;; Helper Functions

(defn send-clarification-request
  "Send a clarification request to another agent.

   Convenience function for common message type."
  [agent-messaging to-agent content]
  (p/send-message agent-messaging
                  {:type :clarification-request
                   :to-agent to-agent
                   :content content}))

(defn send-concern
  "Send a concern to another agent.

   Convenience function for common message type."
  [agent-messaging to-agent content]
  (p/send-message agent-messaging
                  {:type :concern
                   :to-agent to-agent
                   :content content}))

(defn send-suggestion
  "Send a suggestion to another agent.

   Convenience function for common message type."
  [agent-messaging to-agent content]
  (p/send-message agent-messaging
                  {:type :suggestion
                   :to-agent to-agent
                   :content content}))

(defn get-unread-messages
  "Get messages not yet marked as read.

   Note: This is a simple implementation.
   Future: Track read/unread state per message."
  [agent-messaging]
  (p/receive-messages agent-messaging))

(defn filter-messages-by-type
  "Filter messages by type."
  [messages message-type]
  (filter #(= (:message/type %) message-type) messages))

(defn get-clarification-requests
  "Get all clarification requests received."
  [agent-messaging]
  (filter-messages-by-type
   (p/receive-messages agent-messaging)
   :clarification-request))

(defn get-concerns
  "Get all concerns received."
  [agent-messaging]
  (filter-messages-by-type
   (p/receive-messages agent-messaging)
   :concern))

(defn get-suggestions
  "Get all suggestions received."
  [agent-messaging]
  (filter-messages-by-type
   (p/receive-messages agent-messaging)
   :suggestion))
