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
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Supervision setup

(def default-progress-monitor-config
  "Default progress-monitor config when workflow config does not override it."
  {:check-interval-ms 30000
   :stagnation-threshold-ms 120000
   :max-total-ms 600000})

(defn create-supervisors
  "Create supervisors from workflow configuration.

   Falls back to the default progress monitor if no supervisors are configured."
  [workflow]
  (let [supervisor-configs (get workflow :workflow/meta-agents [])]
    (if (seq supervisor-configs)
      ;; Use configured supervisors from the existing workflow config key.
      (for [config supervisor-configs
            :when (:enabled? config true)]
        (case (:id config)
          :progress-monitor
          (supervision/create-progress-monitor-agent
           (merge default-progress-monitor-config
                  (:config config)))))
      ;; Default: just progress monitor
      [(supervision/create-progress-monitor-agent)])))

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

(defn handle-supervision-halt
  "Handle supervision halt signal by transitioning workflow to failed state."
  [ctx supervision-result transition-to-failed-fn]
  (let [halting-check (halting-check supervision-result)
        supervisor-id (:halting-agent supervision-result)]
    (-> ctx
        (update :execution/errors conj
                {:type :supervision-halt
                 :supervisor supervisor-id
                 :message (:halt-reason supervision-result)
                 :data (:data halting-check)})
        (update :execution/response-chain
                response/add-failure :supervision
                :anomalies.workflow/halted-by-supervision
                {:supervisor supervisor-id
                 :reason (:halt-reason supervision-result)
                 :checks (:checks supervision-result)})
        (transition-to-failed-fn))))

(defn clear-transient-state
  "Clear transient state after phase execution to prevent memory buildup."
  [ctx]
  (assoc ctx :execution/streaming-activity []))
