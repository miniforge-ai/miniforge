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
(ns ai.miniforge.compliance-scanner.scan-content-rules
  "Per-rule content-scan orchestration across the repo index, split out
   of `scan` (rule 210: an eighth real layer there is the signal to split
   it). Resolves detection configs (from a pre-compiled pack or by
   compiling standards-path), then runs each rule across the repo index
   (full scan or incremental via a changed-files set) and enriches the
   resulting violations with remediation metadata.

   Layer 0: Per-rule-config file scan + detection-config loading from
            standards-path
   Layer 1: Full/incremental per-rule repo scan + rule-config resolution
   Layer 2: Scan + enrich a single rule config"
  (:require
   [ai.miniforge.compliance-scanner.scan-content      :as scan-content]
   [ai.miniforge.compliance-scanner.scan-pack-config   :as pack-config]
   [ai.miniforge.policy-pack.interface                 :as policy-pack]
   [ai.miniforge.repo-index.interface                  :as repo-index]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} scan-file-record
  "Load and scan a single file record against a rule config.
   Returns vector of raw Violation maps, or [] if the file cannot be read."
  [rule-cfg index file-record]
  (let [path    (:path file-record)
        content (try
                  (slurp (str (:repo-root index) "/" path))
                  (catch Exception _ nil))]
    (if content
      (scan-content/scan-file rule-cfg path content)
      [])))

(defn ^{:stratum 0} load-pack-detection-configs
  "Load compiled pack from standards-path and convert rules to detection configs.
   Returns vector of detection config maps, or nil if pack unavailable."
  [standards-path opts]
  (try
    (let [result (policy-pack/compile-standards-pack standards-path)]
      (when (:success? result)
        (let [rules     (get-in result [:pack :pack/rules])
              filtered  (pack-config/filter-pack-rules rules opts)
              configs   (keep pack-config/pack-rule->detection-config filtered)]
          (when (seq configs)
            (vec configs)))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} scan-rule
  "Scan the entire repo for a single rule.
   Returns vector of raw Violation maps."
  [rule-cfg index]
  (let [file-pred      (get rule-cfg :file-pred (constantly false))
        matching-files (repo-index/find-files index #(file-pred (:path %)))]
    (->> matching-files
         (mapcat (partial scan-file-record rule-cfg index))
         vec)))

;; Top-level entry point
(defn- ^{:stratum 1} scan-changed-files
  "Scan only files present in the changed-files set for a single rule config."
  [cfg index changed-files]
  (let [file-pred (get cfg :file-pred (constantly false))
        matching  (->> (repo-index/find-files index identity)
                       (filter (fn [f]
                                 (and (contains? changed-files (:path f))
                                      (file-pred (:path f))))))]
    (mapcat #(scan-file-record cfg index %) matching)))

(defn ^{:stratum 1} get-rule-configs
  "Resolve detection configs from opts :pack or by compiling from standards-path.
   Returns vector of detection config maps, or nil if none found."
  [opts standards-path]
  (or (when-let [pack (get opts :pack)]
        (let [rules    (get pack :pack/rules)
              filtered (pack-config/filter-pack-rules rules opts)]
          (when (seq filtered)
            (vec (keep pack-config/pack-rule->detection-config filtered)))))
      (load-pack-detection-configs standards-path opts)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-and-enrich
  "Scan a single rule config (full or incremental) and enrich violations."
  [cfg index changed-files]
  (let [viols (if changed-files
                (scan-changed-files cfg index changed-files)
                (scan-rule cfg index))
        rem   (get cfg :remediation)]
    (if rem
      (mapv #(pack-config/enrich-violation % rem) viols)
      viols)))
