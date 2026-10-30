(ns ai.miniforge.release-executor.core
  "Release phase executor — orchestrates the release pipeline.

   In the environment model, code changes live in the executor's git worktree.
   The release phase stages dirty files, commits, pushes, and creates a PR.
   No file writes from code artifacts — the implement agent already wrote files
   to the worktree during the implement phase.

   ## Backends

   Two git backends are supported in a single pipeline:

   - **Sandbox mode** (`:host-mode?` absent / false): all git/gh operations route
     through the DAG executor (`sandbox` module) so that governed-mode capsules
     never shell out to the host.  Requires `:executor` + `:environment-id`.

   - **Host mode** (`:host-mode?` true): git/gh operations run directly on the
     host working tree via `git.clj` (babashka.process).  Active when
     `:worktree-path` is present but `:executor` and `:environment-id` are both
     nil — i.e. the local-dogfood path.

   ### Diagnosis — why local-dogfood PR creation failed

   `step-validate-inputs` checked `:worktree-path` (present), then immediately
   failed on `(not (:executor state))` with `:missing-executor`.  Every
   downstream step calls `sandbox/*` functions that require a live DAG executor.
   The dogfood/host path (local worktree, no Docker capsule) supplied neither
   `:executor` nor `:environment-id`, so the pipeline was dead on entry — no
   branch was created, no commit staged, no PR opened.

   Fix: when `:worktree-path` IS present and `:executor` / `:environment-id`
   are BOTH nil, `step-validate-inputs` now sets `:host-mode? true` and accepts
   the state. Every step that previously called `sandbox/*` unconditionally now
   dispatches: host-mode → `git/*` using `:worktree-path`; sandbox-mode → same
   `sandbox/*` path as before."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.redaction.interface :as redaction]
   [ai.miniforge.release-executor.effects :as effects]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.metadata :as metadata]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Pipeline helpers
(defn ^{:stratum 0} failed?
  "Check if pipeline has failed."
  [state]
  (contains? state :failure))

(defn ^{:stratum 0} fail
  "Mark pipeline as failed with error info."
  [state error-type error-msg & {:keys [hint]}]
  (let [logger (:logger state)]
    (when logger
      (log/error logger :release-executor error-type {:message error-msg}))
    (assoc state :failure
           (cond-> {:type error-type :message error-msg}
             hint (assoc :hint hint)))))

(defn- ^{:stratum 0} gh-exec-opts
  "Build executor opts with GH_TOKEN env var when github-token is present.
   The token is required for gh CLI commands (gh pr create, gh auth status)
   inside the capsule where the host's gh auth context is not available."
  [state]
  (if-let [token (:github-token state)]
    {:env {"GH_TOKEN" token}}
    {}))

(defn ^{:stratum 0} extract-code-artifacts
  "Extract code artifacts from workflow artifacts (used for PR metadata generation)."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))
       (remove nil?)))

(defn ^{:stratum 0} extract-workflow-data
  "Extract review and test artifacts from workflow artifacts for PR metadata.
   Returns a map with :review-artifacts and :test-artifacts."
  [workflow-artifacts]
  {:review-artifacts (metadata/extract-review-artifacts workflow-artifacts)
   :test-artifacts (metadata/extract-test-artifacts workflow-artifacts)})

(defn- ^{:stratum 0} net-negative-tests?
  "True when the diff removes more test definitions than it adds."
  [{:keys [removed added] :as test-counts}]
  (and test-counts (pos? removed) (> removed added)))

(defn- ^{:stratum 0} heavily-destructive?
  "True when deletions exceed 20 lines and outnumber additions 3-to-1."
  [{:keys [deletions additions] :as diff-stats}]
  (and diff-stats
       (> deletions 20)
       (> deletions (* 3 (max 1 additions)))))

(defn- ^{:stratum 0} yaml-ambiguous-scalar?
  "True when `s`, even though it's otherwise made up of yaml-scalar's safe
   plain-scalar character set, would parse back as a YAML boolean/null/
   number rather than a string — the reserved words `true`/`false`/
   `yes`/`no`/`on`/`off`/`null`/`~` (case-insensitive, since YAML 1.1
   parsers treat e.g. `True`/`YES`/`Off` as booleans too) or anything that
   reads as a number. Provenance values (workflow ids, spec paths, task
   ids, shas) must always round-trip as strings, so these need forcing
   into the quoted branch even though their characters alone look
   plain-scalar-safe."
  [s]
  (or (contains? #{"true" "false" "yes" "no" "on" "off" "null" "~"} (str/lower-case s))
      (boolean (re-matches #"[+-]?\d+(\.\d+)?([eE][+-]?\d+)?" s))))

(defn- ^{:stratum 0} pr-doc-filename
  "Generate a docs/pull-requests/ filename from the PR title.
   Format: YYYY-MM-DD-<slugified-title>.md"
  [pr-title]
  (let [date (.format (java.time.LocalDate/now)
                      (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd"))
        slug (-> (or pr-title "untitled")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-|-$" ""))]
    (str date "-" slug ".md")))

(defn- ^{:stratum 0} render-pr-doc
  "Render a docs/pull-requests/ markdown file from release metadata."
  [{:keys [release/pr-title release/pr-description release/commit-message]}
   {:keys [pr-number pr-url branch]}]
  (str "<!--\n"
       "  Title: Miniforge.ai\n"
       "  Author: Christopher Lester (christopher@miniforge.ai)\n"
       "  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n"
       "-->\n\n"
       "# " (or commit-message pr-title "Release") "\n\n"
       (when pr-url
         (str "**PR:** [#" pr-number "](" pr-url ")\n"
              "**Branch:** `" branch "`\n\n"))
       (or pr-description "") "\n"))

(defn- ^{:stratum 0} format-files-changed
  "Format a list of files changed from code artifacts as a markdown bullet list."
  [code-artifacts]
  (let [files (mapcat :code/files code-artifacts)]
    (if (seq files)
      (str/join "\n" (map #(str "- `" (:path %) "` (" (name (get % :action :create)) ")") files))
      "_No file changes recorded._")))

(defn- ^{:stratum 0} format-test-results
  "Format test results from test artifacts as markdown."
  [test-artifacts]
  (if (seq test-artifacts)
    (let [latest (last test-artifacts)
          results (:test/results latest)
          summary (:test/summary latest)
          total   (:test/total latest)
          passed  (:test/passed latest)
          failed  (:test/failed latest)]
      (str (when results
             (str "**Result**: " (name results) "\n"))
           (when (and total passed)
             (str "**Passed**: " passed "/" total
                  (when (and failed (pos? failed))
                    (str " (" failed " failed)"))
                  "\n"))
           (when (and summary (not (str/blank? summary)))
             (str "\n" summary))))
    "_No test artifacts available._"))

(defn- ^{:stratum 0} format-review-decision
  "Format review decision, summary, and any unresolved non-blocking warnings
   from review artifacts as markdown."
  [review-artifacts]
  (if (seq review-artifacts)
    (let [latest       (last review-artifacts)
          decision     (:review/decision latest)
          summary      (:review/summary latest)
          known-issues (metadata/format-known-issues (:review/warnings latest))]
      (str (when decision
             (str (msg/t :pr/decision {:decision (name decision)}) "\n"))
           (when (and summary (not (str/blank? summary)))
             (str "\n" summary))
           (when known-issues
             (str "\n\n" known-issues))))
    "_No review artifacts available._"))

(defn- ^{:stratum 0} looks-like-structured-pr-body?
  "True when a string already starts with the canonical PR-body shape
   (a `## Summary` header at top, possibly after a blank line)."
  [s]
  (and (string? s)
       (boolean (re-find #"(?m)\A\s*##\s+Summary\b" s))))

(defn- ^{:stratum 0} non-blank-section
  "Render `(str header body \"\\n\\n\")` only when `body` is a non-blank string."
  [header body]
  (when (and body (not (str/blank? body)))
    (str header body "\n\n")))

(defn- ^{:stratum 0} usable-worktree-path?
  "True when `v` is a non-blank string.

   `:worktree-path` is contractually a String: `workspace/core.clj`, the
   single blessed execution-workdir resolver, only reads a context path
   that satisfies `string?`. Callers that hand over a
   `java.nio.file.Path` — what `babashka.fs` returns — reached
   `clojure.string/blank?` here and died on its `CharSequence` cast, so a
   wrong-typed path crashed validation instead of failing it. Anything
   non-string is unusable, and falls through to the normal
   `:missing-worktree-path` branch."
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- ^{:stratum 0} pr-body-needs-update?
  "True when the post-create PR body should be overwritten."
  [release-meta]
  (let [body (:release/pr-body release-meta)]
    (or (nil? body)
        (str/blank? body)
        (= (str/trim body) (str/trim (str (:release/pr-title release-meta)))))))

(defn ^{:stratum 0} prepared-release
  "What publish-release needs from a prepared pipeline state, as plain
   data: no runtime handles or credentials, and code artifacts without
   file content (publish renders paths only)."
  [state]
  (let [strip-content (fn [artifact]
                        (update artifact :code/files
                                (fn [files] (mapv #(dissoc % :content) files))))]
    (-> (select-keys state [:branch :base-branch :commit-sha :release-meta
                            :write-metrics :workflow-data])
        (assoc :code-artifacts (mapv strip-content (:code-artifacts state))))))

(defn ^{:stratum 0} pipeline->result
  "Convert pipeline state to phase result. The §7.7.2 answer-channel
   keys (:codex-answers / :codex-answer-log, placed on state by
   step-generate-metadata) ride the result on success AND failure — a
   failed release whose releaser answered pegs still made observations
   the consultation record needs."
  [state]
  (let [{:keys [logger failure release-artifact write-metrics
                branch commit-sha pr-number pr-url
                codex-answers codex-answer-log]} state
        attach-answer-channel
        (fn [result]
          (cond-> result
            (some? codex-answers) (assoc :codex-answers codex-answers)
            (some? codex-answer-log) (assoc :codex-answer-log codex-answer-log)))]
    (if failure
      (attach-answer-channel
       (result/phase-failure (:type failure) (:message failure)
                             {:hint    (:hint failure)
                              :metrics (or write-metrics {})}))
      (do
        (when logger
          (log/info logger :release-executor :phase-completed
                    {:data {:branch        branch
                            :commit        commit-sha
                            :pr-url        pr-url
                            :files-written (:files-written write-metrics)}}))
        (attach-answer-channel
         (result/phase-success
          [release-artifact]
          (merge write-metrics
                 {:pr-number  pr-number
                  :pr-url     pr-url
                  :commit-sha commit-sha
                  :branch     branch})))))))

(defn- ^{:stratum 0} effect-key
  "The key of this release's `effect-class` on `subject`: the run's lineage
   and task from :provenance. nil for a release with no run id, whose
   effects then run untransacted (no fixed key exists)."
  [{:keys [provenance]} effect-class subject]
  (when-let [origin (or (:origin provenance) (:workflow provenance))]
    {:effect-class effect-class :origin origin :task (:task provenance) :subject subject}))

(defn- ^{:stratum 0} effect-report
  "An effect transaction report from a shell result: success, an unknown
   outcome when the call timed out, else a definite failure."
  [result observed]
  (cond
    (result/succeeded? result) {:effect/outcome :succeeded :effect/observed observed}
    (:timed-out? result)       {:effect/outcome :accepted :effect/failure (str (:error result))}
    :else                      {:effect/outcome :failed :effect/failure (str (:error result))}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} prepare->result
  "Result of a prepared (branched and committed, not pushed) pipeline
   state: no release artifact yet, and on success `:prepared`, the input
   publish-release takes."
  [state]
  (let [result (pipeline->result (dissoc state :logger))]
    (cond-> result
      (:success? result)
      (-> (assoc :artifacts [] :prepared (prepared-release state))
          (update :metrics dissoc :pr-number :pr-url)))))

(defn ^{:stratum 1} release-state
  "Initial pipeline state from the workflow state, the execution context
   and the options (see execute-release-phase)."
  [workflow-state context opts]
  (let [workflow-artifacts (:workflow/artifacts workflow-state)]
    (cond-> {:logger         (:logger context)
             :worktree-path  (:worktree-path context)
             :artifact-store (:artifact-store context)
             :context        context
             :create-pr?     (get context :create-pr? true)
             ;; Explicit PR base override (a dependency-chained
             ;; DAG task's parent branch).  When present it wins
             ;; over the branch-creation default so the PR
             ;; stacks on the parent.
             :base-branch-override (:base-branch context)
             ;; PR provenance ({:workflow :spec :task}) rendered
             ;; as YAML frontmatter on the PR body.
             :provenance     (:provenance context)
             :releaser       (:releaser opts)
             :task-description (get-in workflow-state [:workflow/spec :spec/description])
             :code-artifacts (extract-code-artifacts workflow-artifacts)
             :workflow-data  (extract-workflow-data workflow-artifacts)
             :executor       (:executor context)
             :environment-id (:environment-id context)
             :github-token   (:github-token context)}
      (:release-meta opts) (assoc :release-meta (:release-meta opts)))))

(defn- ^{:stratum 1} yaml-scalar
  "Render `v` as a safe YAML scalar for a `key: value` frontmatter line.
   Values starting with an alphanumeric and otherwise made up only of a
   conservative safe character set ([A-Za-z0-9._/-]) — the common case:
   workflow ids, spec paths, task ids, commit shas — are emitted as an
   unquoted YAML plain scalar, UNLESS the value is itself YAML-ambiguous
   (see yaml-ambiguous-scalar?), in which case it's force-quoted so it
   round-trips as a string rather than a boolean/null/number. Anything
   else (characters outside the safe set) is emitted as a double-quoted
   YAML scalar with embedded backslashes/quotes escaped and newlines
   collapsed, so a `:` followed by a space, a leading `-` (a
   block-sequence indicator in YAML), a `#`, or an embedded newline in the
   source value can't corrupt the frontmatter or be ambiguously parsed."
  [v]
  (let [s (str v)]
    (if (and (re-matches #"[A-Za-z0-9][A-Za-z0-9._/-]*" s)
             (not (yaml-ambiguous-scalar? s)))
      s
      (str "\"" (-> s
                    ;; Normalize every newline variant to bare \n BEFORE
                    ;; escaping, so a Windows \r\n collapses to one escape
                    ;; instead of two, and a lone \r (old Mac-style) doesn't
                    ;; slip through as a raw control character.
                    (str/replace "\r\n" "\n")
                    (str/replace "\r" "\n")
                    (str/replace "\\" "\\\\")
                    (str/replace "\"" "\\\"")
                    (str/replace "\n" "\\n")) "\""))))

;; Pipeline steps
(defn ^{:stratum 1} step-validate-inputs
  "Validate that the pipeline state carries enough context to execute.

   Host mode: when :worktree-path IS present and :executor / :environment-id
   are BOTH nil, the state is accepted with :host-mode? true.  Git/gh ops will
   route through git.clj (babashka.process) instead of the DAG executor.

   Sandbox mode: requires :worktree-path, :executor, and :environment-id.

   The previous implementation rejected any context that lacked :executor,
   which made every local-dogfood release fail before any git work could run."
  [state]
  (cond
    (failed? state) state

    ;; Host-mode path: local-dogfood context supplies a worktree but no
    ;; sandbox executor.  Accepting this as valid (with :host-mode? true) is
    ;; the root fix for the dogfood PR failure described in the ns docstring.
    ;; A blank :worktree-path is NOT present — babashka.process treats
    ;; :dir "" as the current working directory, which would run host git/gh
    ;; against whatever repo the process happens to sit in.
    (and (usable-worktree-path? (:worktree-path state))
         (nil? (:executor state))
         (nil? (:environment-id state)))
    (assoc state :host-mode? true)

    ;; Sandbox-mode guards — same order/messages as before
    (not (usable-worktree-path? (:worktree-path state)))
    (fail state :missing-worktree-path (msg/t :exec/missing-worktree-path))

    (not (:executor state))
    (fail state :missing-executor (msg/t :exec/missing-executor))

    (not (:environment-id state))
    (fail state :missing-environment-id (msg/t :exec/missing-environment-id))

    :else state))

(defn ^{:stratum 1} step-check-gh-auth
  "Check gh CLI auth.  Dispatches to git/check-gh-auth! (host) or
   sandbox/check-gh-auth! (sandbox)."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [gh-auth (if (:host-mode? state)
                    (git/check-gh-auth! (:github-token state))
                    (sandbox/check-gh-auth! (:executor state) (:environment-id state)
                                            (gh-exec-opts state)))]
      (if (:authenticated? gh-auth)
        state
        (fail state :gh-auth-failed (:error gh-auth)
              :hint (if (:available? gh-auth)
                      (msg/t :gh/auth-login-hint)
                      (msg/t :gh/install-hint)))))))

(defn ^{:stratum 1} step-generate-metadata [state]
  (if (failed? state)
    state
    (if (:release-meta state)
      state ;; Already provided (e.g. by caller or test)
      (let [{:keys [releaser code-artifacts task-description context logger
                    workflow-data]} state
            {:keys [release-meta codex-answers codex-answer-log]}
            (metadata/generate-release-metadata
             releaser code-artifacts task-description context logger
             workflow-data)]
        (if release-meta
          ;; §7.7.2: the releaser session's recorded peg answers (and the
          ;; lost-vs-unanswered answer-log marker) ride the pipeline state
          ;; so pipeline->result can surface them to the release phase.
          (cond-> (assoc state :release-meta release-meta)
            (some? codex-answers) (assoc :codex-answers codex-answers)
            (some? codex-answer-log) (assoc :codex-answer-log codex-answer-log))
          (fail state :metadata-generation-failed (msg/t :step/metadata-generation-failed)))))))

(defn ^{:stratum 1} step-create-branch
  "Create the release branch.  Dispatches git backend based on :host-mode?.

   With a run id in :provenance the branch is the run's fixed one
   (metadata/release-branch-name), not the releaser's choice, so a retry
   or resume reuses the branch and its PR. :origin, the run's lineage
   across resume attempts, wins over :workflow."
  [state]
  (if (failed? state)
    state
    (let [{:keys [release-meta host-mode? worktree-path executor environment-id
                  base-branch-override logger provenance task-description]} state
          branch-name (if-let [run-id (or (:origin provenance) (:workflow provenance))]
                        (metadata/release-branch-name run-id (:task provenance) task-description)
                        (:release/branch-name release-meta))
          result (if host-mode?
                   (git/create-branch! worktree-path branch-name)
                   (sandbox/create-branch! executor environment-id branch-name))]
      (if-not (result/succeeded? result)
        (fail state :branch-create-failed (:error result))
        (let [detected  (:base-branch result)
              ;; A chained DAG task's parent branch (override) becomes the PR
              ;; base so the PR stacks on the parent.  Fetch the override branch
              ;; so later origin/<base> range diffs and commits-ahead resolve.
              override? (and base-branch-override (not= base-branch-override detected))
              fetch-r   (when override?
                          (if host-mode?
                            (git/fetch-branch! worktree-path base-branch-override)
                            (sandbox/fetch-branch! executor environment-id base-branch-override)))
              ;; Degrade gracefully: if the parent branch can't be fetched
              ;; (e.g. not yet on the remote), fall back to detected default.
              fetch-ok? (or (not override?) (result/succeeded? fetch-r))
              base      (if (and override? fetch-ok?) base-branch-override detected)]
          (when (and override? (not fetch-ok?) logger)
            (log/warn logger :release-executor :base-branch-fetch-degraded
                      {:message (str "Could not fetch base branch " base-branch-override
                                     "; PR targets " detected " instead of stacking.")}))
          (-> state
              (assoc :branch (:branch result) :base-branch base)
              (assoc-in [:release-meta :release/branch-name] (:branch result))))))))

(defn ^{:stratum 1} step-stage-dirty-files
  "Stage all dirty files.  Dispatches git backend based on :host-mode?.

   Empty-stage handling: when nothing is dirty, the implementer's writes
   may already be committed on the branch as phase-boundary commits.
   commits-ahead-of-base (git rev-list --count --right-only
   origin/<base>...HEAD — the three-dot merge-base form) detects this
   carry-forward case and treats it as success — step-commit will skip
   cleanly and step-push will ship the existing commits."
  [state]
  (if (failed? state)
    state
    (let [{:keys [host-mode? worktree-path executor environment-id base-branch]} state
          stage-r (if host-mode?
                    (git/stage-files! worktree-path :all)
                    (sandbox/stage-files! executor environment-id :all))
          staged-r (when (result/succeeded? stage-r)
                     (if host-mode?
                       (git/exec! worktree-path ["git" "diff" "--cached" "--name-only"])
                       (sandbox/exec! executor environment-id "git diff --cached --name-only")))
          staged-output (get staged-r :output "")
          staged-count (count (remove str/blank? (str/split-lines staged-output)))]
      (cond
        (not (result/succeeded? stage-r))
        (fail state :stage-failed (:error stage-r))

        ;; The listing command itself failed — don't misread an errored
        ;; `git diff --cached --name-only` as an empty staged set (which
        ;; would fall through to the boundary-commit path or a spurious
        ;; :no-files-to-stage). Fail fast with the real error.
        (and staged-r (not (result/succeeded? staged-r)))
        (fail state :stage-list-failed (:error staged-r))

        (pos? staged-count)
        (assoc state :write-metrics {:total-operations staged-count
                                     :files-written staged-count})

        ;; Nothing dirty in the worktree, but the branch may already carry
        ;; the work as boundary commits — accept that as success.
        (let [ahead (if host-mode?
                      (git/commits-ahead-of-base worktree-path base-branch)
                      (sandbox/commits-ahead-of-base executor environment-id base-branch))]
          (and ahead (pos? ahead)))
        (assoc state :write-metrics {:total-operations 0 :files-written 0
                                     :preexisting-commits true})

        :else
        (fail state :no-files-to-stage (msg/t :step/no-files-to-stage))))))

(defn ^{:stratum 1} step-validate-diff
  "Validate diff is not destructive before committing.  Dispatches git backend
   based on :host-mode?.

   In the boundary-commits case (:preexisting-commits true) the staged index is
   empty — validation reads the range diff `origin/<base>...HEAD` instead."
  [state]
  (if (failed? state)
    state
    (let [{:keys [host-mode? worktree-path executor environment-id logger base-branch]} state
          preexisting? (get-in state [:write-metrics :preexisting-commits])
          diff-stats  (if preexisting?
                        (if host-mode?
                          (git/diff-stats-range worktree-path base-branch)
                          (sandbox/diff-stats-range executor environment-id base-branch))
                        (if host-mode?
                          (git/diff-stats worktree-path)
                          (sandbox/diff-stats executor environment-id)))
          test-counts (if preexisting?
                        (if host-mode?
                          (git/count-test-defs-range worktree-path base-branch)
                          (sandbox/count-test-defs-range executor environment-id base-branch))
                        (if host-mode?
                          (git/count-test-defs worktree-path)
                          (sandbox/count-test-defs executor environment-id)))]
      (cond
        (or (nil? diff-stats) (nil? test-counts))
        (do (when logger (log/error logger :release-executor :diff/stats-unavailable {}))
            (fail state :destructive-diff (msg/t :step/diff-stats-unavailable)))

        (net-negative-tests? test-counts)
        (let [data {:added (:added test-counts) :removed (:removed test-counts)}]
          (when logger (log/error logger :release-executor :diff/net-negative-tests {:data data}))
          (fail state :destructive-diff (msg/t :step/diff-net-negative-tests data)))

        (heavily-destructive? diff-stats)
        (let [data {:additions (:additions diff-stats) :deletions (:deletions diff-stats)}]
          (when logger (log/warn logger :release-executor :diff/heavily-destructive {:data data}))
          (fail state :destructive-diff (msg/t :step/diff-heavily-destructive data)))

        :else
        (do (when logger
              (log/debug logger :release-executor :diff/validated
                         {:data (merge {} diff-stats test-counts)}))
            state)))))

(defn ^{:stratum 1} step-commit
  "Commit staged changes.  Dispatches git backend based on :host-mode?.

   Skipped when step-stage-dirty-files found no dirty files and the branch
   already carries the work as phase-boundary commits — the existing HEAD
   becomes the release commit."
  [state]
  (cond
    (failed? state) state

    (get-in state [:write-metrics :preexisting-commits])
    (let [{:keys [host-mode? worktree-path executor environment-id]} state
          sha-r (if host-mode?
                  (git/exec! worktree-path ["git" "rev-parse" "HEAD"])
                  (sandbox/exec! executor environment-id "git rev-parse HEAD"))]
      (cond-> state
        (result/succeeded? sha-r)
        (assoc :commit-sha (str/trim (get sha-r :output "")))))

    :else
    (let [{:keys [release-meta host-mode? worktree-path executor environment-id]} state
          result (if host-mode?
                   (git/commit-changes! worktree-path (:release/commit-message release-meta))
                   ;; git/commit-changes! redacts its own message;
                   ;; sandbox/commit-changes! records what it is given, and
                   ;; this is its only caller.
                   (sandbox/commit-changes! executor environment-id
                                            (redaction/redact
                                             (:release/commit-message release-meta))))]
      (if (result/succeeded? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn ^{:stratum 1} step-push
  "Push branch to origin.  Dispatches git backend based on :host-mode?.

   With a run id the push is an effect transaction keyed on the run and the
   commit (see effects): a push already done for this commit is skipped,
   and one an earlier attempt left mid-effect is settled by asking origin
   where the branch points (git ls-remote)."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [branch host-mode? worktree-path executor environment-id commit-sha]} state
          push (fn []
                 (if host-mode?
                   (git/push-branch! worktree-path branch (:github-token state))
                   (sandbox/push-branch! executor environment-id branch
                                         (gh-exec-opts state))))
          probe (fn [_]
                  (let [r (if host-mode?
                            (git/remote-branch-sha! worktree-path branch (:github-token state))
                            (sandbox/remote-branch-sha! executor environment-id branch
                                                        (gh-exec-opts state)))]
                    (when (result/succeeded? r)
                      {:effect/observed {:branch branch :sha (:sha r)}
                       :effect/matched? (= commit-sha (:sha r))})))]
      (if-let [key (effect-key state effects/push-class commit-sha)]
        (let [{:keys [outcome failure detail]}
              (if (nil? commit-sha)
                {:outcome :failed :failure (msg/t :push/no-commit {:branch branch})}
                (effects/transact! (effects/store-dir (:context state)) key
                                   {:branch branch :commit-sha commit-sha}
                                   #(effect-report (push) {:branch branch :sha commit-sha})
                                   probe))]
          (case outcome
            :done   state
            :failed (fail state :push-failed failure)
            (fail state :push-unresolved (msg/t :push/unresolved {:branch branch
                                                                  :detail (pr-str detail)}))))
        (let [result (push)]
          (if (result/succeeded? result)
            state
            (fail state :push-failed (:error result))))))))

(defn- ^{:stratum 1} render-pr-doc-full
  "Render a comprehensive docs/pull-requests/ markdown file.
   Includes: title, summary, files changed, test results, review decision."
  [release-meta state-info code-artifacts workflow-data]
  (let [{:keys [release/pr-title release/pr-description release/commit-message]} release-meta
        {:keys [pr-number pr-url branch]} state-info
        review-artifacts (:review-artifacts workflow-data)
        test-artifacts   (:test-artifacts workflow-data)
        files-md  (format-files-changed code-artifacts)
        tests-md  (format-test-results test-artifacts)
        review-md (format-review-decision review-artifacts)]
    (str "<!--\n"
         "  Title: Miniforge.ai\n"
         "  Author: Christopher Lester (christopher@miniforge.ai)\n"
         "  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n"
         "-->\n\n"
         "# " (or commit-message pr-title "Release") "\n\n"
         (when pr-url
           (str "**PR:** [#" pr-number "](" pr-url ")\n"
                "**Branch:** `" branch "`\n\n"))
         "## Summary\n\n"
         (or pr-description "_No summary available._") "\n\n"
         "## Files Changed\n\n"
         files-md "\n\n"
         "## Test Results\n\n"
         tests-md "\n\n"
         "## Review Decision\n\n"
         review-md "\n")))

(defn ^{:stratum 1} step-write-pr-doc
  "Write a docs/pull-requests/ markdown file, stage it, and amend the commit.
   Runs after step-create-pr so PR number/URL are available.
   Skipped when :create-pr? is false (no PR → no PR doc needed).

   NOTE: This step is retained for compatibility but is NOT in the active
   pipeline — step-generate-pr-doc (below) supersedes it.  The sandbox/*
   calls here are intentionally left without host-mode dispatch to signal
   that this path should not be re-added to the pipeline; remove when safe."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta pr-number pr-url branch
                  executor environment-id logger]} state
          ;; The doc and its file name are committed and pushed: redact both.
          filename (pr-doc-filename (redaction/redact (:release/pr-title release-meta)))
          rel-path (str "docs/pull-requests/" filename)
          content  (redaction/redact
                    (render-pr-doc release-meta
                                   {:pr-number pr-number
                                    :pr-url    pr-url
                                    :branch    branch}))]
      (try
        (let [write-r (sandbox/write-file! executor environment-id rel-path content)]
          (if (result/succeeded? write-r)
            (do
              (when logger
                (log/info logger :release-executor :pr-doc-written
                          {:data {:path rel-path}}))
              (sandbox/exec! executor environment-id (str "git add " rel-path))
              (sandbox/exec! executor environment-id "git commit --amend --no-edit --no-verify")
              (sandbox/exec! executor environment-id "git push --force-with-lease"
                             (gh-exec-opts state)))
            (when logger
              (log/warn logger :release-executor :pr-doc-write-failed
                        {:message (:error write-r)}))))
        state
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :pr-doc-write-failed
                      {:message (.getMessage e)}))
          state)))))

(defn- ^{:stratum 1} render-pr-body-fallback
  "Render a structured GitHub PR body when the releaser agent didn't produce one.
   Distinct from `render-pr-doc-full` (which targets the committed
   docs/pull-requests/*.md file): no HTML copyright header, no placeholder strings."
  [release-meta code-artifacts workflow-data]
  (let [{:keys [release/pr-title release/pr-description]} release-meta
        review-artifacts (:review-artifacts workflow-data)
        test-artifacts   (:test-artifacts workflow-data)
        files-md         (format-files-changed code-artifacts)
        review-md        (when (seq review-artifacts) (format-review-decision review-artifacts))
        tests-md         (when (seq test-artifacts)   (format-test-results test-artifacts))
        structured?      (looks-like-structured-pr-body? pr-description)
        summary-body     (if-not (str/blank? pr-description) pr-description pr-title)
        summary-block    (if structured?
                           (str summary-body "\n\n")
                           (str "## Summary\n\n" summary-body "\n\n"))]
    (str summary-block
         (non-blank-section "## Files Changed\n\n" files-md)
         (non-blank-section "## Test Results\n\n"  tests-md)
         (non-blank-section "## Review\n\n"        review-md)
         "🤖 Generated autonomously by [miniforge](https://github.com/miniforge-ai/miniforge).\n")))

(defn ^{:stratum 1} step-build-artifact [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path branch base-branch commit-sha create-pr?
                  pr-number pr-url release-meta write-metrics code-artifacts]} state
          release-content (merge write-metrics
                                 {:git-staged?   true
                                  :worktree-path (str worktree-path)
                                  :branch        branch
                                  :base-branch   base-branch
                                  :commit-sha    commit-sha
                                  :pr-created?   (boolean create-pr?)
                                  :pr-number     pr-number
                                  :pr-url        pr-url
                                  :release-metadata release-meta})
          release-artifact (artifact/build-artifact
                            {:id       (random-uuid)
                             :type     :release
                             :version  "1.0.0"
                             :content  release-content
                             :metadata {:phase                 :release
                                        :code-artifacts-count  (count code-artifacts)}})]
      (assoc state :release-artifact release-artifact))))

(defn ^{:stratum 1} step-save-artifact [state]
  (if (failed? state)
    state
    (do
      (when-let [artifact-store (:artifact-store state)]
        (try
          (artifact/save! artifact-store (:release-artifact state))
          (catch Exception _e nil)))
      state)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare-release
  "First half of the release: generate metadata, create the branch and
   commit. Nothing leaves the worktree; the push and the PR are
   publish-release's, which the release phase runs only after its gates
   pass. Arguments as execute-release-phase.

   Returns {:success? bool :artifacts [] :errors [] :metrics {...}} and, on
   success, :prepared (see prepared-release)."
  [workflow-state context opts]
  (let [state (release-state workflow-state context opts)]
    (when-let [logger (:logger state)]
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path (:worktree-path state)
                        :create-pr?    (:create-pr? state)}}))
    (-> state
        step-validate-inputs
        step-check-gh-auth
        step-generate-metadata
        step-create-branch
        step-stage-dirty-files
        step-validate-diff
        step-commit
        prepare->result)))

(defn ^{:stratum 2} provenance-frontmatter
  "YAML frontmatter mapping a PR back to its workflow run + spec. Built from
   state's :provenance ({:workflow :spec :task}) + :commit-sha. Always emits
   `generated-by: miniforge` (authorship) plus whatever provenance is present.
   Visible in the rendered PR — human-readable AND deterministically parsable
   (fixed position, explicit `---` delimiters, rigid key: value). Each value
   is rendered via yaml-scalar so a value carrying YAML-significant
   characters can't produce invalid or ambiguous frontmatter."
  [{:keys [provenance commit-sha]}]
  (let [{:keys [workflow spec task]} provenance
        rows (cond-> []
               workflow   (conj (str "miniforge-workflow: " (yaml-scalar workflow)))
               spec       (conj (str "spec: " (yaml-scalar spec)))
               task       (conj (str "task: " (yaml-scalar task)))
               commit-sha (conj (str "commit: " (yaml-scalar commit-sha)))
               true       (conj "generated-by: miniforge"))]
    (str "---\n" (str/join "\n" rows) "\n---\n\n")))

(defn ^{:stratum 2} step-generate-pr-doc
  "Generate a comprehensive PR doc at docs/pull-requests/YYYY-MM-DD-<slug>.md.
   Stages the doc and amends the release commit to include it, then force-pushes.
   Dispatches git backend based on :host-mode?.

   Runs after step-create-pr so PR number/URL are available.
   Skipped when :create-pr? is false."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta pr-number pr-url branch
                  host-mode? worktree-path executor environment-id
                  logger code-artifacts workflow-data]} state
          ;; The doc and its file name are committed and pushed: redact both.
          ;; The doc quotes agent-written summaries, so a token one of them
          ;; quotes would otherwise land in the repository's history.
          filename (pr-doc-filename (redaction/redact (:release/pr-title release-meta)))
          rel-path (str "docs/pull-requests/" filename)
          content  (redaction/redact
                    (render-pr-doc-full
                     release-meta
                     {:pr-number pr-number :pr-url pr-url :branch branch}
                     code-artifacts
                     workflow-data))]
      (try
        (let [write-r (if host-mode?
                        (git/write-file! worktree-path rel-path content)
                        (sandbox/write-file! executor environment-id rel-path content))]
          (if (result/succeeded? write-r)
            (do
              (when logger
                (log/info logger :release-executor :pr-doc-generated
                          {:data {:path rel-path}}))
              ;; Amend the doc onto the branch tip and re-push. Thread each
              ;; step's result so a failed add/amend/push does not let us
              ;; claim :pr-doc-path — the doc would be in the worktree but
              ;; not on the pushed branch. Publish is best-effort: on
              ;; failure we warn and continue (the PR + doc file still exist
              ;; locally) rather than fail the whole release for a doc-only
              ;; step.
              (let [publish-r
                    (if host-mode?
                      (let [add-r    (git/exec! worktree-path ["git" "add" rel-path])
                            commit-r (when (result/succeeded? add-r)
                                       (git/exec! worktree-path
                                                  ["git" "commit" "--amend" "--no-edit" "--no-verify"]))]
                        (if (and commit-r (result/succeeded? commit-r))
                          (git/force-push! worktree-path branch (:github-token state))
                          (or commit-r add-r)))
                      (let [add-r    (sandbox/exec! executor environment-id (str "git add " rel-path))
                            commit-r (when (result/succeeded? add-r)
                                       (sandbox/exec! executor environment-id
                                                      "git commit --amend --no-edit --no-verify"))]
                        (if (and commit-r (result/succeeded? commit-r))
                          (sandbox/exec! executor environment-id "git push --force-with-lease"
                                         (gh-exec-opts state))
                          (or commit-r add-r))))]
                (if (result/succeeded? publish-r)
                  (assoc state :pr-doc-path rel-path :pr-doc-content content)
                  (do
                    (when logger
                      (log/warn logger :release-executor :pr-doc-publish-failed
                                {:message (str "PR doc amend/push did not complete; "
                                               "not claiming it was published: "
                                               (:error publish-r))}))
                    state))))
            (do
              (when logger
                (log/warn logger :release-executor :pr-doc-generation-failed
                          {:message (:error write-r)}))
              state)))
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :pr-doc-generation-failed
                      {:message (.getMessage e)}))
          state)))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} with-provenance
  "Prepend the provenance frontmatter to a PR body. Idempotent: a body that
   already opens with a `---` frontmatter block is returned unchanged."
  [body state]
  (let [body (str body)]
    (if (str/starts-with? body "---\n")
      body
      (str (provenance-frontmatter state) body))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} step-create-pr
  "Create the pull request, unless the branch already has one.  Dispatches
   git backend based on :host-mode?.

   Looks the branch's PR up first, in any state: an open PR is reused (a
   retried or resumed release), and so is a merged one whose head is this
   release's commit. A merged PR of another commit, or a closed PR, fails
   the release rather than opening another or reporting work no PR holds;
   a failed lookup fails it too, since creating blind could duplicate.
   git/create-pr! and sandbox/create-pr! also reuse a PR that gh reports
   as already existing.

   With a run id, opening the PR is an effect transaction keyed on the run
   and the branch (see effects). A creation an earlier attempt left
   mid-effect is settled by the lookup: a listed PR is reused, none means
   it never opened and it is opened now."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta base-branch host-mode? worktree-path
                  executor environment-id branch commit-sha]} state
          lookup   #(if host-mode?
                      (git/find-pr! worktree-path branch (:github-token state))
                      (sandbox/find-pr! executor environment-id branch (gh-exec-opts state)))
          found    (lookup)
          existing (:pr found)
          key      (effect-key state effects/pr-create-class branch)
          dir      (effects/store-dir (:context state))
          pr-observed (fn [pr] {:pr/number (:pr-number pr) :pr/url (:pr-url pr)})]
      (cond
        (not (result/succeeded? found))
        (fail state :pr-lookup-failed (msg/t :pr/lookup-failed {:branch branch
                                                                :error  (:error found)}))

        (= "CLOSED" (:pr-state existing))
        (fail state :pr-closed (msg/t :pr/closed {:branch branch :pr-url (:pr-url existing)}))

        (and (= "MERGED" (:pr-state existing)) (not= commit-sha (:pr-head-sha existing)))
        (fail state :pr-merged (msg/t :pr/merged-other {:branch branch :pr-url (:pr-url existing)}))

        existing
        (do (when key
              (effects/settle-pending! dir key (constantly {:effect/observed (pr-observed existing)
                                                            :effect/matched? true})))
            (assoc state
                   :pr-number  (:pr-number existing)
                   :pr-url     (:pr-url existing)
                   :pr-reused? true))

        :else
        (let [pr-opts {:title       (:release/pr-title release-meta)
                       :body        (with-provenance (:release/pr-body release-meta) state)
                       :base-branch base-branch}
              create  #(if host-mode?
                         (git/create-pr! worktree-path pr-opts (:github-token state))
                         (sandbox/create-pr! executor environment-id pr-opts
                                             (gh-exec-opts state)))
              {:keys [outcome observed failure detail ran?]}
              (if key
                (effects/transact!
                 dir key {:branch branch :base base-branch}
                 #(let [r (create)] (effect-report r (pr-observed r)))
                 (fn [_] (let [f (lookup)]
                           (when (result/succeeded? f)
                             {:effect/observed (some-> (:pr f) pr-observed)
                              :effect/matched? (some? (:pr f))}))))
                (let [r (create)]
                  (if (result/succeeded? r)
                    {:outcome :done :observed (pr-observed r) :ran? true}
                    {:outcome :failed :failure (:error r)})))]
          (cond
            ;; Opened now, or by an earlier attempt whose record (or the
            ;; reconciling lookup) names the PR: the lookup above can lag.
            (and (= :done outcome) (:pr/url observed))
            (cond-> (assoc state :pr-number (:pr/number observed) :pr-url (:pr/url observed))
              (not ran?) (assoc :pr-reused? true))

            ;; Recorded as opened, yet nothing names the PR: do not claim it.
            (= :done outcome)
            (fail state :pr-unconfirmed (msg/t :pr/recorded-not-found {:branch branch}))

            (= :failed outcome)
            (fail state :pr-create-failed failure)

            :else
            (fail state :pr-unresolved (msg/t :pr/unresolved {:branch branch
                                                              :detail (pr-str detail)}))))))))

(defn ^{:stratum 4} step-update-pr-body
  "Update the GitHub PR body when the initial body was missing or degraded.
   Dispatches git backend based on :host-mode?."
  [state]
  (cond
    (failed? state)                               state
    (not (:create-pr? state))                     state
    (not (:pr-number state))                      state
    (not (pr-body-needs-update? (:release-meta state))) state
    :else
    (let [{:keys [release-meta pr-number host-mode? worktree-path
                  executor environment-id logger
                  code-artifacts workflow-data]} state
          body (with-provenance
                (render-pr-body-fallback release-meta code-artifacts workflow-data)
                state)]
      (try
        (if host-mode?
          (git/edit-pr-body! worktree-path pr-number body (:github-token state))
          (sandbox/edit-pr-body! executor environment-id pr-number body
                                 (gh-exec-opts state)))
        (when logger
          (log/info logger :release-executor :pr-body-updated
                    {:data {:pr-number pr-number
                            :reason    :degraded-agent-body
                            :source    :fallback-renderer}}))
        state
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :pr-body-update-failed
                      {:message (.getMessage e)}))
          state)))))

;------------------------------------------------------------------------------ Layer 5

;; Main execute function
(defn ^{:stratum 5} execute-release-phase
  "Execute the release phase: prepare-release's steps, then
   publish-release's, with no gate between them.

   Accepts two backends transparently:

   - **Sandbox mode**: `:executor` + `:environment-id` in context; all git/gh
     ops route through the DAG executor (governed-mode capsule).

   - **Host mode**: `:worktree-path` in context, no `:executor` / `:environment-id`;
     git/gh ops run directly on the host (local-dogfood path).

   Arguments:
   - workflow-state - Workflow state with :workflow/artifacts
   - context        - Execution context with :worktree-path, :executor,
                      :environment-id, :github-token, :logger, etc.
   - opts           - Options with :releaser (optional), :release-meta (optional)

   Returns:
   {:success? bool
    :artifacts [release-artifact]
    :errors []
    :metrics {...}}"
  [workflow-state context opts]
  (let [logger        (:logger context)
        initial-state (release-state workflow-state context opts)]

    (when logger
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path (:worktree-path initial-state)
                        :create-pr?    (:create-pr? initial-state)}}))

    (-> initial-state
        step-validate-inputs
        step-check-gh-auth
        step-generate-metadata
        step-create-branch
        step-stage-dirty-files
        step-validate-diff
        step-commit
        step-push
        step-create-pr
        step-generate-pr-doc
        step-update-pr-body
        step-build-artifact
        step-save-artifact
        pipeline->result)))

(defn ^{:stratum 5} publish-release
  "Second half of the release: push the branch prepare-release made, open
   the PR (when :create-pr?), and build and save the release artifact.
   `prepared` is a prepare-release result's :prepared; `context` is as for
   execute-release-phase. Returns a result as execute-release-phase."
  [prepared context]
  (-> (merge (release-state {} context {}) prepared)
      step-validate-inputs
      step-push
      step-create-pr
      step-generate-pr-doc
      step-update-pr-body
      step-build-artifact
      step-save-artifact
      pipeline->result))
