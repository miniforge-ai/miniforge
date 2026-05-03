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

   Each DAG task receives a full sub-workflow pipeline (explore → plan → implement
   → verify → ...) rather than just an implementer agent. The sub-workflow is
   derived from the parent workflow config, with the plan phase skipped (the plan
   already exists) and DAG execution disabled (to prevent infinite recursion)."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [clojure.set :as set]))

;--- Layer 0: Result Constructors

(defn workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn dag-execution-result [completed failed artifacts metrics-agg & {:keys [unreached] :or {unreached 0}}]
  {:success? (and (zero? failed) (zero? unreached))
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :metrics {:tokens (:total-tokens metrics-agg 0)
             :cost-usd (:total-cost metrics-agg 0.0)
             :duration-ms (:total-duration metrics-agg 0)}})

(defn dag-execution-error [completed failed error]
  {:success? false
   :tasks-completed completed
   :tasks-failed failed
   :artifacts []
   :metrics {}
   :error error})

(defn dag-execution-paused
  [completed-task-ids failed-task-ids artifacts decision]
  (let [reset-at (:reset-at decision)
        wait-ms (:wait-ms decision)
        auto-resume? (= :checkpoint-and-resume (:action decision))]
    {:success? false
     :paused? true
     :tasks-completed (count completed-task-ids)
     :tasks-failed (count failed-task-ids)
     :completed-task-ids (vec completed-task-ids)
     :artifacts (vec artifacts)
     :pause-reason (:reason decision)
     :reset-at reset-at
     :wait-ms wait-ms
     :auto-resume? auto-resume?
     :metrics {}}))

;--- Layer 0: Level Traversal

(defn build-deps-map [tasks]
  (->> tasks
       (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]))
       (into {})))

(defn traverse-levels [task-ids deps-map]
  (loop [remaining (set task-ids)
         completed #{}
         level-count 0
         max-width 0]
    (if (empty? remaining)
      {:levels level-count :max-width max-width}
      (let [ready (->> remaining
                       (filter #(every? completed (get deps-map % #{}))))
            width (count ready)]
        (recur (apply disj remaining ready)
               (into completed ready)
               (inc level-count)
               (max max-width width))))))

(defn compute-max-level-width [tasks]
  (-> tasks
      ((juxt #(map :task/id %) build-deps-map))
      ((fn [[ids deps]] (traverse-levels ids deps)))
      :max-width))

;--- Layer 0: Plan Analysis

(defn parallelizable-plan? [plan]
  (let [tasks (:plan/tasks plan [])]
    (when (> (count tasks) 1)
      (> (compute-max-level-width tasks) 1))))

(defn estimate-parallel-speedup [plan]
  (let [tasks (:plan/tasks plan [])
        task-count (count tasks)
        deps-map (build-deps-map tasks)
        {:keys [levels max-width]} (traverse-levels (map :task/id tasks) deps-map)]
    {:parallelizable? (> max-width 1)
     :task-count task-count
     :max-parallel max-width
     :levels levels
     :estimated-speedup (if (pos? levels) (float (/ task-count levels)) 1.0)}))

;--- Layer 1: Plan to DAG Conversion

(defn normalize-task-id
  "Preserve task IDs in their domain-native form.
   UUID strings are parsed to UUIDs so mixed string/UUID inputs still align."
  [x]
  (cond
    (uuid? x) x
    (string? x) (or (parse-uuid x) x)
    (keyword? x) x
    :else x))

(defn validate-deps
  "Filter deps to only those referencing actual task IDs. Warns on phantoms."
  [task-id raw-deps valid-task-ids]
  (let [valid (set (filter valid-task-ids raw-deps))
        invalid (remove valid-task-ids raw-deps)]
    (when (seq invalid)
      (println "WARN: Task" task-id
               "has dependencies on non-existent tasks:"
               (vec invalid) "— dropping them"))
    valid))

(defn plan-task->dag-task
  "Convert a single plan task to a DAG task with validated deps."
  [t valid-task-ids plan-id workflow-id context]
  (let [task-id (normalize-task-id (:task/id t))]
    (cond-> {:task/id task-id
             :task/deps (validate-deps task-id
                                       (map normalize-task-id (:task/dependencies t []))
                                       valid-task-ids)
             :task/description (:task/description t)
             :task/type (:task/type t :implement)
             :task/acceptance-criteria (:task/acceptance-criteria t [])
             :task/context (merge {:parent-plan-id plan-id
                                   :parent-workflow-id workflow-id}
                                  (select-keys context [:llm-backend :artifact-store]))}
      (:task/component t)      (assoc :task/component (:task/component t))
      (:task/exclusive-files t) (assoc :task/exclusive-files (:task/exclusive-files t))
      (:task/stratum t)         (assoc :task/stratum (:task/stratum t)))))

(defn wire-stratum-deps
  "Auto-wire dependencies from :task/stratum when explicit deps are absent.
   All tasks at stratum N depend on all tasks at stratum N-1.
   No-op when no tasks have :task/stratum set."
  [dag-tasks]
  (if-not (some :task/stratum dag-tasks)
    dag-tasks
    (let [by-stratum (group-by #(:task/stratum % 0) dag-tasks)]
      (mapv (fn [task]
              (let [s (:task/stratum task 0)]
                (if (and (empty? (:task/deps task #{}))
                         (pos? s))
                  (let [prev-ids (set (map :task/id (get by-stratum (dec s) [])))]
                    (assoc task :task/deps prev-ids))
                  task)))
            dag-tasks))))

(defn plan->dag-tasks [plan context]
  (let [tasks (:plan/tasks plan [])
        valid-task-ids (set (map (comp normalize-task-id :task/id) tasks))
        dag-tasks (mapv #(plan-task->dag-task % valid-task-ids (:plan/id plan) (:workflow-id context) context)
                        tasks)]
    (wire-stratum-deps dag-tasks)))

;--- Layer 1: Sub-Workflow Construction

(defn task-sub-workflow
  "Build a sub-workflow config for a single DAG task.

   Derives pipeline from the parent workflow, removing explore/plan phases
   (the plan already exists — we're executing it). Keeps :release so each
   DAG task produces its own PR. Strips :observe (parent handles monitoring)."
  [task-def context]
  (let [parent-workflow (:execution/workflow context)
        parent-pipeline (get parent-workflow :workflow/pipeline [])
        sub-phases (->> parent-pipeline
                        (remove #(#{:explore :plan :observe} (:phase %)))
                        vec)
        sub-pipeline (if (seq sub-phases)
                       sub-phases
                       [{:phase :implement} {:phase :release} {:phase :done}])]
    {:workflow/id (keyword (str "dag-task-" (:task/id task-def)))
     :workflow/version "2.0.0"
     :workflow/name (str "DAG sub-task: " (subs (str (:task/description task-def "task"))
                                                0 (min 60 (count (str (:task/description task-def "task"))))))
     :workflow/pipeline sub-pipeline}))

(defn task-sub-input
  "Build input map for a DAG task's sub-workflow.

   The task description becomes the spec description, and the task itself
   is passed as the plan (single-task plan) so the implement phase can
   pick it up directly. Includes task title and acceptance criteria for
   use by the release phase (PR title/body)."
  [task-def]
  (cond-> {:title (:task/description task-def "Implement task")
           :description (:task/description task-def "Implement task")
           :task/type (:task/type task-def :implement)
           :task/acceptance-criteria (:task/acceptance-criteria task-def [])
           :task/id (:task/id task-def)
           ;; Provide the task as a single-task plan so the implement phase
           ;; receives it without needing another plan phase
           :plan/tasks [{:task/id (random-uuid)
                         :task/description (:task/description task-def "Implement task")
                         :task/type (:task/type task-def :implement)}]}
    (:task/exclusive-files task-def)
    (assoc :files-in-scope (:task/exclusive-files task-def))
    (:task/component task-def)
    (assoc :task/component (:task/component task-def))))

(defn- default-spec-branch
  "Branch the orchestrator should treat as the spec's parent — the one root
   tasks acquire off and dep-resolution falls back to."
  [context]
  (or (get-in context [:execution/opts :branch])
      (get-in context [:execution/branch])
      "main"))

(defn- resolve-task-base-branch
  "Look up the branch task-def's scratch worktree should be forked from.
   Returns either a branch name string (the resolved base) or an anomaly
   map (multi-parent / non-forest).

   When `:dag/branch-registry` is absent on context (test scaffolding
   that didn't bother building one), behaves exactly as if an empty
   registry were on context — root tasks resolve to the spec branch,
   single-dep tasks fall back to the spec branch (defensive: scheduler
   ordering should prevent this in production). The previous
   'no-registry → omit :branch' path was deliberately removed: it
   reproduced the pre-chaining bug (every task forks off the same
   base), and there's no production caller that hits it — `execute-dag-loop`
   always installs a registry."
  [context task-def]
  (let [registry (some-> (get context :dag/branch-registry) deref)
        deps (vec (or (:task/deps task-def) []))
        default (default-spec-branch context)]
    (dag/resolve-base-branch (or registry (dag/create-branch-registry))
                             deps default)))

(defn task-sub-opts
  "Build execution opts for a DAG task's sub-workflow.

   Carries forward LLM backend and event stream from parent context.
   Disables DAG execution to prevent recursion and skips lifecycle events
   (parent workflow owns those).

   When `task-def` is supplied, resolves its dependency to a persisted
   branch via the registry on context and passes it as `:branch` so the
   sub-workflow's `acquire-environment!` forks off that branch instead
   of the spec branch. `:branch` is ALWAYS set when task-def is given:
   - Single-dep registered → dep's branch (chaining payoff).
   - Single-dep unregistered or zero deps → the spec branch.
   - Multi-dep → the registry returns an anomaly; we omit `:branch` here
     because there's no usable string. In practice the orchestrator
     short-circuits multi-parent plans at validation time
     (`execute-plan-as-dag`), so this path is unreachable in production.

   The single-arity form `(task-sub-opts context)` is for callers that
   don't yet thread task-def (kept for compatibility with the
   workflow-runner adapter). It does NOT pass `:branch`.

   IMPORTANT: Does NOT pass the parent's executor, environment-id, or
   worktree-path. Each sub-workflow acquires its own isolated environment
   via run-pipeline's acquire-execution-environment!. This prevents:
   - Concurrent sub-workflows from writing to the same directory
   - Stale/broken files from previous runs polluting the release commit
   - Pre-commit hooks picking up unrelated changes from sibling tasks"
  ([context]
   (task-sub-opts context nil))
  ([context task-def]
   (let [base-branch (when task-def (resolve-task-base-branch context task-def))
         resolved-branch (when (and (string? base-branch)
                                    (not (dag/resolve-base-branch-error? base-branch)))
                           base-branch)]
     (cond-> {:disable-dag-execution true
              :skip-lifecycle-events true
              :quiet (boolean (get-in context [:execution/opts :quiet]))
              :create-pr? true}
       (:llm-backend context)      (assoc :llm-backend (:llm-backend context))
       (:event-stream context)     (assoc :event-stream (:event-stream context))
       (get-in context [:execution/opts :event-stream])
       (assoc :event-stream (get-in context [:execution/opts :event-stream]))
       resolved-branch (assoc :branch resolved-branch)))))

;--- Layer 1: Mini-Workflow Execution

(defn extract-sub-workflow-error
  "Extract the most informative error message from a failed sub-workflow result.
   Digs into phase results to find rate limit messages and other details
   that may not appear in the top-level execution errors.
   Prioritizes rate-limit messages so the DAG orchestrator can detect them."
  [result]
  (let [execution-error (-> result :execution/errors first :message)
        ;; Scan all phase error messages for rate limit indicators
        phase-errors (->> (vals (:execution/phase-results result))
                          (keep #(get-in % [:error :message]))
                          (filter not-empty))
        rate-limit-msg (first (filter #(re-find #"(?i)rate.?limit|429|hit your limit|quota.?exceeded"
                                                (str %))
                                      (cons execution-error phase-errors)))]
    ;; Prioritize rate limit messages so DAG can detect and pause
    (or rate-limit-msg
        execution-error
        (first phase-errors)
        "Sub-workflow failed")))

(defn extract-pr-info-from-result
  "Extract PR info from a sub-workflow result if the release phase produced one."
  [result task-def]
  (let [release-result (get-in result [:execution/phase-results :release])
        pr-info (or (get-in release-result [:result :output :workflow/pr-info])
                    ;; Also check metrics path where leave-release stores it
                    (get-in result [:metrics :release :pr-info]))]
    (when pr-info
      (merge pr-info
             {:task-id (:task/id task-def)
              :deps (set (map normalize-task-id (:task/dependencies task-def [])))}))))

(defn- task-result-branch
  "Branch name produced by the sub-workflow's persist step for this task.

   In governed mode the runner sets `:execution/task-branch` directly. In
   local mode the worktree executor's `environment-id` IS the branch name
   created by `git worktree add -b <env-id>`. Either is fine; we pick
   whichever is present, falling back to nil so callers know nothing
   persisted."
  [result]
  (or (:execution/task-branch result)
      (:execution/environment-id result)))

(defn run-mini-workflow
  "Execute a full sub-workflow pipeline for a single DAG task.

   Each task gets its own workflow (implement → verify → review → done)
   derived from the parent workflow config. The plan phase is skipped
   because the task description IS the plan.

   Threads `task-def` to `task-sub-opts` so per-task base chaining can
   resolve the right base branch from the workflow's branch registry —
   downstream tasks acquire off the prior task's persisted branch instead
   of the spec branch. Extracts the task's resulting branch from the
   sub-workflow result and includes it in the success envelope so
   `execute-dag-loop` can register it for downstream consumers.

   Extracts PR info from the release phase result and includes it in the
   workflow result."
  [task-def context]
  (let [sub-workflow (task-sub-workflow task-def context)
        sub-input    (task-sub-input task-def)
        sub-opts     (task-sub-opts context task-def)
        run-pipeline (:execution/run-pipeline-fn context)
        result       (run-pipeline sub-workflow sub-input sub-opts)
        artifacts    (:execution/artifacts result)
        metrics      (:execution/metrics result)
        pr-info      (extract-pr-info-from-result result task-def)
        task-branch  (task-result-branch result)]
    (if (phase/succeeded? result)
      (cond-> (workflow-success (first artifacts) metrics)
        pr-info (assoc :pr-info pr-info)
        ;; Carry sub-workflow's worktree path so apply-dag-success can
        ;; merge changes back into the parent worktree for release.
        (:execution/worktree-path result)
        (assoc :worktree-path (:execution/worktree-path result))
        ;; Carry the persisted branch name so the orchestrator can register
        ;; it for downstream tasks' base resolution.
        task-branch (assoc :task-branch task-branch))
      (workflow-failure (extract-sub-workflow-error result) metrics))))

(defn workflow-result->dag-result [task-id description wf-result]
  (if (:success? wf-result)
    (dag/ok (cond-> {:task-id task-id
                     :description description
                     :status :implemented
                     :artifacts [(:artifact wf-result)]
                     :metrics (:metrics wf-result)}
              (:pr-info wf-result)    (assoc :pr-info (:pr-info wf-result))
              (:task-branch wf-result) (assoc :task-branch (:task-branch wf-result))))
    (dag/err :task-execution-failed
             (:error wf-result)
             {:task-id task-id :metrics (:metrics wf-result)})))

(defn placeholder-result [task-id description]
  (dag/ok {:task-id task-id
           :description description
           :status :implemented
           :artifacts []
           :metrics {:tokens 0 :cost-usd 0.0}}))

(defn execute-single-task [task-def context]
  (let [task-id (:task/id task-def)
        description (:task/description task-def "Implement task")]
    (try
      (if (:llm-backend context)
        (workflow-result->dag-result task-id description (run-mini-workflow task-def context))
        (placeholder-result task-id description))
      (catch InterruptedException ie
        ;; Restore interrupt flag so the cancelled future terminates cleanly
        ;; and the DAG batch cleanup can proceed.
        (.interrupt (Thread/currentThread))
        (dag/err :task-cancelled
                 (str "Task cancelled: " (.getMessage ie))
                 {:task-id task-id}))
      (catch Exception e
        (dag/err :task-execution-failed
                 (str "Task failed: " (.getMessage e))
                 {:task-id task-id})))))

(defn create-task-executor-fn [context opts]
  (let [{:keys [on-task-start on-task-complete]} opts]
    (fn [task-id dag-context]
      (when on-task-start (on-task-start task-id))
      (let [task-def (get-in dag-context [:run-state :run/tasks task-id])
            result (execute-single-task task-def context)]
        (when on-task-complete (on-task-complete task-id result))
        result))))

;--- Layer 2: Synchronous DAG Execution

(defn compute-ready-tasks [tasks-map completed-ids failed-ids]
  (->> tasks-map
       (filter (fn [[task-id task]]
                 (and (not (contains? completed-ids task-id))
                      (not (contains? failed-ids task-id))
                      (every? #(contains? completed-ids %) (:task/deps task #{})))))))

(defn select-non-conflicting-batch
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

(defn execute-tasks-batch
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

(defn notify-batch-start [batch on-task-start]
  (when on-task-start
    (doseq [[task-id _] batch] (on-task-start task-id))))

(defn notify-batch-complete [results on-task-complete]
  (when on-task-complete
    (doseq [[task-id result] results] (on-task-complete task-id result))))

(defn partition-results [results]
  (let [ok-results (->> results (filter #(dag/ok? (second %))) (map first))
        err-results (->> results (filter #(not (dag/ok? (second %)))) (map first))]
    {:completed ok-results :failed err-results}))

(defn aggregate-results [all-results]
  (let [results (vals all-results)
        artifacts (->> results (mapcat #(get-in % [:data :artifacts] [])))
        pr-infos (->> results (keep #(get-in % [:data :pr-info])) vec)
        worktree-paths (->> results (keep #(get-in % [:data :worktree-path])) vec)
        total-tokens (->> results (map #(get-in % [:data :metrics :tokens] 0)) (reduce + 0))
        total-cost (->> results (map #(get-in % [:data :metrics :cost-usd] 0.0)) (reduce + 0.0))
        total-duration (->> results (map #(get-in % [:data :metrics :duration-ms] 0)) (reduce + 0))]
    (cond-> {:artifacts artifacts :total-tokens total-tokens :total-cost total-cost :total-duration total-duration}
      (seq pr-infos) (assoc :pr-infos pr-infos)
      (seq worktree-paths) (assoc :worktree-paths worktree-paths))))

(defn has-failed-dependency?
  "Check if a task depends on any task in the failed set."
  [task failed-ids]
  (some failed-ids (get task :task/deps #{})))

(defn propagate-failures
  "Mark tasks whose deps include any failed task as transitively failed."
  [tasks-map failed-ids]
  (loop [propagated failed-ids]
    (let [newly-failed (->> tasks-map
                            (remove (fn [[tid _]] (contains? propagated tid)))
                            (filter (fn [[_tid task]] (has-failed-dependency? task propagated)))
                            (map first)
                            set)]
      (if (empty? newly-failed)
        propagated
        (recur (into propagated newly-failed))))))

(defn- make-resume-hint-event
  "Create a :dag/resume-hint event map for the event stream."
  [workflow-id reset-at auto-resume? completed-ids wait-ms]
  {:event/type :dag/resume-hint
   :event/timestamp (str (java.time.Instant/now))
   :workflow/id workflow-id
   :dag/reset-at (str reset-at)
   :dag/auto-resume? auto-resume?
   :dag/completed-task-ids (vec completed-ids)
   :dag/wait-ms wait-ms})

(defn- emit-resume-hint!
  "Emit a :dag/resume-hint event if we know when the rate limit clears."
  [event-stream workflow-id reset-at auto-resume? completed-ids wait-ms]
  (when (and event-stream reset-at)
    (try
      (let [publish! (requiring-resolve 'ai.miniforge.event-stream.interface/publish!)]
        (publish! event-stream
                  (make-resume-hint-event workflow-id reset-at auto-resume?
                                          completed-ids wait-ms)))
      (catch Exception _ nil))))

(defn- checkpoint-log-data
  "Build the log data map for a checkpoint event."
  [reset-at wait-ms completed-count message]
  {:data {:reset-at (str reset-at)
          :wait-minutes (long (/ wait-ms 60000))
          :completed-tasks completed-count
          :message message}})

(defn- log-checkpoint-info
  "Log actionable checkpoint info for the user."
  [logger auto-resume? reset-at wait-ms completed-count]
  (when logger
    (if auto-resume?
      (log/info logger :dag-orchestrator :dag/checkpoint-for-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Workflow checkpointed. Resume with: miniforge run --resume <workflow-id>"))
      (log/info logger :dag-orchestrator :dag/checkpoint-for-manual-resume
                (checkpoint-log-data reset-at wait-ms completed-count
                                     "Rate limit too far out. Resume manually when ready.")))))

(defn handle-rate-limit-in-batch
  "Handle rate-limited tasks in a batch. Returns either:
   - {:action :continue ...} to re-queue tasks (short wait or backend switch)
   - {:action :pause :result <paused-map>} to checkpoint and stop execution

   For medium waits (30min-2hrs), emits a :dag/paused event with :reset-at
   so external schedulers can auto-resume. For long waits (>2hrs), emits
   the same event but flags it as requiring manual resume."
  [context rate-limited-ids new-completed failed-ids all-results batch-results
   event-stream workflow-id logger]
  (let [decision (resilience/handle-rate-limited-batch
                  context rate-limited-ids new-completed logger batch-results)]
    (if (= :continue (:action decision))
      decision
      (let [{:keys [artifacts]} (aggregate-results all-results)
            reset-at (:reset-at decision)
            auto-resume? (= :checkpoint-and-resume (:action decision))]
        ;; Emit enriched pause event with reset time for scheduler
        (resilience/emit-dag-paused! event-stream workflow-id new-completed
                                     (:reason decision))
        (emit-resume-hint! event-stream workflow-id reset-at auto-resume?
                          new-completed (:wait-ms decision))
        (log-checkpoint-info logger auto-resume? reset-at
                             (:wait-ms decision 0) (count new-completed))
        {:action :pause
         :result (dag-execution-paused new-completed failed-ids
                                       artifacts decision)}))))

(defn emit-completed-checkpoints!
  "Emit task-completed events for checkpointing."
  [completed-task-ids results event-stream workflow-id]
  (doseq [tid completed-task-ids]
    (resilience/emit-dag-task-completed! event-stream workflow-id tid (get results tid))))

(defn emit-batch-events!
  "Emit checkpointing events for successful results in a completed batch.
   Returns nil to keep event emission side-effect only."
  [results event-stream workflow-id]
  (let [completed-task-ids (->> results
                                (filter (fn [[_task-id result]] (dag/ok? result)))
                                (map first))]
    (emit-completed-checkpoints! completed-task-ids results event-stream workflow-id)
    nil))

(defn find-unreached-tasks
  "Identify tasks that are neither completed nor failed — stuck due to unmet deps."
  [tasks-map completed-ids all-failed]
  (->> (keys tasks-map)
       (remove #(or (contains? completed-ids %) (contains? all-failed %)))
       (map (fn [tid]
              {:task-id tid
               :unmet-deps (vec (remove completed-ids
                                        (get-in tasks-map [tid :task/deps] #{})))}))))

(defn log-unreached-tasks! [logger tasks-map completed-ids all-failed]
  (let [unreached (find-unreached-tasks tasks-map completed-ids all-failed)]
    (when (seq unreached)
      (log/info logger :dag-orchestrator :dag/unreached-tasks
                {:data {:unreached-count (count unreached)
                        :stuck-deps unreached}}))))

(defn finalize-dag
  "Build the terminal result when no more tasks are ready."
  [tasks-map completed-ids all-failed all-results sub-workflow-ids iteration logger]
  (log-unreached-tasks! logger tasks-map completed-ids all-failed)
  (let [metrics-agg (aggregate-results all-results)
        unreached (- (count tasks-map) (count completed-ids) (count all-failed))]
    (log/info logger :dag-orchestrator :dag/completed
              {:data {:completed (count completed-ids)
                      :failed (count all-failed)
                      :unreached unreached
                      :iterations iteration}})
    (cond-> (assoc (dag-execution-result (count completed-ids) (count all-failed) (:artifacts metrics-agg) metrics-agg
                                         :unreached unreached)
                   :tasks-unreached unreached
                   :sub-workflow-ids (vec sub-workflow-ids))
      (:pr-infos metrics-agg) (assoc :pr-infos (:pr-infos metrics-agg))
      (:worktree-paths metrics-agg) (assoc :worktree-paths (:worktree-paths metrics-agg)))))

(defn- register-batch-branches!
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
          (swap! registry-atom dag/register-branch task-id {:branch branch}))))))

(defn execute-dag-loop [tasks-map context logger]
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
      (let [all-failed (propagate-failures tasks-map failed-ids)
            ready-tasks (compute-ready-tasks tasks-map completed-ids all-failed)]
        (cond
          ;; No more work — finalize
          (empty? ready-tasks)
          (finalize-dag tasks-map completed-ids all-failed all-results
                        sub-workflow-ids iteration logger)

          ;; Safety valve
          (> iteration 100)
          (dag-execution-error (count completed-ids) (count all-failed) "Max iterations exceeded")

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

            (if (seq rate-limited-ids)
              (let [decision (handle-rate-limit-in-batch
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

(defn warn-potential-monolith
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

(defn- task-defs->forest-shape
  "Adapt post-wiring task-defs (`:task/deps` set) to the shape
   `validate-dag-forest` expects (`:task/dependencies` vec).

   Validation runs AFTER stratum wiring on purpose: stratum auto-wiring
   can introduce multi-parent edges that the raw plan doesn't show, and
   we want the orchestrator to reject those at plan-validation time too.

   Sorts deps before vectorizing so anomaly payloads (`:dependencies`
   in `:multi-parent-tasks`) are deterministic across runs — sets have
   no iteration order, and `vec` of a set would otherwise produce
   different log/error output run-to-run for the same plan."
  [task-defs]
  (mapv (fn [t]
          {:task/id (:task/id t)
           ;; sort-by str so heterogeneous task-id types (UUID/keyword/
           ;; string) still produce a total order — `sort` would throw
           ;; on a mixed set.
           :task/dependencies (vec (sort-by str (:task/deps t #{})))})
        task-defs))

(defn execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        _ (warn-potential-monolith plan logger)
        task-defs (plan->dag-tasks plan context)
        forest-anomaly (dag/validate-dag-forest (task-defs->forest-shape task-defs))]
    (if forest-anomaly
      (do
        (log/info logger :dag-orchestrator :dag/non-forest-rejected
                  {:data {:plan-id (:plan/id plan)
                          :violations (:multi-parent-tasks forest-anomaly)}})
        (dag-execution-error 0 (count task-defs) forest-anomaly))
      (let [tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))
            pre-completed (get context :pre-completed-ids #{})
            ctx (cond-> context
                  (seq pre-completed) (assoc :pre-completed-ids pre-completed))]
        (log/info logger :dag-orchestrator :dag/starting
                  {:data {:plan-id (:plan/id plan)
                          :task-count (count task-defs)
                          :pre-completed (count pre-completed)}})
        (execute-dag-loop tasks-map ctx logger)))))

;--- Layer 3: Workflow Integration

(defn maybe-parallelize-plan [plan context]
  (let [estimate (estimate-parallel-speedup plan)]
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
