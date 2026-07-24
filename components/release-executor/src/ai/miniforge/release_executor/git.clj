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

;------------------------------------------------------------------------------ Layer 0
;; Safety + credential helpers (host-mode parity with sandbox.clj)

(defn validate-safe-branch-name
  "Return nil when `branch` is safe to pass as a git/gh argument, else a
   shell-failure result. Mirrors sandbox/validate-safe-branch-name: allows
   only [A-Za-z0-9._/-] (the set git permits) and rejects blank names or
   names starting with '-', which git would read as an option flag (option
   injection). Host-mode passes branch names as argv (not through `sh -c`),
   so shell metacharacters are already inert, but a leading-'-' name is still
   an argument-injection vector — hence the guard."
  [branch]
  (let [s (str branch)]
    (cond
      (str/blank? s)
      (result/shell-failure "Unsafe branch name: must not be blank" {:branch s})

      (str/starts-with? s "-")
      (result/shell-failure (str "Unsafe branch name: must not start with '-': " (pr-str s))
                            {:branch s})

      (not (re-matches #"[A-Za-z0-9._/-]+" s))
      (result/shell-failure (str "Unsafe branch name: " (pr-str s)) {:branch s})

      :else nil)))

(defn- gh-shell-opts
  "process/shell option map for a gh call, injecting the caller's
   `github-token` as GH_TOKEN so host-mode uses the same credential the
   pipeline resolved — parity with sandbox's gh-exec-opts :env injection.
   `worktree-path` may be nil (e.g. `gh auth status` needs no cwd). When no
   token is supplied the call inherits the ambient environment."
  [worktree-path github-token]
  (cond-> {:out :string :err :string :continue true}
    worktree-path (assoc :dir (str worktree-path))
    github-token  (assoc :extra-env {"GH_TOKEN" github-token})))

;------------------------------------------------------------------------------ Layer 1
;; Generic command runner

(defn exec!
  "Run a git command in the worktree directory. `args` is an argv vector
   (e.g. [\"git\" \"rev-parse\" \"HEAD\"]) passed straight to the process — NOT
   through `sh -c` — so shell metacharacters in any argument are inert and
   there is no command-injection surface even if an argument carries
   untrusted data. Returns {:success? bool :output string :error string}.
   For ad-hoc git commands (rev-parse, diff --name-only, add, amend)."
  [worktree-path args]
  (try
    (let [r (apply process/shell
                   {:dir (str worktree-path) :out :string :err :string :continue true}
                   args)]
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
  "Check if gh CLI is available and authenticated on the host. Injects
   `github-token` as GH_TOKEN so the check reflects the credential the
   pipeline will use to open the PR (not just ambient host auth).

   Returns {:available? bool :authenticated? bool :user string :error string}"
  [github-token]
  (try
    (let [which-r (process/shell
                   {:out :string :err :string :continue true}
                   "which" "gh")]
      (if-not (zero? (:exit which-r))
        (gh-unavailable "gh CLI not found. Install with: brew install gh")
        (let [auth-r (process/shell
                      (gh-shell-opts nil github-token)
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
  (or
   (validate-safe-branch-name branch-name)
   (try
    (let [dir-opts {:dir (str worktree-path) :out :string :err :string :continue true}
          default-branch-r (process/shell dir-opts
                                          "git" "symbolic-ref" "refs/remotes/origin/HEAD")
          default-branch (if (zero? (:exit default-branch-r))
                           (-> (get default-branch-r :out "")
                               str/trim
                               (str/replace #"refs/remotes/origin/" ""))
                           "main")
          ;; Refresh origin/<default> before branching so later
          ;; origin/<base>...HEAD range diffs / commits-ahead resolve
          ;; against current tips (sandbox/create-branch! fetches too).
          ;; Best-effort: a fetch failure (offline worktree) degrades to a
          ;; possibly-stale base rather than aborting the release.
          _ (process/shell dir-opts "git" "fetch" "origin" default-branch)
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
      (result/shell-failure (.getMessage e) {:branch nil})))))

(defn fetch-branch!
  "Fetch origin/<branch> into the local worktree.

   Used when a stacked-PR base is a parent task's branch: pulls it so later
   `origin/<base>` range diffs and commits-ahead resolve on the host.
   Returns a shell-result."
  [worktree-path branch]
  (or
   (validate-safe-branch-name branch)
   (try
     (let [r (process/shell
              {:dir (str worktree-path) :out :string :err :string :continue true}
              "git" "fetch" "origin" (str branch))]
       (if (zero? (:exit r))
         (result/shell-success {:output (get r :out "")})
         (result/shell-failure (get r :err ""))))
     (catch Exception e
       (result/shell-failure (.getMessage e))))))

(defn commits-ahead-of-base
  "Count commits HEAD has added since branching from origin/<base>.
   Uses the three-dot merge-base form so concurrent movement of origin/<base>
   does not shrink the count to zero. Returns nil on git failure."
  [worktree-path base-branch]
  (when-not (validate-safe-branch-name base-branch)
    (try
      (let [r (process/shell
               {:dir (str worktree-path) :out :string :err :string :continue true}
               "git" "rev-list" "--count" "--right-only"
               (str "origin/" base-branch "...HEAD"))]
        (when (zero? (:exit r))
          (try
            (Long/parseLong (str/trim (get r :out "0")))
            (catch NumberFormatException _ nil))))
      (catch Exception _ nil))))

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
  (when-not (validate-safe-branch-name base-branch)
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
      (catch Exception _ nil))))

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
  (when-not (validate-safe-branch-name base-branch)
    (try
      (let [r (process/shell
               {:dir (str worktree-path) :out :string :err :string :continue true}
               "git" "diff" (str "origin/" base-branch "...HEAD") "-U0")]
        (when (zero? (:exit r))
          (let [diff-text (get r :out "")
                added   (count (re-seq #"(?m)^\+.*\(deftest " diff-text))
                removed (count (re-seq #"(?m)^-.*\(deftest " diff-text))]
            {:added added :removed removed})))
      (catch Exception _ nil))))

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

(defn- ssh->https-with-token
  "Convert an SSH or HTTPS git remote URL to HTTPS with token auth embedded,
   or nil when conversion is not possible. Mirrors sandbox/ssh->https-with-token."
  [remote-url token]
  (when remote-url
    (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" remote-url)]
      (str "https://x-access-token:" token "@" host "/" path)
      (when (str/starts-with? (str remote-url) "https://")
        (str/replace remote-url #"https://" (str "https://x-access-token:" token "@"))))))

(defn- raw-push!
  "One `git push -u origin <branch>` with GH_TOKEN injected. Returns a
   shell-result."
  [worktree-path branch-name github-token]
  (try
    (let [r (process/shell
             (gh-shell-opts worktree-path github-token)
             "git" "push" "-u" "origin" branch-name)]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err ""))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn push-branch!
  "Push the current branch to origin. Injects `github-token` as GH_TOKEN for
   gh's git credential helper. If the initial push fails and a token is
   present, retries over HTTPS with the token embedded in a temporary origin
   URL (restoring the original afterwards) — so a token-only environment
   whose `origin` is an SSH remote can still push, matching
   sandbox/push-branch!.

   Returns {:success? bool :error string}"
  [worktree-path branch-name github-token]
  (or
   (validate-safe-branch-name branch-name)
   (let [result (raw-push! worktree-path branch-name github-token)]
     (if (or (result/succeeded? result) (not github-token))
       result
       (let [url-r      (exec! worktree-path ["git" "remote" "get-url" "origin"])
             remote-url (when (result/succeeded? url-r) (str/trim (get url-r :output "")))
             https-url  (ssh->https-with-token remote-url github-token)]
         (if https-url
           (do
             (exec! worktree-path ["git" "remote" "set-url" "origin" https-url])
             (let [retry     (raw-push! worktree-path branch-name github-token)
                   ;; Restore the original remote URL so the token never
                   ;; persists in the worktree's git config.
                   restore-r (exec! worktree-path
                                    ["git" "remote" "set-url" "origin" remote-url])]
               (if (result/succeeded? restore-r)
                 retry
                 ;; The restore failed — origin may still embed the token.
                 ;; Fail loud (even if the push itself succeeded) with the
                 ;; scrub command, rather than report a clean success while a
                 ;; credential is persisted in git config.
                 (result/shell-failure
                  (str "Pushed via HTTPS token fallback but could not restore the "
                       "origin remote URL; it may still embed the GitHub token. "
                       "Scrub it: git remote set-url origin " remote-url ". "
                       "Restore error: " (:error restore-r))
                  {:push-succeeded? (result/succeeded? retry)}))))
           result))))))

(defn force-push!
  "Force-push the current branch with --force-with-lease, injecting
   `github-token` as GH_TOKEN so gh's git credential helper authenticates
   with the pipeline credential (the PR-doc amend path uses this). Returns a
   shell-result."
  [worktree-path github-token]
  (try
    (let [r (process/shell
             (gh-shell-opts worktree-path github-token)
             "git" "push" "--force-with-lease")]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err ""))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn- inside-worktree?
  "True when `file` resolves inside `worktree-path` after canonicalization
   (collapses `..`, symlinks, and redundant separators). Kept self-contained
   rather than reusing files/path-traversal-anomaly, which depends on this
   namespace (a git→files require would cycle)."
  [worktree-path ^java.io.File file]
  (try
    (let [root   (.getCanonicalPath (java.io.File. (str worktree-path)))
          target (.getCanonicalPath file)]
      (or (= target root)
          (str/starts-with? target (str root java.io.File/separator))))
    ;; getCanonicalPath can throw on invalid paths / IO errors. The check
    ;; runs before write-file!'s own try, so fail closed here (treat as
    ;; escaping) — write-file! then returns a shell-failure, never throws.
    (catch Exception _ false)))

(defn write-file!
  "Write content to a path inside the worktree, creating parent dirs.
   Rejects a `rel-path` that escapes the worktree root (absolute path, `..`
   segments, or symlinked parents) via a canonical-path check — parity with
   the sandbox backend's guard. Returns {:success? bool :output string
   :error string}."
  [worktree-path rel-path content]
  (let [file (java.io.File. (str worktree-path) (str rel-path))]
    (if-not (inside-worktree? worktree-path file)
      (result/shell-failure (str "Path traversal rejected: " rel-path
                                 " escapes worktree root"))
      (try
        (let [parent (.getParentFile file)]
          (when parent (.mkdirs parent))
          (spit file content)
          (result/shell-success {:output (str rel-path)}))
        (catch Exception e
          (result/shell-failure (.getMessage e)))))))

(defn edit-pr-body!
  "Update the body of an existing PR using gh CLI on the host. Injects
   `github-token` as GH_TOKEN.
   Returns {:success? bool :output string :error string}."
  [worktree-path pr-number body github-token]
  (try
    (let [r (process/shell
             (gh-shell-opts worktree-path github-token)
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
  [worktree-path github-token]
  (try
    (let [r (process/shell
             (gh-shell-opts worktree-path github-token)
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
  [worktree-path {:keys [title body base-branch]} github-token]
  (let [base (or base-branch "main")]
    (or
     (validate-safe-branch-name base)
     (try
       (let [result (process/shell
                     (gh-shell-opts worktree-path github-token)
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
           (reuse-existing-pr! worktree-path github-token)

           :else
           (result/shell-failure (get result :err "") {:pr-url nil :pr-number nil})))
       (catch Exception e
         (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))))
