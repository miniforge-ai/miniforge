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
(ns ai.miniforge.policy-pack.repair.registry
  "Repair function registry: keyed by rule-id or pattern, backed by an atom.
   Split out of `ai.miniforge.policy-pack.repair` (rule 210: the parent
   namespace measured 4 real layers, max 3; registry storage and lookup is
   layer-coherent on its own).

   Layer 0: repair-registry atom
   Layer 1: register-repair!, deregister-repair!, get-repair-fn, list-repairs
     (over repair-registry)")

;------------------------------------------------------------------------------ Layer 0

;; Registry of repair functions keyed by rule-id pattern.
(defonce ^{:stratum 0} repair-registry (atom {}))

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
