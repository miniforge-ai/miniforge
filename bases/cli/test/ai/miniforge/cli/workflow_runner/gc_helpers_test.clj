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

(ns ai.miniforge.cli.workflow-runner.gc-helpers-test
  "Unit tests for the deferred GC hooks in workflow-runner.gc-hooks.

   The helpers live in `workflow-runner.gc-hooks` (not `workflow-runner` itself)
   so they can be loaded independently of the full runner stack, which starts
   threads that would hang a test JVM if required here."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as sut]
   [ai.miniforge.dag-executor.interface :as gc-queue]
   [ai.miniforge.cli.worktree :as worktree]))

;;------------------------------------------------------------------------------ enqueue-workflow-gc-best-effort!

(deftest enqueue-best-effort!-delegates-to-gc-queue-test
  (testing "calls gc-queue/enqueue-workflow-gc! with the workflow-id"
    (let [captured (atom nil)]
      (with-redefs [gc-queue/enqueue-workflow-gc! (fn [wf-id]
                                                    (reset! captured wf-id)
                                                    (gc-queue/ok {:workflow-id (str wf-id)
                                                                  :queue-size 1}))]
        (sut/enqueue-workflow-gc-best-effort! "wf-unit-test"))
      (is (= "wf-unit-test" @captured)))))

(deftest enqueue-best-effort!-swallows-exceptions-test
  (testing "never propagates an exception thrown by gc-queue/enqueue-workflow-gc!"
    (with-redefs [gc-queue/enqueue-workflow-gc! (fn [_] (throw (Exception. "simulated failure")))]
      ;; Must not throw — return value is irrelevant.
      (is (nil? (sut/enqueue-workflow-gc-best-effort! "wf-exception-test"))))))

(deftest enqueue-best-effort!-returns-result-on-success-test
  (testing "returns the result from gc-queue/enqueue-workflow-gc! on the happy path"
    (with-redefs [gc-queue/enqueue-workflow-gc! (fn [wf-id]
                                                  (gc-queue/ok {:workflow-id (str wf-id)
                                                                :queue-size 1}))]
      (let [r (sut/enqueue-workflow-gc-best-effort! "wf-ok")]
        (is (gc-queue/ok? r))))))

;;------------------------------------------------------------------------------ run-gc-pass-best-effort!

(deftest run-gc-pass!-calls-run-deferred-gc!-when-repo-root-exists-test
  (testing "calls gc-queue/run-deferred-gc! with the repo root when worktree-root is non-nil"
    (let [captured-repo (atom nil)]
      (with-redefs [worktree/worktree-root (constantly "/fake/repo/root")
                    gc-queue/run-deferred-gc! (fn [repo-root]
                                                (reset! captured-repo repo-root)
                                                (gc-queue/ok {:pruned 0 :remaining 0 :gc-result nil}))]
        (sut/run-gc-pass-best-effort!))
      (is (= "/fake/repo/root" @captured-repo)))))

(deftest run-gc-pass!-no-op-when-worktree-root-nil-test
  (testing "does not call gc-queue/run-deferred-gc! when worktree-root returns nil"
    (let [called? (atom false)]
      (with-redefs [worktree/worktree-root (constantly nil)
                    gc-queue/run-deferred-gc! (fn [_]
                                                (reset! called? true)
                                                (gc-queue/ok {}))]
        (sut/run-gc-pass-best-effort!))
      (is (not @called?)))))

(deftest run-gc-pass!-swallows-exceptions-test
  (testing "never propagates an exception thrown by gc-queue/run-deferred-gc!"
    (with-redefs [worktree/worktree-root (constantly "/some/repo")
                  gc-queue/run-deferred-gc! (fn [_] (throw (Exception. "git exploded")))]
      ;; Must not throw.
      (is (nil? (sut/run-gc-pass-best-effort!))))))

(deftest run-gc-pass!-returns-nil-when-worktree-root-nil-test
  (testing "returns nil (not an error) when worktree-root is nil"
    (with-redefs [worktree/worktree-root (constantly nil)]
      (is (nil? (sut/run-gc-pass-best-effort!))))))
