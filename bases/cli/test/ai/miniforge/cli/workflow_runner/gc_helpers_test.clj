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
  "Unit tests for the deferred GC helpers in workflow-runner.

   Both `enqueue-workflow-gc-best-effort!` and `run-gc-pass-best-effort!` are
   private functions; they are accessed via the #' reader macro so that
   with-redefs can intercept their dependencies without loading the full
   workflow-runner integration surface."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.workflow-runner]          ;; loads the namespace
   [ai.miniforge.dag-executor.interface :as gc-queue]
   [ai.miniforge.cli.worktree :as worktree]))

;;------------------------------------------------------------------------------ Private-var helpers
;; Access private fns through the namespace's var map.

(def ^:private enqueue-best-effort!
  #'ai.miniforge.cli.workflow-runner/enqueue-workflow-gc-best-effort!)

(def ^:private run-gc-pass!
  #'ai.miniforge.cli.workflow-runner/run-gc-pass-best-effort!)

;;------------------------------------------------------------------------------ enqueue-workflow-gc-best-effort!

(deftest enqueue-best-effort!-delegates-to-gc-queue-test
  (testing "calls gc-queue/enqueue-workflow-gc! with the workflow-id"
    (let [captured (atom nil)]
      (with-redefs [gc-queue/enqueue-workflow-gc! (fn [wf-id]
                                                    (reset! captured wf-id)
                                                    (gc-queue/ok {:workflow-id (str wf-id)
                                                                  :queue-size 1}))]
        (enqueue-best-effort! "wf-unit-test"))
      (is (= "wf-unit-test" @captured)))))

(deftest enqueue-best-effort!-swallows-exceptions-test
  (testing "never propagates an exception from gc-queue/enqueue-workflow-gc!"
    (with-redefs [gc-queue/enqueue-workflow-gc! (fn [_] (throw (Exception. "simulated failure")))]
      ;; Must not throw — return value (nil) is irrelevant.
      (is (nil? (enqueue-best-effort! "wf-exception-test"))))))

(deftest enqueue-best-effort!-swallows-err-results-test
  (testing "does not throw when gc-queue returns a result/err"
    (with-redefs [gc-queue/enqueue-workflow-gc! (fn [_]
                                                  (gc-queue/err :test/error "queue full"))]
      ;; No exception should escape; result is ignored.
      (is (some? (enqueue-best-effort! "wf-err-result"))))))

;;------------------------------------------------------------------------------ run-gc-pass-best-effort!

(deftest run-gc-pass!-calls-run-deferred-gc!-when-repo-root-exists-test
  (testing "calls gc-queue/run-deferred-gc! with the repo root when worktree-root returns a path"
    (let [captured-repo (atom nil)]
      (with-redefs [worktree/worktree-root (constantly "/fake/repo/root")
                    gc-queue/run-deferred-gc! (fn [repo-root]
                                                (reset! captured-repo repo-root)
                                                (gc-queue/ok {:pruned 0 :remaining 0 :gc-result nil}))]
        (run-gc-pass!))
      (is (= "/fake/repo/root" @captured-repo)))))

(deftest run-gc-pass!-no-op-when-worktree-root-nil-test
  (testing "does not call gc-queue/run-deferred-gc! when worktree-root returns nil"
    (let [called? (atom false)]
      (with-redefs [worktree/worktree-root (constantly nil)
                    gc-queue/run-deferred-gc! (fn [_]
                                                (reset! called? true)
                                                (gc-queue/ok {}))]
        (run-gc-pass!))
      (is (not @called?)))))

(deftest run-gc-pass!-swallows-exceptions-test
  (testing "never propagates an exception from gc-queue/run-deferred-gc!"
    (with-redefs [worktree/worktree-root (constantly "/some/repo")
                  gc-queue/run-deferred-gc! (fn [_] (throw (Exception. "git exploded")))]
      ;; Must not throw.
      (is (nil? (run-gc-pass!))))))
