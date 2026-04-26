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

(ns ai.miniforge.workflow.configurable
  "DAG-based workflow execution using configurable workflows.

   Configurable workflows still source their phase definitions from legacy
   `:workflow/phases` data, but execution authority now comes from the
   compiled workflow FSM via `workflow.context`."
  (:require
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.agent-factory :as factory]
   [ai.miniforge.workflow.context :as context]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.workflow.definition :as definition]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-defaults :as defaults]))

;------------------------------------------------------------------------------ Layer 0
;; Constants and result helpers

(def zero-metrics
  "Canonical zeroed metrics for configurable workflow results."
  {:tokens 0
   :cost-usd 0.0
   :duration-ms 0})

(defn- phase-error
  [error-type message & [extra]]
  (cond-> {:type error-type
           :message message}
    extra (merge extra)))

(defn- execution-error
  [error-type message & [extra]]
  (cond-> {:type error-type
           :message message}
    extra (merge extra)))

(defn- configurable-success?
  [phase-result]
  (true? (:success? phase-result)))

(defn- terminal?
  [ctx]
  (or (phase/succeeded? ctx)
      (phase/failed? ctx)))

(defn- merge-phase-metrics
  [exec-metrics phase-metrics]
  (context/merge-metrics exec-metrics (or phase-metrics zero-metrics)))

;------------------------------------------------------------------------------ Layer 0.5
;; Workflow lookup and normalization

(defn find-phase
  "Find phase configuration by ID in workflow."
  [workflow phase-id]
  (definition/find-phase workflow phase-id))

(defn- execution-workflow
  [workflow]
  (definition/execution-workflow workflow))

(defn- workflow-max-phases
  [execution-context]
  (get execution-context :max-phases (defaults/max-phases)))

(defn- phase-not-found-message
  [phase-id]
  (messages/t :configurable/phase-not-found {:phase phase-id}))

(defn- max-phases-message
  [max-phases]
  (messages/t :status/max-phases {:max-phases max-phases}))

(defn- missing-phase-handler-message
  [handler-key]
  (messages/t :configurable/missing-phase-handler {:handler-key handler-key}))

(defn- phase-execution-failed-message
  [error-message]
  (messages/t :configurable/phase-execution-failed {:error error-message}))

(defn- dag-execution-failed-message
  []
  (messages/t :configurable/dag-execution-failed))

;------------------------------------------------------------------------------ Layer 1
;; Phase execution

(defn execute-handler-phase
  "Execute a phase using a caller-provided handler from :phase-handlers context."
  [phase-config exec-state execution-context]
  (let [handler-key (:phase/handler phase-config)
        handler (get (:phase-handlers execution-context) handler-key)
        message (missing-phase-handler-message handler-key)]
    (if handler
      (handler phase-config exec-state execution-context)
      {:success? false
       :artifacts []
       :errors [(phase-error :missing-phase-handler
                             message
                             {:phase (:phase/id phase-config)
                              :handler handler-key})]
       :metrics zero-metrics})))

(defn- loop-exception-result
  [exception]
  (let [message (phase-execution-failed-message (.getMessage exception))]
    {:success false
     :error message
     :metrics zero-metrics}))

(defn- execute-agent-loop
  [phase-config exec-state execution-context]
  (let [phase-agent-instance (factory/create-agent-for-phase phase-config execution-context)
        task (factory/create-task-for-phase phase-config exec-state execution-context)
        generate-fn (factory/create-generate-fn phase-agent-instance execution-context)
        repair-fn (factory/create-repair-fn phase-agent-instance execution-context)
        max-iterations (get-in phase-config [:phase/inner-loop :max-iterations] 5)
        loop-context (assoc execution-context
                            :max-iterations max-iterations
                            :repair-fn repair-fn)]
    (try
      (loop/run-simple task generate-fn loop-context)
      (catch Exception exception
        (loop-exception-result exception)))))

(defn- build-successful-phase-result
  [loop-result phase-config]
  (let [raw-artifact (:artifact loop-result)
        metrics (get loop-result :metrics zero-metrics)
        standard-artifact (factory/build-artifact-for-phase raw-artifact
                                                            phase-config
                                                            metrics)]
    {:success? true
     :artifacts [standard-artifact]
     :errors []
     :metrics metrics}))

(defn- build-failed-phase-result
  [loop-result phase-config]
  (let [metrics (get loop-result :metrics zero-metrics)
        message (get loop-result :error
                     (messages/t :configurable/default-phase-error))]
    {:success? false
     :artifacts []
     :errors [(phase-error :phase-failed
                           message
                           {:phase (:phase/id phase-config)})]
     :metrics metrics}))

(defn execute-configurable-phase
  "Execute a single phase of a configurable workflow."
  [phase-config exec-state execution-context]
  (let [phase-agent (:phase/agent phase-config)]
    (cond
      (:phase/handler phase-config)
      (execute-handler-phase phase-config exec-state execution-context)

      (= :none phase-agent)
      {:success? true
       :artifacts []
       :errors []
       :metrics zero-metrics}

      :else
      (let [loop-result (execute-agent-loop phase-config exec-state execution-context)]
        (if (:success loop-result)
          (build-successful-phase-result loop-result phase-config)
          (build-failed-phase-result loop-result phase-config))))))

;------------------------------------------------------------------------------ Layer 2
;; Context mutation

(defn- record-phase-result
  [ctx phase-id phase-result]
  (let [artifacts (:artifacts phase-result [])
        errors (:errors phase-result [])
        metrics (:metrics phase-result zero-metrics)]
    (-> ctx
        (assoc-in [:execution/phase-results phase-id] phase-result)
        (update :execution/artifacts into artifacts)
        (update :execution/errors into errors)
        (update :execution/metrics merge-phase-metrics metrics))))

(defn- fail-execution
  [ctx error-map]
  (-> ctx
      (update :execution/errors conj error-map)
      (context/transition-to-failed)))

(defn- transition-phase-outcome
  [ctx phase-result]
  (if (configurable-success? phase-result)
    (context/transition-execution ctx :phase/succeed)
    (context/transition-execution ctx :phase/fail)))

;------------------------------------------------------------------------------ Layer 3
;; DAG execution

(defn extract-plan-from-result
  "Extract the first plan artifact from a phase result, if present."
  [phase-result]
  (some #(when (or (:plan/id %)
                   (= :plan (:artifact/type %)))
           %)
        (:artifacts phase-result)))

(defn- pre-completed-dag-task-ids
  [ctx execution-context]
  (get-in ctx
          [:execution/opts :pre-completed-dag-tasks]
          (get execution-context :pre-completed-dag-tasks #{})))

(defn- notify-dag-task-start
  [on-phase-start ctx task-id]
  (when on-phase-start
    (on-phase-start ctx
                    {:phase/id :dag-task
                     :task-id task-id})))

(defn- notify-dag-task-complete
  [on-phase-complete ctx task-id result]
  (when on-phase-complete
    (on-phase-complete ctx
                       {:phase/id :dag-task
                        :task-id task-id}
                       result)))

(defn- build-dag-context
  [ctx execution-context callbacks]
  (let [{:keys [on-phase-start on-phase-complete]} callbacks
        pre-completed-ids (pre-completed-dag-task-ids ctx execution-context)]
    (assoc execution-context
           :workflow-id (:execution/id ctx)
           :execution/workflow (:execution/workflow ctx)
           :pre-completed-ids pre-completed-ids
           :on-task-start
           (fn [task-id]
             (notify-dag-task-start on-phase-start ctx task-id))
           :on-task-complete
           (fn [task-id result]
             (notify-dag-task-complete on-phase-complete ctx task-id result)))))

(defn execute-dag-for-plan
  "Execute plan tasks via the DAG orchestrator and merge results into context."
  [ctx plan execution-context callbacks]
  (let [pre-completed-artifacts (get-in ctx [:execution/opts :pre-completed-artifacts] [])
        dag-context (build-dag-context ctx execution-context callbacks)
        dag-result (dag-orch/execute-plan-as-dag plan dag-context)
        pr-infos (:pr-infos dag-result)
        dag-metrics (get dag-result :metrics zero-metrics)
        dag-artifacts (concat pre-completed-artifacts
                              (:artifacts dag-result []))
        ctx-with-dag (-> ctx
                         (update :execution/artifacts into dag-artifacts)
                         (update :execution/metrics merge-phase-metrics dag-metrics)
                         (assoc-in [:execution/phase-results :dag-execution] dag-result)
                         (assoc :execution/dag-result dag-result))]
    (cond-> ctx-with-dag
      (seq pr-infos) (assoc :execution/dag-pr-infos pr-infos))))

(defn- dag-parallelization-enabled?
  [execution-context]
  (not (:disable-dag-parallelization execution-context)))

(defn- should-parallelize-plan?
  [phase-id phase-result execution-context]
  (let [plan (when (= :plan phase-id)
               (extract-plan-from-result phase-result))]
    (when (and plan
               (configurable-success? phase-result)
               (dag-parallelization-enabled? execution-context)
               (dag-orch/parallelizable-plan? plan))
      plan)))

(defn- continue-after-dag
  [ctx]
  (let [ctx-with-transition (context/transition-execution ctx :phase/succeed)]
    [:continue ctx-with-transition]))

;------------------------------------------------------------------------------ Layer 4
;; Workflow execution

(defn execute-phase-step
  "Execute a single configurable workflow phase."
  [ctx phase-config execution-context callbacks]
  (let [{:keys [on-phase-start on-phase-complete]} callbacks
        phase-id (:execution/current-phase ctx)]
    (when on-phase-start
      (on-phase-start ctx phase-config))
    (let [phase-result (execute-configurable-phase phase-config ctx execution-context)
          ctx-with-result (record-phase-result ctx phase-id phase-result)]
      (when on-phase-complete
        (on-phase-complete ctx-with-result phase-config phase-result))
      (if-let [plan (should-parallelize-plan? phase-id phase-result execution-context)]
        (let [ctx-with-dag (execute-dag-for-plan ctx-with-result plan execution-context callbacks)
              dag-success? (true? (get-in ctx-with-dag [:execution/dag-result :success?]))]
          (if dag-success?
            (continue-after-dag ctx-with-dag)
            (fail-execution ctx-with-dag
                            (execution-error :dag-execution-failed
                                             (dag-execution-failed-message)))))
        (let [next-ctx (transition-phase-outcome ctx-with-result phase-result)]
          (if (terminal? next-ctx)
            next-ctx
            [:continue next-ctx]))))))

(defn run-configurable-workflow
  "Execute a complete configurable workflow with the compiled execution machine."
  [workflow input execution-context]
  (let [workflow' (execution-workflow workflow)
        max-phases (workflow-max-phases execution-context)
        callbacks {:on-phase-start (:on-phase-start execution-context)
                   :on-phase-complete (:on-phase-complete execution-context)}]
    (loop [ctx (context/create-context workflow' input execution-context)
           phase-count 0]
      (cond
        (terminal? ctx)
        ctx

        (>= phase-count max-phases)
        (fail-execution ctx
                        (execution-error :max-phases-exceeded
                                         (max-phases-message max-phases)))

        :else
        (let [phase-id (:execution/current-phase ctx)
              phase-config (find-phase workflow' phase-id)]
          (if-not phase-config
            (fail-execution ctx
                            (execution-error :phase-not-found
                                             (phase-not-found-message phase-id)
                                             {:phase phase-id}))
            (let [step-result (execute-phase-step ctx phase-config
                                                  execution-context callbacks)]
              (if (vector? step-result)
                (recur (second step-result) (inc phase-count))
                step-result))))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.loader :as loader])
  (require '[ai.miniforge.agent.interface :as agent])

  (def workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {}))
  (def workflow (:workflow workflow-result))
  (def mock-llm (agent/create-mock-llm {:content "(defn hello [] \"world\")"}))

  (run-configurable-workflow workflow {:task "Test task"} {:llm-backend mock-llm})
  :leave-this-here)
