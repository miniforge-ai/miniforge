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
            [ai.miniforge.event-stream.interface :as es]
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

;;----------------------------------------------------------------------------- Halt emission
;; Meta-loop halt as a typed REFUSE on the event stream (N3)

(def ^:private halt-reason-codes
  "Default RefusalReason for each meta-agent that can halt the workflow.
   A halting agent's health-check :data may override via :halt/reason-code."
  {:progress-monitor  :no-progress
   :test-quality      :quality-gate
   :conflict-detector :conflict})

(defn- halt-reason-code
  "RefusalReason for a halt: the halting agent's explicit code when supplied,
   else the per-agent default, else :no-progress."
  [halt-data]
  (or (get-in halt-data [:data :halt/reason-code])
      (get halt-reason-codes (:supervisor halt-data) :no-progress)))

(defn- resolve-event-stream
  "Find the event stream on the execution context, across the keys the runner
   and dashboard use to carry it."
  [ctx]
  (or (:event-stream ctx)
      (:execution/event-stream ctx)
      (get-in ctx [:execution/opts :event-stream])))

(defn- emit-halt-requested!
  "Publish the typed REFUSE for a meta-loop halt when a stream is present.
   Best-effort: a telemetry failure must not mask the halt itself."
  [ctx halt-data]
  (when-let [stream (resolve-event-stream ctx)]
    (let [halting-agent (get halt-data :supervisor :supervisor)
          reason-code   (halt-reason-code halt-data)]
      (try
        (es/publish! stream
                     (es/meta-loop-halt-requested
                      stream (:execution/id ctx) halting-agent reason-code
                      {:detail (:reason halt-data)
                       :phase  (:execution/current-phase ctx)}))
        (catch Exception _ nil)))))

(defn handle-supervision-halt
  "Handle supervision halt signal by transitioning workflow to failed state.
   Emits a :meta-loop/halt-requested REFUSE before transitioning so the refusal
   is first-class on the stream, not only an error map in the response chain."
  [ctx supervision-result transition-to-failed-fn]
  (let [halt-data (supervision-halt-data supervision-result)]
    (emit-halt-requested! ctx halt-data)
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
