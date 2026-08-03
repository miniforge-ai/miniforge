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
(ns ai.miniforge.compliance-scanner.scan
  "Scan phase: load files, run detection, return violations.

   Per-file content-scan primitives, pack-driven detection-config
   conversion, per-rule repo-scan orchestration, and diff/plan/
   exceptions-as-data detection live in `scan-content`, `scan-pack-config`,
   `scan-content-rules`, and `scan-diff-plan` respectively (rule 210: an
   eighth real layer here is the signal to split it).

   Layer 0: Top-level scan-repo entry point — resolves rule configs
            across all detection types and assembles the ScanResult"
  (:require [ai.miniforge.compliance-scanner.exceptions-as-data  :as exc-data]
            [ai.miniforge.compliance-scanner.factory             :as factory]
            [ai.miniforge.compliance-scanner.scan-content-rules  :as scan-rules]
            [ai.miniforge.compliance-scanner.scan-diff-plan      :as diff-plan]
            [ai.miniforge.compliance-scanner.scan-pack-config    :as pack-config]
            [ai.miniforge.policy-pack.interface                  :as policy-pack]
            [ai.miniforge.repo-index.interface                   :as repo-index]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} scan-repo
  "Scan a repo for violations across all configured rules.

   Supports multiple detection types:
   - :content-scan — regex matching against file content
   - :diff-analysis — regex matching against git diff (requires :since)
   - :plan-output — terraform plan resource analysis (requires :terraform-plan)

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir
   - opts           - map with:
       :rules           - selector: :all, :always-apply, keyword, or set of keywords
       :since           - git ref for incremental mode + diff-analysis
       :pack            - pre-compiled PackManifest (optional, bypasses compilation)
       :terraform-plan  - plan output string for :plan-output rules

   Returns ScanResult map."
  [repo-path standards-path opts]
  (let [start-ms   (System/currentTimeMillis)
        index      (repo-index/build-index repo-path)
        since-ref  (get opts :since)
        plan-text  (get opts :terraform-plan)

        ;; Compile the standards pack once and reuse it for both the
        ;; content-scan detection-config path below and the diff/plan
        ;; pack-rule path -- opts may already carry a pre-compiled
        ;; pack (e.g. a caller batching multiple scans), in which case
        ;; that one is reused instead of compiling again.
        compiled-pack (or (get opts :pack)
                          (when-let [r (policy-pack/compile-standards-pack standards-path)]
                            (when (:success? r) (:pack r))))
        opts+pack     (cond-> opts compiled-pack (assoc :pack compiled-pack))

        ;; Resolve all rule configs (content-scan detection configs;
        ;; diff/plan rules are resolved separately below from the same
        ;; compiled pack via pack-rules)
        all-cfgs   (or (scan-rules/get-rule-configs opts+pack standards-path) [])

        ;; Content-scan rules use the detection config format (from pack-rule->detection-config)
        content-cfgs (filterv #(contains? % :detect-mode) all-cfgs)

        ;; Diff/plan rules use pack rule format directly
        pack-rules   (when compiled-pack
                       (pack-config/filter-pack-rules (:pack/rules compiled-pack) opts))
        diff-rules   (filterv #(= :diff-analysis (get-in % [:rule/detection :type])) (or pack-rules []))
        plan-rules   (filterv #(= :plan-output (get-in % [:rule/detection :type])) (or pack-rules []))

        ;; Incremental: get changed files if :since is provided
        changed-files (when since-ref (diff-plan/git-diff-name-only repo-path since-ref))
        diff-content  (when (and since-ref (seq diff-rules))
                        (diff-plan/git-diff repo-path since-ref))

        ;; Run all detection types
        content-violations (vec (mapcat #(scan-rules/scan-and-enrich % index changed-files) content-cfgs))
        diff-violations    (diff-plan/scan-diff-rules diff-rules diff-content)
        plan-violations    (diff-plan/scan-plan-rules plan-rules plan-text)
        exc-violations     (diff-plan/run-exceptions-as-data repo-path changed-files opts)

        all-violations (vec (concat content-violations
                                    (or diff-violations [])
                                    (or plan-violations [])
                                    (or exc-violations [])))
        end-ms     (System/currentTimeMillis)
        file-count (get index :file-count 0)
        rule-ids   (cond-> (into (mapv :rule/id content-cfgs)
                                 (map :rule/id (concat diff-rules plan-rules)))
                     (diff-plan/exceptions-as-data-selected? opts)
                     (conj exc-data/rule-id))]
    (factory/->scan-result
     all-violations
     rule-ids
     file-count
     (- end-ms start-ms))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def result (scan-repo "." ".standards" {:rules :all}))
  (count (:violations result))
  (:files-scanned result)
  (:rules-scanned result)

  :leave-this-here)
