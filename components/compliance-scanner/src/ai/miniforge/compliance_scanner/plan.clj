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

(ns ai.miniforge.compliance-scanner.plan
  "Plan phase: generate DAG task defs and markdown work spec from violations.

   Layer 0: DAG topology helpers
   Layer 1: Markdown work-spec builder
   Layer 2: Top-level plan entry point"
  (:require [ai.miniforge.compliance-scanner.factory :as factory]
            [clojure.string                          :as str]))

;------------------------------------------------------------------------------ Layer 0
;; DAG topology helpers

(defn- group-by-file-rule
  "Group violations by [file rule-id].
   Returns map of [file rule-id] -> [violation ...]."
  [violations]
  (group-by (fn [v] [(get v :file) (get v :rule/id)]) violations))

(defn- dewey-order
  "Numeric sort key for a Dewey code string."
  [dewey-str]
  (try (Integer/parseInt dewey-str) (catch Exception _ 0)))

(defn- build-dag-tasks
  "Build PlanTask records with intra-file ordering deps.

   Within a file, a task for a rule with a higher Dewey category depends on all
   tasks for rules with lower Dewey categories in that same file."
  [violations]
  (let [groups    (group-by-file-rule violations)
        ;; Build a map: [file rule-id] -> fresh UUID
        key->id   (into {} (map (fn [k] [k (random-uuid)]) (keys groups)))
        ;; Dewey category (for ordering) for each [file rule-id] key
        key->cat  (into {} (map (fn [[k vs]]
                                  [k (get (first vs) :rule/category "0")])
                                groups))
        ;; Sort rule-ids per file by their Dewey category
        file->rule-ids (->> (keys groups)
                            (group-by first)
                            (into {} (map (fn [[file ks]]
                                           [file (->> ks
                                                      (sort-by #(dewey-order (get key->cat %)))
                                                      (mapv second))]))))]
    (mapv (fn [[[file rule-id] viols]]
            (let [id        (get key->id [file rule-id])
                  prior-ids (take-while
                             #(< (dewey-order (get key->cat [file %]))
                                 (dewey-order (get key->cat [file rule-id])))
                             (get file->rule-ids file []))
                  deps      (into #{} (map #(get key->id [file %]) prior-ids))]
              (factory/->plan-task id deps file rule-id viols)))
          groups)))

;------------------------------------------------------------------------------ Layer 1
;; Markdown work-spec builder

(defn- violation->md-row
  "Render a single violation as a markdown list item."
  [v]
  (let [fixable (if (get v :auto-fixable?) "auto-fix" "review")]
    (str "- **L" (get v :line) "** `" (get v :current) "`"
         (when-let [s (get v :suggested)]
           (str " → `" s "`"))
         " [" fixable "]")))

(defn- section-for-rule
  "Render a markdown section for all violations of one rule."
  [_rule-id viols]
  (let [rule-title (get (first viols) :rule/title (str _rule-id))
        rule-cat   (get (first viols) :rule/category "?")
        auto  (filter :auto-fixable? viols)
        needs (remove :auto-fixable? viols)]
    (str/join "\n"
      (concat
       [(str "### Dewey " rule-cat " — " rule-title)
        ""]
       (when (seq auto)
         (concat
          ["**Auto-fixable:**" ""]
          (map violation->md-row auto)
          [""]))
       (when (seq needs)
         (concat
          ["**Needs review:**" ""]
          (map violation->md-row needs)
          [""])
         )))))

(defn- build-work-spec
  "Build the full markdown work spec string from classified violations."
  [violations summary]
  (let [by-rule      (group-by :rule/id violations)
        review-viols (remove :auto-fixable? violations)]
    (str/join "\n"
      ["# Compliance Remediation Plan"
       ""
       "## Executive Summary"
       ""
       "| Metric | Count |"
       "|--------|-------|"
       (str "| Total violations     | " (get summary :total-violations) " |")
       (str "| Auto-fixable         | " (get summary :auto-fixable)     " |")
       (str "| Needs review         | " (get summary :needs-review)      " |")
       (str "| Files affected       | " (get summary :files-affected)    " |")
       (str "| Rules violated       | " (get summary :rules-violated)    " |")
       ""
       "## Violations by Rule"
       ""
       (str/join "\n\n"
         (map (fn [[rule-id viols]]
                (section-for-rule rule-id viols))
              (sort-by #(dewey-order (get (first (second %)) :rule/category "0"))
                       by-rule)))
       ""
       "## Needs-Review Summary"
       ""
       (if (seq review-viols)
         (str/join "\n"
           (map (fn [v]
                  (str "- `" (get v :file) "` L" (get v :line)
                       " (" (get v :rule/category) "): " (get v :rationale)))
                review-viols))
         "_No violations require manual review._")
       ""
       "## Execution Instructions"
       ""
       "1. Run `clj -M:compliance-scanner run! <repo-path>` to apply all auto-fixes."
       "2. Manually address the needs-review violations listed above."
       "3. Re-run the scanner to confirm zero violations."
       ""])))

;------------------------------------------------------------------------------ Layer 2
;; Top-level entry point

(defn plan
  "Generate DAG task defs and markdown work spec from violations.

   Arguments:
   - violations  - vector of classified Violation maps
   - _repo-path  - string path to repo root (reserved for future use)

   Returns Plan map."
  [violations _repo-path]
  (let [dag-tasks (build-dag-tasks violations)
        summary   (factory/->plan-summary violations)
        work-spec (build-work-spec violations summary)]
    (factory/->plan dag-tasks work-spec summary)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (def viols
    [{:rule/id :std/clojure :rule/category "210" :rule/title "Clojure Map Access"
      :file "components/foo/src/core.clj" :line 10
      :current "(or (:k m) nil)" :suggested "(get m :k nil)"
      :auto-fixable? true :rationale "Literal default"}
     {:rule/id :std/header-copyright :rule/category "810" :rule/title "Copyright Header (Markdown)"
      :file "components/foo/src/core.clj" :line 1
      :current "(missing copyright header)" :suggested nil
      :auto-fixable? true :rationale "Header absent"}
     {:rule/id :std/clojure :rule/category "210" :rule/title "Clojure Map Access"
      :file "components/bar/src/core.clj" :line 5
      :current "(or (:status m) :ok)" :suggested nil
      :auto-fixable? false :rationale "Possible JSON-mapped field"}])

  (def p (plan viols "."))
  (println (:work-spec p))
  (count (:dag-tasks p))

  :leave-this-here)
