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

(ns ai.miniforge.phase.pr-monitor
  "PR monitoring phase interceptor.

   After the DAG executor completes, this phase assembles a PR train
   from DAG results and monitors all PRs through CI, review, and merge.

   Agent: none (orchestration only)
   Default gates: none"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent nil
   :gates []
   :budget {:tokens 0
            :iterations 1
            :time-seconds 7200}}) ; 2 hour default for monitoring

;; Register defaults on load
(registry/register-phase-defaults! :pr-monitor default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-pr-monitor
  "Execute PR monitoring phase.

   Reads :execution/dag-pr-infos from context, assembles a PR train,
   and monitors all PRs through CI/review/merge using dag-monitor."
  [ctx]
  (let [pr-infos (get-in ctx [:execution/dag-pr-infos])
        start-time (System/currentTimeMillis)]
    (if (empty? pr-infos)
      ;; No PRs to monitor — skip
      (-> ctx
          (assoc-in [:phase :name] :pr-monitor)
          (assoc-in [:phase :status] :completed)
          (assoc-in [:phase :result] (response/success {:pr-monitor/status :skipped
                                                         :pr-monitor/reason "No PRs from DAG"})))

      ;; Assemble train and monitor
      (let [;; Resolve at runtime to avoid circular deps
            create-train (requiring-resolve 'ai.miniforge.workflow.dag-train/create-train-from-dag-result)
            monitor-dag-prs (requiring-resolve 'ai.miniforge.workflow.dag-monitor/monitor-dag-prs)

            plan-tasks (get-in ctx [:execution/phase-results :plan :artifacts 0 :plan/tasks]
                               [])
            dag-result (get-in ctx [:execution/dag-result])
            logger (get-in ctx [:execution/logger])
            worktree-path (or (get-in ctx [:execution/worktree-path])
                              (get-in ctx [:worktree-path]))

            ;; Build train from DAG result
            train-state (create-train (assoc dag-result :pr-infos pr-infos)
                                       plan-tasks
                                       :logger logger)

            ;; Monitor all PRs
            monitor-result (monitor-dag-prs train-state pr-infos
                                             {:worktree-path worktree-path
                                              :logger logger
                                              :generate-fn (get-in ctx [:execution/generate-fn])
                                              :event-bus (get-in ctx [:execution/event-bus])})

            result-data {:pr-monitor/status (if (:success? monitor-result) :completed :failed)
                         :pr-monitor/merged-prs (:merged-prs monitor-result [])
                         :pr-monitor/failed-prs (:failed-prs monitor-result [])
                         :pr-monitor/train-id (:train-id train-state)}]

        (-> ctx
            (assoc-in [:phase :name] :pr-monitor)
            (assoc-in [:phase :started-at] start-time)
            (assoc-in [:phase :status] (if (:success? monitor-result) :completed :failed))
            (assoc-in [:phase :result]
                      (if (:success? monitor-result)
                        (response/success result-data)
                        (response/failure (ex-info "PR monitoring failed"
                                                    {:failed-prs (:failed-prs monitor-result)}))))
            (assoc :execution/train train-state))))))

(defn leave-pr-monitor
  "Post-processing for PR monitoring phase. Records merge results."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at] (System/currentTimeMillis))
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:metrics :pr-monitor :duration-ms] duration-ms)
        (update-in [:execution :phases-completed] (fnil conj []) :pr-monitor)
        (update-in [:execution/metrics :duration-ms] (fnil + 0) duration-ms))))

(defn error-pr-monitor
  "Handle PR monitoring phase errors."
  [ctx ex]
  (-> ctx
      (assoc-in [:phase :status] :failed)
      (assoc-in [:phase :error] {:message (ex-message ex)
                                  :data (ex-data ex)})))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod registry/get-phase-interceptor :pr-monitor
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::pr-monitor
     :config merged
     :enter (fn [ctx]
              (enter-pr-monitor (assoc ctx :phase-config merged)))
     :leave leave-pr-monitor
     :error error-pr-monitor}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :pr-monitor})
  (registry/phase-defaults :pr-monitor)
  :leave-this-here)
