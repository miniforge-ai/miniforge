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
(ns ai.miniforge.compliance-scanner.comments
  "Render classified Violations into PR review comment payloads per
   N13 §2.3 (Closed-Loop PR Pipeline — Violation Comment Renderer).

   Pure functions only. Consumers (e.g., `connector-github`) take the
   structured comment maps emitted here and post them via the provider's
   review-comment API. Severity inference and payload/body-rendering
   primitives live in `comments-payload` (rule 210: a fourth real layer
   here is the signal to split it).

   Layer 0: single-violation payload + comment-body builders
   Layer 1: single-comment builder
   Layer 2: bulk comment renderer"
  (:require [ai.miniforge.compliance-scanner.comments-payload :as payload]))

;------------------------------------------------------------------------------ Layer 0

;; Re-export: round-trip payload parser lives in `comments-payload`.
(def ^{:stratum 0} extract-payload payload/extract-payload)

(defn ^{:stratum 0} violation->payload
  "Build the :comment/payload map for a single classified Violation.

   Arguments:
   - violation    - classified Violation (must have :auto-fixable?; :rationale is optional)
   - pack-info    - {:pack/id <string> :pack/version <string>}

   Returns the inner payload map shape per N13 §2.3:

     {:violation/rule-id       :keyword
      :violation/severity      :error|:warning|:info
      :violation/auto-fixable? boolean
      :violation/suggested-fix string-or-nil
      :violation/rationale     string
      :violation/pack-id       string
      :violation/pack-version  string}"
  [violation pack-info]
  {:violation/rule-id        (:rule/id violation)
   :violation/severity       (payload/infer-severity violation)
   :violation/auto-fixable?  (boolean (:auto-fixable? violation))
   :violation/suggested-fix  (:suggested violation)
   :violation/rationale      (payload/rationale-text violation)
   :violation/pack-id        (:pack/id pack-info)
   :violation/pack-version   (:pack/version pack-info)})

(defn- ^{:stratum 0} render-body
  "Build the human-readable comment body with the embedded EDN payload
   block."
  [violation payload-map]
  (str "**" (or (:rule/title violation) (name (:rule/id violation))) "**\n"
       "\n"
       (when-let [r (not-empty (:violation/rationale payload-map))]
         (str r "\n\n"))
       (when-let [s (:suggested violation)]
         (str "Suggested fix:\n```\n" s "\n```\n\n"))
       "```edn\n"
       ":comment/payload\n"
       (payload/pr-edn payload-map) "\n"
       "```\n"
       "<sub>Posted by `miniforge-policy-evaluator[bot]` — see N13 §2.3</sub>"))

;------------------------------------------------------------------------------ Layer 1

;; Single-comment builder
(defn ^{:stratum 1} violation->comment
  "Render a single classified Violation to the comment record per
   N13 §2.3.

   Arguments:
   - violation   - classified Violation
   - pack-info   - {:pack/id ... :pack/version ...}

   Returns:

     {:comment/author  \"miniforge-policy-evaluator[bot]\"
      :comment/path    string
      :comment/line    int
      :comment/body    string  ; markdown w/ embedded :comment/payload EDN
      :comment/payload {:violation/...}}"
  [violation pack-info]
  (let [payload-map (violation->payload violation pack-info)
        body        (render-body violation payload-map)]
    {:comment/author  "miniforge-policy-evaluator[bot]"
     :comment/path    (:file violation)
     :comment/line    (:line violation)
     :comment/body    body
     :comment/payload payload-map}))

;------------------------------------------------------------------------------ Layer 2

;; Bulk renderer
(defn ^{:stratum 2} violations->comments
  "Render a vector of classified Violations to a vector of comment
   records.

   Arguments:
   - violations  - vector of classified Violation maps
   - pack-info   - {:pack/id ... :pack/version ...}

   Stable output order: by (file, line, rule-id) ascending."
  [violations pack-info]
  (->> violations
       (mapv #(violation->comment % pack-info))
       (sort-by (juxt :comment/path :comment/line
                      (comp str :violation/rule-id :comment/payload)))
       vec))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Single violation
  (def v {:rule/id        :ai.miniforge.standards/exceptions-as-data
          :rule/category  "code-quality"
          :rule/title     "Exceptions must be data, not exceptions"
          :file           "components/agent/src/ai/miniforge/agent/foo.clj"
          :line           42
          :current        "(throw (ex-info ...))"
          :suggested      "(anomaly/throw-anomaly ...)"
          :auto-fixable?  true
          :rationale      "Throwing exceptions breaks effect-as-data."})

  (violation->comment v {:pack/id "miniforge-standards"
                         :pack/version "1.4.0"})

  ;; Round-trip the payload
  (-> (violation->comment v {:pack/id "miniforge-standards" :pack/version "1.4.0"})
      :comment/body
      extract-payload)

  :leave-this-here)
