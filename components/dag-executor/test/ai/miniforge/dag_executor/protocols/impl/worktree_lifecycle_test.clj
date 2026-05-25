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
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]
   [ai.miniforge.dag-executor.result :as result]))

;;------------------------------------------------------------------------------ Registry helpers

(defn- reset-registry!
  "Reset the lifecycle registry to empty. Called in finally blocks for isolation."
  []
  (reset! @#'worktree/worktree-lifecycle-registry {}))

(defn- snapshot-registry
  "Return a snapshot of the lifecycle registry for assertions."
  []
  @(#'worktree/worktree-lifecycle-registry))

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
        ;; Critical: the scratch-ref and worktree-path are PRESERVED
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
    ;; Assumes tests are isolated via reset-registry!
    (try
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
  (testing "resolves relative --git-common-dir paths against the worktree path"
    ;; For a worktree under /base/task-abc, `git rev-parse --git-common-dir`
    ;; might return a relative path like `../../main-repo/.git`
    (with-redefs [worktree/run-git
                  (fn [& _]
                    ;; Return a relative path from the worktree's CWD
                    {:exit 0 :out ".git\n" :err ""})]
      (let [r (worktree/derive-parent-repo-path "/tmp/my-repo")]
        (is (result/ok? r))
        ;; .git resolved against /tmp/my-repo → /tmp/my-repo/.git
        ;; parent of that is /tmp/my-repo
        (is (= "/tmp/my-repo"
               (:parent-repo-path (result/unwrap r))))))))

(deftest derive-parent-repo-path-returns-err-when-git-fails-test
  (testing "returns result/err when git rev-parse --git-common-dir fails"
    (with-redefs [worktree/run-git
                  (fn [& _]
                    {:exit 128 :out "" :err "fatal: not a git repository"})]
      (let [r (worktree/derive-parent-repo-path "/not/a/repo")]
        (is (result/err? r))
        (is (= :derive-parent-repo-failed (get-in r [:error :code])))))))

;;------------------------------------------------------------------------------ notify-file-written! tests

(deftest notify-file-written!-calls-scratch-commit-with-parent-repo-test
  (testing "notify-file-written! derives parent path and calls scratch-commit!"
    (let [scratch-args (atom nil)]
      (with-redefs [worktree/run-git
                    (fn [& args]
                      ;; Respond to rev-parse --git-common-dir
                      (if (some #{"--git-common-dir"} args)
                        {:exit 0 :out "/home/user/main-repo/.git\n" :err ""}
                        {:exit 0 :out "" :err ""}))
                    scratch-commit/scratch-commit!
                    (fn [parent-repo-path workflow-id phase file-path]
                      (reset! scratch-args {:parent-repo-path parent-repo-path
                                            :workflow-id      workflow-id
                                            :phase            phase
                                            :file-path        file-path})
                      (result/ok {:commit-sha "deadbeef" :ref "refs/miniforge/scratch/wf-1"
                                  :workflow-id workflow-id :phase phase
                                  :file-path file-path}))]
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

(deftest notify-file-written!-returns-err-when-derive-fails-test
  (testing "notify-file-written! short-circuits on derive-parent-repo-path failure"
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

;;------------------------------------------------------------------------------ acquire-environment! lifecycle hook tests

(deftest acquire-environment-registers-workflow-when-id-provided-test
  (testing "acquire-environment! registers the worktree when :workflow-id is in env-config"
    (try
      (with-redefs [worktree/run-git     (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})
              _        (proto/acquire-environment!
                        executor
                        "task-12345678"
                        {:repo-path   "/tmp/repo"
                         :branch      "main"
                         :workflow-id "wf-lifecycle-acq"})]
          (let [registry (snapshot-registry)
                entry    (get registry "wf-lifecycle-acq")]
            (is (some? entry) "registry must contain the workflow entry")
            (is (= :active (:status entry)))
            (is (str/includes? (:worktree-path entry) "task-12345")))))
      (finally (reset-registry!))))
  nil)

(deftest acquire-environment-no-registry-entry-without-workflow-id-test
  (testing "acquire-environment! skips registry when :workflow-id is absent"
    (try
      (with-redefs [worktree/run-git     (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/ensure-directory (fn [_] nil)]
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})]
          (proto/acquire-environment! executor "task-12345678"
                                      {:repo-path "/tmp/repo"
                                       :branch    "main"})
          ;; No workflow-id → no registry entry
          (is (empty? (snapshot-registry)))))
      (finally (reset-registry!)))))

(deftest release-environment-marks-registry-released-test
  (testing "release-environment! transitions the matching registry entry to :released"
    (try
      (with-redefs [worktree/run-git   (fn [& _] {:exit 0 :out "" :err ""})
                    worktree/run-shell (fn [& _] {:exit 0 :out "" :err ""})]
        ;; Pre-seed the registry so we don't need to create a real worktree
        (worktree/register-worktree-entry! "wf-lifecycle-rel"
                                           "/tmp/base/task-00000000")
        (let [executor (worktree/create-worktree-executor {:base-path "/tmp/base"})]
          (proto/release-environment! executor "task-00000000")
          (let [entry (get (snapshot-registry) "wf-lifecycle-rel")]
            (is (some? entry))
            (is (= :released (:status entry)))
            (is (pos? (:released-at entry))))))
      (finally (reset-registry!)))))

;;------------------------------------------------------------------------------ require clojure.string

;; clojure.string is used in acquire-environment test assertions via str/includes?
(require '[clojure.string :as str])
