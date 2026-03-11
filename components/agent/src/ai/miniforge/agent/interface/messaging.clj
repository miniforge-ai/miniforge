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

(defn send-message
  [agent-messaging message-data]
  (msg-proto/send-message agent-messaging message-data))

(defn receive-messages
  [agent-messaging]
  (msg-proto/receive-messages agent-messaging))

(defn respond-to-message
  [agent-messaging original-message response-content]
  (msg-proto/respond-to-message agent-messaging original-message response-content))

(defn route-message
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
