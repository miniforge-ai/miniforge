(ns ai.miniforge.release-executor.git
  "Git operations for the release executor.
   Provides shell-based git and gh CLI operations."
  (:require
   [ai.miniforge.release-executor.result :as result]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; gh auth result helpers

(defn gh-unavailable
  "Create result for gh CLI not available."
  [error-msg]
  {:available? false :authenticated? false :error error-msg})

(defn gh-available-unauthenticated
  "Create result for gh CLI available but not authenticated."
  [error-msg]
  {:available? true :authenticated? false :error error-msg})

(defn gh-authenticated
  "Create result for gh CLI available and authenticated."
  [user]
  {:available? true :authenticated? true :user user})

;------------------------------------------------------------------------------ Layer 1
;; Git operations

(defn stage-files!
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
        (result/shell-success {:output (:out result "")})
        (result/shell-failure (:err result "") {:output (:out result "")})))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

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
  "Create a new git branch for the release.

   Branches from HEAD (not origin/main) so MCP-written files in the working
   tree are preserved. The PR will target the default branch.

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
          ;; Branch from HEAD to preserve MCP-written files in the working tree
          checkout-result (process/shell dir-opts
                                         "git" "checkout" "-b" branch-name)]
      (if (zero? (:exit checkout-result))
        (result/shell-success {:branch branch-name :base-branch default-branch})
        ;; Retry with timestamp suffix if branch name already exists
        (let [timestamped-name (str branch-name "-" (System/currentTimeMillis))
              retry-result (process/shell dir-opts
                                          "git" "checkout" "-b" timestamped-name)]
          (if (zero? (:exit retry-result))
            (result/shell-success {:branch timestamped-name :base-branch default-branch})
            (result/shell-failure (str "Failed to create branch: " (:err retry-result))
                                  {:branch nil})))))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:branch nil}))))

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
          (result/shell-success {:commit-sha (str/trim (:out sha-result ""))
                                 :output (:out result)}))
        (result/shell-failure (:err result) {:commit-sha nil})))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:commit-sha nil}))))

(defn push-branch!
  "Push the current branch to origin.

   Returns {:success? bool :error string}"
  [worktree-path branch-name]
  (try
    (let [result (process/shell
                  {:dir (str worktree-path) :out :string :err :string :continue true}
                  "git" "push" "-u" "origin" branch-name)]
      (if (zero? (:exit result))
        (result/shell-success {:output (:out result)})
        (result/shell-failure (:err result))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

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
          (result/shell-success {:pr-url pr-url :pr-number pr-num}))
        (result/shell-failure (:err result) {:pr-url nil :pr-number nil})))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))
