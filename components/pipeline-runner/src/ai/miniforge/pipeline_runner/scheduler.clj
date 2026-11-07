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
(ns ai.miniforge.pipeline-runner.scheduler
  "One scheduling step: run the next ready stage with its collected inputs,
   inject any spawned children, and record the result in the run atom."
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.pipeline-runner.connector-stage
             :refer [cursor-entry execute-ingest-stage execute-publish-stage]]
            [ai.miniforge.pipeline-runner.processing-stage
             :refer [execute-transform-stage execute-validate-stage]]
            [ai.miniforge.pipeline-runner.spawn
             :refer [generate-spawns inject-spawned-stages!]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} skip-remaining-tasks!
  "Skip all still-pending tasks. Called when any stage fails to halt execution."
  [run-atom]
  (doseq [[task-id task] (:run/tasks @run-atom)
          :when (= :pending (:task/status task))]
    (dag/transition-task! run-atom task-id :skipped nil)))

(defn- ^{:stratum 0} collect-inputs
  "Collect input datasets and flattened records produced by upstream stages."
  [stage-def stage-map stage-outputs]
  (let [dep->dataset (fn [dep-id]
                       (when-let [recs (get stage-outputs dep-id)]
                         [(get-in stage-map [dep-id :stage/name]) recs]))
        datasets    (into {} (keep dep->dataset) (:stage/dependencies stage-def))]
    {:datasets datasets
     :records  (vec (mapcat val datasets))}))

(defn- ^{:stratum 0} run-stage
  "Dispatch to the appropriate stage executor based on :stage/family."
  [stage-def connectors context input-datasets input-records]
  (case (:stage/family stage-def)
    :ingest   (execute-ingest-stage stage-def connectors context)
    :publish  (execute-publish-stage stage-def connectors
                                     (assoc context :publish/datasets input-datasets)
                                     input-records)
    :validate (execute-validate-stage stage-def context input-records)
    (execute-transform-stage stage-def context input-records)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} apply-stage-result!
  "Update run-atom based on stage result. Skips remaining tasks on failure."
  [run-atom stage-id result]
  (if (= :failed (:status result))
    (do
      (dag/mark-failed! run-atom stage-id {:reason (:error-message result)} nil)
      (skip-remaining-tasks! run-atom))
    (dag/mark-completed! run-atom stage-id nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} step-pipeline
  "Execute the next ready stage. Returns [stage-outputs stage-runs cursors stage-map].
   If the stage defines a :stage/spawn-fn, spawned child tasks are injected into
   the run-atom and stage-map before the parent is marked complete."
  [run-atom stage-map connectors context stage-outputs stage-runs cursors]
  (let [stage-id  (first (dag/ready-tasks @run-atom))
        stage-def (get stage-map stage-id)
        _         (dag/transition-task! run-atom stage-id :ready nil)
        _         (dag/transition-task! run-atom stage-id :running nil)
        {:keys [datasets records]} (collect-inputs stage-def stage-map stage-outputs)
        result    (run-stage stage-def connectors context datasets records)

        ;; Inject spawned child stages before marking parent terminal so they
        ;; depend on parent-id and become ready once parent completes.
        spawns         (when (= :completed (:status result))
                         (generate-spawns stage-def context (:records result)))
        new-stage-map  (if (seq spawns)
                         (inject-spawned-stages! run-atom stage-map spawns stage-def)
                         stage-map)
        _              (apply-stage-result! run-atom stage-id result)]
    [(if (:records result) (assoc stage-outputs stage-id (:records result)) stage-outputs)
     (conj stage-runs (dissoc result :records :cursor))
     (if (:cursor result)
       (assoc cursors stage-id (cursor-entry stage-def (:schema-name result) (:cursor result)))
       cursors)
     new-stage-map]))
