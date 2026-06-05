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
   [ai.miniforge.agent.result-boundary :as result-boundary]
   [ai.miniforge.agent.reviewer.artifact :as artifact]
   [ai.miniforge.agent.reviewer.gates :as gates]
   [ai.miniforge.agent.reviewer.issues :as issues]
   [ai.miniforge.agent.reviewer.llm-response :as llm-response]
   [ai.miniforge.agent.reviewer.prompts :as reviewer-prompts]
   [ai.miniforge.agent.reviewer.scope :as scope]
   [ai.miniforge.agent.role-config :as role-config]
   [ai.miniforge.agent.specialized :as specialized]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]))

;; Schemas (GateFeedback, ReviewIssue, ReviewArtifact) moved to
;; ai.miniforge.agent.reviewer.issues (PR-C decomposition).

;; Prompt loading, progress monitor, and per-call prompt assembly
;; (build-review-prompt, format-artifact-for-review, enumeration-retry-prompt)
;; moved to ai.miniforge.agent.reviewer.prompts (PR-E decomposition).

;; Gate plumbing, error classification, deterministic decision, summary,
;; and gate counts moved to ai.miniforge.agent.reviewer.gates (PR-D
;; decomposition). reviewer.clj orchestrates them via the `gates/` alias.

;------------------------------------------------------------------------------ Layer 2
;; LLM review: response parsing

;; build-review-prompt + format-artifact-for-review moved to
;; ai.miniforge.agent.reviewer.prompts (PR-E decomposition).

;; Scope resolution + partitioning moved to ai.miniforge.agent.reviewer.scope
;; (PR #1039 decomposition).

;; Issue-shape validation / sanitization / decision normalization / issue-text
;; extraction moved to ai.miniforge.agent.reviewer.issues (PR-C decomposition).

;; LLM-response parsing, failure-message resolution, backend-timeout
;; detection, and enumeration-retry validator/recovery moved to
;; ai.miniforge.agent.reviewer.llm-response (PR-F decomposition).
;; llm-issues->recommendations moved to reviewer.issues (PR-F) — same
;; shape as its blocking/warning siblings already there.

;; Artifact extraction, builders, validators, lifecycle telemetry, and the
;; backend-timeout-only error exit moved to ai.miniforge.agent.reviewer.artifact
;; (PR-G decomposition).

;------------------------------------------------------------------------------ Layer 5
;; Agent creation

;; Enumeration-retry validator + well-formed-recovery? +
;; recover-review-enumeration moved to ai.miniforge.agent.reviewer.llm-response
;; (PR-F decomposition).

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
      :system-prompt @reviewer-prompts/reviewer-system-prompt
      :config review-config
      :logger logger

      :invoke-fn
      (fn [context input]
        (let [llm-client (model/resolve-llm-client-for-role
                          :reviewer
                          (get opts :llm-backend (:llm-backend context)))
              on-chunk (:on-chunk context)
              [artifact artifact-id] (artifact/extract-artifact-and-id input)
              start-time (System/currentTimeMillis)]

          ;; Phase lifecycle: mark review entry
          (artifact/enter-review logger {:artifact-id artifact-id
                                :gate-count (count gates)
                                :llm? (boolean llm-client)})

          (log/info logger :reviewer :reviewer/review-start
                    {:data {:artifact-id artifact-id
                            :gate-count (count gates)
                            :llm? (boolean llm-client)}})

          (if llm-client
            ;; LLM + gates review
            (let [user-prompt (reviewer-prompts/build-review-prompt input)
                  monitor (reviewer-prompts/create-reviewer-progress-monitor)
                  max-turns (get @reviewer-prompts/reviewer-prompt-data
                                 :prompt/max-turns
                                 reviewer-prompts/default-reviewer-max-turns)
                  ;; Mirror implementer's build-effective-system-prompt:
                  ;; append the phase-filtered standards addendum so the
                  ;; reviewer sees the rules it should be checking
                  ;; against. Empty string when no addendum is present
                  ;; (legacy callers / no rules apply to :review).
                  effective-system (str @reviewer-prompts/reviewer-system-prompt
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
                               :parse-response llm-response/parse-review-response})
                  content (:content normalized)
                  tokens (:tokens normalized)
                  cost-usd (:cost-usd normalized)]

              (log/info logger :reviewer :reviewer/llm-called
                        {:data {:success (llm/success? response)
                                :tokens tokens
                                :streaming? (boolean on-chunk)}})

              (let [;; Parse LLM review
                    llm-review (:parsed-content normalized)
                    parse-failure-message (llm-response/review-failure-message response content)
                    parse-failed? (nil? llm-review)
                    ;; Run deterministic gates
                    gate-feedbacks (gates/run-gates-on-artifact gates artifact context logger)
                    gate-result (gates/make-review-decision gate-feedbacks config)
                    counts (gates/calculate-gate-counts gate-feedbacks)
                    timeout-failure-message (llm-response/backend-failure-message response llm-review)
                    timeout-only-review? (llm-response/timeout-only-review? llm-review gate-result)
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
                    recovered-review (when (llm-response/enumeration-retry?
                                            initial-llm-decision initial-llm-issues
                                            (:blocking-issues gate-result))
                                       (log/info logger :reviewer
                                                 :reviewer/enumeration-retry
                                                 {:data {:initial-decision    initial-llm-decision
                                                         :initial-issue-count (count initial-llm-issues)
                                                         :gate-blocking-count (count (:blocking-issues gate-result))}})
                                       (llm-response/recover-review-enumeration
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
                    llm-recs (issues/llm-issues->recommendations llm-issues)

                    ;; Build summary
                    summary (or llm-summary
                                (gates/generate-summary final-decision gate-feedbacks))

                    review (cond-> (artifact/build-review-artifact
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
                  (artifact/timeout-only-error-result
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
                    (artifact/leave-review logger {:review/decision final-decision
                                          :duration-ms duration
                                          :gates-passed (:passed counts)
                                          :gates-failed (:failed counts)
                                          :llm? true})

                    (artifact/build-review-result review counts duration tokens :cost-usd cost-usd)))))

            ;; No LLM — gate-only fallback
            (let [gate-feedbacks (gates/run-gates-on-artifact gates artifact context logger)
                  {:keys [decision blocking-issues warnings]} (gates/make-review-decision gate-feedbacks config)
                  counts (gates/calculate-gate-counts gate-feedbacks)
                  review (artifact/build-review-artifact gate-feedbacks decision blocking-issues warnings artifact-id counts)
                  duration (- (System/currentTimeMillis) start-time)]

              (log/info logger :reviewer :reviewer/review-complete
                        {:data {:decision decision
                                :gates-passed (:passed counts)
                                :gates-failed (:failed counts)
                                :duration-ms duration
                                :mode :gate-only}})

              ;; Phase lifecycle: mark review exit with decision
              (artifact/leave-review logger {:review/decision decision
                                    :duration-ms duration
                                    :gates-passed (:passed counts)
                                    :gates-failed (:failed counts)
                                    :llm? false})

              (artifact/build-review-result review counts duration 0)))))

      :validate-fn artifact/validate-review-artifact

      :repair-fn artifact/repair-review-artifact})))

;; Public-API accessors (review-summary, approved?, rejected?,
;; conditionally-approved?, changes-requested?, get-blocking-issues,
;; get-warnings, get-recommendations, get-issues, get-strengths) moved to
;; ai.miniforge.agent.reviewer.artifact (PR-G decomposition).
;; Downstream consumers reach them via agent.interface.specialized.

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
  (artifact/validate-review-artifact
   {:review/id (random-uuid)
    :review/decision :approved
    :review/gate-results []
    :review/summary "All checks passed"
    :review/gates-passed 3
    :review/gates-failed 0
    :review/gates-total 3})

  :leave-this-here)
