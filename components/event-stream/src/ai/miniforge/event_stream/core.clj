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
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const event-version "1.0.0")

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

(defn create-event-stream [& [opts]]
  (atom {:events []
         :subscribers {}
         :filters {}
         :sequence-numbers {}
         :logger (:logger opts)}))

(defn publish! [stream event]
  (let [{:keys [subscribers filters logger]} @stream]
    (swap! stream update :events conj event)
    (doseq [[sub-id callback] subscribers]
      (let [filter-fn (get filters sub-id (constantly true))]
        (when (filter-fn event)
          (try
            (callback event)
            (catch Exception e
              (when logger
                (log/error logger :event-stream :event/callback-error
                           {:message "Event callback failed"
                            :data {:subscriber-id sub-id
                                   :event-type (:event/type event)
                                   :error (.getMessage e)}})))))))
    (when logger
      (log/debug logger :event-stream :event/published
                 {:message "Event published"
                  :data {:event-type (:event/type event)
                         :workflow-id (:workflow/id event)
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
