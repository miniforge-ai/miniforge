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

(ns ai.miniforge.cli.main.commands.plan-executor
  "Execute pre-planned DAG or plan files directly, skipping explore/plan phases."
  (:require
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.workflow.interface :as workflow])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0
;; Format normalization

(defn deterministic-uuid
  "Generate a deterministic UUID from a string id (for DAG task-id → UUID conversion)."
  [s]
  (UUID/nameUUIDFromBytes (.getBytes (str s) "UTF-8")))

(defn normalize-dag-task
  "Normalize a DAG-format task to plan-format task."
  [task]
  (let [task-id (if (uuid? (:task/id task))
                  (:task/id task)
                  (deterministic-uuid (:task/id task)))
        deps (or (:task/dependencies task)
                 (when-let [d (:task/deps task)]
                   (set (map #(if (uuid? %) % (deterministic-uuid %)) d)))
                 #{})
        description (or (:task/description task) (:description task))
        criteria (let [ac (or (:task/acceptance-criteria task)
                              (:acceptance-criteria task))]
                   (cond
                     (vector? ac) ac
                     (string? ac) [ac]
                     :else []))]
    {:task/id task-id
     :task/dependencies deps
     :task/description description
     :task/acceptance-criteria criteria
     :task/type (:task/type task :implement)}))

(defn normalize-dag-to-plan
  "Convert DAG format ({:dag-id, :tasks}) to plan format ({:plan/id, :plan/tasks})."
  [dag]
  {:plan/id (:dag-id dag)
   :plan/title (or (:description dag) (:dag-id dag))
   :plan/tasks (mapv normalize-dag-task (:tasks dag))})

(defn detect-plan-format
  "Detect whether input is already plan format or needs conversion."
  [parsed]
  (cond
    (:plan/id parsed) :plan
    (:dag-id parsed)  :dag
    :else             nil))

;------------------------------------------------------------------------------ Layer 1
;; Execution context setup

(defn build-execution-workflow
  "Build a workflow definition for plan execution (implement → verify → done)."
  [plan-id]
  {:workflow/id (keyword (str "plan-exec-" plan-id))
   :workflow/version "2.0.0"
   :workflow/name (str "Plan execution: " plan-id)
   :workflow/pipeline [{:phase :implement} {:phase :verify} {:phase :done}]
   :workflow/config {:max-tokens 20000 :max-iterations 50}})

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn execute-plan
  "Execute a pre-planned DAG or plan file directly via dag-orchestrator.

   Normalizes DAG format to plan format if needed, sets up execution context,
   and delegates to execute-plan-as-dag."
  [parsed opts]
  (let [format-type (detect-plan-format parsed)
        plan (case format-type
               :dag (normalize-dag-to-plan parsed)
               :plan parsed)
        plan-id (or (:plan/id plan) (str (random-uuid)))
        task-count (count (:plan/tasks plan))
        quiet (:quiet opts false)
        workflow (build-execution-workflow plan-id)
        event-stream (es/create-event-stream)
        _supervisor (supervisory/attach! event-stream)
        ;; N15-6: route routing-causality through the witness surface.
        _correlator (correlator/attach! event-stream)
        workflow-id (random-uuid)
        control-state (es/create-control-state)
        llm-client (context/create-llm-client workflow nil quiet)
        callbacks {:on-phase-start (fn [_ctx interceptor]
                                     (when-not quiet
                                       (display/print-info
                                        (str "Phase: " (get-in interceptor [:config :phase])))))
                   :on-phase-complete (fn [_ctx _interceptor _result]
                                        nil)}
        ctx (context/create-workflow-context {:callbacks callbacks
                                             :event-stream event-stream
                                             :workflow-id workflow-id
                                             :workflow-type (:workflow/id workflow)
                                             :workflow-version (:workflow/version workflow)
                                             :llm-client llm-client
                                             :quiet quiet
                                             :spec-title (:plan/title plan)
                                             :control-state control-state
                                             :skip-lifecycle-events false})
        ;; Enrich context with workflow definition for sub-workflow construction
        ctx (assoc ctx :execution/workflow workflow
                       :workflow-id workflow-id
                       :pre-completed-ids (get opts :pre-completed-dag-tasks #{}))]
    (when-not quiet
      (display/print-info (str "Executing plan: " plan-id " (" task-count " tasks)"))
      (display/print-info (str "Format: " (name format-type))))
    (try
      ;; Governed control path: register before the DAG starts so a
      ;; pause/cancel intervention aimed at this run can reach it, and
      ;; release in the finally so a dead runner stops claiming them.
      (control/register-workflow-control! workflow-id control-state event-stream)
      (let [result (workflow/execute-plan-as-dag plan ctx)]
        (when-not quiet
          (let [completed (:tasks-completed result 0)
                failed (:tasks-failed result 0)
                unreached (:tasks-unreached result 0)]
            (display/print-info (str "Plan execution complete: "
                                     completed " completed, " failed " failed"
                                     (when (pos? unreached)
                                       (str ", " unreached " unreached"))))))
        result)
      (catch Exception e
        (display/print-error (str "Plan execution failed: " (ex-message e)))
        (throw e))
      (finally
        (control/release-workflow-control! workflow-id)))))
