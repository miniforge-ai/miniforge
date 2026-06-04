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

(ns ai.miniforge.phase-software-factory.plan
  "Planning phase interceptor.

   Creates implementation plans from specifications.
   Agent: :planner
   Default gates: [:plan-complete]"
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.knowledge-helpers :as kb-helpers]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :plan))

;; Register defaults on load
(phase/register-phase-defaults! :plan default-config)

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
;; Plan result validation (GROUP 1 — plan-from-agent-dag-wiring)

(defn- validate-dag-readiness
  "Validate that the agent result is DAG-ready after a successful plan.

   Returns the result unchanged when valid, or a failure response with a
   clear diagnostic when the plan would silently collapse DAG execution:

     :anomalies.dag/no-tasks    — planner returned 0 tasks (would trigger
                                   :no-tasks DAG skip → monolithic implement)
     :anomalies.dag/unknown-deps — one or more tasks reference dep IDs that
                                   don't exist in this plan (runtime orphans)

   :already-satisfied and error/failure results pass through unchanged — their
   statuses are handled by the workflow FSM via extract-status."
  [agent-result]
  (if (not= :success (:status agent-result))
    agent-result
    (let [output   (:output agent-result)
          tasks    (:plan/tasks output)
          task-ids (set (keep :task/id tasks))]
      (cond
        ;; Zero tasks → :no-tasks DAG skip → silent monolithic implement fallback
        (empty? tasks)
        (response/error
         (ex-info
          (str "Planner produced 0 tasks — DAG orchestrator cannot activate. "
               "Planner must decompose the spec into a non-empty :plan/tasks vector. "
               "Use :already-satisfied evidence bundle when the work is truly done.")
          {:phase     :plan
           :plan/id   (:plan/id output)
           :plan/name (:plan/name output)
           :anomaly   :anomalies.dag/no-tasks}))

        ;; Unknown dep refs → orphan tasks at DAG runtime — fail early
        :else
        (let [invalid-deps (for [t    tasks
                                 dep  (:task/dependencies t)
                                 :when (not (contains? task-ids dep))]
                             {:task/id (:task/id t) :unknown-dep dep})]
          (if (seq invalid-deps)
            (response/error
             (ex-info
              (str "Plan contains tasks with unknown dependency references. "
                   "Each :task/dependencies entry must be a :task/id from this plan.")
              {:phase        :plan
               :plan/id      (:plan/id output)
               :invalid-deps (vec invalid-deps)
               :anomaly      :anomalies.dag/unknown-deps}))
            agent-result))))))

;------------------------------------------------------------------------------ Layer 1
;; Plan from LLM agent (normal path)

(defn build-planner-task
  "Build the task map to pass to the planner agent.

   The planner now receives a `:task/behavior-addendum` produced by
   `phase/load-and-filter-behaviors` for rules that target `:plan`
   (specification-standards, work-spec-authoring, simple-made-easy,
   etc.) — same mechanism implement and review use. Closes the gap
   where the planner generated plans without consulting the
   compiled standards pack.

   Returns {:task task-map :rules-manifest manifest-or-nil}."
  [input explore-result knowledge-store]
  (let [existing-files (:exploration/files explore-result)
        {:keys [formatted manifest]} (kb-helpers/inject-with-manifest
                                       knowledge-store :planner (get input :tags []))
        behavior-addendum (phase/load-and-filter-behaviors
                            :plan {:task {:task/intent (:intent input)}})
        task (cond-> {:task/id (random-uuid)
                      :task/type :plan
                      :task/description (:description input)
                      :task/title (:title input)
                      :task/intent (:intent input)
                      :task/constraints (:constraints input)}
               (seq existing-files)
               (assoc :task/existing-files existing-files)
               formatted
               (assoc :task/knowledge-context formatted)
               behavior-addendum
               (assoc :task/behavior-addendum behavior-addendum))]
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
    {:result (validate-dag-readiness
              (try
                (agent/invoke planner-agent task agent-ctx)
                (catch Exception e
                  ;; Preserve the spent-token count from the agent's failure
                  ;; anomaly (planner tags ex-data with :tokens) into the
                  ;; failure result's :metrics, so leave-plan still merges the
                  ;; real cost into :execution/metrics instead of reporting $0.
                  (let [ed   (ex-data e)
                        toks (get ed :tokens 0)]
                    (response/failure e {:data ed
                                         :tokens toks
                                         :metrics {:tokens toks}})))))
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
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        input (get-in ctx [:execution/input])
        spec-tasks (:plan/tasks input)
        {:keys [result rules-manifest]}
        (if spec-tasks
          {:result (plan-from-spec-tasks input spec-tasks)
           :rules-manifest nil}
          (plan-from-agent ctx input))]
    (-> (phase/enter-context ctx :plan :planner gates budget start-time result)
        (assoc-in [:phase :rules-manifest] rules-manifest))))

(defn leave-plan
  "Post-processing for planning phase.

   Records metrics and updates execution state."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (- end-time start-time)
        result (get-in ctx [:phase :result])
        success? (response/success? result)
        already-satisfied? (= :already-satisfied (:status result))
        ;; Read tokens from :metrics, falling back to a top-level :tokens —
        ;; failure results carry the spent count one or the other way, and we
        ;; merge cost into :execution/metrics REGARDLESS of success so a failed
        ;; plan turn no longer reports $0.
        metrics (-> (get result :metrics {:tokens (get result :tokens 0)
                                          :duration-ms duration-ms})
                    (assoc :duration-ms duration-ms))
        ;; already-satisfied is a NEUTRAL success outcome, not a failure.
        succeeded-or-done? (or success? already-satisfied?)
        phase-status (cond already-satisfied? :already-satisfied
                           success?           :completed
                           :else              :failed)
        updated-ctx (-> ctx
                        (assoc-in [:phase :ended-at] end-time)
                        (assoc-in [:phase :duration-ms] duration-ms)
                        (assoc-in [:phase :status] phase-status)
                        (assoc-in [:phase :metrics] metrics)
                        ;; Count a completed OR already-satisfied plan; not a failure.
                        (cond-> succeeded-or-done?
                          (update-in [:execution :phases-completed] (fnil conj []) :plan))
                        ;; Merge agent metrics into execution metrics on EVERY
                        ;; outcome — tokens were spent whether or not the plan
                        ;; succeeded.
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd metrics 0.0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    ;; Emit phase-completed with the REAL outcome — never a false :success on a
    ;; failure result (that produced the ✓-then-✗ double-emit).
    (phase/emit-phase-completed! updated-ctx :plan
      (merge {:outcome     (if succeeded-or-done? :success :failure)
              :duration-ms duration-ms
              :tokens      (get metrics :tokens 0)}
             (phase-terminal/derive-termination-reason result nil)))
    updated-ctx))

(defn error-plan
  "Handle planning phase errors. Retry within budget, then fail in
   place (Plan historically never honored `:on-fail`; pass
   `redirect? = false` to preserve that semantics)."
  [ctx ex]
  (phase/handle-error ctx ex 3 false))

;------------------------------------------------------------------------------ Layer 2
;; Registry method

(defmethod phase/get-phase-interceptor-method :plan
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::plan
     :config merged
     :enter (fn [ctx]
              (enter-plan (assoc ctx :phase-config merged)))
     :leave leave-plan
     :error error-plan}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Get plan interceptor with defaults
  (phase/get-phase-interceptor {:phase :plan})

  ;; Get with overrides
  (phase/get-phase-interceptor {:phase :plan
                                   :budget {:tokens 20000}})

  ;; Check defaults
  (phase/phase-defaults :plan)

  :leave-this-here)
