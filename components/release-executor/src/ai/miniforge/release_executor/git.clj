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

(ns ai.miniforge.release-executor.git
  "Git operations for the release executor — host-mode backend.

   Provides shell-based git and gh CLI operations that run directly against
   the host working tree (babashka.process). This is the counterpart to
   sandbox.clj which routes operations through the DAG executor container.

   Used when :host-mode? is true (local-dogfood / no executor supplied)."
  (:require
   [ai.miniforge.release-executor.messages :as msg]
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
;; Generic command runner

(defn exec!
  "Run a shell command string in the worktree directory.
   Returns {:success? bool :output string :error string}.
   Used for ad-hoc git commands (git rev-parse, git add, git commit --amend, etc.)."
  [worktree-path cmd]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "sh" "-c" cmd)]
      (if (zero? (:exit r))
        (result/shell-success {:output (str/trim (get r :out ""))})
        (result/shell-failure (str/trim (get r :err ""))
                              {:output (str/trim (get r :out ""))})))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

;------------------------------------------------------------------------------ Layer 1
;; Git operations

(defn stage-files!
  "Stage files in git worktree using git add."
  [worktree-path file-paths]
  (try
    (let [git-args (if (= file-paths :all)
                     ["git" "add" "."]
                     (into ["git" "add"] (map str file-paths)))
          r (apply process/shell
                   {:dir (str worktree-path)
                    :out :string
                    :err :string
                    :continue true}
                   git-args)]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err "") {:output (get r :out "")})))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn check-gh-auth!
  "Check if gh CLI is available and authenticated on the host.

   Returns {:available? bool :authenticated? bool :user string :error string}"
  []
  (try
    (let [which-r (process/shell
                   {:out :string :err :string :continue true}
                   "which" "gh")]
      (if-not (zero? (:exit which-r))
        (gh-unavailable "gh CLI not found. Install with: brew install gh")
        (let [auth-r (process/shell
                      {:out :string :err :string :continue true}
                      "gh" "auth" "status")]
          (if (zero? (:exit auth-r))
            (let [output (get auth-r :out "")
                  user-match (re-find #"Logged in to [^\s]+ account (\S+)" output)]
              (gh-authenticated (or (second user-match) "unknown")))
            (gh-available-unauthenticated
             (str "gh not authenticated. Run: gh auth login\n" (get auth-r :err "")))))))
    (catch Exception e
      (gh-unavailable (.getMessage e)))))

(defn create-branch!
  "Create a new git branch for the release, branching from HEAD so that
   phase-boundary commits already on the task branch carry forward.

   Arguments:
   - worktree-path - Path to git worktree root
   - branch-name   - Name for the new branch

   Returns {:success? bool :branch string :base-branch string :error string}"
  [worktree-path branch-name]
  (try
    (let [dir-opts {:dir (str worktree-path) :out :string :err :string :continue true}
          default-branch-r (process/shell dir-opts
                                          "git" "symbolic-ref" "refs/remotes/origin/HEAD")
          default-branch (if (zero? (:exit default-branch-r))
                           (-> (get default-branch-r :out "")
                               str/trim
                               (str/replace #"refs/remotes/origin/" ""))
                           "main")
          checkout-r (process/shell dir-opts
                                    "git" "checkout" "-b" branch-name)]
      (if (zero? (:exit checkout-r))
        (result/shell-success {:branch branch-name :base-branch default-branch})
        (let [ts-name (str branch-name "-" (System/currentTimeMillis))
              retry-r (process/shell dir-opts "git" "checkout" "-b" ts-name)]
          (if (zero? (:exit retry-r))
            (result/shell-success {:branch ts-name :base-branch default-branch})
            (result/shell-failure (str "Failed to create branch: " (get retry-r :err ""))
                                  {:branch nil})))))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:branch nil}))))

(defn fetch-branch!
  "Fetch origin/<branch> into the local worktree.

   Used when a stacked-PR base is a parent task's branch: pulls it so later
   `origin/<base>` range diffs and commits-ahead resolve on the host.
   Returns a shell-result."
  [worktree-path branch]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "fetch" "origin" (str branch))]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err ""))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn commits-ahead-of-base
  "Count commits HEAD has added since branching from origin/<base>.
   Uses the three-dot merge-base form so concurrent movement of origin/<base>
   does not shrink the count to zero. Returns nil on git failure."
  [worktree-path base-branch]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "rev-list" "--count" "--right-only"
             (str "origin/" base-branch "...HEAD"))]
      (when (zero? (:exit r))
        (try
          (Long/parseLong (str/trim (get r :out "0")))
          (catch NumberFormatException _ nil))))
    (catch Exception _ nil)))

(defn diff-stats
  "Get line-level diff stats for staged changes.

   Returns {:additions N :deletions N :files N} or nil on error."
  [worktree-path]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "diff" "--cached" "--numstat")]
      (when (zero? (:exit r))
        (let [lines (str/split-lines (str/trim (get r :out "")))
              parsed (keep (fn [line]
                             (when-let [[_ adds dels] (re-matches #"(\d+)\t(\d+)\t.*" line)]
                               {:additions (parse-long adds)
                                :deletions (parse-long dels)}))
                           lines)]
          {:additions (reduce + 0 (map :additions parsed))
           :deletions (reduce + 0 (map :deletions parsed))
           :files (count parsed)})))
    (catch Exception _ nil)))

(defn diff-stats-range
  "Get diff stats for the changes this branch introduced since branching from
   origin/<base> — `git diff origin/<base>...HEAD --numstat` with the
   three-dot form (merge-base diff). Mirrors sandbox/diff-stats-range."
  [worktree-path base-branch]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "diff" (str "origin/" base-branch "...HEAD") "--numstat")]
      (when (zero? (:exit r))
        (let [lines (str/split-lines (str/trim (get r :out "")))
              parsed (keep (fn [line]
                             (when-let [[_ adds dels] (re-matches #"(\d+)\t(\d+)\t.*" line)]
                               {:additions (parse-long adds)
                                :deletions (parse-long dels)}))
                           lines)]
          {:additions (reduce + 0 (map :additions parsed))
           :deletions (reduce + 0 (map :deletions parsed))
           :files (count parsed)})))
    (catch Exception _ nil)))

(defn count-test-defs
  "Count deftest forms in staged changes.

   Returns {:added N :removed N} or nil on error."
  [worktree-path]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "diff" "--cached" "-U0")]
      (when (zero? (:exit r))
        (let [diff-text (get r :out "")
              added   (count (re-seq #"(?m)^\+.*\(deftest " diff-text))
              removed (count (re-seq #"(?m)^-.*\(deftest " diff-text))]
          {:added added :removed removed})))
    (catch Exception _ nil)))

(defn count-test-defs-range
  "Count deftest forms added/removed in origin/<base>...HEAD (merge-base diff).
   Mirrors sandbox/count-test-defs-range."
  [worktree-path base-branch]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "diff" (str "origin/" base-branch "...HEAD") "-U0")]
      (when (zero? (:exit r))
        (let [diff-text (get r :out "")
              added   (count (re-seq #"(?m)^\+.*\(deftest " diff-text))
              removed (count (re-seq #"(?m)^-.*\(deftest " diff-text))]
          {:added added :removed removed})))
    (catch Exception _ nil)))

(defn commit-changes!
  "Commit staged changes with the given message.

   Returns {:success? bool :commit-sha string :error string}"
  [worktree-path commit-message]
  (try
    (let [commit-r (process/shell
                    {:dir (str worktree-path) :out :string :err :string :continue true}
                    "git" "commit" "-m" commit-message)]
      (if (zero? (:exit commit-r))
        (let [sha-r (process/shell
                     {:dir (str worktree-path) :out :string :err :string :continue true}
                     "git" "rev-parse" "HEAD")]
          (result/shell-success {:commit-sha (str/trim (get sha-r :out ""))
                                 :output (get commit-r :out "")}))
        (result/shell-failure (get commit-r :err "") {:commit-sha nil})))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:commit-sha nil}))))

(defn push-branch!
  "Push the current branch to origin.

   Returns {:success? bool :error string}"
  [worktree-path branch-name]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "git" "push" "-u" "origin" branch-name)]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err ""))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn write-file!
  "Write content to a relative path inside the worktree.
   Creates parent directories as needed.
   Returns {:success? bool :output string :error string}."
  [worktree-path rel-path content]
  (try
    (let [file   (java.io.File. (str worktree-path) (str rel-path))
          parent (.getParentFile file)]
      (when parent (.mkdirs parent))
      (spit file content)
      (result/shell-success {:output (str rel-path)}))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn edit-pr-body!
  "Update the body of an existing PR using gh CLI on the host.
   Returns {:success? bool :output string :error string}."
  [worktree-path pr-number body]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "gh" "pr" "edit" (str pr-number) "--body" (or body ""))]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err ""))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

;------------------------------------------------------------------------------ Layer 1.5
;; PR creation helpers (mirrors sandbox.clj equivalents)

(defn- parse-pr-ref
  "Parse a gh PR URL output into {:pr-url :pr-number}, or nil when the output
   carries no /pull/<n> reference."
  [output]
  (let [url (str/trim (or output ""))]
    (when-let [match (re-find #"/pull/(\d+)" url)]
      {:pr-url url :pr-number (parse-long (second match))})))

(defn- pr-already-exists?
  "True when a gh error indicates a PR already exists for the current branch."
  [error]
  (boolean (some-> error str/lower-case (str/includes? "already exists"))))

(defn- reuse-existing-pr!
  "Resolve the PR already open for the current branch via gh pr view, so a
   release retry reuses it instead of opening a duplicate.
   Falls back to a failure carrying the original create error when the PR
   cannot be resolved via gh pr view."
  [worktree-path]
  (try
    (let [r (process/shell
             {:dir (str worktree-path) :out :string :err :string :continue true}
             "gh" "pr" "view" "--json" "url" "--jq" ".url")]
      (if-let [pr (and (zero? (:exit r))
                       (parse-pr-ref (get r :out "")))]
        (result/shell-success pr)
        (result/shell-failure (msg/t :pr/reuse-unresolved
                                     {:error (or (not-empty (str/trim (get r :err "")))
                                                 (not-empty (str/trim (get r :out "")))
                                                 (msg/t :pr/reuse-no-url))})
                              {:pr-url nil :pr-number nil})))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))

(defn create-pr!
  "Create a pull request using gh CLI on the host.

   Two robustness guarantees matching sandbox/create-pr!:
   - A `gh pr create` that exits 0 but prints no PR URL is a FAILURE — release
     must never claim a PR it never opened.
   - A branch that already has an open PR (retry/resume) reuses that PR via
     `gh pr view` rather than opening a duplicate.

   Returns {:success? bool :pr-number int :pr-url string :error string}"
  [worktree-path {:keys [title body base-branch]}]
  (try
    (let [base   (or base-branch "main")
          result (process/shell
                  {:dir (str worktree-path) :out :string :err :string :continue true}
                  "gh" "pr" "create"
                  "--title" title
                  "--body"  (or body "")
                  "--base"  base)]
      (cond
        (zero? (:exit result))
        (if-let [pr (parse-pr-ref (get result :out ""))]
          (result/shell-success pr)
          (result/shell-failure (msg/t :pr/create-unconfirmed
                                       {:output (str/trim (get result :out ""))})
                                {:pr-url nil :pr-number nil}))

        (pr-already-exists? (get result :err ""))
        (reuse-existing-pr! worktree-path)

        :else
        (result/shell-failure (get result :err "") {:pr-url nil :pr-number nil})))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))
