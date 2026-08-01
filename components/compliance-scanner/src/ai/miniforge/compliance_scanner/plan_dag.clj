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
(ns ai.miniforge.compliance-scanner.plan-dag
  "DAG-task assembly, split out of `plan` (rule 210: a fifth real layer
   there is the signal to split it). Groups violations by [file rule-id],
   assigns each group an id, and resolves intra-file Dewey-ordering
   dependencies between tasks in the same file.

   Layer 0: Grouping + id/category-lookup helpers
   Layer 1: Rule-id ordering + single-task builder
   Layer 2: DAG task assembly entry point"
  (:require [ai.miniforge.coerce.interface            :as coerce]
            [ai.miniforge.compliance-scanner.factory  :as factory]))

;------------------------------------------------------------------------------ Layer 0

;; DAG topology helpers
(defn- ^{:stratum 0} group-by-file-rule
  "Group violations by [file rule-id].
   Returns map of [file rule-id] -> [violation ...]."
  [violations]
  (group-by (fn [v] [(get v :file) (get v :rule/id)]) violations))

(defn ^{:stratum 0} dewey-order
  "Numeric sort key for a Dewey code string."
  [dewey-str]
  (coerce/safe-parse-int dewey-str 0))

(defn- ^{:stratum 0} key->uuid-entry
  "Return [k (random-uuid)] for use in building a key->id map."
  [k]
  [k (random-uuid)])

(defn- ^{:stratum 0} key->category-entry
  "Return [k category-string] for a [file rule-id] key and its violations."
  [[k vs]]
  [k (get (first vs) :rule/category "0")])

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} file->ordered-rule-ids-entry
  "Return [file ordered-rule-ids] sorted by Dewey category ascending.
   ks is a seq of [file rule-id] keys; the result extracts just the rule-ids."
  [key->cat [file ks]]
  [file (->> ks
             (sort-by #(dewey-order (get key->cat %)))
             (mapv second))])

(defn- ^{:stratum 1} build-task
  "Build a PlanTask for a [file rule-id] group, resolving intra-file deps."
  [key->id key->cat file->rule-ids [[file rule-id] viols]]
  (let [id        (get key->id [file rule-id])
        prior-ids (take-while
                   #(< (dewey-order (get key->cat [file %]))
                       (dewey-order (get key->cat [file rule-id])))
                   (get file->rule-ids file []))
        deps      (into #{} (map #(get key->id [file %]) prior-ids))]
    (factory/->plan-task id deps file rule-id viols)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} build-dag-tasks
  "Build PlanTask records with intra-file ordering deps.

   Within a file, a task for a rule with a higher Dewey category depends on all
   tasks for rules with lower Dewey categories in that same file."
  [violations]
  (let [groups         (group-by-file-rule violations)
        key->id        (into {} (map key->uuid-entry (keys groups)))
        key->cat       (into {} (map key->category-entry groups))
        file->rule-ids (into {} (map (partial file->ordered-rule-ids-entry key->cat)
                                     (group-by first (keys groups))))]
    (mapv (partial build-task key->id key->cat file->rule-ids) groups)))
