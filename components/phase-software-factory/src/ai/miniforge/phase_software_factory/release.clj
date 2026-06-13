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

(ns ai.miniforge.phase-software-factory.release
  "Release phase interceptor.

   Prepares release artifacts and creates PRs using the release-executor component.
   All git/gh operations route through the DAG executor so governed-mode capsules
   never shell out to the host. The GitHub token is resolved and injected into the
   capsule as GH_TOKEN for gh CLI authentication.
   Agent: :releaser
   Default gates: [:release-ready]"
  (:require [clojure.java.io :as io]
            [ai.miniforge.workspace.interface :as workspace]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [ai.miniforge.agent.interface :as agent]
            [ai.miniforge.logging.interface :as log]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [ai.miniforge.phase-software-factory.phase-config :as phase-config]
            [ai.miniforge.phase-software-factory.phase-terminal :as phase-terminal]
            [ai.miniforge.release-executor.interface :as release-executor]
            [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def default-config
  "Phase defaults loaded from config/phase/defaults.edn."
  (phase-config/defaults-for :release))

;; Register defaults on load
(phase/register-phase-defaults! :release default-config)

;------------------------------------------------------------------------------ Layer 1
;; Interceptor implementation

(defn- log-already-implemented!
  "Log when implement phase reports work already done."
  [ctx impl-status]
  (when (= :already-implemented impl-status)
    (let [logger (or (get-in ctx [:execution/logger])
                     (log/create-logger {:min-level :info :output :human}))]
      (log/info logger :release :release/skipped-already-implemented
                {:data {:summary (get-in ctx [:execution/phase-results :implement :result :summary])}}))))

;; assert-implement-artifact! used to throw when the implement phase
;; reported anything other than :status :success. That gate disagreed with
;; verify and review, which read the environment directly and run regardless
;; of what the implement result map said. The disagreement surfaced under
;; the curator's :curator/no-files-written path: the agent narrates code in
;; chat, curator marks implement :failed, but persist still captures whatever
;; landed on disk and verify+review work fine against it. Release would
;; then explode with `:release/no-implement-artifact` even though there was
;; real work to release. Removed — the env-based `:release/zero-files`
;; check below is the authoritative gate, matching what verify and review
;; already do.

(defn- ctx-worktree-path
  "Resolve the working directory from phase context."
  [ctx]
  (workspace/resolve-execution-workdir ctx "release"))

(defn- git-dirty-files
  "Scan git working tree for new/modified/deleted files; return as :code/files entries.
   Used as fallback when the implement phase wrote files directly to disk
   (design docs, specs, UX assets, etc.) rather than returning them in a
   :code/files artifact.

   Filters out non-substantive paths (session markers, miniforge runtime
   artifacts) via `agent/substantive-file?` — a diff consisting only of
   these must not count as releasable work. Matches the same filter the
   curator applies upstream (iter-23 regression: empty-diff PRs)."
  [root-path]
  (try
    (let [{:keys [out]} (shell/sh "git" "status" "--porcelain" "-uall" :dir root-path)
          lines (->> (str/split-lines (or out "")) (remove str/blank?))]
      (->> lines
           (keep (fn [line]
                   (let [xy   (subs line 0 2)
                         path (str/trim (subs line 3))
                         file (io/file root-path path)]
                     (cond
                       (str/includes? xy "D") {:path path :content "" :action :delete}
                       (.isFile file)         {:path path
                                               :content (slurp file)
                                               :action (if (str/starts-with? (str/trim xy) "?")
                                                         :create
                                                         :modify)}))))
           (filter agent/substantive-file?)
           vec))
    (catch Exception _ [])))

(defn- git-dirty-files-capsule
  "Scan git working tree inside a task capsule via execute-fn (N11 §9.3).
   Used for K8s executors where the filesystem is not locally accessible.
   execute-fn is dag-exec/execute! passed through context to avoid cross-component requires.

   Applies the same `agent/substantive-file?` filter as the local variant so
   session markers and miniforge runtime artifacts don't slip through the
   governed path (iter-23 empty-diff regression)."
  [execute-fn executor env-id root-path]
  (try
    (let [result (execute-fn executor env-id "git status --porcelain -uall" {:workdir root-path})
          out    (get-in result [:data :stdout] "")
          lines  (->> (str/split-lines out) (remove str/blank?))
          path-of (fn [line] (str/trim (subs line 3)))
          substantive-line? (fn [line]
                              (and (>= (count line) 3)
                                   (agent/substantive-file? {:path (path-of line)})))]
      (->> lines
           (filter substantive-line?)
           (keep (fn [line]
                   (let [status (str/trim (subs line 0 2))
                         path   (path-of line)]
                     (cond
                       (str/includes? status "D")
                       {:path path :content "" :action :delete}

                       :else
                       ;; Read file content via executor
                       (let [cat-result (execute-fn executor env-id (str "cat " root-path "/" path)
                                                    {:workdir root-path})
                             content (get-in cat-result [:data :stdout] "")]
                         {:path    path
                          :content content
                          :action  (if (= "??" status) :create :modify)})))))
           vec))
    (catch Exception _ [])))

(defn- rehydrate-committed-files
  "Read content for the `:code/file-paths` recorded by the implement
   phase. Used when implement's boundary commit has cleaned the
   worktree — `git-dirty-files` then returns empty even though the
   files exist as committed content on the task branch.

   Modeled after review.clj/rehydrate-from-paths but with two
   differences:
   - Returns a `:code/files` vector directly (review wraps it back
     into the outer artifact); release wants the vector to feed
     into `code-artifacts` below.
   - Applies the same `agent/substantive-file?` filter that
     `git-dirty-files` uses, so a record consisting only of
     `.miniforge/session-id` does not count as releasable work
     (matches the iter-23 empty-diff PR guard).

   Host-mode only: `agent/rehydrate-files` reads the host
   filesystem. In governed (capsule) mode the worktree lives
   inside a Docker/K8s container and the host cannot reach it
   directly. A capsule-aware variant is a follow-up; today the
   capsule path falls through to `git-dirty-files-capsule`, which
   matches the pre-fix behavior in that mode."
  [implement-artifact worktree-path]
  (when-let [paths (and implement-artifact
                        worktree-path
                        (seq (:code/file-paths implement-artifact)))]
    (let [files (try
                  (agent/rehydrate-files worktree-path paths
                                         (:code/file-actions implement-artifact))
                  (catch Exception _ nil))]
      (->> (or files [])
           (filter agent/substantive-file?)
           vec))))

(defn build-workflow-state
  "Build workflow state from phase context for the release executor.

   In the new environment model, code changes live in the execution
   environment's git working tree (:execution/worktree-path) rather than
   being serialized into phase results.

   File-discovery resolution order:

     1. `:code/file-paths` recorded on the implement phase artifact
        (`lightweight-curated-artifact`). After implement's boundary
        commit lands on the task branch the worktree is clean at HEAD,
        so `git-dirty-files` returns empty even though the files exist
        as committed content. Reading the recorded paths off disk is
        the same pattern review.clj/rehydrate-from-paths uses.
     2. `git-dirty-files` (or its capsule-aware twin) for files the
        agent wrote to the worktree but the boundary did not commit
        (legacy / partial-write path).
     3. Throw :release/zero-files when both paths come back empty —
        the only legitimate \"nothing to release\" condition.

   Stage-3 dogfood (2026-05-07) tripped on the missing #1 path:
   plan/implement/verify/review all green, files committed on the
   task branch, but release threw zero-files because git-dirty-files
   saw a clean worktree. This commit closes that gap."
  [ctx]
  (let [impl-result   (get-in ctx [:execution/phase-results :implement :result])
        impl-artifact (get-in ctx [:execution/phase-results :implement :artifact])
        impl-status   (:status impl-result)]
    (log-already-implemented! ctx impl-status)
    (let [worktree-path  (ctx-worktree-path ctx)
          governed?      (= :governed (get ctx :execution/mode))
          execute-fn     (get ctx :execution/execute-fn)
          executor       (get ctx :execution/executor)
          env-id         (get ctx :execution/environment-id)
          committed      (rehydrate-committed-files impl-artifact worktree-path)
          dirty          (when (empty? committed)
                           (if (and governed? execute-fn executor env-id)
                             (git-dirty-files-capsule execute-fn executor env-id worktree-path)
                             (git-dirty-files worktree-path)))
          files          (or (not-empty committed)
                             (not-empty dirty)
                             (throw (ex-info (messages/t :release/zero-files)
                                             {:type           :release/zero-files
                                              :phase          :release
                                              :environment-id (:environment-id impl-result)
                                              :worktree-path  worktree-path})))
          code-artifacts [{:artifact/type    :code
                           :artifact/content {:code/id        (random-uuid)
                                              :code/language  "mixed"
                                              :code/files     files
                                              :code/summary   (get impl-result :summary
                                                                   (messages/t :release/changes-description))
                                              :environment-id (:environment-id impl-result)}}]
          input          (get-in ctx [:execution/input])]
      {:workflow/id       (or (get-in ctx [:execution/id]) (random-uuid))
       :workflow/phase    :release
       :workflow/spec     {:spec/description (or (:description input)
                                                 (:title input)
                                                 (messages/t :default/task-description))}
       :workflow/artifacts code-artifacts})))

(defn- resolve-github-token
  "Resolve GitHub token for capsule PR operations.
   Mirrors the resolution order from docker.clj's resolve-git-token:
   1. Execution context (passed by task runner)
   2. MINIFORGE_GIT_TOKEN env var (universal override)
   3. GH_TOKEN env var (GitHub-specific)
   4. `gh auth token` CLI fallback (host-side only, pre-capsule)"
  [ctx]
  (or (get-in ctx [:execution/opts :github-token])
      (get-in ctx [:execution/github-token])
      (System/getenv "MINIFORGE_GIT_TOKEN")
      (System/getenv "GH_TOKEN")
      (try (let [r (shell/sh "gh" "auth" "token")]
             (when (zero? (:exit r))
               (str/trim (:out r))))
           (catch Exception _ nil))))

(defn build-executor-context
  "Build context for the release executor from phase context.
   Includes :github-token so the release executor can inject GH_TOKEN
   into the capsule's environment for gh CLI authentication.

   The phase-local `logger` is threaded in so the release executor's
   log calls (phase-started / fail / phase-completed) are emitted
   even when the workflow ctx lacks :execution/logger — observed in
   the 2026-05-03 dogfood, where release failed in 0ms with zero
   visible log lines because the executor's logger was nil.

   Also computes the phase-filtered policy-pack behavior addendum via
   `phase/load-and-filter-behaviors :release` and threads it as
   `:task/behavior-addendum` so `release-executor/invoke-releaser` can
   forward it into the agent input. Mirrors the wiring review (#945)
   and plan use; closes the gap where the releaser ran without the
   compiled standards pack."
  [ctx config logger]
  (let [on-chunk (phase/create-streaming-callback ctx :release)
        input (get-in ctx [:execution/input])
        behavior-addendum (phase/load-and-filter-behaviors
                            :release {:task {:task/intent (:intent input)}})]
    (cond-> {;; Preserve the original precedence — explicit ctx worktree paths
             ;; win over config; the blessed resolver is the LAST resort,
             ;; adding the opts key + the governed-fail / CWD decision (no
             ;; silent user.dir downgrade) in place of the old bare fallback.
             :worktree-path (or (get-in ctx [:execution/worktree-path])
                                (get-in ctx [:worktree-path])
                                (get-in config [:worktree-path])
                                (workspace/resolve-execution-workdir ctx "release"))
             :executor (get-in ctx [:execution/executor])
             :environment-id (get-in ctx [:execution/environment-id])
             :logger (or (get-in ctx [:execution/logger]) logger)
             :llm-backend (get-in ctx [:execution/llm-backend])
             :artifact-store (get-in ctx [:execution/artifact-store])
             :event-stream (:event-stream ctx)
             ;; Allow disabling PR creation via config or execution opts
             :create-pr? (or (get-in ctx [:execution/opts :create-pr?])
                             (get config :create-pr? true))
             ;; PR base override for a dependency-chained DAG task: the DAG
             ;; orchestrator passes the parent task's branch so this task's PR
             ;; STACKS on the parent (base = parent branch) instead of opening
             ;; against the default branch with a cumulative diff. Absent (root
             ;; tasks / non-DAG runs) → executor falls back to the default branch.
             :base-branch (get-in ctx [:execution/opts :release/base-branch])
             ;; PR provenance → rendered as YAML frontmatter on the PR body so
             ;; a PR maps deterministically back to its workflow run + spec.
             ;; :workflow is the PARENT run id for a DAG sub-task (shared across
             ;; the run's PRs) else this run's id; :task distinguishes DAG tasks.
             :provenance {:workflow (or (get-in ctx [:execution/input :workflow/parent-id])
                                        (get-in ctx [:execution/id]))
                          :spec     (get-in ctx [:execution/input :spec/path])
                          :task     (get-in ctx [:execution/input :task/id])}
             ;; Resolve and inject GitHub token for capsule gh CLI auth
             :github-token (resolve-github-token ctx)}
      on-chunk (assoc :on-chunk on-chunk)
      behavior-addendum (assoc :task/behavior-addendum behavior-addendum))))

(defn enter-release
  "Execute release phase.

   Uses the workflow release executor to:
   - Generate release metadata (branch name, commit message, PR title/body)
   - Create git branch
   - Write files and stage them
   - Commit changes
   - Push branch and create PR (if enabled)"
  [ctx]
  (let [config (phase/merge-with-defaults (get-in ctx [:phase-config]))
        {:keys [gates budget]} config
        start-time (System/currentTimeMillis)
        impl-status (get-in ctx [:execution/phase-results :implement :result :status])

        logger (or (get-in ctx [:execution/logger])
                   (log/create-logger {:min-level :debug :output :human}))
        ;; Emit phase-started telemetry event
        _ (phase/emit-phase-started! ctx :release)]
    ;; Short-circuit: skip release when there is nothing to release.
    ;; This covers:
    ;; - implement returned :already-implemented (no new code)
    ;; - plan returned :already-satisfied (DAG had 0 tasks, no implement ran)
    ;; In both cases, only skip if there are also no git changes from other phases.
    (if (and (contains? #{:already-implemented :already-satisfied nil} impl-status)
             (let [wt         (ctx-worktree-path ctx)
                   execute-fn (get ctx :execution/execute-fn)
                   executor   (get ctx :execution/executor)
                   env-id     (get ctx :execution/environment-id)]
               (empty? (if (and (= :governed (get ctx :execution/mode)) execute-fn executor env-id)
                         (git-dirty-files-capsule execute-fn executor env-id wt)
                         (git-dirty-files wt)))))
      (-> (phase/enter-context ctx :release nil gates budget start-time
                                      (phase/skipped :already-implemented))
          (assoc-in [:phase :status] :completed))
      (let [;; Build workflow state and context
            workflow-state (build-workflow-state ctx)
        _ (log/debug logger :release :release/workflow-state-built
                     {:data {:artifact-count (count (:workflow/artifacts workflow-state))
                             :file-count (count (get-in (first (:workflow/artifacts workflow-state))
                                                        [:artifact/content :code/files]))}})
        exec-context (build-executor-context ctx config logger)
        releaser-agent (get config :releaser-agent)
        ;; Execute the release phase
        ;; Emit agent-started telemetry event for release executor
        _ (phase/emit-agent-started! ctx :release :releaser)
        _ (log/info logger :release :release/executor-invoked
                    {:data {:worktree-path (:worktree-path exec-context)
                            :executor (some-> (:executor exec-context) type str)
                            :environment-id (:environment-id exec-context)
                            :create-pr? (:create-pr? exec-context)
                            :releaser-agent? (some? releaser-agent)
                            :artifact-count (count (:workflow/artifacts workflow-state))}})

        result (try
                 (let [exec-result (release-executor/execute-release-phase
                                    workflow-state
                                    exec-context
                                    {:releaser releaser-agent})]
                   (if (:success? exec-result)
                     (let [release-artifact (first (:artifacts exec-result))
                           content (:artifact/content release-artifact)]
                       (response/success
                        (merge
                         {:release/id (get release-artifact :artifact/id (random-uuid))
                          :release/status :completed
                          :release/artifacts (:artifacts exec-result)}
                         ;; Include PR info for evidence bundle
                         (when (:pr-url content)
                           {:workflow/pr-info {:pr-number (:pr-number content)
                                               :pr-url (:pr-url content)
                                               :branch (:branch content)
                                               :commit-sha (:commit-sha content)}})
                         {:release/metrics (:metrics exec-result)})))
                     ;; Execution failed — surface the executor's :errors
                     ;; so the next dogfood run can diagnose root cause from
                     ;; the event log instead of staring at a generic
                     ;; :anomalies.phase/agent-failed.
                     (do
                       (log/error logger :release :release/executor-failed
                                  {:data {:errors (:errors exec-result)
                                          :metrics (:metrics exec-result)}})
                       (response/failure
                        (ex-info (messages/t :release/phase-failed)
                                 {:errors (:errors exec-result)
                                  :metrics (:metrics exec-result)})))))
                 (catch Exception e
                   ;; The 2026-05-03 dogfood lost the actual exception here —
                   ;; the bare (response/failure e) wraps it but no log line
                   ;; ever fired, leaving the phase-completed event with
                   ;; :phase/duration-ms 0 and no actionable detail.
                   (log/error logger :release :release/executor-threw
                              {:message (ex-message e)
                               :data (cond-> {:exception/class (.getName (class e))}
                                       (ex-data e) (assoc :exception/data (ex-data e)))})
                   (response/failure e)))

        ;; Emit agent-completed telemetry event for release executor
        _ (phase/emit-agent-completed! ctx :release :releaser result)]

    (-> (phase/enter-context ctx :release :releaser gates budget start-time result)
        ;; Single derived projection of the canonical [:result :output
        ;; :workflow/pr-info] onto ctx-level :workflow/pr-info — the API surface
        ;; several consumers read (runner_events completion event, CLI summary,
        ;; evidence-bundle). Derived from the authority, not an independent
        ;; write, so it can't drift.
        (cond-> (phase/result-succeeded? result)
          (assoc-in [:workflow/pr-info] (get-in (:output result) [:workflow/pr-info]))))))))

(def ^:private verdicts
  "Phase 3b verdict tag set for release.

     :approved            — PR opened or release artifact persisted;
                            `:phase/succeed` event
     :repair-requested    — release failed with `:on-fail` set; FSM
                            redirects (subject to budget)
     :release/zero-files  — curator's empty-diff verdict; FSM
                            terminates because retrying implement
                            won't change the curator's decision
     :exhausted           — release failed without `:on-fail` set"
  #{:approved :repair-requested :release/zero-files :exhausted})

(defn- compute-verdict
  "Pick the verdict that should flow on the phase result.

   `:release/zero-files` pre-empts `:repair-requested` — when the curator
   reports an empty diff, retrying the implementer is wasted work: the
   next pass would just produce another empty diff. Make this FSM-visible
   so the verdict-driven guarded array terminates the loop."
  [{:keys [phase-failed? on-fail-configured? zero-files?]}]
  (cond
    zero-files?
    :release/zero-files

    (and phase-failed? on-fail-configured?)
    :repair-requested

    phase-failed?
    :exhausted

    :else
    :approved))

(defn leave-release
  "Post-processing for release phase.

   Records release metrics and PR info.

   Phase 3b: attaches a `:phase/verdict` to the phase result so the
   FSM's verdict-driven guarded `:phase/fail` array picks the right
   target. `:release/zero-files` is a TERMINAL verdict — the FSM
   bypasses `:on-fail` and lands on `:failed` because the curator's
   empty-diff signal means the implementer has nothing left to do."
  [ctx]
  (let [start-time (get-in ctx [:phase :started-at])
        end-time (System/currentTimeMillis)
        duration-ms (if start-time (- end-time start-time) 0)
        result (get-in ctx [:phase :result])
        release-data (when (phase/result-succeeded? result) (:output result))
        release-metrics (get release-data :release/metrics {})
        metrics (merge {:tokens 0 :duration-ms duration-ms} release-metrics)
        agent-status (:status result)
        iterations (get-in ctx [:phase :iterations] 1)
        max-iterations (get-in ctx [:phase :budget :iterations]
                               (get-in default-config [:budget :iterations]))
        zero-files? (= :release/zero-files
                       (get-in result [:error :data :type]))
        phase-status (if zero-files?
                       :failed
                       (phase/determine-phase-status
                        agent-status iterations max-iterations))
        on-fail (get-in ctx [:phase-config :on-fail])
        ;; Verdict is only meaningful for terminal phase statuses
        ;; (:completed → :approved; :failed → one of the failure
        ;; verdicts). For :retrying the runner handles the in-phase
        ;; loop and the FSM never sees a transition, so skip the
        ;; verdict attachment — leaving the prior verdict in place if
        ;; the phase has been around the loop before, else nothing.
        verdict (when (contains? #{:completed :failed} phase-status)
                  (compute-verdict {:phase-failed?       (= :failed phase-status)
                                    :on-fail-configured? (some? on-fail)
                                    :zero-files?         zero-files?}))
        updated-ctx (cond-> ctx
                      true
                      (-> (assoc-in [:phase :ended-at] end-time)
                          (assoc-in [:phase :duration-ms] duration-ms)
                          (assoc-in [:phase :status] phase-status)
                          (assoc-in [:phase :metrics] metrics))
                      verdict
                      (-> (assoc-in [:phase :verdict] verdict)
                          (assoc-in [:phase :result :output :phase/verdict] verdict)))
        updated-ctx (-> updated-ctx
                        (assoc-in [:metrics :release :duration-ms] duration-ms)
                        (assoc-in [:metrics :release :repair-cycles] (dec iterations))
                        (update-in [:execution :phases-completed] (fnil conj []) :release)
                        (update-in [:execution/metrics :tokens] (fnil + 0) (:tokens metrics 0))
                        (update-in [:execution/metrics :cost-usd] (fnil + 0.0) (:cost-usd metrics 0.0))
                        (update-in [:execution/metrics :duration-ms] (fnil + 0) (:duration-ms metrics 0)))]
    (doto (cond-> updated-ctx
            (phase/retrying? (:phase updated-ctx))
            (-> (update-in [:phase :iterations] (fnil inc 1))
                (assoc-in [:phase :last-error]
                          (get-in result [:error :message] (messages/t :release/phase-failed)))))
      (phase/emit-phase-completed! :release
        (merge {:outcome     (if (= :completed phase-status) :success :failure)
                :duration-ms duration-ms
                :tokens      (:tokens metrics 0)}
               (phase-terminal/derive-termination-reason result nil))))))

(defn- attach-error-verdict
  "Phase 3b: when error-release fails terminally, attach the verdict to
   the result so the FSM's guarded :phase/fail array reads it. The
   `:release/zero-files` case is terminal; everything else is either
   :repair-requested (on-fail set, FSM will redirect) or :exhausted
   (no on-fail target)."
  [ctx zero-files? on-fail-configured?]
  (let [verdict (cond
                  zero-files?            :release/zero-files
                  on-fail-configured?    :repair-requested
                  :else                  :exhausted)]
    (-> ctx
        (assoc-in [:phase :verdict] verdict)
        (assoc-in [:phase :result :output :phase/verdict] verdict))))

(defn error-release
  "Handle release phase errors.

   Phase 3b: failures no longer call `phase/fail-and-request-redirect`
   — the FSM's verdict-driven guarded :phase/fail array routes via
   `:on-fail` if configured, or to `:failed` otherwise. error-release
   stays responsible for the phase-level retry budget; once that's
   exhausted it just attaches the verdict + error and lets the FSM
   dispatch."
  [ctx ex]
  (let [iterations (get-in ctx [:phase :iterations] 0)
        max-iterations (get-in ctx [:phase :budget :iterations] 2)
        on-fail (get-in ctx [:phase-config :on-fail])
        error-map (phase/exception-error ex)
        zero-files? (= :release/zero-files (get (ex-data ex) :type))]
    (cond
      zero-files?
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict true (some? on-fail)))

      ;; Within phase-level retry budget — retry in place. The FSM
      ;; doesn't see this as a transition.
      (< iterations max-iterations)
      (-> ctx
          (update-in [:phase :iterations] (fnil inc 0))
          (assoc-in [:phase :last-error] (ex-message ex))
          (assoc-in [:phase :status] :retrying))

      ;; Out of retries — attach verdict and let the FSM dispatch
      ;; (:repair-requested when on-fail set, :exhausted otherwise).
      :else
      (-> ctx
          (assoc :phase (phase/fail-phase (:phase ctx) error-map))
          (attach-error-verdict false (some? on-fail))))))

;------------------------------------------------------------------------------ Layer 2
;; Registry methods

(defmethod phase/get-phase-interceptor-method :release
  [config]
  (let [merged (phase/merge-with-defaults config)]
    {:name ::release
     :config merged
     :enter (fn [ctx]
              (enter-release (assoc ctx :phase-config merged)))
     :leave leave-release
     :error error-release}))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (phase/get-phase-interceptor {:phase :release})
  (phase/phase-defaults :release)
  (phase/list-phases)
  :leave-this-here)
