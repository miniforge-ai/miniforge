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

   Plan analysis/conversion (`dag-plan`), sub-workflow construction
   (`dag-sub-workflow`) and execution (`dag-task-execution`), v2
   multi-parent git-merge orchestration (`dag-merge` and its own
   internal split), and terminal-result/rate-limit accounting
   (`dag-finalize`, `dag-rate-limit`) all live in sibling namespaces —
   split out 2026-07-25, miniforge#1317, rule 210: this file was 1810
   lines with a non-monotonic layer stack. This namespace keeps the
   batch-scheduling engine itself (ready-task selection, parallel
   execution, checkpointing) and the plan-level entry points
   (`execute-plan-as-dag`, `maybe-parallelize-plan`), and re-exports the
   handful of relocated vars that existing callers/tests reference
   through this namespace so the public interface is unchanged.

   CAVEAT (stratum-lint SL003): this file still measures 4 distinct
   layers (max 3) after the split above. It's irreducible without
   breaking an existing test contract: `execute-single-task` must be a
   var literally named `dag-orchestrator/execute-single-task` (several
   tests `with-redefs` it directly), and `execute-dag-loop` /
   `execute-plan-as-dag` / `maybe-parallelize-plan` must each remain
   directly callable as `dag-orchestrator/<name>` (functional tests,
   plus configurable.clj/execution.clj/interface/pipeline.clj callers)
   — a linear must-colocate chain of 4 functions is 4 layers by
   construction, and moving any one of them to a sibling namespace
   creates a circular require (the sibling would need this namespace
   for `execute-single-task`; this namespace would need the sibling to
   re-export the moved function). Run with
   `MINIFORGE_STRATUM_BUDGET_MODE=warn` for this file until the wave
   program reaches `workflow` and either accepts this floor or revisits
   the with-redefs contract."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-finalize :as dag-finalize]
   [ai.miniforge.workflow.dag-merge :as dag-merge]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-rate-limit :as dag-rate-limit]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.workflow.dag-sub-workflow :as dag-sub-workflow]
   [ai.miniforge.workflow.dag-task-execution :as dag-task-execution]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

;--- Layer 0: Compatibility Re-exports
;; Moved to dag-plan / dag-sub-workflow / dag-task-execution / dag-merge;
;; re-exported here so existing `:require [... :as dag-orch]` call sites
;; (production and test) keep resolving without modification.
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

;--- Layer 1: Synchronous DAG Execution
(defn ^{:stratum 0} compute-ready-tasks [tasks-map completed-ids failed-ids]
  (->> tasks-map
       (filter (fn [[task-id task]]
                 (and (not (contains? completed-ids task-id))
                      (not (contains? failed-ids task-id))
                      (every? #(contains? completed-ids %) (:task/deps task #{})))))))

(defn ^{:stratum 0} select-non-conflicting-batch
  "Select up to max-parallel ready tasks whose exclusive-files don't overlap.
   Falls back to (take max-parallel) when no tasks declare exclusive-files."
  [ready-tasks max-parallel]
  (loop [candidates (seq ready-tasks)
         selected []
         claimed-files #{}]
    (cond
      (nil? candidates)                  selected
      (>= (count selected) max-parallel) selected
      :else
      (let [[_tid task] (first candidates)
            task-files (set (:task/exclusive-files task))]
        (if (and (seq task-files)
                 (seq (set/intersection claimed-files task-files)))
          (recur (next candidates) selected claimed-files)
          (recur (next candidates)
                 (conj selected (first candidates))
                 (into claimed-files task-files)))))))

(defn ^{:stratum 0} execute-tasks-batch
  "Execute a batch of tasks in parallel via futures.
   Ensures all futures are cancelled on failure so that sub-workflow
   finally blocks can release their execution environments (Docker
   containers, worktrees). Without cancellation, abandoned futures
   keep capsules alive until the JVM exits."
  [tasks execute-fn context]
  (let [task-futures (doall
                      (map (fn [[task-id task]]
                             [task-id (future (execute-fn task context))])
                           tasks))]
    (try
      (->> task-futures
           (map (fn [[task-id f]] [task-id @f]))
           (into {}))
      (catch Throwable t
        ;; Cancel outstanding futures so their sub-workflow finally blocks
        ;; run and release any acquired execution environments.
        (doseq [[_ f] task-futures]
          (when-not (future-done? f)
            (future-cancel f)))
        (throw t)))))

(defn ^{:stratum 0} notify-batch-start [batch on-task-start]
  (when on-task-start
    (doseq [[task-id _] batch] (on-task-start task-id))))

(defn ^{:stratum 0} notify-batch-complete [results on-task-complete]
  (when on-task-complete
    (doseq [[task-id result] results] (on-task-complete task-id result))))

(defn ^{:stratum 0} partition-results [results]
  (let [ok-results (->> results (filter #(dag/ok? (second %))) (map first))
        err-results (->> results (filter #(not (dag/ok? (second %)))) (map first))]
    {:completed ok-results :failed err-results}))

(defn ^{:stratum 0} emit-completed-checkpoints!
  "Emit task-completed events for checkpointing."
  [completed-task-ids results event-stream workflow-id]
  (doseq [tid completed-task-ids]
    (resilience/emit-dag-task-completed! event-stream workflow-id tid (get results tid))))

(defn ^{:stratum 0} emit-failed-checkpoints!
  "Emit :dag/task-failed diagnostic events for failed results in a batch.
   Mirrors emit-completed-checkpoints! on the failure path so dogfood
   post-mortems can see WHY individual tasks died — :dag-result aggregates
   tasks-failed counts but without per-task events those failures are
   invisible to tooling that scans the event stream."
  [failed-task-ids results event-stream workflow-id]
  (doseq [tid failed-task-ids]
    (resilience/emit-dag-task-failed! event-stream workflow-id tid (get results tid))))

(defn- ^{:stratum 0} register-batch-branches!
  "Register every successfully-completed task's persisted branch in the
   per-workflow branch registry. Runs once per batch, AFTER all futures
   in the batch have joined — keeps mutation off the hot path of any
   running task and guarantees siblings in the same batch never observe
   each other's incomplete state."
  [registry-atom batch-results]
  (when registry-atom
    (doseq [[task-id result] batch-results]
      (when (dag/ok? result)
        (when-let [branch (get-in result [:data :task-branch])]
          ;; :branch is the LOCAL worktree branch (downstream worktree fork
          ;; point). :pr-branch is the PUSHED branch this task's release
          ;; published — the base a dependent task's PR should stack on
          ;; (resolve-pr-base-branch). nil when the task opened no PR.
          (swap! registry-atom dag/register-branch task-id
                 {:branch    branch
                  :pr-branch (get-in result [:data :pr-info :branch])}))))))

;--- Layer 2: Workflow Integration
(defn ^{:stratum 0} warn-potential-monolith
  "Log a warning when a plan may be under-decomposed — single task but
   multiple components or many files."
  [plan logger]
  (let [tasks (:plan/tasks plan [])
        components (->> tasks (keep :task/component) distinct)
        all-files (->> tasks (mapcat :task/exclusive-files) distinct)]
    (when (and (<= (count tasks) 1)
               (or (> (count components) 1)
                   (> (count all-files) 5)))
      (log/info logger :dag-orchestrator :plan/potential-monolith
                {:data {:task-count (count tasks)
                        :component-count (count components)
                        :file-count (count all-files)}}))))

(defn- ^{:stratum 0} task-defs->forest-shape
  "Adapt post-wiring task-defs (`:task/deps` set) to the shape
   `validate-dag-forest` expects (`:task/dependencies` vec).

   In v1 this fed the plan-time gate; in v2 the gate is dropped and
   the result is used only for informational logging (so the operator
   sees `:dag/multi-parent-detected` for fan-in plans, but the
   orchestrator runs them anyway via `merge-parent-branches!`).

   Sorts deps before vectorizing so logged payloads are deterministic
   across runs — sets have no iteration order."
  [task-defs]
  (mapv (fn [t]
          {:task/id (:task/id t)
           ;; sort-by str so heterogeneous task-id types (UUID/keyword/
           ;; string) still produce a total order — `sort` would throw
           ;; on a mixed set.
           :task/dependencies (vec (sort-by str (:task/deps t #{})))})
        task-defs))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} emit-batch-events!
  "Emit checkpointing + diagnostic events for a completed batch.
   Successful results get :dag/task-completed; failed results get
   :dag/task-failed. Both paths emit so dogfood and TUI can see the
   full task disposition without grepping trace logs.
   Returns nil to keep event emission side-effect only."
  [results event-stream workflow-id]
  (let [grouped (group-by (fn [[_task-id result]]
                            (if (dag/ok? result) :completed :failed))
                          results)
        completed-task-ids (map first (:completed grouped))
        failed-task-ids    (map first (:failed grouped))]
    (emit-completed-checkpoints! completed-task-ids results event-stream workflow-id)
    (emit-failed-checkpoints! failed-task-ids results event-stream workflow-id)
    nil))

(defn ^{:stratum 1} execute-dag-loop [tasks-map context logger]
  (let [{:keys [on-task-start on-task-complete]} context
        max-parallel (get context :max-parallel 4)
        event-stream (or (:event-stream context)
                         (get-in context [:execution/opts :event-stream]))
        workflow-id (:workflow-id context)
        pre-completed (get context :pre-completed-ids #{})
        ;; Per-workflow branch registry. Lives on context so all sibling
        ;; futures in `execute-tasks-batch` see the same atom; no global
        ;; state. Empty for brand-new runs; pre-populated during resume
        ;; from the prior run's checkpoint events when that lands.
        registry-atom (atom (dag/create-branch-registry))
        context (assoc context :dag/branch-registry registry-atom)]

    (when (seq pre-completed)
      (log/info logger :dag-orchestrator :dag/resuming
                {:data {:pre-completed-count (count pre-completed)
                        :pre-completed-ids (vec pre-completed)}}))

    (loop [completed-ids pre-completed
           failed-ids #{}
           all-results {}
           sub-workflow-ids []
           current-backend (get context :current-backend)
           iteration 0]
      (let [all-failed (dag-finalize/propagate-failures tasks-map failed-ids)
            ready-tasks (compute-ready-tasks tasks-map completed-ids all-failed)]
        (cond
          ;; No more work — finalize
          (empty? ready-tasks)
          (dag-finalize/finalize-dag tasks-map completed-ids all-failed all-results
                                     sub-workflow-ids iteration logger)

          ;; Safety valve
          (> iteration 100)
          (dag-plan/dag-execution-error (count completed-ids) (count all-failed) "Max iterations exceeded")

          ;; Execute next batch
          :else
          (let [batch (select-non-conflicting-batch ready-tasks max-parallel)
                _ (notify-batch-start batch on-task-start)
                ctx (cond-> context
                      current-backend (assoc :current-backend current-backend))
                results (execute-tasks-batch batch execute-single-task ctx)
                _ (notify-batch-complete results on-task-complete)
                {:keys [completed failed]} (partition-results results)
                batch-sub-ids (map (fn [[tid _]] (keyword (str "dag-task-" tid))) batch)
                {:keys [rate-limited-ids other-failed-ids]} (resilience/analyze-batch-for-rate-limits results)
                new-completed (into completed-ids completed)
                new-failed (into failed-ids other-failed-ids)]

            ;; Register persisted branches BEFORE the rate-limit decision so
            ;; that even on pause the registry reflects what's been written
            ;; — checkpoint/resume can replay it from the event stream
            ;; alongside :dag/task-completed.
            (register-batch-branches! registry-atom results)
            (emit-completed-checkpoints! completed results event-stream workflow-id)
            ;; Emit :dag/task-failed for non-rate-limited failures only.
            ;; Rate-limited tasks will be retried after the cooldown — emitting
            ;; a failure event for them would be premature and would lie about
            ;; the run's actual state.
            (emit-failed-checkpoints! other-failed-ids results event-stream workflow-id)

            (if (seq rate-limited-ids)
              (let [decision (dag-rate-limit/handle-rate-limit-in-batch
                              context rate-limited-ids new-completed new-failed
                              all-results results event-stream workflow-id logger)]
                (if (= :continue (:action decision))
                  (recur new-completed
                         new-failed
                         (merge all-results (select-keys results completed))
                         (into sub-workflow-ids batch-sub-ids)
                         (:new-backend decision)
                         (inc iteration))
                  (:result decision)))
              (recur new-completed
                     (into failed-ids failed)
                     (merge all-results results)
                     (into sub-workflow-ids batch-sub-ids)
                     current-backend
                     (inc iteration)))))))))

(defn- ^{:stratum 1} log-multi-parent-detected!
  "Emit an informational log (NOT an anomaly) when the plan has
   multi-parent tasks. v2 runs them via the merge path; the log
   surfaces plan shape on the dashboard so operators can see fan-in
   patterns without the orchestrator rejecting work."
  [logger plan task-defs]
  (when-let [non-forest (dag/validate-dag-forest (task-defs->forest-shape task-defs))]
    (log/info logger :dag-orchestrator :dag/multi-parent-detected
              {:data {:plan-id (:plan/id plan)
                      :multi-parent-tasks (:multi-parent-tasks non-forest)}})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        _ (warn-potential-monolith plan logger)
        task-defs (dag-plan/plan->dag-tasks plan (assoc context :logger logger))
        _ (log-multi-parent-detected! logger plan task-defs)
        tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))
        pre-completed (get context :pre-completed-ids #{})
        ctx (cond-> context
              (seq pre-completed) (assoc :pre-completed-ids pre-completed))]
    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan)
                      :task-count (count task-defs)
                      :pre-completed (count pre-completed)}})
    (execute-dag-loop tasks-map ctx logger)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} maybe-parallelize-plan [plan context]
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
