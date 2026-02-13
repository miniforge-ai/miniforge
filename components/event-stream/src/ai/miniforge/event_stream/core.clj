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
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.event-stream.sinks :as sinks]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const event-version "1.0.0")

;------------------------------------------------------------------------------ Layer 0
;; Event persistence via configurable sinks
;; Note: File persistence moved to sinks.clj for configurability

;------------------------------------------------------------------------------ Layer 0
;; Event envelope constructor

(defn- create-envelope [stream event-type workflow-id message]
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
  (let [outcome (or (:outcome result) :success)]
    (-> (create-envelope stream :workflow/phase-completed workflow-id
                         (str (name phase) " phase " (name outcome)))
        (assoc :workflow/phase phase
               :phase/outcome outcome)
        (cond->
          (:duration-ms result) (assoc :phase/duration-ms (:duration-ms result))
          (:artifacts result) (assoc :phase/artifacts (:artifacts result))))))

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

(defn workflow-completed [stream workflow-id status & [duration-ms]]
  (-> (create-envelope stream :workflow/completed workflow-id
                       (str "Workflow " (name status)))
      (assoc :workflow/status status)
      (cond-> duration-ms (assoc :workflow/duration-ms duration-ms))))

(defn workflow-failed [stream workflow-id error]
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
                                               (or (:message error-map) "unknown error"))))]
    (-> (create-envelope stream :workflow/failed workflow-id
                         (str "Workflow failed: " (:message error-map "unknown error")))
        (assoc :workflow/failure-reason (:message error-map (:message event-data))
               :workflow/error-details error-map
               :workflow/anomaly-code (:anomaly-code event-data)
               :workflow/retryable? (:retryable? event-data false)))))

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

(defn agent-failed [stream workflow-id agent-id & [error]]
  (-> (create-envelope stream :agent/failed workflow-id
                       (str "Agent " (name agent-id) " failed"))
      (assoc :agent/id agent-id)
      (cond-> error (assoc :agent/error error))))

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

(defn gate-failed [stream workflow-id gate-id & [violations]]
  (-> (create-envelope stream :gate/failed workflow-id
                       (str "Gate " (name gate-id) " failed"))
      (assoc :gate/id gate-id)
      (cond-> violations (assoc :gate/violations violations))))

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
