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
(ns ai.miniforge.dag-executor.protocols.impl.host-guarded-test
  "End-to-end cover for the guarded worktree executor: a real host
   checkout, a real `git worktree add`, and a real config write from inside
   the task worktree.

   The point of running the whole lifecycle rather than stubbing the
   delegate is that the leak only exists because git shares a common dir
   between a checkout and its linked worktrees. A stubbed delegate would
   pass these tests with the guard removed."
  (:require
   [ai.miniforge.dag-executor.host-git-fixtures :as fixtures]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.host-guarded :as sut]
   [ai.miniforge.dag-executor.protocols.impl.host-guarded.lifecycle :as lifecycle]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private task-id
  "Fixed so the derived worktree directory name is stable across a test's
   acquire and release. The executor takes the first eight characters."
  "abcd1234-0000-0000-0000-000000000000")

(def ^{:stratum 0} ^:private second-task-id
  "A distinct id for the task that follows a drifted one. Reusing `task-id`
   would let a stale branch or directory from the first task explain the
   refusal, which is the wrong reason for that assertion to pass."
  "ef567890-0000-0000-0000-000000000000")

(defn- ^{:stratum 0} guarded-executor
  "A guarded worktree executor rooted at `base-path`, with guard state
   cleared. Both guard registries are process-lifetime by design, so a test
   that skipped the reset would inherit the previous test's verdicts."
  [base-path]
  (sut/reset-guard-state!)
  (sut/guard-host-git (worktree/create-worktree-executor {:base-path base-path})))

(defn- ^{:stratum 0} origin-url
  [dir]
  (str/trim (:out (fixtures/git! dir "config" "--get" "remote.origin.url"))))

(defn- ^{:stratum 0} worktree-dir-for
  "Where the worktree executor puts a task's directory: `task-` plus the
   first eight characters of the id, under the executor's base path."
  [base-path id]
  (File. (str base-path "/task-" (subs id 0 8))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} acquire!
  ([executor host] (acquire! executor host task-id))
  ([executor host id]
   (proto/acquire-environment! executor id {:repo-path host :branch "main"})))

;; Lifecycle
(deftest ^{:stratum 1} guarded-executor-reports-the-delegates-type-test
  (testing "given a wrapped worktree executor → still selects as :worktree"
    (let [base (fixtures/temp-dir!)]
      (try
        (is (= :worktree (proto/executor-type (guarded-executor base))))
        (finally (fixtures/delete-tree! base))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-run-that-leaves-the-host-alone-releases-normally-test
  (testing "given a task worktree that touched no remote → release stays ok"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))
          base (fixtures/temp-dir!)
          exec (guarded-executor base)]
      (try
        (let [acquired (acquire! exec host)
              env-id   (:environment-id (result/unwrap-or acquired nil))]
          (is (result/ok? acquired) "the guard does not break normal acquisition")
          (is (result/ok? (proto/release-environment! exec env-id))))
        (finally
          (sut/reset-guard-state!)
          (fixtures/delete-tree! base)
          (fixtures/delete-tree! host))))))

(deftest ^{:stratum 2} set-url-inside-the-task-worktree-fails-the-release-test
  (testing "given a run that repointed origin → release reports :host-git-drift"
    (let [host     (fixtures/init-host-repo! (fixtures/temp-dir!))
          base     (fixtures/temp-dir!)
          exec     (guarded-executor base)
          warnings (atom [])]
      (try
        (binding [lifecycle/*warn-fn* #(swap! warnings conj %)]
          (let [acquired  (acquire! exec host)
                env       (result/unwrap-or acquired nil)
                env-id    (:environment-id env)
                worktree  (:workdir env)
                _         (fixtures/git! worktree "remote" "set-url" "origin"
                                         fixtures/redirected-origin-url)
                released  (proto/release-environment! exec env-id)]
            (is (= fixtures/redirected-origin-url (origin-url host))
                "the leak is real: the task worktree rewrote the host checkout's origin")
            (is (result/err? released))
            (is (= 1 (count @warnings))
                "drift is announced as well as returned, because callers discard the result")
            (let [retry (acquire! exec host second-task-id)]
              (is (result/ok? retry)
                  "a condemned checkout still hands out worktrees: refusing degrades a
                   local run to no sandbox at all, which is worse")
              (is (= 2 (count @warnings))
                  "but every later acquire against it warns again")
              (proto/release-environment! exec (:environment-id (result/unwrap-or retry nil))))))
        (finally
          (sut/reset-guard-state!)
          (fixtures/delete-tree! base)
          (fixtures/delete-tree! host))))))

(deftest ^{:stratum 2} reset-guard-state-clears-a-condemned-checkout-test
  (testing "given a repaired checkout → resetting stops the warning and releases clean"
    (let [host (fixtures/init-host-repo! (fixtures/temp-dir!))
          base (fixtures/temp-dir!)
          exec (guarded-executor base)]
      (try
        (binding [lifecycle/*warn-fn* (fn [_])]
          (let [env-id (:environment-id (result/unwrap-or (acquire! exec host) nil))]
            (fixtures/git! (worktree-dir-for base task-id)
                           "remote" "set-url" "origin" fixtures/redirected-origin-url)
            (proto/release-environment! exec env-id)
            (fixtures/git! host "remote" "set-url" "origin" fixtures/host-origin-url)
            (sut/reset-guard-state!)
            (let [retried  (acquire! exec host)
                  env-id'  (:environment-id (result/unwrap-or retried nil))]
              (is (result/ok? retried))
              (is (result/ok? (proto/release-environment! exec env-id'))
                  "with the verdict cleared and the checkout repaired, a run releases clean"))))
        (finally
          (sut/reset-guard-state!)
          (fixtures/delete-tree! base)
          (fixtures/delete-tree! host))))))

(deftest ^{:stratum 2} a-verdict-follows-the-checkout-not-the-path-spelling-test
  (testing "given a second acquire naming the same checkout differently → still warned"
    (let [host     (fixtures/init-host-repo! (fixtures/temp-dir!))
          base     (fixtures/temp-dir!)
          exec     (guarded-executor base)
          warnings (atom [])]
      (try
        (binding [lifecycle/*warn-fn* #(swap! warnings conj %)]
          (let [env-id (:environment-id (result/unwrap-or (acquire! exec host) nil))]
            (fixtures/git! (worktree-dir-for base task-id)
                           "remote" "set-url" "origin" fixtures/redirected-origin-url)
            (proto/release-environment! exec env-id)
            (is (= 1 (count @warnings)))
            (let [spelled-differently (str host "/./")
                  retry  (acquire! exec spelled-differently second-task-id)]
              (is (= 2 (count @warnings))
                  "the verdict is keyed on the git common dir, not the string the caller passed")
              (proto/release-environment! exec (:environment-id (result/unwrap-or retry nil))))))
        (finally
          (sut/reset-guard-state!)
          (fixtures/delete-tree! base)
          (fixtures/delete-tree! host))))))
