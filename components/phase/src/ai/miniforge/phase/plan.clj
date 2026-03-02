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

(ns ai.miniforge.phase.plan
  "Planning phase interceptor.

   Creates implementation plans from specifications.
   Agent: :planner
   Default gates: [:plan-complete]"
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
  {:agent :planner
   :gates [:plan-complete]
   :budget {:tokens 10000
            :iterations 3
            :time-seconds 300}
   ;; Planning requires complex reasoning - hint at Opus
   :model-hint :opus-4.6})

;; Register defaults on load
(registry/register-phase-defaults! :plan default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- enter-plan
  "Execute planning phase.

   Reads specification from context, invokes planner agent,
   runs through inner loop with gates."
  [ctx]
  ;; Emit phase started event
  (emit-phase-started! ctx :plan)
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)

        ;; Create planner agent (specialized implementation uses llm/chat directly)
        planner-agent (agent/create-planner {})

        ;; Build task from workflow input
        input (get-in ctx [:execution/input])

        ;; Read exploration results if available (from explore phase)
        explore-result (get-in ctx [:execution/phase-results :explore :result :output])
        existing-files (:exploration/files explore-result)

        task (cond-> {:task/id (random-uuid)
                      :task/type :plan
                      :task/description (:description input)
                      :task/title (:title input)
                      :task/intent (:intent input)
                      :task/constraints (:constraints input)}
               (seq existing-files)
               (assoc :task/existing-files existing-files))

        ;; Create streaming callback for agent output
        on-chunk (when-let [es (:event-stream ctx)]
                   (when-let [create-cb (requiring-resolve
                                         'ai.miniforge.event-stream.interface/create-streaming-callback)]
                     (create-cb es (:execution/id ctx) :plan
                                {:print? (not (:quiet ctx)) :quiet? (:quiet ctx)})))
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))

        ;; Invoke agent (this will call LLM and do actual work)
        result (try
                 (agent/invoke planner-agent task agent-ctx)
                 (catch Exception e
                   (response/failure e)))]

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
        metrics (get result :metrics {:tokens 0 :duration-ms duration-ms})
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] :completed)
                        (assoc-in [:phase :metrics] metrics)
                        (update-in [:execution :phases-completed] (fnil conj []) :plan)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Emit phase completed event
    (emit-phase-completed! updated-ctx :plan result)
    ;; Handle :already-satisfied — signal pipeline to skip to :done
    (if (= :already-satisfied (:status result))
      (assoc-in updated-ctx [:phase :status] :already-satisfied)
      updated-ctx)))

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
