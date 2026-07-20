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

(ns ai.miniforge.dag-executor.protocols.impl.worktree-test
  "Tests for the worktree executor: concurrent-add lock and fallback strategy."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.result :as result]))

;; ============================================================================
;; create-worktree: lock and fallback strategy
;; ============================================================================

(deftest resolve-branch-sha-rejects-unusable-output-test
  (testing "successful rev-parse with absent, blank, or malformed stdout remains unresolved"
    (doseq [output [nil false :not-a-sha 42 " \n"]]
      (with-redefs [worktree/run-git (fn [& _] {:exit 0 :out output :err ""})]
        (is (nil? (#'worktree/resolve-branch-sha "/tmp/repo" "main"))
            (str "ignored " (pr-str output)))))))

(deftest create-worktree-succeeds-on-first-attempt-test
  (testing "returns ok with worktree-path when git worktree add -b succeeds immediately"
    (with-redefs [worktree/run-git     (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/run-shell   (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/ensure-directory (fn [_] nil)
                  worktree/check-legacy-tmp-worktrees! (fn [] nil)]
      (let [r (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")]
        (is (result/ok? r))
        (is (= "/tmp/base/task-abc" (:worktree-path (:data r))))))))

(deftest create-worktree-resolves-branch-to-sha-before-add-test
  (testing "the parent branch is resolved to its sha before `worktree add -b` so the
            command works even when that branch is already checked out elsewhere"
    (let [add-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["rev-parse" "main"] tail)
                          {:exit 0 :out "abc1234deadbeef\n" :err ""}

                          ;; Tight match on `worktree add` specifically —
                          ;; `(some #{"worktree" "add"} args)` would also
                          ;; trigger on `worktree prune` from the cleanup
                          ;; path and read the wrong call's args.
                          (= ["worktree" "add"] (take 2 tail))
                          (do (reset! add-args (vec args))
                              {:exit 0 :out "" :err ""})

                          :else {:exit 0 :out "" :err ""})))
                    worktree/run-shell        (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)
                    worktree/check-legacy-tmp-worktrees! (fn [] nil)]
        (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")
        ;; Final positional arg of `worktree add` should be the resolved sha,
        ;; not the literal branch name. Without this, `git worktree add -b
        ;; new path main` fails when main is already checked out in another
        ;; worktree (the common case for `bb dogfood` from a feature branch).
        (is (= "abc1234deadbeef" (last @add-args))
            "create-worktree must pass the resolved sha to `worktree add`, not the branch name")))))

(deftest create-worktree-retries-after-cleanup-on-first-failure-test
  (testing "cleans up stale branch/dir and retries -b when first add fails"
    (let [add-b-calls (atom 0)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (if (some #{"add"} args)
                        (if (zero? (swap! add-b-calls inc))
                          {:exit 0 :out "" :err ""}
                          ;; First add-b fails; second succeeds
                          (if (= 1 @add-b-calls)
                            {:exit 1 :out "" :err "fatal: branch already exists"}
                            {:exit 0 :out "" :err ""}))
                        {:exit 0 :out "" :err ""}))
                    worktree/run-shell        (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)
                    worktree/check-legacy-tmp-worktrees! (fn [] nil)]
        (let [r (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")]
          (is (result/ok? r))
          (is (= "/tmp/base/task-abc" (:worktree-path (:data r)))))))))

(deftest create-worktree-falls-back-to-detach-when-both-branch-attempts-fail-test
  (testing "falls back to --detach when both -b attempts fail"
    (let [git-calls (atom [])]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (swap! git-calls conj (vec args))
                      (cond
                        ;; Both worktree add -b attempts fail
                        (and (some #{"add"} args) (some #{"-b"} args))
                        {:exit 1 :out "" :err "fatal: branch already exists"}
                        ;; worktree add --detach succeeds
                        (and (some #{"add"} args) (some #{"--detach"} args))
                        {:exit 0 :out "" :err ""}
                        ;; All other git calls (prune, branch -D) succeed
                        :else
                        {:exit 0 :out "" :err ""}))
                    worktree/run-shell        (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)
                    worktree/check-legacy-tmp-worktrees! (fn [] nil)]
        (let [r (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")]
          (is (result/ok? r))
          (is (= "/tmp/base/task-abc" (:worktree-path (:data r))))
          ;; Verify --detach was attempted
          (is (some #(some #{"--detach"} %) @git-calls)))))))

(deftest create-worktree-returns-error-when-all-attempts-fail-test
  (testing "returns err when -b retry and --detach all fail"
    (with-redefs [worktree/run-git
                  (fn [& args]
                    (if (some #{"add"} args)
                      {:exit 1 :out "" :err "fatal: cannot create worktree"}
                      {:exit 0 :out "" :err ""}))
                  worktree/run-shell        (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/ensure-directory (fn [_] nil)
                  worktree/check-legacy-tmp-worktrees! (fn [] nil)]
      (let [r (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")]
        (is (not (result/ok? r)))
        (is (= :worktree-create-failed (get-in r [:error :code])))))))

(deftest worktree-lock-is-present-test
  (testing "worktree-lock is an Object used to serialize concurrent git add calls"
    (is (some? @#'worktree/worktree-lock))
    (is (instance? Object @#'worktree/worktree-lock))))

;; ============================================================================
;; persist-workspace! / restore-workspace! (no-op for worktree)
;; ============================================================================

(deftest archive-bundle-recovers-from-detached-head-test
  (testing "detached-HEAD scratch gets a real branch before bundling so the
            bundle records refs/heads/<name>, not bare HEAD"
    ;; Regression: the dogfood of PR #732 caught this — when create-worktree
    ;; falls back to `--detach`, archive-bundle! used to record the literal
    ;; string \"HEAD\" as the branch name. The bundle then advertised only
    ;; HEAD (legacy `base..HEAD` shape), which the harvest CLI correctly
    ;; refused to fetch. ensure-named-branch! creates `task-<id>` at HEAD
    ;; before any of the rest of the pipeline runs.
    (let [checkout-args (atom nil)
          bundle-args (atom nil)
          status-calls (atom 0)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (let [tail (drop 2 args)]
                        (cond
                          ;; Scratch is detached — abbrev-ref returns the literal "HEAD"
                          (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                          {:exit 0 :out "HEAD\n" :err ""}

                          ;; ensure-named-branch! runs `checkout -B <name>`
                          ;; (-B, not -b — idempotent across reruns, doesn't
                          ;; fail if the branch name already exists from a
                          ;; previous run with the same task-id)
                          (and (= "checkout" (first tail))
                               (= "-B" (second tail)))
                          (do (reset! checkout-args (vec tail))
                              {:exit 0 :out "" :err ""})

                          (= ["status" "--porcelain"] tail)
                          (do (swap! status-calls inc)
                              (if (= 1 @status-calls)
                                {:exit 0 :out " M file.clj\n" :err ""}
                                {:exit 0 :out "" :err ""}))

                          (= "rev-list" (first tail))
                          {:exit 0 :out "1\n" :err ""}

                          (= "rev-parse" (first tail))
                          {:exit 0 :out "abc123\n" :err ""}

                          (= "bundle" (first tail))
                          (do (reset! bundle-args (vec tail))
                              {:exit 0 :out "" :err ""})

                          :else {:exit 0 :out "" :err ""})))
                    worktree/ensure-archive-dir (fn [d] d)]
        (let [r (worktree/archive-bundle! "/tmp/scratch"
                                          {:archive-dir "/tmp/test-archive"
                                           :task-id     "abc"
                                           :base-ref    "main"})]
          (is (result/ok? r))
          ;; Real branch name was created via `checkout -B` (force-reset)
          ;; rather than `-b` (create-only) so reruns with the same task-id
          ;; don't fail on a leftover ref from an earlier run.
          (is (= ["checkout" "-B" "task-abc"] @checkout-args))
          ;; Bundle uses the new branch name, not "HEAD"
          (is (= ["bundle" "create" "/tmp/test-archive/abc.bundle"
                  "task-abc" "--not" "main"]
                 @bundle-args))
          (is (= "task-abc" (get-in r [:data :branch]))
              "result reports the materialized branch name so callers know
               which ref name to fetch"))))))

(deftest archive-bundle-recovery-branch-no-double-prefix-test
  (testing "recovery branch name is not double-prefixed when task-id already
            starts with `task-`"
    ;; persist-workspace! defaults :task-id to environment-id, which the
    ;; executor names `task-<8-char-uuid>`. Naively prepending `task-` here
    ;; would yield `task-task-<id>` — verbose and hard to grep for in logs.
    (let [checkout-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                          {:exit 0 :out "HEAD\n" :err ""}

                          (and (= "checkout" (first tail))
                               (= "-B" (second tail)))
                          (do (reset! checkout-args (vec tail))
                              {:exit 0 :out "" :err ""})

                          :else {:exit 0 :out "" :err ""})))
                    worktree/ensure-archive-dir (fn [d] d)]
        (worktree/archive-bundle! "/tmp/scratch"
                                  {:archive-dir "/tmp/test-archive"
                                   :task-id     "task-already-prefixed"
                                   :base-ref    "main"})
        (is (= ["checkout" "-B" "task-already-prefixed"] @checkout-args)
            "task-id already starts with `task-` so the prefix is not added again")))))

(deftest persist-workspace-clean-worktree-no-changes-test
  (testing "persist-workspace! reports no-changes when the scratch worktree is clean"
    (let [executor (worktree/create-worktree-executor {})
          calls (atom [])]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (cond
                        ;; rev-parse --abbrev-ref HEAD → some branch name
                        (= ["rev-parse" "--abbrev-ref" "HEAD"] (drop 2 args))
                        {:exit 0 :out "task-1\n" :err ""}
                        ;; status --porcelain → empty (clean)
                        (= ["status" "--porcelain"] (drop 2 args))
                        {:exit 0 :out "" :err ""}
                        ;; rev-list --count → 0 (no commits ahead of base)
                        (= "rev-list" (nth args 2))
                        {:exit 0 :out "0\n" :err ""}
                        :else {:exit 0 :out "" :err ""}))]
        (let [r (proto/persist-workspace! executor "env-1"
                                          {:branch "task-1" :base-branch "main"})]
          (is (result/ok? r))
          (is (false? (get-in r [:data :persisted?])))
          (is (true? (get-in r [:data :no-changes?]))))))))

(deftest persist-workspace-dirty-bundles-test
  (testing "persist-workspace! commits dirty changes and writes a bundle"
    (let [executor (worktree/create-worktree-executor {})
          status-calls (atom 0)
          bundle-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                          {:exit 0 :out "task-1\n" :err ""}

                          (= ["status" "--porcelain"] tail)
                          (do (swap! status-calls inc)
                              ;; Dirty before commit, clean after
                              (if (= 1 @status-calls)
                                {:exit 0 :out " M src/foo.clj\n" :err ""}
                                {:exit 0 :out "" :err ""}))

                          (= "rev-list" (first tail))
                          {:exit 0 :out "1\n" :err ""}

                          (= "rev-parse" (first tail))
                          {:exit 0 :out "abc123\n" :err ""}

                          (= "bundle" (first tail))
                          (do (reset! bundle-args (vec tail))
                              {:exit 0 :out "" :err ""})

                          :else {:exit 0 :out "" :err ""})))
                    worktree/ensure-archive-dir
                    (fn [d] d)]
        (let [r (proto/persist-workspace!
                 executor "env-1"
                 {:branch "task-1"
                  :base-branch "main"
                  :archive-dir "/tmp/test-archive"
                  :task-id "env-1"})]
          (is (result/ok? r))
          (is (true? (get-in r [:data :persisted?])))
          (is (= "abc123" (get-in r [:data :commit-sha])))
          (is (= "/tmp/test-archive/env-1.bundle"
                 (get-in r [:data :bundle-path])))
          (is (= ["bundle" "create" "/tmp/test-archive/env-1.bundle"
                  "task-1" "--not" "main"]
                 @bundle-args)))))))

(deftest restore-workspace-fetches-from-bundle-test
  (testing "restore-workspace! fetches the branch back into the host repo"
    (let [executor (worktree/create-worktree-executor {})
          fetch-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (when (= "fetch" (nth args 2 nil))
                        (reset! fetch-args (vec args)))
                      {:exit 0 :out "" :err ""})]
        (let [r (proto/restore-workspace!
                 executor "env-1"
                 {:host-repo-path "/host/repo"
                  :bundle-path    "/tmp/checkpoints/env-1.bundle"
                  :branch         "task-1"})]
          (is (result/ok? r))
          (is (true? (get-in r [:data :restored?])))
          (is (= "task-1" (get-in r [:data :branch])))
          (is (= ["-C" "/host/repo" "fetch"
                  "/tmp/checkpoints/env-1.bundle" "task-1:task-1"]
                 @fetch-args)))))))

(deftest restore-workspace-accepts-already-checked-out-branch-test
  (testing "restore succeeds when the checked-out branch already contains the bundled commit"
    (let [executor (worktree/create-worktree-executor {})
          calls (atom [])]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["fetch" "/tmp/checkpoints/env-1.bundle" "task-1:task-1"] tail)
                          {:exit 128
                           :out ""
                           :err "fatal: refusing to fetch into branch 'refs/heads/task-1' checked out"}

                          (= ["fetch" "/tmp/checkpoints/env-1.bundle"
                              "task-1:refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "" :err ""}

                          (= ["rev-parse" "task-1"] tail)
                          {:exit 0 :out "abc123\n" :err ""}

                          (= ["rev-parse" "refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "abc123\n" :err ""}

                          (= ["merge-base" "--is-ancestor" "abc123" "abc123"] tail)
                          {:exit 0 :out "" :err ""}

                          (= ["update-ref" "-d" "refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "" :err ""}

                          :else {:exit 1 :out "" :err (str "unexpected git call " tail)})))]
        (let [r (proto/restore-workspace!
                 executor "env-1"
                 {:host-repo-path "/host/repo"
                  :bundle-path    "/tmp/checkpoints/env-1.bundle"
                  :branch         "task-1"})]
          (is (result/ok? r))
          (is (true? (get-in r [:data :already-checked-out?])))
          (is (some #(= ["-C" "/host/repo" "update-ref" "-d"
                         "refs/miniforge/resume/task-1"] %)
                    @calls)))))))

(deftest restore-workspace-surfaces-resume-fetch-failure-test
  (testing "checked-out branch fallback reports resume-ref fetch failures and cleans up"
    (let [executor (worktree/create-worktree-executor {})
          calls (atom [])]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["fetch" "/tmp/checkpoints/env-1.bundle" "task-1:task-1"] tail)
                          {:exit 128
                           :out ""
                           :err "fatal: refusing to fetch into branch 'refs/heads/task-1' checked out"}

                          (= ["fetch" "/tmp/checkpoints/env-1.bundle"
                              "task-1:refs/miniforge/resume/task-1"] tail)
                          {:exit 128 :out "" :err "fatal: bad bundle"}

                          (= ["update-ref" "-d" "refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "" :err ""}

                          :else {:exit 1 :out "" :err (str "unexpected git call " tail)})))]
        (let [r (proto/restore-workspace!
                 executor "env-1"
                 {:host-repo-path "/host/repo"
                  :bundle-path    "/tmp/checkpoints/env-1.bundle"
                  :branch         "task-1"})]
          (is (result/err? r))
          (is (= "fatal: bad bundle" (get-in r [:error :message])))
          (is (some #(= ["-C" "/host/repo" "update-ref" "-d"
                         "refs/miniforge/resume/task-1"] %)
                    @calls)))))))

(deftest restore-workspace-errors-when-checked-out-branch-lacks-bundle-tip-test
  (testing "checked-out branch fallback rejects stale branches and cleans up"
    (let [executor (worktree/create-worktree-executor {})
          calls (atom [])]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (swap! calls conj (vec args))
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["fetch" "/tmp/checkpoints/env-1.bundle" "task-1:task-1"] tail)
                          {:exit 128
                           :out ""
                           :err "fatal: refusing to fetch into branch 'refs/heads/task-1' checked out"}

                          (= ["fetch" "/tmp/checkpoints/env-1.bundle"
                              "task-1:refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "" :err ""}

                          (= ["rev-parse" "task-1"] tail)
                          {:exit 0 :out "branch-sha\n" :err ""}

                          (= ["rev-parse" "refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "bundle-sha\n" :err ""}

                          (= ["merge-base" "--is-ancestor" "bundle-sha" "branch-sha"] tail)
                          {:exit 1 :out "" :err ""}

                          (= ["update-ref" "-d" "refs/miniforge/resume/task-1"] tail)
                          {:exit 0 :out "" :err ""}

                          :else {:exit 1 :out "" :err (str "unexpected git call " tail)})))]
        (let [r (proto/restore-workspace!
                 executor "env-1"
                 {:host-repo-path "/host/repo"
                  :bundle-path    "/tmp/checkpoints/env-1.bundle"
                  :branch         "task-1"})]
          (is (result/err? r))
          (is (= "Checked-out branch does not contain bundled checkpoint commit"
                 (get-in r [:error :message])))
          (is (some #(= ["-C" "/host/repo" "update-ref" "-d"
                         "refs/miniforge/resume/task-1"] %)
                    @calls)))))))

(deftest persist-workspace-base-ref-never-falls-back-to-task-branch-test
  (testing "persist-workspace! does NOT use the task branch as base-ref fallback"
    ;; Regression: the original fallback chain was
    ;;   (or :base-branch :base-ref :branch \"main\")
    ;; which made commits-ahead compute task-branch..HEAD = 0, silently
    ;; skipping bundle creation when callers passed :branch but not
    ;; :base-branch. The current chain stops at default-base-ref ('main').
    (let [executor (worktree/create-worktree-executor {})
          rev-list-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      (let [tail (drop 2 args)]
                        (cond
                          (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                          {:exit 0 :out "task-1\n" :err ""}

                          (= ["status" "--porcelain"] tail)
                          {:exit 0 :out " M src/foo.clj\n" :err ""}

                          (= "rev-list" (first tail))
                          (do (reset! rev-list-args (vec tail))
                              {:exit 0 :out "1\n" :err ""})

                          (= "rev-parse" (first tail))
                          {:exit 0 :out "abc123\n" :err ""}

                          (= "bundle" (first tail))
                          {:exit 0 :out "" :err ""}

                          :else {:exit 0 :out "" :err ""})))
                    worktree/ensure-archive-dir (fn [d] d)]
        (proto/persist-workspace! executor "env-1"
                                  ;; Caller passes :branch but no :base-branch
                                  ;; — the executor must NOT use task-1 as base.
                                  {:branch "task-1"
                                   :archive-dir "/tmp/test-archive"
                                   :task-id "env-1"})
        (is (some? @rev-list-args))
        (is (some #(re-matches #"main\.\.HEAD" %) @rev-list-args)
            "rev-list must compare against the default base ('main'),
             not against the task branch")))))

(deftest archive-bundle-surfaces-status-failure-test
  (testing "git status failure returns result/err — not a silent no-changes"
    (with-redefs [worktree/run-git
                  (fn [& args]
                    (let [tail (drop 2 args)]
                      (cond
                        (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                        {:exit 0 :out "task-1\n" :err ""}

                        (= ["status" "--porcelain"] tail)
                        {:exit 128 :out "" :err "fatal: not a git repository"}

                        :else {:exit 0 :out "" :err ""})))]
      (let [r (worktree/archive-bundle! "/tmp/scratch"
                                        {:archive-dir "/tmp/test-archive"
                                         :task-id     "env-1"
                                         :base-ref    "main"})]
        (is (result/err? r))
        (is (= :archive-status-failed (get-in r [:error :code]))
            "git status failure must bubble up; the previous code silently
             treated non-zero exit as 'no changes' and dropped the persist")))))

(deftest archive-bundle-surfaces-rev-list-failure-test
  (testing "git rev-list failure returns result/err — not a silent zero"
    (with-redefs [worktree/run-git
                  (fn [& args]
                    (let [tail (drop 2 args)]
                      (cond
                        (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                        {:exit 0 :out "task-1\n" :err ""}

                        (= ["status" "--porcelain"] tail)
                        {:exit 0 :out "" :err ""}

                        (= "rev-list" (first tail))
                        {:exit 128 :out "" :err "fatal: bad revision"}

                        :else {:exit 0 :out "" :err ""})))]
      (let [r (worktree/archive-bundle! "/tmp/scratch"
                                        {:archive-dir "/tmp/test-archive"
                                         :task-id     "env-1"
                                         :base-ref    "main"})]
        (is (result/err? r))
        (is (= :archive-rev-list-failed (get-in r [:error :code]))
            "rev-list failure must bubble up rather than be coerced to 0")))))

(deftest archive-bundle-fails-cleanly-on-bundle-error-test
  (testing "archive-bundle! returns an err result when git bundle fails"
    (with-redefs [worktree/run-git
                  (fn [& args]
                    (let [tail (drop 2 args)]
                      (cond
                        (= ["rev-parse" "--abbrev-ref" "HEAD"] tail)
                        {:exit 0 :out "task-1\n" :err ""}

                        (= ["status" "--porcelain"] tail)
                        {:exit 0 :out " M src/foo.clj\n" :err ""}

                        (= "rev-list" (first tail))
                        {:exit 0 :out "1\n" :err ""}

                        (= "bundle" (first tail))
                        {:exit 128 :out "" :err "fatal: refusing to create empty bundle"}

                        :else {:exit 0 :out "" :err ""})))
                  worktree/ensure-archive-dir (fn [d] d)]
      (let [r (worktree/archive-bundle! "/tmp/scratch"
                                        {:archive-dir "/tmp/test-archive"
                                         :task-id     "env-1"
                                         :base-ref    "main"})]
        (is (result/err? r))
        (is (= :archive-bundle-failed (get-in r [:error :code])))))))

;; ============================================================================
;; default-base-path — survives reboots + config-aware resolution
;; ============================================================================

(deftest default-base-path-lives-under-home-not-tmp-test
  ;; Regression for the 2026-04-17 gates-workflow data-loss incident:
  ;; the old `/tmp/miniforge-worktrees` default got wiped on macOS
  ;; tmpfs cleanup, taking 8+ minutes of working code with it. Pin
  ;; the new default to a path that survives reboots.
  (testing "default-base-path is rooted at the user's home directory"
    (let [p (worktree/default-base-path)
          home (System/getProperty "user.home")]
      (is (.startsWith ^String p home)
          (str "default-base-path " (pr-str p)
               " must live under user.home (" home ")"))
      (is (.endsWith ^String p "/.miniforge/worktrees")
          "must end with `.miniforge/worktrees` so it sits next to checkpoints")
      (is (not (.startsWith ^String p "/tmp"))
          "must NOT be /tmp-rooted — that's the regression we're guarding"))))

(deftest default-base-path-reads-workflow-worktree-root-from-config-test
  (testing "default-base-path with config map returns :workflow/worktree-root when present"
    (let [custom "/custom/worktrees"
          p (worktree/default-base-path {:workflow/worktree-root custom})]
      (is (= custom p)
          "config :workflow/worktree-root overrides the hardcoded default")))

  (testing "default-base-path with empty config falls back to hardcoded default"
    (let [p (worktree/default-base-path {})
          home (System/getProperty "user.home")]
      (is (.startsWith ^String p home))
      (is (.endsWith ^String p "/.miniforge/worktrees"))))

  (testing "default-base-path with nil :workflow/worktree-root falls back to hardcoded"
    (let [p (worktree/default-base-path {:workflow/worktree-root nil})]
      (is (.endsWith ^String p "/.miniforge/worktrees")))))

(deftest default-base-path-tmp-opt-in-via-config-test
  (testing "/tmp-rooted path is valid when explicitly set via :workflow/worktree-root"
    (let [tmp-path "/tmp/miniforge-worktrees-ci"
          p (worktree/default-base-path {:workflow/worktree-root tmp-path})]
      (is (= tmp-path p)
          "/tmp opt-in works when caller sets :workflow/worktree-root explicitly"))))

(deftest create-worktree-executor-uses-new-default-test
  (testing "create-worktree-executor with no :base-path picks up the home default"
    (let [exec (worktree/create-worktree-executor {})]
      (is (= (worktree/default-base-path) (:base-path exec)))))

  (testing "explicit :base-path override still works (CI ephemeral runs opt in)"
    (let [exec (worktree/create-worktree-executor
                {:base-path "/tmp/miniforge-worktrees-ci"})]
      (is (= "/tmp/miniforge-worktrees-ci" (:base-path exec))))))

(deftest create-worktree-executor-reads-workflow-worktree-root-test
  (testing ":workflow/worktree-root in config is used as base-path when :base-path absent"
    (let [exec (worktree/create-worktree-executor
                {:workflow/worktree-root "/configured/worktrees"})]
      (is (= "/configured/worktrees" (:base-path exec))
          "executor picks up :workflow/worktree-root from user config")))

  (testing ":base-path takes priority over :workflow/worktree-root"
    (let [exec (worktree/create-worktree-executor
                {:base-path "/explicit/override"
                 :workflow/worktree-root "/configured/worktrees"})]
      (is (= "/explicit/override" (:base-path exec))
          ":base-path is highest priority, overrides :workflow/worktree-root"))))

;; ============================================================================
;; Legacy /tmp worktree detection
;; ============================================================================

(deftest check-legacy-tmp-worktrees-does-not-throw-test
  (testing "check-legacy-tmp-worktrees! is safe to call regardless of filesystem state"
    ;; No assertion on output — just verify it never throws.
    (is (nil? (worktree/check-legacy-tmp-worktrees!)))))

(deftest create-worktree-calls-legacy-check-test
  (testing "create-worktree invokes check-legacy-tmp-worktrees! as advisory step"
    (let [check-called? (atom false)]
      (with-redefs [worktree/check-legacy-tmp-worktrees!
                    (fn [] (reset! check-called? true))
                    worktree/run-git        (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell      (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (worktree/create-worktree "/base" "/repo" "task-x" "main")
        (is @check-called?
            "create-worktree must delegate to check-legacy-tmp-worktrees!")))))

(deftest check-legacy-tmp-worktrees-never-throws-test
  (testing "check-legacy-tmp-worktrees! is safe against any real filesystem state"
    ;; No mock needed: just runs against real FS. /tmp/miniforge-worktrees
    ;; almost certainly does not exist in CI — but even if it does, the
    ;; function only reads and emits a warning; it never throws.
    (is (nil? (worktree/check-legacy-tmp-worktrees!))
        "must return nil (not throw) in any filesystem state")))

;; ============================================================================
;; default-base-path — tilde expansion
;; ============================================================================

(deftest default-base-path-expands-tilde-test
  (testing "leading ~/ in :workflow/worktree-root is expanded to user home"
    (let [home (System/getProperty "user.home")
          p    (worktree/default-base-path
                {:workflow/worktree-root "~/.miniforge/worktrees"})]
      (is (= (str home "/.miniforge/worktrees") p)
          "tilde must be substituted with the actual home directory")))

  (testing "absolute path in :workflow/worktree-root is returned unchanged"
    (let [p (worktree/default-base-path
             {:workflow/worktree-root "/absolute/custom/path"})]
      (is (= "/absolute/custom/path" p)
          "absolute path must pass through without modification"))))

;; ============================================================================
;; Legacy /tmp detection — warning content and graceful coexistence
;; ============================================================================

(deftest check-legacy-tmp-worktrees-warns-with-message-test
  (testing "warning message includes legacy path, migration key, and cleanup command"
    (let [warnings (atom [])]
      ;; Reset the one-shot dedup atom so this test is independent of execution order.
      (reset! worktree/legacy-tmp-warned? false)
      (with-redefs [worktree/legacy-tmp-worktrees-exist? (constantly true)]
        (binding [worktree/*warn-fn* (fn [msg] (swap! warnings conj msg))]
          (worktree/check-legacy-tmp-worktrees!)
          (is (= 1 (count @warnings))
              "exactly one warning is emitted")
          (is (str/includes? (first @warnings) "WARNING")
              "message must include WARNING for operator visibility")
          (is (str/includes? (first @warnings) "/tmp/miniforge-worktrees")
              "message must identify the legacy path")
          (is (str/includes? (first @warnings) ":workflow/worktree-root")
              "message must reference the config key for migration")
          (is (str/includes? (first @warnings) "rm -rf")
              "message must include cleanup command"))))))

(deftest check-legacy-tmp-worktrees-silent-when-absent-test
  (testing "no warning is emitted when the legacy directory does not exist"
    (let [warnings (atom [])]
      (with-redefs [worktree/legacy-tmp-worktrees-exist? (constantly false)]
        (binding [worktree/*warn-fn* (fn [msg] (swap! warnings conj msg))]
          (worktree/check-legacy-tmp-worktrees!)
          (is (zero? (count @warnings))
              "warning must not fire when legacy path is absent"))))))

(deftest create-worktree-succeeds-with-legacy-worktrees-present-test
  (testing "create-worktree returns ok even when legacy /tmp worktrees exist"
    ;; Graceful coexistence constraint: a warning is emitted but the worktree
    ;; is still created at the new configured base-path without any error.
    ;; Reset the one-shot dedup atom so this test is independent of execution order.
    (reset! worktree/legacy-tmp-warned? false)
    (let [warned? (atom false)]
      (with-redefs [worktree/legacy-tmp-worktrees-exist? (constantly true)
                    worktree/run-git          (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell        (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (binding [worktree/*warn-fn* (fn [_] (reset! warned? true))]
          (let [r (worktree/create-worktree
                   "/home/user/.miniforge/worktrees"
                   "/repo" "task-coexist" "main")]
            (is (result/ok? r)
                "creation must succeed — the warning is advisory, not an error")
            (is @warned?
                "warning was emitted before creation proceeded")))))))
