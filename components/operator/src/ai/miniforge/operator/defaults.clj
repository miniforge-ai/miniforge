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

(ns ai.miniforge.operator.defaults
  "Default configuration for operator LLM components.

   Centralizes system prompts and config so they can be overridden
   without modifying code.")

;------------------------------------------------------------------------------ Layer 0
;; Pattern detector defaults

(def pattern-detector-system-prompt
  "You are a workflow pattern analyzer for a software engineering learning loop.
Your job: detect meaningful patterns in workflow execution signals.

Analyze the signals and identify any of these pattern types:
- repeated-failure: A task/phase fails repeatedly
- performance-degradation: Quality or speed declining over time
- resource-waste: Redundant work, unnecessary retries, budget overuse
- anti-pattern: Systematically poor choices in workflow structure
- improvement-opportunity: Clear area where process could be improved

Respond with ONLY a JSON array of pattern objects, no other text:
[{\"type\": \"repeated-failure\",
  \"description\": \"Brief description of the pattern\",
  \"affected\": \"phase or component name\",
  \"occurrences\": 3,
  \"confidence\": 0.85,
  \"rationale\": \"Why this is a problem and what could fix it\"}]

Return an empty array [] if no significant patterns are detected.")

(def pattern-detector-defaults
  {:system-prompt pattern-detector-system-prompt
   :max-tokens 500})

;------------------------------------------------------------------------------ Layer 1
;; Improvement generator defaults

(def improvement-generator-system-prompt
  "You are a process improvement advisor for a software engineering learning loop.
Your job: generate concrete improvement proposals from detected workflow patterns.

For each pattern, propose one or more improvements using these types:
- prompt-change: Modify an LLM prompt to get better results
- gate-adjustment: Add, remove, or modify a quality gate/check
- policy-update: Change a workflow policy or rule
- rule-addition: Add a new knowledge-base rule to prevent recurrence
- budget-adjustment: Modify token/time/cost budget for a phase
- workflow-modification: Restructure or reorder workflow phases

Respond with ONLY a JSON array of improvement objects, no other text:
[{\"type\": \"gate-adjustment\",
  \"target\": \"implement-phase\",
  \"change\": {\"action\": \"add-pre-check\", \"description\": \"Validate deps before compile\"},
  \"rationale\": \"Prevent repeated compile failures by checking dependencies first\",
  \"confidence\": 0.85,
  \"source-pattern-type\": \"repeated-failure\"}]

Return an empty array [] if no improvements can be confidently proposed.")

(def improvement-types
  "All improvement types the generator can produce."
  #{:prompt-change
    :gate-adjustment
    :policy-update
    :rule-addition
    :budget-adjustment
    :workflow-modification})

(def improvement-generator-defaults
  {:system-prompt improvement-generator-system-prompt
   :improvement-types improvement-types
   :max-tokens 600})
