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
(ns ai.miniforge.policy-pack.repair
  "Repair orchestration for policy-pack violations: looks up a registered
   repair function and applies it, sequentially, across a set of violations.

   Registry storage/lookup and the built-in repair implementations were
   split out to `ai.miniforge.policy-pack.repair.registry` and
   `ai.miniforge.policy-pack.repair.builtin` (rule 210: this namespace
   measured 4 real layers, max 3; a genuine namespace split (Wave 2), not a
   labeling problem).

   Layer 0: succeeded?, attempt-repair (over repair.registry/get-repair-fn)
   Layer 1: attempt-repairs (over attempt-repair)"
  (:require [ai.miniforge.policy-pack.repair.registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

;; Result predicates
(defn ^{:stratum 0} succeeded?
  "Check if a repair result indicates success."
  [result]
  (boolean (:success? result)))

;; Repair orchestration
(defn ^{:stratum 0} attempt-repair
  "Attempt to repair a single violation.

   Arguments:
   - violation: Violation map with :violation/rule-id
   - artifact: Artifact map with :content
   - context: Execution context

   Returns: {:success? bool :artifact map :fix-description string?} or nil if no repair available"
  [violation artifact context]
  (when-let [repair-fn (registry/get-repair-fn (:violation/rule-id violation))]
    (try
      (repair-fn violation artifact context)
      (catch Exception e
        {:success? false
         :artifact artifact
         :fix-description (str "Repair failed: " (ex-message e))}))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} attempt-repairs
  "Attempt to repair all violations in sequence.

   Applies repair functions in order. Each successful repair updates the artifact
   for subsequent repairs.

   Arguments:
   - violations: Vector of violation maps
   - artifact: Artifact map
   - context: Execution context

   Returns: {:artifact map :repaired [] :unrepaired [] :repair-count int}"
  [violations artifact context]
  (loop [remaining violations
         current-artifact artifact
         repaired []
         unrepaired []]
    (if (empty? remaining)
      {:artifact current-artifact
       :repaired repaired
       :unrepaired unrepaired
       :repair-count (count repaired)}
      (let [violation (first remaining)
            result (attempt-repair violation current-artifact context)]
        (if (and result (succeeded? result))
          (recur (rest remaining)
                 (:artifact result)
                 (conj repaired {:violation violation
                                 :fix (:fix-description result)})
                 unrepaired)
          (recur (rest remaining)
                 current-artifact
                 repaired
                 (conj unrepaired violation)))))))
