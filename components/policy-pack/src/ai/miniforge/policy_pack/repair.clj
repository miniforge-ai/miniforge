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
  "Repair function registry for policy-pack violations.

   Repair functions can automatically fix certain violation types.
   They are registered by rule-id or violation pattern.

   Layer 0: succeeded?, repair-registry atom, built-in repair fns
     (whitespace-repair, trailing-newline-repair)
   Layer 1: register-repair!, deregister-repair!, get-repair-fn, list-repairs
     (over repair-registry)
   Layer 2: attempt-repair (over get-repair-fn)
   Layer 3: attempt-repairs (over attempt-repair)

   4 real strata — over the rule 210 budget of 3; a genuine namespace split
   (Wave 2), not a labeling problem."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Result predicates
(defn ^{:stratum 0} succeeded?
  "Check if a repair result indicates success."
  [result]
  (boolean (:success? result)))

;; Repair registry
;; Registry of repair functions keyed by rule-id pattern.
(defonce ^{:stratum 0} repair-registry (atom {}))

;; Built-in repair functions
(defn ^{:stratum 0} whitespace-repair
  "Repair function for whitespace violations (trailing whitespace, mixed tabs/spaces)."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (-> content
                  (str/replace #"[ \t]+\n" "\n")
                  (str/replace #"\t" "  "))]
    (if (= content fixed)
      {:success? false :artifact artifact :fix-description "No whitespace issues found"}
      {:success? true
       :artifact (assoc artifact :content fixed)
       :fix-description "Removed trailing whitespace and converted tabs to spaces"})))

(defn ^{:stratum 0} trailing-newline-repair
  "Ensure file ends with exactly one newline."
  [_violation artifact _context]
  (let [content (get artifact :content "")
        fixed (str (clojure.string/trimr content) "\n")]
    {:success? true
     :artifact (assoc artifact :content fixed)
     :fix-description "Ensured file ends with single newline"}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} register-repair!
  "Register a repair function for a rule-id or pattern.

   Arguments:
   - rule-id-or-pattern: String or regex matching rule IDs
   - repair-fn: (fn [violation artifact context] -> {:success? bool :artifact map :fix-description string})

   Returns: rule-id-or-pattern"
  [rule-id-or-pattern repair-fn]
  (swap! repair-registry assoc rule-id-or-pattern repair-fn)
  rule-id-or-pattern)

(defn ^{:stratum 1} deregister-repair!
  "Remove a repair function."
  [rule-id-or-pattern]
  (swap! repair-registry dissoc rule-id-or-pattern)
  nil)

(defn ^{:stratum 1} get-repair-fn
  "Find a repair function for a given rule-id.

   Checks exact match first, then regex patterns.

   Returns: repair-fn or nil"
  [rule-id]
  (or (get @repair-registry rule-id)
      (some (fn [[k v]]
              (when (and (instance? java.util.regex.Pattern k)
                         (re-matches k rule-id))
                v))
            @repair-registry)))

(defn ^{:stratum 1} list-repairs
  "List all registered repair function keys."
  []
  (keys @repair-registry))

;------------------------------------------------------------------------------ Layer 2

;; Repair orchestration
(defn ^{:stratum 2} attempt-repair
  "Attempt to repair a single violation.

   Arguments:
   - violation: Violation map with :violation/rule-id
   - artifact: Artifact map with :content
   - context: Execution context

   Returns: {:success? bool :artifact map :fix-description string?} or nil if no repair available"
  [violation artifact context]
  (when-let [repair-fn (get-repair-fn (:violation/rule-id violation))]
    (try
      (repair-fn violation artifact context)
      (catch Exception e
        {:success? false
         :artifact artifact
         :fix-description (str "Repair failed: " (ex-message e))}))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} attempt-repairs
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
