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
(ns ai.miniforge.policy-pack.external
  "External PR evaluation workflow for read-only policy checking.

   Parses PR diffs into artifacts, selects applicable packs, and runs
   evaluation in read-only mode (no repair phase).

   Diff parsing lives in `ai.miniforge.policy-pack.external.diff`; pack
   applicability / glob matching lives in
   `ai.miniforge.policy-pack.external.matching` (rule 210: the combined
   namespace measured 5 real layers, max 3). This namespace keeps pack
   selection and the top-level evaluation entry point.

   Layer 0: select-applicable-packs (over matching/pack-applies?)
   Layer 1: evaluate-external-pr (over diff/parse-pr-diff + select-applicable-packs)"
  (:require
   [ai.miniforge.policy-pack.core :as core]
   [ai.miniforge.policy-pack.external.diff :as diff]
   [ai.miniforge.policy-pack.external.matching :as matching]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} select-applicable-packs
  "Select packs applicable to a PR based on metadata.

   Arguments:
   - packs - Vector of PackManifest maps
   - pr-meta - Map with :repo, :labels, :base-branch, :changed-files

   Returns: Vector of applicable packs."
  [packs pr-meta]
  (let [changed-files (get pr-meta :changed-files [])]
    (filterv (partial matching/pack-applies? changed-files) packs)))

;------------------------------------------------------------------------------ Layer 1

;; Evaluation and reporting
(defn ^{:stratum 1} evaluate-external-pr
  "Evaluate an external PR against policy packs in read-only mode.

   Arguments:
   - packs - Vector of PackManifest maps (or single pack)
   - pr-data - Map with:
     - :diff - Raw unified diff string
     - :artifacts - Pre-parsed artifacts (alternative to :diff)
     - :repo - Repository identifier
     - :pr-number - PR number
     - :labels - Vector of label strings
     - :changed-files - Vector of changed file paths

   Returns:
   {:evaluation/passed? bool
    :evaluation/violations [{:rule-id kw :severity kw :message str :path str}]
    :evaluation/summary {:critical int :high int :medium int :low int :info int :total int}
    :evaluation/artifacts-checked int
    :evaluation/packs-applied [string]}"
  [packs pr-data]
  (let [packs-vec (if (vector? packs) packs [packs])
        applicable (select-applicable-packs packs-vec pr-data)
        artifacts (or (:artifacts pr-data)
                      (diff/parse-pr-diff (:diff pr-data))
                      [])
        context {:read-only? true
                 :phase :review
                 :pr-meta (select-keys pr-data [:repo :pr-number :labels])}
        ;; Check each artifact against applicable packs
        results (mapv (fn [artifact]
                        (core/check-artifact applicable artifact context))
                      artifacts)
        ;; Aggregate violations
        all-violations (mapcat :violations results)
        severity-counts (merge {:critical 0 :high 0 :medium 0 :low 0 :info 0}
                               (frequencies (map :severity all-violations)))
        total (count all-violations)
        has-blocking? (some (comp seq :blocking) results)]
    {:evaluation/passed? (not has-blocking?)
     :evaluation/violations (vec all-violations)
     :evaluation/summary (assoc severity-counts :total total)
     :evaluation/artifacts-checked (count artifacts)
     :evaluation/packs-applied (mapv :pack/id applicable)}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Parse a diff
  (diff/parse-pr-diff
   "diff --git a/main.tf b/main.tf
--- a/main.tf
+++ b/main.tf
@@ -1,3 +1,4 @@
 resource \"aws_vpc\" \"main\" {
+  enable_dns_hostnames = true
   cidr_block = var.vpc_cidr
 }
diff --git a/variables.tf b/variables.tf
--- a/variables.tf
+++ b/variables.tf
@@ -1,2 +1,3 @@
+variable \"new_var\" {}
 variable \"vpc_cidr\" {}")

  :leave-this-here)
