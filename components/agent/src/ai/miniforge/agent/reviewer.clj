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

(ns ai.miniforge.agent.reviewer
  "Reviewer agent implementation.
   Performs LLM-backed semantic code review plus deterministic gate validation.
   Falls back to gate-only review when no LLM backend is available."
  (:require
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Reviewer-specific schemas

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

;; System prompt - loaded from resources/prompts/reviewer.edn
(def reviewer-system-prompt
  "System prompt for the reviewer agent."
  (delay (prompts/load-prompt :reviewer)))

(def ^:private reviewer-prompt-data
  "Full prompt data map for the reviewer agent.
   Exposes knobs like :prompt/progress-monitor that gate stream supervision."
  (delay (prompts/load-prompt-data :reviewer)))

(defn- create-reviewer-progress-monitor
  "Reviewer main-turn progress monitor. Thresholds live in
   resources/prompts/reviewer.edn (:prompt/progress-monitor). Falls
   back to the framework default when the EDN omits the block."
  []
  (prompts/load-progress-monitor @reviewer-prompt-data
                                 :prompt/progress-monitor))

(def ^:private default-reviewer-max-turns
  "Fallback when reviewer.edn omits :prompt/max-turns. Authority is
   the EDN; this constant only protects against malformed prompt
   resources."
  20)

(def ^:private review-issue-keys
  "Canonical keys accepted in LLM review issue maps."
  #{:severity :file :line :description :suggestion})

;------------------------------------------------------------------------------ Layer 1
;; Gate running and feedback

(defn gate-result->feedback
  "Convert a loop gate result to reviewer feedback format.

   Gate identity resolution order:
     1. `:gate/id`   on the result   (populated by `loop/pass-result`
                                      and `loop/fail-result`)
     2. `:gate/id`   on the gate     (rare — not set by current records,
                                      kept for forward-compat)
     3. `:id`        on the gate     (the plain field on SyntaxGate /
                                      LintGate / TestGate / PolicyGate /
                                      CustomGate records)
     4. `:unknown`   final fallback

   Gate-type resolution differs slightly. Only `CustomGate` carries a
   `type-kw` field on the record itself; the other gate records hard-
   code their type at construction (e.g. `SyntaxGate` always passes
   `:syntax` into the result via `loop/pass-result`). So:
     1. `:gate/type` on the result
     2. `:gate/type` on the gate
     3. `:type-kw`   on the gate     (CustomGate only)
     4. `:unknown`

   Before this two-step resolution every failing gate surfaced as
   `:unknown` in the `:failing-gate-ids` diagnostic and the 2026-05-18
   dogfood couldn't tell which gate flipped its LLM verdict."
  [gate result]
  (let [gate-id   (or (:gate/id result) (:gate/id gate) (:id gate) :unknown)
        gate-type (or (:gate/type result) (:gate/type gate) (:type-kw gate) :unknown)
        passed?   (:gate/passed? result true)
        errors    (:gate/errors result [])
        warnings  (:gate/warnings result [])]
    {:gate-id gate-id
     :gate-type gate-type
     :passed? passed?
     :errors errors
     :warnings warnings
     :duration-ms (get result :gate/duration-ms 0)}))

(defn create-exception-feedback
  "Create error feedback when gate throws exception."
  [gate idx exception duration]
  {:gate-id (or (:gate/id gate) (keyword (str "gate-" idx)))
   :gate-type :unknown
   :passed? false
   :errors [{:type :gate-exception
             :message (str "Gate execution failed: " (ex-message exception))}]
   :duration-ms duration})

(defn run-single-gate
  "Run a single gate and return feedback with timing.
   Handles exceptions gracefully."
  [gate idx artifact context logger]
  (let [gate-start (System/currentTimeMillis)
        gate-id (:gate/id gate :unknown)]
    (log/debug logger :reviewer :reviewer/gate-start
               {:data {:gate-idx idx :gate-id gate-id}})
    (try
      (let [result (loop/check-gate gate artifact context)
            duration (- (System/currentTimeMillis) gate-start)
            feedback (gate-result->feedback gate result)]
        (log/info logger :reviewer :reviewer/gate-complete
                  {:data {:gate-id (:gate-id feedback)
                          :passed? (:passed? feedback)
                          :duration-ms duration}})
        (assoc feedback :duration-ms duration))
      (catch Exception e
        (let [duration (- (System/currentTimeMillis) gate-start)]
          (log/error logger :reviewer :reviewer/gate-error
                     {:data {:gate-idx idx
                             :error (ex-message e)}})
          (create-exception-feedback gate idx e duration))))))

(defn run-gates-on-artifact
  "Run all gates on the artifact and collect results.
   Returns vector of GateFeedback maps."
  [gates artifact context logger]
  (log/info logger :reviewer :reviewer/running-gates
            {:data {:gate-count (count gates)}})
  (->> gates
       (map-indexed (fn [idx gate]
                      (run-single-gate gate idx artifact context logger)))
       vec))

(defn implementation-handoff-gate
  "Reject degraded implement handoffs before semantic review.

   This catches environment-model cases where implement recovered a code artifact
   from disk after an agent-side failure, or where the curator flagged
   out-of-scope writes. Both are strong signals that review must not silently
   approve the handoff."
  []
  (loop/custom-gate
   :implementation-handoff
   :policy
   (fn [artifact _context]
     (let [degraded? (true? (:code/degraded-handoff? artifact))
           scope-deviations (vec (:code/scope-deviations artifact))
           errors (cond-> []
                    degraded?
                    (conj (loop/make-error
                           :degraded-implement-handoff
                           "Implement handoff is degraded: the artifact was recovered after an implementer-side failure"))
                    (seq scope-deviations)
                    (conj (loop/make-error
                           :scope-deviations
                           (str "Artifact includes out-of-scope writes: "
                                (str/join ", " scope-deviations)))))]
       (if (seq errors)
         (loop/fail-result :implementation-handoff :policy errors)
         (loop/pass-result :implementation-handoff :policy))))))

(def ^:private advisory-gate-types
  "Internal gate types whose failure is advisory — it must NOT override an
   LLM :approved verdict. Only :lint qualifies: a style/formatting failure on
   a build that already passed verify is not a ship-blocker, and treating it
   as one flipped verify-passed, LLM-:approved builds to :rejected, capping
   every dogfood spec at the :review-approved gate (observed 2026-05-24,
   workflow adhoc-807481487).

   Every other gate type blocks by default — fail-safe. That deliberately
   includes unresolved `:unknown` and gate-execution exceptions
   (`create-exception-feedback` produces `:unknown` errors with no
   `:severity`): a gate that crashed must block review, never silently
   approve."
  #{:lint})

(defn- error-blocking?
  "An internal-gate error is non-blocking only when it explicitly declares a
   non-:blocking severity, or — absent an explicit severity — comes from an
   advisory gate type. Everything else blocks (fail-safe), so an unresolved
   `:unknown` gate type or a gate crash cannot pass review open."
  [gate-type error]
  (if-let [severity (:severity error)]
    (= :blocking severity)
    (not (contains? advisory-gate-types gate-type))))

(defn extract-blocking-issues
  "Extract blocking errors from failed gates, honoring per-error severity and
   gate type (see `error-blocking?`). Errors from advisory gates such as
   :lint no longer force a rejection of an otherwise-approved review."
  [failed-gates]
  (->> failed-gates
       (mapcat (fn [{:keys [gate-type errors]}]
                 (filter #(error-blocking? gate-type %) errors)))
       vec))

(defn extract-warning-messages
  "Extract warning messages from all gates."
  [gate-feedbacks]
  (->> gate-feedbacks
       (mapcat :warnings)
       (map :message)
       vec))

(defn extract-error-messages
  "Extract error messages from failed gates."
  [failed-gates]
  (->> failed-gates
       (mapcat :errors)
       (map :message)
       vec))

(defn decide-on-failures
  "Determine decision when there are gate failures."
  [failed-gates blocking-issues config]
  (if (seq blocking-issues)
    {:decision :rejected
     :blocking-issues (mapv :message blocking-issues)
     :warnings []}
    {:decision (if (:strict config false) :rejected :conditionally-approved)
     :blocking-issues (if (:strict config)
                        (extract-error-messages failed-gates)
                        [])
     :warnings (extract-error-messages failed-gates)}))

(defn make-review-decision
  "Determine review decision based on gate results."
  [gate-feedbacks config]
  (let [failed (filter (complement :passed?) gate-feedbacks)
        failed-count (count failed)]
    (if (zero? failed-count)
      {:decision :approved
       :blocking-issues []
       :warnings (extract-warning-messages gate-feedbacks)}
      (let [blocking-issues (extract-blocking-issues failed)]
        (decide-on-failures failed blocking-issues config)))))

(defn generate-summary
  "Generate human-readable summary of review."
  [decision gate-feedbacks]
  (let [passed (filter :passed? gate-feedbacks)
        failed (filter (complement :passed?) gate-feedbacks)
        total (count gate-feedbacks)]
    (case decision
      :approved
      (format "Review approved: All %d gates passed" total)

      :rejected
      (format "Review rejected: %d/%d gates failed with blocking issues"
              (count failed) total)

      :conditionally-approved
      (format "Conditionally approved: %d/%d gates passed, %d non-blocking issues"
              (count passed) total (count failed))

      ;; default for :changes-requested or other LLM-sourced decisions
      (format "Review complete: %s (%d gates evaluated)" (name decision) total))))

(defn generate-recommendations
  "Generate recommendations based on gate results."
  [gate-feedbacks]
  (->> gate-feedbacks
       (filter (complement :passed?))
       (mapcat (fn [feedback]
                 (map (fn [error]
                        (str "[" (name (:gate-id feedback)) "] "
                             (:message error)
                             (when-let [fix (:fix-suggestion error)]
                               (str " -> " fix))))
                      (:errors feedback))))
       vec))

;------------------------------------------------------------------------------ Layer 2
;; LLM review: prompt building and response parsing

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

(defn- clean-scope-paths
  "Drop blank and non-string entries, trim whitespace, dedupe."
  [paths]
  (->> paths
       (keep #(when (string? %) (not-empty (str/trim %))))
       distinct
       vec
       not-empty))

(defn effective-review-scope
  "Resolve the effective scope vector for a review task.

   Strict priority — uses the most specific signal available and ignores
   broader ones underneath it. Order:

   1. `:task/scope` — set explicitly by the dispatch layer for this task.
   2. `:task/files-in-scope` — a DAG sub-task's per-task scope from the
      planner (`dag-orchestrator/task-sub-input`).
   3. `(:scope task/intent)` — the spec-level scope, when intent is an
      EDN map carrying it.

   Merging across levels would dilute per-task narrowness with the broader
   spec scope, which defeats the purpose. Returns a deduped vector of
   non-blank strings, or nil when no scope signal is present (legacy
   behavior: review every file)."
  [input]
  (let [intent (:task/intent input)]
    (or (clean-scope-paths (:task/scope input))
        (clean-scope-paths (:task/files-in-scope input))
        (when (map? intent) (clean-scope-paths (:scope intent))))))

(defn- normalize-scope-entry
  "Strip trailing slashes so a directory prefix matches `dir/file.clj` cleanly
   without a false-positive on `dir-suffix/x.clj`."
  [s]
  (str/replace s #"/+$" ""))

(defn in-scope-issue?
  "True when a `ReviewIssue`'s `:file` falls inside `scope`.

   - `scope == nil`  → true (no filtering; legacy behavior).
   - `:file` blank   → true (file-level concern with no path attribution;
                       conservatively kept so the LLM can still surface a
                       real concern when it didn't name a file).
   - Otherwise       → at least one scope entry equals the file or is a
                       parent directory of it (entry + `/` is a prefix of
                       the file path)."
  [scope issue]
  (let [file (some-> (:file issue) str str/trim not-empty)]
    (cond
      (nil? scope) true
      (nil? file)  true
      :else (boolean
              (some (fn [entry]
                      (let [trimmed (normalize-scope-entry entry)]
                        (or (= file trimmed)
                            (str/starts-with? file (str trimmed "/")))))
                    scope)))))

(defn partition-issues-by-scope
  "Split `issues` against `scope` and return
   `{:in-scope [...] :out-of-scope [...]}`. When `scope` is nil every
   issue is in-scope (no filtering)."
  [scope issues]
  (let [{in true out false} (group-by #(in-scope-issue? scope %) issues)]
    {:in-scope     (vec in)
     :out-of-scope (vec out)}))

(defn build-review-prompt
  "Construct the user prompt for LLM review from task data."
  [input]
  (let [artifact (or (:task/artifact input) input)
        description (or (:task/description input) "")
        title (or (:task/title input) "")
        intent (or (:task/intent input) "")
        constraints (or (:task/constraints input) "")
        tests (:task/tests input)
        scope (effective-review-scope input)
        artifact-text (format-artifact-for-review artifact)]
    (str "Review the following code implementation.\n\n"
         (when (seq title)
           (str "## Task: " title "\n\n"))
         (when (seq description)
           (str "## Description\n\n" description "\n\n"))
         (when (and intent (not (str/blank? (str intent))))
           (str "## Intent\n\n" (if (string? intent) intent (pr-str intent)) "\n\n"))
         (when scope
           (str "## Scope\n\n"
                "Findings inside these paths/prefixes are in-scope; report them in\n"
                "`:review/issues` with the appropriate severity\n"
                "(`:blocking` / `:warning` / `:nit`). Normal severity rules apply —\n"
                "only `:blocking` issues actually block the verdict.\n\n"
                "Findings outside the scope are out-of-scope — report them in\n"
                "`:review/out-of-scope-observations`, NOT in `:review/issues`.\n\n"
                (str/join "\n" (map #(str "- " %) scope))
                "\n\n"))
         (when (and constraints (not (str/blank? (str constraints))))
           (str "## Constraints\n\n" (if (string? constraints) constraints (pr-str constraints)) "\n\n"))
         "## Code to Review\n\n"
         artifact-text
         (when tests
           (str "\n\n## Test Results\n\n"
                (if (string? tests) tests (pr-str tests))))
         "\n\nOutput your review as a Clojure map inside a ```clojure code block.")))

(defn- valid-review-issue-map?
  "True when an LLM-supplied issue has the canonical ReviewIssue shape.
   Malli map schemas are intentionally open, so reject extra keys here to
   catch EDN that parsed only because quoted prose was read as symbols."
  [issue]
  (and (map? issue)
       (every? review-issue-keys (keys issue))
       (m/validate ReviewIssue issue)))

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

(defn- valid-review-issues?
  "True when parsed LLM review issues are absent or structurally canonical."
  [parsed]
  (let [issues (find parsed :review/issues)]
    (or (nil? issues)
        (and (vector? (val issues))
             (every? valid-review-issue-map? (val issues))))))

(defn parse-review-response
  "Parse the LLM response to extract review feedback.
   Handles EDN in code blocks and plain EDN."
  [response-content]
  (try
    (let [parsed (if-let [match (re-find #"```(?:clojure|edn)?\s*\n([\s\S]*?)\n```" response-content)]
                   (edn/read-string (second match))
                   (edn/read-string response-content))]
      (when (and (map? parsed)
                 (valid-review-issues? parsed))
        parsed))
    (catch Exception _
      nil)))

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

(def ^:private unparseable-review-message
  "Blocking issue used when the reviewer LLM returns content that cannot be parsed
   into the canonical review artifact shape."
  "Reviewer LLM output could not be parsed into a review artifact")

(defn- review-failure-message
  "Derive the blocking issue recorded when the reviewer LLM response cannot
   be converted into a canonical review artifact."
  [response content]
  (let [content-present? (not (str/blank? (or content "")))
        llm-error (llm/get-error response)]
    (cond
      content-present? unparseable-review-message
      (string? (:message llm-error)) (:message llm-error)
      :else "Reviewer LLM invocation failed before producing a review artifact")))

(defn- backend-failure-message
  "Derive the backend failure message when the reviewer LLM response parsed,
   but the underlying invocation still failed."
  [response llm-review]
  (if-let [message (:message (llm/get-error response))]
    message
    (or (first (:review/blocking-issues llm-review))
        "Reviewer LLM invocation failed after producing a review artifact")))

(defn- backend-timeout-issue?
  [message]
  (boolean
   (and (string? message)
        (re-find #"(?i)(adaptive timeout|stagnation timeout|timed out|stream-idle|timeout)"
                 message))))

(defn- timeout-only-review?
  "True when a parsed review artifact is just reflecting the reviewer backend's
   own timeout rather than providing actionable code-review findings."
  [llm-review gate-result]
  (let [blocking-issues (vec (:review/blocking-issues llm-review))
        recommendations (vec (:review/recommendations llm-review))
        issues (vec (:review/issues llm-review))
        negative-decision? (contains? #{:rejected :changes-requested}
                                      (:review/decision llm-review))]
    (and negative-decision?
         (= :approved (:decision gate-result))
         (seq blocking-issues)
         (empty? recommendations)
         (empty? issues)
         (every? backend-timeout-issue? blocking-issues))))

(defn llm-issues->recommendations
  "Extract suggestions from LLM issues as recommendations."
  [issues]
  (->> issues
       (filter :suggestion)
       (mapv (fn [{:keys [file description suggestion]}]
               (str (when file (str "[" file "] "))
                    description " -> " suggestion)))))

;------------------------------------------------------------------------------ Layer 3
;; Review validation and repair

(defn validate-review-artifact
  "Validate a review artifact against the schema."
  [artifact]
  (let [schema-valid? (m/validate ReviewArtifact artifact)]
    (if-not schema-valid?
      {:valid? false
       :errors (schema/explain ReviewArtifact artifact)}
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
             (generate-summary (:review/decision @repaired)
                               (:review/gate-results @repaired))))

    {:status :success
     :output @repaired}))

;------------------------------------------------------------------------------ Layer 4
;; Public API - Helper functions

(defn extract-artifact-and-id
  "Extract artifact and its ID from input."
  [input]
  (let [artifact (or (:task/artifact input) (:artifact input) input)
        artifact-id (or (:artifact/id artifact)
                        (:code/id artifact)
                        (random-uuid))]
    [artifact artifact-id]))

(defn calculate-gate-counts
  "Calculate passed, failed, and total gate counts."
  [gate-feedbacks]
  (let [passed (count (filter :passed? gate-feedbacks))
        failed (count (filter (complement :passed?) gate-feedbacks))
        total (count gate-feedbacks)]
    {:passed passed :failed failed :total total}))

(defn build-review-artifact
  "Build the review artifact from gate results, LLM feedback, and decision."
  [gate-feedbacks decision blocking-issues warnings artifact-id counts
   & {:keys [issues strengths summary out-of-scope-observations]}]
  (cond-> {:review/id (random-uuid)
           :review/decision decision
           :review/gate-results gate-feedbacks
           :review/summary (or summary (generate-summary decision gate-feedbacks))
           :review/artifact-id artifact-id
           :review/gates-passed (:passed counts)
           :review/gates-failed (:failed counts)
           :review/gates-total (:total counts)
           :review/blocking-issues blocking-issues
           :review/warnings warnings
           :review/recommendations (generate-recommendations gate-feedbacks)
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

(defn merge-gate-overrides
  "If gates failed, override the LLM decision accordingly."
  [llm-decision gate-decision config]
  (cond
    ;; Gate rejection always wins
    (= :rejected gate-decision)
    :rejected

    ;; Gate conditional-approval downgrades LLM approval
    (and (= :approved llm-decision) (= :conditionally-approved gate-decision))
    (if (:strict config) :rejected :conditionally-approved)

    ;; Otherwise use LLM decision
    :else
    llm-decision))

;------------------------------------------------------------------------------ Layer 4b
;; Phase lifecycle telemetry

(defn enter-review
  "Emit a phase-started telemetry event when entering the review phase.

   Called at the very beginning of a review invocation to mark phase entry.
   `data` is a map of contextual information about the review about to begin
   (e.g. :artifact-id, :gate-count, :llm?).

   Example:
     (enter-review logger {:artifact-id artifact-id
                           :gate-count (count gates)
                           :llm? (boolean llm-client)})"
  [logger data]
  (log/info logger :reviewer :reviewer/phase-started {:data data}))

(defn leave-review
  "Emit a phase-completed telemetry event when leaving the review phase.

   Called just before returning from a review invocation to mark phase exit.
   `data` must include :review/decision; additional fields (e.g. :duration-ms,
   :gates-passed, :gates-failed) are recommended for observability.

   Example:
     (leave-review logger {:review/decision :approved
                           :duration-ms 120
                           :gates-passed 3
                           :gates-failed 0})"
  [logger data]
  (log/info logger :reviewer :reviewer/phase-completed {:data data}))

(defn- timeout-only-error-result
  "Normalize the reviewer exit path when the LLM only reports its own timeout.

   This preserves backend timeout metadata, emits the standard phase-completed
   telemetry, and reports the deterministic gate outcome instead of converting
   the backend failure into a bogus code-review rejection."
  [logger normalized llm-review gate-result counts duration tokens cost-usd timeout-failure-message]
  (log/warn logger :reviewer :reviewer/backend-timeout-only
            {:data {:llm-decision (:review/decision llm-review)
                    :gate-decision (:decision gate-result)
                    :blocking-issues (:review/blocking-issues llm-review)
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
                   :blocking-issues (:review/blocking-issues llm-review)})})
   :metrics
   (cond-> {:decision (:decision gate-result)
            :gates-passed (:passed counts)
            :gates-failed (:failed counts)
            :gates-total (:total counts)
            :duration-ms duration
            :tokens tokens}
     cost-usd (assoc :cost-usd cost-usd))))

;------------------------------------------------------------------------------ Layer 5
;; Agent creation

;;----------------------------------------------------------------------------- Enumeration-retry validator
;; A rejection without enumerated :blocking findings is malformed (the
;; implementer cannot act on it). Mirrors the planner/implementer
;; submission-recovery pattern but for the reviewer's *output shape* — re-runs
;; the reviewer once with an enumeration-retry prompt that demands the inline
;; list. The reviewer doesn't use artifact-session/worktree-promotion, so the
;; retry is a direct LLM call (not the session-wrapped run-recovery-session).

(def ^:private rejection-decisions
  "Decisions that mean 'rejected — needs work'. Mirrors review.clj's
   `blocking-decisions`; defined locally to avoid a cross-component require."
  #{:rejected :changes-requested})

(def ^:private valid-decisions
  "The full set of `:review/decision` keywords the ReviewArtifact schema
   allows (see `ReviewArtifact` ~L75). `parse-review-response` does not itself
   validate the decision enum, so the enumeration-retry validator must — an
   unexpected decision (`:approve` typo, `nil`, etc.) would otherwise slip
   past `well-formed-recovery?` as 'non-rejection' and get collapsed
   downstream to `:changes-requested`, re-introducing the exact malformed
   rejection the validator exists to prevent."
  #{:approved :rejected :conditionally-approved :changes-requested})

(defn- review-has-blocking?
  "True when `issues` contains at least one entry with :severity :blocking."
  [issues]
  (boolean (some #(= :blocking (:severity %)) issues)))

(defn- enumeration-retry?
  "True when the LLM's review decision is a rejection but it enumerated NO
   :blocking findings AND the deterministic gates have no blocking issues
   either — a malformed rejection the implementer cannot act on. The validator
   rejects this review and demands a re-enumeration."
  [llm-decision llm-issues gate-blocking]
  (and (contains? rejection-decisions llm-decision)
       (not (review-has-blocking? llm-issues))
       (empty? gate-blocking)))

(defn- enumeration-retry-prompt
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

(defn- well-formed-recovery?
  "True when a re-reviewed ReviewArtifact resolves the malformed-rejection
   case: either it now enumerates :blocking findings, OR it correctly
   concludes :approved / :conditionally-approved (the retry template
   explicitly allows that — discarding it would leave the original malformed
   rejection in place and re-introduce the churn the validator exists to
   eliminate)."
  [re-review]
  ;; The raw `:review/decision` is read directly (not re-normalized) to keep
  ;; this insulated from future `normalize-llm-decision` changes — but we
  ;; MUST validate it is one of the ReviewArtifact enums first
  ;; (`parse-review-response` doesn't check), otherwise a typo or nil decision
  ;; would slip past as "non-rejection" and get collapsed downstream to
  ;; :changes-requested — defeating the whole validator.
  (let [dec (:review/decision re-review)]
    (and (contains? valid-decisions dec)
         (or (not (contains? rejection-decisions dec))
             (review-has-blocking? (get re-review :review/issues []))))))

(defn- recover-review-enumeration
  "Run ONE bounded enumeration-retry turn and return the re-parsed
   ReviewArtifact when the recovery is well-formed (enumerates blockers OR
   corrects to a non-rejection decision), or nil when recovery ALSO produced
   a malformed rejection — in which case the original raw rejection stands
   as-is."
  [llm-client base-opts on-chunk user-prompt prior-content]
  (let [retry-prompt (enumeration-retry-prompt user-prompt prior-content)
        retry-opts   (assoc base-opts :max-turns
                            (get @reviewer-prompt-data
                                 :prompt/enumeration-retry-max-turns 6))
        response     (if on-chunk
                       (llm/chat-stream llm-client retry-prompt on-chunk retry-opts)
                       (llm/chat llm-client retry-prompt retry-opts))
        normalized   (result-boundary/normalize-llm-result
                      {:response response :parse-response parse-review-response})
        re-review    (:parsed-content normalized)]
    (when (and re-review (well-formed-recovery? re-review))
      re-review)))

(defn create-reviewer
  "Create a Reviewer agent with optional configuration overrides.

   The Reviewer performs LLM-backed semantic code review plus deterministic
   gate validation. Falls back to gate-only review when no LLM backend
   is available.

   Options:
   - :gates       - Vector of gate implementations (default: syntax, lint, policy)
   - :strict      - If true, any gate failure causes rejection (default: false)
   - :logger      - Logger instance
   - :llm-backend - LLM client (if not provided, uses :llm-backend from context)
   - :config      - Agent configuration (model, temperature, etc.)

   Example:
     (create-reviewer)
     (create-reviewer {:llm-backend llm-client})
     (create-reviewer {:gates [(loop/syntax-gate)
                               (loop/lint-gate)]
                       :strict true})"
  [& [opts]]
  (let [logger (or (:logger opts)
                   (log/create-logger {:min-level :info :output (fn [_])}))
        default-gates [(loop/syntax-gate)
                       (implementation-handoff-gate)
                       (loop/lint-gate)
                       (loop/policy-gate :security {:policies [:no-secrets]})]
        gates (get opts :gates default-gates)
        review-config (->> (merge (role-config/agent-llm-default :reviewer)
                                  (:config opts))
                           (model/apply-default-model :reviewer))
        config {:strict (get opts :strict false)}]
    (specialized/create-base-agent
     {:role :reviewer
      :system-prompt @reviewer-system-prompt
      :config review-config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :reviewer
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              [artifact artifact-id] (extract-artifact-and-id input)
              start-time (System/currentTimeMillis)]

          ;; Phase lifecycle: mark review entry
          (enter-review logger {:artifact-id artifact-id
                                :gate-count (count gates)
                                :llm? (boolean llm-client)})

          (log/info logger :reviewer :reviewer/review-start
                    {:data {:artifact-id artifact-id
                            :gate-count (count gates)
                            :llm? (boolean llm-client)}})

          (if llm-client
            ;; LLM + gates review
            (let [user-prompt (build-review-prompt input)
                  monitor (create-reviewer-progress-monitor)
                  max-turns (get @reviewer-prompt-data
                                 :prompt/max-turns
                                 default-reviewer-max-turns)
                  ;; Mirror implementer's build-effective-system-prompt:
                  ;; append the phase-filtered standards addendum so the
                  ;; reviewer sees the rules it should be checking
                  ;; against. Empty string when no addendum is present
                  ;; (legacy callers / no rules apply to :review).
                  effective-system (str @reviewer-system-prompt
                                        (get input :task/behavior-addendum ""))
                  base-opts (cond-> {:system effective-system
                                     :max-turns max-turns}
                              monitor (assoc :progress-monitor monitor))
                  response (if on-chunk
                             (llm/chat-stream llm-client user-prompt on-chunk
                                              base-opts)
                             (llm/chat llm-client user-prompt base-opts))
                  normalized (result-boundary/normalize-llm-result
                              {:response response
                               :parse-response parse-review-response})
                  content (:content normalized)
                  tokens (:tokens normalized)
                  cost-usd (:cost-usd normalized)]

              (log/info logger :reviewer :reviewer/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})

              (let [;; Parse LLM review
                    llm-review (:parsed-content normalized)
                    parse-failure-message (review-failure-message response content)
                    parse-failed? (nil? llm-review)
                    ;; Run deterministic gates
                    gate-feedbacks (run-gates-on-artifact gates artifact context logger)
                    gate-result (make-review-decision gate-feedbacks config)
                    counts (calculate-gate-counts gate-feedbacks)
                    timeout-failure-message (backend-failure-message response llm-review)
                    timeout-only-review? (timeout-only-review? llm-review gate-result)
                    ;; "initial" = the first-call decision/issues fed into the
                    ;; enumeration validator. Named to contrast with
                    ;; `recovered-review` below; these are already normalized,
                    ;; not literally "raw".
                    initial-llm-decision (cond
                                           timeout-only-review? nil
                                           parse-failed? :rejected
                                           llm-review (normalize-llm-decision (:review/decision llm-review)))
                    ;; Resolve the task's scope once; partitioning happens
                    ;; below at both the initial-parse and enumeration-retry
                    ;; sites. nil means no filtering (legacy specs without
                    ;; :scope continue to review every file).
                    review-scope (effective-review-scope input)

                    initial-llm-issues-raw (get llm-review :review/issues [])
                    {initial-llm-issues          :in-scope
                     initial-issues-filtered-out :out-of-scope}
                    (partition-issues-by-scope review-scope initial-llm-issues-raw)

                    initial-llm-strengths (get llm-review :review/strengths [])
                    initial-llm-summary   (:review/summary llm-review)
                    initial-llm-out-of-scope (into
                                               (sanitize-review-issues
                                                 (get llm-review :review/out-of-scope-observations))
                                               initial-issues-filtered-out)
                    _ (when (seq initial-issues-filtered-out)
                        (log/info logger :reviewer
                                  :reviewer/scope-filter-applied
                                  {:data {:scope             review-scope
                                          :filtered-count    (count initial-issues-filtered-out)
                                          :kept-count        (count initial-llm-issues)
                                          :retry-path        :initial}}))

                    ;; ENUMERATION VALIDATOR: a rejection without enumerated
                    ;; :blocking findings is malformed — the implementer
                    ;; cannot act on it, and silent "rejected but listed
                    ;; nothing" reviews drove the 2026-05-27 dogfood
                    ;; review-redirect churn. Re-run the reviewer ONCE with an
                    ;; enumeration-retry prompt; use the recovered review iff
                    ;; it now lists blockers OR corrects to a non-rejection.
                    ;; Otherwise the initial rejection stands as-is.
                    recovered-review (when (enumeration-retry?
                                            initial-llm-decision initial-llm-issues
                                            (:blocking-issues gate-result))
                                       (log/info logger :reviewer
                                                 :reviewer/enumeration-retry
                                                 {:data {:initial-decision    initial-llm-decision
                                                         :initial-issue-count (count initial-llm-issues)
                                                         :gate-blocking-count (count (:blocking-issues gate-result))}})
                                       (recover-review-enumeration
                                        llm-client base-opts on-chunk
                                        user-prompt content))
                    ;; When recovery succeeds, the first call's parse failure
                    ;; no longer represents the agent's verdict — clearing
                    ;; this stops `all-blocking` from appending the parse-
                    ;; failure message on top of an otherwise-clean recovered
                    ;; review (could even falsely block a recovered
                    ;; :approved).
                    parse-failed? (and parse-failed? (nil? recovered-review))
                    llm-decision  (if recovered-review
                                    (normalize-llm-decision (:review/decision recovered-review))
                                    initial-llm-decision)
                    recovered-llm-issues-raw (when recovered-review
                                               (get recovered-review :review/issues []))
                    {recovered-llm-issues          :in-scope
                     recovered-issues-filtered-out :out-of-scope}
                    (partition-issues-by-scope review-scope (or recovered-llm-issues-raw []))
                    _ (when (and recovered-review
                                 (seq recovered-issues-filtered-out))
                        (log/info logger :reviewer
                                  :reviewer/scope-filter-applied
                                  {:data {:scope          review-scope
                                          :filtered-count (count recovered-issues-filtered-out)
                                          :kept-count     (count recovered-llm-issues)
                                          :retry-path     :recovered}}))
                    llm-issues    (if recovered-review
                                    recovered-llm-issues
                                    initial-llm-issues)
                    llm-strengths (if recovered-review
                                    (get recovered-review :review/strengths [])
                                    initial-llm-strengths)
                    llm-summary   (if recovered-review
                                    (:review/summary recovered-review)
                                    initial-llm-summary)
                    llm-out-of-scope (if recovered-review
                                       (into
                                         (sanitize-review-issues
                                           (get recovered-review :review/out-of-scope-observations))
                                         recovered-issues-filtered-out)
                                       initial-llm-out-of-scope)

                    ;; Merge decisions: gates can override LLM
                    final-decision (if llm-decision
                                     (merge-gate-overrides llm-decision (:decision gate-result) config)
                                     (:decision gate-result))

                    ;; Merge issues from both sources
                    all-blocking (cond-> (into (vec (:blocking-issues gate-result))
                                               (llm-issues->blocking-strings llm-issues))
                                   parse-failed?
                                   (conj parse-failure-message)
                                   timeout-only-review?
                                   (conj timeout-failure-message))
                    all-warnings (into (vec (:warnings gate-result))
                                       (llm-issues->warning-strings llm-issues))

                    ;; Merge recommendations
                    llm-recs (llm-issues->recommendations llm-issues)

                    ;; Build summary
                    summary (or llm-summary
                                (generate-summary final-decision gate-feedbacks))

                    review (cond-> (build-review-artifact
                                    gate-feedbacks final-decision all-blocking all-warnings
                                    artifact-id counts
                                    :issues llm-issues
                                    :strengths llm-strengths
                                    :summary summary
                                    :out-of-scope-observations llm-out-of-scope)
                             (seq llm-recs) (update :review/recommendations
                                                    (fn [existing] (into (or existing []) llm-recs))))

                    duration (- (System/currentTimeMillis) start-time)]

                (if timeout-only-review?
                  (timeout-only-error-result
                   logger normalized llm-review gate-result counts duration tokens cost-usd
                   timeout-failure-message)
                  (do
                    ;; Observability — when the deterministic gates flip
                    ;; the LLM's verdict, the operator needs to know
                    ;; which gate(s) caused it. Without this, the
                    ;; downstream workflow gate just fails opaquely.
                    ;; The 2026-05-18 agent-stream-watchdog dogfood
                    ;; surfaced LLM :approved → final :rejected with no
                    ;; signal in the event log about which internal gate
                    ;; produced the override.
                    (let [failing-gate-ids (->> gate-feedbacks
                                                (remove :passed?)
                                                (mapv :gate-id))
                          gate-overrode-llm? (and (some? llm-decision)
                                                  (not= llm-decision final-decision))]
                      (log/info logger :reviewer :reviewer/review-complete
                                {:data {:decision final-decision
                                        :llm-decision llm-decision
                                        :llm-parse-failed? parse-failed?
                                        :timeout-only-review? timeout-only-review?
                                        :gates-passed (:passed counts)
                                        :gates-failed (:failed counts)
                                        :failing-gate-ids failing-gate-ids
                                        :gate-overrode-llm? gate-overrode-llm?
                                        :llm-issues (count llm-issues)
                                        :duration-ms duration}})
                      (when gate-overrode-llm?
                        (log/warn logger :reviewer :reviewer/gate-overrode-llm
                                  {:data {:llm-decision llm-decision
                                          :final-decision final-decision
                                          :failing-gate-ids failing-gate-ids
                                          :artifact-id artifact-id}})))

                    ;; Phase lifecycle: mark review exit with decision
                    (leave-review logger {:review/decision final-decision
                                          :duration-ms duration
                                          :gates-passed (:passed counts)
                                          :gates-failed (:failed counts)
                                          :llm? true})

                    (build-review-result review counts duration tokens :cost-usd cost-usd)))))

            ;; No LLM — gate-only fallback
            (let [gate-feedbacks (run-gates-on-artifact gates artifact context logger)
                  {:keys [decision blocking-issues warnings]} (make-review-decision gate-feedbacks config)
                  counts (calculate-gate-counts gate-feedbacks)
                  review (build-review-artifact gate-feedbacks decision blocking-issues warnings artifact-id counts)
                  duration (- (System/currentTimeMillis) start-time)]

              (log/info logger :reviewer :reviewer/review-complete
                        {:data {:decision decision
                                :gates-passed (:passed counts)
                                :gates-failed (:failed counts)
                                :duration-ms duration
                                :mode :gate-only}})

              ;; Phase lifecycle: mark review exit with decision
              (leave-review logger {:review/decision decision
                                    :duration-ms duration
                                    :gates-passed (:passed counts)
                                    :gates-failed (:failed counts)
                                    :llm? false})

              (build-review-result review counts duration 0)))))

      :validate-fn validate-review-artifact

      :repair-fn repair-review-artifact})))

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

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a reviewer with default gates (gate-only mode)
  (def reviewer (create-reviewer))

  ;; Create a reviewer with LLM backend
  #_(def llm-reviewer (create-reviewer {:llm-backend llm-client}))

  ;; Create a reviewer with custom gates
  (def strict-reviewer (create-reviewer {:gates [(loop/syntax-gate)
                                                  (loop/lint-gate)
                                                  (loop/policy-gate :security {:policies [:no-secrets :no-todos]})]
                                         :strict true}))

  ;; Invoke via protocol (works because FunctionalAgent implements Agent)
  (require '[ai.miniforge.agent.interface :as agent])
  (agent/invoke reviewer
                {:task/description "Review this code"
                 :task/artifact {:code/id (random-uuid)
                                 :code/files [{:path "src/example.clj"
                                               :content "(ns example)\n(defn hello [] \"world\")"
                                               :action :create}]}}
                {})

  ;; Check review result (bind result from invoke call above)
  #_(approved? (:artifact result))
  #_(get-issues (:artifact result))
  #_(get-strengths (:artifact result))
  #_(get-recommendations (:artifact result))

  ;; Validate a review artifact
  (validate-review-artifact
   {:review/id (random-uuid)
    :review/decision :approved
    :review/gate-results []
    :review/summary "All checks passed"
    :review/gates-passed 3
    :review/gates-failed 0
    :review/gates-total 3})

  :leave-this-here)
