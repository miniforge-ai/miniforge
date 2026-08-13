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
(ns ai.miniforge.cli.tui.risk
  "PR risk/complexity heuristics: color and icon tables plus the
   heuristic-based risk analysis. Extracted from `ai.miniforge.cli.tui`
   (rule 210: the combined namespace measured 4 real layers, max 3)."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Risk/complexity scoring
(def ^{:stratum 0} risk-colors
  {:low :green
   :medium :yellow
   :high :red})

(def ^{:stratum 0} risk-icons
  {:low "●"
   :medium "◐"
   :high "◉"})

(defn ^{:stratum 0} analyze-pr-risk
  "Analyze a PR and return risk assessment.

   This is a heuristic-based analysis. In the future, this could
   call an LLM for deeper analysis.

   Returns:
   {:risk :low/:medium/:high
    :complexity :trivial/:simple/:moderate/:complex
    :summary string
    :suggested-action string
    :reasons [string]}"
  [{:keys [title additions deletions changedFiles] :as _pr}]
  (let [;; Size-based heuristics
        total-changes (+ (or additions 0) (or deletions 0))
        file-count (or changedFiles 0)

        ;; Pattern matching on title
        title-lower (str/lower-case (or title ""))
        is-deps? (or (str/includes? title-lower "bump")
                     (str/includes? title-lower "deps")
                     (str/includes? title-lower "dependency"))
        is-docs? (or (str/includes? title-lower "readme")
                     (str/includes? title-lower "docs")
                     (str/includes? title-lower "documentation"))
        is-fix? (str/includes? title-lower "fix")
        is-refactor? (str/includes? title-lower "refactor")
        is-feature? (or (str/includes? title-lower "add")
                        (str/includes? title-lower "feat")
                        (str/includes? title-lower "implement"))

        ;; Calculate risk
        risk (cond
               ;; Low risk patterns
               (and is-docs? (< total-changes 100)) :low
               (and is-deps? (< file-count 3)) :low
               (and (< total-changes 50) (< file-count 3)) :low

               ;; High risk patterns
               (> total-changes 500) :high
               (> file-count 20) :high
               (and is-refactor? (> total-changes 200)) :high

               ;; Medium by default
               :else :medium)

        complexity (cond
                     (< total-changes 20) :trivial
                     (< total-changes 100) :simple
                     (< total-changes 300) :moderate
                     :else :complex)

        ;; Generate summary
        summary (cond
                  is-docs? "Documentation update"
                  is-deps? "Dependency version bump"
                  is-fix? "Bug fix"
                  is-refactor? "Code refactoring"
                  is-feature? "New feature"
                  :else "Code changes")

        suggested-action (case risk
                           :low "✓ Safe to merge"
                           :medium "Review recommended"
                           :high "⚠ Careful review needed")

        reasons (cond-> []
                  (> total-changes 300) (conj (str total-changes " lines changed"))
                  (> file-count 10) (conj (str file-count " files modified"))
                  is-refactor? (conj "Refactoring changes"))]

    {:risk risk
     :complexity complexity
     :summary summary
     :suggested-action suggested-action
     :reasons reasons}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (analyze-pr-risk {:title "Bump dependencies"
                    :additions 10
                    :deletions 5
                    :changedFiles 2})

  (analyze-pr-risk {:title "Refactor authentication system"
                    :additions 500
                    :deletions 300
                    :changedFiles 25})

  :end)
