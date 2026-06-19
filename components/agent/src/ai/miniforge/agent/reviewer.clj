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
   [ai.miniforge.agent.artifact-session :as artifact-session]
   [ai.miniforge.agent.budget :as budget]
   [ai.miniforge.agent.context-budget :as context-budget]
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
   [ai.miniforge.event-stream.interface :as es]
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

(def ^:private reviewer-disallowed-tools
  "Native tools the reviewer MUST NOT call. Forces reads onto the MCP context
   cache (context_read/grep/glob) — same mechanism as planner/implementer/
   tester — and blocks ALL mutation: the reviewer inspects, it never writes.
   Read/Grep/Glob/LS/Agent → use the cached read tools; Write/Edit/MultiEdit/
   Bash → off entirely. With cached reads it can check reachability (callers,
   related files) without native exploration or modifying anything."
  ["Read" "Grep" "Glob" "LS" "Agent" "Bash" "Write" "Edit" "MultiEdit"])

(def ^:private default-context-window-reserve-tokens
  "Fallback when reviewer.edn omits :prompt/context-window-reserve-tokens.
   Headroom kept below the model's context window for the agent CLI's own
   unmeasured baseline (system prompt, tool schemas, host plugins/skills).
   Matches the planner default; authority is the EDN."
  50000)

(defn- assemble-review-within-budget
  "Reviewer-side N12 §5 budgeting — parallels the planner's
   `assemble-within-budget`. The artifact-under-review's eagerly-inlined
   file bodies are the unbounded contributor: an aggregated DAG integration
   task's full file contents + the reviewer's exploration + the standards
   addendum overflowed the 200k window → the CLI hard-errored 'Prompt is too
   long' and the review phase died. When the assembled prompt would
   overflow, shed those bodies to a manifest the reviewer fetches on demand
   via `context_read` (read-only session, native Read/Grep disallowed; the
   context server reads through to the worktree on a cache miss). The
   standards addendum lives in `effective-system` and is counted toward the
   estimate but never trimmed here — bailing pre-flight is preferred to
   silently dropping enforcement rules. Delegates the ladder to
   `context-budget/assemble-within-budget`."
  [input effective-system model reserve]
  (let [artifact (or (:task/artifact input) input)]
    (context-budget/assemble-within-budget
     {:effective-system effective-system
      :model            model
      :reserve          reserve
      :build-full       #(reviewer-prompts/build-review-prompt input)
      :build-shed       #(reviewer-prompts/build-review-prompt
                          input reviewer-prompts/format-artifact-manifest)
      :shed-count       (count (:code/files artifact))})))

(defn- emit-prompt-size!
  "Emit an :agent/prompt-size workflow event recording the reviewer's
   pre-flight budget (N12 §3) so estimate-vs-window is visible/queryable.
   Mirrors the planner emitter. Safe no-op without an event stream."
  [context budget]
  (when-let [stream (or (:event-stream context) (:execution/event-stream context))]
    (when-let [wf-id (or (:execution/id context) (:workflow/id context))]
      (try
        (es/publish!
         stream
         (-> (es/create-envelope stream :agent/prompt-size wf-id
                                 (str "reviewer prompt ~" (:est-full budget)
                                      " est tokens / window " (:window budget)
                                      (when (:shed? budget) " (shed to manifest)")))
             (assoc :agent/id :reviewer
                    :prompt/estimated-input-tokens (:est-full budget)
                    :prompt/context-window (:window budget)
                    :prompt/reserve (:reserve budget)
                    :prompt/reserve-clamped? (:reserve-clamped? budget)
                    :prompt/effective-window (:effective-window budget)
                    :prompt/shed? (:shed? budget)
                    :prompt/estimated-after-shed (:est-final budget)
                    :prompt/file-count (:file-count budget))))
        (catch Exception _ nil)))))

(defn- invoke-reviewer-session
  "Read-only session body for the reviewer: force the MCP-read tools, build the
   chat opts (base-opts + session MCP opts + the disallow-list + worktree cwd),
   and call the LLM. Returns the LLM response. Run via `with-readonly-session`
   so reads go through the context cache and nothing is written."
  [session llm-client user-prompt on-chunk base-opts budget-usd max-turns]
  (artifact-session/write-cursor-permissions-for-session! session reviewer-disallowed-tools)
  (let [opts (cond-> (merge base-opts
                            (assoc (artifact-session/session->mcp-opts session budget-usd max-turns)
                                   :disallowed-tools reviewer-disallowed-tools))
               ;; Thread the worktree cwd like the other roles — CWD-dependent
               ;; backends (e.g. Cursor's project-scoped .cursor/*) need it to
               ;; read the session's permission allowlist and the right repo root.
               (:workdir session) (assoc :workdir (:workdir session)))]
    (if on-chunk
      (llm/chat-stream llm-client user-prompt on-chunk opts)
      (llm/chat llm-client user-prompt opts))))

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
            (let [monitor (reviewer-prompts/create-reviewer-progress-monitor)
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
                  ;; N12 §5: assemble the user-prompt within the model's
                  ;; context window, shedding the artifact's inlined file
                  ;; bodies to a context_read-able manifest before the prompt
                  ;; overflows. Without this the reviewer balloons past 200k
                  ;; on aggregated DAG integration tasks (full file contents +
                  ;; standards addendum) → CLI 'Prompt is too long' kills the
                  ;; review phase.
                  prompt-budget (assemble-review-within-budget
                                 input effective-system (:model review-config)
                                 (get @reviewer-prompts/reviewer-prompt-data
                                      :prompt/context-window-reserve-tokens
                                      default-context-window-reserve-tokens))
                  user-prompt (:user-prompt prompt-budget)
                  _ (emit-prompt-size! context prompt-budget)
                  _ (when (:shed? prompt-budget)
                      (log/info logger :reviewer :reviewer/context-shed
                                {:data {:file-count (:file-count prompt-budget)
                                        :window (:window prompt-budget)
                                        :estimated-tokens-before (:est-full prompt-budget)
                                        :estimated-tokens-after (:est-final prompt-budget)}}))
                  ;; Irreducible overflow: the prompt exceeds the window even
                  ;; with the artifact shed to a manifest (only reachable on a
                  ;; tiny-window model — production 200k windows fit the shed
                  ;; form). Skip the doomed LLM call, but STILL run the
                  ;; deterministic policy gates below and return an infra
                  ;; failure. The compiled policy gates are the trust boundary
                  ;; and MUST always apply; the LLM's semantic pass is the only
                  ;; thing dropped here, and a 'prompt too long' error must
                  ;; never be synthesized into a false :rejected. N12 §5.4-5.5.
                  context-overflow? (:over-after-shed? prompt-budget)
                  overflow-message (when context-overflow?
                                     (str "Reviewer prompt ~" (:est-final prompt-budget)
                                          " est tokens exceeds the model context window "
                                          (:window prompt-budget)
                                          (if (:shed? prompt-budget)
                                            " even after shedding the artifact's file bodies to a manifest"
                                            " and has no inlined file bodies to shed")
                                          "; LLM semantic review skipped, deterministic gates still applied"))
                  base-opts (cond-> {:system effective-system
                                     :max-turns max-turns}
                              monitor (assoc :progress-monitor monitor))
                  budget-usd (budget/resolve-cost-budget-usd :reviewer review-config context)
                  ;; Run the main review inside a read-only artifact session so
                  ;; the reviewer reads through the MCP context cache
                  ;; (context_read/grep/glob) instead of native tools — same
                  ;; mechanism as planner/implementer/tester. Read-only (no
                  ;; Write/Edit/Bash) so it can cheaply check reachability via
                  ;; the cache without modifying anything. with-readonly-session
                  ;; skips artifact promotion/WARN (the reviewer has no work
                  ;; product) and returns the LLM result directly. The
                  ;; enumeration-retry below keeps base-opts — it re-asks over
                  ;; the same content and needs no tools/cache.
                  ;; Skip the LLM call entirely on irreducible overflow — the
                  ;; synthesized error `normalized` below routes to the
                  ;; context-overflow infra exit; the gates still run.
                  response (when-not context-overflow?
                             (artifact-session/with-readonly-session
                              context
                              #(invoke-reviewer-session % llm-client user-prompt on-chunk
                                                        base-opts budget-usd max-turns)))
                  normalized (if context-overflow?
                               {:llm-error {:message overflow-message
                                            :data {:context-overflow true
                                                   :estimated-tokens (:est-final prompt-budget)
                                                   :context-window (:window prompt-budget)}}
                                :content nil :parsed-content nil :tokens 0 :cost-usd nil}
                               (result-boundary/normalize-llm-result
                                {:response response
                                 :parse-response llm-response/parse-review-response}))
                  content (:content normalized)
                  tokens (:tokens normalized)
                  cost-usd (:cost-usd normalized)]

              (log/info logger :reviewer :reviewer/llm-called
                        {:data {:success (boolean (and response (llm/success? response)))
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
                    ;; `timeout-only-review?` only fires when the LLM
                    ;; parsed a `:rejected` whose `:review/blocking-
                    ;; issues` are all timeout-shaped — it cannot fire
                    ;; when the LLM call itself errored, because
                    ;; `llm-review` is nil and every condition in the
                    ;; predicate reads from it. The 2026-06-05 dogfood
                    ;; (workflow adhoc-944448986) reviewer stream-idle'd
                    ;; at 360s with no parsed response; the framework
                    ;; promoted the timeout text into `:review/blocking-
                    ;; issues` via the parse-failed branch and synthesized
                    ;; a false `:rejected`. The boundary-level check below
                    ;; covers that path so a real infra timeout takes the
                    ;; `timeout-only-error-result` exit instead of
                    ;; masquerading as a code-review rejection.
                    ;;
                    ;; ANY adaptive LLM timeout (stream-idle / stagnation /
                    ;; hard-limit) means the reviewer produced no verdict — route
                    ;; to the backend-timeout (infra) path, not a synthesized
                    ;; :rejected. The 2026-06-05 fix only covered :stream-idle; a
                    ;; 2026-06-14 dogfood (rn-03) stagnation-timed-out and was
                    ;; promoted into a false :rejected via the parse-failed
                    ;; branch, redirecting implement to "fix" a non-rejection.
                    backend-adaptive-timeout? (result-boundary/backend-timeout-error? normalized)
                    backend-timeout? (or timeout-only-review? backend-adaptive-timeout?)
                    ;; "initial" = the first-call decision/issues fed into the
                    ;; enumeration validator. Named to contrast with
                    ;; `recovered-review` below; these are already normalized,
                    ;; not literally "raw".
                    initial-llm-decision (cond
                                           (or backend-timeout? context-overflow?) nil
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

                    ;; Merge issues from both sources. `parse-failed?`
                    ;; gates the parse-failure-message append so a
                    ;; backend-timeout (taking the early exit below)
                    ;; does NOT carry the timeout text into the
                    ;; artifact's blocking-issues — it would otherwise
                    ;; mislead downstream consumers into treating an
                    ;; infra timeout as a code-review defect, the exact
                    ;; pathology the 2026-06-05 dogfood revealed.
                    all-blocking (cond-> (into (vec (:blocking-issues gate-result))
                                               (issues/llm-issues->blocking-strings llm-issues))
                                   (and parse-failed? (not backend-timeout?) (not context-overflow?))
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

                (cond
                  context-overflow?
                  (artifact/context-overflow-error-result
                   logger normalized gate-result counts duration overflow-message)

                  backend-timeout?
                  (artifact/timeout-only-error-result
                   logger normalized llm-review gate-result counts duration tokens cost-usd
                   timeout-failure-message)

                  :else
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
                                        :backend-adaptive-timeout? backend-adaptive-timeout?
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
