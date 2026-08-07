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
(ns ai.miniforge.compliance-scanner.scan-content
  "Per-file content-scan detection, split out of `scan` (rule 210: an
   eighth real layer there is the signal to split it). Regex matching
   against a single file's content — the leaf-level detection primitives
   for :content-scan rules.

   Layer 0: Header/pattern presence + positive-match line finder
   Layer 1: Positive/negative rule scanning for one file
   Layer 2: Per-file scan dispatch by :detect-mode"
  (:require [ai.miniforge.compliance-scanner.factory  :as factory]
            [ai.miniforge.compliance-scanner.messages :as msg]
            [clojure.string                           :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Per-file detection helpers
(defn- ^{:stratum 0} header-present?
  "Return true if the first 10 lines of content contain both
   the name pattern and the email pattern."
  [content name-pattern email-pattern]
  (let [first-10 (->> (str/split-lines content)
                      (take 10)
                      (str/join "\n"))]
    (and (re-find name-pattern first-10)
         (re-find email-pattern first-10))))

(defn- ^{:stratum 0} pattern-present?
  "Return true if pattern appears anywhere in content.
   Generic negative-mode check (not header-specific)."
  [content pattern]
  (boolean (re-find pattern content)))

(defn ^{:stratum 0} positive-matches
  "Find all lines in content matching pattern.
   Returns seq of {:line int :text string :match string}."
  [pattern content]
  (->> (str/split-lines content)
       (map-indexed (fn [idx line]
                      (let [m (re-find pattern line)]
                        {:line  (inc idx)
                         :text  line
                         :match (if (string? m) m (first m))})))
       (filter :match)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} violations-for-positive-rule
  "Scan a single file with a positive-match rule (pattern = violation).
   Returns vector of Violation maps (without :auto-fixable? / :rationale —
   those are added by classify)."
  [rule-cfg file-path content]
  (let [rule-id  (get rule-cfg :rule/id)
        rule-cat (get rule-cfg :rule/category)
        title    (get rule-cfg :title)
        pattern  (get rule-cfg :pattern)
        suggest  (get rule-cfg :suggest-fn)
        matches  (positive-matches pattern content)]
    (mapv (fn [{:keys [line match]}]
            (factory/->violation
             rule-id
             rule-cat
             title
             file-path
             line
             match
             (suggest match)
             false          ; classify phase fills this in
             ""))           ; classify phase fills this in
          matches)))

(defn- ^{:stratum 1} violations-for-negative-rule
  "Scan a single file with a negative-match rule (absence of pattern = violation).
   Returns a vector of 0 or 1 Violation maps."
  [rule-cfg file-path content]
  (let [rule-id  (get rule-cfg :rule/id)
        rule-cat (get rule-cfg :rule/category)
        title    (get rule-cfg :title)
        pattern  (get rule-cfg :pattern)
        suggest  (get rule-cfg :suggest-fn)
        ep       (get rule-cfg :email-pattern)
        present? (if ep
                   (header-present? content pattern ep)
                   (pattern-present? content pattern))
        absence-msg (if ep
                      (msg/t :scan/missing-header)
                      (str "(missing: " title ")"))]
    (if present?
      []
      [(factory/->violation
        rule-id
        rule-cat
        title
        file-path
        1
        absence-msg
        (suggest nil)
        false
        "")])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-file
  "Dispatch to the appropriate scanner for a rule config.
   Returns vector of raw (pre-classify) Violation maps."
  [rule-cfg file-path content]
  (case (get rule-cfg :detect-mode :positive)
    :positive (violations-for-positive-rule rule-cfg file-path content)
    :negative (violations-for-negative-rule rule-cfg file-path content)
    []))
