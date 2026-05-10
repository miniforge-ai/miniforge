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

(ns ai.miniforge.pr-lifecycle.review-scheduler
  "Auto-trigger plumbing for the N13 Standards Reviewer (#818).

   Provides the small surface needed by the `pr review-monitor` CLI
   command to decide which PRs need a fresh standards review and to
   run that review against an ephemeral worktree pinned to the PR's
   head SHA.

   Layer 0: GH-side existing-review detection (uses `github/review-marker`)
   Layer 1: per-PR ephemeral worktree management
   Layer 2: review-needs predicate"
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn- run-gh
  [args worktree-path]
  (try
    (let [r (apply process/shell
                   {:dir (str worktree-path)
                    :out :string :err :string :continue true}
                   args)]
      (if (zero? (:exit r))
        (dag/ok {:output (str/trim (:out r ""))})
        (dag/err :gh-command-failed (str/trim (:err r ""))
                 {:exit-code (:exit r) :command (vec args)})))
    (catch Exception e
      (dag/err :gh-exception (.getMessage e)))))

(defn existing-review-shas
  "Return a `(dag/ok #{sha …})` of head SHAs for which we've already
   posted a standards review on `pr-number`. A review counts if its
   body contains `review-marker`.

   Uses `gh api repos/{owner}/{repo}/pulls/<n>/reviews --paginate`
   so the calling worktree's git remote provides the {owner}/{repo}
   resolution."
  [worktree-path pr-number]
  (let [endpoint (str "repos/{owner}/{repo}/pulls/" pr-number "/reviews")
        result   (run-gh ["gh" "api" endpoint "--paginate"] worktree-path)]
    (if (dag/ok? result)
      (try
        (let [raw    (:output (:data result))
              ;; --paginate may emit multiple JSON arrays back-to-back;
              ;; concat by replacing "][" with ",".
              joined (-> raw (str/replace #"\]\s*\[" ","))
              items  (if (str/blank? joined) []
                       (json/parse-string joined true))
              shas   (into #{}
                           (comp
                            (filter (fn [r]
                                      (some-> (:body r)
                                              (str/includes? github/review-marker))))
                            (keep :commit_id))
                           items)]
          (dag/ok shas))
        (catch Exception e
          (dag/err :json-parse-error (.getMessage e))))
      result)))

;------------------------------------------------------------------------------ Layer 1
;; Per-PR ephemeral worktree

(defn- git-sh
  "Run a git subcommand from `repo` and return DAG result. Args are
   the trailing args after `git -C <repo>`."
  [repo & args]
  (try
    (let [r (apply process/shell
                   {:dir (str repo)
                    :out :string :err :string :continue true}
                   "git" "-C" (str repo) args)]
      (if (zero? (:exit r))
        (dag/ok {:output (str/trim (:out r ""))})
        (dag/err :git-command-failed (str/trim (:err r ""))
                 {:exit-code (:exit r) :args (vec args)})))
    (catch Exception e
      (dag/err :git-exception (.getMessage e)))))

(defn fetch-pr-head!
  "Fetch the PR head into a stable local ref so an ephemeral worktree
   can check it out without racing FETCH_HEAD. Ref shape is
   `refs/miniforge-review/pr-<n>`. Returns DAG result."
  [base-repo pr-number]
  (git-sh base-repo "fetch" "origin"
          (str "+refs/pull/" pr-number "/head:refs/miniforge-review/pr-" pr-number)))

(defn add-pr-worktree!
  "Create a detached-HEAD worktree at `sha` rooted under `tmp-dir`.
   Returns DAG result with `:worktree-path`."
  [base-repo sha tmp-dir]
  (let [r (git-sh base-repo "worktree" "add" "--detach" (str tmp-dir) sha)]
    (if (dag/ok? r)
      (dag/ok {:worktree-path (str tmp-dir)})
      r)))

(defn remove-pr-worktree!
  "Best-effort cleanup of a previously-added ephemeral worktree.
   Always attempts both `git worktree remove --force` AND filesystem
   delete so a half-created worktree doesn't leak."
  [base-repo worktree-path]
  (let [_ (git-sh base-repo "worktree" "remove" "--force" (str worktree-path))]
    (try (fs/delete-tree worktree-path) (catch Throwable _ nil))
    (dag/ok {:removed worktree-path})))

(defn with-pr-worktree
  "Bracketing helper: fetch PR head, create an ephemeral detached
   worktree at that SHA under `/tmp/mf-review-<sha>`, invoke `f`
   with the worktree path + the resolved SHA, then clean up.

   `f` is called with `{:worktree-path string :sha string}` and may
   return any value, which is wrapped on success.

   Cleanup invariants:
   - Pre-flight: any pre-existing leftover at `tmp` is removed
     before fetch/add — handles the previous-crashed-run case.
   - On fetch failure: nothing to clean up (no worktree created).
   - On add failure: best-effort `remove-pr-worktree!` on `tmp`
     because `git worktree add` can leave a partial directory and/or
     a registered worktree entry even when it exits non-zero.
   - On `f` success or `f` exception: `try/finally` always runs
     `remove-pr-worktree!`.

   Returns:
   - On any setup failure: the failing DAG result.
   - On `f` success: `(dag/ok {:result <f-return> :sha <sha>})`.
   - On `f` exception: re-throws after cleanup."
  [base-repo pr-number sha f]
  (let [tmp (fs/path (fs/temp-dir) (str "mf-review-" sha))]
    ;; Pre-clean any leftover from a prior crashed run with the same SHA.
    (when (fs/exists? tmp) (remove-pr-worktree! base-repo tmp))
    (let [fetch-r (fetch-pr-head! base-repo pr-number)]
      (if-not (dag/ok? fetch-r)
        fetch-r
        (let [add-r (add-pr-worktree! base-repo sha tmp)]
          (if-not (dag/ok? add-r)
            ;; Add failed — best-effort sweep of any partial leftover
            ;; before bubbling the typed error. `remove-pr-worktree!`
            ;; tolerates a missing worktree (git noop) + missing dir
            ;; (catch in delete-tree).
            (do (remove-pr-worktree! base-repo tmp)
                add-r)
            (try
              (let [r (f {:worktree-path (str tmp) :sha sha})]
                (dag/ok {:result r :sha sha}))
              (finally
                (remove-pr-worktree! base-repo tmp)))))))))

;------------------------------------------------------------------------------ Layer 2
;; Review-needs predicate

(defn pr-needs-review?
  "Pure predicate. Returns true when `pr` (a `:pr/sha`-bearing map
   from `pr-poller/poll-open-prs`) has no entry in `existing-shas`
   (the set returned by `existing-review-shas`)."
  [pr existing-shas]
  (let [sha (:pr/sha pr)]
    (and (string? sha)
         (not (contains? (or existing-shas #{}) sha)))))

(defn partition-needs-review
  "Split a vector of `pr-poller`-shaped PR maps into
   `{:needs-review [...] :already-reviewed [...]}` against the
   `existing-shas-by-pr` map (keyed by `:pr/number`)."
  [prs existing-shas-by-pr]
  (reduce
   (fn [acc pr]
     (let [shas (get existing-shas-by-pr (:pr/number pr) #{})]
       (if (pr-needs-review? pr shas)
         (update acc :needs-review conj pr)
         (update acc :already-reviewed conj pr))))
   {:needs-review [] :already-reviewed []}
   prs))
