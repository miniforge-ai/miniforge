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
(ns ai.miniforge.compliance-scanner.execute-patch
  "File-patching logic, split out of `execute` (rule 210: a fifth real
   layer there is the signal to split it).

   Layer 0: Line/prepend/append violation helpers (pure)
   Layer 1: Whole-file content patching (pure)
   Layer 2: On-disk file patching (I/O)"
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; File patch helpers
(defn- ^{:stratum 0} apply-line-replacement
  "Replace the first occurrence of :current with :suggested on the violation's line.
   lines is a vector of strings (0-indexed). line numbers in violations are 1-indexed."
  [lines violation]
  (let [idx       (dec (get violation :line 1))
        current   (get violation :current "")
        suggested (get violation :suggested)]
    (if (and suggested (< idx (count lines)) (str/includes? (get lines idx "") current))
      (update lines idx str/replace-first current suggested)
      lines)))

(defn- ^{:stratum 0} prepend-violation?
  "Return true if a violation is a prepend-type remediation."
  [violation]
  (or (= :prepend (get violation :remediation-type))
      (= :std/header-copyright (get violation :rule/id))))

(defn- ^{:stratum 0} append-violation?
  "Return true if a violation is an append-type remediation."
  [violation]
  (= :append (get violation :remediation-type)))

(defn- ^{:stratum 0} apply-prepend
  "Prepend content from the violation's remediation template."
  [content violation]
  (let [template (get violation :remediation-template)]
    (if template
      (str template "\n" content)
      content)))

(defn- ^{:stratum 0} apply-append
  "Append content from the violation's remediation template."
  [content violation]
  (let [template (get violation :remediation-template)]
    (if template
      (if (str/ends-with? content "\n")
        (str content template "\n")
        (str content "\n" template "\n"))
      content)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} patch-file-content
  "Apply all violations for one file to its string content. Pure — no I/O.

   Line-replacement violations are applied bottom-up (descending line number) so
   prior edits do not shift subsequent line indices. Prepend violations
   are applied after all line edits (they add content at position 0). Append
   violations are applied last (they add content at end of file)."
  [content violations]
  (let [prepend-viols (filter prepend-violation? violations)
        append-viols  (filter append-violation? violations)
        line-viols    (remove #(or (prepend-violation? %) (append-violation? %)) violations)
        ;; Preserve trailing newline: split with limit -1 keeps trailing empty segment
        lines         (str/split content #"\n" -1)
        patched-lines (reduce apply-line-replacement lines (sort-by :line > line-viols))
        patched       (str/join "\n" patched-lines)
        ;; Apply prepends then appends
        patched       (if (seq prepend-viols)
                        (apply-prepend patched (first prepend-viols))
                        patched)
        patched       (if (seq append-viols)
                        (reduce apply-append patched append-viols)
                        patched)]
    patched))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} patch-file!
  "Apply auto-fixable violations to a file on disk. No-op if content is unchanged.
   Returns the absolute file path."
  [repo-path file violations]
  (let [path    (str repo-path "/" file)
        content (slurp path)
        patched (patch-file-content content violations)]
    (when-not (= content patched)
      (spit path patched))
    path))
