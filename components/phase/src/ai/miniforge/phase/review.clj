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
   :budget {:tokens 20000
            :iterations 4
            :time-seconds 180}
   ;; Code review needs balanced capabilities - hint at Sonnet
   :model-hint :sonnet-4.5})

;; Register defaults on load
(registry/register-phase-defaults! :review default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-review
  "Execute review phase.

   Runs code review, quality checks, and policy validation."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create reviewer agent with LLM backend from context
        llm-backend (:llm-backend ctx)
        reviewer-agent (agent/create-reviewer
                        (cond-> {}
                          llm-backend (assoc :llm-backend llm-backend)))

        ;; Build task from workflow input and previous phase results
        input (get-in ctx [:execution/input])
        implement-result (get-in ctx [:execution/phase-results :implement :result :output])
        verify-result (get-in ctx [:execution/phase-results :verify :result :output])
        task {:task/id (random-uuid)
              :task/type :review
              :task/description (:description input)
              :task/title (:title input)
              :task/intent (:intent input)
              :task/constraints (:constraints input)
              :task/artifact (or (:artifact implement-result)
                                 implement-result) ; Extract code artifact
              :task/tests verify-result} ; Test results if available

        ;; Create streaming callback for agent output
        on-chunk (when-let [es (:event-stream ctx)]
                   (when-let [create-cb (requiring-resolve
                                         'ai.miniforge.event-stream.interface/create-streaming-callback)]
                     (create-cb es (:execution/id ctx) :review
                                {:print? (not (:quiet ctx)) :quiet? (:quiet ctx)})))
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))

        ;; Invoke agent
        result (try
                 (agent/invoke reviewer-agent task agent-ctx)
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

(defn leave-review
  "Post-processing for review phase.

   Records review metrics: issues found, approval status.
   When review decision is :changes-requested and within iteration budget,
   sets status to :failed with :redirect-to :implement so the execution engine
   jumps back to implement with the review feedback attached."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        review-decision (get-in result [:output :review/decision])
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        ;; changes-requested always sets :failed — the redirect-to :implement
        ;; tells the execution engine to jump back (not stay at review).
        ;; Within budget: redirect to implement for repair.
        ;; Over budget: fail without redirect (terminal failure).
        phase-status (if (= :changes-requested review-decision)
                       :failed
                       :completed)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :review :duration-ms] duration-ms)
                        (assoc-in [:metrics :review :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :review)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Handle changes-requested: redirect to implement with review feedback
    (if (and (= :changes-requested review-decision)
             (< iterations max-iterations))
      (-> updated-ctx
          (update-in [:phase :iterations] (fnil inc 1))
          (assoc-in [:phase :review-feedback]
                    (or (get-in result [:output :review/feedback])
                        (get-in result [:output :review/issues])))
          (assoc-in [:phase :redirect-to] :implement))
      updated-ctx)))

(defn error-review
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
