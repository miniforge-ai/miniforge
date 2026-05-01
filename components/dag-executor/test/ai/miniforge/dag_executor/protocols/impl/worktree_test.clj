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
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.result :as result]))

;; ============================================================================
;; create-worktree: lock and fallback strategy
;; ============================================================================

(deftest create-worktree-succeeds-on-first-attempt-test
  (testing "returns ok with worktree-path when git worktree add -b succeeds immediately"
    (with-redefs [worktree/run-git     (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/run-shell   (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/ensure-directory (fn [_] nil)]
      (let [r (worktree/create-worktree "/tmp/base" "/tmp/repo" "task-abc" "main")]
        (is (result/ok? r))
        (is (= "/tmp/base/task-abc" (:worktree-path (:data r))))))))

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
                    worktree/ensure-directory (fn [_] nil)]
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
                    worktree/ensure-directory (fn [_] nil)]
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
                  worktree/ensure-directory (fn [_] nil)]
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
