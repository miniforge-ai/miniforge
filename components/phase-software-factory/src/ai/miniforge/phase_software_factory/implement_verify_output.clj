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
(ns ai.miniforge.phase-software-factory.implement-verify-output
  "Excerpts of the verify phase's test output for the implementer task.

   The parsed failure blocks come first, in clojure.test's own shape, then
   a head/tail cut of the raw output capped at `max-test-output-lines`.
   Split out of `implement` (rule 210 layer budget)."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private max-test-output-lines
  "Cap raw test output sent to implementer to avoid token waste.
   Failure summaries are typically in the first/last 30 lines."
  60)

(defn- ^{:stratum 0} render-failure-block
  "One parsed failure back in clojure.test's own shape, so the implementer
   reads it exactly as a local run would print it."
  [{:keys [kind test location detail]}]
  (str (if (= :fail kind) "FAIL" "ERROR") " in (" test ")"
       (when location (str " (" location ")"))
       (when-not (str/blank? detail) (str "\n" detail))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} truncate-test-output
  "Truncate test output to max-test-output-lines, keeping head and tail."
  [output]
  (when (string? output)
    (let [lines (str/split-lines output)
          n (count lines)]
      (if (<= n max-test-output-lines)
        output
        (let [head-n 30
              tail-n 25
              head (take head-n lines)
              tail (take-last tail-n lines)
              skipped (- n head-n tail-n)]
          (str/join "\n"
            (concat head
                    [(str "\n... [" skipped " lines omitted] ...\n")]
                    tail)))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} failure-focused-output
  "The failure blocks first, verbatim, then the head/tail excerpt. Head and
   tail alone lost every failure of a 6,000-line run (series 8, 2026-09-04):
   the first namespace's clean summary was the head, the runner's epilogue
   the tail, and the three FAIL blocks sat in the omitted middle."
  [failures output]
  (let [excerpt (truncate-test-output output)]
    (if (seq failures)
      (str (str/join "\n\n" (map render-failure-block failures)) "\n\n" excerpt)
      excerpt)))
