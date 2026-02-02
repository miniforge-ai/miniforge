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
      {:success? (zero? (:exit result))
       :output (:out result "")
       :error (:err result "")})
    (catch Exception e
      {:success? false
       :output ""
       :error (.getMessage e)})))

(defn check-gh-auth!
  "Check if gh CLI is available and authenticated.

   Returns {:available? bool :authenticated? bool :user string :error string}"
  []
  (try
    (let [which-result (process/shell
                        {:out :string :err :string :continue true}
                        "which" "gh")]
      (if-not (zero? (:exit which-result))
        {:available? false
         :authenticated? false
         :error "gh CLI not found. Install with: brew install gh"}

        (let [auth-result (process/shell
                          {:out :string :err :string :continue true}
                          "gh" "auth" "status")]
          (if (zero? (:exit auth-result))
            (let [output (:out auth-result "")
                  user-match (re-find #"Logged in to [^\s]+ account (\S+)" output)]
              {:available? true
               :authenticated? true
               :user (or (second user-match) "unknown")})
            {:available? true
             :authenticated? false
             :error (str "gh not authenticated. Run: gh auth login\n" (:err auth-result))}))))
    (catch Exception e
      {:available? false
       :authenticated? false
       :error (.getMessage e)})))

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
        {:success? false
         :branch nil
         :error (str "Failed to fetch: " (:err fetch-result))}

        (let [checkout-result (process/shell dir-opts
                                             "git" "checkout" "-b" branch-name
                                             (str "origin/" default-branch))]
          (if (zero? (:exit checkout-result))
            {:success? true
             :branch branch-name
             :base-branch default-branch}
            (let [timestamped-name (str branch-name "-" (System/currentTimeMillis))
                  retry-result (process/shell dir-opts
                                              "git" "checkout" "-b" timestamped-name
                                              (str "origin/" default-branch))]
              (if (zero? (:exit retry-result))
                {:success? true
                 :branch timestamped-name
                 :base-branch default-branch}
                {:success? false
                 :branch nil
                 :error (str "Failed to create branch: " (:err retry-result))}))))))
    (catch Exception e
      {:success? false
       :branch nil
       :error (.getMessage e)})))

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
          {:success? true
           :commit-sha (str/trim (:out sha-result ""))
           :output (:out result)})
        {:success? false
         :commit-sha nil
         :error (:err result)}))
    (catch Exception e
      {:success? false
       :commit-sha nil
       :error (.getMessage e)})))

(defn push-branch!
  "Push the current branch to origin.

   Returns {:success? bool :error string}"
  [worktree-path branch-name]
  (try
    (let [result (process/shell
                  {:dir (str worktree-path) :out :string :err :string :continue true}
                  "git" "push" "-u" "origin" branch-name)]
      (if (zero? (:exit result))
        {:success? true
         :output (:out result)}
        {:success? false
         :error (:err result)}))
    (catch Exception e
      {:success? false
       :error (.getMessage e)})))

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
          {:success? true
           :pr-url pr-url
           :pr-number pr-num})
        {:success? false
         :pr-url nil
         :pr-number nil
         :error (:err result)}))
    (catch Exception e
      {:success? false
       :pr-url nil
       :pr-number nil
       :error (.getMessage e)})))

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
        slug-raw (-> task-desc
                     str/lower-case
                     (str/replace #"[^a-z0-9\s-]" "")
                     (str/replace #"\s+" "-"))
        slug (subs slug-raw 0 (min 40 (count slug-raw)))]
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
        worktree-path (:worktree-path context)
        artifact-store (:artifact-store context)
        task-description (get-in workflow-state [:workflow/spec :spec/description])
        create-pr? (get context :create-pr? true)
        releaser (:releaser opts)]

    (when logger
      (log/info logger :release-executor :phase-started
                {:data {:worktree-path worktree-path :create-pr? create-pr?}}))

    (if-not worktree-path
      (do
        (when logger
          (log/error logger :release-executor :missing-worktree-path
                     {:message "Context must include :worktree-path"}))
        {:success? false
         :artifacts []
         :errors [{:type :missing-worktree-path
                   :message "Context must include :worktree-path for release phase"}]
         :metrics {}})

      (let [code-artifacts (extract-code-artifacts (:workflow/artifacts workflow-state))]

        (if (empty? code-artifacts)
          (do
            (when logger
              (log/warn logger :release-executor :no-code-artifacts
                        {:message "No code artifacts found to release"}))
            {:success? false
             :artifacts []
             :errors [{:type :no-code-artifacts
                       :message "No code artifacts found in workflow state"}]
             :metrics {}})

          (let [gh-auth (when create-pr? (check-gh-auth!))
                gh-ok? (or (not create-pr?) (:authenticated? gh-auth))]

            (if (and create-pr? (not gh-ok?))
              (do
                (when logger
                  (log/error logger :release-executor :gh-auth-failed
                             {:message (:error gh-auth)}))
                {:success? false
                 :artifacts []
                 :errors [{:type :gh-auth-failed
                           :message (:error gh-auth)
                           :hint (if (:available? gh-auth)
                                   "Run: gh auth login"
                                   "Install: brew install gh")}]
                 :metrics {}})

              (let [release-meta (or (invoke-releaser releaser code-artifacts task-description context logger)
                                     (make-fallback-release-metadata code-artifacts task-description))
                    branch-name (:release/branch-name release-meta)
                    branch-result (create-branch! worktree-path branch-name)]
                (if-not (:success? branch-result)
                  (do
                    (when logger
                      (log/error logger :release-executor :branch-failed
                                 {:message (:error branch-result)}))
                    {:success? false
                     :artifacts []
                     :errors [{:type :branch-create-failed
                               :message (:error branch-result)}]
                     :metrics {}})

                  (let [actual-branch (:branch branch-result)
                        base-branch (:base-branch branch-result)
                        write-result (write-and-stage-files! worktree-path code-artifacts logger)]

                    (if-not (:success? write-result)
                      (do
                        (when logger
                          (log/error logger :release-executor :write-stage-failed
                                     {:data {:errors (:errors write-result)}}))
                        {:success? false
                         :artifacts []
                         :errors (:errors write-result)
                         :metrics (:metrics write-result)})

                      (let [commit-result (commit-changes! worktree-path (:release/commit-message release-meta))]
                        (if-not (:success? commit-result)
                          (do
                            (when logger
                              (log/error logger :release-executor :commit-failed
                                         {:message (:error commit-result)}))
                            {:success? false
                             :artifacts []
                             :errors [{:type :commit-failed
                                       :message (:error commit-result)}]
                             :metrics (:metrics write-result)})

                          (let [push-result (when create-pr?
                                              (push-branch! worktree-path actual-branch))]
                            (if (and create-pr? (not (:success? push-result)))
                              (do
                                (when logger
                                  (log/error logger :release-executor :push-failed
                                             {:message (:error push-result)}))
                                {:success? false
                                 :artifacts []
                                 :errors [{:type :push-failed
                                           :message (:error push-result)}]
                                 :metrics (:metrics write-result)})

                              (let [pr-result (when create-pr?
                                                (create-pr! worktree-path
                                                            {:title (:release/pr-title release-meta)
                                                             :body (:release/pr-description release-meta)
                                                             :base-branch base-branch}))]

                                (if (and create-pr? (not (:success? pr-result)))
                                  (do
                                    (when logger
                                      (log/error logger :release-executor :pr-failed
                                                 {:message (:error pr-result)}))
                                    {:success? false
                                     :artifacts []
                                     :errors [{:type :pr-create-failed
                                               :message (:error pr-result)}]
                                     :metrics (:metrics write-result)})

                                  (let [release-content (merge
                                                         (:metrics write-result)
                                                         {:git-staged? true
                                                          :worktree-path (str worktree-path)
                                                          :branch actual-branch
                                                          :base-branch base-branch
                                                          :commit-sha (:commit-sha commit-result)
                                                          :pr-created? (boolean create-pr?)
                                                          :pr-number (:pr-number pr-result)
                                                          :pr-url (:pr-url pr-result)
                                                          :release-metadata release-meta})
                                        release-artifact (artifact/build-artifact
                                                          {:id (random-uuid)
                                                           :type :release
                                                           :version "1.0.0"
                                                           :content release-content
                                                           :metadata {:phase :release
                                                                      :code-artifacts-count (count code-artifacts)}})]

                                    (when artifact-store
                                      (try
                                        (artifact/save! artifact-store release-artifact)
                                        (catch Exception _e nil)))

                                    (when logger
                                      (log/info logger :release-executor :phase-completed
                                                {:data {:branch actual-branch
                                                        :commit (:commit-sha commit-result)
                                                        :pr-url (:pr-url pr-result)
                                                        :files-written (get-in write-result [:metrics :files-written])}}))

                                    {:success? true
                                     :artifacts [release-artifact]
                                     :errors []
                                     :metrics (merge (:metrics write-result)
                                                     {:pr-number (:pr-number pr-result)
                                                      :pr-url (:pr-url pr-result)
                                                      :commit-sha (:commit-sha commit-result)
                                                      :branch actual-branch})}))))))))))))))))))
