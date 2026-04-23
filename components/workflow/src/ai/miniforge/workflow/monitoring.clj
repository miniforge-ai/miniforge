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

(ns ai.miniforge.workflow.monitoring
  "Workflow supervision integration.

   Provides functions for building workflow state, checking health via the
   live supervision runtime, and handling halt conditions."
  (:require [ai.miniforge.agent.interface.supervision :as supervision]
            [ai.miniforge.workflow.messages :as messages]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Supervision setup

(def default-progress-monitor-config
  "Default progress-monitor config when workflow config does not override it."
  {:check-interval-ms 30000
   :stagnation-threshold-ms 120000
   :max-total-ms 600000})

(defn- enabled-supervisor?
  [config]
  (get config :enabled? true))

(defn- progress-monitor-config
  [config]
  (merge default-progress-monitor-config
         (get config :config {})))

(defn- create-progress-monitor
  [config]
  (supervision/create-progress-monitor-agent
   (progress-monitor-config config)))

(defn- unsupported-supervisor-exception
  [config]
  (let [supervisor-id (:id config)]
    (ex-info
     (messages/t :supervision/unsupported-supervisor
                 {:supervisor-id (pr-str supervisor-id)})
     {:anomaly/category :anomalies.workflow/invalid-supervisor
      :supervisor/id supervisor-id
      :supervisor/config config})))

(defn- create-configured-supervisor
  [config]
  (let [supervisor-id (:id config)]
    (case supervisor-id
      :progress-monitor
      (create-progress-monitor config)
      (throw (unsupported-supervisor-exception config)))))

(defn create-supervisors
  "Create supervisors from workflow configuration.

   Falls back to the default progress monitor if no supervisors are configured."
  [workflow]
  (let [supervisor-configs (get workflow :workflow/meta-agents [])]
    (if (seq supervisor-configs)
      (->> supervisor-configs
           (filter enabled-supervisor?)
           (mapv create-configured-supervisor))
      ;; Default: just progress monitor
      [(create-progress-monitor {})])))

;------------------------------------------------------------------------------ Health monitoring

(defn build-supervision-state
  "Build workflow state map for supervision checks."
  [ctx iteration]
  {:workflow/id (:execution/workflow-id ctx)
   :workflow/phase (:execution/current-phase ctx)
   :workflow/iterations iteration
   :workflow/metrics (:execution/metrics ctx)
   :workflow/streaming-activity (:execution/streaming-activity ctx)
   :workflow/files-written (:execution/files-written ctx)})

(defn check-workflow-supervision
  "Run workflow supervision checks on workflow state.

   Returns supervision result."
  [runtime workflow-state]
  (supervision/check-all-supervisors runtime workflow-state))

(defn- halting-check
  [supervision-result]
  (first (filter #(= :halt (:status %))
                 (:checks supervision-result))))

(defn- supervision-halt-data
  [supervision-result]
  (let [halting-check' (halting-check supervision-result)]
    {:supervisor (:halting-agent supervision-result)
     :reason (:halt-reason supervision-result)
     :checks (:checks supervision-result)
     :data (:data halting-check')}))

(defn- supervision-halt-error
  [halt-data]
  {:type :supervision-halt
   :supervisor (:supervisor halt-data)
   :message (:reason halt-data)
   :data (:data halt-data)})

(defn- supervision-halt-response
  [halt-data]
  {:supervisor (:supervisor halt-data)
   :reason (:reason halt-data)
   :checks (:checks halt-data)})

(defn handle-supervision-halt
  "Handle supervision halt signal by transitioning workflow to failed state."
  [ctx supervision-result transition-to-failed-fn]
  (let [halt-data (supervision-halt-data supervision-result)]
    (-> ctx
        (update :execution/errors conj
                (supervision-halt-error halt-data))
        (update :execution/response-chain
                response/add-failure :supervision
                :anomalies.workflow/halted-by-supervision
                (supervision-halt-response halt-data))
        (transition-to-failed-fn))))

(defn clear-transient-state
  "Clear transient state after phase execution to prevent memory buildup."
  [ctx]
  (assoc ctx :execution/streaming-activity []))
