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

(ns ai.miniforge.agent.protocols.impl.messaging
  "Implementation functions for inter-agent messaging protocols.

   Implements N1 Architecture Specification section 6.2."
  (:require
   [ai.miniforge.response.interface :as response]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Message Schema

(def MessageType
  "Valid message types for inter-agent communication."
  [:enum
   :clarification-request
   :clarification-response
   :concern
   :suggestion])

(def Message
  "Schema for inter-agent message.
   Conforms to N1 Architecture section 6.2.2."
  [:map
   [:message/id :uuid]
   [:message/type MessageType]
   [:message/from-agent :keyword]
   [:message/from-instance-id :uuid]
   [:message/to-agent :keyword]
   [:message/workflow-id :uuid]
   [:message/content :string]
   [:message/timestamp inst?]
   ;; Optional fields
   [:message/parent-id {:optional true} :uuid]
   [:message/metadata {:optional true} :map]])

;------------------------------------------------------------------------------ Layer 1
;; Message Creation

(defn create-message
  "Create a message map with required fields.
   Auto-generates id and timestamp if not provided."
  [{:keys [type from-agent from-instance-id to-agent
           workflow-id content parent-id metadata]
    :or {parent-id nil metadata {}}}]
  (cond-> {:message/id (random-uuid)
           :message/type type
           :message/from-agent from-agent
           :message/from-instance-id from-instance-id
           :message/to-agent to-agent
           :message/workflow-id workflow-id
           :message/content content
           :message/timestamp (java.util.Date.)}
    parent-id (assoc :message/parent-id parent-id)
    (seq metadata) (assoc :message/metadata metadata)))

(defn create-clarification-request
  "Create a clarification request message."
  [from-agent from-instance-id to-agent workflow-id content]
  (create-message
   {:type :clarification-request
    :from-agent from-agent
    :from-instance-id from-instance-id
    :to-agent to-agent
    :workflow-id workflow-id
    :content content}))

(defn create-clarification-response
  "Create a clarification response message."
  [from-agent from-instance-id to-agent workflow-id content parent-id]
  (create-message
   {:type :clarification-response
    :from-agent from-agent
    :from-instance-id from-instance-id
    :to-agent to-agent
    :workflow-id workflow-id
    :content content
    :parent-id parent-id}))

(defn create-concern
  "Create a concern message."
  [from-agent from-instance-id to-agent workflow-id content]
  (create-message
   {:type :concern
    :from-agent from-agent
    :from-instance-id from-instance-id
    :to-agent to-agent
    :workflow-id workflow-id
    :content content}))

(defn create-suggestion
  "Create a suggestion message."
  [from-agent from-instance-id to-agent workflow-id content]
  (create-message
   {:type :suggestion
    :from-agent from-agent
    :from-instance-id from-instance-id
    :to-agent to-agent
    :workflow-id workflow-id
    :content content}))

;------------------------------------------------------------------------------ Layer 2
;; Message Validation

(def message-validator
  "Compiled Malli validator for Message schema."
  (m/validator Message))

(def message-explainer
  "Malli explainer for detailed validation errors."
  (m/explainer Message))

(defn validate-message-impl
  "Validate message against schema.
   Returns {:valid? boolean :errors [...]}"
  [message]
  (if (message-validator message)
    {:valid? true :errors []}
    {:valid? false
     :errors [(str "Message validation failed: " (m/explain Message message))]}))

(defn valid-message?
  "Check if message is valid."
  [message]
  (message-validator message))

;------------------------------------------------------------------------------ Layer 3
;; Message Routing

(defn route-message-impl
  "Route message to recipient agent.
   Adds routing metadata and stores in router."
  [router message]
  (let [routed-msg (assoc message
                          :message/routed-at (java.util.Date.)
                          :message/status :delivered)
        workflow-id (:message/workflow-id message)
        to-agent (:message/to-agent message)]
    ;; Add to workflow messages
    (swap! (:messages router)
           update workflow-id
           (fnil conj [])
           routed-msg)
    ;; Add to agent inbox
    (swap! (:inboxes router)
           update [workflow-id to-agent]
           (fnil conj [])
           routed-msg)
    routed-msg))

(defn get-messages-for-agent-impl
  "Get all messages for specific agent in workflow."
  [router agent-id workflow-id]
  (get @(:inboxes router) [workflow-id agent-id] []))

(defn get-messages-by-workflow-impl
  "Get all messages in workflow, ordered by timestamp."
  [router workflow-id]
  (let [messages (get @(:messages router) workflow-id [])]
    (sort-by :message/timestamp messages)))

(defn clear-messages-impl
  "Clear all messages for workflow."
  [router workflow-id]
  (swap! (:messages router) dissoc workflow-id)
  ;; Clear all inboxes for this workflow
  (swap! (:inboxes router)
         (fn [inboxes]
           (into {}
                 (remove (fn [[[wf-id _] _]]
                           (= wf-id workflow-id))
                         inboxes))))
  nil)

;------------------------------------------------------------------------------ Layer 4
;; Event Creation (N3-compliant fallback for when no stream is available)

(defn create-message-sent-event
  "Create a minimal agent/message-sent event per N3 section 3.7.

   This fallback is used when the event-stream component is unavailable.
   When an event stream is present, prefer emit-inter-agent-event! which
   goes through create-envelope and includes event/sequence-number."
  [message]
  {:event/type            :agent/message-sent
   :event/id              (random-uuid)
   :event/timestamp       (java.util.Date.)
   :event/version         "1.0.0"
   :event/sequence-number 0

   :from-agent/id         (:message/from-agent message)
   :from-agent/instance-id (:message/from-instance-id message)
   :to-agent/id           (:message/to-agent message)
   :workflow/id           (:message/workflow-id message)

   :message-type          (:message/type message)
   :message-content       (:message/content message)

   :message (format "Sending %s to %s: %s"
                    (name (:message/type message))
                    (name (:message/to-agent message))
                    (:message/content message))})

(defn create-message-received-event
  "Create a minimal agent/message-received event per N3 section 3.7.

   This fallback is used when the event-stream component is unavailable."
  [message]
  {:event/type            :agent/message-received
   :event/id              (random-uuid)
   :event/timestamp       (java.util.Date.)
   :event/version         "1.0.0"
   :event/sequence-number 0

   :from-agent/id         (:message/from-agent message)
   :to-agent/id           (:message/to-agent message)
   :workflow/id           (:message/workflow-id message)

   :message-type          (:message/type message)
   :message-content       (:message/content message)

   :message (format "Received %s from %s: %s"
                    (name (:message/type message))
                    (name (:message/from-agent message))
                    (:message/content message))})

;------------------------------------------------------------------------------ Layer 5
;; N3-compliant event emission (via event-stream create-envelope, includes sequence-number)

(defn emit-inter-agent-event!
  "Emit an N3-compliant inter-agent messaging event to the event-stream.

   Uses the event-stream component's inter-agent-message-sent or
   inter-agent-message-received constructor, which wraps create-envelope
   and therefore includes the required event/sequence-number field.

   Uses requiring-resolve to avoid a hard compile-time dependency on
   the event-stream component. Silently no-ops on any failure.

   Arguments:
   - stream          — event-stream atom (from :event-stream on context)
   - workflow-id     — UUID of the enclosing workflow
   - from-agent      — keyword identifying the sending agent
   - to-agent        — keyword identifying the receiving agent
   - message-type    — keyword (:clarification-request, :concern, :suggestion, ...)
   - constructor-sym — fully-qualified symbol for the event-stream constructor var

   Returns the published event map, or nil when unavailable."
  [stream workflow-id from-agent to-agent message-type constructor-sym]
  (try
    (when stream
      (let [publish-fn  (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)
            constructor (requiring-resolve constructor-sym)]
        (publish-fn stream
                    (constructor stream workflow-id from-agent to-agent message-type))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 5
;; InterAgentMessaging protocol implementations

(defn publish-to-event-stream!
  "Publish a pre-built event to the event stream if available (no hard dep on event-stream).
   Used as fallback when emit-inter-agent-event! cannot resolve the constructors."
  [agent-messaging event]
  (try
    (when-let [stream (:event-stream agent-messaging)]
      ((requiring-resolve 'ai.miniforge.event-stream.interface/publish!) stream event))
    (catch Exception _ nil)))

(defn send-message-impl
  "Send message from agent to another agent.

   Emits an N3-compliant :agent/message-sent event via the event-stream component
   (includes event/sequence-number from create-envelope). Falls back to a manually
   constructed event when the event-stream component is not available.

   Returns {:message message-map :event event-map}"
  [agent-messaging message-data]
  (let [message (create-message
                 (merge message-data
                        {:from-agent (:agent-id agent-messaging)
                         :from-instance-id (:instance-id agent-messaging)
                         :workflow-id (:workflow-id agent-messaging)}))
        router (:router agent-messaging)]
    ;; Validate message
    (let [validation (validate-message-impl message)]
      (when-not (:valid? validation)
        (response/throw-anomaly! :anomalies/incorrect
                                "Invalid message"
                                {:message message
                                 :errors (:errors validation)})))
    ;; Route message
    (let [routed-msg   (route-message-impl router message)
          stream       (:event-stream agent-messaging)
          workflow-id  (:message/workflow-id routed-msg)
          from-agent   (:message/from-agent routed-msg)
          to-agent     (:message/to-agent routed-msg)
          message-type (:message/type routed-msg)
          ;; Prefer N3-compliant event (with sequence-number via create-envelope).
          ;; Fall back to manual event construction when event-stream is absent.
          sent-event (or (emit-inter-agent-event!
                          stream workflow-id from-agent to-agent message-type
                          'ai.miniforge.event-stream.interface/inter-agent-message-sent)
                         (let [manual-event (create-message-sent-event routed-msg)]
                           (publish-to-event-stream! agent-messaging manual-event)
                           manual-event))
          ;; Emit :agent/message-received on behalf of the recipient agent.
          ;; Both events share the same routing transaction so observers see
          ;; sent → received in strict sequence-number order.
          _received-event (or (emit-inter-agent-event!
                               stream workflow-id from-agent to-agent message-type
                               'ai.miniforge.event-stream.interface/inter-agent-message-received)
                              (let [manual-event (create-message-received-event routed-msg)]
                                (publish-to-event-stream! agent-messaging manual-event)
                                manual-event))]
      {:message routed-msg
       :event sent-event})))

(defn receive-messages-impl
  "Get all messages received by agent."
  [agent-messaging]
  (let [router (:router agent-messaging)
        agent-id (:agent-id agent-messaging)
        workflow-id (:workflow-id agent-messaging)]
    (get-messages-for-agent-impl router agent-id workflow-id)))

(defn respond-to-message-impl
  "Send response to a received message.

   Emits an N3-compliant :agent/message-sent event for the response.
   The original message sender receives a :clarification-response routed to
   their agent-id, which the router stores in their inbox."
  [agent-messaging original-message response-content]
  (let [response-data {:type :clarification-response
                       :to-agent (:message/from-agent original-message)
                       :content response-content
                       :parent-id (:message/id original-message)}]
    (send-message-impl agent-messaging response-data)))
