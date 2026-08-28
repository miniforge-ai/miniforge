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
(ns ai.miniforge.deliberation-workspace.termination
  "The N14 §7 closing rules: success, budget boundary, quiescence, deadlock."
  (:require
   [ai.miniforge.deliberation-workspace.object :as object]
   [ai.miniforge.deliberation-workspace.scheduler :as scheduler]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-quiescence-rounds
  "Consecutive committed transactions producing no new open objects before
   the run is quiescent (N14 §7)."
  3)

(defn- ^{:stratum 0} goals-terminal? [workspace]
  (let [goals (->> (get workspace :workspace/objects {})
                   vals
                   (filter #(= :goal (:object/type %))))]
    (and (seq goals) (every? object/terminal? goals))))

(defn- ^{:stratum 0} exhausted-budget
  "The budget dimension that ran out, or nil while the run can still spend."
  [workspace]
  (let [{:keys [activations cost]} (get workspace :workspace/budget {})
        spent (get workspace :workspace/spent {})]
    (cond
      (and activations (>= (get spent :activations 0) activations)) :activations
      (and cost (>= (get spent :cost 0) cost)) :cost)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} quiescent? [workspace]
  (>= (get workspace :workspace/quiet-rounds 0)
      (get workspace :workspace/quiescence-rounds default-quiescence-rounds)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} closing-rule
  "The §7 rule that fires, or nil while the run continues.

   Order matters: success is checked before budget so a run that completes
   on its last permitted activation closes as a success, not an exhaustion."
  [workspace]
  (let [exhausted (exhausted-budget workspace)]
    (cond
      (goals-terminal? workspace)
      {:termination/rule :success}

      exhausted
      {:termination/rule :budget-boundary
       :termination/detail exhausted
       :termination/forced-synthesis true}

      (quiescent? workspace)
      {:termination/rule :quiescence}

      (nil? (scheduler/next-activation workspace))
      {:termination/rule :deadlock})))
