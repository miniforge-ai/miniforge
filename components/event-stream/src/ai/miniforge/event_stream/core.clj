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

(ns ai.miniforge.event-stream.core
  "Event bus and event constructors for workflow observability."
  (:require
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.event-stream.sinks :as sinks]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const event-version "1.0.0")

(def ^:private redirect-transition-type
  :transition/redirect)

(defn- redirect-target
  "Project a redirect target for legacy consumers.

   The workflow runner now emits :phase/transition-request. This helper keeps
   :phase/redirect-to available only when the transition request represents a
   redirect, so older event consumers do not break while the newer event shape
   remains authoritative."
  [result]
  (let [transition-request (phase/transition-request result)
        transition-type (get transition-request :transition/type)]
    (when (= redirect-transition-type transition-type)
      (get transition-request :transition/target))))

;------------------------------------------------------------------------------ Layer 0
;; Event persistence via configurable sinks
;; Note: File persistence moved to sinks.clj for configurability

;------------------------------------------------------------------------------ Layer 0
;; Event envelope constructor

(defn create-envelope
  "Create an event envelope with sequence numbering."
  [stream event-type workflow-id message]
  (let [seq-num (get-in @stream [:sequence-numbers workflow-id] 0)]
    (swap! stream assoc-in [:sequence-numbers workflow-id] (inc seq-num))
    {:event/type event-type
     :event/id (random-uuid)
     :event/timestamp (java.util.Date.)
     :event/version event-version
     :event/sequence-number seq-num
     :workflow/id workflow-id
     :message message}))

;------------------------------------------------------------------------------ Layer 1
;; Event bus operations

(defn create-event-stream
  "Create an event stream with configurable sinks.

   Options:
     :logger - Optional logger instance
     :sinks - Vector of sink functions (default: file sink)
     :config - Config map to create sinks from

   Returns: Event stream atom

   Example:
     ;; Default file sink
     (create-event-stream)

     ;; Custom sinks
     (create-event-stream {:sinks [(sinks/file-sink) (sinks/stdout-sink)]})

     ;; From config
     (create-event-stream {:config user-config})"
  [& [opts]]
  (let [;; Create sinks from config or use provided sinks or default
        event-sinks (cond
                      (:sinks opts) (:sinks opts)
                      (:config opts) (sinks/create-sinks-from-config (:config opts))
                      :else [(sinks/file-sink)])] ;; Default to file sink
    (atom {:events []
           :subscribers {}
           :filters {}
           :sequence-numbers {}
           :logger (:logger opts)
           :sinks event-sinks})))

(defn publish! [stream event]
  (let [{:keys [subscribers filters logger sinks]} @stream
        workflow-id (:workflow/id event)]
    ;; Persist event to configured sinks
    (doseq [sink sinks]
      (try
        (sink event)
        (catch Exception e
          (when logger
            (let [anomaly (response/from-exception e)]
              (log/warn logger :event-stream :sink-error
                       {:message "Event sink failed"
                        :data {:event-type (:event/type event)
                               :anomaly anomaly}}))))))

    ;; In-memory event log
    (swap! stream update :events conj event)

    ;; Notify subscribers
    (doseq [[sub-id callback] subscribers]
      (let [filter-fn (get filters sub-id (constantly true))]
        (when (filter-fn event)
          (try
            (callback event)
            (catch Exception e
              (when logger
                (let [anomaly (response/from-exception e)]
                  (log/error logger :event-stream :event/callback-error
                             {:message "Event callback failed"
                              :data {:subscriber-id sub-id
                                     :event-type (:event/type event)
                                     :anomaly anomaly}}))))))))
    (when logger
      (log/debug logger :event-stream :event/published
                 {:message "Event published"
                  :data {:event-type (:event/type event)
                         :workflow-id workflow-id
                         :sequence (:event/sequence-number event)}}))
    event))

(defn subscribe!
  ([stream subscriber-id callback]
   (subscribe! stream subscriber-id callback (constantly true)))
  ([stream subscriber-id callback filter-fn]
   (swap! stream
          (fn [s]
            (-> s
                (assoc-in [:subscribers subscriber-id] callback)
                (assoc-in [:filters subscriber-id] filter-fn))))
   subscriber-id))

(defn unsubscribe! [stream subscriber-id]
  (swap! stream
         (fn [s]
           (-> s
               (update :subscribers dissoc subscriber-id)
               (update :filters dissoc subscriber-id))))
  nil)

;------------------------------------------------------------------------------ Layer 2
;; Query API

(defn get-events [stream & [opts]]
  (let [{:keys [workflow-id event-type offset limit]} opts
        events (:events @stream)]
    (cond->> events
      workflow-id (filter #(= workflow-id (:workflow/id %)))
      event-type (filter #(= event-type (:event/type %)))
      offset (drop offset)
      limit (take limit)
      true vec)))

(defn get-latest-status [stream workflow-id & [agent-id]]
  (->> (:events @stream)
       (filter #(= :agent/status (:event/type %)))
       (filter #(= workflow-id (:workflow/id %)))
       (filter #(or (nil? agent-id) (= agent-id (:agent/id %))))
       last))

;------------------------------------------------------------------------------ Layer 3
;; Event constructors (N3 compliant)

(defn workflow-started [stream workflow-id & [spec]]
  (-> (create-envelope stream :workflow/started workflow-id "Workflow started")
      (cond-> spec (assoc :workflow/spec spec))))

(defn phase-started [stream workflow-id phase & [context]]
  (-> (create-envelope stream :workflow/phase-started workflow-id
                       (str (name phase) " phase started"))
      (assoc :workflow/phase phase)
      (cond-> context (assoc :phase/context context))))

(defn phase-completed [stream workflow-id phase & [result]]
  (let [outcome (get result :outcome :success)
        transition-request (phase/transition-request result)
        redirect-to (or (redirect-target result)
                        (:redirect-to result))]
    (-> (create-envelope stream :workflow/phase-completed workflow-id
                         (str (name phase) " phase " (name outcome)))
        (assoc :workflow/phase phase
               :phase/outcome outcome)
        (cond->
          (:duration-ms result) (assoc :phase/duration-ms (:duration-ms result))
          (:artifacts result) (assoc :phase/artifacts (:artifacts result))
          (:error result) (assoc :phase/error (:error result))
          transition-request (assoc :phase/transition-request transition-request)
          redirect-to (assoc :phase/redirect-to redirect-to)
          (:tokens result) (assoc :phase/tokens (:tokens result))
          (:cost-usd result) (assoc :phase/cost-usd (:cost-usd result))))))

(defn agent-chunk [stream workflow-id agent-id delta & [done?]]
  (-> (create-envelope stream :agent/chunk workflow-id
                       (if done? "Agent stream completed" "Agent streaming"))
      (assoc :agent/id agent-id
             :chunk/delta delta)
      (cond-> done? (assoc :chunk/done? true))))

(defn agent-status [stream workflow-id agent-id status-type message]
  (-> (create-envelope stream :agent/status workflow-id message)
      (assoc :agent/id agent-id
             :status/type status-type)))

(defn agent-tool-call
  "Publish a :agent/tool-call event carrying the tool name(s) the agent
   just invoked, per N3 §3.x. Replaces the generic :agent/status
   :tool-calling emission for consumers that want structured tool data.

   Fields:
     :tool/name            — single tool name when one tool fired in the block
     :tool/names           — vector of names when a single assistant block
                             included multiple tool_use items
     :tool/call-id         — provider-supplied id (Claude tool_use.id /
                             codex item id) when available
     :tool/args-preview    — truncated/digested args for diagnosis (bounded
                             to keep events small)"
  [stream workflow-id agent-id
   {:keys [tool-name tool-names tool-call-id tool-args-preview]}]
  (cond-> (create-envelope stream :agent/tool-call workflow-id
                           (str "Agent called tool"
                                (when tool-name (str ": " tool-name))))
    true                (assoc :agent/id agent-id)
    tool-name           (assoc :tool/name tool-name)
    (seq tool-names)    (assoc :tool/names (vec tool-names))
    tool-call-id        (assoc :tool/call-id tool-call-id)
    tool-args-preview   (assoc :tool/args-preview tool-args-preview)))

(defn workflow-completed [stream workflow-id status & [duration-ms opts]]
  (-> (create-envelope stream :workflow/completed workflow-id
                       (str "Workflow " (name status)))
      (assoc :workflow/status status)
      (cond-> duration-ms (assoc :workflow/duration-ms duration-ms)
              (:tokens opts) (assoc :workflow/tokens (:tokens opts))
              (:cost-usd opts) (assoc :workflow/cost-usd (:cost-usd opts))
              (:pr-info opts) (assoc :workflow/pr-info (:pr-info opts))
              (seq (:pr-infos opts)) (assoc :workflow/pr-infos (:pr-infos opts))
              (:workflow/evidence-bundle-id opts)
              (assoc :workflow/evidence-bundle-id (:workflow/evidence-bundle-id opts)))))

(defn workflow-failed [stream workflow-id error & [{:keys [failure/class]}]]
  (let [;; Handle anomaly maps, Throwables, and plain error maps
        anomaly-map (cond
                      (response/anomaly-map? error) error
                      (instance? Throwable error) (response/from-exception error)
                      (and (map? error) (:anomaly error)) (:anomaly error)
                      :else nil)
        error-map (cond
                    (instance? Throwable error)
                    {:message (.getMessage ^Throwable error)
                     :type (str (type error))}
                    (response/anomaly-map? error)
                    (response/anomaly->event-data error)
                    :else error)
        event-data (response/anomaly->event-data
                    (or anomaly-map
                        (response/make-anomaly :anomalies/fault
                                               (get error-map :message "unknown error"))))]
    (-> (create-envelope stream :workflow/failed workflow-id
                         (str "Workflow failed: " (:message error-map "unknown error")))
        (assoc :workflow/failure-reason (:message error-map (:message event-data))
               :workflow/error-details error-map
               :workflow/anomaly-code (:anomaly-code event-data)
               :workflow/retryable? (:retryable? event-data false))
        (cond-> class (assoc :failure/class class)))))

(defn llm-request [stream workflow-id agent-id model & [prompt-tokens]]
  (-> (create-envelope stream :llm/request workflow-id
                       (str "Calling " model (when prompt-tokens (str " (" prompt-tokens " tokens)"))))
      (assoc :agent/id agent-id
             :llm/model model
             :llm/request-id (random-uuid))
      (cond-> prompt-tokens (assoc :llm/prompt-tokens prompt-tokens))))

(defn llm-response [stream workflow-id agent-id model request-id & [metrics]]
  (-> (create-envelope stream :llm/response workflow-id
                       (str "Response from " model
                            (when (:completion-tokens metrics)
                              (str " (" (:completion-tokens metrics) " tokens)"))))
      (assoc :agent/id agent-id
             :llm/model model
             :llm/request-id request-id)
      (cond->
        (:completion-tokens metrics) (assoc :llm/completion-tokens (:completion-tokens metrics))
        (:total-tokens metrics) (assoc :llm/total-tokens (:total-tokens metrics))
        (:duration-ms metrics) (assoc :llm/duration-ms (:duration-ms metrics))
        (:cost-usd metrics) (assoc :llm/cost-usd (:cost-usd metrics)))))

;------------------------------------------------------------------------------ Layer 4
;; Agent lifecycle events

(defn agent-started [stream workflow-id agent-id & [context]]
  (-> (create-envelope stream :agent/started workflow-id
                       (str "Agent " (name agent-id) " started"))
      (assoc :agent/id agent-id)
      (cond-> context (assoc :agent/context context))))

(defn agent-completed [stream workflow-id agent-id & [result]]
  (-> (create-envelope stream :agent/completed workflow-id
                       (str "Agent " (name agent-id) " completed"))
      (assoc :agent/id agent-id)
      (cond-> result (assoc :agent/result result))))

(defn agent-failed [stream workflow-id agent-id & [error {:keys [failure/class]}]]
  (-> (create-envelope stream :agent/failed workflow-id
                       (str "Agent " (name agent-id) " failed"))
      (assoc :agent/id agent-id)
      (cond-> error (assoc :agent/error error)
              class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 4
;; Gate lifecycle events

(defn gate-started [stream workflow-id gate-id & [artifact-summary]]
  (-> (create-envelope stream :gate/started workflow-id
                       (str "Gate " (name gate-id) " started"))
      (assoc :gate/id gate-id)
      (cond-> artifact-summary (assoc :gate/artifact-summary artifact-summary))))

(defn gate-passed [stream workflow-id gate-id & [duration-ms]]
  (-> (create-envelope stream :gate/passed workflow-id
                       (str "Gate " (name gate-id) " passed"))
      (assoc :gate/id gate-id)
      (cond-> duration-ms (assoc :gate/duration-ms duration-ms))))

(defn gate-failed [stream workflow-id gate-id & [violations {:keys [failure/class]}]]
  (-> (create-envelope stream :gate/failed workflow-id
                       (str "Gate " (name gate-id) " failed"))
      (assoc :gate/id gate-id)
      (cond-> violations (assoc :gate/violations violations)
              class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 4
;; Tool lifecycle events

(defn tool-invoked [stream workflow-id agent-id tool-id & [params-summary]]
  (-> (create-envelope stream :tool/invoked workflow-id
                       (str "Tool " (name tool-id) " invoked by " (name agent-id)))
      (assoc :agent/id agent-id
             :tool/id tool-id)
      (cond-> params-summary (assoc :tool/params-summary params-summary))))

(defn tool-completed [stream workflow-id agent-id tool-id & [result-summary]]
  (-> (create-envelope stream :tool/completed workflow-id
                       (str "Tool " (name tool-id) " completed"))
      (assoc :agent/id agent-id
             :tool/id tool-id)
      (cond-> result-summary (assoc :tool/result-summary result-summary))))

;------------------------------------------------------------------------------ Layer 4
;; Milestone event

(defn milestone-reached [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :workflow/milestone-reached workflow-id
                       (or description (str "Milestone " (name milestone-id) " reached")))
      (assoc :milestone/id milestone-id)))

(defn milestone-started [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :phase/milestone-started workflow-id
                       (or description (str "Milestone " (name milestone-id) " started")))
      (assoc :milestone/id milestone-id)))

(defn milestone-completed [stream workflow-id milestone-id & [description]]
  (-> (create-envelope stream :phase/milestone-completed workflow-id
                       (or description (str "Milestone " (name milestone-id) " completed")))
      (assoc :milestone/id milestone-id)))

(defn milestone-failed [stream workflow-id milestone-id & [reason]]
  (-> (create-envelope stream :phase/milestone-failed workflow-id
                       (or reason (str "Milestone " (name milestone-id) " failed")))
      (assoc :milestone/id milestone-id)))

;------------------------------------------------------------------------------ Layer 4
;; Task lifecycle (DAG) events

(defn task-state-changed [stream workflow-id dag-id task-id from-state to-state & [context]]
  (-> (create-envelope stream :task/state-changed workflow-id
                       (str "Task " task-id " " (name from-state) " -> " (name to-state)))
      (assoc :dag/id dag-id
             :task/id task-id
             :task/from-state from-state
             :task/to-state to-state)
      (cond-> context (assoc :task/context context))))

(defn task-frontier-entered [stream workflow-id dag-id task-id & [frontier-size]]
  (-> (create-envelope stream :task/frontier-entered workflow-id
                       (str "Task " task-id " entered frontier"))
      (assoc :dag/id dag-id
             :task/id task-id)
      (cond-> frontier-size (assoc :task/frontier-size frontier-size))))

(defn task-skip-propagated [stream workflow-id dag-id task-id & [cause-task]]
  (-> (create-envelope stream :task/skip-propagated workflow-id
                       (str "Task " task-id " skip propagated"))
      (assoc :dag/id dag-id
             :task/id task-id)
      (cond-> cause-task (assoc :task/cause-task cause-task))))

;------------------------------------------------------------------------------ Layer 4
;; Inter-agent messaging events

(defn inter-agent-message-sent [stream workflow-id from-agent to-agent & [message-type]]
  (-> (create-envelope stream :agent/message-sent workflow-id
                       (str (name from-agent) " -> " (name to-agent)
                            (when message-type (str " (" (name message-type) ")"))))
      (assoc :from-agent/id from-agent
             :to-agent/id to-agent)
      (cond-> message-type (assoc :message/type message-type))))

(defn inter-agent-message-received [stream workflow-id from-agent to-agent & [message-type]]
  (-> (create-envelope stream :agent/message-received workflow-id
                       (str (name to-agent) " <- " (name from-agent)
                            (when message-type (str " (" (name message-type) ")"))))
      (assoc :from-agent/id from-agent
             :to-agent/id to-agent)
      (cond-> message-type (assoc :message/type message-type))))

;------------------------------------------------------------------------------ Layer 4
;; Listener lifecycle events (N8)

(defn listener-attached [stream workflow-id listener-id & [listener-type capability]]
  (-> (create-envelope stream :listener/attached workflow-id
                       (str "Listener " listener-id " attached"))
      (assoc :listener/id listener-id)
      (cond->
        listener-type (assoc :listener/type listener-type)
        capability (assoc :listener/capability capability))))

(defn listener-detached [stream workflow-id listener-id & [reason]]
  (-> (create-envelope stream :listener/detached workflow-id
                       (str "Listener " listener-id " detached"))
      (assoc :listener/id listener-id)
      (cond-> reason (assoc :listener/reason reason))))

(defn annotation-created [stream workflow-id listener-id annotation-type & [content]]
  (-> (create-envelope stream :annotation/created workflow-id
                       (str "Annotation from " listener-id ": " (name annotation-type)))
      (assoc :listener/id listener-id
             :annotation/type annotation-type)
      (cond-> content (assoc :annotation/content content))))

;------------------------------------------------------------------------------ Layer 4
;; Chain lifecycle events
;; Chains are not workflow-scoped, so workflow-id is nil.
;; Message is derived from the event-type keyword.

(defn chain-envelope
  "Create an envelope for chain events. Chains are not workflow-scoped,
   so workflow-id is nil. Message is derived from the event-type keyword."
  [stream event-type]
  (create-envelope stream event-type nil (name event-type)))

(defn chain-started [stream chain-id step-count]
  (-> (chain-envelope stream :chain/started)
      (assoc :chain/id chain-id
             :chain/step-count step-count)))

(defn chain-step-started [stream chain-id step-id step-index workflow-id]
  (-> (chain-envelope stream :chain/step-started)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :step/workflow-id workflow-id)))

(defn chain-step-completed [stream chain-id step-id step-index]
  (-> (chain-envelope stream :chain/step-completed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index)))

(defn chain-step-failed [stream chain-id step-id step-index error & [{:keys [failure/class]}]]
  (-> (chain-envelope stream :chain/step-failed)
      (assoc :chain/id chain-id
             :step/id step-id
             :step/index step-index
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

(defn chain-completed [stream chain-id duration-ms step-count]
  (-> (chain-envelope stream :chain/completed)
      (assoc :chain/id chain-id
             :chain/duration-ms duration-ms
             :chain/step-count step-count)))

(defn chain-failed [stream chain-id step-id error & [{:keys [failure/class]}]]
  (-> (chain-envelope stream :chain/failed)
      (assoc :chain/id chain-id
             :chain/failed-step step-id
             :chain/error error)
      (cond-> class (assoc :failure/class class))))

;------------------------------------------------------------------------------ Layer 4
;; Control action events (N8)

(defn control-action-requested [stream workflow-id action-id action-type & [requester]]
  (-> (create-envelope stream :control-action/requested workflow-id
                       (str "Control action " (name action-type) " requested"))
      (assoc :action/id action-id
             :action/type action-type)
      (cond-> requester (assoc :action/requester requester))))

(defn control-action-executed [stream workflow-id action-id & [result]]
  (-> (create-envelope stream :control-action/executed workflow-id
                       (str "Control action " action-id " executed"))
      (assoc :action/id action-id)
      (cond-> result (assoc :action/result result))))

;------------------------------------------------------------------------------ Layer 4
;; OCI container events (N8)

(defn container-started [stream workflow-id container-id & [opts]]
  (-> (create-envelope stream :oci/container-started workflow-id
                       (str "Container " container-id " started"))
      (assoc :oci/container-id container-id)
      (cond->
        (:image-digest opts) (assoc :oci/image-digest (:image-digest opts))
        (:trust-level opts)  (assoc :oci/trust-level (:trust-level opts)))))

(defn container-completed [stream workflow-id container-id exit-code & [duration-ms]]
  (-> (create-envelope stream :oci/container-completed workflow-id
                       (str "Container " container-id " completed (exit " exit-code ")"))
      (assoc :oci/container-id container-id
             :oci/exit-code exit-code)
      (cond-> duration-ms (assoc :oci/duration-ms duration-ms))))

;------------------------------------------------------------------------------ Layer 4
;; Tool supervision events (N6/N8)

(defn tool-use-evaluated
  "Emit when a tool-use request is evaluated by the supervisor.

   Captures both regex and meta-eval decisions for evidence trail."
  [stream workflow-id tool-name decision & [opts]]
  (-> (create-envelope stream :supervision/tool-use-evaluated workflow-id
                       (str "Tool " tool-name " evaluated: " (name (keyword decision))))
      (assoc :tool/name tool-name
             :supervision/decision decision)
      (cond->
        (:reasoning opts)  (assoc :supervision/reasoning (:reasoning opts))
        (:meta-eval? opts) (assoc :supervision/meta-eval? true)
        (:confidence opts) (assoc :supervision/confidence (:confidence opts))
        (:phase opts)      (assoc :workflow/phase (:phase opts)))))

;------------------------------------------------------------------------------ Layer 5
;; Control plane events

(defn cp-agent-registered
  "Emit when an external agent registers with the control plane."
  [stream workflow-id agent-id vendor & [opts]]
  (-> (create-envelope stream :control-plane/agent-registered workflow-id
                       (str (name vendor) " agent " agent-id " registered"))
      (assoc :cp/agent-id agent-id
             :cp/vendor vendor)
      (cond->
        (:name opts) (assoc :cp/agent-name (:name opts))
        (:external-id opts) (assoc :cp/external-id (:external-id opts))
        (:capabilities opts) (assoc :cp/capabilities (vec (:capabilities opts)))
        (:metadata opts) (assoc :cp/metadata (:metadata opts))
        (:tags opts) (assoc :cp/tags (vec (:tags opts)))
        (:heartbeat-interval-ms opts)
        (assoc :cp/heartbeat-interval-ms (:heartbeat-interval-ms opts)))))

(defn cp-agent-heartbeat
  "Emit when the control plane receives a heartbeat from an agent."
  [stream workflow-id agent-id status & [opts]]
  (-> (create-envelope stream :control-plane/agent-heartbeat workflow-id
                       (str "Heartbeat from " agent-id ": " (name status)))
      (assoc :cp/agent-id agent-id
             :cp/status status)
      (cond->
        (:task opts) (assoc :cp/task (:task opts))
        (:metrics opts) (assoc :cp/metrics (:metrics opts)))))

(defn cp-agent-state-changed
  "Emit when an agent's normalized state changes."
  [stream workflow-id agent-id from-status to-status]
  (-> (create-envelope stream :control-plane/agent-state-changed workflow-id
                       (str "Agent " agent-id ": " (name from-status) " → " (name to-status)))
      (assoc :cp/agent-id agent-id
             :cp/from-status from-status
             :cp/to-status to-status)))

(defn cp-decision-created
  "Emit when an agent submits a decision request."
  [stream workflow-id agent-id decision-id summary & [priority-or-opts]]
  (let [opts (if (map? priority-or-opts)
               priority-or-opts
               {:priority priority-or-opts})]
  (-> (create-envelope stream :control-plane/decision-created workflow-id
                       (str "Decision needed from " agent-id ": " summary))
      (assoc :cp/agent-id agent-id
             :cp/decision-id decision-id
             :cp/summary summary)
      (cond->
        (:priority opts) (assoc :cp/priority (:priority opts))
        (:type opts) (assoc :cp/type (:type opts))
        (:context opts) (assoc :cp/context (:context opts))
        (:options opts) (assoc :cp/options (vec (:options opts)))
        (:deadline opts) (assoc :cp/deadline (:deadline opts))))))

(defn cp-decision-resolved
  "Emit when a human resolves a decision."
  [stream workflow-id decision-id resolution & [comment]]
  (-> (create-envelope stream :control-plane/decision-resolved workflow-id
                       (str "Decision " decision-id " resolved: " resolution))
      (assoc :cp/decision-id decision-id
             :cp/resolution resolution)
      (cond-> comment (assoc :cp/comment comment))))

(defn intervention-requested
  "Emit when a bounded supervisory intervention is created."
  [stream workflow-id intervention]
  (let [message (messages/t :supervisory/intervention-requested
                            {:type (name (:intervention/type intervention))})]
    (-> (create-envelope stream :supervisory/intervention-requested workflow-id
                         message)
        (merge intervention))))

(defn intervention-state-changed
  "Emit when an InterventionRequest changes lifecycle state."
  [stream workflow-id intervention-id to-state & [opts]]
  (let [message (messages/t :supervisory/intervention-state-changed
                            {:intervention-id intervention-id
                             :state (name to-state)})]
    (-> (create-envelope stream :supervisory/intervention-state-changed workflow-id
                         message)
        (assoc :intervention/id intervention-id
               :intervention/state to-state)
        (cond->
          (:intervention/from-state opts)
          (assoc :intervention/from-state (:intervention/from-state opts))

          (:intervention/type opts)
          (assoc :intervention/type (:intervention/type opts))

          (:intervention/target-type opts)
          (assoc :intervention/target-type (:intervention/target-type opts))

          (contains? opts :intervention/target-id)
          (assoc :intervention/target-id (:intervention/target-id opts))

          (:intervention/requested-by opts)
          (assoc :intervention/requested-by (:intervention/requested-by opts))

          (:intervention/request-source opts)
          (assoc :intervention/request-source (:intervention/request-source opts))

          (:intervention/justification opts)
          (assoc :intervention/justification (:intervention/justification opts))

          (:intervention/details opts)
          (assoc :intervention/details (:intervention/details opts))

          (:intervention/reason opts)
          (assoc :intervention/reason (:intervention/reason opts))

          (:intervention/outcome opts)
          (assoc :intervention/outcome (:intervention/outcome opts))

          (:intervention/requested-at opts)
          (assoc :intervention/requested-at (:intervention/requested-at opts))

          (:intervention/updated-at opts)
          (assoc :intervention/updated-at (:intervention/updated-at opts))

          (contains? opts :intervention/approval-required?)
          (assoc :intervention/approval-required?
                 (:intervention/approval-required? opts))))))

;------------------------------------------------------------------------------ Layer 6
;; PR scoring events (N5-delta-2 §4.1)

(defn pr-created
  "Emit when a workflow-owned PR is created.

   This is the canonical fine-grained event that lets `supervisory-state`
   attach a PR back to the owning workflow run instead of forcing
   consumers to infer that relationship from summary payloads later."
  [stream workflow-id {:pr/keys [repo number url branch title author merge-order] :as _pr}]
  (-> (create-envelope stream :pr/created workflow-id
                       (str "PR " repo "#" number " created"))
      (assoc :pr/repo repo
             :pr/number number
             :pr/url url
             :pr/branch branch)
      (cond->
        title       (assoc :pr/title title)
        author      (assoc :pr/author author)
        merge-order (assoc :pr/merge-order merge-order))))

(defn pr-scored
  "Emit when the pr-scoring component has computed readiness, risk, and
   policy scores for a PR, per N5-delta-2 §4.1. Produces the only event
   carrying the four `:pr/readiness`, `:pr/risk`, `:pr/policy`, and
   `:pr/recommendation` fields for downstream consumption by
   `supervisory-state`.

   Args:
     stream      — event-stream atom
     repo        — \"owner/repo\" string
     number      — PR number (long)
     scores      — map with keys :readiness, :risk, :policy,
                   :recommendation (any subset MAY be present; consumers
                   render absent fields as \"not yet scored\", §5.4)
     opts        — optional map; :workflow/id scopes the event to a
                   run, otherwise nil (PR scoring is not inherently
                   workflow-scoped)"
  [stream repo number {:keys [readiness risk policy recommendation]}
   & [{:workflow/keys [id] :as _opts}]]
  (-> (create-envelope stream :pr/scored id
                       (str "PR " repo "#" number " scored"))
      (assoc :pr/repo repo
             :pr/number number)
      (cond->
        readiness      (assoc :pr/readiness readiness)
        risk           (assoc :pr/risk risk)
        policy         (assoc :pr/policy policy)
        recommendation (assoc :pr/recommendation recommendation))))

;------------------------------------------------------------------------------ Layer 6
;; Reliability metric events (N3 §3.17, N1 §5.5)

(defn sli-computed
  "Emit when an SLI value is computed over a rolling window."
  [stream sli-name value window & [opts]]
  (-> (create-envelope stream :reliability/sli-computed nil
                       (format "SLI computed: %s = %.3f over %s"
                               (name sli-name) (double value) (name window)))
      (assoc :sli/name sli-name
             :sli/value value
             :sli/window window)
      (cond->
        (:tier opts)       (assoc :sli/tier (:tier opts))
        (:dimensions opts) (assoc :sli/dimensions (:dimensions opts)))))

(defn slo-breach
  "Emit when an SLO target is missed for :standard or :critical tiers."
  [stream sli-name target actual tier window]
  (-> (create-envelope stream :reliability/slo-breach nil
                       (format "SLO breach: %s target=%.3f actual=%.3f tier=%s"
                               (name sli-name) (double target) (double actual) (name tier)))
      (assoc :slo/sli-name sli-name
             :slo/target target
             :slo/actual actual
             :slo/tier tier
             :slo/window window)))

(defn error-budget-update
  "Emit when error budget state is recomputed."
  [stream tier sli remaining burn-rate window]
  (-> (create-envelope stream :reliability/error-budget-update nil
                       (format "Error budget: tier=%s sli=%s remaining=%.3f burn-rate=%.2f"
                               (name tier) (name sli) (double remaining) (double burn-rate)))
      (assoc :budget/tier tier
             :budget/sli sli
             :budget/remaining remaining
             :budget/burn-rate burn-rate
             :budget/window window)))

(defn degradation-mode-changed
  "Emit when the system transitions between degradation modes (N1 §5.5.5)."
  [stream from-mode to-mode trigger]
  (-> (create-envelope stream :reliability/degradation-mode-changed nil
                       (format "Degradation mode: %s → %s (%s)"
                               (name from-mode) (name to-mode) trigger))
      (assoc :degradation/from from-mode
             :degradation/to to-mode
             :degradation/trigger trigger)))

(defn safe-mode-entered
  "Emit when safe-mode is activated (N8 §3.4.4)."
  [stream trigger & [details]]
  (-> (create-envelope stream :safe-mode/entered nil
                       (str "Safe-mode entered: " (name trigger)))
      (assoc :safe-mode/trigger trigger)
      (cond-> details (assoc :safe-mode/trigger-details details))))

(defn safe-mode-exited
  "Emit when safe-mode is deactivated (N8 §3.4.4)."
  [stream exited-by justification duration-ms workflows-queued]
  (-> (create-envelope stream :safe-mode/exited nil
                       (format "Safe-mode exited after %dms: %s" duration-ms justification))
      (assoc :safe-mode/exited-by exited-by
             :safe-mode/justification justification
             :safe-mode/duration-ms duration-ms
             :safe-mode/workflows-queued workflows-queued)))

(defn- dependency-id-string
  [dependency-id]
  (if (keyword? dependency-id)
    (name dependency-id)
    (str dependency-id)))

(defn- dependency-event
  [stream event-type dependency previous-status message-key]
  (let [dependency-id (:dependency/id dependency)
        status (:dependency/status dependency)
        message (messages/t message-key
                            {:dependency-id (dependency-id-string dependency-id)
                             :status (name status)})]
    (-> (create-envelope stream event-type nil message)
        (merge dependency)
        (cond-> previous-status
          (assoc :dependency/previous-status previous-status)))))

(defn dependency-health-updated
  "Emit when a dependency health projection changes."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/health-updated
                    dependency
                    previous-status
                    :dependency/health-updated))

(defn dependency-recovered
  "Emit when a dependency returns to healthy status."
  [stream dependency & [previous-status]]
  (dependency-event stream
                    :dependency/recovered
                    dependency
                    previous-status
                    :dependency/recovered))

;------------------------------------------------------------------------------ Layer 6
;; Meta-loop events

(defn meta-loop-cycle-completed
  "Emit when a meta-loop cycle completes."
  [stream summary]
  (-> (create-envelope stream :meta-loop/cycle-completed nil
                       (format "Meta-loop cycle: %d signals, %d diagnoses, %d proposals"
                               (:signals summary 0)
                               (:diagnoses summary 0)
                               (:proposals summary 0)))
      (merge summary)))

(defn meta-loop-cycle-failed
  "Emit when a meta-loop cycle throws an unhandled exception."
  [stream error]
  (-> (create-envelope stream :meta-loop/cycle-failed nil
                       (str "Meta-loop cycle failed: " (ex-message error)))
      (assoc :meta-loop/error (ex-message error)
             :meta-loop/error-class (.getName (class error)))))

;------------------------------------------------------------------------------ Layer 7
;; Observer / knowledge failure events

(defn observer-signal-failed
  "Emit when observe-workflow-signal! fails to forward a signal to the meta-loop."
  [stream workflow-id error]
  (-> (create-envelope stream :observer/signal-failed workflow-id
                       (str "Observer signal failed: " (ex-message error)))
      (assoc :observer/error (ex-message error))))

(defn knowledge-synthesis-failed
  "Emit when synthesize-patterns! fails."
  [stream error]
  (-> (create-envelope stream :knowledge/synthesis-failed nil
                       (str "Knowledge synthesis failed: " (ex-message error)))
      (assoc :knowledge/error (ex-message error))))

(defn knowledge-promotion-failed
  "Emit when promote-mature-learnings! fails."
  [stream error]
  (-> (create-envelope stream :knowledge/promotion-failed nil
                       (str "Knowledge promotion failed: " (ex-message error)))
      (assoc :knowledge/error (ex-message error))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create event stream
  (def stream (create-event-stream))

  ;; Subscribe to events
  (subscribe! stream :console-logger
              (fn [event] (println "Event:" (:event/type event) "-" (:message event))))

  ;; Workflow lifecycle
  (def wf-id (random-uuid))
  (publish! stream (workflow-started stream wf-id {:name "test-workflow"}))
  (publish! stream (phase-started stream wf-id :plan))
  (publish! stream (agent-status stream wf-id :planner :thinking "Analyzing specification"))
  (publish! stream (agent-chunk stream wf-id :planner "Creating plan..."))
  (publish! stream (phase-completed stream wf-id :plan {:outcome :success :duration-ms 5000}))
  (publish! stream (workflow-completed stream wf-id :success 10000))

  ;; Query events
  (get-events stream {:workflow-id wf-id})
  (get-latest-status stream wf-id :planner)

  ;; Unsubscribe
  (unsubscribe! stream :console-logger)

  :leave-this-here)
