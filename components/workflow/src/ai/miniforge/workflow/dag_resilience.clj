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

(ns ai.miniforge.workflow.dag-resilience
  "Rate limit detection, backend failover, and DAG pause/resume support.

   Keeps the DAG orchestrator under 400 lines by extracting resilience
   concerns into this module. Three layers:
   - Layer 0: Rate limit detection from error patterns
   - Layer 1: Backend failover + event emission
   - Layer 2: Batch analysis + rate limit handling"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]))

;--- Layer 0: Rate Limit Detection

(defn- load-rate-limit-patterns
  "Load and compile rate-limit patterns from external error patterns.
   Returns a seq of compiled regex patterns."
  []
  (try
    (let [load-fn (requiring-resolve 'ai.miniforge.agent-runtime.error-classifier.patterns/external-patterns)]
      (->> @load-fn
           (filter :rate-limit?)
           (map :regex)))
    (catch Exception _
      ;; Fallback patterns if error-classifier not available
      (map re-pattern
           ["(?i)you've hit your limit"
            "(?i)rate.?limit"
            "(?i)quota.?exceeded"
            "429|Too Many Requests"
            "resets \\d+[ap]m|resets in \\d+"]))))

(def ^:private rate-limit-patterns
  (delay (load-rate-limit-patterns)))

(defn rate-limit-error?
  "Check if a DAG result indicates a rate limit error."
  [result]
  (when-not (dag/ok? result)
    (let [msg (or (get-in result [:error :message])
                  (get-in result [:error :data :message])
                  (str result))]
      (boolean (some #(re-find % msg) @rate-limit-patterns)))))

;--- Layer 1: Backend Failover + Event Emission

(defn attempt-backend-switch
  "Attempt to switch to an alternative backend after rate limiting.

   Returns {:switched? true :new-backend :openai} or
           {:switched? false :reason ...}"
  [current-backend allowed-backends cost-ceiling accumulated-cost logger]
  (cond
    ;; Cost ceiling exceeded
    (and cost-ceiling (> accumulated-cost cost-ceiling))
    (do (log/info logger :dag-resilience :failover/cost-ceiling-exceeded
                  {:data {:ceiling cost-ceiling :accumulated accumulated-cost}})
        {:switched? false :reason :cost-ceiling})

    ;; No allowed backends configured (user didn't opt in)
    (empty? allowed-backends)
    (do (log/info logger :dag-resilience :failover/no-allowed-backends {})
        {:switched? false :reason :no-allowed-backends})

    ;; Try to find a healthy backend from the allowed list
    :else
    (try
      (let [record-call! (requiring-resolve 'ai.miniforge.self-healing.backend-health/record-backend-call!)
            trigger-switch! (requiring-resolve 'ai.miniforge.self-healing.backend-health/trigger-backend-switch!)
            _ (record-call! current-backend false)
            ;; Pick first allowed backend that isn't the current one
            candidate (first (filter #(not= % (keyword current-backend))
                                     (map keyword allowed-backends)))]
        (if candidate
          (let [result (trigger-switch! current-backend candidate)]
            (log/info logger :dag-resilience :failover/switched
                      {:data {:from current-backend :to candidate}})
            {:switched? true :new-backend candidate :switch-result result})
          (do (log/info logger :dag-resilience :failover/exhausted
                        {:data {:allowed allowed-backends}})
              {:switched? false :exhausted? true :reason :all-allowed-exhausted})))
      (catch Exception e
        (log/info logger :dag-resilience :failover/error
                  {:data {:error (.getMessage e)}})
        {:switched? false :reason :failover-error :error (.getMessage e)}))))

(defn emit-dag-task-completed!
  "Publish :dag/task-completed event for checkpointing."
  [event-stream workflow-id task-id result]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/task-completed
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/task-id task-id
                   :dag/result (select-keys result [:data :status])}))
      (catch Exception _ nil))))

(defn emit-dag-paused!
  "Publish :dag/paused event with completed task IDs for resume."
  [event-stream workflow-id completed-ids reason]
  (when event-stream
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  {:event/type :dag/paused
                   :event/timestamp (str (java.time.Instant/now))
                   :workflow/id workflow-id
                   :dag/completed-task-ids (vec completed-ids)
                   :dag/pause-reason reason}))
      (catch Exception _ nil))))

;--- Layer 2: Batch Analysis + Rate Limit Handling

(defn analyze-batch-for-rate-limits
  "Categorize batch results into completed, rate-limited, and other-failed."
  [results]
  (reduce (fn [acc [task-id result]]
            (cond
              (dag/ok? result)
              (update acc :completed-ids conj task-id)

              (rate-limit-error? result)
              (update acc :rate-limited-ids conj task-id)

              :else
              (update acc :other-failed-ids conj task-id)))
          {:completed-ids #{} :rate-limited-ids #{} :other-failed-ids #{}}
          results))

(defn handle-rate-limited-batch
  "Orchestrate the failover decision for a rate-limited batch.

   Returns {:action :continue :new-backend X} or {:action :pause :reason \"...\"}."
  [context rate-limited-ids completed-ids logger]
  (let [self-healing (or (get-in context [:execution/opts :self-healing])
                         (get-in context [:user-config :self-healing])
                         {})
        auto-switch? (get self-healing :backend-auto-switch true)
        allowed (get self-healing :allowed-failover-backends [])
        cost-ceiling (get self-healing :max-cost-per-workflow)
        accumulated-cost (get-in context [:execution/metrics :cost-usd] 0.0)
        current-backend (or (get-in context [:llm-backend :backend-type])
                            (get context :current-backend :claude))]
    (log/info logger :dag-resilience :rate-limit/detected
              {:data {:rate-limited-count (count rate-limited-ids)
                      :completed-count (count completed-ids)
                      :auto-switch? auto-switch?
                      :allowed-backends allowed}})
    (if (and auto-switch? (seq allowed))
      (let [switch-result (attempt-backend-switch
                           current-backend allowed cost-ceiling accumulated-cost logger)]
        (if (:switched? switch-result)
          {:action :continue :new-backend (:new-backend switch-result)}
          {:action :pause
           :reason (str "Rate limited. Failover failed: " (name (or (:reason switch-result) :unknown)))}))
      {:action :pause
       :reason (if (not auto-switch?)
                 "Rate limited. Backend auto-switch is disabled."
                 "Rate limited. No failover backends configured (set :allowed-failover-backends).")})))
