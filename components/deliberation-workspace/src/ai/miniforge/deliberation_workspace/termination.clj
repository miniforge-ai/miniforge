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

(defn- ^{:stratum 0} goals [workspace]
  (->> (get workspace :workspace/objects {})
       vals
       (filter #(= :goal (:object/type %)))))

;; Activations held back from ordinary work for the forced-synthesis
;; closure. The reserve sits inside the activation budget (N14 §6.3).
(defn- ^{:stratum 0} closure-reserve
  [workspace]
  (get workspace :workspace/closure-reserve 0))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} goals-terminal? [workspace]
  (let [all (goals workspace)]
    (and (seq all) (every? object/terminal? all))))

(defn- ^{:stratum 1} goal-rejected? [workspace]
  (some #(= :rejected (:object/status %)) (goals workspace)))

(defn ^{:stratum 1} exhausted-budget
  "The budget dimension that ran out, or nil while ordinary work can still
   spend. Ordinary work stops where the closure reserve begins."
  [workspace]
  (let [{:keys [activations cost]} (get workspace :workspace/budget {})
        spent (get workspace :workspace/spent {})]
    (cond
      (and activations (>= (get spent :activations 0)
                           (- activations (closure-reserve workspace)))) :activations
      (and cost (>= (get spent :cost 0) cost)) :cost)))

;; N14 §7: every participant scheduled for ordinary work passed since the
;; last committed contribution, and no structural tier has work waiting.
(defn- ^{:stratum 1} quiescent?
  [workspace]
  (let [population (scheduler/ordinary-participants workspace)]
    (and (seq population)
         (every? (get workspace :workspace/passed #{}) population)
         (nil? (scheduler/triggered-activation workspace)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} closing-rule
  "The §7 rule that fires, or nil while the run continues.

   Order matters: completion is checked before budget so a run that closes
   its goals on its last permitted activation completes rather than
   exhausting. Success requires every goal accepted; terminal goals with a
   rejection complete under a distinct rule (§7.1)."
  [workspace]
  (let [exhausted (exhausted-budget workspace)]
    (cond
      (and (goals-terminal? workspace) (goal-rejected? workspace))
      {:termination/rule :completed-with-rejected-goals}

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
