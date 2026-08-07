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
(ns ai.miniforge.pr-lifecycle.merge
  "Merge policy enforcement and PR merging.

   Handles the final step of the PR lifecycle - determining when
   a PR is ready to merge and executing the merge."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [ai.miniforge.pr-lifecycle.merge-orchestration :as orchestration]
   [ai.miniforge.pr-lifecycle.merge-readiness :as readiness]
   [ai.miniforge.response.interface :as response]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Anomaly detection (dual shape during W2 convergence)
(defn- ^{:stratum 0} any-anomaly?
  "True when `x` is either a canonical anomaly (`:anomaly/type`) or a
   legacy response anomaly (`:anomaly/category`).

   `normalize-resolution-outcome` inspects the return of the injected
   `resolve-fn` (workflow.merge-resolution/resolve-conflict! today,
   still legacy-shape pre W2 batch 4). The terminal
   `:dag-multi-parent-unresolvable` anomaly arrives as a bare map and
   must be detected by both shape conventions until W5 retires the
   legacy producers. Prefers the canonical predicate; falls back to
   the legacy one. Mirrors the dispatch-key pattern in
   `failure-classifier/classify-failure`."
  [x]
  (or (anomaly/anomaly? x)
      (response/anomaly-map? x)))

;; Merge policies
(def ^{:stratum 0} merge-methods
  "Supported merge methods."
  #{:merge    ; Create merge commit
    :squash   ; Squash and merge
    :rebase})  ; Rebase and merge

(def ^{:stratum 0} default-merge-policy
  "Default merge policy."
  {:method :squash
   :require-ci-green? true
   :require-approvals? true
   :required-approvals 1
   :require-no-unresolved-threads? true
   :require-branch-up-to-date? true
   :delete-branch-after-merge? true
   :auto-rebase-on-stale? true
   ;; Spec §6.4 hook: when GitHub reports the PR as CONFLICTING with
   ;; its base, run the multi-parent merge resolution sub-workflow
   ;; via conflict-resolution/resolve-pr-conflicts!. Engages only if
   ;; the caller also supplied :resolve-fn on context (workflow side
   ;; injects workflow.merge-resolution/resolve-conflict! there).
   :auto-resolve-conflicts? true})

;; GitHub CLI helpers
(defn ^{:stratum 0} run-gh-command
  "Run a gh CLI command and return result."
  [args worktree-path]
  (try
    (let [result (apply process/shell
                        {:dir (str worktree-path)
                         :out :string
                         :err :string
                         :continue true}
                        args)]
      (if (zero? (:exit result))
        (dag/ok {:output (str/trim (:out result ""))})
        (dag/err :gh-command-failed
                 (str/trim (:err result ""))
                 {:exit-code (:exit result)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

(defn ^{:stratum 0} check-unresolved-threads
  "Read GitHub's review-thread state for one pull request."
  [worktree-path pr-number]
  (github/unresolved-review-threads worktree-path pr-number))

;; Conflict-resolution dispatch (Stage 3d, spec §6.4)
(defn- ^{:stratum 0} parse-gh-json
  "Parse `gh --json` output as JSON via Cheshire. Returns the parsed
   map (keywordized keys) on success or nil if the body isn't valid
   JSON. Cheshire matches what github.clj / pr_poller.clj already
   use; the prior regex-based parse here was brittle to formatting
   and escaping changes in gh's output."
  [body]
  (try (json/parse-string body true)
       (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1

;; Merge readiness checks
(defn ^{:stratum 1} check-ci-status
  "Check if CI is green for a PR."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "checks" (str pr-number) "--fail-on-error"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:ci-green? true})
      (dag/ok {:ci-green? false
               :error (:error result)}))))

(defn ^{:stratum 1} check-review-status
  "Check if PR has required approvals."
  [worktree-path pr-number _required-approvals]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json" "reviewDecision"]
                worktree-path)]
    (if (dag/ok? result)
      (let [output (:output (:data result))
            approved? (str/includes? (str/upper-case output) "APPROVED")]
        (dag/ok {:approved? approved?
                 :raw output}))
      result)))

(defn ^{:stratum 1} check-branch-status
  "Check if PR branch is up-to-date with base."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json" "mergeable,mergeStateStatus"]
                worktree-path)]
    (if (dag/ok? result)
      (let [output (:output (:data result))
            clean? (and (str/includes? output "MERGEABLE")
                        (or (str/includes? output "CLEAN")
                            (str/includes? output "HAS_HOOKS")))]
        (dag/ok {:up-to-date? clean?
                 :raw output}))
      result)))

;; Merge execution
(defn ^{:stratum 1} merge-pr!
  "Merge a PR using gh CLI.

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy (or use defaults)

   Returns result with merge information."
  [worktree-path pr-number & {:keys [policy]
                              :or {policy default-merge-policy}}]
  (let [method-flag (case (:method policy :squash)
                      :squash "--squash"
                      :rebase "--rebase"
                      :merge "--merge")
        delete-flag (when (:delete-branch-after-merge? policy)
                      "--delete-branch")
        args (cond-> ["gh" "pr" "merge" (str pr-number) method-flag "--auto"]
               delete-flag (conj delete-flag))
        result (run-gh-command args worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:merged? true
               :method (:method policy)
               :output (:output (:data result))})
      result)))

(defn ^{:stratum 1} enable-auto-merge!
  "Enable auto-merge for a PR (merges when all checks pass).

   Arguments:
   - worktree-path: Path to git worktree
   - pr-number: PR number
   - policy: Merge policy

   Returns result."
  [worktree-path pr-number & {:keys [policy]
                              :or {policy default-merge-policy}}]
  (let [method-flag (case (:method policy :squash)
                      :squash "--squash"
                      :rebase "--rebase"
                      :merge "--merge")
        result (run-gh-command
                ["gh" "pr" "merge" (str pr-number) method-flag "--auto"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:auto-merge-enabled? true})
      result)))

(defn ^{:stratum 1} disable-auto-merge!
  "Disable auto-merge for a PR."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "merge" (str pr-number) "--disable-auto"]
                worktree-path)]
    (if (dag/ok? result)
      (dag/ok {:auto-merge-disabled? true})
      result)))

(defn ^{:stratum 1} rebase-pr!
  "Rebase a PR onto the latest base branch.

   Arguments:
   - worktree-path: Path to git worktree
   - branch: PR branch name

   Returns result with new HEAD SHA."
  [worktree-path branch]
  (let [;; Fetch latest base
        fetch-result (run-gh-command
                      ["git" "fetch" "origin" "main"]
                      worktree-path)]
    (if (dag/err? fetch-result)
      fetch-result
      (let [;; Attempt rebase
            rebase-result (run-gh-command
                           ["git" "rebase" "origin/main"]
                           worktree-path)]
        (if (dag/ok? rebase-result)
          ;; Push rebased branch
          (let [push-result (run-gh-command
                             ["git" "push" "--force-with-lease" "origin" branch]
                             worktree-path)]
            (if (dag/ok? push-result)
              (let [sha-result (run-gh-command
                                ["git" "rev-parse" "HEAD"]
                                worktree-path)]
                (dag/ok {:rebased? true
                         :new-sha (when (dag/ok? sha-result)
                                    (:output (:data sha-result)))}))
              (dag/err :push-failed (:error push-result))))
          (dag/err :rebase-failed (:error rebase-result)))))))

(defn ^{:stratum 1} fetch-pr-branch
  "Read the PR head branch from GitHub."
  [worktree-path pr-number]
  (let [result (run-gh-command
                ["gh" "pr" "view" (str pr-number) "--json" "headRefName"] worktree-path)]
    (if (dag/err? result)
      result
      (if-let [branch (:headRefName (parse-gh-json (:output (:data result))))]
        (dag/ok {:branch branch})
        (dag/err :branch-not-found "Could not determine PR branch")))))

(defn- ^{:stratum 1} pr-info-from-gh
  "Fetch the fields conflict-resolution/resolve-pr-conflicts! needs
   from `gh pr view`: PR number, branch (headRefName), base
   (baseRefName), head SHA (headRefOid), base SHA (baseRefOid).
   Returns dag/ok with the `{:pr/* ...}` shape on success or the
   underlying gh failure as dag/err."
  [worktree-path pr-number]
  (let [r (run-gh-command
           ["gh" "pr" "view" (str pr-number) "--json"
            "number,headRefName,baseRefName,headRefOid,baseRefOid"]
           worktree-path)]
    (if (dag/err? r)
      r
      (let [out (:output (:data r))
            parsed (parse-gh-json out)
            head-branch (:headRefName parsed)
            base-branch (:baseRefName parsed)
            head-sha (:headRefOid parsed)
            base-sha (:baseRefOid parsed)]
        (cond
          (nil? parsed)
          (dag/err :pr-info-invalid-json
                   "Could not parse gh JSON output"
                   {:gh-output out})

          (and head-branch base-branch head-sha base-sha)
          (dag/ok {:pr/id        pr-number
                   :pr/branch    head-branch
                   :pr/base      base-branch
                   :pr/head-sha  head-sha
                   :pr/base-sha  base-sha})

          :else
          (dag/err :pr-info-incomplete
                   "gh JSON missing one or more PR head/base fields"
                   {:gh-output out
                    :missing (cond-> []
                               (not head-branch) (conj :headRefName)
                               (not base-branch) (conj :baseRefName)
                               (not head-sha)    (conj :headRefOid)
                               (not base-sha)    (conj :baseRefOid))}))))))

(defn- ^{:stratum 1} normalize-resolution-outcome
  "conflict-resolution/resolve-pr-conflicts! can return:
   - dag/ok on success;
   - dag/err on infrastructure failure; or
   - the bare `:dag-multi-parent-unresolvable` anomaly map (a
     terminal failure of the resolution sub-workflow itself).

   Bare anomaly maps break attempt-merge's implicit
   dag/ok-or-dag/err contract — callers that branch on
   dag/err?/dag/ok? would treat the anomaly as a generic blocked
   merge and could retry indefinitely. Wrap the anomaly into a
   dag/err with the distinct :code :conflict-unresolvable so the
   controller / train monitor can transition the PR to :failed
   instead of looping. The original anomaly is preserved under
   :data :anomaly for diagnostic surfacing."
  [outcome]
  (if (and (any-anomaly? outcome)
           (not (dag/ok? outcome))
           (not (dag/err? outcome)))
    (dag/err :conflict-unresolvable
             (or (:anomaly/message outcome)
                 "Conflict resolution sub-workflow declared the merge unresolvable")
             {:anomaly outcome})
    outcome))

;; Merge orchestration
(defn ^{:stratum 1} fetch-pr-labels!
  "Fetch the label names attached to a PR via `gh pr view --json labels`.

   Returns a `#{}` of label-name strings (GitHub-native, literal,
   no case-folding). Returns `#{}` on any failure — label fetch is
   best-effort and never blocks the merge-event publish path. The
   first downstream consumer is the M2 pr-label-actions watcher."
  [worktree-path pr-number]
  (try
    (let [result (run-gh-command
                  ["gh" "pr" "view" (str pr-number) "--json" "labels"]
                  worktree-path)]
      (if (dag/ok? result)
        (let [body (json/parse-string (:output (:data result)) true)]
          (->> (:labels body)
               (keep :name)
               set))
        #{}))
    (catch Exception _ #{})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} evaluate-merge-readiness
  "Evaluate every enabled merge policy check."
  [worktree-path pr-number policy]
  (readiness/evaluate
   {:check-ci check-ci-status :check-review check-review-status
    :check-branch check-branch-status :check-threads check-unresolved-threads}
   worktree-path pr-number policy))

(defn ^{:stratum 2} attempt-merge
  "Attempt a governed merge or the configured branch repair."
  [worktree-path pr-number policy context]
  (orchestration/attempt-merge
   {:check-ci check-ci-status :check-review check-review-status
    :check-branch check-branch-status :check-threads check-unresolved-threads
    :run-gh run-gh-command :merge-pr merge-pr!
    :fetch-labels fetch-pr-labels! :fetch-branch fetch-pr-branch
    :rebase-pr rebase-pr! :pr-info pr-info-from-gh :normalize-resolution normalize-resolution-outcome}
   worktree-path pr-number policy context))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Default merge policy
  default-merge-policy

  ;; Check merge readiness
  (evaluate-merge-readiness "/path/to/repo" 123 default-merge-policy)

  ;; Merge a PR
  (merge-pr! "/path/to/repo" 123 :policy {:method :squash
                                          :delete-branch-after-merge? true})

  ;; Enable auto-merge
  (enable-auto-merge! "/path/to/repo" 123)

  ;; Rebase a PR
  (rebase-pr! "/path/to/repo" "feat/my-feature")

  :leave-this-here)
