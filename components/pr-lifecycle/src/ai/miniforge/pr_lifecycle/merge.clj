(ns ai.miniforge.pr-lifecycle.merge
  "Merge policy enforcement and PR merging.

   Handles the final step of the PR lifecycle - determining when
   a PR is ready to merge and executing the merge."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.logging.interface :as log]
   [babashka.process :as process]
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
   :auto-rebase-on-stale? true})

;------------------------------------------------------------------------------ Layer 0
;; GitHub CLI helpers

(defn- run-gh-command
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

;------------------------------------------------------------------------------ Layer 2
;; Merge orchestration

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
              ;; Publish merged event
              (when event-bus
                (let [sha-result (run-gh-command
                                  ["git" "rev-parse" "HEAD"]
                                  worktree-path)
                      merge-sha (when (dag/ok? sha-result)
                                  (:output (:data sha-result)))]
                  (events/publish! event-bus
                                   (events/merged dag-id run-id task-id pr-id merge-sha)
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

        ;; Not ready - check if we should auto-rebase
        (if (and (contains? (set (:blocking readiness)) :branch-not-up-to-date)
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

          ;; Can't merge and won't auto-rebase
          (dag/err :not-ready
                   "PR is not ready to merge"
                   {:blocking (:blocking readiness)}))))))

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
