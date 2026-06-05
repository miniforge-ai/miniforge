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
   [ai.miniforge.agent.reviewer.gates :as gates]
   [ai.miniforge.agent.reviewer.issues :as issues]
   [ai.miniforge.agent.reviewer.scope :as scope]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [malli.core :as m]))

;; Schemas (GateFeedback, ReviewIssue, ReviewArtifact) moved to
;; ai.miniforge.agent.reviewer.issues (PR-C decomposition).

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

;; Gate plumbing, error classification, deterministic decision, summary,
;; and gate counts moved to ai.miniforge.agent.reviewer.gates (PR-D
;; decomposition). reviewer.clj orchestrates them via the `gates/` alias.

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

;; Scope resolution + partitioning moved to ai.miniforge.agent.reviewer.scope
;; (PR #1039 decomposition). The reviewer module references the helpers
;; through the `scope` alias.

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
         (when (seq title)
           (str "## Task: " title "\n\n"))
         (when (seq description)
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

;; Issue-shape validation / sanitization / decision normalization / issue-text
;; extraction moved to ai.miniforge.agent.reviewer.issues (PR-C decomposition).

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

;; calculate-gate-counts moved to reviewer.gates (PR-D).

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

;; merge-gate-overrides moved to reviewer.gates (PR-D).

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

;; Decision enums + `review-has-blocking?` moved to
;; ai.miniforge.agent.reviewer.issues (PR-C decomposition).

(defn- enumeration-retry?
  "True when the LLM's review decision is a rejection but it enumerated NO
   :blocking findings AND the deterministic gates have no blocking issues
   either — a malformed rejection the implementer cannot act on. The validator
   rejects this review and demands a re-enumeration."
  [llm-decision llm-issues gate-blocking]
  (and (contains? issues/rejection-decisions llm-decision)
       (not (issues/review-has-blocking? llm-issues))
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
    (and (contains? issues/valid-decisions dec)
         (or (not (contains? issues/rejection-decisions dec))
             (issues/review-has-blocking? (get re-review :review/issues []))))))

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
                       (gates/implementation-handoff-gate)
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
                    gate-feedbacks (gates/run-gates-on-artifact gates artifact context logger)
                    gate-result (gates/make-review-decision gate-feedbacks config)
                    counts (gates/calculate-gate-counts gate-feedbacks)
                    timeout-failure-message (backend-failure-message response llm-review)
                    timeout-only-review? (timeout-only-review? llm-review gate-result)
                    ;; "initial" = the first-call decision/issues fed into the
                    ;; enumeration validator. Named to contrast with
                    ;; `recovered-review` below; these are already normalized,
                    ;; not literally "raw".
                    initial-llm-decision (cond
                                           timeout-only-review? nil
                                           parse-failed? :rejected
                                           llm-review (issues/normalize-llm-decision (:review/decision llm-review)))
                    ;; Resolve the task's scope once; partitioning happens
                    ;; below at both the initial-parse and enumeration-retry
                    ;; sites. Required — `effective-review-scope` raises if
                    ;; no scope can be resolved (PR #1039).
                    review-scope (scope/effective-review-scope input)

                    initial-llm-issues-raw (get llm-review :review/issues [])
                    {initial-llm-issues          :in-scope
                     initial-issues-filtered-out :out-of-scope}
                    (scope/partition-issues-by-scope review-scope initial-llm-issues-raw)

                    initial-llm-strengths (get llm-review :review/strengths [])
                    initial-llm-summary   (:review/summary llm-review)
                    initial-llm-out-of-scope (into
                                               (issues/sanitize-review-issues
                                                 (get llm-review :review/out-of-scope-observations))
                                               initial-issues-filtered-out)
                    _ (when (seq initial-issues-filtered-out)
                        (log/info logger :reviewer
                                  :reviewer/scope-filter-applied
                                  (scope/filter-applied-event
                                    {:scope          review-scope
                                     :filtered-count (count initial-issues-filtered-out)
                                     :kept-count     (count initial-llm-issues)
                                     :retry-path     :initial})))

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
                                    (issues/normalize-llm-decision (:review/decision recovered-review))
                                    initial-llm-decision)
                    recovered-llm-issues-raw (when recovered-review
                                               (get recovered-review :review/issues []))
                    {recovered-llm-issues          :in-scope
                     recovered-issues-filtered-out :out-of-scope}
                    (scope/partition-issues-by-scope review-scope (or recovered-llm-issues-raw []))
                    _ (when (and recovered-review
                                 (seq recovered-issues-filtered-out))
                        (log/info logger :reviewer
                                  :reviewer/scope-filter-applied
                                  (scope/filter-applied-event
                                    {:scope          review-scope
                                     :filtered-count (count recovered-issues-filtered-out)
                                     :kept-count     (count recovered-llm-issues)
                                     :retry-path     :recovered})))
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
                                         (issues/sanitize-review-issues
                                           (get recovered-review :review/out-of-scope-observations))
                                         recovered-issues-filtered-out)
                                       initial-llm-out-of-scope)

                    ;; Merge decisions: gates can override LLM
                    final-decision (if llm-decision
                                     (gates/merge-gate-overrides llm-decision (:decision gate-result) config)
                                     (:decision gate-result))

                    ;; Merge issues from both sources
                    all-blocking (cond-> (into (vec (:blocking-issues gate-result))
                                               (issues/llm-issues->blocking-strings llm-issues))
                                   parse-failed?
                                   (conj parse-failure-message)
                                   timeout-only-review?
                                   (conj timeout-failure-message))
                    all-warnings (into (vec (:warnings gate-result))
                                       (issues/llm-issues->warning-strings llm-issues))

                    ;; Merge recommendations
                    llm-recs (llm-issues->recommendations llm-issues)

                    ;; Build summary
                    summary (or llm-summary
                                (gates/generate-summary final-decision gate-feedbacks))

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
            (let [gate-feedbacks (gates/run-gates-on-artifact gates artifact context logger)
                  {:keys [decision blocking-issues warnings]} (gates/make-review-decision gate-feedbacks config)
                  counts (gates/calculate-gate-counts gate-feedbacks)
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
