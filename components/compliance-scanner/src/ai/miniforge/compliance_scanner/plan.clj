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
  (:require [ai.miniforge.compliance-scanner.factory   :as factory]
            [ai.miniforge.compliance-scanner.messages  :as msg]
            [clojure.string                            :as str]))

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

(defn- key->uuid-entry
  "Return [k (random-uuid)] for use in building a key->id map."
  [k]
  [k (random-uuid)])

(defn- key->category-entry
  "Return [k category-string] for a [file rule-id] key and its violations."
  [[k vs]]
  [k (get (first vs) :rule/category "0")])

(defn- file->ordered-rule-ids-entry
  "Return [file ordered-rule-ids] sorted by Dewey category ascending.
   ks is a seq of [file rule-id] keys; the result extracts just the rule-ids."
  [key->cat [file ks]]
  [file (->> ks
             (sort-by #(dewey-order (get key->cat %)))
             (mapv second))])

(defn- build-task
  "Build a PlanTask for a [file rule-id] group, resolving intra-file deps."
  [key->id key->cat file->rule-ids [[file rule-id] viols]]
  (let [id        (get key->id [file rule-id])
        prior-ids (take-while
                   #(< (dewey-order (get key->cat [file %]))
                       (dewey-order (get key->cat [file rule-id])))
                   (get file->rule-ids file []))
        deps      (into #{} (map #(get key->id [file %]) prior-ids))]
    (factory/->plan-task id deps file rule-id viols)))

(defn- build-dag-tasks
  "Build PlanTask records with intra-file ordering deps.

   Within a file, a task for a rule with a higher Dewey category depends on all
   tasks for rules with lower Dewey categories in that same file."
  [violations]
  (let [groups         (group-by-file-rule violations)
        key->id        (into {} (map key->uuid-entry (keys groups)))
        key->cat       (into {} (map key->category-entry groups))
        file->rule-ids (into {} (map (partial file->ordered-rule-ids-entry key->cat)
                                     (group-by first (keys groups))))]
    (mapv (partial build-task key->id key->cat file->rule-ids) groups)))

;------------------------------------------------------------------------------ Layer 1
;; Markdown work-spec builder

(defn- violation->md-row
  "Render a single violation as a markdown list item."
  [v]
  (let [tag (if (get v :auto-fixable?) (msg/t :plan/tag-auto) (msg/t :plan/tag-review))]
    (str "- **L" (get v :line) "** `" (get v :current) "`"
         (when-let [s (get v :suggested)]
           (str " → `" s "`"))
         " [" tag "]")))

(defn- render-violation-group
  "Render a labeled group of violations as markdown lines, or nil if empty."
  [label viols]
  (when (seq viols)
    (concat [label ""]
            (map violation->md-row viols)
            [""])))

(defn- section-for-rule
  "Render a markdown section for all violations of one rule."
  [_rule-id viols]
  (let [rule-title (get (first viols) :rule/title (str _rule-id))
        rule-cat   (get (first viols) :rule/category "?")
        auto       (filter :auto-fixable? viols)
        needs      (remove :auto-fixable? viols)]
    (str/join "\n"
      (concat
       [(msg/t :plan/rule-section-header {:category rule-cat :title rule-title})
        ""]
       (render-violation-group (msg/t :plan/auto-fixable-label) auto)
       (render-violation-group (msg/t :plan/needs-review-label) needs)))))

(defn- build-work-spec
  "Build the full markdown work spec string from classified violations."
  [violations summary]
  (let [by-rule      (group-by :rule/id violations)
        review-viols (remove :auto-fixable? violations)]
    (str/join "\n"
      [(msg/t :plan/title)
       ""
       (msg/t :plan/exec-summary)
       ""
       (msg/t :plan/table-header)
       (msg/t :plan/table-separator)
       (msg/t :plan/metric-total        {:n (get summary :total-violations)})
       (msg/t :plan/metric-auto         {:n (get summary :auto-fixable)})
       (msg/t :plan/metric-needs-review {:n (get summary :needs-review)})
       (msg/t :plan/metric-files        {:n (get summary :files-affected)})
       (msg/t :plan/metric-rules        {:n (get summary :rules-violated)})
       ""
       (msg/t :plan/violations-by-rule)
       ""
       (str/join "\n\n"
         (map (fn [[rule-id viols]]
                (section-for-rule rule-id viols))
              (sort-by #(dewey-order (get (first (second %)) :rule/category "0"))
                       by-rule)))
       ""
       (msg/t :plan/needs-review-summary)
       ""
       (if (seq review-viols)
         (str/join "\n"
           (map (fn [v]
                  (str "- `" (get v :file) "` L" (get v :line)
                       " (" (get v :rule/category) "): " (get v :rationale)))
                review-viols))
         (msg/t :plan/no-review))
       ""
       (msg/t :plan/exec-instructions)
       ""
       (msg/t :plan/instr-1)
       (msg/t :plan/instr-2)
       (msg/t :plan/instr-3)
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
