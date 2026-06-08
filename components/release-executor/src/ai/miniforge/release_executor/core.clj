(ns ai.miniforge.release-executor.core
  "Release phase executor - orchestrates the release pipeline.

   In the environment model, code changes live in the executor's git worktree.
   The release phase stages dirty files, commits, pushes, and creates a PR.
   No file writes from code artifacts — the implement agent already wrote files
   to the worktree during the implement phase.

   All git/gh operations route through the DAG executor (sandbox module) so
   that governed-mode capsules never shell out to the host."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.metadata :as metadata]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline helpers

(defn failed?
  "Check if pipeline has failed."
  [state]
  (contains? state :failure))

(defn fail
  "Mark pipeline as failed with error info."
  [state error-type error-msg & {:keys [hint]}]
  (let [logger (:logger state)]
    (when logger
      (log/error logger :release-executor error-type {:message error-msg}))
    (assoc state :failure
           (cond-> {:type error-type :message error-msg}
             hint (assoc :hint hint)))))

(defn- gh-exec-opts
  "Build executor opts with GH_TOKEN env var when github-token is present.
   The token is required for gh CLI commands (gh pr create, gh auth status)
   inside the capsule where the host's gh auth context is not available."
  [state]
  (if-let [token (:github-token state)]
    {:env {"GH_TOKEN" token}}
    {}))

(defn extract-code-artifacts
  "Extract code artifacts from workflow artifacts (used for PR metadata generation)."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))
       (remove nil?)))

(defn extract-workflow-data
  "Extract review and test artifacts from workflow artifacts for PR metadata.
   Returns a map with :review-artifacts and :test-artifacts."
  [workflow-artifacts]
  {:review-artifacts (metadata/extract-review-artifacts workflow-artifacts)
   :test-artifacts (metadata/extract-test-artifacts workflow-artifacts)})

;------------------------------------------------------------------------------ Layer 1
;; Pipeline steps

(defn step-validate-inputs [state]
  (cond
    (failed? state)              state
    (not (:worktree-path state)) (fail state :missing-worktree-path (msg/t :exec/missing-worktree-path))
    (not (:executor state))      (fail state :missing-executor (msg/t :exec/missing-executor))
    (not (:environment-id state)) (fail state :missing-environment-id (msg/t :exec/missing-environment-id))
    :else                        state))

(defn step-check-gh-auth [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [gh-auth (sandbox/check-gh-auth! (:executor state) (:environment-id state)
                                           (gh-exec-opts state))]
      (if (:authenticated? gh-auth)
        state
        (fail state :gh-auth-failed (:error gh-auth)
              :hint (if (:available? gh-auth)
                      (msg/t :gh/auth-login-hint)
                      (msg/t :gh/install-hint)))))))

(defn step-generate-metadata [state]
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

(defn step-create-branch [state]
  (if (failed? state)
    state
    (let [{:keys [release-meta executor environment-id base-branch-override]} state
          branch-name (:release/branch-name release-meta)
          result (sandbox/create-branch! executor environment-id branch-name)]
      (if-not (result/succeeded? result)
        (fail state :branch-create-failed (:error result))
        (let [detected  (:base-branch result)
              logger    (:logger state)
              ;; A chained DAG task's parent branch (override) becomes the PR
              ;; base so the PR stacks on the parent. create-branch! fetched
              ;; only origin/<detected>; fetch the override too so later
              ;; origin/<base> range diffs / commits-ahead and the PR
              ;; merge-base resolve.
              override? (and base-branch-override (not= base-branch-override detected))
              fetch-r   (when override?
                          (sandbox/fetch-branch! executor environment-id base-branch-override))
              ;; Degrade gracefully: if the parent branch can't be fetched
              ;; (e.g. not yet on the remote), fall back to the detected
              ;; default rather than failing the release — the PR opens
              ;; against the default instead of stacking. Degraded, not fatal.
              fetch-ok? (or (not override?) (result/succeeded? fetch-r))
              base      (if (and override? fetch-ok?) base-branch-override detected)]
          (when (and override? (not fetch-ok?) logger)
            (log/warn logger :release-executor :base-branch-fetch-degraded
                      {:message (str "Could not fetch base branch " base-branch-override
                                     "; PR targets " detected " instead of stacking.")}))
          (assoc state :branch (:branch result) :base-branch base))))))

(defn step-stage-dirty-files
  "Stage all dirty files in the executor environment.

   In the environment model, files are already in the worktree (written by
   the implement agent); this step stages them for commit.

   Empty-stage handling: when nothing is dirty, the implementer's writes
   may already be committed on the branch as phase-boundary commits (the
   workflow runtime persists each phase boundary as a commit on the task
   branch since 2026-04-30). In that case `git rev-list --count
   origin/<base>..HEAD` reports the carry-forward commits and we treat
   that as success — step-commit will skip cleanly and step-push will
   ship the existing commits."
  [state]
  (if (failed? state)
    state
    (let [{:keys [executor environment-id base-branch]} state
          stage-r (sandbox/stage-files! executor environment-id :all)
          staged-r (when (result/succeeded? stage-r)
                     (sandbox/exec! executor environment-id "git diff --cached --name-only"))
          staged-output (get staged-r :output "")
          staged-count (count (remove str/blank? (str/split-lines staged-output)))]
      (cond
        (not (result/succeeded? stage-r))
        (fail state :stage-failed (:error stage-r))

        (pos? staged-count)
        (assoc state :write-metrics {:total-operations staged-count
                                     :files-written staged-count})

        ;; Nothing dirty in the worktree, but the branch may already
        ;; carry the work as boundary commits — accept that as success.
        (let [ahead (sandbox/commits-ahead-of-base executor environment-id base-branch)]
          (and ahead (pos? ahead)))
        (assoc state :write-metrics {:total-operations 0 :files-written 0
                                     :preexisting-commits true})

        :else
        (fail state :no-files-to-stage (msg/t :step/no-files-to-stage))))))

(defn- net-negative-tests?
  "True when the diff removes more test definitions than it adds."
  [{:keys [removed added] :as test-counts}]
  (and test-counts (pos? removed) (> removed added)))

(defn- heavily-destructive?
  "True when deletions exceed 20 lines and outnumber additions 3-to-1."
  [{:keys [deletions additions] :as diff-stats}]
  (and diff-stats
       (> deletions 20)
       (> deletions (* 3 (max 1 additions)))))

(defn step-validate-diff
  "Validate diff is not destructive before committing. Rejects changes
   that delete more tests than they add or that empty files. Routes
   through the executor (sandbox) so governed-mode capsules never
   shell out to the host.

   In the boundary-commits case (`:preexisting-commits true`), the
   staged index is empty — the work is in commits on the branch, not
   in the index — so validation reads the range diff
   `origin/<base>..HEAD` instead. Without this branch the destructive-
   diff gate would silently no-op on every boundary-commit release."
  [state]
  (if (failed? state)
    state
    (let [{:keys [executor environment-id logger base-branch]} state
          preexisting? (get-in state [:write-metrics :preexisting-commits])
          diff-stats  (if preexisting?
                        (sandbox/diff-stats-range executor environment-id base-branch)
                        (sandbox/diff-stats executor environment-id))
          test-counts (if preexisting?
                        (sandbox/count-test-defs-range executor environment-id base-branch)
                        (sandbox/count-test-defs executor environment-id))]
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

(defn step-commit
  "Commit the staged changes with the release-meta message.

   Skipped when step-stage-dirty-files found no dirty files and the
   branch already carries the work as phase-boundary commits — the
   existing HEAD becomes the release commit and the boundary-commit
   messages are what end up in the PR. (This matches the worktree-as-
   artifact handoff model: the work is in the branch already; release
   is publishing it, not re-recording it.)"
  [state]
  (cond
    (failed? state) state

    (get-in state [:write-metrics :preexisting-commits])
    (let [sha-r (sandbox/exec! (:executor state) (:environment-id state)
                               "git rev-parse HEAD")]
      (cond-> state
        (result/succeeded? sha-r)
        (assoc :commit-sha (str/trim (:output sha-r "")))))

    :else
    (let [{:keys [release-meta executor environment-id]} state
          result (sandbox/commit-changes! executor environment-id
                                          (:release/commit-message release-meta))]
      (if (result/succeeded? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn step-push [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [branch executor environment-id]} state
          result (sandbox/push-branch! executor environment-id branch
                                       (gh-exec-opts state))]
      (if (result/succeeded? result)
        state
        (fail state :push-failed (:error result))))))

(defn step-create-pr [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [release-meta base-branch executor environment-id]} state
          result (sandbox/create-pr! executor environment-id
                                     {:title (:release/pr-title release-meta)
                                      :body (:release/pr-body release-meta)
                                      :base-branch base-branch}
                                     (gh-exec-opts state))]
      (if (result/succeeded? result)
        (assoc state
               :pr-number (:pr-number result)
               :pr-url (:pr-url result))
        (fail state :pr-create-failed (:error result))))))

(defn- pr-doc-filename
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

(defn- render-pr-doc
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

(defn- format-files-changed
  "Format a list of files changed from code artifacts as a markdown bullet list."
  [code-artifacts]
  (let [files (mapcat :code/files code-artifacts)]
    (if (seq files)
      (str/join "\n" (map #(str "- `" (:path %) "` (" (name (get % :action :create)) ")") files))
      "_No file changes recorded._")))

(defn- format-test-results
  "Format test results from test artifacts as markdown.
   Extracts result status, pass/fail counts, and summary text."
  [test-artifacts]
  (if (seq test-artifacts)
    (let [latest (last test-artifacts)
          results (:test/results latest)
          summary (:test/summary latest)
          total (:test/total latest)
          passed (:test/passed latest)
          failed (:test/failed latest)]
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

(defn- format-review-decision
  "Format review decision and summary from review artifacts as markdown."
  [review-artifacts]
  (if (seq review-artifacts)
    (let [latest (last review-artifacts)
          decision (:review/decision latest)
          summary (:review/summary latest)]
      (str (when decision
             (str "**Decision**: " (name decision) "\n"))
           (when (and summary (not (str/blank? summary)))
             (str "\n" summary))))
    "_No review artifacts available._"))

(defn- render-pr-doc-full
  "Render a comprehensive docs/pull-requests/ markdown file.
   Includes: title, summary, files changed, test results, review decision."
  [release-meta state-info code-artifacts workflow-data]
  (let [{:keys [release/pr-title release/pr-description release/commit-message]} release-meta
        {:keys [pr-number pr-url branch]} state-info
        review-artifacts (:review-artifacts workflow-data)
        test-artifacts (:test-artifacts workflow-data)
        files-md (format-files-changed code-artifacts)
        tests-md (format-test-results test-artifacts)
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

(defn step-write-pr-doc
  "Write a docs/pull-requests/ markdown file, stage it, and amend the commit.
   Runs after step-create-pr so PR number/URL are available.
   Skipped when :create-pr? is false (no PR → no PR doc needed).
   All I/O routes through the executor so governed-mode capsules never
   touch the host filesystem."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path release-meta pr-number pr-url branch
                  executor environment-id logger]} state
          filename (pr-doc-filename (:release/pr-title release-meta))
          rel-path (str "docs/pull-requests/" filename)
          full-path (str worktree-path "/" rel-path)
          content (render-pr-doc release-meta
                                 {:pr-number pr-number
                                  :pr-url pr-url
                                  :branch branch})]
      (try
        ;; Write via executor (governed) — never touch host filesystem
        (sandbox/write-file! executor environment-id full-path content)
        (when logger
          (log/info logger :release-executor :pr-doc-written
                    {:data {:path rel-path}}))
        ;; Stage the doc and amend the commit to include it
        (sandbox/exec! executor environment-id
                       (str "git add " rel-path))
        (sandbox/exec! executor environment-id
                       "git commit --amend --no-edit --no-verify")
        ;; Force-push since we amended — route through executor with token
        (sandbox/exec! executor environment-id
                       "git push --force-with-lease"
                       (gh-exec-opts state))
        state
      (catch Exception e
        (when logger
          (log/warn logger :release-executor :pr-doc-write-failed
                    {:message (.getMessage e)}))
        ;; Non-fatal: continue pipeline even if doc write fails
        state)))))

(defn- looks-like-structured-pr-body?
  "True when a string already starts with the canonical PR-body shape
   (a `## Summary` header at top, possibly after a blank line). When
   the releaser agent obeyed the updated prompt it produces this shape
   directly; in that case the fallback must NOT wrap it under another
   `## Summary` (which would double-nest the headings)."
  [s]
  (and (string? s)
       (boolean (re-find #"(?m)\A\s*##\s+Summary\b" s))))

(defn- non-blank-section
  "Render `(str header body \"\\n\\n\")` only when `body` is a non-blank
   string. Guards the section against rendering an empty `## Header`
   when the formatter returned `\"\"` for an artifact with no data."
  [header body]
  (when (and body (not (str/blank? body)))
    (str header body "\n\n")))

(defn- render-pr-body-fallback
  "Render a structured GitHub PR body when the releaser agent didn't
   produce one. Distinct from `render-pr-doc-full` (which targets the
   committed docs/pull-requests/*.md file): no HTML copyright header,
   no `_No X available._` placeholder strings — those belong in the
   docs file for repo browsers, not in the PR description GitHub
   reviewers see first.

   When `:release/pr-description` already looks like a full PR body
   (starts with `## Summary`), use it as the body verbatim and append
   only the structured sections (Files Changed / Test Results /
   Review) — wrapping a structured pr-description under another
   `## Summary` would double-nest the heading."
  [release-meta code-artifacts workflow-data]
  (let [{:keys [release/pr-title release/pr-description]} release-meta
        review-artifacts (:review-artifacts workflow-data)
        test-artifacts   (:test-artifacts workflow-data)
        files-md         (format-files-changed code-artifacts)
        ;; Sections are omitted entirely when their formatted markdown is
        ;; blank — `format-test-results` etc. can return "" when artifacts
        ;; exist but lack the expected fields, so `(seq artifacts)` alone
        ;; isn't enough to gate the section.
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

(defn- pr-body-needs-update?
  "True when the post-create PR body should be overwritten. Only fires
   when the agent failed to produce a useful body — never clobbers a
   real :release/pr-body from the releaser agent."
  [release-meta]
  (let [body (:release/pr-body release-meta)]
    (or (nil? body)
        (str/blank? body)
        ;; Treat a body that's just the title as a degraded agent
        ;; output worth replacing with the structured fallback.
        (= (str/trim body) (str/trim (str (:release/pr-title release-meta)))))))

(defn step-update-pr-body
  "Update the GitHub PR body ONLY when the initial body was missing or
   degraded (just the title, blank). When the releaser agent produced
   a real :release/pr-body, step-create-pr already posted it — do not
   overwrite. Crucially, the GitHub PR body is NOT the same surface as
   the committed docs/pull-requests/*.md file; use a body-specific
   renderer (`render-pr-body-fallback`), not the docs-file renderer."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    (not (:pr-number state)) state
    (not (pr-body-needs-update? (:release-meta state))) state
    :else
    (let [{:keys [release-meta pr-number executor environment-id logger
                  code-artifacts workflow-data]} state
          body (render-pr-body-fallback release-meta code-artifacts workflow-data)]
      (try
        (sandbox/edit-pr-body! executor environment-id
                               pr-number body
                               (gh-exec-opts state))
        (when logger
          (log/info logger :release-executor :pr-body-updated
                    {:data {:pr-number pr-number
                            :reason :degraded-agent-body
                            :source :fallback-renderer}}))
        state
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :pr-body-update-failed
                      {:message (.getMessage e)}))
          ;; Non-fatal: PR exists, just has the original (degraded) body.
          state)))))

(defn step-generate-pr-doc
  "Generate a comprehensive PR doc at docs/pull-requests/YYYY-MM-DD-<slug>.md.
   Includes title, summary, files changed, test results, and review decision.
   Stages the doc and amends the release commit to include it, then
   force-pushes so the PR branch reflects the amended commit.

   Runs after step-create-pr so PR number/URL are available.
   Skipped when :create-pr? is false (no PR → no PR doc needed).
   All I/O routes through the executor so governed-mode capsules never
   touch the host filesystem."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path release-meta pr-number pr-url branch
                  executor environment-id logger code-artifacts workflow-data]} state
          filename (pr-doc-filename (:release/pr-title release-meta))
          rel-path (str "docs/pull-requests/" filename)
          full-path (str worktree-path "/" rel-path)
          content (render-pr-doc-full
                   release-meta
                   {:pr-number pr-number :pr-url pr-url :branch branch}
                   code-artifacts
                   workflow-data)]
      (try
        ;; Write via executor (governed) — never touch host filesystem
        (sandbox/write-file! executor environment-id full-path content)
        (when logger
          (log/info logger :release-executor :pr-doc-generated
                    {:data {:path rel-path}}))
        ;; Stage the doc and amend the commit to include it
        (sandbox/exec! executor environment-id
                       (str "git add " rel-path))
        (sandbox/exec! executor environment-id
                       "git commit --amend --no-edit --no-verify")
        ;; Force-push since we amended — route through executor with token
        (sandbox/exec! executor environment-id
                       "git push --force-with-lease"
                       (gh-exec-opts state))
        (assoc state
               :pr-doc-path rel-path
               :pr-doc-content content)
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :pr-doc-generation-failed
                      {:message (.getMessage e)}))
          ;; Non-fatal: continue pipeline even if doc generation fails
          state)))))

(defn step-build-artifact [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path branch base-branch commit-sha create-pr?
                  pr-number pr-url release-meta write-metrics code-artifacts]} state
          release-content (merge write-metrics
                                 {:git-staged? true
                                  :worktree-path (str worktree-path)
                                  :branch branch
                                  :base-branch base-branch
                                  :commit-sha commit-sha
                                  :pr-created? (boolean create-pr?)
                                  :pr-number pr-number
                                  :pr-url pr-url
                                  :release-metadata release-meta})
          release-artifact (artifact/build-artifact
                            {:id (random-uuid)
                             :type :release
                             :version "1.0.0"
                             :content release-content
                             :metadata {:phase :release
                                        :code-artifacts-count (count code-artifacts)}})]
      (assoc state :release-artifact release-artifact))))

(defn step-save-artifact [state]
  (if (failed? state)
    state
    (do
      (when-let [artifact-store (:artifact-store state)]
        (try
          (artifact/save! artifact-store (:release-artifact state))
          (catch Exception _e nil)))
      state)))

(defn pipeline->result
  "Convert pipeline state to phase result."
  [state]
  (let [{:keys [logger failure release-artifact write-metrics
                branch commit-sha pr-number pr-url]} state]
    (if failure
      (result/phase-failure (:type failure) (:message failure)
                            {:hint (:hint failure)
                             :metrics (or write-metrics {})})
      (do
        (when logger
          (log/info logger :release-executor :phase-completed
                    {:data {:branch branch
                            :commit commit-sha
                            :pr-url pr-url
                            :files-written (:files-written write-metrics)}}))
        (result/phase-success
         [release-artifact]
         (merge write-metrics
                {:pr-number pr-number
                 :pr-url pr-url
                 :commit-sha commit-sha
                 :branch branch}))))))

;------------------------------------------------------------------------------ Layer 2
;; Main execute function

(defn execute-release-phase
  "Execute the release phase.

   Requires an executor environment (worktree) acquired before this phase.
   Stages dirty files in the worktree, commits, pushes, and creates a PR.

   All git/gh operations route through the sandbox module (DAG executor)
   so that governed-mode capsules never shell out to the host. The GitHub
   token (when provided via :github-token in context) is injected as the
   GH_TOKEN env var for gh CLI commands inside the capsule.

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
  (let [logger (:logger context)
        executor (:executor context)
        environment-id (:environment-id context)
        workflow-artifacts (:workflow/artifacts workflow-state)
        initial-state (cond-> {:logger logger
                               :worktree-path (:worktree-path context)
                               :artifact-store (:artifact-store context)
                               :context context
                               :create-pr? (get context :create-pr? true)
                               ;; Explicit PR base override (a dependency-chained
                               ;; DAG task's parent branch). When present it wins
                               ;; over the branch-creation default so the PR
                               ;; stacks on the parent; diff/commits-ahead ranges
                               ;; key off it too, yielding this task's own layer.
                               :base-branch-override (:base-branch context)
                               :releaser (:releaser opts)
                               :task-description (get-in workflow-state [:workflow/spec :spec/description])
                               :code-artifacts (extract-code-artifacts workflow-artifacts)
                               :workflow-data (extract-workflow-data workflow-artifacts)
                               :executor executor
                               :environment-id environment-id
                               :github-token (:github-token context)}
                        (:release-meta opts) (assoc :release-meta (:release-meta opts)))]

    (when logger
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path (:worktree-path initial-state)
                        :create-pr? (:create-pr? initial-state)}}))

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
