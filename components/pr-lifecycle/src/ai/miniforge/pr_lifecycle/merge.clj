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

(ns ai.miniforge.pr-lifecycle.merge
  "Merge policy enforcement and PR merging.

   Handles the final step of the PR lifecycle - determining when
   a PR is ready to merge and executing the merge."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.conflict-resolution :as conflict-resolution]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Merge policies

(def merge-methods
  "Supported merge methods."
  #{:merge    ; Create merge commit
    :squash   ; Squash and merge
    :rebase}) ; Rebase and merge

(def default-merge-policy
  "Default merge policy."
  {:method :squash
   :require-ci-green? true
   :require-approvals? true
   :required-approvals 1
   :require-no-unresolved-threads? true
   :require-branch-up-to-date? true
   :delete-branch-after-merge? true
   :auto-rebase-on-stale? true
   ;; Spec §6.4 hook: when GitHub reports the PR as CONFLICTING with
   ;; its base, run the multi-parent merge resolution sub-workflow
   ;; via conflict-resolution/resolve-pr-conflicts!. Engages only if
   ;; the caller also supplied :resolve-fn on context (workflow side
   ;; injects workflow.merge-resolution/resolve-conflict! there).
   :auto-resolve-conflicts? true})

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn run-gh-command
  "Run a gh CLI command and return result."
  [args worktree-path]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        args)]
      (if (zero? (:exit result))
        (dag/ok {:output (str/trim (:out result ""))})
        (dag/err :gh-command-failed
                 (str/trim (:err result ""))
                 {:exit-code (:exit result)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

;------------------------------------------------------------------------------ Layer 1
;; Merge readiness checks

(defn check-ci-status
  "Check if CI is green for a PR."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "checks" (str pr-number) "--fail-on-error"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:ci-green? true})
      (dag/ok {:ci-green? false
               :error (:error result)}))))

(defn check-review-status
  "Check if PR has required approvals."
  [worktree-path pr-number _required-approvals]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json" "reviewDecision"]
                worktree-path)]
    (if (dag/ok? result)
      (let [output (:output (:data result))
            approved? (str/includes? (str/upper-case output) "APPROVED")]
        (dag/ok {:approved? approved?
                 :raw output}))
      result)))

(defn check-branch-status
  "Check if PR branch is up-to-date with base."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json" "mergeable,mergeStateStatus"]
                worktree-path)]
    (if (dag/ok? result)
      (let [output (:output (:data result))
            clean? (and (str/includes? output "MERGEABLE")
                        (or (str/includes? output "CLEAN")
                            (str/includes? output "HAS_HOOKS")))]
        (dag/ok {:up-to-date? clean?
                 :raw output}))
      result)))

(defn check-unresolved-threads
  "Check if PR has unresolved comment threads."
  [_worktree-path _pr-number]
  ;; gh doesn't have a direct way to check this
  ;; Would need GitHub API for accurate count
  (dag/ok {:has-unresolved? false
           :note "Thread resolution check requires GitHub API"}))

(defn evaluate-merge-readiness
  "Evaluate if a PR is ready to merge according to policy.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy map

   Returns {:ready? bool :checks {...} :blocking [...]}."
  [worktree-path pr-number policy]
  (let [ci-check (when (:require-ci-green? policy)
                   (check-ci-status worktree-path pr-number))
        review-check (when (:require-approvals? policy)
                       (check-review-status worktree-path pr-number
                                            (:required-approvals policy)))
        branch-check (when (:require-branch-up-to-date? policy)
                       (check-branch-status worktree-path pr-number))
        thread-check (when (:require-no-unresolved-threads? policy)
                       (check-unresolved-threads worktree-path pr-number))

        checks {:ci ci-check
                :review review-check
                :branch branch-check
                :threads thread-check}

        blocking (cond-> []
                   (and ci-check (not (:ci-green? (:data ci-check))))
                   (conj :ci-not-green)

                   (and review-check (not (:approved? (:data review-check))))
                   (conj :not-approved)

                   (and branch-check (not (:up-to-date? (:data branch-check))))
                   (conj :branch-not-up-to-date)

                   (and thread-check (:has-unresolved? (:data thread-check)))
                   (conj :unresolved-threads))]

    {:ready? (empty? blocking)
     :checks checks
     :blocking blocking}))

;------------------------------------------------------------------------------ Layer 2
;; Merge execution

(defn merge-pr!
  "Merge a PR using gh CLI.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy (or use defaults)

   Returns result with merge information."
  [worktree-path pr-number & {:keys [policy]
                              :or {policy default-merge-policy}}]
  (let [method-flag (case (:method policy :squash)
                      :squash "--squash"
                      :rebase "--rebase"
                      :merge "--merge")
        delete-flag (when (:delete-branch-after-merge? policy)
                      "--delete-branch")
        args (cond-> ["gh" "pr" "merge" (str pr-number) method-flag "--auto"]
               delete-flag (conj delete-flag))
        result (run-gh-command args worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:merged? true
               :method (:method policy)
               :output (:output (:data result))})
      result)))

(defn enable-auto-merge!
  "Enable auto-merge for a PR (merges when all checks pass).

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy

   Returns result."
  [worktree-path pr-number & {:keys [policy]
                              :or {policy default-merge-policy}}]
  (let [method-flag (case (:method policy :squash)
                      :squash "--squash"
                      :rebase "--rebase"
                      :merge "--merge")
        result (run-gh-command
                ["gh" "pr" "merge" (str pr-number) method-flag "--auto"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:auto-merge-enabled? true})
      result)))

(defn disable-auto-merge!
  "Disable auto-merge for a PR."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "merge" (str pr-number) "--disable-auto"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:auto-merge-disabled? true})
      result)))

(defn rebase-pr!
  "Rebase a PR onto the latest base branch.

   Arguments:
   - worktree-path: Path to git worktree
   - branch: PR branch name

   Returns result with new HEAD SHA."
  [worktree-path branch]
  (let [;; Fetch latest base
        fetch-result (run-gh-command
                      ["git" "fetch" "origin" "main"]
                      worktree-path)]
    (if (dag/err? fetch-result)
      fetch-result
      (let [;; Attempt rebase
            rebase-result (run-gh-command
                           ["git" "rebase" "origin/main"]
                           worktree-path)]
        (if (dag/ok? rebase-result)
          ;; Push rebased branch
          (let [push-result (run-gh-command
                             ["git" "push" "--force-with-lease" "origin" branch]
                             worktree-path)]
            (if (dag/ok? push-result)
              (let [sha-result (run-gh-command
                                ["git" "rev-parse" "HEAD"]
                                worktree-path)]
                (dag/ok {:rebased? true
                         :new-sha (when (dag/ok? sha-result)
                                    (:output (:data sha-result)))}))
              (dag/err :push-failed (:error push-result))))
          (dag/err :rebase-failed (:error rebase-result)))))))

;------------------------------------------------------------------------------ Layer 1
;; Conflict-resolution dispatch (Stage 3d, spec §6.4)

(defn- parse-gh-json
  "Parse `gh --json` output as JSON via Cheshire. Returns the parsed
   map (keywordized keys) on success or nil if the body isn't valid
   JSON. Cheshire matches what github.clj / pr_poller.clj already
   use; the prior regex-based parse here was brittle to formatting
   and escaping changes in gh's output."
  [body]
  (try (json/parse-string body true)
       (catch Exception _ nil)))

(defn- pr-info-from-gh
  "Fetch the fields conflict-resolution/resolve-pr-conflicts! needs
   from `gh pr view`: PR number, branch (headRefName), base
   (baseRefName), head SHA (headRefOid), base SHA (baseRefOid).
   Returns dag/ok with the `{:pr/* ...}` shape on success or the
   underlying gh failure as dag/err."
  [worktree-path pr-number]
  (let [r (run-gh-command
           ["gh" "pr" "view" (str pr-number) "--json"
            "number,headRefName,baseRefName,headRefOid,baseRefOid"]
           worktree-path)]
    (if (dag/err? r)
      r
      (let [out (:output (:data r))
            parsed (parse-gh-json out)
            head-branch (:headRefName parsed)
            base-branch (:baseRefName parsed)
            head-sha (:headRefOid parsed)
            base-sha (:baseRefOid parsed)]
        (cond
          (nil? parsed)
          (dag/err :pr-info-invalid-json
                   "Could not parse gh JSON output"
                   {:gh-output out})

          (and head-branch base-branch head-sha base-sha)
          (dag/ok {:pr/id        pr-number
                   :pr/branch    head-branch
                   :pr/base      base-branch
                   :pr/head-sha  head-sha
                   :pr/base-sha  base-sha})

          :else
          (dag/err :pr-info-incomplete
                   "gh JSON missing one or more PR head/base fields"
                   {:gh-output out
                    :missing (cond-> []
                               (not head-branch) (conj :headRefName)
                               (not base-branch) (conj :baseRefName)
                               (not head-sha)    (conj :headRefOid)
                               (not base-sha)    (conj :baseRefOid))}))))))

(defn- normalize-resolution-outcome
  "conflict-resolution/resolve-pr-conflicts! can return:
   - dag/ok on success;
   - dag/err on infrastructure failure; or
   - the bare `:dag-multi-parent-unresolvable` anomaly map (a
     terminal failure of the resolution sub-workflow itself).

   Bare anomaly maps break attempt-merge's implicit
   dag/ok-or-dag/err contract — callers that branch on
   dag/err?/dag/ok? would treat the anomaly as a generic blocked
   merge and could retry indefinitely. Wrap the anomaly into a
   dag/err with the distinct :code :conflict-unresolvable so the
   controller / train monitor can transition the PR to :failed
   instead of looping. The original anomaly is preserved under
   :data :anomaly for diagnostic surfacing."
  [outcome]
  (if (and (map? outcome)
           (contains? outcome :anomaly/category)
           (not (dag/ok? outcome))
           (not (dag/err? outcome)))
    (dag/err :conflict-unresolvable
             (or (:anomaly/message outcome)
                 "Conflict resolution sub-workflow declared the merge unresolvable")
             {:anomaly outcome})
    outcome))

(defn- attempt-conflict-resolution!
  "Spec §6.4 dispatch: when `branch-check` raw output classifies as
   :conflicting AND policy enables auto-resolve AND context carries
   `:resolve-fn`, run conflict-resolution/resolve-pr-conflicts! and
   return its outcome (normalized into dag/ok or dag/err — see
   normalize-resolution-outcome). Otherwise return nil so the
   caller falls back to the existing rebase/not-ready path.

   The resolve-fn comes from context (workflow side injects
   workflow.merge-resolution/resolve-conflict! at lifecycle ctor
   time) so pr-lifecycle stays free of a workflow dependency."
  [worktree-path pr-number policy context branch-raw]
  (let [resolve-fn (:resolve-fn context)
        state      (conflict-resolution/classify-merge-state branch-raw)]
    (when (and (= :conflicting state)
               (:auto-resolve-conflicts? policy)
               resolve-fn)
      (let [pr-r (pr-info-from-gh worktree-path pr-number)]
        (if (dag/err? pr-r)
          pr-r
          (normalize-resolution-outcome
           (conflict-resolution/resolve-pr-conflicts!
            {:worktree-path worktree-path
             :pr            (:data pr-r)
             :resolve-fn    resolve-fn
             :context       context})))))))

;------------------------------------------------------------------------------ Layer 2
;; Merge orchestration

(defn fetch-pr-labels!
  "Fetch the label names attached to a PR via `gh pr view --json labels`.

   Returns a `#{}` of label-name strings (GitHub-native, literal,
   no case-folding). Returns `#{}` on any failure — label fetch is
   best-effort and never blocks the merge-event publish path. The
   first downstream consumer is the M2 pr-label-actions watcher."
  [worktree-path pr-number]
  (try
    (let [result (run-gh-command
                  ["gh" "pr" "view" (str pr-number) "--json" "labels"]
                  worktree-path)]
      (if (dag/ok? result)
        (let [body (json/parse-string (:output (:data result)) true)]
          (->> (:labels body)
               (keep :name)
               set))
        #{}))
    (catch Exception _ #{})))

(defn attempt-merge
  "Attempt to merge a PR, handling common failure cases.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy
   - context: Context with :dag/id :run/id :task/id :pr/id :event-bus :logger

   Returns result with merge status and events."
  [worktree-path pr-number policy context]
  (let [logger (:logger context)
        event-bus (:event-bus context)
        {:keys [dag-id run-id task-id pr-id]} context]

    (when logger
      (log/info logger :pr-lifecycle :merge/attempting
                {:message "Attempting PR merge"
                 :data {:pr-number pr-number}}))

    ;; First check if ready
    (let [readiness (evaluate-merge-readiness worktree-path pr-number policy)]
      (if (:ready? readiness)
        ;; Ready to merge - attempt it
        (let [merge-result (merge-pr! worktree-path pr-number :policy policy)]
          (if (dag/ok? merge-result)
            (do
              ;; Publish merged event with the PR's labels so downstream
              ;; watchers (label-actions M2) can match without re-querying
              ;; GitHub. Label fetch is best-effort — missing labels
              ;; default to #{}, never block the merge event.
              (when event-bus
                (let [sha-result (run-gh-command
                                  ["git" "rev-parse" "HEAD"]
                                  worktree-path)
                      merge-sha (when (dag/ok? sha-result)
                                  (:output (:data sha-result)))
                      labels (fetch-pr-labels! worktree-path pr-number)]
                  (events/publish! event-bus
                                   (events/merged dag-id run-id task-id pr-id
                                                  merge-sha labels)
                                   logger)))
              (when logger
                (log/info logger :pr-lifecycle :merge/success
                          {:message "PR merged successfully"
                           :data {:pr-number pr-number}}))
              (dag/ok {:merged? true
                       :method (:method policy)}))

            ;; Merge failed
            (dag/err :merge-failed
                     "Merge command failed"
                     {:gh-error (:error merge-result)})))

        ;; Not ready — branch-not-up-to-date may mean either
        ;; CONFLICTING (Stage 3 §6.4 hook engages) or BEHIND (auto-
        ;; rebase). Attempt conflict-resolution first; only if it
        ;; declines (state isn't :conflicting, or policy/resolver
        ;; absent) fall through to the rebase path.
        (let [branch-raw (get-in (:branch (:checks readiness))
                                 [:data :raw])
              cr-result  (when (contains? (set (:blocking readiness))
                                          :branch-not-up-to-date)
                           (attempt-conflict-resolution!
                            worktree-path pr-number policy context
                            branch-raw))]
          (cond
            ;; conflict-resolution engaged — return its outcome
            ;; (success, dag/err, or terminal anomaly) directly. The
            ;; lifecycle's outer loop re-evaluates merge readiness
            ;; on the next cycle once GitHub re-classifies the PR.
            (some? cr-result)
            cr-result

            (and (contains? (set (:blocking readiness)) :branch-not-up-to-date)
                 (:auto-rebase-on-stale? policy))
          ;; Try to rebase
          (do
            (when logger
              (log/info logger :pr-lifecycle :merge/rebasing
                        {:message "PR is stale, attempting rebase"}))
            (let [branch-result (run-gh-command
                                 ["gh" "pr" "view" (str pr-number) "--json" "headRefName"]
                                 worktree-path)
                  branch (when (dag/ok? branch-result)
                           ;; Parse branch name from JSON
                           (second (re-find #"\"headRefName\":\"([^\"]+)\""
                                            (:output (:data branch-result)))))]
              (if branch
                (let [rebase-result (rebase-pr! worktree-path branch)]
                  (if (dag/ok? rebase-result)
                    ;; Rebase succeeded - CI will run again, then we can merge
                    (do
                      (when event-bus
                        (events/publish! event-bus
                                         (events/rebase-needed dag-id run-id task-id pr-id
                                                               (:new-sha (:data rebase-result)))
                                         logger))
                      (dag/ok {:merged? false
                               :rebased? true
                               :new-sha (:new-sha (:data rebase-result))}))
                    rebase-result))
                (dag/err :branch-not-found "Could not determine PR branch"))))

            :else
            ;; Can't merge and won't auto-rebase
            (dag/err :not-ready
                     "PR is not ready to merge"
                     {:blocking (:blocking readiness)})))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Default merge policy
  default-merge-policy

  ;; Check merge readiness
  (evaluate-merge-readiness "/path/to/repo" 123 default-merge-policy)

  ;; Merge a PR
  (merge-pr! "/path/to/repo" 123 :policy {:method :squash
                                          :delete-branch-after-merge? true})

  ;; Enable auto-merge
  (enable-auto-merge! "/path/to/repo" 123)

  ;; Rebase a PR
  (rebase-pr! "/path/to/repo" "feat/my-feature")

  ;; Full merge attempt with event handling
  (attempt-merge "/path/to/repo" 123
                 default-merge-policy
                 {:dag-id (random-uuid)
                  :run-id (random-uuid)
                  :task-id (random-uuid)
                  :pr-id 123
                  :logger nil
                  :event-bus nil})

  :leave-this-here)
