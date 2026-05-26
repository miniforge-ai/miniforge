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

(ns ai.miniforge.dag-executor.protocols.impl.worktree-lifecycle-test
  "Tests for worktree lifecycle registry, derive-parent-repo-path, and
   the notify-file-written! scratch-commit hook.

   Tests that touch the registry atom reset it in finally blocks to ensure
   test isolation — the atom is module-level and shared across tests."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.result :as result]))

;;------------------------------------------------------------------------------ Registry helpers

(defn- reset-registry!
  "Reset the lifecycle registry to empty. Called in finally blocks for isolation.
   Uses @#'var to obtain the atom through its private Var, then reset! to empty it."
  []
  (reset! @#'worktree/worktree-lifecycle-registry {}))

(defn- snapshot-registry
  "Return a snapshot of the lifecycle registry for assertions.
   Delegates to the public get-worktree-registry accessor."
  []
  (worktree/get-worktree-registry))

;;------------------------------------------------------------------------------ register-worktree-entry! tests

(deftest register-worktree-entry!-adds-active-entry-test
  (testing "register-worktree-entry! adds an :active entry keyed by workflow-id"
    (try
      (worktree/register-worktree-entry! "wf-reg-001" "/tmp/worktrees/task-abc")
      (let [registry (snapshot-registry)
            entry    (get registry "wf-reg-001")]
        (is (some? entry) "entry must be present in registry")
        (is (= :active (:status entry)))
        (is (= "/tmp/worktrees/task-abc" (:worktree-path entry)))
        (is (= (scratch-commit/scratch-ref-name "wf-reg-001") (:scratch-ref entry)))
        (is (pos? (:created-at entry)) ":created-at must be a positive epoch ms"))
      (finally (reset-registry!)))))

(deftest register-worktree-entry!-replaces-existing-entry-test
  (testing "register-worktree-entry! is idempotent — replaces an existing entry"
    (try
      (worktree/register-worktree-entry! "wf-reg-002" "/tmp/worktrees/old")
      (worktree/register-worktree-entry! "wf-reg-002" "/tmp/worktrees/new")
      (let [entry (get (snapshot-registry) "wf-reg-002")]
        (is (= "/tmp/worktrees/new" (:worktree-path entry))
            "second registration must win"))
      (finally (reset-registry!)))))

(deftest register-worktree-entry!-returns-nil-test
  (testing "register-worktree-entry! returns nil (fire-and-forget semantics)"
    (try
      (is (nil? (worktree/register-worktree-entry! "wf-nil-ret" "/tmp/wt")))
      (finally (reset-registry!)))))

(deftest register-worktree-entry!-multiple-workflows-coexist-test
  (testing "multiple workflow entries coexist in the registry"
    (try
      (worktree/register-worktree-entry! "wf-multi-a" "/tmp/worktrees/a")
      (worktree/register-worktree-entry! "wf-multi-b" "/tmp/worktrees/b")
      (let [registry (snapshot-registry)]
        (is (= 2 (count registry)))
        (is (= "/tmp/worktrees/a" (:worktree-path (get registry "wf-multi-a"))))
        (is (= "/tmp/worktrees/b" (:worktree-path (get registry "wf-multi-b")))))
      (finally (reset-registry!)))))

;;------------------------------------------------------------------------------ release-worktree-entry! tests

(deftest release-worktree-entry!-marks-entry-released-test
  (testing "release-worktree-entry! transitions an active entry to :released"
    (try
      (worktree/register-worktree-entry! "wf-rel-001" "/tmp/worktrees/rel")
      (worktree/release-worktree-entry! "wf-rel-001")
      (let [entry (get (snapshot-registry) "wf-rel-001")]
        (is (= :released (:status entry)))
        (is (pos? (:released-at entry)) ":released-at must be set to epoch ms")
        ;; Critical: the scratch-ref and worktree-path are PRESERVED after release
        (is (= (scratch-commit/scratch-ref-name "wf-rel-001") (:scratch-ref entry))
            "scratch-ref must be preserved after release")
        (is (= "/tmp/worktrees/rel" (:worktree-path entry))
            "worktree-path must be preserved after release"))
      (finally (reset-registry!)))))

(deftest release-worktree-entry!-no-op-for-unknown-workflow-test
  (testing "release-worktree-entry! is a no-op when workflow-id is not in registry"
    (try
      ;; Should not throw; registry remains empty
      (worktree/release-worktree-entry! "wf-unknown")
      (is (empty? (snapshot-registry)))
      (finally (reset-registry!)))))

(deftest release-worktree-entry!-returns-nil-test
  (testing "release-worktree-entry! returns nil (fire-and-forget semantics)"
    (try
      (worktree/register-worktree-entry! "wf-nil-rel" "/tmp/wt")
      (is (nil? (worktree/release-worktree-entry! "wf-nil-rel")))
      (finally (reset-registry!)))))

;;------------------------------------------------------------------------------ get-worktree-registry tests

(deftest get-worktree-registry-returns-snapshot-test
  (testing "get-worktree-registry returns the current registry contents"
    (try
      (worktree/register-worktree-entry! "wf-snap" "/tmp/snap")
      (let [snap (worktree/get-worktree-registry)]
        (is (map? snap))
        (is (contains? snap "wf-snap")))
      (finally (reset-registry!)))))

(deftest get-worktree-registry-returns-empty-map-when-clean-test
  (testing "get-worktree-registry returns {} when nothing is registered"
    (try
      ;; Reset first to guarantee isolation
      (reset-registry!)
      (is (= {} (worktree/get-worktree-registry)))
      (finally (reset-registry!)))))

;;------------------------------------------------------------------------------ derive-parent-repo-path tests

(deftest derive-parent-repo-path-returns-parent-of-git-common-dir-test
  (testing "returns the directory containing the .git folder for an absolute common-dir"
    (with-redefs [worktree/run-git
                  (fn [& _]
                    {:exit 0 :out "/home/user/main-repo/.git\n" :err ""})]
      (let [r (worktree/derive-parent-repo-path "/tmp/worktrees/task-abc")]
        (is (result/ok? r))
        ;; Parent of /home/user/main-repo/.git is /home/user/main-repo
        (is (= "/home/user/main-repo"
               (:parent-repo-path (result/unwrap r))))))))

(deftest derive-parent-repo-path-handles-relative-git-common-dir-test
  (testing "resolves relative --git-common-dir paths against the worktree directory"
    ;; For a plain (non-linked) repo, git returns the relative path ".git"
    (with-redefs [worktree/run-git
                  (fn [& _]
                    {:exit 0 :out ".git\n" :err ""})]
      (let [r (worktree/derive-parent-repo-path "/tmp/my-repo")]
        (is (result/ok? r))
        ;; .git resolved against /tmp/my-repo → /tmp/my-repo/.git
        ;; parent of that is /tmp/my-repo
        (is (= "/tmp/my-repo"
               (:parent-repo-path (result/unwrap r))))))))

(deftest derive-parent-repo-path-returns-err-when-git-fails-test
  (testing "returns result/err when git rev-parse --git-common-dir exits non-zero"
    (with-redefs [worktree/run-git
                  (fn [& _]
                    {:exit 128 :out "" :err "fatal: not a git repository"})]
      (let [r (worktree/derive-parent-repo-path "/not/a/repo")]
        (is (result/err? r))
        (is (= :derive-parent-repo-failed (get-in r [:error :code])))))))

;;------------------------------------------------------------------------------ notify-file-written! tests

(deftest notify-file-written!-calls-scratch-commit-with-parent-repo-test
  (testing "notify-file-written! derives parent path and delegates to scratch-commit!"
    (let [scratch-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& _]
                      {:exit 0 :out "/home/user/main-repo/.git\n" :err ""})
                    scratch-commit/scratch-commit!
                    (fn [parent-repo-path workflow-id phase file-path]
                      (reset! scratch-args {:parent-repo-path parent-repo-path
                                            :workflow-id      workflow-id
                                            :phase            phase
                                            :file-path        file-path})
                      (result/ok {:commit-sha  "deadbeef"
                                  :ref         (scratch-commit/scratch-ref-name workflow-id)
                                  :workflow-id workflow-id
                                  :phase       phase
                                  :file-path   file-path}))]
        (let [r (worktree/notify-file-written!
                 "/tmp/worktrees/task-abc"
                 "wf-notify-001"
                 "implement"
                 "/tmp/worktrees/task-abc/components/foo/src/bar.clj")]
          (is (result/ok? r))
          (is (some? @scratch-args) "scratch-commit! must have been called")
          (is (= "/home/user/main-repo" (:parent-repo-path @scratch-args)))
          (is (= "wf-notify-001" (:workflow-id @scratch-args)))
          (is (= "implement" (:phase @scratch-args)))
          (is (= "/tmp/worktrees/task-abc/components/foo/src/bar.clj"
                 (:file-path @scratch-args))))))))

(deftest notify-file-written!-short-circuits-when-derive-fails-test
  (testing "notify-file-written! returns err without calling scratch-commit! on git failure"
    (let [scratch-called? (atom false)]
      (with-redefs [worktree/run-git
                    (fn [& _]
                      {:exit 128 :out "" :err "fatal: not a git repository"})
                    scratch-commit/scratch-commit!
                    (fn [& _]
                      (reset! scratch-called? true)
                      (result/ok {}))]
        (let [r (worktree/notify-file-written!
                 "/not/a/repo" "wf-no-git" "impl" "/some/file.clj")]
          (is (result/err? r))
          (is (= :derive-parent-repo-failed (get-in r [:error :code])))
          (is (false? @scratch-called?)
              "scratch-commit! must NOT be called when parent-repo derivation fails"))))))

(deftest notify-file-written!-returns-result-from-scratch-commit-test
  (testing "notify-file-written! propagates the scratch-commit! result to caller"
    (with-redefs [worktree/run-git
                  (fn [& _]
                    {:exit 0 :out "/repo/.git\n" :err ""})
                  scratch-commit/scratch-commit!
                  (fn [& _]
                    (result/err :scratch-commit/hash-object-failed
                                "hash-object failed" {:file-path "/bad/file"}))]
      (let [r (worktree/notify-file-written!
               "/tmp/wt" "wf-sc-fail" "verify" "/bad/file")]
        (is (result/err? r))
        (is (= :scratch-commit/hash-object-failed (get-in r [:error :code])))))))

;;------------------------------------------------------------------------------ WorktreeExecutor lifecycle-hook integration tests

(deftest acquire-environment-registers-workflow-when-id-provided-test
  (testing "acquire-environment! registers worktree in lifecycle registry when :workflow-id is in env-config"
    (try
      (with-redefs [worktree/run-git       (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell     (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})
              ;; task-id must be at least 8 chars; executor takes (subs task-id 0 8)
              task-id  "abcd1234efgh"]
          (proto/acquire-environment! executor task-id
                                      {:repo-path   "/tmp/repo"
                                       :branch      "main"
                                       :workflow-id "wf-lifecycle-acq"})
          (let [registry (snapshot-registry)
                entry    (get registry "wf-lifecycle-acq")]
            (is (some? entry) "registry must contain the workflow entry")
            (is (= :active (:status entry)))
            (is (str/ends-with? (:worktree-path entry) "task-abcd1234")
                "worktree-path must include the truncated task-id prefix"))))
      (finally (reset-registry!)))))

(deftest acquire-environment-skips-registry-without-workflow-id-test
  (testing "acquire-environment! does not add a registry entry when :workflow-id is absent"
    (try
      (with-redefs [worktree/run-git       (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell     (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})]
          (proto/acquire-environment! executor "abcd1234efgh"
                                      {:repo-path "/tmp/repo" :branch "main"})
          ;; No :workflow-id → registry stays empty
          (is (empty? (snapshot-registry)))))
      (finally (reset-registry!)))))

(deftest release-environment-marks-registry-released-test
  (testing "release-environment! transitions the matching registry entry to :released"
    (try
      (with-redefs [worktree/run-git   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell (fn [& _] {:exit 0 :out "" :err ""})]
        ;; Pre-seed the registry matching the path the executor will derive.
        ;; executor base-path=/tmp/base + environment-id=task-00000000
        ;; → worktree-path = /tmp/base/task-00000000
        (worktree/register-worktree-entry! "wf-lifecycle-rel"
                                           "/tmp/base/task-00000000")
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})]
          (proto/release-environment! executor "task-00000000")
          (let [entry (get (snapshot-registry) "wf-lifecycle-rel")]
            (is (some? entry))
            (is (= :released (:status entry)))
            (is (pos? (:released-at entry)))
            ;; scratch-ref preserved after release
            (is (= (scratch-commit/scratch-ref-name "wf-lifecycle-rel")
                   (:scratch-ref entry))))))
      (finally (reset-registry!)))))

(deftest release-environment-without-matching-registry-entry-is-safe-test
  (testing "release-environment! does not throw when no registry entry matches"
    ;; This is the common case when :workflow-id was not in the original env-config
    (with-redefs [worktree/run-git   (fn [& _] {:exit 0 :out "" :err ""})
                  worktree/run-shell (fn [& _] {:exit 0 :out "" :err ""})]
      (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})]
        ;; Should complete without throwing
        (let [r (proto/release-environment! executor "no-registry-entry")]
          (is (result/ok? r)))))))
