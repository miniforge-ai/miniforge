(ns ai.miniforge.release-executor.core
  "Release phase executor for creating PRs from code artifacts.

   This component provides the full release flow:
   - Check GitHub CLI authentication
   - Generate release metadata (branch name, commit message, PR title/body)
   - Create git branch
   - Write files and stage them
   - Commit changes
   - Push branch and create PR

   Extracted to a separate component to avoid circular dependencies
   between workflow and phase components."
  (:require
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.logging.interface :as log]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; File operation helpers

(defn- ensure-parent-dir!
  "Create parent directories for a file path if they don't exist."
  [file-path]
  (let [parent (fs/parent file-path)]
    (when parent
      (fs/create-dirs parent))
    parent))

(defn- write-file!
  "Write content to a file, creating parent directories as needed."
  [file-path content]
  (ensure-parent-dir! file-path)
  (spit (str file-path) content)
  file-path)

(defn- delete-file!
  "Delete a file if it exists. Returns true if deleted, false if file didn't exist."
  [file-path]
  (if (fs/exists? file-path)
    (do
      (fs/delete file-path)
      true)
    false))

;------------------------------------------------------------------------------ Layer 0.5
;; Result helpers

(defn- shell-success
  "Create a shell operation success result."
  [data]
  (merge {:success? true} data))

(defn- shell-failure
  "Create a shell operation failure result."
  ([error-msg]
   (shell-failure error-msg {}))
  ([error-msg data]
   (merge {:success? false :error error-msg} data)))

(defn- phase-success
  "Create a release phase success result."
  [artifacts metrics]
  {:success? true
   :artifacts artifacts
   :errors []
   :metrics metrics})

(defn- phase-failure
  "Create a release phase failure result."
  ([error-type error-msg]
   (phase-failure error-type error-msg {}))
  ([error-type error-msg opts]
   {:success? false
    :artifacts []
    :errors [(merge {:type error-type :message error-msg}
                    (select-keys opts [:hint :data]))]
    :metrics (or (:metrics opts) {})}))

;------------------------------------------------------------------------------ Layer 0.6
;; String utilities

(defn- slugify
  "Convert a string to a URL-safe slug.
   Handles basic ASCII transliteration and normalizes spacing."
  [s]
  (let [input (or s "change")
        ;; Basic ASCII transliteration for common characters
        transliterated (-> input
                           (str/replace #"[àáâãäå]" "a")
                           (str/replace #"[èéêë]" "e")
                           (str/replace #"[ìíîï]" "i")
                           (str/replace #"[òóôõö]" "o")
                           (str/replace #"[ùúûü]" "u")
                           (str/replace #"[ñ]" "n")
                           (str/replace #"[ç]" "c")
                           (str/replace #"[ß]" "ss"))
        slug (-> transliterated
                 str/lower-case
                 (str/replace #"[^a-z0-9\s-]" "")
                 (str/replace #"\s+" "-")
                 (str/replace #"-+" "-")
                 (str/replace #"^-|-$" ""))]
    (subs slug 0 (min 40 (count slug)))))

;------------------------------------------------------------------------------ Layer 1
;; Git operations

(defn- stage-files!
  "Stage files in git worktree using git add."
  [worktree-path file-paths]
  (try
    (let [git-args (if (= file-paths :all)
                     ["git" "add" "."]
                     (into ["git" "add"] (map str file-paths)))
          result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        git-args)]
      (if (zero? (:exit result))
        (shell-success {:output (:out result "")})
        (shell-failure (:err result "") {:output (:out result "")})))
    (catch Exception e
      (shell-failure (.getMessage e)))))

(defn- gh-unavailable
  "Create result for gh CLI not available."
  [error-msg]
  {:available? false :authenticated? false :error error-msg})

(defn- gh-available-unauthenticated
  "Create result for gh CLI available but not authenticated."
  [error-msg]
  {:available? true :authenticated? false :error error-msg})

(defn- gh-authenticated
  "Create result for gh CLI available and authenticated."
  [user]
  {:available? true :authenticated? true :user user})

(defn check-gh-auth!
  "Check if gh CLI is available and authenticated.

   Returns {:available? bool :authenticated? bool :user string :error string}"
  []
  (try
    (let [which-result (process/shell
                        {:out :string :err :string :continue true}
                        "which" "gh")]
      (if-not (zero? (:exit which-result))
        (gh-unavailable "gh CLI not found. Install with: brew install gh")
        (let [auth-result (process/shell
                           {:out :string :err :string :continue true}
                           "gh" "auth" "status")]
          (if (zero? (:exit auth-result))
            (let [output (:out auth-result "")
                  user-match (re-find #"Logged in to [^\s]+ account (\S+)" output)]
              (gh-authenticated (or (second user-match) "unknown")))
            (gh-available-unauthenticated
             (str "gh not authenticated. Run: gh auth login\n" (:err auth-result)))))))
    (catch Exception e
      (gh-unavailable (.getMessage e)))))

(defn create-branch!
  "Create a new git branch from main/master.

   Arguments:
   - worktree-path - Path to git worktree root
   - branch-name   - Name for the new branch

   Returns {:success? bool :branch string :base-branch string :error string}"
  [worktree-path branch-name]
  (try
    (let [dir-opts {:dir (str worktree-path) :out :string :err :string :continue true}
          default-branch-result (process/shell dir-opts
                                               "git" "symbolic-ref" "refs/remotes/origin/HEAD")
          default-branch (if (zero? (:exit default-branch-result))
                           (-> (:out default-branch-result)
                               str/trim
                               (str/replace #"refs/remotes/origin/" ""))
                           "main")
          fetch-result (process/shell dir-opts "git" "fetch" "origin" default-branch)]

      (if-not (zero? (:exit fetch-result))
        (shell-failure (str "Failed to fetch: " (:err fetch-result)) {:branch nil})
        (let [checkout-result (process/shell dir-opts
                                             "git" "checkout" "-b" branch-name
                                             (str "origin/" default-branch))]
          (if (zero? (:exit checkout-result))
            (shell-success {:branch branch-name :base-branch default-branch})
            (let [timestamped-name (str branch-name "-" (System/currentTimeMillis))
                  retry-result (process/shell dir-opts
                                              "git" "checkout" "-b" timestamped-name
                                              (str "origin/" default-branch))]
              (if (zero? (:exit retry-result))
                (shell-success {:branch timestamped-name :base-branch default-branch})
                (shell-failure (str "Failed to create branch: " (:err retry-result))
                               {:branch nil})))))))
    (catch Exception e
      (shell-failure (.getMessage e) {:branch nil}))))

(defn commit-changes!
  "Commit staged changes with the given message.

   Returns {:success? bool :commit-sha string :error string}"
  [worktree-path commit-message]
  (try
    (let [result (process/shell
                  {:dir (str worktree-path) :out :string :err :string :continue true}
                  "git" "commit" "-m" commit-message)]
      (if (zero? (:exit result))
        (let [sha-result (process/shell
                          {:dir (str worktree-path) :out :string :err :string :continue true}
                          "git" "rev-parse" "HEAD")]
          (shell-success {:commit-sha (str/trim (:out sha-result ""))
                          :output (:out result)}))
        (shell-failure (:err result) {:commit-sha nil})))
    (catch Exception e
      (shell-failure (.getMessage e) {:commit-sha nil}))))

(defn push-branch!
  "Push the current branch to origin.

   Returns {:success? bool :error string}"
  [worktree-path branch-name]
  (try
    (let [result (process/shell
                  {:dir (str worktree-path) :out :string :err :string :continue true}
                  "git" "push" "-u" "origin" branch-name)]
      (if (zero? (:exit result))
        (shell-success {:output (:out result)})
        (shell-failure (:err result))))
    (catch Exception e
      (shell-failure (.getMessage e)))))

(defn create-pr!
  "Create a pull request using gh CLI.

   Returns {:success? bool :pr-number int :pr-url string :error string}"
  [worktree-path {:keys [title body base-branch]}]
  (try
    (let [base (or base-branch "main")
          args ["gh" "pr" "create"
                "--title" title
                "--body" body
                "--base" base]
          result (apply process/shell
                        {:dir (str worktree-path) :out :string :err :string :continue true}
                        args)]
      (if (zero? (:exit result))
        (let [pr-url (str/trim (:out result ""))
              pr-num (when-let [match (re-find #"/pull/(\d+)" pr-url)]
                       (parse-long (second match)))]
          (shell-success {:pr-url pr-url :pr-number pr-num}))
        (shell-failure (:err result) {:pr-url nil :pr-number nil})))
    (catch Exception e
      (shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))

;------------------------------------------------------------------------------ Layer 2
;; File action processing

(defmulti ^:private process-file-action
  "Process a file action based on its :action keyword."
  (fn [_worktree-path file-spec _logger] (:action file-spec)))

(defmethod process-file-action :create
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :release-executor :file-created {:data {:path path}}))
      {:success? true :action :create :path path})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-create-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :create :path path :error (.getMessage e)})))

(defmethod process-file-action :modify
  [worktree-path {:keys [path content]} logger]
  (try
    (let [full-path (fs/path worktree-path path)]
      (write-file! full-path content)
      (when logger
        (log/debug logger :release-executor :file-modified {:data {:path path}}))
      {:success? true :action :modify :path path})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-modify-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :modify :path path :error (.getMessage e)})))

(defmethod process-file-action :delete
  [worktree-path {:keys [path]} logger]
  (try
    (let [full-path (fs/path worktree-path path)
          deleted? (delete-file! full-path)]
      (when logger
        (log/debug logger :release-executor :file-deleted {:data {:path path :existed? deleted?}}))
      {:success? true :action :delete :path path :deleted? deleted?})
    (catch Exception e
      (when logger
        (log/error logger :release-executor :file-delete-failed
                  {:message (.getMessage e) :data {:path path}}))
      {:success? false :action :delete :path path :error (.getMessage e)})))

(defmethod process-file-action :default
  [_worktree-path {:keys [path action]} logger]
  (when logger
    (log/warn logger :release-executor :unknown-action {:data {:path path :action action}}))
  {:success? false :action action :path path :error (str "Unknown action: " action)})

;------------------------------------------------------------------------------ Layer 3
;; Code artifact extraction

(defn- extract-code-artifacts
  "Extract code artifacts from workflow artifacts."
  [workflow-artifacts]
  (->> workflow-artifacts
       (filter #(or (= :code (:type %))
                    (= :code (:artifact/type %))))
       (map (fn [artifact]
              (or (:artifact/content artifact)
                  (:content artifact))))))

(defn- write-and-stage-files!
  "Write files to worktree and stage them. Returns result map."
  [worktree-path code-artifacts logger]
  (let [results (atom {:created 0 :modified 0 :deleted 0 :errors []})
        all-files (mapcat :code/files code-artifacts)]

    (doseq [file-spec all-files]
      (let [result (process-file-action worktree-path file-spec logger)]
        (if (:success? result)
          (case (:action result)
            :create (swap! results update :created inc)
            :modify (swap! results update :modified inc)
            :delete (swap! results update :deleted inc)
            nil)
          (swap! results update :errors conj
                 {:type :file-operation-failed
                  :message (:error result)
                  :file (:path result)
                  :action (:action result)}))))

    (let [{:keys [created modified deleted errors]} @results
          total-operations (+ created modified deleted)]
      (if (seq errors)
        {:success? false
         :errors errors
         :metrics {:files-written created :files-modified modified :files-deleted deleted}}
        (let [stage-result (stage-files! worktree-path :all)]
          (if (:success? stage-result)
            {:success? true
             :metrics {:files-written created :files-modified modified :files-deleted deleted
                       :total-operations total-operations}}
            {:success? false
             :errors [{:type :git-stage-failed :message (:error stage-result)}]
             :metrics {:files-written created :files-modified modified :files-deleted deleted}}))))))

;------------------------------------------------------------------------------ Layer 4
;; Releaser agent integration

(defn- invoke-releaser
  "Invoke the releaser agent to generate release metadata.
   Falls back to nil if agent fails (caller should use fallback)."
  [releaser code-artifacts task-description context logger]
  (let [llm-backend (:llm-backend context)
        first-artifact (first code-artifacts)
        input {:code-artifact first-artifact
               :task-description (or task-description
                                     (:code/summary first-artifact)
                                     "implement changes")}]
    (if (and releaser llm-backend)
      (try
        (let [result ((:invoke-fn releaser)
                      (assoc context :llm-backend llm-backend)
                      input)]
          (when logger
            (log/debug logger :release-executor :releaser-invoked
                       {:data {:status (:status result)}}))
          (if (= :success (:status result))
            (:output result)
            (do
              (when logger
                (log/warn logger :release-executor :releaser-failed-fallback
                          {:message "Releaser agent failed, using fallback"}))
              nil)))
        (catch Exception e
          (when logger
            (log/warn logger :release-executor :releaser-exception
                      {:message (.getMessage e)}))
          nil))
      nil)))

(defn- make-fallback-release-metadata
  "Generate fallback release metadata when releaser agent is unavailable."
  [code-artifacts task-description]
  (let [first-artifact (first code-artifacts)
        files (:code/files first-artifact)
        summary (or (:code/summary first-artifact) "code changes")
        task-desc (or task-description summary)
        slug (slugify task-desc)]
    {:release/id (random-uuid)
     :release/branch-name (str "feature/" slug)
     :release/commit-message (str "feat: " task-desc)
     :release/pr-title (str "feat: " (subs task-desc 0 (min 60 (count task-desc))))
     :release/pr-description (str "## Summary\n" task-desc "\n\n"
                                  "## Changes\n"
                                  (if files
                                    (str/join "\n" (map #(str "- " (name (:action %)) " `" (:path %) "`") files))
                                    "See commits for details"))
     :release/created-at (java.util.Date.)}))

;------------------------------------------------------------------------------ Layer 5
;; Pipeline step functions
;; Each step takes pipeline state, returns updated state or adds :failure

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

(defn- step-validate-inputs
  "Validate required inputs are present."
  [state]
  (cond
    (failed? state) state
    (not (:worktree-path state))
    (fail state :missing-worktree-path "Context must include :worktree-path for release phase")
    (empty? (:code-artifacts state))
    (do
      (when-let [logger (:logger state)]
        (log/warn logger :release-executor :no-code-artifacts
                  {:message "No code artifacts found to release"}))
      (fail state :no-code-artifacts "No code artifacts found in workflow state"))
    :else state))

(defn- step-check-gh-auth
  "Check GitHub CLI authentication if creating PR."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [gh-auth (check-gh-auth!)]
      (if (:authenticated? gh-auth)
        state
        (fail state :gh-auth-failed (:error gh-auth)
              :hint (if (:available? gh-auth)
                      "Run: gh auth login"
                      "Install: brew install gh"))))))

(defn- step-generate-metadata
  "Generate release metadata using releaser agent or fallback."
  [state]
  (if (failed? state)
    state
    (let [{:keys [releaser code-artifacts task-description context logger]} state
          release-meta (or (invoke-releaser releaser code-artifacts task-description context logger)
                           (make-fallback-release-metadata code-artifacts task-description))]
      (assoc state :release-meta release-meta))))

(defn- step-create-branch
  "Create git branch for the release."
  [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path release-meta]} state
          branch-name (:release/branch-name release-meta)
          result (create-branch! worktree-path branch-name)]
      (if (:success? result)
        (assoc state
               :branch (:branch result)
               :base-branch (:base-branch result))
        (fail state :branch-create-failed (:error result))))))

(defn- step-write-and-stage
  "Write files to worktree and stage them."
  [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path code-artifacts logger]} state
          result (write-and-stage-files! worktree-path code-artifacts logger)]
      (if (:success? result)
        (assoc state :write-metrics (:metrics result))
        (do
          (when logger
            (log/error logger :release-executor :write-stage-failed
                       {:data {:errors (:errors result)}}))
          (assoc state
                 :failure {:type :write-stage-failed :errors (:errors result)}
                 :write-metrics (:metrics result)))))))

(defn- step-commit
  "Commit the staged changes."
  [state]
  (if (failed? state)
    state
    (let [{:keys [worktree-path release-meta]} state
          result (commit-changes! worktree-path (:release/commit-message release-meta))]
      (if (:success? result)
        (assoc state :commit-sha (:commit-sha result))
        (fail state :commit-failed (:error result))))))

(defn- step-push
  "Push branch to origin if creating PR."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path branch]} state
          result (push-branch! worktree-path branch)]
      (if (:success? result)
        state
        (fail state :push-failed (:error result))))))

(defn- step-create-pr
  "Create pull request if enabled."
  [state]
  (cond
    (failed? state) state
    (not (:create-pr? state)) state
    :else
    (let [{:keys [worktree-path release-meta base-branch]} state
          result (create-pr! worktree-path
                             {:title (:release/pr-title release-meta)
                              :body (:release/pr-description release-meta)
                              :base-branch base-branch})]
      (if (:success? result)
        (assoc state
               :pr-number (:pr-number result)
               :pr-url (:pr-url result))
        (fail state :pr-create-failed (:error result))))))

(defn- step-build-artifact
  "Build the release artifact."
  [state]
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

(defn- step-save-artifact
  "Save artifact to store if available."
  [state]
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
      (phase-failure (:type failure) (:message failure)
                     {:hint (:hint failure)
                      :metrics (or write-metrics {})})
      (do
        (when logger
          (log/info logger :release-executor :phase-completed
                    {:data {:branch branch
                            :commit commit-sha
                            :pr-url pr-url
                            :files-written (:files-written write-metrics)}}))
        (phase-success
         [release-artifact]
         (merge write-metrics
                {:pr-number pr-number
                 :pr-url pr-url
                 :commit-sha commit-sha
                 :branch branch}))))))

;------------------------------------------------------------------------------ Layer 6
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
        initial-state {:logger logger
                       :worktree-path (:worktree-path context)
                       :artifact-store (:artifact-store context)
                       :context context
                       :create-pr? (get context :create-pr? true)
                       :releaser (:releaser opts)
                       :task-description (get-in workflow-state [:workflow/spec :spec/description])
                       :code-artifacts (extract-code-artifacts (:workflow/artifacts workflow-state))}]

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
