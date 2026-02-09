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
  "Orchestrates parallel task execution via DAG scheduling."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.task.interface :as task]))

;--- Layer 0: Result Constructors

(defn- workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn- workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0})})

(defn- dag-execution-result [completed failed artifacts total-tokens]
  {:success? (zero? failed)
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :metrics {:tokens total-tokens}})

(defn- dag-execution-error [completed failed error]
  {:success? false
   :tasks-completed completed
   :tasks-failed failed
   :artifacts []
   :metrics {}
   :error error})

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

(defn plan->dag-tasks [plan context]
  (->> (:plan/tasks plan [])
       (mapv (fn [t]
               {:task/id (:task/id t)
                :task/deps (set (:task/dependencies t []))
                :task/description (:task/description t)
                :task/type (:task/type t :implement)
                :task/acceptance-criteria (:task/acceptance-criteria t [])
                :task/context (merge {:parent-plan-id (:plan/id plan)
                                      :parent-workflow-id (:workflow-id context)}
                                     (select-keys context [:llm-backend :artifact-store]))}))))

;--- Layer 1: Mini-Workflow Execution

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
      {:artifact (:artifact result)
       :tokens (or (get-in result [:metrics :tokens]) 0)})))

(defn- create-repair-fn [impl-agent context]
  (fn [old-artifact errors _ctx]
    (let [result (agent/repair impl-agent old-artifact errors context)]
      {:success? (:success result)
       :artifact (:repaired result old-artifact)
       :tokens-used (or (get-in result [:metrics :tokens]) 0)})))

(defn- run-mini-workflow [task-def context]
  (let [impl-agent (agent/create-implementer {:llm-backend (:llm-backend context)})
        inner-task (create-inner-task task-def)
        generate-fn (create-generate-fn impl-agent context)
        loop-context (assoc context
                            :max-iterations 3
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

(defn- compute-ready-tasks [tasks-map completed-ids]
  (->> tasks-map
       (filter (fn [[task-id task]]
                 (and (not (contains? completed-ids task-id))
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
  (let [artifacts (->> (vals all-results) (mapcat #(get-in % [:data :artifacts] [])))
        total-tokens (->> (vals all-results) (map #(get-in % [:data :metrics :tokens] 0)) (reduce + 0))]
    {:artifacts artifacts :total-tokens total-tokens}))

(defn- execute-dag-loop [tasks-map context logger]
  (let [{:keys [on-task-start on-task-complete]} context
        max-parallel (or (:max-parallel context) 4)]
    (loop [completed-ids #{}
           failed-ids #{}
           all-results {}
           iteration 0]
      (let [ready-tasks (compute-ready-tasks tasks-map completed-ids)]
        (cond
          (empty? ready-tasks)
          (let [{:keys [artifacts total-tokens]} (aggregate-results all-results)]
            (log/info logger :dag-orchestrator :dag/completed
                      {:data {:completed (count completed-ids)
                              :failed (count failed-ids)
                              :iterations iteration}})
            (dag-execution-result (count completed-ids) (count failed-ids) artifacts total-tokens))

          (> iteration 100)
          (dag-execution-error (count completed-ids) (count failed-ids) "Max iterations exceeded")

          :else
          (let [batch (take max-parallel ready-tasks)
                _ (notify-batch-start batch on-task-start)
                results (execute-tasks-batch batch execute-single-task context)
                _ (notify-batch-complete results on-task-complete)
                {:keys [completed failed]} (partition-results results)]
            (recur (into completed-ids completed)
                   (into failed-ids failed)
                   (merge all-results results)
                   (inc iteration))))))))

(defn execute-plan-as-dag [plan context]
  (let [logger (or (:logger context) (log/create-logger {:min-level :info}))
        task-defs (plan->dag-tasks plan context)
        tasks-map (->> task-defs (map (fn [t] [(:task/id t) t])) (into {}))]
    (log/info logger :dag-orchestrator :dag/starting
              {:data {:plan-id (:plan/id plan) :task-count (count task-defs)}})
    (execute-dag-loop tasks-map context logger)))

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
