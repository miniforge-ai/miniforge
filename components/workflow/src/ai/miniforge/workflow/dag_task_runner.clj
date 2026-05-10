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

(ns ai.miniforge.workflow.dag-task-runner
  "Runs a single DAG task through a mini-workflow: creates an agent,
   runs an inner generate/repair loop, and converts the result into
   a dag-executor ok/err value.

   Extracted from dag_orchestrator to keep each file at a single
   abstraction layer."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.task.interface :as task]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]))

;--- Constants

(def ^:private mini-workflow-max-iterations
  "Inner generate/repair loop iteration cap for a single DAG task.
   Three is the historical setting carried over from the inline call
   site; lifted here so it can be tuned without hunting through the
   run-mini-workflow body. Distinct from the parent workflow's
   :max-iterations on the outer SDLC loop."
  3)

;--- Result Constructors (private, used only within mini-workflow)

(defn- workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics dag-orch/zero-metrics)})

(defn- workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics dag-orch/zero-metrics)})

;--- Mini-Workflow Execution

(defn- create-inner-task [task-def]
  (task/create-task
   {:task/id (random-uuid)
    :task/type :implement
    :task/title (:task/description task-def "Implement task")
    :task/description (:task/description task-def "Implement task")
    :task/status :pending
    :task/metadata {:dag-task-id (:task/id task-def)
                    :parent-plan-id (get-in task-def [:task/context :parent-plan-id])}}))

(defn- create-generate-fn [impl-agent context]
  (fn [t _ctx]
    (let [result (agent/invoke impl-agent t context)]
      ;; Forward :tokens and :cost-usd from the agent response. The
      ;; inner loop measures :duration-ms locally from a wall-clock
      ;; delta, so we don't (and don't need to) forward duration here.
      ;; Previously only :tokens was carried, leaving :cost-usd
      ;; dropped on every DAG task and surfacing as $0.0000 in the
      ;; runner banner despite real backend costs.
      {:artifact (:artifact result)
       :tokens   (or (get-in result [:metrics :tokens]) 0)
       :cost-usd (or (get-in result [:metrics :cost-usd]) 0.0)})))

(defn- create-repair-fn [impl-agent context]
  (fn [old-artifact errors _ctx]
    (let [result (agent/repair impl-agent old-artifact errors context)]
      ;; Same :tokens + :cost-usd forwarding as the generate-fn
      ;; (duration is measured loop-side). Repair iterations were
      ;; previously dropping :cost-usd on every call.
      {:success?    (:success result)
       :artifact    (:repaired result old-artifact)
       :tokens-used (or (get-in result [:metrics :tokens]) 0)
       :cost-usd    (or (get-in result [:metrics :cost-usd]) 0.0)})))

(defn- run-mini-workflow [task-def context]
  (let [impl-agent (agent/create-implementer {:llm-backend (:llm-backend context)})
        inner-task (create-inner-task task-def)
        generate-fn (create-generate-fn impl-agent context)
        loop-context (assoc context
                            :max-iterations mini-workflow-max-iterations
                            :repair-fn (create-repair-fn impl-agent context))
        loop-result (loop/run-simple inner-task generate-fn loop-context)]
    (if (:success loop-result)
      (workflow-success (:artifact loop-result) (:metrics loop-result))
      (workflow-failure (or (:error loop-result)
                            (get-in loop-result [:termination :message])
                            "Inner loop failed")
                        (:metrics loop-result)))))

(defn- workflow-result->dag-result [task-id description wf-result]
  (if (:success? wf-result)
    (dag/ok {:task-id task-id
             :description description
             :status :implemented
             :artifacts [(:artifact wf-result)]
             :metrics (:metrics wf-result)})
    (dag/err :task-execution-failed
             (:error wf-result)
             {:task-id task-id :metrics (:metrics wf-result)})))

(defn- placeholder-result [task-id description]
  (dag/ok {:task-id task-id
           :description description
           :status :implemented
           :artifacts []
           :metrics dag-orch/zero-metrics}))

(defn execute-single-task
  "Run a single DAG task. When an :llm-backend is present in context,
   spins up a mini-workflow (agent + generate/repair loop). Otherwise
   returns a placeholder result.

   Handles InterruptedException separately to restore the thread's
   interrupt flag — essential when the DAG orchestrator cancels futures
   during batch cleanup so the thread terminates cleanly."
  [task-def context]
  (let [task-id (:task/id task-def)
        description (:task/description task-def "Implement task")]
    (try
      (if (:llm-backend context)
        (workflow-result->dag-result task-id description (run-mini-workflow task-def context))
        (placeholder-result task-id description))
      (catch InterruptedException ie
        ;; Restore interrupt flag so the cancelled future terminates cleanly.
        (.interrupt (Thread/currentThread))
        (dag/err :task-cancelled
                 (str "Task cancelled: " (.getMessage ie))
                 {:task-id task-id}))
      (catch Exception e
        (dag/err :task-execution-failed
                 (str "Task failed: " (.getMessage e))
                 {:task-id task-id})))))

(defn create-task-executor-fn
  "Build a closure that the DAG executor can call for each task-id.
   Looks up the task definition from the DAG run-state, delegates to
   execute-single-task, and fires optional callbacks."
  [context opts]
  (let [{:keys [on-task-start on-task-complete]} opts]
    (fn [task-id dag-context]
      (when on-task-start (on-task-start task-id))
      (let [task-def (get-in dag-context [:run-state :run/tasks task-id])
            result (execute-single-task task-def context)]
        (when on-task-complete (on-task-complete task-id result))
        result))))
