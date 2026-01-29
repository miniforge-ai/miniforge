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

(ns ai.miniforge.phase.review
  "Review phase interceptor.

   Performs code review and quality checks.
   Agent: :reviewer
   Default gates: [:review-approved :quality-check]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  {:agent :reviewer
   :gates [:review-approved :quality-check]
   :budget {:tokens 10000
            :iterations 2
            :time-seconds 180}})

;; Register defaults on load
(registry/register-phase-defaults! :review default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-review
  "Execute review phase.

   Runs code review, quality checks, and policy validation."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create reviewer agent (does not use LLM - runs gates)
        reviewer-agent (agent/create-reviewer {:gates (map (fn [gate-name]
                                                                 ;; This is simplified - in real implementation
                                                                 ;; would resolve gate-name to actual gate impl
                                                                 {:gate/id gate-name
                                                                  :gate/type gate-name})
                                                               gates)})

        ;; Build task from workflow input and previous phase results
        input (get-in ctx [:execution/input])
        implement-result (get-in ctx [:phase :result]) ; From implement phase
        verify-result (or (get-in ctx [:phases :verify :result])
                          (get-in ctx [:previous-phase :result]))
        task {:task/id (random-uuid)
              :task/type :review
              :task/description (:description input)
              :task/title (:title input)
              :task/intent (:intent input)
              :task/constraints (:constraints input)
              :task/artifact implement-result ; Code to review
              :task/tests verify-result} ; Test results if available

        ;; Invoke agent
        result (try
                 (agent/invoke reviewer-agent task ctx)
                 (catch Exception e
                   (response/failure e)))]

    (-> ctx
        (assoc-in [:phase :name] :review)
        (assoc-in [:phase :agent] :reviewer)
        (assoc-in [:phase :gates] gates)
        (assoc-in [:phase :budget] budget)
        (assoc-in [:phase :started-at] start-time)
        (assoc-in [:phase :status] :running)
        (assoc-in [:phase :result] result))))

(defn- leave-review
  "Post-processing for review phase.

   Records review metrics: issues found, approval status."
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
        (assoc-in [:metrics :review :duration-ms] duration-ms)
        (assoc-in [:metrics :review :repair-cycles] (dec iterations))
        (update-in [:execution :phases-completed] (fnil conj []) :review)
        ;; Merge agent metrics into execution metrics
        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))))

(defn- error-review
  "Handle review phase errors.

   On rejection, can redirect to :implement if on-fail specified."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
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

(defmethod registry/get-phase-interceptor :review
  [config]
  (let [merged (registry/merge-with-defaults config)]
    {:name ::review
     :config merged
     :enter (fn [ctx]
              (enter-review (assoc ctx :phase-config merged)))
     :leave leave-review
     :error error-review}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (registry/get-phase-interceptor {:phase :review})
  (registry/get-phase-interceptor {:phase :review :on-fail :implement})
  (registry/phase-defaults :review)
  :leave-this-here)
