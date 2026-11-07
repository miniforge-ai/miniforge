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
(ns ai.miniforge.pipeline-runner.spawn
  "Dynamic spawn support: turn a stage's spawn requests into child stages and
   wire them into the running DAG."
  (:require [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} generate-spawns
  "If the stage config contains :stage/spawn-fn, look up the function in
   context [:spawn-fns] and call it with (records config) to produce spawn requests.
   Returns a seq of spawn-request maps, or nil."
  [stage-def context records]
  (when-let [spawn-key (get-in stage-def [:stage/config :stage/spawn-fn])]
    (when-let [spawn-fn (get-in context [:spawn-fns spawn-key])]
      (seq (spawn-fn records (:stage/config stage-def))))))

(defn- ^{:stratum 0} spawn->stage-def
  "Convert a spawn-request map into a stage-def suitable for the stage-map.
   Assigns a new stage id and wires the parent as a dependency.
   Inherits :stage/connector-ref from parent when the spawn omits it."
  [spawn parent-stage-def]
  (let [parent-id (:stage/id parent-stage-def)]
    {:stage/id            (random-uuid)
     :stage/name          (:spawn/name spawn)
     :stage/family        (or (:spawn/family spawn) :ingest)
     :stage/connector-ref (or (:spawn/connector-ref spawn)
                               (:stage/connector-ref parent-stage-def))
     :stage/config        (:spawn/config spawn)
     :stage/dependencies  [parent-id]
     :stage/input-datasets  []
     :stage/output-datasets (or (:spawn/output-datasets spawn) [])}))

(defn- ^{:stratum 0} depends-on?
  "True when a stage-def's dependencies include the given parent-id."
  [parent-id [_sid sd]]
  (when (some #{parent-id} (:stage/dependencies sd)) _sid))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} downstream-stage-ids
  "Return the set of stage-map keys whose :stage/dependencies include parent-id."
  [stage-map parent-id]
  (into #{} (keep #(depends-on? parent-id %)) stage-map))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} inject-spawned-stages!
  "Add spawned stage tasks to run-atom and return updated stage-map.
   Each spawned task depends on the parent stage; it becomes ready once the
   parent completes. Inherits connector-ref from parent when omitted.

   Also adds each spawned stage as a dependency of any stages that already
   depend on the parent, so those downstream stages wait for and collect
   records from all spawned children."
  [run-atom stage-map spawns parent-stage-def]
  (let [parent-id (:stage/id parent-stage-def)
        ds-ids    (downstream-stage-ids stage-map parent-id)]
    (letfn [(add-spawned-dep [sm' ds-id stage-id]
              (update-in sm' [ds-id :stage/dependencies] conj stage-id))
            (register-spawn [sm spawn]
              (let [stage-def (spawn->stage-def spawn parent-stage-def)
                    stage-id  (:stage/id stage-def)]
                ;; Register the spawned task — depends on parent only.
                (swap! run-atom update :run/tasks assoc stage-id
                       (dag/create-task-state stage-id #{parent-id}))
                ;; Wire spawned stage into each downstream stage's dep-set so
                ;; those stages wait for (and collect records from) all children.
                (doseq [ds-id ds-ids]
                  (swap! run-atom update-in [:run/tasks ds-id :task/deps] conj stage-id))
                ;; Mirror the dep-set update in the stage-map so collect-inputs
                ;; can look up the spawned stage's records via its UUID.
                (reduce #(add-spawned-dep %1 %2 stage-id)
                        (assoc sm stage-id stage-def)
                        ds-ids)))]
      (reduce register-spawn stage-map spawns))))
