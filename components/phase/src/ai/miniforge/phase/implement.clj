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

(ns ai.miniforge.phase.implement
  "Implementation phase interceptor.

   Generates code artifacts from plans.
   Agent: :implementer
   Default gates: [:syntax :lint]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Event stream helpers (optional dependency)

(defn- emit-phase-started!
  "Emit phase-started event if event-stream is available in context."
  [ctx phase]
  (when-let [event-stream (:event-stream ctx)]
    (when-let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
      (when-let [phase-started (requiring-resolve 'ai.miniforge.event-stream.interface/phase-started)]
        (let [workflow-id (:execution/id ctx)]
          (publish! event-stream (phase-started event-stream workflow-id phase)))))))

(defn- emit-phase-completed!
  "Emit phase-completed event if event-stream is available in context."
  [ctx phase _result]
  (when-let [event-stream (:event-stream ctx)]
    (when-let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
      (when-let [phase-completed (requiring-resolve 'ai.miniforge.event-stream.interface/phase-completed)]
        (let [workflow-id (:execution/id ctx)
              outcome (if (= :completed (get-in ctx [:phase :status])) :success :failure)
              duration-ms (get-in ctx [:phase :duration-ms])]
          (publish! event-stream (phase-completed event-stream workflow-id phase
                                                   {:outcome outcome :duration-ms duration-ms})))))))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :implementer
   :gates [:syntax :lint]
   :budget {:tokens 30000
            :iterations 5
            :time-seconds 600}})

;; Register defaults on load
(registry/register-phase-defaults! :implement default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-implement
  "Execute implementation phase.

   Reads plan from context, invokes implementer agent,
   runs through inner loop with syntax/lint gates."
  [ctx]
  ;; Emit phase started event
  (emit-phase-started! ctx :implement)
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create implementer agent (specialized implementation uses llm/chat directly)
        implementer-agent (agent/create-implementer {})

        ;; Build task from workflow input and plan result
        input (get-in ctx [:execution/input])
        ;; Read from execution phase results where plan phase stored its output
        ;; Phase results contain the full phase map, so extract :result :output
        plan-result (get-in ctx [:execution/phase-results :plan :result :output])
        task {:task/id (random-uuid)
              :task/type :implement
              :task/description (:description input)
              :task/title (:title input)
              :task/intent (:intent input)
              :task/constraints (:constraints input)
              :task/plan plan-result} ; Pass plan output to implementer

        ;; Invoke agent
        result (try
                 (agent/invoke implementer-agent task ctx)
                 (catch Exception e
                   (response/failure e)))]

    (-> ctx
        (assoc-in [:phase :name] :implement)
        (assoc-in [:phase :agent] :implementer)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-implement
  "Post-processing for implementation phase.

   Records metrics, captures code artifacts."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        iterations (get-in ctx [:phase :iterations] 1)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] :completed)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :implementation :duration-ms] duration-ms)
                        (assoc-in [:metrics :implementation :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :implement)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Emit phase completed event
    (emit-phase-completed! updated-ctx :implement result)
    updated-ctx))

(defn- error-implement
  "Handle implementation phase errors.

   Attempts repair via inner loop if within budget."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 5)
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

(defmethod registry/get-phase-interceptor :implement
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::implement
     :config merged
     :enter (fn [ctx]
              (enter-implement (assoc ctx :phase-config merged)))
     :leave leave-implement
     :error error-implement}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get implement interceptor with defaults
  (registry/get-phase-interceptor {:phase :implement})

  ;; Get with overrides
  (registry/get-phase-interceptor {:phase :implement
                                   :budget {:tokens 50000}
                                   :gates [:syntax :lint :no-secrets]})

  ;; Check defaults
  (registry/phase-defaults :implement)

  :leave-this-here)
