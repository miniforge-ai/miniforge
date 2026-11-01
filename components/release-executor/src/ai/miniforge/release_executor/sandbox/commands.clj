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
(ns ai.miniforge.release-executor.sandbox.commands
  "The single sandbox commands `sandbox` builds its operations from:
   `exec!`, the container path and branch-name guards, gh and git output
   parsing, and the operations that run one git or gh command (or a fixed
   short sequence of them). Split out of `sandbox` (rule 210); `sandbox`
   keeps aliases for the names its callers use.

   All commands are governed — they execute inside the task capsule via
   dag/executor-execute!, never through host-side shell/sh."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.redaction.interface :as redaction]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers
(defn- ^{:stratum 0} unsafe-container-path-result
  [message type path]
  (result/shell-failure message {:type type :path path}))

(defn ^{:stratum 0} exec!
  "Execute a command in the sandbox environment.
   Returns {:success? bool :output string :error string}, plus
   :timed-out? true when the runtime stopped the command at its deadline
   (exit 124, coreutils timeout(1)'s convention), or :interrupted? true
   when it was interrupted (exit 130, or the executor's :exec-interrupted):
   either way it may have taken effect.
   Optional opts map is merged with {:capture-output? true} and
   forwarded to the executor (supports :env, :timeout-ms, :workdir)."
  ([executor env-id command] (exec! executor env-id command {}))
  ([executor env-id command opts]
   (let [r (dag/executor-execute! executor env-id command
                                  (merge {:capture-output? true} opts))]
     (if (dag/ok? r)
       (let [{:keys [exit-code stdout stderr]} (:data r)]
         (if (zero? exit-code)
           (result/shell-success {:output (str/trim (or stdout ""))})
           (result/shell-failure (str/trim (or stderr ""))
                                 (cond-> {:output (str/trim (or stdout ""))}
                                   (= 124 exit-code) (assoc :timed-out? true)
                                   (= 130 exit-code) (assoc :interrupted? true)))))
       (result/shell-failure (str "Executor error: " (:error r))
                             (cond-> {:output ""}
                               (= :exec-interrupted (get-in r [:error :code]))
                               (assoc :interrupted? true)))))))

(defn ^{:stratum 0} ssh->https-with-token
  "Convert an SSH or HTTPS git remote URL to HTTPS with token auth.
   Returns the authenticated URL, or nil if conversion fails."
  [remote-url token]
  (when remote-url
    (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" remote-url)]
      (str "https://x-access-token:" token "@" host "/" path)
      (when (str/starts-with? (str remote-url) "https://")
        (str/replace remote-url #"https://" (str "https://x-access-token:" token "@"))))))

(defn ^{:stratum 0} parse-pr-ref
  "Parse a gh PR URL into {:pr-url :pr-number}, or nil when the output carries
   no /pull/<n> reference (so a phantom success can be told from a real PR)."
  [output]
  (let [url (str/trim (or output ""))]
    (when-let [match (re-find #"/pull/(\d+)" url)]
      {:pr-url url :pr-number (parse-long (second match))})))

(defn ^{:stratum 0} pr-already-exists?
  "True when a gh error indicates a PR already exists for the current branch."
  [error]
  (boolean (some-> error str/lower-case (str/includes? "already exists"))))

;; Diff inspection (governed equivalents of git/diff-stats, git/count-test-defs)
(defn ^{:stratum 0} numstat-totals
  "Parse `git diff <range> --numstat` output into {:additions :deletions :files}."
  [output]
  (let [lines (remove str/blank? (str/split-lines (str/trim (or output ""))))
        parsed (keep (fn [line]
                       (when-let [[_ adds dels] (re-matches #"(\d+)\t(\d+)\t.*" line)]
                         {:additions (parse-long adds)
                          :deletions (parse-long dels)}))
                     lines)]
    {:additions (reduce + 0 (map :additions parsed))
     :deletions (reduce + 0 (map :deletions parsed))
     :files (count parsed)}))

(defn ^{:stratum 0} count-deftest
  "Count `(deftest …` lines added vs removed in a unified diff body."
  [diff-text]
  {:added   (count (re-seq #"(?m)^\+.*\(deftest " (or diff-text "")))
   :removed (count (re-seq #"(?m)^-.*\(deftest " (or diff-text "")))})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate-safe-container-path
  "Return nil when `path` is safe to interpolate into a single-quoted shell argument
   and cannot escape the container working directory.

   Two classes of attack are blocked:

   1. Path traversal -- any `..` segment (lexical check; host-side
      normalization cannot be applied to a container path).

   2. Shell injection -- any character that terminates a POSIX single-quoted
      string or introduces a shell metacharacter that survives quoting:
      single-quote, backslash, backtick, $, !, space, tab, newline, NUL,
      and the glob/redirect set (; | & > < ( ) { } * ? [ ] # ~).

   Returns a shell failure result with `:type :path-traversal` or
   `:type :shell-injection` on violation."
  [path]
  (let [s (str path)]
    (cond
      (or (str/starts-with? s "/")
          (re-find #"^[A-Za-z]:" s))
      (unsafe-container-path-result
       "Path traversal rejected: sandbox path must be relative"
       :path-traversal
       s)

      (re-find #"(^|[/\\])\.\.([/\\]|$)" s)
      (unsafe-container-path-result
       "Path traversal rejected: sandbox path contains .. segment"
       :path-traversal
       s)

      (re-find #"['\\`$! \t\n\r\x00;|&><(){}*?\[\]#~]" s)
      (unsafe-container-path-result
       "Shell injection rejected: sandbox path contains unsafe character"
       :shell-injection
       s))))

(defn ^{:stratum 1} validate-safe-branch-name
  "Return nil when `branch` is safe to interpolate unquoted into a shell
   command (git checkout -b, git push, --base). Allows only
   [a-zA-Z0-9._/-] — the character set git itself permits for branch names.
   Additionally rejects blank names and names starting with '-', which
   would be interpreted by git as option flags (option injection)."
  [branch]
  (let [s (str branch)]
    (cond
      (str/blank? s)
      (unsafe-container-path-result
       "Shell injection rejected: branch name must not be blank"
       :shell-injection
       s)

      (str/starts-with? s "-")
      (unsafe-container-path-result
       "Shell injection rejected: branch name must not start with '-'"
       :shell-injection
       s)

      (re-find #"[^a-zA-Z0-9._/\-]" s)
      (unsafe-container-path-result
       "Shell injection rejected: branch name contains unsafe character"
       :shell-injection
       s))))

;; Git / GH operations (mirrors git.clj)
(defn ^{:stratum 1} check-gh-auth!
  "Check if gh CLI is available and authenticated inside the container.
   Optional opts supports :env for injecting GH_TOKEN."
  ([executor env-id] (check-gh-auth! executor env-id {}))
  ([executor env-id opts]
   (let [r (exec! executor env-id "gh auth status" opts)]
     (if (result/succeeded? r)
       {:available? true :authenticated? true :user "container-token"}
       {:available? true :authenticated? false :error (:error r)}))))

(defn ^{:stratum 1} detect-default-branch
  "Detect the default branch from the remote. Falls back to
   \"refs/remotes/origin/main\" when :output is blank, not merely absent —
   exec!'s executor-error branch always includes :output (as \"\"), so
   (:output r default) alone would never reach the default in that case,
   quietly producing an empty branch name downstream instead of masking
   nothing."
  [executor env-id]
  (let [r (exec! executor env-id
                 "git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null || echo refs/remotes/origin/main")
        out (:output r)]
    (-> (if (str/blank? out) "refs/remotes/origin/main" out)
        str/trim
        (str/replace #"refs/remotes/origin/" ""))))

(defn ^{:stratum 1} commit-changes!
  "Commit staged changes inside the sandbox container.

   Returns {:success? bool :commit-sha string :error string}"
  [executor env-id commit-message]
  (let [escaped-msg (str/replace commit-message "'" "'\\''")
        commit-r (exec! executor env-id (str "git commit -m '" escaped-msg "'"))]
    (if (result/succeeded? commit-r)
      (let [sha-r (exec! executor env-id "git rev-parse HEAD")]
        (if (result/succeeded? sha-r)
          (result/shell-success {:commit-sha (:output sha-r "")
                                 :output (:output commit-r)})
          ;; The commit itself succeeded, but resolving its sha didn't — do
          ;; NOT report shell-success with an empty/missing :commit-sha, which
          ;; would mislead callers that expect a real sha. Surface the
          ;; rev-parse failure instead.
          (result/shell-failure (:error sha-r) {:commit-sha nil})))
      (result/shell-failure (:error commit-r) {:commit-sha nil}))))

(defn ^{:stratum 1} push-with-https-fallback!
  "Push using HTTPS + token auth after SSH fails. Temporarily sets the
   remote URL to include the token, pushes, then restores the original URL
   so the token never persists in git config.

   The token-bearing URL is passed to `git remote set-url` as an argv
   vector, never a shell string — `exec!` routes string commands through
   `sh -c`, which would otherwise expose the embedded token to shell
   interpolation. Mirrors `git/with-https-token-fallback!`: a failed
   set-url aborts before pushing against a remote that was never actually
   repointed; a failed restore fails loud with a scrub command rather than
   silently leaving the token persisted in git config. All three exec!
   calls share the caller's `opts` (e.g. `:workdir`) so the repoint, push,
   and restore all target the same executor context — a `:workdir`
   applied only to the push would otherwise leave set-url/restore running
   against the wrong directory."
  [executor env-id branch-name remote-url https-url push-error opts]
  (let [set-r (exec! executor env-id ["git" "remote" "set-url" "origin" https-url] opts)]
    (if-not (result/succeeded? set-r)
      (result/shell-failure
       (msg/t :push/https-fallback-setup-failed
              {:push-error    push-error
               :set-url-error (:error set-r)})
       {:push-succeeded? false})
      (let [retry     (exec! executor env-id (str "git push -u origin " branch-name) opts)
            restore-r (exec! executor env-id ["git" "remote" "set-url" "origin" remote-url] opts)]
        (if (result/succeeded? restore-r)
          retry
          (let [pushed? (result/succeeded? retry)]
            (result/shell-failure
             (if pushed?
               (msg/t :push/https-fallback-restore-failed
                      {:remote-url    remote-url
                       :restore-error (:error restore-r)})
               (msg/t :push/https-fallback-push-and-restore-failed
                      {:remote-url    remote-url
                       :push-error    (:error retry)
                       :restore-error (:error restore-r)}))
             (merge {:push-succeeded? pushed?}
                    (select-keys retry [:timed-out? :interrupted?])))))))))

(defn ^{:stratum 1} reuse-existing-pr!
  "Resolve the PR already open for the current branch via gh pr view, so a
   release retry reuses it instead of opening a duplicate. Falls back to a
   failure carrying the `gh pr view` resolution error (not the original
   create error) when it can't be resolved — see the comment below."
  [executor env-id exec-opts _create-error]
  (let [r (exec! executor env-id "gh pr view --json url --jq '.url'" exec-opts)]
    (if-let [pr (and (result/succeeded? r) (parse-pr-ref (:output r "")))]
      (result/shell-success pr)
      ;; Surface the actual gh pr view resolution failure (its stderr, or the
      ;; unparseable stdout when it exited 0 without a URL) — not the original
      ;; "already exists" create error — so retries are debuggable.
      (result/shell-failure (msg/t :pr/reuse-unresolved
                                   {:error (or (not-empty (:error r))
                                               (not-empty (str/trim (:output r "")))
                                               (msg/t :pr/reuse-no-url))})
                            {:pr-url nil :pr-number nil}))))

(defn ^{:stratum 1} edit-pr-body!
  "Update the body of an existing PR using gh CLI.
   Used to replace the initial stub body with the full PR doc content.
   Secrets in `body` are replaced with the redaction marker first."
  ([executor env-id pr-number body] (edit-pr-body! executor env-id pr-number body {}))
  ([executor env-id pr-number body exec-opts]
   (let [escaped-body (str/replace (or (redaction/redact body) "") "'" "'\\''")
         cmd (str "gh pr edit " pr-number " --body '" escaped-body "'")]
     (exec! executor env-id cmd exec-opts))))

(defn ^{:stratum 1} diff-stats
  "Get staged diff stats via executor. Mirrors git/diff-stats.
   Returns {:additions N :deletions N :files N} or nil."
  [executor env-id]
  (let [r (exec! executor env-id "git diff --cached --numstat")]
    (when (result/succeeded? r)
      (numstat-totals (:output r "")))))

(defn ^{:stratum 1} count-test-defs
  "Count deftest forms in staged changes via executor. Mirrors git/count-test-defs.
   Returns {:added N :removed N} or nil."
  [executor env-id]
  (let [r (exec! executor env-id "git diff --cached -U0")]
    (when (result/succeeded? r)
      (count-deftest (:output r "")))))
