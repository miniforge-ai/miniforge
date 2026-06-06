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

(ns ai.miniforge.agent.reviewer.artifact
  "Reviewer artifact assembly, validation, repair, lifecycle telemetry,
   and public-API accessors.

   Extracted from `ai.miniforge.agent.reviewer` as PR-G of the
   decomposition stack (#1039 scope → #1041 issues → #1042 gates →
   #1044 prompts → #1045 llm-response → this).

   Holds everything between the LLM-response processing path and the
   final `:status :success` result map:
   - `extract-artifact-and-id` — resolve the artifact + its ID from input.
   - `build-review-artifact` / `build-review-result` — assemble the
     `ReviewArtifact` from gate feedbacks + LLM output + scope-filtered
     observations and wrap it with metrics.
   - `validate-review-artifact` / `repair-review-artifact` — malli
     schema check + minimal self-repair for the loop validator.
   - `enter-review` / `leave-review` / `timeout-only-error-result` —
     phase-lifecycle telemetry + the backend-timeout-only error exit.
   - Public-API accessors (`approved?`, `rejected?`, `get-issues`, …)
     used by phase-software-factory and downstream consumers through
     `agent.interface.specialized`.

   reviewer.clj is left as a thin orchestrator wiring these together."
  (:require [ai.miniforge.agent.result-boundary :as result-boundary]
            [ai.miniforge.agent.reviewer.gates :as gates]
            [ai.miniforge.agent.reviewer.issues :as issues]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.schema.interface :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Artifact extraction + ID

(defn extract-artifact-and-id
  "Extract `[artifact artifact-id]` from review input.

   Resolves `:task/artifact` → `:artifact` → the raw input itself for the
   artifact, then prefers `:artifact/id` → `:code/id` for the id. Anything
   resolved that isn't already a `uuid?` is replaced with a fresh
   `random-uuid`.

   The UUID coercion matters: `:review/artifact-id` is schema-typed
   `uuid?` on `ReviewArtifact` (see
   `ai.miniforge.agent.reviewer.issues/ReviewArtifact`), so a non-UUID id
   here would silently fail the malli check downstream and either churn
   through `repair-review-artifact` or land as an invalid artifact. Test
   scaffolding routinely uses string ids like `\"code-1\"`; promoting to
   a UUID is the boundary that keeps those harmless."
  [input]
  (let [artifact (or (:task/artifact input) (:artifact input) input)
        resolved (or (:artifact/id artifact) (:code/id artifact))
        artifact-id (if (uuid? resolved) resolved (random-uuid))]
    [artifact artifact-id]))

;------------------------------------------------------------------------------ Layer 1
;; Validation + repair

(defn validate-review-artifact
  "Validate a review artifact against the schema."
  [artifact]
  (let [schema-valid? (m/validate issues/ReviewArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain issues/ReviewArtifact artifact)}
      ;; Additional validations
      (let [passed (:review/gates-passed artifact)
            failed (:review/gates-failed artifact)
            total (:review/gates-total artifact)]
        (if (not= total (+ passed failed))
          {:valid? false
           :errors {:gates "Gate counts don't add up"}}
          {:valid? true :errors nil})))))

(defn repair-review-artifact
  "Attempt to repair a review artifact."
  [artifact _errors _context]
  (let [repaired (atom artifact)]
    ;; Fix missing ID
    (when-not (:review/id @repaired)
      (swap! repaired assoc :review/id (random-uuid)))

    ;; Fix missing decision
    (when-not (:review/decision @repaired)
      (swap! repaired assoc :review/decision :rejected))

    ;; Fix missing gate results
    (when-not (:review/gate-results @repaired)
      (swap! repaired assoc :review/gate-results []))

    ;; Recalculate gate counts
    (let [results (:review/gate-results @repaired)
          passed (count (filter :passed? results))
          failed (count (filter (complement :passed?) results))
          total (count results)]
      (swap! repaired assoc
             :review/gates-passed passed
             :review/gates-failed failed
             :review/gates-total total))

    ;; Fix missing summary
    (when-not (:review/summary @repaired)
      (swap! repaired assoc :review/summary
             (gates/generate-summary (:review/decision @repaired)
                                     (:review/gate-results @repaired))))

    {:status :success
     :output @repaired}))

;------------------------------------------------------------------------------ Layer 2
;; Artifact + result assembly

(defn build-review-artifact
  "Build the review artifact from gate results, LLM feedback, and decision."
  [gate-feedbacks decision blocking-issues warnings artifact-id counts
   & {:keys [issues strengths summary out-of-scope-observations]}]
  (cond-> {:review/id (random-uuid)
           :review/decision decision
           :review/gate-results gate-feedbacks
           :review/summary (or summary (gates/generate-summary decision gate-feedbacks))
           :review/artifact-id artifact-id
           :review/gates-passed (:passed counts)
           :review/gates-failed (:failed counts)
           :review/gates-total (:total counts)
           :review/blocking-issues blocking-issues
           :review/warnings warnings
           :review/recommendations (gates/generate-recommendations gate-feedbacks)
           :review/created-at (java.util.Date.)}
    (seq issues) (assoc :review/issues issues)
    (seq strengths) (assoc :review/strengths strengths)
    (seq out-of-scope-observations)
    (assoc :review/out-of-scope-observations out-of-scope-observations)))

(defn build-review-result
  "Build the final result map with metrics."
  [review counts duration tokens & {:keys [cost-usd]}]
  {:status :success
   :output review
   :artifact review
   :metrics (cond-> {:decision (:review/decision review)
                     :gates-passed (:passed counts)
                     :gates-failed (:failed counts)
                     :gates-total (:total counts)
                     :duration-ms duration
                     :tokens tokens}
              cost-usd (assoc :cost-usd cost-usd))})

;------------------------------------------------------------------------------ Layer 3
;; Phase-lifecycle telemetry

(defn enter-review
  "Emit a phase-started telemetry event when entering the review phase.

   Called at the very beginning of a review invocation to mark phase entry.
   `data` is a map of contextual information about the review about to begin
   (e.g. :artifact-id, :gate-count, :llm?)."
  [logger data]
  (log/info logger :reviewer :reviewer/phase-started {:data data}))

(defn leave-review
  "Emit a phase-completed telemetry event when leaving the review phase.

   Called just before returning from a review invocation to mark phase exit.
   `data` must include :review/decision; additional fields (e.g. :duration-ms,
   :gates-passed, :gates-failed) are recommended for observability."
  [logger data]
  (log/info logger :reviewer :reviewer/phase-completed {:data data}))

(defn timeout-only-error-result
  "Normalize the reviewer exit path when the LLM only reports its own timeout.

   This preserves backend timeout metadata, emits the standard phase-completed
   telemetry, and reports the deterministic gate outcome instead of converting
   the backend failure into a bogus code-review rejection.

   `llm-review` may be nil — PR-A of the phase-timeout stack added a
   boundary-level stream-idle path where the LLM call errors before
   producing any parseable review. The defaulted vector keeps the
   `:blocking-issues` field a vector across both invocation shapes so
   downstream telemetry / error consumers that pattern-match on
   sequential data don't see a surprise nil from the new path."
  [logger normalized llm-review gate-result counts duration tokens cost-usd timeout-failure-message]
  (let [blocking-issues (vec (:review/blocking-issues llm-review))]
    (log/warn logger :reviewer :reviewer/backend-timeout-only
              {:data {:llm-decision (:review/decision llm-review)
                      :gate-decision (:decision gate-result)
                      :blocking-issues blocking-issues
                      :duration-ms duration}})
    (leave-review logger {:review/decision (:decision gate-result)
                          :duration-ms duration
                          :gates-passed (:passed counts)
                          :gates-failed (:failed counts)
                          :llm? true
                          :status :error
                          :error-code :reviewer/backend-timeout})
    (assoc
     (result-boundary/error-response
      normalized
      timeout-failure-message
      {:data (merge (or (some-> normalized :llm-error :data) {})
                    {:code :reviewer/backend-timeout
                     :blocking-issues blocking-issues})})
     :metrics
     (cond-> {:decision (:decision gate-result)
              :gates-passed (:passed counts)
              :gates-failed (:failed counts)
              :gates-total (:total counts)
              :duration-ms duration
              :tokens tokens}
       cost-usd (assoc :cost-usd cost-usd)))))

;------------------------------------------------------------------------------ Layer 4
;; Public-API accessors
;;
;; Surfaced through `agent.interface.specialized` for phase-software-factory
;; and other downstream consumers. Kept as a tight, intentional surface so
;; renames here don't ripple silently through the SDLC pipeline.

(defn review-summary
  "Get a summary of a review artifact for logging/display."
  [artifact]
  {:id (:review/id artifact)
   :decision (:review/decision artifact)
   :gates-passed (:review/gates-passed artifact)
   :gates-failed (:review/gates-failed artifact)
   :gates-total (:review/gates-total artifact)
   :blocking-issues-count (count (:review/blocking-issues artifact))
   :warnings-count (count (:review/warnings artifact))
   :llm-issues-count (count (:review/issues artifact))})

(defn approved?
  "Check if a review artifact represents approval."
  [artifact]
  (= :approved (:review/decision artifact)))

(defn rejected?
  "Check if a review artifact represents rejection."
  [artifact]
  (= :rejected (:review/decision artifact)))

(defn conditionally-approved?
  "Check if a review artifact is conditionally approved."
  [artifact]
  (= :conditionally-approved (:review/decision artifact)))

(defn changes-requested?
  "Check if a review artifact has changes requested."
  [artifact]
  (= :changes-requested (:review/decision artifact)))

(defn get-blocking-issues
  "Extract blocking issues from review artifact."
  [artifact]
  (:review/blocking-issues artifact []))

(defn get-warnings
  "Extract warnings from review artifact."
  [artifact]
  (:review/warnings artifact []))

(defn get-recommendations
  "Extract recommendations from review artifact."
  [artifact]
  (:review/recommendations artifact []))

(defn get-issues
  "Extract LLM review issues from review artifact."
  [artifact]
  (:review/issues artifact []))

(defn get-strengths
  "Extract strengths noted by the LLM from review artifact."
  [artifact]
  (:review/strengths artifact []))

(defn rejection-warnings-only?
  "True when the review is a rejection driven exclusively by warnings (no
   real blocking defects).

   Returns true iff ALL three conditions hold:
   1. `:review/decision` is a rejection-class decision
      (`issues/rejection-decisions` — `:rejected` or `:changes-requested`).
   2. `:review/blocking-issues` is empty (no hard blockers).
   3. `:review/warnings` is non-empty (there are warning-level nits).

   This is the cause-classification primitive that `leave-review` (and
   downstream phase logic) needs to distinguish a genuine-defect rejection
   from nit-churn: a rejection where every complaint is advisory-only
   warrants a different response (e.g. skip-redirect) versus one where at
   least one true blocker exists."
  [artifact]
  (boolean
   (and (contains? issues/rejection-decisions (:review/decision artifact))
        (empty? (get artifact :review/blocking-issues []))
        (seq (get artifact :review/warnings [])))))
