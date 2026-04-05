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
  "Group violations by [file dewey-code].
   Returns map of [file dewey] -> [violation ...]."
  [violations]
  (group-by (fn [v] [(get v :file) (get v :rule/dewey)]) violations))

(defn- dewey-order
  "Numeric sort key for a Dewey code string."
  [dewey-str]
  (try (Integer/parseInt dewey-str) (catch Exception _ 0)))

(defn- build-dag-tasks
  "Build PlanTask records with intra-file ordering deps.

   Within a file, a task for a higher Dewey code depends on all tasks
   for lower Dewey codes in that same file."
  [violations]
  (let [groups         (group-by-file-rule violations)
        ;; Build a map: [file dewey] -> fresh UUID (assigned now)
        key->id        (into {} (map (fn [k] [k (random-uuid)]) (keys groups)))
        ;; Sort dewey codes per file for dep resolution
        file->deweys   (->> (keys groups)
                            (group-by first)
                            (into {} (map (fn [[file ks]]
                                           [file (->> ks
                                                      (map second)
                                                      (sort-by dewey-order)
                                                      vec)]))))]
    (mapv (fn [[[file dewey] viols]]
            (let [id         (get key->id [file dewey])
                  ;; Deps = UUIDs of same-file tasks with lower Dewey codes
                  prior-deweys (take-while #(< (dewey-order %)
                                               (dewey-order dewey))
                                           (get file->deweys file []))
                  deps       (into #{} (map #(get key->id [file %]) prior-deweys))]
              (factory/->plan-task id deps file dewey viols)))
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
  "Render a markdown section for all violations of one Dewey rule."
  [dewey viols]
  (let [rule-title (get (first viols) :rule/title dewey)
        auto  (filter :auto-fixable? viols)
        needs (remove :auto-fixable? viols)]
    (str/join "\n"
      (concat
       [(str "### Dewey " dewey " — " rule-title)
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
  (let [by-rule      (group-by :rule/dewey violations)
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
         (map (fn [[dewey viols]]
                (section-for-rule dewey viols))
              (sort-by #(dewey-order (first %)) by-rule)))
       ""
       "## Needs-Review Summary"
       ""
       (if (seq review-viols)
         (str/join "\n"
           (map (fn [v]
                  (str "- `" (get v :file) "` L" (get v :line)
                       " (" (get v :rule/dewey) "): " (get v :rationale)))
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
    [{:rule/dewey "210" :rule/title "Clojure Map Access"
      :file "components/foo/src/core.clj" :line 10
      :current "(or (:k m) nil)" :suggested "(get m :k nil)"
      :auto-fixable? true :rationale "Literal default"}
     {:rule/dewey "810" :rule/title "Copyright Header (Markdown)"
      :file "components/foo/src/core.clj" :line 1
      :current "(missing copyright header)" :suggested nil
      :auto-fixable? true :rationale "Header absent"}
     {:rule/dewey "210" :rule/title "Clojure Map Access"
      :file "components/bar/src/core.clj" :line 5
      :current "(or (:status m) :ok)" :suggested nil
      :auto-fixable? false :rationale "Possible JSON-mapped field"}])

  (def p (plan viols "."))
  (println (:work-spec p))
  (count (:dag-tasks p))

  :leave-this-here)
