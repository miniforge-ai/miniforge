;; Copyright 2025 miniforge.ai
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
  "Meta-agent health monitoring integration.

   Provides functions for building workflow state, checking health via meta-agent
   coordinator, and handling halt conditions."
  (:require [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Meta-agent setup

(defn create-meta-agents
  "Create meta-agents from workflow configuration.

   Falls back to default progress monitor if no meta-agents configured."
  [workflow]
  (let [meta-agent-configs (or (:workflow/meta-agents workflow) [])]
    (if (seq meta-agent-configs)
      ;; Use configured meta-agents
      (for [config meta-agent-configs
            :when (:enabled? config true)]
        (case (:id config)
          :progress-monitor
          (agent/create-progress-monitor-agent
           (merge {:check-interval-ms 30000
                   :stagnation-threshold-ms 120000
                   :max-total-ms 600000}
                  (:config config)))))
      ;; Default: just progress monitor
      [(agent/create-progress-monitor-agent)])))

;------------------------------------------------------------------------------ Health monitoring

(defn build-workflow-state
  "Build workflow state map for meta-agent health checks."
  [ctx iteration]
  {:workflow/id (:execution/workflow-id ctx)
   :workflow/phase (:execution/current-phase ctx)
   :workflow/iterations iteration
   :workflow/metrics (:execution/metrics ctx)
   :workflow/streaming-activity (:execution/streaming-activity ctx)
   :workflow/files-written (:execution/files-written ctx)})

(defn check-workflow-health
  "Run meta-agent health checks on workflow state.

   Returns health check result."
  [coordinator workflow-state]
  (agent/check-all-meta-agents coordinator workflow-state))

(defn handle-meta-agent-halt
  "Handle meta-agent halt signal by transitioning workflow to failed state."
  [ctx health-check transition-to-failed-fn]
  (-> ctx
      (update :execution/errors conj
              {:type :meta-agent-halt
               :agent (:halting-agent health-check)
               :message (:halt-reason health-check)
               :data (:data (first (filter #(= :halt (:status %))
                                           (:checks health-check))))})
      (update :execution/response-chain
              response/add-failure :meta-agent
              :anomalies.workflow/halted-by-meta-agent
              {:agent (:halting-agent health-check)
               :reason (:halt-reason health-check)
               :checks (:checks health-check)})
      (transition-to-failed-fn)))

(defn clear-transient-state
  "Clear transient state after phase execution to prevent memory buildup."
  [ctx]
  (assoc ctx :execution/streaming-activity []))
