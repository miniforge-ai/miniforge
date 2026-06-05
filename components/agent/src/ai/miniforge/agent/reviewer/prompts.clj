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

(ns ai.miniforge.agent.reviewer.prompts
  "Reviewer prompt assembly.

   Extracted from `ai.miniforge.agent.reviewer` as PR-E of the
   decomposition stack (#1039 scope → #1041 issues → #1042 gates → this).

   Holds the prompt-side concerns:
   - Loading and caching the reviewer's system prompt + prompt-data EDN
     (`reviewer-system-prompt`, `reviewer-prompt-data`).
   - The main-turn progress monitor + max-turns fallback knobs that gate
     stream supervision.
   - The artifact formatter that turns a `CodeArtifact` into the
     markdown the LLM sees (`format-artifact-for-review`).
   - The user-prompt builder, including the scope-bound `## Scope`
     section (`build-review-prompt`).
   - The bounded enumeration-retry prompt used when the LLM rejects
     without enumerating blockers (`enumeration-retry-prompt`).

   Prompt content stays in English on purpose — these are LLM instructions,
   not operator-facing copy, and the LLM was trained on English templates.
   Localization rule 050 applies to UI/log text, not LLM prompts. The
   system prompt lives in `resources/prompts/reviewer.edn`; this namespace
   only orchestrates loading + per-call template substitution."
  (:require [ai.miniforge.agent.prompts :as prompts]
            [ai.miniforge.agent.reviewer.scope :as scope]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Prompt resource loading

(def reviewer-system-prompt
  "System prompt for the reviewer agent."
  (delay (prompts/load-prompt :reviewer)))

(def reviewer-prompt-data
  "Full prompt data map for the reviewer agent.
   Exposes knobs like :prompt/progress-monitor that gate stream supervision."
  (delay (prompts/load-prompt-data :reviewer)))

(defn create-reviewer-progress-monitor
  "Reviewer main-turn progress monitor. Thresholds live in
   resources/prompts/reviewer.edn (:prompt/progress-monitor). Falls
   back to the framework default when the EDN omits the block."
  []
  (prompts/load-progress-monitor @reviewer-prompt-data
                                 :prompt/progress-monitor))

(def default-reviewer-max-turns
  "Fallback when reviewer.edn omits :prompt/max-turns. Authority is
   the EDN; this constant only protects against malformed prompt
   resources."
  20)

;------------------------------------------------------------------------------ Layer 1
;; Artifact rendering

(defn format-artifact-for-review
  "Format a code artifact into a readable string for the LLM prompt."
  [artifact]
  (cond
    ;; CodeArtifact with :code/files
    (:code/files artifact)
    (str/join "\n\n"
              (map (fn [{:keys [path content action]}]
                     (str "### " path " (" (name (or action :unknown)) ")\n"
                          "```\n" content "\n```"))
                   (:code/files artifact)))

    ;; Plain string
    (string? artifact)
    artifact

    ;; Fallback
    :else
    (pr-str artifact)))

;------------------------------------------------------------------------------ Layer 2
;; User-prompt assembly

(defn build-review-prompt
  "Construct the user prompt for LLM review from task data."
  [input]
  (let [artifact (or (:task/artifact input) input)
        description (or (:task/description input) "")
        title (or (:task/title input) "")
        intent (or (:task/intent input) "")
        constraints (or (:task/constraints input) "")
        tests (:task/tests input)
        review-scope (scope/effective-review-scope input)
        artifact-text (format-artifact-for-review artifact)]
    (str "Review the following code implementation.\n\n"
         (when-not (str/blank? title)
           (str "## Task: " title "\n\n"))
         (when-not (str/blank? description)
           (str "## Description\n\n" description "\n\n"))
         (when (and intent (not (str/blank? (str intent))))
           (str "## Intent\n\n" (if (string? intent) intent (pr-str intent)) "\n\n"))
         (when review-scope
           (str "## Scope\n\n"
                "Findings inside these paths/prefixes are in-scope; report them in\n"
                "`:review/issues` with the appropriate severity\n"
                "(`:blocking` / `:warning` / `:nit`). Normal severity rules apply —\n"
                "only `:blocking` issues actually block the verdict.\n\n"
                "Findings outside the scope are out-of-scope — report them in\n"
                "`:review/out-of-scope-observations`, NOT in `:review/issues`.\n\n"
                (str/join "\n" (map #(str "- " %) review-scope))
                "\n\n"))
         (when (and constraints (not (str/blank? (str constraints))))
           (str "## Constraints\n\n" (if (string? constraints) constraints (pr-str constraints)) "\n\n"))
         "## Code to Review\n\n"
         artifact-text
         (when tests
           (str "\n\n## Test Results\n\n"
                (if (string? tests) tests (pr-str tests))))
         "\n\nOutput your review as a Clojure map inside a ```clojure code block.")))

;------------------------------------------------------------------------------ Layer 3
;; Enumeration retry

(defn enumeration-retry-prompt
  "Build the enumeration-retry prompt: the ORIGINAL review user-prompt (so the
   retry has the same artifact/diff evidence the first call had — `llm/chat`
   is single-turn with no history) followed by the retry instruction with the
   prior malformed output for reference."
  [user-prompt prior-content]
  (str user-prompt
       "\n\n---\n\n"
       (prompts/render-template
        (get @reviewer-prompt-data :prompt/enumeration-retry-template)
        {:prior-content prior-content})))
