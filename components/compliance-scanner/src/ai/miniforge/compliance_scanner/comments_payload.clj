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
(ns ai.miniforge.compliance-scanner.comments-payload
  "Severity inference + payload/body-rendering primitives, split out of
   `comments` (rule 210: a fourth real layer there is the signal to split
   it). Every def here is independent of the others — no same-file
   reference edges — so they all sit at a single real stratum.

   Layer 0: Severity inference, rationale text, EDN payload rendering,
            and payload round-trip parsing"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Severity inference
(defn ^{:stratum 0} infer-severity
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
(defn ^{:stratum 0} rationale-text
  "Return a violation rationale when it is a string; otherwise return empty text."
  [violation]
  (let [rationale (get violation :rationale)]
    (if (string? rationale) rationale "")))

;; Body rendering
(defn ^{:stratum 0} pr-edn
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
