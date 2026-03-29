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
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase.registry :as registry]
            [ai.miniforge.phase.phase-config :as phase-config]
            [ai.miniforge.phase.knowledge-helpers :as kb-helpers]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :plan))

;; Register defaults on load
(registry/register-phase-defaults! :plan default-config)

;------------------------------------------------------------------------------ Layer 1
;; Phase context builder

(defn build-phase-context
  "Build the common phase context fields for plan phase."
  [ctx gates budget start-time result]
  (-> ctx
      (assoc-in [:phase :name] :plan)
      (assoc-in [:phase :agent] :planner)
      (assoc-in [:phase :gates] gates)
      (assoc-in [:phase :budget] budget)
      (assoc-in [:phase :started-at] start-time)
      (assoc-in [:phase :status] :running)
      (assoc-in [:phase :result] result)))

;------------------------------------------------------------------------------ Layer 1
;; Plan from spec tasks (fast path — no LLM)

(defn plan-from-spec-tasks
  "Build a plan directly from spec-provided tasks. No LLM call, 0 tokens."
  [input spec-tasks]
  (let [plan {:plan/id (random-uuid)
              :plan/name (or (:title input) (:spec/title input) "spec-provided-plan")
              :plan/tasks (mapv #(update % :task/id (fn [id] (or id (random-uuid)))) spec-tasks)
              :plan/created-at (java.util.Date.)}]
    (response/success plan {:tokens 0
                            :metrics {:tasks-created (count spec-tasks)
                                      :tokens 0
                                      :source :spec-provided}})))

;------------------------------------------------------------------------------ Layer 1
;; Plan from LLM agent (normal path)

(defn build-planner-task
  "Build the task map to pass to the planner agent.
   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [input explore-result knowledge-store]
  (let [existing-files (:exploration/files explore-result)
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       knowledge-store :planner (get input :tags []))
        task (cond-> {:task/id (random-uuid)
                      :task/type :plan
                      :task/description (:description input)
                      :task/title (:title input)
                      :task/intent (:intent input)
                      :task/constraints (:constraints input)}
               (seq existing-files)
               (assoc :task/existing-files existing-files)
               formatted
               (assoc :task/knowledge-context formatted))]
    {:task task
     :rules-manifest manifest}))

(defn create-streaming-callback
  "Create a streaming callback for agent output, if event-stream is available."
  [ctx]
  (phase/create-streaming-callback ctx :plan))

(defn plan-from-agent
  "Invoke the planner agent to generate a plan via LLM.
   Returns {:result agent-result :rules-manifest manifest-or-nil}."
  [ctx input]
  (let [explore-result (get-in ctx [:execution/phase-results :explore :result :output])
        {:keys [task rules-manifest]} (build-planner-task input explore-result (:knowledge-store ctx))
        on-chunk (create-streaming-callback ctx)
        agent-ctx (cond-> ctx on-chunk (assoc :on-chunk on-chunk))
        planner-agent (agent/create-planner {})]
    {:result (try
               (agent/invoke planner-agent task agent-ctx)
               (catch Exception e
                 (response/failure e)))
     :rules-manifest rules-manifest}))

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn enter-plan
  "Execute planning phase.

   Reads specification from context, invokes planner agent,
   runs through inner loop with gates.

   When the spec provides :plan/tasks directly, builds the plan from those
   tasks and skips the LLM call entirely."
  [ctx]
  (let [config (registry/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        input (get-in ctx [:execution/input])
        spec-tasks (:plan/tasks input)
        {:keys [result rules-manifest]}
        (if spec-tasks
          {:result (plan-from-spec-tasks input spec-tasks)
           :rules-manifest nil}
          (plan-from-agent ctx input))]
    (-> (build-phase-context ctx gates budget start-time result)
        (assoc-in [:phase :rules-manifest] rules-manifest))))

(defn leave-plan
  "Post-processing for planning phase.

   Records metrics and updates execution state."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        metrics (-> (get result :metrics {:tokens 0 :duration-ms duration-ms})
                    (assoc :duration-ms duration-ms))
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] :completed)
                        (assoc-in [:phase :metrics] metrics)
                        (update-in [:execution :phases-completed] (fnil conj []) :plan)
                        ;; Merge agent metrics into execution metrics
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Handle :already-satisfied — signal pipeline to skip to :done
    (if (= :already-satisfied (:status result))
      (assoc-in updated-ctx [:phase :status] :already-satisfied)
      updated-ctx)))

(defn error-plan
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
