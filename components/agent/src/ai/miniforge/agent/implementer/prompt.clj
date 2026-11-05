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
(ns ai.miniforge.agent.implementer.prompt
  "Implementer prompt text: the system prompt and its prompt data, the
   task text with its plan, intent, review, verify, gate-failure,
   prior-attempt and phase-handoff sections, the user prompt, and the
   submission-retry prompt."
  (:require
   [ai.miniforge.agent.implementer.handoff :as impl-handoff]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.repo-index.interface :as messages]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} implementer-system-prompt
  "System prompt for the implementer agent.
   Loaded from EDN resource for configurability."
  (delay (prompts/load-prompt :implementer)))

(def ^{:stratum 0} implementer-prompt-data
  "Full prompt data map for the implementer agent."
  (delay (prompts/load-prompt-data :implementer)))

;; Prompt formatting
(defn ^{:stratum 0} format-repo-map
  "Format a repo map for inclusion in the user prompt."
  [repo-map-text]
  (when (and repo-map-text (not (str/blank? repo-map-text)))
    (str "\n\n## " (messages/t :prompt/repo-map-section-title) "\n\n"
         (messages/t :prompt/repo-map-header)
         "\n\n" repo-map-text)))

(defn- ^{:stratum 0} format-file-block
  "Format a single file as a markdown code block."
  [{:keys [path content truncated?]}]
  (str "\n### " path
       (when truncated? " (truncated)")
       "\n```\n" content "\n```"))

;; Task → text conversion
(defn- ^{:stratum 0} format-plan-section
  "Format the plan as a markdown section."
  [plan]
  (str "\n\n## " (messages/t :prompt/plan-header) "\n\n"
       (if (string? plan) plan (pr-str plan))))

(defn- ^{:stratum 0} format-intent-section
  "Format the intent as a markdown section."
  [intent]
  (str "\n\n## " (messages/t :prompt/intent-header) "\n\n"
       (pr-str intent)))

(defn- ^{:stratum 0} format-review-section
  "Format review feedback as a markdown section."
  [review-feedback]
  (str "\n\n## " (messages/t :prompt/review-feedback-header) "\n\n"
       (messages/t :prompt/review-feedback-intro) "\n\n"
       (if (string? review-feedback) review-feedback (pr-str review-feedback))))

(defn- ^{:stratum 0} format-verify-section
  "Format verify failures as a markdown section."
  [verify-failures]
  (str "\n\n## " (messages/t :prompt/test-failures-header) "\n\n"
       (messages/t :prompt/test-failures-intro) "\n\n"
       (if-let [test-results (:test-results verify-failures)]
         (str (messages/t :prompt/test-results-label) "\n" (pr-str test-results))
         (str (messages/t :prompt/verify-details-label) "\n" (pr-str verify-failures)))
       (when-let [test-output (:test-output verify-failures)]
         (str "\n\n" (messages/t :prompt/test-output-label) "\n" test-output))))

(defn- ^{:stratum 0} format-gate-failures-section
  "Format a prior attempt's gate denial as an imperative repair section.
   These are DETERMINISTIC checks: the same change resubmitted unchanged
   is denied again, so the section leads and reads as instructions."
  [gate-failures]
  (str "\n\n## " (messages/t :prompt/gate-failures-header) "\n\n"
       (messages/t :prompt/gate-failures-intro) "\n\n"
       (str/join "\n"
                 (for [{:keys [gate errors]} gate-failures
                       error errors]
                   ;; The file list and matching lines ride structurally,
                   ;; not only inside :message: when a catalog entry was
                   ;; missing, :message was the bare word "stale" and five
                   ;; trap-bench reps never saw the file they had to fix.
                   (str "- [" (name (or gate :unknown)) "] "
                        (or (:message error) (pr-str error))
                        (when-let [files (seq (:files error))]
                          (str "\n  " (messages/t :prompt/gate-failure-files)
                               " " (str/join ", " files)))
                        (str/join ""
                                  (for [{:keys [file line text]} (:hits error)]
                                    (str "\n  - " file ":" line ": " text)))
                        ;; :tests-pass carries verify's failing tests here so
                        ;; the denial names what to fix, not only how many.
                        (str/join ""
                                  (for [{:keys [test location]} (:failures error)]
                                    (str "\n  - " test
                                         (when location (str " (" location ")"))))))))
       "\n"))

(defn- ^{:stratum 0} format-prior-attempts-section
  "Format prior attempt context as a prominent warning section."
  [{:keys [attempt-number prior-error instruction]}]
  (str "\n\n## ⚠️ RETRY — Attempt " attempt-number "\n\n"
       "**Previous attempt failed:** " prior-error "\n\n"
       "**" instruction "**\n"))

(defn ^{:stratum 0} task-sections
  "The evidence sections `task->text` will actually render for `task`,
   in render order — the ONE place the render predicates live, so the
   prompt-section telemetry can only ever claim what was rendered.
   :task/gate-failures renders on non-empty; the others on presence."
  [task]
  (when (map? task)
    (cond-> []
      (:task/prior-attempts task) (conj :task/prior-attempts)
      (seq (:task/gate-failures task)) (conj :task/gate-failures)
      (:task/phase-handoff task) (conj :task/phase-handoff)
      (:task/review-feedback task) (conj :task/review-feedback)
      (:task/verify-failures task) (conj :task/verify-failures))))

(defn- ^{:stratum 0} format-phase-handoff-section
  "Format a structured phase handoff as a prominent repair section."
  [phase-handoff]
  (str "\n\n## " (messages/t :prompt/phase-handoff-header) "\n\n"
       (messages/t :prompt/phase-handoff-intro) "\n\n"
       (impl-handoff/format-phase-handoff-findings (impl-handoff/phase-handoff-findings phase-handoff))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} format-existing-files
  "Format existing file contents for inclusion in the user prompt."
  [files]
  (when (seq files)
    (str "\n\n## " (messages/t :prompt/existing-files-section-title) "\n\n"
         (messages/t :prompt/existing-files-header) "\n"
         (->> files (map format-file-block) (str/join "\n")))))

(defn ^{:stratum 1} build-effective-system-prompt
  "Append behavior addendum to the base system prompt."
  [input]
  (str @implementer-system-prompt
       (get input :task/behavior-addendum "")))

(defn ^{:stratum 1} submission-retry-prompt
  "Submission-only retry prompt: re-feed the task + the prior (prose) output
   and ask the implementer to deliver the actual file writes now."
  [task-text prior-content]
  (prompts/render-template (get @implementer-prompt-data :prompt/submission-retry-template)
                           {:task-text task-text :prior-content prior-content}))

(defn ^{:stratum 1} task->text
  "Convert a task to text for the LLM.
   Includes plan and intent when available for richer context."
  [task]
  (cond
    (string? task) task
    (map? task) (let [desc (or (:task/description task) (:description task) (:content task))
                      plan (:task/plan task)
                      intent (:task/intent task)
                      phase-handoff (:task/phase-handoff task)
                      review-feedback (:task/review-feedback task)
                      verify-failures (:task/verify-failures task)
                      gate-failures (:task/gate-failures task)
                      prior-attempts (:task/prior-attempts task)
                      sections (set (task-sections task))
                      parts (cond-> []
                              (sections :task/prior-attempts)
                              (conj (format-prior-attempts-section prior-attempts))
                              (sections :task/gate-failures)
                              (conj (format-gate-failures-section gate-failures))
                              (sections :task/phase-handoff)
                              (conj (format-phase-handoff-section phase-handoff))
                              desc
                              (conj desc)
                              plan
                              (conj (format-plan-section plan))
                              (and intent (map? intent))
                              (conj (format-intent-section intent))
                              (sections :task/review-feedback)
                              (conj (format-review-section review-feedback))
                              (sections :task/verify-failures)
                              (conj (format-verify-section verify-failures)))]
                  (if (seq parts)
                    (str/join "" parts)
                    (pr-str task)))
    :else (str task)))

;------------------------------------------------------------------------------ Layer 2

;; LLM invocation helpers
(defn ^{:stratum 2} build-user-prompt
  "Build the user prompt for the implementer."
  [task-text input]
  (str (messages/t :prompt/implement-task-prefix) "\n\n"
       task-text
       (format-repo-map (:task/repo-map input))
       (format-existing-files (:task/existing-files input))
       "\n\n" (messages/t :prompt/output-instruction)
       "\n\n" (messages/t :prompt/already-impl-instruction) "\n"
       (messages/t :prompt/already-impl-template)))
