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

(ns ai.miniforge.phase.verify
  "Verification phase interceptor.

   Generates and runs tests for code artifacts.
   Agent: :tester
   Default gates: [:tests-pass :coverage]"
  (:require [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.task.interface :as task]
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
  {:agent :tester
   :gates [:tests-pass :coverage]
   :budget {:tokens 15000
            :iterations 3
            :time-seconds 300}
   ;; Verification can use fast validation - hint at Haiku
   :model-hint :haiku-4.5})

;; Register defaults on load
(registry/register-phase-defaults! :verify default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-verify
  "Execute verification phase.

   Generates tests for code artifacts, runs them,
   checks coverage thresholds."
  [ctx]
  ;; Emit phase started event
  (emit-phase-started! ctx :verify)
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create tester agent (specialized implementation uses llm/chat directly)
        tester-agent (agent/create-tester {})

        ;; Build task from workflow input and implementation result
        input (get-in ctx [:execution/input])
        ;; Code can come from:
        ;; 1. Previous implement phase result (in multi-phase workflows)
        ;; 2. Direct input as :task/code-artifact (in test-only workflows)
        ;; Read from execution phase results where implement phase stored its output
        ;; Phase results contain the full phase map, so extract :result :output
        implement-result (get-in ctx [:execution/phase-results :implement :result :output])
        code-artifact (or implement-result
                         (:task/code-artifact input)
                         (:code-artifact input))
        ;; Fail fast if no code artifact is available
        _ (when-not code-artifact
            (throw (ex-info "Verify phase received no code artifact from implement phase"
                            {:phase :verify
                             :available-keys (keys (get-in ctx [:execution/phase-results :implement :result]))
                             :hint "Check implement phase agent result"})))
        ;; Build task using canonical builder
        task (task/verify-task code-artifact input)

        ;; Create streaming callback for agent output
        on-chunk (when-let [es (:event-stream ctx)]
                   (when-let [create-cb (requiring-resolve
                                         'ai.miniforge.event-stream.interface/create-streaming-callback)]
                     (create-cb es (:execution/id ctx) :verify
                                {:print? (not (:quiet ctx)) :quiet? (:quiet ctx)})))
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))

        ;; Invoke agent
        result (try
                 (agent/invoke tester-agent task agent-ctx)
                 (catch Exception e
                   (response/failure e)))]

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
        iterations (get-in ctx [:phase :iterations] 1)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] :completed)
                        (assoc-in [:phase :metrics] metrics)
                        (assoc-in [:metrics :verification :duration-ms] duration-ms)
                        (assoc-in [:metrics :verification :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :verify)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Emit phase completed event
    (emit-phase-completed! updated-ctx :verify result)
    updated-ctx))

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
