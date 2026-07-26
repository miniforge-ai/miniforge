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
(ns ai.miniforge.workflow.dag-finalize
  "Terminal-result accounting for a DAG run, split out of
   `dag-orchestrator` (rule 210 — a 1810-line namespace with a
   non-monotonic layer stack, miniforge#1317): rolling up per-task
   metrics/artifacts/PR-info across a run, propagating dependency
   failures transitively, and building the terminal result once no
   more tasks are ready. Kept separate from the scheduling loop itself
   so `execute-dag-loop`'s own in-file call depth doesn't have to
   absorb this chain too."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.workflow.dag-plan :as dag-plan]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} find-unreached-tasks
  "Identify tasks that are neither completed nor failed — stuck due to unmet deps."
  [tasks-map completed-ids all-failed]
  (->> (keys tasks-map)
       (remove #(or (contains? completed-ids %) (contains? all-failed %)))
       (map (fn [tid]
              {:task-id tid
               :unmet-deps (vec (remove completed-ids
                                        (get-in tasks-map [tid :task/deps] #{})))}))))

(defn ^{:stratum 0} has-failed-dependency?
  "Check if a task depends on any task in the failed set."
  [task failed-ids]
  (some failed-ids (get task :task/deps #{})))

(defn- ^{:stratum 0} result-metrics
  "Extract per-task `:metrics` from a dag/ok OR dag/err result.
   `dag/ok` puts the data payload under `:data`; `dag/err` puts the
   caller-supplied data under `:error :data`. The previous rollup
   only inspected `:data`, which silently dropped every failed
   task's tokens / cost / duration at the aggregate. With 8 failed
   tasks in a dogfood run that's a 100% loss of cost telemetry."
  [result]
  (if (dag/ok? result)
    (get-in result [:data :metrics])
    (get-in result [:error :data :metrics])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} log-unreached-tasks! [logger tasks-map completed-ids all-failed]
  (let [unreached (find-unreached-tasks tasks-map completed-ids all-failed)]
    (when (seq unreached)
      (log/info logger :dag-orchestrator :dag/unreached-tasks
                {:data {:unreached-count (count unreached)
                        :stuck-deps unreached}}))))

(defn ^{:stratum 1} propagate-failures
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

(defn ^{:stratum 1} aggregate-results [all-results]
  (let [results (vals all-results)
        artifacts (->> results (mapcat #(get-in % [:data :artifacts] [])))
        pr-infos (->> results (keep #(get-in % [:data :pr-info])) vec)
        worktree-paths (->> results (keep #(get-in % [:data :worktree-path])) vec)
        metrics (map result-metrics results)
        total-tokens   (->> metrics (map #(get % :tokens 0))      (reduce + 0))
        total-cost     (->> metrics (map #(get % :cost-usd 0.0))  (reduce + 0.0))
        total-duration (->> metrics (map #(get % :duration-ms 0)) (reduce + 0))]
    (cond-> {:artifacts artifacts
             :total-tokens total-tokens
             :total-cost total-cost
             :total-duration total-duration}
      (seq pr-infos) (assoc :pr-infos pr-infos)
      (seq worktree-paths) (assoc :worktree-paths worktree-paths))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} finalize-dag
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
    (cond-> (assoc (dag-plan/dag-execution-result (count completed-ids) (count all-failed) (:artifacts metrics-agg) metrics-agg
                                                  :unreached unreached)
                   :tasks-unreached unreached
                   :sub-workflow-ids (vec sub-workflow-ids))
      (:pr-infos metrics-agg) (assoc :pr-infos (:pr-infos metrics-agg))
      (:worktree-paths metrics-agg) (assoc :worktree-paths (:worktree-paths metrics-agg)))))
