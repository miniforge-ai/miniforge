(ns ai.miniforge.release-executor.core
  "Release phase executor - orchestrates the release pipeline.

   This is the main entry point that coordinates:
   - Git operations (branch, commit, push, PR)
   - File operations (write, delete, stage)
   - Metadata generation (releaser agent or fallback)

   The pipeline pattern ensures clean sequential execution with early exit on failure."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.release-executor.files :as files]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.metadata :as metadata]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.sandbox :as sandbox]))

;------------------------------------------------------------------------------ Layer 0
;; Pipeline helpers

(defn- failed?
  "Check if pipeline has failed."
  [state]
  (contains? state :failure))

(defn- fail
  "Mark pipeline as failed with error info."
  [state error-type error-msg & {:keys [hint]}]
  (let [logger (:logger state)]
    (when logger
      (log/error logger :release-executor error-type {:message error-msg}))
    (assoc state :failure
           (cond-> {:type error-type :message error-msg}
             hint (assoc :hint hint)))))

(defn- extract-code-artifacts
  "Extract code artifacts from workflow artifacts."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))))

;------------------------------------------------------------------------------ Layer 1
;; Pipeline steps

(defn- step-validate-inputs [state]
  (cond
    (failed? state) state
    ;; In sandbox mode, worktree-path is not required (container has its own workspace)
    (and (not (:sandbox? state)) (not (:worktree-path state)))
    (fail state :missing-worktree-path "Context must include :worktree-path for release phase")
    (empty? (:code-artifacts state))
    (do
      (when-let [logger (:logger state)]
        (log/warn logger :release-executor :no-code-artifacts
                  {:message "No code artifacts found to release"}))
      (fail state :no-code-artifacts "No code artifacts found in workflow state"))
    :else state))

(defn- step-check-gh-auth [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [gh-auth (if (:sandbox? state)
                    (sandbox/check-gh-auth! (:executor state) (:environment-id state))
                    (git/check-gh-auth!))]
      (if (:authenticated? gh-auth)
        state
        (fail state :gh-auth-failed (:error gh-auth)
              :hint (if (:available? gh-auth)
                      "Run: gh auth login"
                      "Install: brew install gh"))))))

(defn- step-generate-metadata [state]
  (if (failed? state)
    state
    (let [{:keys [releaser code-artifacts task-description context logger]} state
          release-meta (metadata/generate-release-metadata
                        releaser code-artifacts task-description context logger)]
      (assoc state :release-meta release-meta))))

(defn- step-create-branch [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path release-meta sandbox? executor environment-id]} state
          branch-name (:release/branch-name release-meta)
          result (if sandbox?
                   (sandbox/create-branch! executor environment-id branch-name)
                   (git/create-branch! worktree-path branch-name))]
      (if (:success? result)
        (assoc state
               :branch (:branch result)
               :base-branch (:base-branch result))
        (fail state :branch-create-failed (:error result))))))

(defn- step-write-and-stage [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path code-artifacts logger sandbox? executor environment-id]} state
          result (if sandbox?
                   (sandbox/write-and-stage-files! executor environment-id code-artifacts)
                   (files/write-and-stage-files! worktree-path code-artifacts logger))]
      (if (:success? result)
        (assoc state :write-metrics (:metrics result))
        (do
          (when logger
            (log/error logger :release-executor :write-stage-failed
                       {:data {:errors (:errors result)}}))
          (assoc state
                 :failure {:type :write-stage-failed :errors (:errors result)}
                 :write-metrics (:metrics result)))))))

(defn- step-commit [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path release-meta sandbox? executor environment-id]} state
          result (if sandbox?
                   (sandbox/commit-changes! executor environment-id (:release/commit-message release-meta))
                   (git/commit-changes! worktree-path (:release/commit-message release-meta)))]
      (if (:success? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn- step-push [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path branch sandbox? executor environment-id]} state
          result (if sandbox?
                   (sandbox/push-branch! executor environment-id branch)
                   (git/push-branch! worktree-path branch))]
      (if (:success? result)
        state
        (fail state :push-failed (:error result))))))

(defn- step-create-pr [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path release-meta base-branch sandbox? executor environment-id]} state
          result (if sandbox?
                   (sandbox/create-pr! executor environment-id
                                       {:title (:release/pr-title release-meta)
                                        :body (:release/pr-description release-meta)
                                        :base-branch base-branch})
                   (git/create-pr! worktree-path
                                   {:title (:release/pr-title release-meta)
                                    :body (:release/pr-description release-meta)
                                    :base-branch base-branch}))]
      (if (:success? result)
        (assoc state
               :pr-number (:pr-number result)
               :pr-url (:pr-url result))
        (fail state :pr-create-failed (:error result))))))

(defn- step-build-artifact [state]
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

(defn- step-save-artifact [state]
  (if (failed? state)
    state
    (do
      (when-let [artifact-store (:artifact-store state)]
        (try
          (artifact/save! artifact-store (:release-artifact state))
          (catch Exception _e nil)))
      state)))

(defn- pipeline->result
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

   Arguments:
   - workflow-state - Workflow state with :workflow/artifacts
   - context        - Execution context with :worktree-path, :logger, etc.
   - opts           - Options with :releaser (optional)

   Returns:
   {:success? bool
    :artifacts [release-artifact]
    :errors []
    :metrics {...}}"
  [workflow-state context opts]
  (let [logger (:logger context)
        executor (:executor context)
        environment-id (:environment-id context)
        sandbox? (boolean (and executor environment-id))
        initial-state {:logger logger
                       :worktree-path (:worktree-path context)
                       :artifact-store (:artifact-store context)
                       :context context
                       :create-pr? (get context :create-pr? true)
                       :releaser (:releaser opts)
                       :task-description (get-in workflow-state [:workflow/spec :spec/description])
                       :code-artifacts (extract-code-artifacts (:workflow/artifacts workflow-state))
                       :executor executor
                       :environment-id environment-id
                       :sandbox? sandbox?}]

    (when logger
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path (:worktree-path initial-state)
                        :create-pr? (:create-pr? initial-state)}}))

    (-> initial-state
        step-validate-inputs
        step-check-gh-auth
        step-generate-metadata
        step-create-branch
        step-write-and-stage
        step-commit
        step-push
        step-create-pr
        step-build-artifact
        step-save-artifact
        pipeline->result)))
