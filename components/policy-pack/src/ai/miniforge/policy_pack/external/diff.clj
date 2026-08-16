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
(ns ai.miniforge.policy-pack.external.diff
  "Unified-diff parsing for external PR evaluation. Split out of
   `ai.miniforge.policy-pack.external` (rule 210: the combined namespace
   measured 5 real layers, max 3) — pack matching/selection and the
   top-level evaluation entry point stay in the parent namespace and its
   `matching` sibling; the diff-to-artifact parsing they run against
   lives here."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Diff parsing
(defn ^{:stratum 0} parse-diff-header
  "Parse a diff header to extract the file path.
   Handles 'diff --git a/path b/path' format."
  [header-line]
  (when-let [[_ path] (re-find #"^diff --git a/.+ b/(.+)$" header-line)]
    path))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} parse-pr-diff
  "Parse a unified diff into a vector of artifact maps.

   Arguments:
   - diff-content - String containing unified diff output

   Returns:
   [{:artifact/path string
     :artifact/content string   ;; the new file content (+ lines)
     :artifact/diff string}]    ;; the raw diff hunk"
  [diff-content]
  (when (and diff-content (not (str/blank? diff-content)))
    (let [lines (str/split-lines diff-content)
          ;; Split on diff headers
          segments (reduce
                    (fn [acc line]
                      (if (str/starts-with? line "diff --git")
                        (conj acc {:header line :lines []})
                        (if (seq acc)
                          (update-in acc [(dec (count acc)) :lines] conj line)
                          acc)))
                    []
                    lines)]
      (vec
       (keep (fn [{:keys [header lines]}]
               (when-let [path (parse-diff-header header)]
                 (let [diff-text (str/join "\n" lines)
                       ;; Extract added lines (content approximation)
                       added-lines (->> lines
                                        (filter #(str/starts-with? % "+"))
                                        (remove #(str/starts-with? % "+++"))
                                        (map #(subs % 1)))]
                   {:artifact/path path
                    :artifact/content (str/join "\n" added-lines)
                    :artifact/diff diff-text})))
             segments)))))
