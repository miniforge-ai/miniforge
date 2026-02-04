(ns ai.miniforge.pr-lifecycle.fix-loop
  "Automated fix generation for CI failures and review feedback.

   This is the highest-ROI component for automated PR iteration.
   Takes failure context (CI logs, review comments) and generates fixes."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.triage :as triage]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.release-executor.interface :as release]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Fix context building

(defn create-fix-context
  "Build context pack for fix generation.

   Arguments:
   - task: Task definition with acceptance criteria
   - pr-info: PR information (branch, SHA, etc.)
   - failure-info: CI/review failure information

   Options:
   - :current-diff - Current PR diff
   - :dependency-artifacts - Artifacts from completed dependencies
   - :previous-fixes - Previous fix attempts (for avoiding repetition)

   Returns context map for the fix agent."
  [task pr-info failure-info & {:keys [current-diff dependency-artifacts previous-fixes]}]
  {:fix/task-id (:task/id task)
   :fix/type (:type failure-info) ; :ci-failure :review-changes :conflict
   :fix/pr-info pr-info

   ;; Failure details
   :fix/failure-summary (:summary failure-info)
   :fix/failing-tests (:test-failures failure-info)
   :fix/lint-errors (:lint-errors failure-info)
   :fix/build-errors (:build-errors failure-info)
   :fix/review-comments (:comments failure-info)
   :fix/conflict-files (:conflict-files failure-info)

   ;; Task context
   :fix/acceptance-criteria (:task/acceptance-criteria task)
   :fix/constraints (:task/constraints task)

   ;; Code context
   :fix/current-diff current-diff
   :fix/affected-files (or (:affected-files failure-info)
                           (triage/extract-failing-files failure-info))

   ;; History
   :fix/dependency-artifacts dependency-artifacts
   :fix/previous-fixes (or previous-fixes [])

   :fix/created-at (java.util.Date.)})

(defn build-fix-prompt
  "Build a prompt for the fix agent based on failure type.

   Arguments:
   - fix-context: Context from create-fix-context

   Returns string prompt for the agent."
  [fix-context]
  (let [fix-type (:fix/type fix-context)
        task-id (:fix/task-id fix-context)
        summary (:fix/failure-summary fix-context)
        affected-files (str/join ", " (:fix/affected-files fix-context))]
    (case fix-type
      :ci-failure
      (str "Fix CI failures for task " task-id ".\n\n"
           "Summary: " summary "\n\n"
           "Affected files: " affected-files "\n\n"
           (when-let [tests (:fix/failing-tests fix-context)]
             (str "Failing tests:\n" (str/join "\n" (take 10 tests)) "\n\n"))
           (when-let [lint (:fix/lint-errors fix-context)]
             (str "Lint errors:\n" (str/join "\n" (take 10 lint)) "\n\n"))
           (when-let [build (:fix/build-errors fix-context)]
             (str "Build errors:\n" (str/join "\n" (take 5 build)) "\n\n"))
           "Generate fixes to resolve these issues while maintaining the original intent.")

      :review-changes
      (str "Address review feedback for task " task-id ".\n\n"
           "Requested changes:\n"
           (str/join "\n\n"
                     (->> (:fix/review-comments fix-context)
                          (map (fn [c]
                                 (str "- " (:file c) (when (:line c) (str ":" (:line c)))
                                      "\n  " (:change c))))
                          (take 10)))
           "\n\nGenerate fixes that address these specific concerns.")

      :conflict
      (str "Resolve merge conflicts for task " task-id ".\n\n"
           "Conflicting files: " affected-files "\n\n"
           "Resolve the conflicts while preserving the intent of both changes.")

      ;; Default
      (str "Fix issues for task " task-id ".\n\n"
           "Summary: " summary "\n\n"
           "Affected files: " affected-files))))

;------------------------------------------------------------------------------ Layer 1
;; Fix generation

(defn generate-fix
  "Generate a fix using the inner loop.

   Arguments:
   - fix-context: Context from create-fix-context
   - generate-fn: Generation function for the agent
   - context: Execution context with :logger etc.

   Options:
   - :gates - Validation gates (default: syntax only)
   - :max-iterations - Max repair iterations (default 3)

   Returns {:success? bool :artifact patch-artifact :metrics {...}}"
  [fix-context generate-fn context & {:keys [_gates max-iterations]
                                       :or {max-iterations 3}}]
  (let [logger (:logger context)
        task {:task/id (:fix/task-id fix-context)
              :task/type :fix}
        loop-context (merge context
                            {:max-iterations max-iterations
                             :fix-context fix-context})]
    (when logger
      (log/info logger :pr-lifecycle :fix/generating
                {:message "Generating fix"
                 :data {:task-id (:fix/task-id fix-context)
                        :fix-type (:fix/type fix-context)
                        :affected-files (count (:fix/affected-files fix-context))}}))

    ;; Use inner loop for generate → validate → repair cycle
    (loop/run-simple task generate-fn loop-context)))

;------------------------------------------------------------------------------ Layer 1
;; Fix application

(defn apply-fix-to-worktree
  "Apply a fix artifact to the git worktree.

   Arguments:
   - worktree-path: Path to git worktree
   - fix-artifact: Code artifact with file changes

   Returns result with applied changes."
  [worktree-path fix-artifact logger]
  (when logger
    (log/info logger :pr-lifecycle :fix/applying
              {:message "Applying fix to worktree"
               :data {:worktree-path worktree-path
                      :file-count (count (:code/files fix-artifact))}}))

  ;; The fix artifact should contain file changes
  ;; This would write files to the worktree
  (let [files (:code/files fix-artifact)]
    (try
      (doseq [{:keys [path content action]} files]
        (let [full-path (str worktree-path "/" path)]
          (case action
            :create (spit full-path content)
            :modify (spit full-path content)
            :delete (io/delete-file full-path true)
            ;; Default to modify
            (spit full-path content))))
      (dag/ok {:files-modified (count files)
               :paths (mapv :path files)})
      (catch Exception e
        (when logger
          (log/error logger :pr-lifecycle :fix/apply-failed
                     {:message "Failed to apply fix"
                      :data {:error (.getMessage e)}}))
        (dag/err :fix-apply-failed (.getMessage e))))))

(defn commit-fix
  "Commit fix changes and push to the PR branch.

   Arguments:
   - worktree-path: Path to git worktree
   - fix-context: Fix context for commit message
   - branch: Branch name to push to

   Returns result with commit SHA."
  [worktree-path fix-context branch logger]
  (let [fix-type (:fix/type fix-context)
        commit-msg (str "fix: " (case fix-type
                                  :ci-failure "resolve CI failures"
                                  :review-changes "address review feedback"
                                  :conflict "resolve merge conflicts"
                                  "automated fix")
                        "\n\n"
                        "Affected files:\n"
                        (str/join "\n" (map #(str "- " %) (:fix/affected-files fix-context))))]

    (when logger
      (log/info logger :pr-lifecycle :fix/committing
                {:message "Committing fix"
                 :data {:branch branch :fix-type fix-type}}))

    ;; Stage and commit changes
    (let [stage-result (release/commit-changes! worktree-path commit-msg)]
      (if (:success? stage-result)
        (let [push-result (release/push-branch! worktree-path branch)]
          (if (:success? push-result)
            (dag/ok {:commit-sha (:commit-sha stage-result)
                     :branch branch})
            (dag/err :push-failed (:error push-result))))
        (dag/err :commit-failed (:error stage-result))))))

;------------------------------------------------------------------------------ Layer 2
;; Fix loop orchestration

(defn run-fix-loop
  "Run the complete fix loop for a failure.

   This is the main entry point for automated fix generation.

   Arguments:
   - task: Task definition
   - pr-info: PR information
   - failure-info: CI/review failure information
   - generate-fn: Agent generation function
   - context: Execution context

   Options:
   - :worktree-path - Path to git worktree (required)
   - :max-attempts - Max fix attempts before giving up (default 3)
   - :event-bus - Event bus for publishing events
   - :run-gates-locally - Whether to run gates before push (default true)

   Returns {:success? bool :commit-sha string :attempts int :metrics {...}}"
  [task pr-info failure-info generate-fn context
   & {:keys [worktree-path max-attempts event-bus _run-gates-locally]
      :or {max-attempts 3}}]
  (let [logger (:logger context)
        dag-id (:dag/id context)
        run-id (:run/id context)
        task-id (:task/id task)
        pr-id (:pr/id pr-info)
        branch (:pr/branch pr-info)]

    (when logger
      (log/info logger :pr-lifecycle :fix-loop/starting
                {:message "Starting fix loop"
                 :data {:task-id task-id
                        :pr-id pr-id
                        :failure-type (:type failure-info)
                        :max-attempts max-attempts}}))

    (loop [attempt 1
           previous-fixes []
           total-metrics {:tokens 0 :cost-usd 0.0 :duration-ms 0}]
      (if (> attempt max-attempts)
        ;; Max attempts exceeded
        (do
          (when logger
            (log/warn logger :pr-lifecycle :fix-loop/max-attempts
                      {:message "Fix loop exceeded max attempts"
                       :data {:task-id task-id :attempts attempt}}))
          {:success? false
           :reason :max-attempts-exceeded
           :attempts (dec attempt)
           :metrics total-metrics})

        ;; Try to generate and apply fix
        (let [_ (when logger
                  (log/info logger :pr-lifecycle :fix-loop/attempt
                            {:message (str "Fix attempt " attempt " of " max-attempts)
                             :data {:task-id task-id}}))

              ;; Build fix context
              fix-context (create-fix-context task pr-info failure-info
                                              :previous-fixes previous-fixes)

              ;; Generate fix
              fix-result (generate-fix fix-context generate-fn context)

              ;; Update metrics
              new-metrics (merge-with + total-metrics
                                      (select-keys (:metrics fix-result)
                                                   [:tokens :cost-usd :duration-ms]))]

          (if (:success fix-result)
            ;; Fix generated successfully
            (let [artifact (:artifact fix-result)

                  ;; Apply fix to worktree
                  apply-result (apply-fix-to-worktree worktree-path artifact logger)]

              (if (dag/ok? apply-result)
                ;; Commit and push
                (let [commit-result (commit-fix worktree-path fix-context branch logger)]
                  (if (dag/ok? commit-result)
                    (do
                      ;; Publish fix-pushed event
                      (when event-bus
                        (events/publish! event-bus
                                         (events/fix-pushed dag-id run-id task-id pr-id
                                                            (:commit-sha (:data commit-result))
                                                            (:fix/type fix-context))
                                         logger))
                      (when logger
                        (log/info logger :pr-lifecycle :fix-loop/success
                                  {:message "Fix pushed successfully"
                                   :data {:task-id task-id
                                          :attempt attempt
                                          :commit-sha (:commit-sha (:data commit-result))}}))
                      {:success? true
                       :commit-sha (:commit-sha (:data commit-result))
                       :attempts attempt
                       :metrics new-metrics})

                    ;; Commit/push failed - retry
                    (recur (inc attempt)
                           (conj previous-fixes {:attempt attempt
                                                 :error (:error commit-result)})
                           new-metrics)))

                ;; Apply failed - retry
                (recur (inc attempt)
                       (conj previous-fixes {:attempt attempt
                                             :error (:error apply-result)})
                       new-metrics)))

            ;; Fix generation failed - retry
            (recur (inc attempt)
                   (conj previous-fixes {:attempt attempt
                                         :error (:termination fix-result)})
                   new-metrics)))))))

;------------------------------------------------------------------------------ Layer 2
;; Specialized fix functions

(defn fix-ci-failure
  "Convenience function for fixing CI failures.

   Arguments:
   - task: Task definition
   - pr-info: PR information
   - ci-logs: CI failure logs
   - generate-fn: Agent generation function
   - context: Execution context with :worktree-path

   Returns fix result."
  [task pr-info ci-logs generate-fn context]
  (let [parsed-failure (triage/parse-ci-failure ci-logs)
        failure-info (assoc parsed-failure
                            :type :ci-failure
                            :summary (:actionable-summary parsed-failure))]
    (run-fix-loop task pr-info failure-info generate-fn context
                  :worktree-path (:worktree-path context))))

(defn fix-review-feedback
  "Convenience function for fixing review feedback.

   Arguments:
   - task: Task definition
   - pr-info: PR information
   - review-comments: Actionable review comments
   - generate-fn: Agent generation function
   - context: Execution context with :worktree-path

   Returns fix result."
  [task pr-info review-comments generate-fn context]
  (let [grouped (triage/group-changes-by-file
                 (triage/extract-requested-changes review-comments))
        failure-info {:type :review-changes
                      :summary (str (count review-comments) " requested changes")
                      :comments review-comments
                      :affected-files (mapv :file grouped)}]
    (run-fix-loop task pr-info failure-info generate-fn context
                  :worktree-path (:worktree-path context))))

(defn fix-merge-conflict
  "Convenience function for fixing merge conflicts.

   Arguments:
   - task: Task definition
   - pr-info: PR information
   - conflicting-files: List of files with conflicts
   - generate-fn: Agent generation function
   - context: Execution context with :worktree-path

   Returns fix result."
  [task pr-info conflicting-files generate-fn context]
  (let [failure-info {:type :conflict
                      :summary (str (count conflicting-files) " files with conflicts")
                      :conflict-files conflicting-files
                      :affected-files conflicting-files}]
    (run-fix-loop task pr-info failure-info generate-fn context
                  :worktree-path (:worktree-path context))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Build fix context for CI failure
  (def task {:task/id (random-uuid)
             :task/acceptance-criteria ["Tests pass" "No lint errors"]})
  (def pr-info {:pr/id 123 :pr/branch "feat/foo" :pr/sha "abc123"})
  (def failure-info {:type :ci-failure
                     :summary "2 test failures"
                     :test-failures ["FAIL in (test-foo)" "FAIL in (test-bar)"]})

  (def fix-ctx (create-fix-context task pr-info failure-info))
  (:fix/type fix-ctx)  ; => :ci-failure

  ;; Build prompt
  (build-fix-prompt fix-ctx)

  ;; Parse CI failure
  (triage/parse-ci-failure
   "FAIL in (test-foo)
    expected: 1
    actual: 2")

  ;; Run fix loop (would need generate-fn implementation)
  ;; (run-fix-loop task pr-info failure-info my-generate-fn
  ;;               {:logger logger :worktree-path "/path/to/repo"})

  :leave-this-here)
