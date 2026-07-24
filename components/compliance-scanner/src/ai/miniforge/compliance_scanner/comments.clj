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
   review-comment API.

   Layer 0: payload helpers
   Layer 1: comment-record builders
   Layer 2: bulk renderer + severity inference"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Severity inference
(defn- ^{:stratum 0} infer-severity
  "Map a classified Violation to a :violation/severity keyword.

   Heuristic: violations whose `:rule/category` matches
   security/safety/critical → :error. Everything else → :warning,
   regardless of auto-fixability. Callers may override by providing
   `:severity-override` on the violation map."
  [violation]
  (or (:severity-override violation)
      (let [category      (some-> (:rule/category violation) str/lower-case)
            critical-cat? (and category
                               (or (str/includes? category "security")
                                   (str/includes? category "safety")
                                   (str/includes? category "critical")))]
        (if critical-cat? :error :warning))))

;; Payload construction
(defn- ^{:stratum 0} rationale-text
  "Return a violation rationale when it is a string; otherwise return empty text."
  [violation]
  (let [rationale (get violation :rationale)]
    (if (string? rationale) rationale "")))

;; Body rendering
(defn- ^{:stratum 0} pr-edn
  "Pretty-print an EDN value for embedding in a comment body. Stable
   key ordering keeps comment diffs minimal across re-renders."
  [v]
  (if (map? v)
    (let [ordered-keys [:violation/rule-id
                        :violation/severity
                        :violation/auto-fixable?
                        :violation/suggested-fix
                        :violation/rationale
                        :violation/pack-id
                        :violation/pack-version]
          present      (filter #(contains? v %) ordered-keys)
          extras       (sort (remove (set ordered-keys) (keys v)))
          all-keys     (concat present extras)
          lines        (for [k all-keys]
                         (str " " (pr-str k) " " (pr-str (get v k))))]
      (str "{" (str/triml (str/join "\n" lines)) "}"))
    (pr-str v)))

;; Parser (round-trip helper)
(defn ^{:stratum 0} extract-payload
  "Extract a `:comment/payload` map from a comment body produced by
   `render-body`. Returns nil if no payload block is found.

   Used by the Comment Response Agent's bot-comment-table parser
   (per `pr-monitoring-workflow.md` Bot Comment Handling) to recover
   the structured payload from a posted comment."
  [body]
  (when (string? body)
    (let [block-re #"(?s)```edn\s*:comment/payload\s*(\{.*?\})\s*```"]
      (when-let [[_ edn-str] (re-find block-re body)]
        (try
          ;; clojure.edn/read-string is strictly EDN — no reader macros,
          ;; no #=, no eval. Comment bodies are untrusted input once
          ;; posted/retrieved from a PR. {:default identity} swallows
          ;; unknown tagged literals safely.
          (edn/read-string {:default (fn [_tag value] value)} edn-str)
          (catch Exception _ nil))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} violation->payload
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
   :violation/severity       (infer-severity violation)
   :violation/auto-fixable?  (boolean (:auto-fixable? violation))
   :violation/suggested-fix  (:suggested violation)
   :violation/rationale      (rationale-text violation)
   :violation/pack-id        (:pack/id pack-info)
   :violation/pack-version   (:pack/version pack-info)})

(defn- ^{:stratum 1} render-body
  "Build the human-readable comment body with the embedded EDN payload
   block."
  [violation payload]
  (str "**" (or (:rule/title violation) (name (:rule/id violation))) "**\n"
       "\n"
       (when-let [r (not-empty (:violation/rationale payload))]
         (str r "\n\n"))
       (when-let [s (:suggested violation)]
         (str "Suggested fix:\n```\n" s "\n```\n\n"))
       "```edn\n"
       ":comment/payload\n"
       (pr-edn payload) "\n"
       "```\n"
       "<sub>Posted by `miniforge-policy-evaluator[bot]` — see N13 §2.3</sub>"))

;------------------------------------------------------------------------------ Layer 2

;; Single-comment builder
(defn ^{:stratum 2} violation->comment
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
  (let [payload (violation->payload violation pack-info)
        body    (render-body violation payload)]
    {:comment/author  "miniforge-policy-evaluator[bot]"
     :comment/path    (:file violation)
     :comment/line    (:line violation)
     :comment/body    body
     :comment/payload payload}))

;------------------------------------------------------------------------------ Layer 3

;; Bulk renderer
(defn ^{:stratum 3} violations->comments
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
