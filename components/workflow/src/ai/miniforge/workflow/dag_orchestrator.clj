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
(ns ai.miniforge.workflow.dag-orchestrator
  "Orchestrates parallel task execution via DAG scheduling.

   Supports resumable execution: pass :pre-completed-ids in the context
   to skip tasks that completed in a prior run. Emits :dag/task-completed
   events to the event stream so completed work survives crashes.

   Honors the run's control state (`mf workflow pause|resume|cancel`)
   before launching each batch: a pause holds the next batch, a cancel
   ends the run with the rest unreached. Each task's sub-workflow gets
   the same control state, so in-flight tasks stop at their next phase
   boundary. An agent turn already running is not interrupted.

   Plan analysis/conversion (`dag-plan`), sub-workflow construction
   (`dag-sub-workflow`) and execution (`dag-task-execution`), v2
   multi-parent git-merge orchestration (`dag-merge` and its own
   internal split), and terminal-result/rate-limit accounting
   (`dag-finalize`, `dag-rate-limit`) all live in sibling namespaces —
   split out 2026-07-25, miniforge#1317, rule 210: this file was 1810
   lines with a non-monotonic layer stack. The batch-scheduling loop
   followed into `dag-loop` and the plan-shape logging into `dag-plan`.
   This namespace keeps the plan-level entry points (`execute-plan-as-dag`,
   `maybe-parallelize-plan`) and re-exports the handful of relocated vars
   that existing callers/tests reference through this namespace so the
   public interface is unchanged."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-finalize :as dag-finalize]
   [ai.miniforge.workflow.dag-loop :as dag-loop]
   [ai.miniforge.workflow.dag-merge :as dag-merge]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-sub-workflow :as dag-sub-workflow]
   [ai.miniforge.workflow.dag-task-execution :as dag-task-execution]))

;------------------------------------------------------------------------------ Layer 0

;; Compatibility Re-exports
;; Moved to dag-plan / dag-sub-workflow / dag-task-execution / dag-merge /
;; dag-loop; re-exported here so existing `:require [... :as dag-orch]`
;; call sites (production and test) keep resolving without modification.
(def ^{:stratum 0} zero-metrics dag-plan/zero-metrics)

(def ^{:stratum 0} parallelizable-plan? dag-plan/parallelizable-plan?)

(def ^{:stratum 0} estimate-parallel-speedup dag-plan/estimate-parallel-speedup)

(def ^{:stratum 0} plan->dag-tasks dag-plan/plan->dag-tasks)

(def ^{:stratum 0} wire-stratum-deps dag-plan/wire-stratum-deps)

(def ^{:stratum 0} task-sub-workflow dag-sub-workflow/task-sub-workflow)

(def ^{:stratum 0} task-sub-input dag-sub-workflow/task-sub-input)

(def ^{:stratum 0} task-sub-opts dag-sub-workflow/task-sub-opts)

(def ^{:stratum 0} run-mini-workflow dag-task-execution/run-mini-workflow)

(def ^{:stratum 0} extract-sub-workflow-error dag-task-execution/extract-sub-workflow-error)

(def ^{:stratum 0} extract-pr-info-from-result dag-task-execution/extract-pr-info-from-result)

(def ^{:stratum 0} execute-single-task dag-task-execution/execute-single-task)

(def ^{:stratum 0} merge-parent-branches! dag-merge/merge-parent-branches!)

(def ^{:stratum 0} aggregate-results dag-finalize/aggregate-results)

(def ^{:stratum 0} propagate-failures dag-finalize/propagate-failures)

(def ^{:stratum 0} compute-ready-tasks dag-loop/compute-ready-tasks)

(def ^{:stratum 0} select-non-conflicting-batch dag-loop/select-non-conflicting-batch)

(def ^{:stratum 0} partition-results dag-loop/partition-results)

(def ^{:stratum 0} emit-batch-events! dag-loop/emit-batch-events!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        _ (dag-plan/warn-potential-monolith plan logger)
        task-defs (dag-plan/plan->dag-tasks plan (assoc context :logger logger))
        _ (dag-plan/log-multi-parent-detected! logger plan task-defs)
        tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))
        pre-completed (get context :pre-completed-ids #{})
        ctx (cond-> context
              (seq pre-completed) (assoc :pre-completed-ids pre-completed))]
    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan)
                      :task-count (count task-defs)
                      :pre-completed (count pre-completed)}})
    (dag-loop/execute-dag-loop tasks-map ctx logger #'execute-single-task)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} maybe-parallelize-plan [plan context]
  (let [estimate (dag-plan/estimate-parallel-speedup plan)]
    (when (:parallelizable? estimate)
      (let [logger (or (:logger context) (log/create-logger {:min-level :info}))]
        (log/info logger :dag-orchestrator :plan/parallelizing
                  {:data {:plan-id (:plan/id plan)
                          :task-count (:task-count estimate)
                          :max-parallel (:max-parallel estimate)
                          :estimated-speedup (:estimated-speedup estimate)}}))
      (execute-plan-as-dag plan context))))

;--- Rich Comment
(comment
  (def sample-plan
    {:plan/id (random-uuid)
     :plan/name "test-plan"
     :plan/tasks
     (let [a (random-uuid)
           b (random-uuid)
           c (random-uuid)]
       [{:task/id a :task/description "Task A" :task/type :implement :task/dependencies []}
        {:task/id b :task/description "Task B" :task/type :implement :task/dependencies []}
        {:task/id c :task/description "Task C" :task/type :test :task/dependencies [a b]}])})

  (parallelizable-plan? sample-plan)
  (estimate-parallel-speedup sample-plan)
  (parallelizable-plan? {:plan/tasks [{:task/id (random-uuid) :task/description "Only task"}]})
  (execute-plan-as-dag sample-plan {:logger (log/create-logger {:min-level :debug})})

  :leave-this-here)
