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

(ns ai.miniforge.agent.reviewer.llm-response
  "Reviewer LLM response processing.

   Extracted from `ai.miniforge.agent.reviewer` as PR-F of the
   decomposition stack (#1039 scope → #1041 issues → #1042 gates →
   #1044 prompts → this).

   Holds everything that happens to the LLM's raw response between the
   `llm/chat` call and the assembled `ReviewArtifact`:
   - `parse-review-response` — EDN extraction from code-block or plain text
   - Failure-message resolution for the assorted failure paths
     (`review-failure-message`, `backend-failure-message`)
   - Backend-timeout detection (`backend-timeout-issue?`,
     `timeout-only-review?`) — distinguishes a real `:rejected` verdict
     from the reviewer LLM hitting its own progress-monitor timeout.
     `timeout-only-review?` reads the parsed review map and gate result;
     the nil-parse path (LLM errored before producing content) is handled
     by the CALLER (reviewer.clj) ORing this predicate with
     `result-boundary/backend-timeout-error?` on the NORMALIZED boundary
     — splitting the two levels prevents the 2026-06-05 dogfood shape
     drift (adhoc-944448986) where nil `llm-review` caused
     `timeout-only-review?` to silently return false and synthesize a
     false `:rejected`.
   - Enumeration-retry validator + recovery (`enumeration-retry?`,
     `well-formed-recovery?`, `recover-review-enumeration`) — re-runs
     the LLM once when a rejection lands without inline blockers"
  (:require [ai.miniforge.agent.messages :as msg]
            [ai.miniforge.agent.result-boundary :as result-boundary]
            [ai.miniforge.agent.reviewer.issues :as issues]
            [ai.miniforge.agent.reviewer.prompts :as reviewer-prompts]
            [ai.miniforge.llm.interface :as llm]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Parsing

(defn parse-review-response
  "Parse the LLM response to extract review feedback.
   Handles EDN in code blocks and plain EDN."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   (edn/read-string response-content))]
      (when (and (map? parsed)
                 (issues/valid-review-issues? parsed))
        parsed))
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; Failure messages

(defn review-failure-message
  "Derive the blocking-issue text recorded when the reviewer LLM response
   cannot be converted into a canonical review artifact.

   Three cases, in priority order:
   1. The LLM returned content but parsing failed → the canonical
      `:reviewer.llm-response/unparseable` message.
   2. No content but the LLM client surfaced an error message → that
      error's `:message` verbatim (already operator-facing).
   3. Neither → the generic pre-parse invocation-failed message."
  [response content]
  (let [content-present? (not (str/blank? (or content "")))
        llm-error (llm/get-error response)]
    (cond
      content-present?
      (msg/t :reviewer.llm-response/unparseable)

      (string? (:message llm-error))
      (:message llm-error)

      :else
      (msg/t :reviewer.llm-response/invocation-failed-pre-parse))))

(defn backend-failure-message
  "Derive the backend failure message when the reviewer LLM response parsed
   but the underlying invocation still flagged failure.

   Prefers the LLM client's error message, then the first inline
   `:review/blocking-issues` entry (the LLM may have enumerated the
   failure itself), then a generic post-parse fallback."
  [response llm-review]
  (if-let [message (:message (llm/get-error response))]
    message
    (or (first (:review/blocking-issues llm-review))
        (msg/t :reviewer.llm-response/invocation-failed-post-parse))))

;------------------------------------------------------------------------------ Layer 2
;; Backend-timeout detection

(defn backend-timeout-issue?
  "True when `message` looks like a reviewer-backend timeout / stagnation
   message rather than a code-review finding. Matched as a regex against
   the strings the runner / progress-monitor emit."
  [message]
  (boolean
   (and (string? message)
        (re-find #"(?i)(adaptive timeout|stagnation timeout|timed out|stream-idle|timeout)"
                 message))))

(defn timeout-only-review?
  "True when the reviewer's outcome reflects the reviewer backend's own
   timeout rather than actionable code-review findings.

   Reads the PARSED review map directly; all conditions must hold:
   - LLM decision is a rejection-class verdict
     (`:rejected` / `:changes-requested`)
   - Deterministic gates are otherwise approved
   - `:review/blocking-issues` is present (the LLM enumerated something)
   - `:review/recommendations` and `:review/issues` are both empty
   - Every entry in `:review/blocking-issues` is a timeout-shaped string
     per `backend-timeout-issue?`

   Scope boundary — nil-parse path NOT handled here:
   When the LLM errors before producing any parseable content, `llm-review`
   is nil and the checks below silently return false. That path is the
   exact 2026-06-05 dogfood (adhoc-944448986) pathology where stream-idle
   text got promoted into `:review/blocking-issues` from the parse-failed
   branch, synthesising a false `:rejected`. This predicate covers only
   the review-SHAPED case (a parsed rejection whose blockers are all
   timeout text); the caller (reviewer.clj) ORs it with
   `result-boundary/backend-timeout-error?` on the NORMALIZED boundary so
   nil-parse timeouts route to the backend-timeout (infra) exit, not here.

   Args:
   - `llm-review`  — parsed review map (nil when LLM errored; the caller's
                     boundary check covers that path).
   - `gate-result` — deterministic gate decision map."
  [llm-review gate-result]
  (let [blocking-issues    (vec (:review/blocking-issues llm-review))
        recommendations    (vec (:review/recommendations llm-review))
        issue-vec          (vec (:review/issues llm-review))
        negative-decision? (contains? issues/rejection-decisions
                                      (:review/decision llm-review))]
    (and negative-decision?
         (= :approved (:decision gate-result))
         (seq blocking-issues)
         (empty? recommendations)
         (empty? issue-vec)
         (every? backend-timeout-issue? blocking-issues))))

;------------------------------------------------------------------------------ Layer 3
;; Enumeration-retry validator + recovery
;;
;; A rejection without enumerated :blocking findings is malformed (the
;; implementer cannot act on it). Mirrors the planner/implementer
;; submission-recovery pattern but for the reviewer's *output shape* — re-runs
;; the reviewer once with an enumeration-retry prompt that demands the inline
;; list. The reviewer doesn't use artifact-session/worktree-promotion, so the
;; retry is a direct LLM call (not the session-wrapped run-recovery-session).

(defn enumeration-retry?
  "True when the LLM's review decision is a rejection but it enumerated NO
   :blocking findings AND the deterministic gates have no blocking issues
   either — a malformed rejection the implementer cannot act on. The
   validator rejects this review and demands a re-enumeration."
  [llm-decision llm-issues gate-blocking]
  (and (contains? issues/rejection-decisions llm-decision)
       (not (issues/review-has-blocking? llm-issues))
       (empty? gate-blocking)))

(defn well-formed-recovery?
  "True when a re-reviewed ReviewArtifact resolves the malformed-rejection
   case: either it now enumerates :blocking findings, OR it correctly
   concludes :approved / :conditionally-approved (the retry template
   explicitly allows that — discarding it would leave the original
   malformed rejection in place and re-introduce the churn the validator
   exists to eliminate).

   The raw `:review/decision` is read directly (not re-normalized) to
   keep this insulated from future `issues/normalize-llm-decision`
   changes — but the decision MUST be one of the ReviewArtifact enums
   first (`parse-review-response` doesn't check), otherwise a typo or
   nil decision would slip past as 'non-rejection' and get collapsed
   downstream to `:changes-requested` — defeating the whole validator."
  [re-review]
  (let [dec (:review/decision re-review)]
    (and (contains? issues/valid-decisions dec)
         (or (not (contains? issues/rejection-decisions dec))
             (issues/review-has-blocking? (get re-review :review/issues []))))))

(def ^:private default-enumeration-retry-max-turns
  "Fallback when reviewer.edn omits :prompt/enumeration-retry-max-turns.
   Authority is the EDN; this constant only protects against malformed
   prompt resources."
  6)

(defn recover-review-enumeration
  "Run ONE bounded enumeration-retry turn and return the re-parsed
   ReviewArtifact when the recovery is well-formed (enumerates blockers
   OR corrects to a non-rejection decision), or nil when recovery ALSO
   produced a malformed rejection — in which case the original raw
   rejection stands as-is."
  [llm-client base-opts on-chunk user-prompt prior-content]
  (let [retry-prompt (reviewer-prompts/enumeration-retry-prompt user-prompt prior-content)
        retry-opts   (assoc base-opts :max-turns
                            (get @reviewer-prompts/reviewer-prompt-data
                                 :prompt/enumeration-retry-max-turns
                                 default-enumeration-retry-max-turns))
        response     (if on-chunk
                       (llm/chat-stream llm-client retry-prompt on-chunk retry-opts)
                       (llm/chat llm-client retry-prompt retry-opts))
        normalized   (result-boundary/normalize-llm-result
                      {:response response :parse-response parse-review-response})
        re-review    (:parsed-content normalized)]
    (when (and re-review (well-formed-recovery? re-review))
      re-review)))
