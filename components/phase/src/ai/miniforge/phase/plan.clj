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

(ns ai.miniforge.phase.plan
  "Planning phase interceptor.

   Creates implementation plans from specifications.
   Agent: :planner
   Default gates: [:plan-complete]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.agent.interface :as agent]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :planner
   :gates [:plan-complete]
   :budget {:tokens 10000
            :iterations 3
            :time-seconds 300}})

;; Register defaults on load
(registry/register-phase-defaults! :plan default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-plan
  "Execute planning phase.

   Reads specification from context, invokes planner agent,
   runs through inner loop with gates."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create planner agent
        planner (agent/create-agent :planner {})

        ;; Build task from workflow input
        input (get-in ctx [:execution/input])
        task {:task/id (random-uuid)
              :task/type :plan
              :task/description (:description input)
              :task/title (:title input)
              :task/intent (:intent input)
              :task/constraints (:constraints input)}

        ;; Invoke agent (this will call LLM and do actual work)
        result (try
                 (agent/invoke planner task ctx)
                 (catch Exception e
                   {:success false
                    :error {:message (ex-message e)
                            :data (ex-data e)}
                    :metrics {:tokens 0 :duration-ms 0}}))]

    (-> ctx
        (assoc-in [:phase :name] :plan)
        (assoc-in [:phase :agent] :planner)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-plan
  "Post-processing for planning phase.

   Records metrics and updates execution state."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] metrics)
        (update-in [:execution :phases-completed] (fnil conj []) :plan)
        ;; Merge agent metrics into execution metrics
        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))))

(defn- error-plan
  "Handle planning phase errors.

   Attempts repair if within budget, otherwise propagates error."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 3)]
    (if (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :plan
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::plan
     :config merged
     :enter (fn [ctx]
              (enter-plan (assoc ctx :phase-config merged)))
     :leave leave-plan
     :error error-plan}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get plan interceptor with defaults
  (registry/get-phase-interceptor {:phase :plan})

  ;; Get with overrides
  (registry/get-phase-interceptor {:phase :plan
                                   :budget {:tokens 20000}})

  ;; Check defaults
  (registry/phase-defaults :plan)

  :leave-this-here)
