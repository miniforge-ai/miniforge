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

   Each DAG task receives a full sub-workflow pipeline (explore → plan → implement
   → verify → ...) rather than just an implementer agent. The sub-workflow is
   derived from the parent workflow config, with the plan phase skipped (the plan
   already exists) and DAG execution disabled (to prevent infinite recursion)."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.phase.registry :as phase-reg]
   [ai.miniforge.workflow.dag-resilience :as resilience]))

;--- Layer 0: Result Constructors

(defn- workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn- workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn- dag-execution-result [completed failed artifacts metrics-agg]
  {:success? (zero? failed)
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :metrics {:tokens (:total-tokens metrics-agg 0)
             :cost-usd (:total-cost metrics-agg 0.0)
             :duration-ms (:total-duration metrics-agg 0)}})

(defn- dag-execution-error [completed failed error]
  {:success? false
   :tasks-completed completed
   :tasks-failed failed
   :artifacts []
   :metrics {}
   :error error})

(defn- dag-execution-paused [completed failed artifacts reason]
  {:success? false
   :paused? true
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :pause-reason reason
   :metrics {}})

;--- Layer 0: Level Traversal

(defn- build-deps-map [tasks]
  (->> tasks
       (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]))
       (into {})))

(defn- traverse-levels [task-ids deps-map]
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

(defn- compute-max-level-width [tasks]
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

(defn- normalize-task-id
  "Normalize a task ID to a UUID. Handles UUIDs, UUID strings, keywords, and
   other values by generating a deterministic UUID from the value's string form."
  [x]
  (cond
    (uuid? x) x
    (string? x) (or (parse-uuid x)
                     (java.util.UUID/nameUUIDFromBytes (.getBytes (str x))))
    (keyword? x) (java.util.UUID/nameUUIDFromBytes (.getBytes (name x)))
    :else (java.util.UUID/nameUUIDFromBytes (.getBytes (pr-str x)))))

(defn- validate-deps
  "Filter deps to only those referencing actual task IDs. Warns on phantoms."
  [task-id raw-deps valid-task-ids]
  (let [valid (set (filter valid-task-ids raw-deps))
        invalid (remove valid-task-ids raw-deps)]
    (when (seq invalid)
      (println "WARN: Task" task-id
               "has dependencies on non-existent tasks:"
               (vec invalid) "— dropping them"))
    valid))

(defn- plan-task->dag-task
  "Convert a single plan task to a DAG task with validated deps."
  [t valid-task-ids plan-id workflow-id context]
  (let [task-id (normalize-task-id (:task/id t))]
  {:task/id task-id
   :task/deps (validate-deps task-id
                             (map normalize-task-id (:task/dependencies t []))
                             valid-task-ids)
   :task/description (:task/description t)
   :task/type (:task/type t :implement)
   :task/acceptance-criteria (:task/acceptance-criteria t [])
   :task/context (merge {:parent-plan-id plan-id
                         :parent-workflow-id workflow-id}
                        (select-keys context [:llm-backend :artifact-store]))}))

(defn plan->dag-tasks [plan context]
  (let [tasks (:plan/tasks plan [])
        valid-task-ids (set (map (comp normalize-task-id :task/id) tasks))]
    (mapv #(plan-task->dag-task % valid-task-ids (:plan/id plan) (:workflow-id context) context)
          tasks)))

;--- Layer 1: Sub-Workflow Construction

(defn- task-sub-workflow
  "Build a sub-workflow config for a single DAG task.

   Derives pipeline from the parent workflow, removing explore/plan phases
   (the plan already exists — we're executing it) and keeping implement
   through done. Disables DAG execution to prevent infinite recursion."
  [task-def context]
  (let [parent-workflow (:execution/workflow context)
        parent-pipeline (or (:workflow/pipeline parent-workflow) [])
        ;; Keep phases after plan (implement, verify, review, release, done)
        ;; Drop explore and plan — the task description IS the plan
        sub-phases (->> parent-pipeline
                        (remove #(#{:explore :plan} (:phase %)))
                        vec)
        ;; If no implement phase found, use a minimal pipeline
        sub-pipeline (if (seq sub-phases)
                       sub-phases
                       [{:phase :implement} {:phase :done}])]
    {:workflow/id (keyword (str "dag-task-" (:task/id task-def)))
     :workflow/version "2.0.0"
     :workflow/name (str "DAG sub-task: " (subs (str (:task/description task-def "task"))
                                                0 (min 60 (count (str (:task/description task-def "task"))))))
     :workflow/pipeline sub-pipeline}))

(defn- task-sub-input
  "Build input map for a DAG task's sub-workflow.

   The task description becomes the spec description, and the task itself
   is passed as the plan (single-task plan) so the implement phase can
   pick it up directly."
  [task-def]
  {:title (:task/description task-def "Implement task")
   :description (:task/description task-def "Implement task")
   :task/type (:task/type task-def :implement)
   :task/acceptance-criteria (:task/acceptance-criteria task-def [])
   ;; Provide the task as a single-task plan so the implement phase
   ;; receives it without needing another plan phase
   :plan/tasks [{:task/id (random-uuid)
                 :task/description (:task/description task-def "Implement task")
                 :task/type (:task/type task-def :implement)}]})

(defn- task-sub-opts
  "Build execution opts for a DAG task's sub-workflow.

   Carries forward LLM backend, event stream, and sandbox/isolation config
   from parent context. Disables DAG execution to prevent recursion and
   skips lifecycle events (parent workflow owns those)."
  [context]
  (cond-> {:disable-dag-execution true
           :skip-lifecycle-events true
           :quiet true}
    (:llm-backend context)      (assoc :llm-backend (:llm-backend context))
    (:event-stream context)     (assoc :event-stream (:event-stream context))
    (get-in context [:execution/opts :event-stream])
    (assoc :event-stream (get-in context [:execution/opts :event-stream]))
    (:executor context)         (assoc :executor (:executor context))
    (:environment-id context)   (assoc :environment-id (:environment-id context))
    (:sandbox-workdir context)  (assoc :sandbox-workdir (:sandbox-workdir context))
    (:worktree-path context)    (assoc :worktree-path (:worktree-path context))))

;--- Layer 1: Mini-Workflow Execution

(defn- run-mini-workflow
  "Execute a full sub-workflow pipeline for a single DAG task.

   Each task gets its own workflow (implement → verify → review → done)
   derived from the parent workflow config. The plan phase is skipped
   because the task description IS the plan."
  [task-def context]
  (let [sub-workflow (task-sub-workflow task-def context)
        sub-input (task-sub-input task-def)
        sub-opts (task-sub-opts context)
        run-pipeline (requiring-resolve 'ai.miniforge.workflow.runner/run-pipeline)
        result (run-pipeline sub-workflow sub-input sub-opts)
        artifacts (:execution/artifacts result)
        metrics (:execution/metrics result)]
    (if (phase-reg/succeeded? result)
      (workflow-success (first artifacts) metrics)
      (workflow-failure (or (-> result :execution/errors first :message)
                            "Sub-workflow failed")
                        metrics))))

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
           :metrics {:tokens 0 :cost-usd 0.0}}))

(defn execute-single-task [task-def context]
  (let [task-id (:task/id task-def)
        description (:task/description task-def "Implement task")]
    (try
      (if (:llm-backend context)
        (workflow-result->dag-result task-id description (run-mini-workflow task-def context))
        (placeholder-result task-id description))
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

(defn- compute-ready-tasks [tasks-map completed-ids failed-ids]
  (->> tasks-map
       (filter (fn [[task-id task]]
                 (and (not (contains? completed-ids task-id))
                      (not (contains? failed-ids task-id))
                      (every? #(contains? completed-ids %) (:task/deps task #{})))))))

(defn- execute-tasks-batch [tasks execute-fn context]
  (->> tasks
       (map (fn [[task-id task]] [task-id (future (execute-fn task context))]))
       doall
       (map (fn [[task-id f]] [task-id @f]))
       (into {})))

(defn- notify-batch-start [batch on-task-start]
  (when on-task-start
    (doseq [[task-id _] batch] (on-task-start task-id))))

(defn- notify-batch-complete [results on-task-complete]
  (when on-task-complete
    (doseq [[task-id result] results] (on-task-complete task-id result))))

(defn- partition-results [results]
  (let [ok-results (->> results (filter #(dag/ok? (second %))) (map first))
        err-results (->> results (filter #(not (dag/ok? (second %)))) (map first))]
    {:completed ok-results :failed err-results}))

(defn- aggregate-results [all-results]
  (let [results (vals all-results)
        artifacts (->> results (mapcat #(get-in % [:data :artifacts] [])))
        total-tokens (->> results (map #(get-in % [:data :metrics :tokens] 0)) (reduce + 0))
        total-cost (->> results (map #(get-in % [:data :metrics :cost-usd] 0.0)) (reduce + 0.0))
        total-duration (->> results (map #(get-in % [:data :metrics :duration-ms] 0)) (reduce + 0))]
    {:artifacts artifacts :total-tokens total-tokens :total-cost total-cost :total-duration total-duration}))

(defn- has-failed-dependency?
  "Check if a task depends on any task in the failed set."
  [task failed-ids]
  (some failed-ids (get task :task/deps #{})))

(defn- propagate-failures
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

(defn- handle-rate-limit-in-batch
  "Handle rate-limited tasks in a batch. Returns either:
   - {:action :continue :new-backend X} to re-queue tasks
   - {:action :pause :result <paused-map>} to stop execution"
  [context rate-limited-ids new-completed failed-ids all-results
   event-stream workflow-id logger]
  (let [decision (resilience/handle-rate-limited-batch
                  context rate-limited-ids new-completed logger)]
    (if (= :continue (:action decision))
      decision
      (let [{:keys [artifacts]} (aggregate-results all-results)]
        (resilience/emit-dag-paused! event-stream workflow-id new-completed (:reason decision))
        {:action :pause
         :result (dag-execution-paused (count new-completed) (count failed-ids)
                                       artifacts (:reason decision))}))))

(defn- emit-completed-checkpoints!
  "Emit task-completed events for checkpointing."
  [completed-task-ids results event-stream workflow-id]
  (doseq [tid completed-task-ids]
    (resilience/emit-dag-task-completed! event-stream workflow-id tid (get results tid))))

(defn- find-unreached-tasks
  "Identify tasks that are neither completed nor failed — stuck due to unmet deps."
  [tasks-map completed-ids all-failed]
  (->> (keys tasks-map)
       (remove #(or (contains? completed-ids %) (contains? all-failed %)))
       (map (fn [tid]
              {:task-id tid
               :unmet-deps (vec (remove completed-ids
                                        (get-in tasks-map [tid :task/deps] #{})))}))))

(defn- log-unreached-tasks! [logger tasks-map completed-ids all-failed]
  (let [unreached (find-unreached-tasks tasks-map completed-ids all-failed)]
    (when (seq unreached)
      (log/info logger :dag-orchestrator :dag/unreached-tasks
                {:data {:unreached-count (count unreached)
                        :stuck-deps unreached}}))))

(defn- finalize-dag
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
    (assoc (dag-execution-result (count completed-ids) (count all-failed) (:artifacts metrics-agg) metrics-agg)
           :tasks-unreached unreached
           :sub-workflow-ids (vec sub-workflow-ids))))

(defn- execute-dag-loop [tasks-map context logger]
  (let [{:keys [on-task-start on-task-complete]} context
        max-parallel (get context :max-parallel 4)
        event-stream (or (:event-stream context)
                         (get-in context [:execution/opts :event-stream]))
        workflow-id (:workflow-id context)]
    (loop [completed-ids (get context :pre-completed-ids #{})
           failed-ids #{}
           all-results {}
           sub-workflow-ids []
           current-backend (get context :current-backend)
           iteration 0]
      (let [all-failed (propagate-failures tasks-map failed-ids)
            ready-tasks (compute-ready-tasks tasks-map completed-ids all-failed)]
        (cond
          (empty? ready-tasks)
          (finalize-dag tasks-map completed-ids all-failed all-results
                        sub-workflow-ids iteration logger)

          (> iteration 100)
          (dag-execution-error (count completed-ids) (count all-failed) "Max iterations exceeded")

          :else
          (let [batch (take max-parallel ready-tasks)
                _ (notify-batch-start batch on-task-start)
                ctx (cond-> context
                      current-backend (assoc :current-backend current-backend))
                results (execute-tasks-batch batch execute-single-task ctx)
                _ (notify-batch-complete results on-task-complete)
                {:keys [completed failed]} (partition-results results)
                batch-sub-ids (map (fn [[tid _]] (keyword (str "dag-task-" tid))) batch)
                {:keys [rate-limited-ids]} (resilience/analyze-batch-for-rate-limits results)
                new-completed (into completed-ids completed)]

            (emit-completed-checkpoints! completed results event-stream workflow-id)

            (if (seq rate-limited-ids)
              (let [decision (handle-rate-limit-in-batch
                              context rate-limited-ids new-completed failed-ids
                              all-results event-stream workflow-id logger)]
                (if (= :continue (:action decision))
                  (recur new-completed
                         failed-ids
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

(defn execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        task-defs (plan->dag-tasks plan context)
        tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))
        pre-completed (get context :pre-completed-ids #{})
        ctx (cond-> context
              (seq pre-completed) (assoc :pre-completed-ids pre-completed))]
    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan)
                      :task-count (count task-defs)
                      :pre-completed (count pre-completed)}})
    (execute-dag-loop tasks-map ctx logger)))

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
