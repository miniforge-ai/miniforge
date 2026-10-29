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

   Used when :host-mode? is true (local-dogfood / no executor supplied).

   Calls that reach a remote (git fetch, git push, gh) go through
   `shell/run-bounded`: a deadline, empty stdin, and prompts disabled, so a
   stalled connection or a credential prompt fails the call instead of
   blocking the release phase. Local git calls use process/shell directly."
  (:require
   [ai.miniforge.redaction.interface :as redaction]
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.push-auth :as push-auth]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.shell :as shell]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; gh auth result helpers
(defn ^{:stratum 0} gh-unavailable
  "Create result for gh CLI not available."
  [error-msg]
  {:available? false :authenticated? false :error error-msg})

(defn ^{:stratum 0} gh-available-unauthenticated
  "Create result for gh CLI available but not authenticated."
  [error-msg]
  {:available? true :authenticated? false :error error-msg})

(defn ^{:stratum 0} gh-authenticated
  "Create result for gh CLI available and authenticated."
  [user]
  {:available? true :authenticated? true :user user})

;; Safety + credential helpers (host-mode parity with sandbox.clj)
(defn ^{:stratum 0} validate-safe-branch-name
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

(defn- ^{:stratum 0} gh-shell-opts
  "shell/run-bounded option map for a remote git or gh call, injecting the
   caller's `github-token` as GH_TOKEN so host-mode uses the same credential
   the pipeline resolved — parity with sandbox's gh-exec-opts :env injection.
   `worktree-path` may be nil (e.g. `gh auth status` needs no cwd). When no
   token is supplied the call inherits the ambient environment. `extra-env`
   (the push fallback's `push-auth/retry-plan` env) is merged in. A call
   carrying a token runs with git's tracing variables removed
   (`shell/trace-env-var?`), so a trace cannot log the credential."
  ([worktree-path github-token] (gh-shell-opts worktree-path github-token nil))
  ([worktree-path github-token extra-env]
   (let [env (merge (when github-token {"GH_TOKEN" github-token}) extra-env)]
     (cond-> {}
       worktree-path (assoc :dir (str worktree-path))
       (seq env)     (assoc :extra-env env)
       github-token  (assoc :no-trace? true)))))

(defn- ^{:stratum 0} branch-refspec
  "Full refspec pushing local `branch` to the same-named branch on the
   remote. Without an explicit destination, remote.origin.push can remap
   one release push (`<branch>` -> review/<branch>) but not another, so
   the PR branch could be left unchanged while the push succeeds."
  [branch]
  (str "refs/heads/" branch ":refs/heads/" branch))

(defn- ^{:stratum 0} timeout-data
  "The :timed-out? flag and :timeout anomaly of a shell/run-bounded result,
   to carry into a shell-failure; {} when the call did not time out."
  [run-result]
  (select-keys run-result [:timed-out? :anomaly]))

;; Generic command runner
(defn ^{:stratum 0} exec!
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
      (result/shell-failure (.getMessage e) {:output ""}))))

;; Git operations
(defn ^{:stratum 0} stage-files!
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

(defn ^{:stratum 0} diff-stats
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

(defn ^{:stratum 0} count-test-defs
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

(defn ^{:stratum 0} commit-changes!
  "Commit staged changes with the given message. Optional `git-args` are
   global git options placed before `commit`, such as
   `[\"-c\" \"core.hooksPath=/dev/null\"]` to keep every hook from running.

   Secrets in `commit-message` are replaced with the redaction marker
   (components/redaction) before git records it: a commit is pushed and
   kept in history, so the message is written once, redacted.

   Returns {:success? bool :commit-sha string :error string}"
  ([worktree-path commit-message]
   (commit-changes! worktree-path commit-message nil))
  ([worktree-path commit-message git-args]
   (try
     (let [commit-r (apply process/shell
                           {:dir (str worktree-path) :out :string :err :string :continue true}
                           "git" (concat git-args ["commit" "-m" (redaction/redact commit-message)]))]
       (if (zero? (:exit commit-r))
         (let [sha-r (process/shell
                      {:dir (str worktree-path) :out :string :err :string :continue true}
                      "git" "rev-parse" "HEAD")]
           (if (zero? (:exit sha-r))
             (result/shell-success {:commit-sha (str/trim (get sha-r :out ""))
                                    :output (get commit-r :out "")})
             ;; The commit itself succeeded, but resolving its sha didn't — do
             ;; NOT report shell-success with an empty/missing :commit-sha,
             ;; which would mislead callers that expect a real sha. Surface
             ;; the rev-parse failure instead.
             (result/shell-failure (get sha-r :err "") {:commit-sha nil})))
         (result/shell-failure (get commit-r :err "") {:commit-sha nil})))
     (catch Exception e
       (result/shell-failure (.getMessage e) {:commit-sha nil})))))

(defn- ^{:stratum 0} inside-worktree?
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

;; PR creation helpers (mirrors sandbox.clj equivalents)
(defn- ^{:stratum 0} parse-pr-ref
  "Parse a gh PR URL output into {:pr-url :pr-number}, or nil when the output
   carries no /pull/<n> reference."
  [output]
  (let [url (str/trim (or output ""))]
    (when-let [match (re-find #"/pull/(\d+)" url)]
      {:pr-url url :pr-number (parse-long (second match))})))

(defn- ^{:stratum 0} pr-already-exists?
  "True when a gh error indicates a PR already exists for the current branch."
  [error]
  (boolean (some-> error str/lower-case (str/includes? "already exists"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} check-gh-auth!
  "Check if gh CLI is available and authenticated on the host. The optional
   `github-token` is injected as GH_TOKEN so the check reflects the
   credential the pipeline will use to open the PR (not just ambient host
   auth); the 0-arity keeps the historical public-interface signature and
   inherits the ambient environment.

   Returns {:available? bool :authenticated? bool :user string :error string},
   plus :timed-out? and :anomaly when `gh auth status` hit its deadline."
  ([] (check-gh-auth! nil))
  ([github-token]
   (try
    ;; fs/which, not the `which` binary: Windows has none, so shelling out
    ;; to it reported gh missing on every Windows host.
    (if-not (fs/which "gh")
      (gh-unavailable "gh CLI not found. Install with: brew install gh")
      (let [auth-r (shell/run-bounded ["gh" "auth" "status"]
                                      (gh-shell-opts nil github-token))]
        (if (zero? (:exit auth-r))
          (let [output (get auth-r :out "")
                user-match (re-find #"Logged in to [^\s]+ account (\S+)" output)]
            (gh-authenticated (or (second user-match) "unknown")))
          ;; A timeout is not an auth failure: report its own message and
          ;; carry :timed-out? / :anomaly like the other bounded calls.
          (merge (gh-available-unauthenticated
                  (if (:timed-out? auth-r)
                    (get auth-r :err "")
                    (str "gh not authenticated. Run: gh auth login\n" (get auth-r :err ""))))
                 (timeout-data auth-r)))))
    (catch Exception e
      (gh-unavailable (.getMessage e))))))

(defn ^{:stratum 1} create-branch!
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
          _ (shell/run-bounded ["git" "fetch" "origin" default-branch]
                               (gh-shell-opts worktree-path nil))
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

(defn ^{:stratum 1} fetch-branch!
  "Fetch origin/<branch> into the local worktree.

   Used when a stacked-PR base is a parent task's branch: pulls it so later
   `origin/<base>` range diffs and commits-ahead resolve on the host.
   Returns a shell-result."
  [worktree-path branch]
  (or
   (validate-safe-branch-name branch)
   (try
     (let [r (shell/run-bounded ["git" "fetch" "origin" (str branch)]
                                (gh-shell-opts worktree-path nil))]
       (if (zero? (:exit r))
         (result/shell-success {:output (get r :out "")})
         (result/shell-failure (get r :err "") (timeout-data r))))
     (catch Exception e
       (result/shell-failure (.getMessage e))))))

(defn ^{:stratum 1} commits-ahead-of-base
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

(defn ^{:stratum 1} diff-stats-range
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

(defn ^{:stratum 1} count-test-defs-range
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

(defn- ^{:stratum 1} raw-push!
  "One `git <git-args> push -u origin <branch-refspec>` with GH_TOKEN and
   `auth-env` (nil, or the fallback's `push-auth/retry-plan` env) injected.
   Returns a shell-result."
  [worktree-path branch-name github-token auth-env git-args]
  (try
    (let [r (shell/run-bounded (into ["git"] (concat git-args ["push" "-u" "origin"
                                                                (branch-refspec branch-name)]))
                               (gh-shell-opts worktree-path github-token auth-env))]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err "") (timeout-data r))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn- ^{:stratum 1} with-https-token-fallback!
  "Run `push!` (a 1-arg fn: extra env map or nil -> shell-result) with no
   extra env. On failure with a token present, retry once over HTTPS with
   the token in that push's environment only (`push-auth/retry-plan`), so
   an SSH `origin` that GH_TOKEN alone can't authenticate still pushes.
   The retry runs only when origin has one push URL, no rewrite rule
   matches it, it is on github.com, and the environment carries no git
   config of its own. Both pushes name origin, so the destination checked
   is the one used. Otherwise (mirrors, insteadOf rewrites, other hosts)
   the original failure comes back with the reason no credential was sent
   appended to :error and the message key in :token-retry-skipped. origin
   and .git/config are never modified.
   Shared by push-branch! and force-push!."
  [worktree-path github-token push!]
  (let [result (push! nil)]
    (if (or (result/succeeded? result) (not github-token))
      result
      (let [lines    (fn [args]
                       (let [r (exec! worktree-path args)]
                         (when (result/succeeded? r)
                           (remove str/blank? (str/split-lines (:output r))))))
            pushurls (lines ["git" "config" "--get-all" "remote.origin.pushurl"])
            {:keys [env skip]}
            (push-auth/retry-plan
             {:configured  (or (seq pushurls)
                               (lines ["git" "config" "--get-all" "remote.origin.url"]))
              :effective   (lines ["git" "remote" "get-url" "--push" "--all" "origin"])
              :pushurl?    (boolean (seq pushurls))
              :config      (lines ["git" "config" "--list"])
              :env-config? (push-auth/inherited-env-config? (shell/parent-env))}
             github-token)]
        (if env
          (push! env)
          (assoc result
                 :error (str (:error result) "\n"
                             (msg/t :push/token-retry-skipped {:reason (msg/t skip)}))
                 :token-retry-skipped skip))))))

(defn- ^{:stratum 1} raw-force-push!
  "One `git push --force-with-lease origin <branch-refspec>` with GH_TOKEN
   and `auth-env` (nil, or the fallback's `push-auth/retry-plan` env)
   injected. Names origin and the full refspec, the same target as
   raw-push!, so pushRemote/pushDefault and remote.origin.push cannot send
   it elsewhere. Returns a shell-result."
  [worktree-path branch-name github-token auth-env]
  (try
    (let [r (shell/run-bounded ["git" "push" "--force-with-lease" "origin"
                                (branch-refspec branch-name)]
                               (gh-shell-opts worktree-path github-token auth-env))]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err "") (timeout-data r))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn ^{:stratum 1} write-file!
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

(defn- ^{:stratum 1} gh-edit-pr-body!
  "Run `gh pr edit <n> --body <body>` on the host, injecting `github-token`
   as GH_TOKEN. `body` is published as given; callers go through
   edit-pr-body!, which redacts it first.
   Returns {:success? bool :output string :error string}."
  [worktree-path pr-number body github-token]
  (try
    (let [r (shell/run-bounded ["gh" "pr" "edit" (str pr-number) "--body" (or body "")]
                               (gh-shell-opts worktree-path github-token))]
      (if (zero? (:exit r))
        (result/shell-success {:output (get r :out "")})
        (result/shell-failure (get r :err "") (timeout-data r))))
    (catch Exception e
      (result/shell-failure (.getMessage e)))))

(defn- ^{:stratum 1} reuse-existing-pr!
  "Resolve the PR already open for the current branch via gh pr view, so a
   release retry reuses it instead of opening a duplicate.
   Falls back to a failure carrying the `gh pr view` resolution error (not
   the original create error) when the PR cannot be resolved via gh pr
   view — so retries are debuggable."
  [worktree-path github-token]
  (try
    (let [r (shell/run-bounded ["gh" "pr" "view" "--json" "url" "--jq" ".url"]
                               (gh-shell-opts worktree-path github-token))]
      (if-let [pr (and (zero? (:exit r))
                       (parse-pr-ref (get r :out "")))]
        (result/shell-success pr)
        (result/shell-failure (msg/t :pr/reuse-unresolved
                                     {:error (or (not-empty (str/trim (get r :err "")))
                                                 (not-empty (str/trim (get r :out "")))
                                                 (msg/t :pr/reuse-no-url))})
                              (merge {:pr-url nil :pr-number nil} (timeout-data r)))))
    (catch Exception e
      (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} edit-pr-body!
  "Update the body of an existing PR using gh CLI on the host. Secrets in
   `body` are replaced with the redaction marker (components/redaction)
   first: the body is LLM-written and the PR may be public. Injects
   `github-token` as GH_TOKEN.
   Returns {:success? bool :output string :error string}."
  [worktree-path pr-number body github-token]
  (gh-edit-pr-body! worktree-path pr-number (redaction/redact body) github-token))

(defn ^{:stratum 2} push-branch!
  "Push the current branch to origin. Injects `github-token` as GH_TOKEN for
   gh's git credential helper. If the initial push fails and a token is
   present, retries over HTTPS with the token passed in that push's
   environment only (see `push-auth/retry-plan`) — so a token-only environment
   whose `origin` is a github.com SSH remote can still push. Mirrors,
   rewritten URLs and other hosts are not retried.

   The optional `github-token` drives GH_TOKEN injection and the HTTPS
   fallback; the 2-arity keeps the historical public-interface signature
   (ambient credentials, no fallback). Optional `git-args` are global git
   options placed before `push`, such as `[\"-c\" \"core.hooksPath=/dev/null\"]`.

   Returns {:success? bool :error string}"
  ([worktree-path branch-name] (push-branch! worktree-path branch-name nil))
  ([worktree-path branch-name github-token]
   (push-branch! worktree-path branch-name github-token nil))
  ([worktree-path branch-name github-token git-args]
   (or
    (validate-safe-branch-name branch-name)
    (with-https-token-fallback!
     worktree-path github-token
     #(raw-push! worktree-path branch-name github-token % git-args)))))

(defn ^{:stratum 2} force-push!
  "Force-push the current branch with --force-with-lease, injecting
   `github-token` as GH_TOKEN so gh's git credential helper authenticates
   with the pipeline credential (the PR-doc amend path uses this). If the push
   fails and a token is present, retries over HTTPS + token auth via the same
   with-https-token-fallback! that push-branch! uses — an SSH `origin` can't be
   authenticated by GH_TOKEN alone, so without this the PR-doc amend push would
   fail in a token-only local run. `branch-name` is the release branch
   push-branch! pushed; both push the same full refspec. Returns a
   shell-result."
  [worktree-path branch-name github-token]
  (or
   (validate-safe-branch-name branch-name)
   (with-https-token-fallback!
    worktree-path github-token
    #(raw-force-push! worktree-path branch-name github-token %))))

(defn ^{:stratum 2} create-pr!
  "Create a pull request using gh CLI on the host.

   Two robustness guarantees matching sandbox/create-pr!:
   - A `gh pr create` that exits 0 but prints no PR URL is a FAILURE — release
     must never claim a PR it never opened.
   - A branch that already has an open PR (retry/resume) reuses that PR via
     `gh pr view` rather than opening a duplicate.

   The optional `github-token` (injected as GH_TOKEN) is the new host-mode
   credential path; the 2-arity keeps the historical public-interface
   signature and inherits the ambient environment.

   Secrets in the title and body are replaced with the redaction marker
   (components/redaction) before gh runs.

   Returns {:success? bool :pr-number int :pr-url string :error string}"
  ([worktree-path pr-opts] (create-pr! worktree-path pr-opts nil))
  ([worktree-path {:keys [title body base-branch]} github-token]
   (let [base  (or base-branch "main")
         ;; Title and body are LLM-written and may quote a credential the
         ;; agent saw; a PR is public, so strip secrets before gh sees them.
         title (redaction/redact title)
         body  (redaction/redact body)]
     (or
      (validate-safe-branch-name base)
      (try
        (let [result (shell/run-bounded ["gh" "pr" "create"
                                         "--title" title
                                         "--body"  (or body "")
                                         "--base"  base]
                                        (gh-shell-opts worktree-path github-token))]
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
            (result/shell-failure (get result :err "")
                                  (merge {:pr-url nil :pr-number nil} (timeout-data result)))))
        (catch Exception e
          (result/shell-failure (.getMessage e) {:pr-url nil :pr-number nil})))))))
