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

(deftest persist-workspace-noop-test
  (testing "worktree persist-workspace! is a no-op — files are already on host"
    (let [executor (worktree/create-worktree-executor {})]
      (with-redefs [worktree/run-git   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell (fn [& _] {:exit 0 :out "" :err ""})]
        (let [r (proto/persist-workspace! executor "env-1" {:branch "task/x"})]
          (is (result/ok? r))
          (is (false? (get-in r [:data :persisted?]))))))))

(deftest restore-workspace-noop-test
  (testing "worktree restore-workspace! is a no-op"
    (let [executor (worktree/create-worktree-executor {})]
      (with-redefs [worktree/run-git   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell (fn [& _] {:exit 0 :out "" :err ""})]
        (let [r (proto/restore-workspace! executor "env-1" {:branch "task/x"})]
          (is (result/ok? r))
          (is (false? (get-in r [:data :restored?]))))))))
