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

(ns ai.miniforge.phase.verify
  "Verification phase interceptor.

   Generates and runs tests for code artifacts.
   Agent: :tester
   Default gates: [:tests-pass :coverage]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.agent.tester :as tester]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :tester
   :gates [:tests-pass :coverage]
   :budget {:tokens 15000
            :iterations 3
            :time-seconds 300}})

;; Register defaults on load
(registry/register-phase-defaults! :verify default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-verify
  "Execute verification phase.

   Generates tests for code artifacts, runs them,
   checks coverage thresholds."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create tester agent (specialized implementation uses llm/chat directly)
        tester-agent (tester/create-tester {})

        ;; Build task from workflow input and implementation result
        input (get-in ctx [:execution/input])
        implement-result (get-in ctx [:phase :result]) ; From implement phase
        task {:task/id (random-uuid)
              :task/type :verify
              :task/description (:description input)
              :task/title (:title input)
              :task/intent (:intent input)
              :task/constraints (:constraints input)
              :task/code implement-result} ; Pass implementation output to tester

        ;; Invoke agent
        result (try
                 (agent/invoke tester-agent task ctx)
                 (catch Exception e
                   {:success false
                    :error {:message (ex-message e)
                            :data (ex-data e)}
                    :metrics {:tokens 0 :duration-ms 0}}))]

    (-> ctx
        (assoc-in [:phase :name] :verify)
        (assoc-in [:phase :agent] :tester)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-verify
  "Post-processing for verification phase.

   Records test metrics: count, pass rate, coverage."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        iterations (get-in ctx [:phase :iterations] 1)]
    (-> ctx
        (assoc-in [:phase :ended-at] end-time)
        (assoc-in [:phase :duration-ms] duration-ms)
        (assoc-in [:phase :status] :completed)
        (assoc-in [:phase :metrics] metrics)
        (assoc-in [:metrics :verification :duration-ms] duration-ms)
        (assoc-in [:metrics :verification :repair-cycles] (dec iterations))
        (update-in [:execution :phases-completed] (fnil conj []) :verify)
        ;; Merge agent metrics into execution metrics
        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))))

(defn- error-verify
  "Handle verification phase errors.

   On test failure, can redirect to :implement if on-fail specified."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 3)
        on-fail (get-in ctx [:phase-config :on-fail])]
    (cond
      ;; Within budget - retry
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Has on-fail transition - redirect
      on-fail
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :redirect-to] on-fail)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)}))

      ;; No recovery - propagate
      :else
      (-> ctx
          (assoc-in [:phase :status] :failed)
          (assoc-in [:phase :error] {:message (ex-message ex)
                                     :data (ex-data ex)})))))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod registry/get-phase-interceptor :verify
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::verify
     :config merged
     :enter (fn [ctx]
              (enter-verify (assoc ctx :phase-config merged)))
     :leave leave-verify
     :error error-verify}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :verify})
  (registry/get-phase-interceptor {:phase :verify :on-fail :implement})
  (registry/phase-defaults :verify)
  :leave-this-here)
