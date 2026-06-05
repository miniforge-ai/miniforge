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

(ns ai.miniforge.agent.reviewer.issues
  "Reviewer schemas + issue/decision canonical shapes.

   Extracted from `ai.miniforge.agent.reviewer` as the second step of the
   PR #1039 / PR-C decomposition: holds the malli schemas
   (`GateFeedback`, `ReviewIssue`, `ReviewArtifact`), the issue-shape
   validators and sanitizer used at every LLM-output boundary, the
   decision-enum predicates (`rejection-decisions`, `valid-decisions`,
   `review-has-blocking?`), and the LLM-decision normalizer.

   No dependencies on prompts, gates, or LLM-response infrastructure —
   the goal is a stable foundation the rest of the reviewer decomposition
   can build against without circular requires."
  (:require [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Schemas

(def GateFeedback
  "Schema for feedback from a single gate."
  [:map
   [:gate-id keyword?]
   [:gate-type keyword?]
   [:passed? boolean?]
   [:errors {:optional true} [:vector :any]]
   [:warnings {:optional true} [:vector :any]]
   [:duration-ms {:optional true} [:int {:min 0}]]])

(def ReviewIssue
  "Schema for a single review issue from LLM analysis.

   Optional fields are wrapped in `:maybe` because the LLM frequently
   emits explicit `nil` for fields that don't apply to a given issue
   (e.g. `:line nil` on a file-level concern). Bare `{:optional true}`
   only permits the key to be absent, not present-but-nil — a strict
   read of the schema would silently reject an otherwise-fine review
   and cascade through `parse-review-response` → `llm-review = nil`
   → `llm-decision = :rejected`, flipping a real :approved verdict
   into a rejected one. The 2026-05-16 event-log-tool-visibility
   dogfood shipped a verifier-pass + LLM-:approved build that the
   gate refused for exactly this reason; the root-cause trace lives
   in `docs/pull-requests/2026-05-16-fix-reviewer-issue-schema-nil-tolerance.md`."
  [:map
   [:severity [:enum :blocking :warning :nit]]
   [:file {:optional true} [:maybe [:string {:min 1}]]]
   [:line {:optional true} [:maybe [:int {:min 1}]]]
   [:description [:string {:min 1}]]
   [:suggestion {:optional true} [:maybe [:string {:min 1}]]]])

(def ReviewArtifact
  "Schema for the reviewer's output."
  [:map
   [:review/id uuid?]
   [:review/decision [:enum :approved :rejected :conditionally-approved :changes-requested]]
   [:review/gate-results [:vector GateFeedback]]
   [:review/summary [:string {:min 1}]]
   [:review/artifact-id {:optional true} uuid?]
   [:review/gates-passed [:int {:min 0}]]
   [:review/gates-failed [:int {:min 0}]]
   [:review/gates-total [:int {:min 0}]]
   [:review/blocking-issues {:optional true} [:vector :string]]
   [:review/warnings {:optional true} [:vector :string]]
   [:review/recommendations {:optional true} [:vector :string]]
   [:review/issues {:optional true} [:vector ReviewIssue]]
   ;; Findings outside the task's :scope. Advisory only — these MUST NOT
   ;; appear in :review/issues and do NOT drive the verdict. Carried on
   ;; the artifact so the implementer's redirect feedback (and any
   ;; downstream evidence consumer) can still see what the reviewer
   ;; observed in adjacent code.
   [:review/out-of-scope-observations {:optional true} [:vector ReviewIssue]]
   [:review/strengths {:optional true} [:vector :string]]
   [:review/created-at {:optional true} inst?]])

;------------------------------------------------------------------------------ Layer 1
;; Issue-shape validation + sanitization

(def ^:private review-issue-keys
  "Canonical keys accepted in LLM review issue maps."
  #{:severity :file :line :description :suggestion})

(defn- valid-review-issue-map?
  "True when an LLM-supplied issue has the canonical ReviewIssue shape.
   Malli map schemas are intentionally open, so reject extra keys here to
   catch EDN that parsed only because quoted prose was read as symbols."
  [issue]
  (and (map? issue)
       (every? review-issue-keys (keys issue))
       (m/validate ReviewIssue issue)))

(defn valid-review-issues?
  "True when parsed LLM review issues are absent or structurally canonical.

   Used by `parse-review-response` to reject a whole LLM output when its
   `:review/issues` carries any malformed entry — the alternative is the
   2026-05-16 nil-line incident where the malli check on the assembled
   `ReviewArtifact` silently rejects an otherwise-clean :approved verdict."
  [parsed]
  (let [issues (find parsed :review/issues)]
    (or (nil? issues)
        (and (vector? (val issues))
             (every? valid-review-issue-map? (val issues))))))

(defn sanitize-review-issues
  "Filter an LLM-supplied issue collection down to the entries that match
   the canonical `ReviewIssue` shape.

   Returns a vector — empty when input is nil, not a collection, or every
   entry is malformed. Use at every point where unvalidated LLM output
   would otherwise reach `ReviewArtifact`'s malli check (the schema
   rejects the whole artifact on a single bad issue map, which would
   silently fail the entire review). The pattern came out of the
   `:review/issues` parse-validator (`valid-review-issues?`); this helper
   exists so additional issue-shaped fields (currently
   `:review/out-of-scope-observations`) don't reinvent the same filter at
   every call site."
  [issues]
  (->> issues
       (filter valid-review-issue-map?)
       vec))

;------------------------------------------------------------------------------ Layer 2
;; Decision enums + predicates

(def rejection-decisions
  "Review decisions that count as a rejection for enumeration-retry purposes.

   `:conditionally-approved` is intentionally NOT a rejection — it is an
   approval that asks the implementer to address listed nits in a future
   pass; treating it as a rejection would cascade into a redirect and
   defeat the retry's permitted self-correction path."
  #{:rejected :changes-requested})

(def valid-decisions
  "All decision keywords the ReviewArtifact schema enum accepts.

   `parse-review-response` doesn't validate the decision enum, so the
   enumeration-retry validator must — an unexpected decision (`:approve`
   typo, `nil`, etc.) would otherwise slip past `well-formed-recovery?`
   as 'non-rejection' and get collapsed downstream to
   `:changes-requested`, re-introducing the exact malformed rejection
   the validator exists to prevent."
  #{:approved :rejected :conditionally-approved :changes-requested})

(defn review-has-blocking?
  "True when `issues` contains at least one entry with :severity :blocking."
  [issues]
  (boolean (some #(= :blocking (:severity %)) issues)))

(defn normalize-llm-decision
  "Map LLM decision keywords to ReviewArtifact-compatible decisions.
   Preserves every variant the ReviewArtifact schema allows — collapsing
   `:conditionally-approved` to `:changes-requested` (a rejection-class
   decision) would misclassify a legitimate conditional approval as a
   rejection and defeat the enumeration validator + the retry's permitted
   self-correction to conditionally-approved."
  [decision]
  (case decision
    :approved               :approved
    :rejected               :rejected
    :changes-requested      :changes-requested
    :conditionally-approved :conditionally-approved
    ;; default
    :changes-requested))

;------------------------------------------------------------------------------ Layer 3
;; Issue-text extraction helpers

(defn llm-issues->blocking-strings
  "Extract blocking issue descriptions from LLM issues."
  [issues]
  (->> issues
       (filter #(= :blocking (:severity %)))
       (mapv :description)))

(defn llm-issues->warning-strings
  "Extract warning descriptions from LLM issues."
  [issues]
  (->> issues
       (filter #(= :warning (:severity %)))
       (mapv :description)))
