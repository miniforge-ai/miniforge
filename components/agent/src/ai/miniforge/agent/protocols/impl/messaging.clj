(ns ai.miniforge.agent.protocols.impl.messaging
  "Implementation functions for inter-agent messaging protocols.

   Implements N1 Architecture Specification section 6.2."
  (:require
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
;; Event Creation (for N3 compliance)

(defn create-message-sent-event
  "Create agent/message-sent event per N3 section 3.7."
  [message]
  {:event/type :agent/message-sent
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"

   :from-agent/id (:message/from-agent message)
   :from-agent/instance-id (:message/from-instance-id message)
   :to-agent/id (:message/to-agent message)
   :workflow/id (:message/workflow-id message)

   :message-type (:message/type message)
   :message-content (:message/content message)

   :message (format "Sending %s to %s: %s"
                    (name (:message/type message))
                    (name (:message/to-agent message))
                    (:message/content message))})

(defn create-message-received-event
  "Create agent/message-received event per N3 section 3.7."
  [message]
  {:event/type :agent/message-received
   :event/id (random-uuid)
   :event/timestamp (java.util.Date.)
   :event/version "1.0.0"

   :from-agent/id (:message/from-agent message)
   :to-agent/id (:message/to-agent message)
   :workflow/id (:message/workflow-id message)

   :message-type (:message/type message)
   :message-content (:message/content message)

   :message (format "Received %s from %s: %s"
                    (name (:message/type message))
                    (name (:message/from-agent message))
                    (:message/content message))})

;------------------------------------------------------------------------------ Layer 5
;; InterAgentMessaging protocol implementations

(defn- publish-to-event-stream!
  "Publish an event to the event stream if available (no hard dep on event-stream)."
  [agent-messaging event]
  (try
    (when-let [stream (:event-stream agent-messaging)]
      ((requiring-resolve 'ai.miniforge.event-stream.interface/publish!) stream event))
    (catch Exception _ nil)))

(defn send-message-impl
  "Send message from agent to another agent.
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
        (throw (ex-info "Invalid message"
                        {:message message
                         :errors (:errors validation)}))))
    ;; Route message
    (let [routed-msg (route-message-impl router message)
          event (create-message-sent-event routed-msg)]
      ;; Publish to event stream if available
      (publish-to-event-stream! agent-messaging event)
      {:message routed-msg
       :event event})))

(defn receive-messages-impl
  "Get all messages received by agent."
  [agent-messaging]
  (let [router (:router agent-messaging)
        agent-id (:agent-id agent-messaging)
        workflow-id (:workflow-id agent-messaging)]
    (get-messages-for-agent-impl router agent-id workflow-id)))

(defn respond-to-message-impl
  "Send response to a received message."
  [agent-messaging original-message response-content]
  (let [response-data {:type :clarification-response
                       :to-agent (:message/from-agent original-message)
                       :content response-content
                       :parent-id (:message/id original-message)}]
    (send-message-impl agent-messaging response-data)))
