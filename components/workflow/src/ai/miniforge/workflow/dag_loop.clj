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
(ns ai.miniforge.workflow.dag-loop
  "The DAG batch-scheduling loop, split out of `dag-orchestrator` (rule
   210). Each iteration runs the next batch of ready tasks in parallel,
   checkpoints the results, and handles rate limits, pause and cancel."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-finalize :as dag-finalize]
   [ai.miniforge.workflow.dag-plan :as dag-plan]
   [ai.miniforge.workflow.dag-rate-limit :as dag-rate-limit]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.workflow.messages :as messages]
   [ai.miniforge.workflow.runner-defaults :as runner-defaults]
   [ai.miniforge.workflow.stop-signal :as stop-signal]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

;; Synchronous DAG Execution
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

;; Operator Control (pause / resume / cancel)
(defn- ^{:stratum 0} run-control-state
  "The run's control-state atom: the one `mf workflow pause|resume|cancel`
   flips. `mf run plan.edn` puts it on the context; the workflow runner
   carries it in :execution/opts. nil when the caller supplied none."
  [context]
  (or (:control-state context)
      (get-in context [:execution/opts :control-state])))

(defn- ^{:stratum 0} await-launch-clearance!
  "Block while the run is paused, then answer whether it is stopped.
   :stopped is re-read on every poll, so a cancel issued during a pause
   ends the wait instead of waiting for a resume that may never come.
   nil when there is no control state."
  [control-state logger]
  (when control-state
    (loop [logged? false]
      (let [{:keys [paused stopped]} @control-state]
        (cond
          stopped true
          paused  (do (when-not logged?
                        (log/info logger :dag-orchestrator :dag/paused
                                  {:message (messages/t :status/paused)}))
                      ;; A cancel ends the wait; the loop then returns the cancelled result.
                      (stop-signal/sleep! control-state (runner-defaults/pause-poll-interval-ms))
                      (recur true))
          :else   false)))))

(defn- ^{:stratum 0} with-stop-aware-wait
  "`(f)` with the rate-limit wait (`resilience/*sleep!*`) ending on a stop.
   A wait a caller already bound (a test's fake clock) is left as it is."
  [control-state f]
  (if (thread-bound? #'resilience/*sleep!*)
    (f)
    (binding [resilience/*sleep!* (partial stop-signal/sleep! control-state)]
      (f))))

(defn- ^{:stratum 0} cancelled-dag-result
  "Terminal result for a run the operator stopped between batches. Built by
   `finalize-dag`, so completed work, failures, spend and opened PRs are
   reported as usual and every task not yet launched counts in
   :tasks-unreached. :error is the same :anomalies.dashboard/stop anomaly
   the runner raises when it sees :stopped at a phase boundary."
  [tasks-map completed-ids all-failed all-results sub-workflow-ids iteration logger]
  (let [result (dag-finalize/finalize-dag tasks-map completed-ids all-failed all-results
                                          sub-workflow-ids iteration logger)]
    (log/warn logger :dag-orchestrator :dag/cancelled
              {:message (messages/t :status/stopped-dashboard)
               :data {:reason :dashboard-stop
                      :tasks-unreached (:tasks-unreached result)}})
    (assoc result
           :success? false
           :cancelled? true
           :error (anomaly/sub-anomaly :conflict
                                       :anomalies.dashboard/stop
                                       (messages/t :status/stopped-control)
                                       {:reason :dashboard-stop}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute-dag-loop
  "Run `tasks-map` batch by batch until it finishes, is cancelled, pauses
   for a rate limit, or hits the iteration cap. `execute-fn` runs one task:
   the caller passes `#'dag-orchestrator/execute-single-task`, so tests
   that `with-redefs` that var still intercept every task launch."
  [tasks-map context logger execute-fn]
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
        ;; Installed on context under one key so every task's sub-workflow
        ;; shares it (`task-sub-opts`) and honors pause/cancel at its own
        ;; phase boundaries.
        control-state (run-control-state context)
        context (cond-> (assoc context :dag/branch-registry registry-atom)
                  control-state (assoc :control-state control-state))]

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
            ready-tasks (compute-ready-tasks tasks-map completed-ids all-failed)
            ;; Operator control. A pause holds the next batch, so it is
            ;; waited out only when a batch is ready to launch. A cancel
            ;; ends the run whenever it leaves tasks undone, including when
            ;; an in-flight sub-workflow stopped on it and its failure took
            ;; every remaining task down with it (nothing left ready).
            stopped? (if (seq ready-tasks)
                       (await-launch-clearance! control-state logger)
                       (some-> control-state deref :stopped))
            cut-short? (and stopped? (not-every? completed-ids (keys tasks-map)))]
        (cond
          ;; Cancelled — launch nothing more; remaining tasks are unreached
          cut-short?
          (cancelled-dag-result tasks-map completed-ids all-failed all-results
                                sub-workflow-ids iteration logger)

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
                results (execute-tasks-batch batch execute-fn ctx)
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

            (cond
              ;; Cancelled while this batch ran: rate-limit recovery would
              ;; only delay the cancel (a cooldown sleep) or mask it (a
              ;; :paused? checkpoint that invites auto-resume). Leave the
              ;; rate-limited tasks undone, keep their spend, and let the
              ;; next iteration return the cancelled result.
              (and (seq rate-limited-ids) (some-> control-state deref :stopped))
              (recur new-completed
                     new-failed
                     (merge all-results results)
                     (into sub-workflow-ids batch-sub-ids)
                     current-backend
                     (inc iteration))

              (seq rate-limited-ids)
              (let [decision (with-stop-aware-wait
                               control-state
                               #(dag-rate-limit/handle-rate-limit-in-batch
                                 context rate-limited-ids new-completed new-failed
                                 all-results results event-stream workflow-id logger))]
                (cond
                  ;; A cancel during the wait or the decision wins over the decision.
                  (stop-signal/stopped? control-state)
                  (recur new-completed
                         new-failed
                         (merge all-results results)
                         (into sub-workflow-ids batch-sub-ids)
                         current-backend
                         (inc iteration))

                  (= :continue (:action decision))
                  (recur new-completed
                         new-failed
                         (merge all-results (select-keys results completed))
                         (into sub-workflow-ids batch-sub-ids)
                         (:new-backend decision)
                         (inc iteration))

                  :else
                  (:result decision)))

              :else
              (recur new-completed
                     (into failed-ids failed)
                     (merge all-results results)
                     (into sub-workflow-ids batch-sub-ids)
                     current-backend
                     (inc iteration)))))))))
