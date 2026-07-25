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
(ns ai.miniforge.workflow.dag-plan
  "DAG plan analysis and conversion, split out of `dag-orchestrator`
   (rule 210 — a 1810-line namespace with a non-monotonic layer stack,
   miniforge#1317).

   Owns the result-shape constructors shared across the DAG stack
   (workflow-success/-failure, dag-execution-result/-error/-paused),
   level-traversal / parallelism analysis over a plan's task graph, and
   converting a plan's tasks into validated DAG tasks (dependency
   normalization, phantom-dep dropping, stratum auto-wiring)."
  (:require
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0

;--- Layer 0: Result Constructors
(def ^{:stratum 0} zero-metrics
  "Canonical zeroed metrics for DAG / inner-workflow results. Used as
   the default when no per-task metrics flow through. Single source
   of truth — configurable.clj aliases this so the workflow stack
   doesn't drift on what an empty metrics map looks like."
  {:tokens 0 :cost-usd 0.0 :duration-ms 0})

(defn ^{:stratum 0} dag-execution-result [completed failed artifacts metrics-agg & {:keys [unreached] :or {unreached 0}}]
  {:success? (and (zero? failed) (zero? unreached))
   :tasks-completed completed
   :tasks-failed failed
   :artifacts (vec artifacts)
   :metrics {:tokens (:total-tokens metrics-agg 0)
             :cost-usd (:total-cost metrics-agg 0.0)
             :duration-ms (:total-duration metrics-agg 0)}})

(defn ^{:stratum 0} dag-execution-error [completed failed error]
  {:success? false
   :tasks-completed completed
   :tasks-failed failed
   :artifacts []
   :metrics {}
   :error error})

(defn ^{:stratum 0} dag-execution-paused
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
(defn ^{:stratum 0} build-deps-map [tasks]
  (->> tasks
       (map (fn [t] [(:task/id t) (set (:task/dependencies t []))]))
       (into {})))

(defn ^{:stratum 0} traverse-levels [task-ids deps-map]
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

;--- Layer 1: Plan to DAG Conversion
(defn ^{:stratum 0} normalize-task-id
  "Preserve task IDs in their domain-native form.
   UUID strings are parsed to UUIDs so mixed string/UUID inputs still align."
  [x]
  (cond
    (uuid? x) x
    (string? x) (or (parse-uuid x) x)
    (keyword? x) x
    :else x))

(defn ^{:stratum 0} validate-deps
  "Filter deps to only those referencing actual task IDs. Warns on phantoms."
  [task-id raw-deps valid-task-ids logger]
  (let [valid (set (filter valid-task-ids raw-deps))
        invalid (remove valid-task-ids raw-deps)]
    (when (seq invalid)
      (log/warn logger :dag-orchestrator :dag/phantom-deps-dropped
                {:data {:task-id task-id
                        :dropped-deps (vec invalid)}}))
    valid))

(defn ^{:stratum 0} wire-stratum-deps
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

;--- Layer 1: Branch Resolution
(defn ^{:stratum 0} default-spec-branch
  "Branch the orchestrator should treat as the spec's parent — the one root
   tasks acquire off and dep-resolution falls back to.

   Shared by dag-sub-workflow (v1 single-parent base resolution) and
   dag-merge (v2 empty-registry fallback) — lives here, the lowest layer
   in the DAG stack, so neither has to depend on the other for it."
  [context]
  (or (get-in context [:execution/opts :branch])
      (get-in context [:execution/branch])
      "main"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} workflow-success [artifact metrics]
  {:success? true
   :artifact artifact
   :metrics (or metrics zero-metrics)})

(defn ^{:stratum 1} workflow-failure [error metrics]
  {:success? false
   :error error
   :metrics (or metrics zero-metrics)})

(defn ^{:stratum 1} compute-max-level-width [tasks]
  (-> tasks
      ((juxt #(map :task/id %) build-deps-map))
      ((fn [[ids deps]] (traverse-levels ids deps)))
      :max-width))

(defn ^{:stratum 1} estimate-parallel-speedup [plan]
  (let [tasks (:plan/tasks plan [])
        task-count (count tasks)
        deps-map (build-deps-map tasks)
        {:keys [levels max-width]} (traverse-levels (map :task/id tasks) deps-map)]
    {:parallelizable? (> max-width 1)
     :task-count task-count
     :max-parallel max-width
     :levels levels
     :estimated-speedup (if (pos? levels) (float (/ task-count levels)) 1.0)}))

(defn ^{:stratum 1} plan-task->dag-task
  "Convert a single plan task to a DAG task with validated deps."
  [t valid-task-ids plan-id workflow-id context]
  (let [task-id (normalize-task-id (:task/id t))]
    (cond-> {:task/id task-id
             :task/deps (validate-deps task-id
                                       (map normalize-task-id (:task/dependencies t []))
                                       valid-task-ids
                                       (:logger context))
             :task/description (:task/description t)
             :task/type (:task/type t :implement)
             :task/acceptance-criteria (:task/acceptance-criteria t [])
             :task/context (merge {:parent-plan-id plan-id
                                   :parent-workflow-id workflow-id}
                                  (select-keys context [:llm-backend :artifact-store]))}
      (:task/component t)      (assoc :task/component (:task/component t))
      (:task/exclusive-files t) (assoc :task/exclusive-files (:task/exclusive-files t))
      (:task/stratum t)         (assoc :task/stratum (:task/stratum t)))))

;------------------------------------------------------------------------------ Layer 2

;--- Layer 0: Plan Analysis
(defn ^{:stratum 2} parallelizable-plan? [plan]
  (let [tasks (:plan/tasks plan [])]
    (when (> (count tasks) 1)
      (> (compute-max-level-width tasks) 1))))

(defn ^{:stratum 2} plan->dag-tasks [plan context]
  (let [tasks (:plan/tasks plan [])
        valid-task-ids (set (map (comp normalize-task-id :task/id) tasks))
        logger (or (:logger context) (log/create-logger {:min-level :info}))
        ctx (assoc context :logger logger)
        dag-tasks (mapv #(plan-task->dag-task % valid-task-ids (:plan/id plan) (:workflow-id context) ctx)
                        tasks)]
    (wire-stratum-deps dag-tasks)))
