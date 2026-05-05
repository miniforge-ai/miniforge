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

(ns ai.miniforge.workflow.merge-resolution-test
  "Tests for the v2 multi-parent merge-resolution iteration loop.

   Each test sets up a real git repo with an induced conflict, then
   runs `resolve-conflict!` with a parameterized `agent-edit-fn` that
   simulates what the real LLM-driven agent will do in Stage 2C —
   resolve markers (success path) or do nothing (terminate path).
   The curator and verify steps are exercised against the real
   worktree state."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.workflow.merge-resolution :as sut]
   [babashka.fs :as fs]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]))

;------------------------------------------------------------------------------ Fixture: temp git repo with conflict

(def ^:dynamic *repo* nil)
(def ^:dynamic *worktree* nil)

(defn- run-git! [cwd & args]
  (let [r (apply shell/sh "git" "-C" cwd args)]
    (when-not (zero? (:exit r))
      (throw (ex-info (str "git " (str/join " " args) " failed: " (:err r))
                      {:cwd cwd :args args :result r})))
    r))

(defn- write-file! [cwd path content]
  (let [f (java.io.File. ^String cwd ^String path)]
    (.mkdirs (.getParentFile f))
    (spit f content)))

(defn- commit-file! [cwd path content message]
  (write-file! cwd path content)
  (run-git! cwd "add" path)
  (run-git! cwd "commit" "-m" message))

(defn- conflict-fixture [f]
  (let [host (str (fs/create-temp-dir {:prefix "mr-test-host-"}))]
    (try
      (run-git! host "init" "-b" "main")
      (run-git! host "config" "user.email" "test@miniforge.ai")
      (run-git! host "config" "user.name" "miniforge-test")
      (run-git! host "config" "commit.gpgsign" "false")
      (commit-file! host "README.md" "initial\n" "init")
      ;; Two branches that conflict on the same file
      (run-git! host "checkout" "-b" "task-a" "main")
      (commit-file! host "src/conflict.txt" "from a\n" "a edit")
      (run-git! host "checkout" "-b" "task-b" "main")
      (commit-file! host "src/conflict.txt" "from b\n" "b edit")
      (run-git! host "checkout" "main")
      ;; Stage the merge worktree off task-a, attempt the merge — it
      ;; will conflict, leaving markers in src/conflict.txt
      (let [worktree (str (fs/create-temp-dir {:prefix "mr-test-worktree-"}))]
        (try
          (fs/delete-tree worktree)
          (run-git! host "worktree" "add" "--detach" worktree "task-a")
          (let [merge-r (shell/sh "git" "-C" worktree "merge" "-s" "ort"
                                  "--no-edit" "--no-gpg-sign" "--no-verify"
                                  "--no-ff" "-m" "induced-conflict" "task-b")]
            ;; merge exits non-zero on conflict; that's expected — the
            ;; worktree now has conflict markers in src/conflict.txt.
            (assert (not (zero? (:exit merge-r)))
                    "fixture setup expected merge to conflict"))
          (binding [*repo* host *worktree* worktree]
            (f))
          (finally
            (try (run-git! host "worktree" "remove" "--force" worktree)
                 (catch Throwable _ nil))
            (try (fs/delete-tree worktree) (catch Throwable _ nil)))))
      (finally
        (try (fs/delete-tree host) (catch Throwable _ nil))))))

(use-fixtures :each conflict-fixture)

;------------------------------------------------------------------------------ Helpers

(defn- conflict-input
  "Build the conflict-anomaly shape that resolve-conflict! expects."
  []
  {:task/id "task-c"
   :merge/parents [{:task/id :a :branch "task-a" :commit-sha "a-sha" :order 0}
                   {:task/id :b :branch "task-b" :commit-sha "b-sha" :order 1}]
   :merge/strategy :git-merge
   :merge/input-key "test-key"
   :merge/conflicts [{:path "src/conflict.txt" :stages ["1" "2" "3"]}]})

(defn- write-resolved-content!
  "Helper: an agent-edit-fn that resolves the conflict by overwriting
   the marker file with deterministic content. Mirrors what a real LLM
   agent would do for a trivial conflict."
  [worktree _conflict _iteration]
  (write-file! worktree "src/conflict.txt"
               "from a\nfrom b\n;; merged by mock agent\n")
  (response/success {:edits/applied 1
                     :edits/files ["src/conflict.txt"]}
                    nil))

;------------------------------------------------------------------------------ Tests: success path

(deftest mock-agent-resolves-on-first-iteration-test
  (testing "When the agent's edits clear the conflict markers and
            verify passes, the loop returns dag/ok wrapping the
            resolution commit's SHA. Verifies the integration-level
            contract: agent fix → curator success → verify success →
            commit lands → ok result."
    (let [result (sut/resolve-conflict!
                  {:conflict-input (conflict-input)
                   :host-repo *repo*
                   :worktree-path *worktree*
                   :task-id "task-c"
                   :agent-edit-fn write-resolved-content!})]
      (is (dag/ok? result))
      (let [data (:data result)]
        (is (string? (:commit-sha data)))
        (is (= 40 (count (:commit-sha data)))
            "full-length git SHA")
        (is (= 1 (:iterations data))
            "first-iteration success")
        (is (true? (:resolved? data)))))))

;------------------------------------------------------------------------------ Tests: terminal anomalies

(deftest no-op-stub-agent-recurring-conflict-test
  (testing "With the default no-op stub agent, the curator finds the
            same conflict path each iteration; recurring-conflict
            fires on iteration 2 and the loop terminates. The
            terminal anomaly carries :resolution/reason
            :curator/recurring-conflict."
    (let [result (sut/resolve-conflict!
                  {:conflict-input (conflict-input)
                   :host-repo *repo*
                   :worktree-path *worktree*
                   :task-id "task-c"
                   ;; default agent-edit-fn = no-op-agent-edit-fn
                   })]
      (is (= :anomalies/dag-multi-parent-unresolvable
             (:anomaly/category result)))
      (is (= :curator/recurring-conflict
             (:resolution/reason result))))))

(deftest budget-exhausted-test
  (testing "When the agent edits 'something' but never resolves the
            conflict — and the conflict path set keeps shifting so
            recurring-conflict doesn't fire — the loop runs to
            budget exhaustion. Carries :resolution/reason
            :budget-exhausted with iteration count equal to
            :max-iterations.

            The mock agent fixes the original conflict file but
            introduces markers in a different newly-tracked file each
            iteration. Since git grep only sees tracked files, the
            agent must `git add` each new file for the curator scan
            to detect it (this is also what a real LLM agent would
            do — emit code via Edit/Write tools, then have the
            agent loop run git add)."
    (let [churn-fn
          (fn [worktree _conflict iteration]
            ;; Iteration 0 fixes the original conflict file (so its
            ;; markers go away) but introduces markers in a new file.
            ;; Iterations 1+ shift to new files (keeping path-set
            ;; changing). All files are git-add'd so the scan sees them.
            (write-file! worktree "src/conflict.txt" "fixed-by-mock\n")
            (let [new-path (str "src/churn-" iteration ".txt")]
              (write-file! worktree new-path
                           "<<<<<<< a\nfoo\n=======\nbar\n>>>>>>> b\n")
              (run-git! worktree "add" "src/conflict.txt" new-path))
            (response/success nil nil))
          result (sut/resolve-conflict!
                  {:conflict-input (conflict-input)
                   :host-repo *repo*
                   :worktree-path *worktree*
                   :task-id "task-c"
                   :agent-edit-fn churn-fn
                   :budget {:max-iterations 3 :stagnation-cap 2}})]
      (is (= :anomalies/dag-multi-parent-unresolvable
             (:anomaly/category result)))
      (is (= :budget-exhausted (:resolution/reason result)))
      (is (= 3 (:resolution/iterations result))
          "iteration count matches the budget cap"))))

(deftest verify-failure-counts-as-no-progress-test
  (testing "When the agent clears markers but verify fails, the loop
            doesn't immediately terminate — it tries again. With a
            verify that always fails AND markers always cleared, the
            loop reaches budget exhaustion (the recurring-conflict
            path requires markers to remain)."
    (let [result (sut/resolve-conflict!
                  {:conflict-input (conflict-input)
                   :host-repo *repo*
                   :worktree-path *worktree*
                   :task-id "task-c"
                   :agent-edit-fn write-resolved-content!
                   ;; Use response/error so the loop's response/success?
                   ;; check sees the failure, matching how a real
                   ;; verify-fn (Stage 4) will signal a failed test run.
                   :verify-fn (fn always-fails [_]
                                (response/error "tests failed" nil))
                   :budget {:max-iterations 2 :stagnation-cap 2}})]
      (is (= :anomalies/dag-multi-parent-unresolvable
             (:anomaly/category result)))
      (is (= :budget-exhausted (:resolution/reason result))))))

;------------------------------------------------------------------------------ Tests: agent makes partial progress

(deftest progress-then-stuck-test
  (testing "An agent that makes progress on the first iteration but
            then gets stuck still surfaces recurring-conflict — the
            curator fires on the SECOND no-progress iteration, not
            on the iteration that DID change the path set.

            Iteration 1 fixes src/conflict.txt and introduces a
            different conflicted file (src/new.txt, git-add'd so
            git grep sees it). Iteration 2+ does nothing — same
            path set as left behind ⇒ recurring."
    (let [iteration (atom 0)
          partial-fn (fn [worktree _conflict _i]
                       (let [n (swap! iteration inc)]
                         (when (= n 1)
                           (write-file! worktree "src/conflict.txt"
                                        "resolved\n")
                           (write-file! worktree "src/new.txt"
                                        "<<<<<<< a\nfoo\n=======\nbar\n>>>>>>> b\n")
                           (run-git! worktree "add" "src/conflict.txt"
                                     "src/new.txt"))
                         (response/success nil nil)))
          result (sut/resolve-conflict!
                  {:conflict-input (conflict-input)
                   :host-repo *repo*
                   :worktree-path *worktree*
                   :task-id "task-c"
                   :agent-edit-fn partial-fn})]
      (is (= :anomalies/dag-multi-parent-unresolvable
             (:anomaly/category result)))
      (is (= :curator/recurring-conflict
             (:resolution/reason result))
          "second iteration sees the same {src/new.txt} set as the
           first iteration left behind ⇒ recurring"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.merge-resolution-test)

  :leave-this-here)
