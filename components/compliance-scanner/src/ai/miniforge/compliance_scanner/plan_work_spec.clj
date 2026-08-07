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
(ns ai.miniforge.compliance-scanner.plan-work-spec
  "Markdown work-spec assembly, split out of `plan` (rule 210: a fifth
   real layer there is the signal to split it). Per-rule section
   rendering lives in `plan-rule-section`; Dewey ordering lives in
   `plan-dag`.

   Layer 0: Full markdown work-spec assembly"
  (:require [ai.miniforge.compliance-scanner.messages          :as msg]
            [ai.miniforge.compliance-scanner.plan-dag           :as plan-dag]
            [ai.miniforge.compliance-scanner.plan-rule-section  :as rule-section]
            [clojure.string                                     :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} build-work-spec
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
                (rule-section/section-for-rule rule-id viols))
              (sort-by #(plan-dag/dewey-order (get (first (second %)) :rule/category "0"))
                       by-rule)))
       ""
       (msg/t :plan/needs-review-summary)
       ""
       (if (seq review-viols)
         (str/join "\n\n"
           (map (fn [v]
                  (str "**[" (str/upper-case (name (get v :rule/severity :info)))
                       "]** Rule: " (get v :rule/title (name (get v :rule/id :unknown)))
                       "\n\n"
                       "  Problem: " (get v :rationale) "\n"
                       "  Location: `" (get v :file) ":" (get v :line) "`\n"
                       "  Current: `" (get v :current "") "`\n"
                       (when-let [suggested (get v :suggested)]
                         (str "  Fix: `" suggested "`\n"))
                       (when-let [refs (get v :rule/references)]
                         (str "  Docs: " (str/join ", " refs) "\n"))))
                review-viols))
         (msg/t :plan/no-review))
       ""
       (msg/t :plan/exec-instructions)
       ""
       (msg/t :plan/instr-1)
       (msg/t :plan/instr-2)
       (msg/t :plan/instr-3)
       ""])))
