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
(ns ai.miniforge.release-executor.sandbox
  "Sandbox operations for release executor.

   Mirrors the git.clj API but routes commands through the DAG executor's
   Docker backend. Used when the workflow runs in sandbox mode, where the
   container serves as an isolated workspace for file I/O, git ops, and PR creation.

   All commands are governed — they execute inside the task capsule via
   dag/executor-execute!, never through host-side shell/sh."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers
(defn- ^{:stratum 0} unsafe-container-path-result
  [message type path]
  (result/shell-failure message {:type type :path path}))

(defn ^{:stratum 0} exec!
  "Execute a command in the sandbox environment.
   Returns {:success? bool :output string :error string}.
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
                                 {:output (str/trim (or stdout ""))})))
       (result/shell-failure (str "Executor error: " (:error r)))))))

(defn- ^{:stratum 0} ssh->https-with-token
  "Convert an SSH or HTTPS git remote URL to HTTPS with token auth.
   Returns the authenticated URL, or nil if conversion fails."
  [remote-url token]
  (when remote-url
    (if-let [[_ host path] (re-matches #"git@([^:]+):(.+)" remote-url)]
      (str "https://x-access-token:" token "@" host "/" path)
      (when (str/starts-with? (str remote-url) "https://")
        (str/replace remote-url #"https://" (str "https://x-access-token:" token "@"))))))

(defn- ^{:stratum 0} parse-pr-ref
  "Parse a gh PR URL into {:pr-url :pr-number}, or nil when the output carries
   no /pull/<n> reference (so a phantom success can be told from a real PR)."
  [output]
  (let [url (str/trim (or output ""))]
    (when-let [match (re-find #"/pull/(\d+)" url)]
      {:pr-url url :pr-number (parse-long (second match))})))

(defn- ^{:stratum 0} pr-already-exists?
  "True when a gh error indicates a PR already exists for the current branch."
  [error]
  (boolean (some-> error str/lower-case (str/includes? "already exists"))))

;; Diff inspection (governed equivalents of git/diff-stats, git/count-test-defs)
(defn- ^{:stratum 0} numstat-totals
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

(defn- ^{:stratum 0} count-deftest
  "Count `(deftest …` lines added vs removed in a unified diff body."
  [diff-text]
  {:added   (count (re-seq #"(?m)^\+.*\(deftest " (or diff-text "")))
   :removed (count (re-seq #"(?m)^-.*\(deftest " (or diff-text "")))})

(defn ^{:stratum 0} track-operation
  "Update metrics for a completed file operation."
  [metrics {:keys [action path]} op-result]
  (if (result/succeeded? op-result)
    (case action
      :create (update metrics :created inc)
      :modify (update metrics :modified inc)
      :delete (update metrics :deleted inc)
      metrics)
    (update metrics :errors conj
           {:type :file-operation-failed
            :message (:error op-result)
            :file path
            :action action})))

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
  "Detect the default branch from the remote."
  [executor env-id]
  (let [r (exec! executor env-id
                 "git symbolic-ref refs/remotes/origin/HEAD 2>/dev/null || echo refs/remotes/origin/main")]
    (-> (:output r "refs/remotes/origin/main")
        str/trim
        (str/replace #"refs/remotes/origin/" ""))))

(defn ^{:stratum 1} stage-files!
  "Stage files in the sandbox container."
  [executor env-id file-paths]
  (let [cmd (if (= file-paths :all)
              "git add ."
              (str "git add " (str/join " " (map #(str "'" % "'") file-paths))))]
    (exec! executor env-id cmd)))

(defn ^{:stratum 1} commit-changes!
  "Commit staged changes inside the sandbox container.

   Returns {:success? bool :commit-sha string :error string}"
  [executor env-id commit-message]
  (let [escaped-msg (str/replace commit-message "'" "'\\''")
        commit-r (exec! executor env-id (str "git commit -m '" escaped-msg "'"))]
    (if (result/succeeded? commit-r)
      (let [sha-r (exec! executor env-id "git rev-parse HEAD")]
        (result/shell-success {:commit-sha (:output sha-r "")
                               :output (:output commit-r)}))
      (result/shell-failure (:error commit-r) {:commit-sha nil}))))

(defn- ^{:stratum 1} push-with-https-fallback!
  "Push using HTTPS + token auth after SSH fails. Temporarily sets the
   remote URL to include the token, pushes, then restores the original URL."
  [executor env-id branch-name remote-url https-url opts]
  (exec! executor env-id (str "git remote set-url origin " https-url) {})
  (let [retry (exec! executor env-id (str "git push -u origin " branch-name) opts)]
    (exec! executor env-id (str "git remote set-url origin " remote-url) {})
    retry))

(defn- ^{:stratum 1} reuse-existing-pr!
  "Resolve the PR already open for the current branch via gh pr view, so a
   release retry reuses it instead of opening a duplicate. Falls back to a
   failure carrying the original create error when it can't be resolved."
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
   Used to replace the initial stub body with the full PR doc content."
  ([executor env-id pr-number body] (edit-pr-body! executor env-id pr-number body {}))
  ([executor env-id pr-number body exec-opts]
   (let [escaped-body (str/replace (or body "") "'" "'\\''")
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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} try-checkout-branch
  "Try to checkout a new branch off the current HEAD, retrying with a
   timestamp suffix if the desired name already exists.

   Branches off HEAD (not `origin/<base-branch>`) so the new branch
   inherits whatever commits the task worktree already carries — e.g.
   the per-phase boundary commits the workflow runtime makes after
   plan/implement/verify/review. If we branched off `origin/<base>`
   instead, those commits would be discarded and a downstream
   `git add .` would find nothing to stage. `:base-branch` is still
   tracked for the PR-creation step, which uses it as the merge base."
  [executor env-id branch-name base-branch]
  (if-let [guard (validate-safe-branch-name branch-name)]
    guard
    (let [checkout-r (exec! executor env-id
                            (str "git checkout -b " branch-name))]
    (if (result/succeeded? checkout-r)
      (result/shell-success {:branch branch-name :base-branch base-branch})
      (let [ts-name (str branch-name "-" (System/currentTimeMillis))
            retry-r (exec! executor env-id
                           (str "git checkout -b " ts-name))]
        (if (result/succeeded? retry-r)
          (result/shell-success {:branch ts-name :base-branch base-branch})
          (result/shell-failure (str "Failed to create branch: " (:error retry-r))
                               {:branch nil})))))))

(defn ^{:stratum 2} fetch-branch!
  "Fetch origin/<branch> into the sandbox. `create-branch!` fetches only the
   detected default; when a stacked-PR base is a parent task's branch, this
   pulls it so later `origin/<base>` range diffs / commits-ahead and the PR
   merge-base resolve instead of failing on a missing remote-tracking ref.
   Returns a shell-result."
  [executor env-id branch]
  (or (validate-safe-branch-name branch)
      (exec! executor env-id (str "git fetch origin " branch))))

(defn ^{:stratum 2} commits-ahead-of-base
  "Count commits HEAD has added since branching from `origin/<base>`.
   Counts against the merge-base (`origin/<base>...HEAD`, three-dot
   form, right-only) so concurrent movement of `origin/<base>` while
   the workflow was running doesn't artificially shrink the count to
   zero. Returns nil on git failure (caller treats nil as 'unknown',
   not zero). Used by step-stage-dirty-files to recognise that a clean
   worktree may still carry unreleased work in the form of boundary
   commits — when the implementer's writes were committed at the
   implement-phase boundary, the release branch inherits them and
   there is nothing left to stage."
  [executor env-id base-branch]
  (when-not (validate-safe-branch-name base-branch)
    (let [r (exec! executor env-id
                   (str "git rev-list --count --right-only origin/" base-branch "...HEAD"))]
      (when (result/succeeded? r)
      (try
        (Long/parseLong (str/trim (:output r "0")))
        (catch NumberFormatException _ nil))))))

(defn ^{:stratum 2} write-file!
  "Write content to a file inside the sandbox container.
   Uses base64 encoding to safely transfer arbitrary content.
   Returns a shell failure result if `path` is unsafe."
  [executor env-id path content]
  (if-let [invalid (validate-safe-container-path path)]
    invalid
    (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes content "UTF-8"))
          cmd (str "mkdir -p \"$(dirname '" path "')\" && "
                   "echo '" encoded "' | base64 -d > '" path "'")]
      (exec! executor env-id cmd))))

(defn ^{:stratum 2} delete-file!
  "Delete a file inside the sandbox container.
   Returns a shell failure result if `path` is unsafe."
  [executor env-id path]
  (if-let [invalid (validate-safe-container-path path)]
    invalid
    (exec! executor env-id (str "rm -f '" path "'"))))

(defn ^{:stratum 2} push-branch!
  "Push branch to origin inside the sandbox container.
   Optional opts supports :env for credential injection.
   Retries with GH_TOKEN HTTPS auth if SSH push fails."
  ([executor env-id branch-name] (push-branch! executor env-id branch-name {}))
  ([executor env-id branch-name opts]
   (or (validate-safe-branch-name branch-name)
       (let [result (exec! executor env-id (str "git push -u origin " branch-name) opts)]
         (if (result/succeeded? result)
       result
       (if-let [token (get-in opts [:env "GH_TOKEN"])]
         (let [url-r (exec! executor env-id "git remote get-url origin" {})
               remote-url (when (result/succeeded? url-r) (str/trim (get url-r :output "")))
               https-url (ssh->https-with-token remote-url token)]
           (if https-url
             (push-with-https-fallback! executor env-id branch-name remote-url https-url opts)
             result))
         result))))))

(defn ^{:stratum 2} create-pr!
  "Create a pull request using gh CLI inside the sandbox container.
   Optional exec-opts supports :env for GH_TOKEN injection.
   Returns {:success? bool :pr-number int :pr-url string :error string}.

   Two robustness guarantees beyond the bare gh call:
   - A `gh pr create` that exits 0 but prints no PR URL is a FAILURE, not a
     phantom success — release must never ship a PR doc claiming a PR it
     never opened.
   - A branch that already has an open PR (retry/resume) reuses that PR
     rather than opening a duplicate."
  ([executor env-id pr-opts] (create-pr! executor env-id pr-opts {}))
  ([executor env-id {:keys [title body base-branch]} exec-opts]
   (let [base (or base-branch "main")]
     (or (validate-safe-branch-name base)
         (let [escaped-title (str/replace title "'" "'\\''")
               escaped-body (str/replace (or body "") "'" "'\\''")
               cmd (str "gh pr create"
                        " --title '" escaped-title "'"
                        " --body '" escaped-body "'"
                        " --base " base)
               r (exec! executor env-id cmd exec-opts)]
           (cond
             (result/succeeded? r)
             (if-let [pr (parse-pr-ref (:output r ""))]
               (result/shell-success pr)
               (result/shell-failure (msg/t :pr/create-unconfirmed
                                            {:output (str/trim (:output r ""))})
                                     {:pr-url nil :pr-number nil}))

             (pr-already-exists? (:error r))
             (reuse-existing-pr! executor env-id exec-opts (:error r))

             :else
             (result/shell-failure (:error r) {:pr-url nil :pr-number nil})))))))

(defn ^{:stratum 2} diff-stats-range
  "Get diff stats for the changes this branch introduced since branching
   from `origin/<base>` — i.e. `git diff origin/<base>...HEAD` with the
   three-dot form, which compares HEAD to the merge-base rather than to
   the current tip of `origin/<base>`. The two-dot form would compare
   trees directly, so files merged into `origin/<base>` while the
   workflow was running would surface as spurious deletions on the
   branch and trip the destructive-diff gate. Mirrors `diff-stats` but
   reads the merge-base range rather than the staged index. Used in
   the boundary-commits release path."
  [executor env-id base-branch]
  (when-not (validate-safe-branch-name base-branch)
    (let [r (exec! executor env-id
                   (str "git diff origin/" base-branch "...HEAD --numstat"))]
      (when (result/succeeded? r)
        (numstat-totals (:output r ""))))))

(defn ^{:stratum 2} count-test-defs-range
  "Count deftest forms added/removed in `origin/<base>...HEAD` (three-dot,
   merge-base diff). Mirrors `count-test-defs` but reads the merge-base
   range so the destructive-diff gate keeps inspecting the commits this
   branch introduced — not deltas caused by `origin/<base>` moving
   forward concurrently."
  [executor env-id base-branch]
  (when-not (validate-safe-branch-name base-branch)
    (let [r (exec! executor env-id
                   (str "git diff origin/" base-branch "...HEAD -U0"))]
      (when (result/succeeded? r)
        (count-deftest (:output r ""))))))

(defn ^{:stratum 2} metrics->result
  "Convert operation metrics to a final result, staging files if no errors."
  [executor env-id written-paths {:keys [created modified deleted errors]}]
  (let [file-metrics {:files-written created
                      :files-modified modified
                      :files-deleted deleted}]
    (if (seq errors)
      {:success? false :errors errors :metrics file-metrics}
      (let [stage-r (stage-files! executor env-id written-paths)]
        (if (result/succeeded? stage-r)
          {:success? true
           :metrics (assoc file-metrics :total-operations (+ created modified deleted))}
          {:success? false
           :errors [{:type :git-stage-failed :message (:error stage-r)}]
           :metrics file-metrics})))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} create-branch!
  "Create a new git branch inside the sandbox container, off the current
   HEAD (so phase-boundary commits already on the task branch carry
   forward). Fetches `origin/<default-branch>` first so the PR-creation
   step has a fresh merge base to compare against.

   Returns {:success? bool :branch string :base-branch string :error string}"
  [executor env-id branch-name]
  (let [default-branch (detect-default-branch executor env-id)
        fetch-r (exec! executor env-id (str "git fetch origin " default-branch))]
    (if-not (result/succeeded? fetch-r)
      (result/shell-failure (str "Failed to fetch: " (:error fetch-r)) {:branch nil})
      (try-checkout-branch executor env-id branch-name default-branch))))

;; File batch operations (mirrors files.clj write-and-stage-files!)
(defn ^{:stratum 3} apply-file-operation!
  "Apply a single file operation (create, modify, delete) in the sandbox."
  [executor env-id {:keys [action path content]}]
  (case action
    :create (write-file! executor env-id path content)
    :modify (write-file! executor env-id path content)
    :delete (delete-file! executor env-id path)
    (result/shell-failure (str "Unknown action: " action))))

;------------------------------------------------------------------------------ Layer 4

(defn ^{:stratum 4} write-and-stage-files!
  "Write code artifact files into the sandbox and stage them.
   Returns result map matching files/write-and-stage-files! contract."
  [executor env-id code-artifacts]
  (let [all-files (mapcat :code/files code-artifacts)
        metrics (reduce
                 (fn [m file-op]
                   (let [r (apply-file-operation! executor env-id file-op)]
                     (track-operation m file-op r)))
                 {:created 0 :modified 0 :deleted 0 :errors []}
                 all-files)]
    (metrics->result executor env-id (map :path all-files) metrics)))
