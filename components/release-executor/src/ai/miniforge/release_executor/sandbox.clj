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
   dag/executor-execute!, never through host-side shell/sh.

   The single commands these operations are built from (`exec!`, the path
   and branch-name guards, output parsing, one-command git and gh calls)
   live in `sandbox.commands`. Aliases keep the names callers use here.
   This namespace calls `commands` directly, so redefining `sandbox/exec!`
   steers only callers outside it; redefine `commands/exec!` to steer the
   operations here too."
  (:require
   [ai.miniforge.release-executor.messages :as msg]
   [ai.miniforge.release-executor.pr-lookup :as pr-lookup]
   [ai.miniforge.release-executor.result :as result]
   [ai.miniforge.release-executor.sandbox.commands :as commands]
   [ai.miniforge.redaction.interface :as redaction]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Aliases: the moved definitions keep their sandbox names for callers.
(def ^{:stratum 0} exec!
  "Alias of `commands/exec!`."
  commands/exec!)

(def ^{:stratum 0} validate-safe-branch-name
  "Alias of `commands/validate-safe-branch-name`."
  commands/validate-safe-branch-name)

(def ^{:stratum 0} check-gh-auth!
  "Alias of `commands/check-gh-auth!`."
  commands/check-gh-auth!)

(def ^{:stratum 0} detect-default-branch
  "Alias of `commands/detect-default-branch`."
  commands/detect-default-branch)

(def ^{:stratum 0} commit-changes!
  "Alias of `commands/commit-changes!`."
  commands/commit-changes!)

(def ^{:stratum 0} edit-pr-body!
  "Alias of `commands/edit-pr-body!`."
  commands/edit-pr-body!)

(def ^{:stratum 0} diff-stats
  "Alias of `commands/diff-stats`."
  commands/diff-stats)

(def ^{:stratum 0} count-test-defs
  "Alias of `commands/count-test-defs`."
  commands/count-test-defs)

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

(defn ^{:stratum 0} remote-branch-sha!
  "The commit origin's `branch` points at, via `git ls-remote` in the
   sandbox: {:success? true :sha sha-or-nil} (nil: no such branch), or the
   failed command's result. The ls-remote asks the executor for git
   credentials, since origin in the capsule holds none; the local
   `remote get-url` checks do not."
  [executor env-id branch exec-opts]
  (or (commands/validate-safe-branch-name branch)
      (let [fetch  (commands/exec! executor env-id "git remote get-url origin" exec-opts)
            pushes (commands/exec! executor env-id "git remote get-url --push --all origin" exec-opts)]
        ;; ls-remote asks origin's fetch URL; it speaks for the push only
        ;; when the push goes there and nowhere else.
        (if-not (and (result/succeeded? fetch) (result/succeeded? pushes)
                     (= [(str/trim (:output fetch))]
                        (remove str/blank? (str/split-lines (:output pushes)))))
          (result/shell-failure (msg/t :push/probe-destination-differs))
          (let [r (commands/exec! executor env-id ["git" "ls-remote" "origin" (str "refs/heads/" branch)]
                                  (assoc exec-opts :git-credentials? true))]
            (if (result/succeeded? r)
              (result/shell-success {:sha (-> (str/trim (:output r "")) (str/split #"\s+") first not-empty)})
              r))))))

(defn ^{:stratum 0} find-pr!
  "Look up the PR `branch` already has, in any state, via gh in the sandbox
   (see pr-lookup). Optional exec-opts supports :env for GH_TOKEN. Returns
   {:success? true :pr pr-or-nil}, or the failed command's result."
  [executor env-id branch exec-opts]
  (or (commands/validate-safe-branch-name branch)
      (let [r (commands/exec! executor env-id (pr-lookup/lookup-argv branch) exec-opts)]
        (if (result/succeeded? r)
          (result/shell-success {:pr (pr-lookup/parse-lookup (:output r))})
          r))))

(defn ^{:stratum 0} stage-files!
  "Stage files in the sandbox container. Returns a shell failure result if
   any path is unsafe (see validate-safe-container-path) — an unvalidated
   path containing a single quote or other shell metacharacter would
   otherwise break out of the single-quoting below, since exec! routes
   string commands through `sh -c`. An empty/nil `file-paths` short-circuits
   to a no-op success without shelling out at all — relying on git's own
   no-args behavior for `git add` (currently a harmless advisory exit 0,
   but unstated and not something to depend on) would also leak that
   advisory message into :output."
  [executor env-id file-paths]
  (cond
    (= file-paths :all)
    (commands/exec! executor env-id "git add .")

    (empty? file-paths)
    (result/shell-success {:output ""})

    :else
    (if-let [invalid (some commands/validate-safe-container-path file-paths)]
      invalid
      (commands/exec! executor env-id
                      (str "git add " (str/join " " (map #(str "'" % "'") file-paths)))))))

(defn ^{:stratum 0} try-checkout-branch
  "Try to checkout a new branch off the current HEAD. When the name already
   exists and the branch contains HEAD, check it out instead: a retried or
   resumed release of the same run, whose branch name is fixed, carries on
   with the branch it made rather than a renamed one (which would get a
   second PR). Fails when the existing branch does not contain HEAD.

   Branches off HEAD (not `origin/<base-branch>`) so the new branch
   inherits whatever commits the task worktree already carries — e.g.
   the per-phase boundary commits the workflow runtime makes after
   plan/implement/verify/review. If we branched off `origin/<base>`
   instead, those commits would be discarded and a downstream
   `git add .` would find nothing to stage. `:base-branch` is still
   tracked for the PR-creation step, which uses it as the merge base."
  [executor env-id branch-name base-branch]
  (if-let [guard (commands/validate-safe-branch-name branch-name)]
    guard
    (let [checkout-r (commands/exec! executor env-id
                                     (str "git checkout -b " branch-name))]
    (if (result/succeeded? checkout-r)
      (result/shell-success {:branch branch-name :base-branch base-branch})
      (let [exists?  (result/succeeded?
                      (commands/exec! executor env-id ["git" "rev-parse" "--verify" "--quiet"
                                                       (str "refs/heads/" branch-name)]))
            holds?   (and exists?
                          (result/succeeded?
                           (commands/exec! executor env-id ["git" "merge-base" "--is-ancestor"
                                                            "HEAD" branch-name])))
            checkout (when holds? (commands/exec! executor env-id ["git" "checkout" branch-name]))]
        (cond
          (not exists?) (result/shell-failure (str "Failed to create branch: " (:error checkout-r))
                                              {:branch nil})
          (not holds?)  (result/shell-failure (msg/t :branch/diverged {:branch branch-name})
                                              {:branch nil})
          (result/succeeded? checkout)
          (result/shell-success {:branch branch-name :base-branch base-branch :reused? true})
          :else (result/shell-failure (str "Failed to check out branch: " (:error checkout))
                                      {:branch nil})))))))

(defn ^{:stratum 0} fetch-branch!
  "Fetch origin/<branch> into the sandbox. `create-branch!` fetches only the
   detected default; when a stacked-PR base is a parent task's branch, this
   pulls it so later `origin/<base>` range diffs / commits-ahead and the PR
   merge-base resolve instead of failing on a missing remote-tracking ref.
   Returns a shell-result."
  [executor env-id branch]
  (or (commands/validate-safe-branch-name branch)
      (commands/exec! executor env-id (str "git fetch origin " branch) {:git-credentials? true})))

(defn ^{:stratum 0} commits-ahead-of-base
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
  (when-not (commands/validate-safe-branch-name base-branch)
    (let [r (commands/exec! executor env-id
                            (str "git rev-list --count --right-only origin/" base-branch "...HEAD"))]
      (when (result/succeeded? r)
      (try
        (Long/parseLong (str/trim (:output r "0")))
        (catch NumberFormatException _ nil))))))

(defn ^{:stratum 0} write-file!
  "Write content to a file inside the sandbox container.
   Uses base64 encoding to safely transfer arbitrary content.
   Returns a shell failure result if `path` is unsafe."
  [executor env-id path content]
  (if-let [invalid (commands/validate-safe-container-path path)]
    invalid
    (let [encoded (.encodeToString (java.util.Base64/getEncoder)
                                   (.getBytes content "UTF-8"))
          cmd (str "mkdir -p \"$(dirname '" path "')\" && "
                   "echo '" encoded "' | base64 -d > '" path "'")]
      (commands/exec! executor env-id cmd))))

(defn ^{:stratum 0} delete-file!
  "Delete a file inside the sandbox container.
   Returns a shell failure result if `path` is unsafe."
  [executor env-id path]
  (if-let [invalid (commands/validate-safe-container-path path)]
    invalid
    (commands/exec! executor env-id (str "rm -f '" path "'"))))

(defn ^{:stratum 0} push-branch!
  "Push branch to origin inside the sandbox container.
   Optional opts supports :env for credential injection.
   If the push fails and opts carry GH_TOKEN, retries once with the token in
   that push's environment (`token-auth-env`), which also covers an SSH
   origin; origin and .git/config are never modified."
  ([executor env-id branch-name] (push-branch! executor env-id branch-name {}))
  ([executor env-id branch-name opts]
   (or (commands/validate-safe-branch-name branch-name)
       (let [result (commands/exec! executor env-id (str "git push -u origin " branch-name) opts)]
         ;; A push stopped mid-way may have reached origin: retrying it over
         ;; HTTPS would hide that unknown outcome behind the retry's.
         (if (or (result/succeeded? result) (:timed-out? result) (:interrupted? result))
       result
       (if-let [token (get-in opts [:env "GH_TOKEN"])]
         (let [url-r (commands/exec! executor env-id "git remote get-url origin" opts)
               remote-url (when (result/succeeded? url-r) (str/trim (get url-r :output "")))]
           (if-let [auth-env (commands/token-auth-env remote-url token)]
             (commands/exec! executor env-id (str "git push -u origin " branch-name)
                             (update opts :env merge auth-env))
             result))
         result))))))

(defn- ^{:stratum 0} look-up-head
  "pr-lookup/head-from-lookups for the branch checked out in the sandbox.
   The git reads take `exec-opts` without its :env, so they run where gh
   will run and without the token."
  [executor env-id exec-opts]
  (let [opts (dissoc exec-opts :env)]
    (pr-lookup/head-from-lookups (commands/exec! executor env-id pr-lookup/branch-argv opts)
                                 (commands/exec! executor env-id pr-lookup/push-url-argv opts))))

(defn ^{:stratum 0} diff-stats-range
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
  (when-not (commands/validate-safe-branch-name base-branch)
    (let [r (commands/exec! executor env-id
                            (str "git diff origin/" base-branch "...HEAD --numstat"))]
      (when (result/succeeded? r)
        (commands/numstat-totals (:output r ""))))))

(defn ^{:stratum 0} count-test-defs-range
  "Count deftest forms added/removed in `origin/<base>...HEAD` (three-dot,
   merge-base diff). Mirrors `count-test-defs` but reads the merge-base
   range so the destructive-diff gate keeps inspecting the commits this
   branch introduced — not deltas caused by `origin/<base>` moving
   forward concurrently."
  [executor env-id base-branch]
  (when-not (commands/validate-safe-branch-name base-branch)
    (let [r (commands/exec! executor env-id
                            (str "git diff origin/" base-branch "...HEAD -U0"))]
      (when (result/succeeded? r)
        (commands/count-deftest (:output r ""))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-pr!
  "Create a pull request using gh CLI inside the sandbox container.
   Optional exec-opts supports :env for GH_TOKEN injection.
   Returns {:success? bool :pr-number int :pr-url string :error string}.

   Two robustness guarantees beyond the bare gh call:
   - A `gh pr create` that exits 0 but prints no PR URL is a FAILURE, not a
     phantom success — release must never ship a PR doc claiming a PR it
     never opened.
   - A branch that already has an open PR (retry/resume) reuses that PR
     rather than opening a duplicate.

   The head is the branch checked out in the sandbox, named as origin's
   (`pr-lookup/head-ref`) and passed as `--head`: gh's own head detection
   can miss a pushed branch when the checkout has a second remote for the
   same repository. A detached HEAD leaves the head to gh. An interrupted
   or timed-out lookup, or an unsafe branch name, returns a failure
   without running gh.

   Title and body are LLM-written and may quote a credential the agent saw;
   secrets in them are replaced with the redaction marker before gh runs."
  ([executor env-id pr-opts] (create-pr! executor env-id pr-opts {}))
  ([executor env-id {:keys [title body base-branch]} exec-opts]
   (let [base (or base-branch "main")]
     (or (commands/validate-safe-branch-name base)
         (let [{:keys [stopped branch head]} (look-up-head executor env-id exec-opts)]
           (or stopped
               (some-> branch commands/validate-safe-branch-name)
               (let [escaped-title (str/replace (redaction/redact title) "'" "'\\''")
                     escaped-body (str/replace (or (redaction/redact body) "") "'" "'\\''")
                     cmd (str "gh pr create"
                              " --title '" escaped-title "'"
                              " --body '" escaped-body "'"
                              " --base " base
                              (when head (str " --head " head)))
                     r (commands/exec! executor env-id cmd exec-opts)]
                 (cond
                   (result/succeeded? r)
                   (if-let [pr (commands/parse-pr-ref (:output r ""))]
                     (result/shell-success pr)
                     (result/shell-failure (msg/t :pr/create-unconfirmed
                                                  {:output (str/trim (:output r ""))})
                                           {:pr-url nil :pr-number nil}))

                   (commands/pr-already-exists? (:error r))
                   (commands/reuse-existing-pr! executor env-id exec-opts (:error r))

                   :else
                   (result/shell-failure (:error r) (merge {:pr-url nil :pr-number nil}
                                                           (select-keys r [:timed-out? :interrupted?])))))))))))

(defn ^{:stratum 1} metrics->result
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

(defn ^{:stratum 1} create-branch!
  "Create a new git branch inside the sandbox container, off the current
   HEAD (so phase-boundary commits already on the task branch carry
   forward). Fetches `origin/<default-branch>` first so the PR-creation
   step has a fresh merge base to compare against. `default-branch` is
   validated with `validate-safe-branch-name` and the fetch is issued as
   an argv vector (never a shell string) — the same guard and pattern
   already used elsewhere in this file — even though it's normally
   derived from git's own symbolic-ref output, not directly attacker
   controlled.

   Returns {:success? bool :branch string :base-branch string :error string}"
  [executor env-id branch-name]
  (let [default-branch (commands/detect-default-branch executor env-id)]
    (if-let [invalid (commands/validate-safe-branch-name default-branch)]
      invalid
      (let [fetch-r (commands/exec! executor env-id ["git" "fetch" "origin" default-branch]
                                    {:git-credentials? true})]
        (if-not (result/succeeded? fetch-r)
          (result/shell-failure (str "Failed to fetch: " (:error fetch-r)) {:branch nil})
          (try-checkout-branch executor env-id branch-name default-branch))))))

;; File batch operations (mirrors files.clj write-and-stage-files!)
(defn ^{:stratum 1} apply-file-operation!
  "Apply a single file operation (create, modify, delete) in the sandbox."
  [executor env-id {:keys [action path content]}]
  (case action
    :create (write-file! executor env-id path content)
    :modify (write-file! executor env-id path content)
    :delete (delete-file! executor env-id path)
    (result/shell-failure (str "Unknown action: " action))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} write-and-stage-files!
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
