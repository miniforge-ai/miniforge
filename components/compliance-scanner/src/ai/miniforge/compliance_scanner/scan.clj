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

   Layer 0: Per-file detection helpers
   Layer 1: Per-rule scanning
   Layer 2: Top-level scan-repo entry point"
  (:require [ai.miniforge.compliance-scanner.factory :as factory]
            [ai.miniforge.compliance-scanner.rules   :as rules]
            [ai.miniforge.repo-index.interface       :as repo-index]
            [clojure.string                          :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Per-file detection helpers

(defn- header-present?
  "Return true if the first 10 lines of content contain both
   the name pattern and the email pattern."
  [content name-pattern email-pattern]
  (let [first-10 (->> (str/split-lines content)
                      (take 10)
                      (str/join "\n"))]
    (and (re-find name-pattern first-10)
         (re-find email-pattern first-10))))

(defn- positive-matches
  "Find all lines in content matching pattern.
   Returns seq of {:line int :text string}."
  [pattern content]
  (->> (str/split-lines content)
       (map-indexed (fn [idx line] {:line (inc idx) :text line}))
       (filter (fn [{:keys [text]}] (re-find pattern text)))))

(defn- violations-for-positive-rule
  "Scan a single file with a positive-match rule (pattern = violation).
   Returns vector of Violation maps (without :auto-fixable? / :rationale —
   those are added by classify)."
  [rule-cfg file-path content]
  (let [{:keys [dewey title pattern suggest-fn]} rule-cfg
        matches (positive-matches pattern content)]
    (mapv (fn [{:keys [line text]}]
            (factory/->violation
             dewey
             title
             file-path
             line
             (str/trim text)
             (suggest-fn (str/trim text))
             false          ; classify phase fills this in
             ""))           ; classify phase fills this in
          matches)))

(defn- violations-for-negative-rule
  "Scan a single file with a negative-match rule (absence of pattern = violation).
   Returns a vector of 0 or 1 Violation maps."
  [rule-cfg file-path content]
  (let [{:keys [dewey title pattern email-pattern suggest-fn]} rule-cfg
        ep (get rule-cfg :email-pattern email-pattern)]
    (if (header-present? content pattern ep)
      []
      [(factory/->violation
        dewey
        title
        file-path
        1
        "(missing copyright header)"
        (suggest-fn nil)
        false   ; classify phase fills this in
        "")]))) ; classify phase fills this in

(defn- scan-file
  "Dispatch to the appropriate scanner for a rule config.
   Returns vector of raw (pre-classify) Violation maps."
  [rule-cfg file-path content]
  (case (get rule-cfg :detect-mode :positive)
    :positive (violations-for-positive-rule rule-cfg file-path content)
    :negative (violations-for-negative-rule rule-cfg file-path content)
    []))

;------------------------------------------------------------------------------ Layer 1
;; Per-rule scanning

(defn- scan-rule
  "Scan the entire repo for a single rule.
   Returns vector of raw Violation maps."
  [rule-cfg index]
  (let [file-pred (get rule-cfg :file-pred (constantly false))
        matching-files (repo-index/find-files index (fn [f] (file-pred (:path f))))]
    (->> matching-files
         (mapcat (fn [file-record]
                   (let [path    (:path file-record)
                         content (try
                                   (slurp (str (:repo-root index) "/" path))
                                   (catch Exception _ nil))]
                     (if content
                       (scan-file rule-cfg path content)
                       []))))
         vec)))

;------------------------------------------------------------------------------ Layer 2
;; Top-level entry point

(defn scan-repo
  "Scan a repo for violations across all configured rules.

   Arguments:
   - repo-path      - string path to repo root
   - standards-path - string path to .standards dir (not used in M1)
   - opts           - map with:
       :rules  - set of Dewey strings to run, or :all (default)
       :since  - git ref (optional, ignored in M1)

   Returns ScanResult map."
  [repo-path _standards-path opts]
  (let [start-ms   (System/currentTimeMillis)
        index      (repo-index/build-index repo-path)
        rule-cfgs  (rules/enabled-rule-configs opts)
        all-violations (->> rule-cfgs
                            (mapcat #(scan-rule % index))
                            vec)
        end-ms     (System/currentTimeMillis)
        file-count (:file-count index 0)
        deweys     (mapv :dewey rule-cfgs)]
    (factory/->scan-result
     all-violations
     deweys
     file-count
     (- end-ms start-ms))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def result (scan-repo "." ".standards" {:rules :all}))
  (count (:violations result))
  (:files-scanned result)
  (:rules-scanned result)

  :leave-this-here)
