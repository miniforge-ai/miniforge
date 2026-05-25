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

(ns ai.miniforge.cli.workflow-runner.gc-hooks-test
  "Unit tests for the deferred GC hooks in workflow-runner.gc-hooks.

   ## Why no external requires (dag-executor.interface, cli.worktree)

   `gc-hooks` is a *pure* namespace with zero external requires.  Its functions
   accept side-effecting collaborators as injected function arguments.  This
   lets us test them with plain lambdas — no `with-redefs`, no namespace loading
   of thread-starting infrastructure (both `cli.worktree` and
   `dag-executor.interface` transitively load code that starts JVM threads,
   which causes 30-minute test-runner hangs when loaded in isolation).

   Tests here only require `gc-hooks` itself, which is completely dependency-free."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as sut]))

;;------------------------------------------------------------------------------ enqueue-workflow-gc-best-effort!

(deftest enqueue-best-effort!-delegates-to-enqueue-fn-test
  (testing "calls enqueue-fn with the workflow-id"
    (let [captured   (atom nil)
          enqueue-fn (fn [id]
                       (reset! captured id)
                       {:status :ok :data {:workflow-id id :queue-size 1}})]
      (sut/enqueue-workflow-gc-best-effort! enqueue-fn "wf-unit-test")
      (is (= "wf-unit-test" @captured)))))

(deftest enqueue-best-effort!-swallows-exceptions-test
  (testing "never propagates an exception thrown by enqueue-fn"
    (let [throwing-fn (fn [_] (throw (Exception. "simulated failure")))]
      ;; Must not throw — return value is irrelevant.
      (is (nil? (sut/enqueue-workflow-gc-best-effort! throwing-fn "wf-exception-test"))))))

(deftest enqueue-best-effort!-returns-result-on-success-test
  (testing "returns the result produced by enqueue-fn on the happy path"
    (let [ok-result   {:status :ok :data {:workflow-id "wf-ok" :queue-size 1}}
          enqueue-fn  (constantly ok-result)]
      (is (= ok-result
             (sut/enqueue-workflow-gc-best-effort! enqueue-fn "wf-ok"))))))

;;------------------------------------------------------------------------------ run-gc-pass-best-effort!

(deftest run-gc-pass!-calls-gc-fn-when-repo-root-exists-test
  (testing "calls gc-fn with the repo root when worktree-root-fn returns non-nil"
    (let [captured-repo    (atom nil)
          worktree-root-fn (constantly "/fake/repo/root")
          gc-fn            (fn [repo-root]
                             (reset! captured-repo repo-root)
                             {:status :ok :data {:pruned 0 :remaining 0 :gc-result nil}})]
      (sut/run-gc-pass-best-effort! worktree-root-fn gc-fn)
      (is (= "/fake/repo/root" @captured-repo)))))

(deftest run-gc-pass!-no-op-when-worktree-root-nil-test
  (testing "does not call gc-fn when worktree-root-fn returns nil"
    (let [called?          (atom false)
          worktree-root-fn (constantly nil)
          gc-fn            (fn [_]
                             (reset! called? true)
                             {:status :ok :data {}})]
      (sut/run-gc-pass-best-effort! worktree-root-fn gc-fn)
      (is (not @called?)))))

(deftest run-gc-pass!-swallows-exceptions-test
  (testing "never propagates an exception thrown by gc-fn"
    (let [worktree-root-fn (constantly "/some/repo")
          throwing-gc-fn   (fn [_] (throw (Exception. "git exploded")))]
      ;; Must not throw.
      (is (nil? (sut/run-gc-pass-best-effort! worktree-root-fn throwing-gc-fn))))))

(deftest run-gc-pass!-returns-nil-when-worktree-root-nil-test
  (testing "returns nil (not an error) when worktree-root-fn returns nil"
    (is (nil? (sut/run-gc-pass-best-effort! (constantly nil) identity)))))

(deftest run-gc-pass!-returns-gc-fn-result-on-success-test
  (testing "returns the result from gc-fn when everything succeeds"
    (let [gc-result        {:status :ok :data {:pruned 2 :remaining 0 :gc-result {}}}
          worktree-root-fn (constantly "/some/repo")
          gc-fn            (constantly gc-result)]
      (is (= gc-result
             (sut/run-gc-pass-best-effort! worktree-root-fn gc-fn))))))
