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

(defn format-artifact-manifest
  "Compact manifest of the artifact-under-review for the shed path (N12 §5):
   each file's path, action, and size — not its inlined body. The reviewer
   runs read-only with native Read/Grep disallowed, so it fetches any file
   on demand via `context_read`, which reads through to the worktree on a
   cache miss. Mirrors the planner's `format-existing-files-manifest`.

   Only `:code/files` artifacts carry sheddable bodies; plain-string / other
   shapes have nothing to shed and fall back to the full rendering (the
   budget ladder treats those as a zero sheddable-unit count, so this
   fallback is never reached on the shed branch — it just keeps the
   formatter total over every artifact shape)."
  [artifact]
  (if (:code/files artifact)
    (str "_File bodies omitted to fit the context window — fetch any on "
         "demand with `context_read`:_\n"
         (str/join "\n"
                   (map (fn [{:keys [path content action]}]
                          (str "- " path " (" (name (or action :unknown)) ")"
                               (when content (str " (" (count content) " chars)"))))
                        (:code/files artifact))))
    (format-artifact-for-review artifact)))

;------------------------------------------------------------------------------ Layer 2
;; User-prompt assembly

(defn build-review-prompt
  "Construct the user prompt for LLM review from task data.

   `artifact-fmt` selects how the artifact-under-review is rendered: full
   bodies by default (`format-artifact-for-review`), or a compact manifest
   (`format-artifact-manifest`) on the N12 §5 shed path when the full prompt
   would overflow the model's context window."
  ([input] (build-review-prompt input format-artifact-for-review))
  ([input artifact-fmt]
   (let [artifact (or (:task/artifact input) input)
         description (or (:task/description input) "")
         title (or (:task/title input) "")
         intent (or (:task/intent input) "")
         constraints (or (:task/constraints input) "")
         tests (:task/tests input)
         review-scope (scope/effective-review-scope input)
         artifact-text (artifact-fmt artifact)]
     (prompts/render-template
      (get @reviewer-prompt-data :prompt/user-template)
      {"title" title
       "has_title" (not (str/blank? title))
       "description" description
       "has_description" (not (str/blank? description))
       "intent" (if (string? intent) intent (pr-str intent))
       "has_intent" (not (str/blank? (str intent)))
       "review_scope" review-scope
       "constraints" (if (string? constraints) constraints (pr-str constraints))
       "has_constraints" (not (str/blank? (str constraints)))
       "artifact_text" artifact-text
       "has_tests" (some? tests)
       "tests" (when (some? tests)
                 (if (string? tests) tests (pr-str tests)))}))))

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
