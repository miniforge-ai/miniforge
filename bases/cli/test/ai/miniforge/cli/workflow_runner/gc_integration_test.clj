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

(ns ai.miniforge.cli.workflow-runner.gc-integration-test
  "Integration tests for the GC hook wiring pattern used in workflow-runner.

   ## What is tested

   workflow_runner.clj wires gc-hooks functions into the workflow lifecycle at
   two integration points:

   1. **Workflow start** — `run-gc-pass-best-effort!` is called once at the top
      of both `run-workflow!` and `run-workflow-from-spec!`.

   2. **Workflow finally block** — `enqueue-workflow-gc-best-effort!` is called
      in the `finally` clause of both functions, so it fires on *both* normal
      completion and exception-path exit.

   ## Why workflow_runner.clj is not loaded here

   `workflow_runner.clj` starts JVM background threads at namespace-load time
   that never terminate in a test JVM, causing a 30-minute hang.  These tests
   therefore verify the *lifecycle wiring pattern* directly via `gc-hooks`,
   using the same DI approach that `workflow_runner.clj` employs internally:

     ;; Pattern in workflow_runner.clj (simplified):
     (run-gc-pass-best-effort!)   ; at entry of run-workflow!
     (try
       (run-workflow-body! ...)
       (finally
         (enqueue-workflow-gc-best-effort! workflow-id)))

   The `defn-` wrappers in workflow_runner.clj call through the gc-hooks vars
   at runtime, so `with-redefs` on gc-hooks vars intercepts calls correctly.
   These tests exercise that same call path with mock collaborators."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]))

;;------------------------------------------------------------------------------ Helpers

(defn- ok-result [data]
  {:status :ok :data data})

;;------------------------------------------------------------------------------ GC pass at workflow start

(deftest gc-pass-invoked-at-workflow-start-test
  (testing "GC pass hook is called once at the top of the workflow"
    (let [gc-called? (atom false)
          repo-fn    (constantly "/fake/repo")
          gc-fn      (fn [_] (reset! gc-called? true) (ok-result {:pruned 0 :remaining 0 :gc-result nil}))]
      ;; Mirrors the first line of run-workflow! / run-workflow-from-spec!
      (gc-hooks/run-gc-pass-best-effort! repo-fn gc-fn)
      (is @gc-called? "GC pass must be invoked at workflow start"))))

(deftest gc-pass-forwards-repo-root-test
  (testing "GC pass forwards the worktree root path to gc-fn"
    (let [captured (atom nil)
          repo-fn  (constantly "/expected/repo/root")
          gc-fn    (fn [root] (reset! captured root) (ok-result {:pruned 0 :remaining 0 :gc-result nil}))]
      (gc-hooks/run-gc-pass-best-effort! repo-fn gc-fn)
      (is (= "/expected/repo/root" @captured)))))

(deftest gc-pass-skipped-outside-git-repo-test
  (testing "GC pass is a no-op when not inside a git repository"
    (let [gc-called? (atom false)
          repo-fn    (constantly nil)   ; no git repo
          gc-fn      (fn [_] (reset! gc-called? true))]
      (gc-hooks/run-gc-pass-best-effort! repo-fn gc-fn)
      (is (not @gc-called?) "gc-fn must NOT be called when repo root is nil"))))

;;------------------------------------------------------------------------------ Enqueue in finally block — normal exit

(deftest enqueue-called-on-normal-workflow-completion-test
  (testing "Enqueue fires in finally when the workflow body completes normally"
    (let [enqueued   (atom nil)
          enqueue-fn (fn [wf-id]
                       (reset! enqueued wf-id)
                       (ok-result {:workflow-id wf-id :queue-size 1}))]
      ;; Mirrors the finally block in run-workflow! on the success path.
      (try
        :simulated-workflow-success
        (finally
          (gc-hooks/enqueue-workflow-gc-best-effort! enqueue-fn "wf-normal-exit")))
      (is (= "wf-normal-exit" @enqueued)
          "enqueue-fn must be called with the workflow-id on normal completion"))))

;;------------------------------------------------------------------------------ Enqueue in finally block — exception exit

(deftest enqueue-called-on-exception-exit-test
  (testing "Enqueue fires in finally even when the workflow body throws"
    (let [enqueued   (atom nil)
          enqueue-fn (fn [wf-id]
                       (reset! enqueued wf-id)
                       (ok-result {:workflow-id wf-id :queue-size 1}))]
      ;; Mirrors the finally block in run-workflow! on the exception path.
      (try
        (try
          (throw (Exception. "simulated workflow failure"))
          (finally
            (gc-hooks/enqueue-workflow-gc-best-effort! enqueue-fn "wf-exception-exit")))
        (catch Exception _ nil))
      (is (= "wf-exception-exit" @enqueued)
          "enqueue-fn must be called even when the workflow body throws"))))

(deftest enqueue-exception-does-not-mask-workflow-exception-test
  (testing "An exception inside enqueue-fn does not mask the workflow's own exception"
    (let [throwing-enqueue-fn (fn [_] (throw (Exception. "enqueue exploded")))]
      ;; gc-hooks/enqueue-workflow-gc-best-effort! swallows enqueue-fn exceptions.
      ;; The original workflow exception must propagate unchanged.
      (let [caught (atom nil)]
        (try
          (try
            (throw (ex-info "original workflow error" {:code :test}))
            (finally
              (gc-hooks/enqueue-workflow-gc-best-effort! throwing-enqueue-fn "wf-masked")))
          (catch Exception e
            (reset! caught e)))
        (is (some? @caught) "The workflow exception must propagate")
        (is (= "original workflow error" (ex-message @caught))
            "The enqueue exception must not replace the workflow exception")))))

;;------------------------------------------------------------------------------ Full lifecycle pattern

(deftest full-lifecycle-gc-pass-then-workflow-then-enqueue-test
  (testing "Full GC lifecycle: pass at start → workflow → enqueue in finally"
    (let [gc-started? (atom false)
          enqueued    (atom nil)
          repo-fn     (constantly "/repo/root")
          gc-fn       (fn [_] (reset! gc-started? true) (ok-result {:pruned 0 :remaining 0 :gc-result nil}))
          enqueue-fn  (fn [id] (reset! enqueued id) (ok-result {:workflow-id id :queue-size 1}))]
      ;; Step 1: GC pass at workflow start
      (gc-hooks/run-gc-pass-best-effort! repo-fn gc-fn)
      ;; Step 2: workflow body + enqueue in finally
      (try
        :simulated-workflow-body
        (finally
          (gc-hooks/enqueue-workflow-gc-best-effort! enqueue-fn "wf-full-lifecycle")))
      (is @gc-started? "GC pass must fire at the start of the workflow")
      (is (= "wf-full-lifecycle" @enqueued)
          "Enqueue must fire in the finally block after the workflow body"))))
