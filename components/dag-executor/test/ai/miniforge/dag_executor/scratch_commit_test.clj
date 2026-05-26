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
  "Tests for the scratch-commit module.

   ## Why only pure functions are tested here

   `scratch-commit!`, `list-scratch-commits`, and `gc-scratch-refs!` all invoke
   git plumbing commands via `clojure.java.shell/sh`, which has NO built-in
   timeout.  A hung git subprocess (e.g. waiting for user identity, a pager,
   or a credential helper) will block the entire Polylith test runner for the
   full 30-minute timeout window.

   The git-plumbing paths are exercised indirectly — with mocked collaborators
   — in `scratch_gc_queue_test.clj`.  Only the pure helper `scratch-ref-name`
   is tested here because it performs no I/O and cannot hang."
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

  (testing "Concatenates prefix and workflow-id with no extra characters"
    (is (= (str "refs/miniforge/scratch/" "my-wf-42")
           (sut/scratch-ref-name "my-wf-42")))))
