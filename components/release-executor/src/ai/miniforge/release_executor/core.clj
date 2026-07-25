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

(defn ^{:stratum 0} provenance-frontmatter
  "YAML frontmatter mapping a PR back to its workflow run + spec. Built from
   state's :provenance ({:workflow :spec :task}) + :commit-sha. Always emits
   `generated-by: miniforge` (authorship) plus whatever provenance is present.
   Visible in the rendered PR — human-readable AND deterministically parsable
   (fixed position, explicit `---` delimiters, rigid key: value)."
  [{:keys [provenance commit-sha]}]
  (let [{:keys [workflow spec task]} provenance
        rows (cond-> []
               workflow   (conj (str "miniforge-workflow: " workflow))
               spec       (conj (str "spec: " spec))
               task       (conj (str "task: " task))
               commit-sha (conj (str "commit: " commit-sha))
               true       (conj "generated-by: miniforge"))]
    (str "---\n" (str/join "\n" rows) "\n---\n\n")))

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

(defn- ^{:stratum 0} pr-body-needs-update?
  "True when the post-create PR body should be overwritten."
  [release-meta]
  (let [body (:release/pr-body release-meta)]
    (or (nil? body)
        (str/blank? body)
        (= (str/trim body) (str/trim (str (:release/pr-title release-meta)))))))

(defn ^{:stratum 0} pipeline->result
  "Convert pipeline state to phase result."
  [state]
  (let [{:keys [logger failure release-artifact write-metrics
                branch commit-sha pr-number pr-url]} state]
    (if failure
      (result/phase-failure (:type failure) (:message failure)
                            {:hint    (:hint failure)
                             :metrics (or write-metrics {})})
      (do
        (when logger
          (log/info logger :release-executor :phase-completed
                    {:data {:branch        branch
                            :commit        commit-sha
                            :pr-url        pr-url
                            :files-written (:files-written write-metrics)}}))
        (result/phase-success
         [release-artifact]
         (merge write-metrics
                {:pr-number  pr-number
                 :pr-url     pr-url
                 :commit-sha commit-sha
                 :branch     branch}))))))

;------------------------------------------------------------------------------ Layer 1

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
    (and (not (str/blank? (:worktree-path state)))
         (nil? (:executor state))
         (nil? (:environment-id state)))
    (assoc state :host-mode? true)

    ;; Sandbox-mode guards — same order/messages as before
    (str/blank? (:worktree-path state))
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
            release-meta (metadata/generate-release-metadata
                          releaser code-artifacts task-description context logger
                          workflow-data)]
        (if release-meta
          (assoc state :release-meta release-meta)
          (fail state :metadata-generation-failed (msg/t :step/metadata-generation-failed)))))))

(defn ^{:stratum 1} step-create-branch
  "Create the release branch.  Dispatches git backend based on :host-mode?."
  [state]
  (if (failed? state)
    state
    (let [{:keys [release-meta host-mode? worktree-path executor environment-id
                  base-branch-override logger]} state
          branch-name (:release/branch-name release-meta)
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
          (assoc state :branch (:branch result) :base-branch base))))))

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
                   (sandbox/commit-changes! executor environment-id
                                            (:release/commit-message release-meta)))]
      (if (result/succeeded? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn ^{:stratum 1} step-push
  "Push branch to origin.  Dispatches git backend based on :host-mode?."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [branch host-mode? worktree-path executor environment-id]} state
          result (if host-mode?
                   (git/push-branch! worktree-path branch (:github-token state))
                   (sandbox/push-branch! executor environment-id branch
                                         (gh-exec-opts state)))]
      (if (result/succeeded? result)
        state
        (fail state :push-failed (:error result))))))

(defn ^{:stratum 1} with-provenance
  "Prepend the provenance frontmatter to a PR body. Idempotent: a body that
   already opens with a `---` frontmatter block is returned unchanged."
  [body state]
  (let [body (str body)]
    (if (str/starts-with? body "---\n")
      body
      (str (provenance-frontmatter state) body))))

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
          filename (pr-doc-filename (:release/pr-title release-meta))
          rel-path (str "docs/pull-requests/" filename)
          content  (render-pr-doc release-meta
                                  {:pr-number pr-number
                                   :pr-url    pr-url
                                   :branch    branch})]
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

(defn ^{:stratum 2} step-create-pr
  "Create the pull request.  Dispatches git backend based on :host-mode?.

   git/create-pr! carries the same duplicate-PR reuse logic as
   sandbox/create-pr! — retry-safe on both backends."
  [state]
  (cond
    (failed? state)           state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta base-branch host-mode? worktree-path
                  executor environment-id]} state
          pr-opts {:title       (:release/pr-title release-meta)
                   :body        (with-provenance (:release/pr-body release-meta) state)
                   :base-branch base-branch}
          result (if host-mode?
                   (git/create-pr! worktree-path pr-opts (:github-token state))
                   (sandbox/create-pr! executor environment-id pr-opts
                                       (gh-exec-opts state)))]
      (if (result/succeeded? result)
        (assoc state
               :pr-number (:pr-number result)
               :pr-url    (:pr-url result))
        (fail state :pr-create-failed (:error result))))))

(defn ^{:stratum 2} step-update-pr-body
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
          filename (pr-doc-filename (:release/pr-title release-meta))
          rel-path (str "docs/pull-requests/" filename)
          content  (render-pr-doc-full
                    release-meta
                    {:pr-number pr-number :pr-url pr-url :branch branch}
                    code-artifacts
                    workflow-data)]
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
                          (git/force-push! worktree-path (:github-token state))
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

;; Main execute function
(defn ^{:stratum 3} execute-release-phase
  "Execute the release phase.

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
  (let [logger         (:logger context)
        executor       (:executor context)
        environment-id (:environment-id context)
        workflow-artifacts (:workflow/artifacts workflow-state)
        initial-state  (cond-> {:logger         logger
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
                                :executor       executor
                                :environment-id environment-id
                                :github-token   (:github-token context)}
                       (:release-meta opts) (assoc :release-meta (:release-meta opts)))]

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
