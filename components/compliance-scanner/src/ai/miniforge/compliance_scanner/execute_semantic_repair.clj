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
(ns ai.miniforge.compliance-scanner.execute-semantic-repair
  "LLM semantic-repair prompt building, split out of `execute` (rule 210:
   a fifth real layer there is the signal to split it). Dispatch path for
   :semantic remediation strategy violations — the actual LLM call is
   deferred, this only produces the prompt.

   Layer 0: Prompt template
   Layer 1: Structured prompt builder")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private semantic-repair-template
  "Template for LLM semantic repair prompts.
   Pack-bundled templates (PR #463) will supersede this when wired."
  (str "Fix the following code violation.\n\n"
       "**Rule:** %s\n"
       "**File:** %s:%s\n"
       "**Current code:** `%s`\n"
       "**Issue:** %s\n"
       "%s\n"
       "\nProvide the corrected code only, no explanation."))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} build-semantic-repair-prompt
  "Build a structured prompt for LLM-based semantic repair of a violation.

   This is the dispatch path for :semantic remediation strategy violations.
   The actual LLM call is deferred — this function produces the prompt.

   Arguments:
   - violation — Violation map with :rule/id, :file, :line, :current, :rationale
   - rule      — Full rule map with :rule/knowledge-content

   Returns:
   - {:role :user :content string}"
  [violation rule]
  (let [reference (when-let [kc (get rule :rule/knowledge-content)]
                    (str "\n**Reference:**\n" kc))]
    {:role    :user
     :content (format semantic-repair-template
                      (get rule :rule/title (name (get violation :rule/id)))
                      (get violation :file "")
                      (get violation :line "")
                      (get violation :current "")
                      (get violation :rationale "")
                      (or reference ""))}))
