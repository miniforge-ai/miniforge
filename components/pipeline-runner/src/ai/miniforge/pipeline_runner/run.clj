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
(ns ai.miniforge.pipeline-runner.run
  "Pipeline execution: build the run and its DAG, then loop scheduling steps
   until every stage is terminal."
  (:require [ai.miniforge.pipeline.interface :as pipeline]
            [ai.miniforge.pipeline-runner.core :as core]
            [ai.miniforge.pipeline-runner.messages :as msg]
            [ai.miniforge.pipeline-runner.preflight :as preflight]
            [ai.miniforge.pipeline-runner.scheduler
             :refer [skip-remaining-tasks! step-pipeline]]
            [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.schema.interface :as schema])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} stage->task-def
  "Convert a pipeline stage to a DAG task definition."
  [{:stage/keys [id dependencies]}]
  {:task/id id :task/deps (set dependencies)})

(defn- ^{:stratum 0} run-pipeline-dag
  "Loop over the DAG until all stages reach a terminal state.
   Returns {:stage-runs [...] :cursors {...} :failed? bool}.

   Guards against DAG deadlock: when the run is not yet terminal but no task
   is ready to execute — which happens when (first (dag/ready-tasks @run-atom))
   returns nil and the silent failed transitions leave state unchanged — the
   loop would spin forever. Detecting an empty ready-set before calling
   step-pipeline lets us skip remaining pending tasks and surface the deadlock
   as a normal pipeline failure."
  [run-atom stage-map connectors context]
  (loop [stage-outputs {}
         stage-runs    []
         cursors       {}
         sm            stage-map]
    (cond
      (dag/all-terminal? @run-atom)
      {:stage-runs stage-runs
       :cursors    cursors
       :failed?    (boolean (seq (:run/failed @run-atom)))}

      ;; DAG deadlock: non-terminal run with no ready tasks.
      ;; Skip any remaining pending tasks and return as a failed run so the
      ;; caller receives a deterministic failure instead of an infinite loop.
      (empty? (dag/ready-tasks @run-atom))
      (do (skip-remaining-tasks! run-atom)
          {:stage-runs stage-runs
           :cursors    cursors
           :failed?    true})

      :else
      (let [[outputs' runs' cursors' sm']
            (step-pipeline run-atom sm connectors context stage-outputs stage-runs cursors)]
        (recur outputs' runs' cursors' sm')))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-preflighted-pipeline
  "Execute a pipeline that passed validation and preflight."
  [pipeline-def connectors context]
  (let [{:pipeline/keys [id version stages mode]} pipeline-def
        run-result   (core/create-pipeline-run
                      {:pipeline-run/pipeline-id id
                       :pipeline-run/version version
                       :pipeline-run/mode (or mode :full-refresh)})
        pipeline-run (:pipeline-run run-result)
        stage-map    (into {} (map (juxt :stage/id identity)) stages)
        task-defs    (map stage->task-def stages)
        run-atom     (dag/create-run-atom
                       (dag/create-dag-from-tasks (random-uuid) task-defs))
        {:keys [stage-runs cursors failed?]}
        (run-pipeline-dag run-atom stage-map connectors context)
        final-run    (assoc pipeline-run
                            :pipeline-run/status (if failed? :failed :completed)
                            :pipeline-run/stage-runs stage-runs
                            :pipeline-run/connector-cursors cursors
                            :pipeline-run/started-at (:pipeline-run/created-at pipeline-run)
                            :pipeline-run/completed-at (Instant/now))]
    (if failed?
      (schema/failure :pipeline-run
                      (msg/t :run/stage-failed
                             {:name (:stage/name (first (filter #(= :failed (:status %)) stage-runs)))})
                      {:pipeline-run final-run})
      (schema/success :pipeline-run final-run))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-pipeline
  "Execute a pipeline using dag-executor for dependency scheduling.
   Runs stages in topological order. Each stage result feeds into dependent stages.
   Stops on first failure — skips all remaining pending stages.

   Before any stage runs, preflight checks that every connector, transform,
   quality rule and spawn function the pipeline names resolves. A preflight
   failure returns the schema/failure from `preflight/check`, carrying
   :preflight/problems; no connector is opened.

   context may include:
     :transforms  — map of keyword → (fn [records config] -> records)
     :spawn-fns   — map of keyword → (fn [records config] -> [spawn-request ...])"
  [pipeline-def connectors context]
  (let [validation (pipeline/validate-pipeline pipeline-def)
        checked    (when (:success? validation)
                     (preflight/check pipeline-def connectors context))]
    (cond
      (not (:success? validation)) (schema/failure-with-errors :pipeline-run (:errors validation))
      (schema/failed? checked)     checked
      :else                        (run-preflighted-pipeline pipeline-def connectors context))))

(comment
  (stage->task-def {:stage/id (random-uuid) :stage/dependencies []}))
