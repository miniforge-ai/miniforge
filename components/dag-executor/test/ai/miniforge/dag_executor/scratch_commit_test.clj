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

(ns ai.miniforge.dag-executor.scratch-commit-test
  "Unit tests for the scratch-commit module.

   ## Scope

   This namespace tests only the pure, side-effect-free functions in
   `scratch-commit` (`scratch-ref-name`).

   ## Why no git-subprocess tests here

   `scratch-commit!`, `list-scratch-commits`, and `gc-scratch-refs!` all
   delegate to `clojure.java.shell/sh`, which has **no timeout**.  On macOS
   and Linux, a git subprocess can hang indefinitely when a git credential
   helper, pager, or global commit hook blocks — causing the Polylith test
   runner to time out after 30 minutes and kill the entire test suite.

   The git-operation contract of these functions is exercised indirectly in
   `scratch_gc_queue_test.clj` via `with-redefs` mocks that return controlled
   result values without spawning any subprocesses."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.scratch-commit :as sut]))

;;------------------------------------------------------------------------------ Layer 0: scratch-ref-name (pure)

(deftest scratch-ref-name-test
  (testing "Returns the expected refs/miniforge/scratch/<id> path"
    (is (= "refs/miniforge/scratch/wf-abc123"
           (sut/scratch-ref-name "wf-abc123")))
    (is (= "refs/miniforge/scratch/some-workflow"
           (sut/scratch-ref-name "some-workflow"))))

  (testing "Handles workflow IDs with hyphens and dates"
    (is (= "refs/miniforge/scratch/run-2026-05-24"
           (sut/scratch-ref-name "run-2026-05-24"))))

  (testing "Returns a string starting with refs/miniforge/scratch/"
    (is (str/starts-with? (sut/scratch-ref-name "x") "refs/miniforge/scratch/")))

  (testing "Handles minimal single-character IDs"
    (is (= "refs/miniforge/scratch/x" (sut/scratch-ref-name "x"))))

  (testing "Handles UUID-shaped workflow IDs"
    (let [wf-id "550e8400-e29b-41d4-a716-446655440000"]
      (is (= (str "refs/miniforge/scratch/" wf-id)
             (sut/scratch-ref-name wf-id))))))
