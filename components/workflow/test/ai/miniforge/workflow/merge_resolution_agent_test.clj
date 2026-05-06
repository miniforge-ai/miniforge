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

(ns ai.miniforge.workflow.merge-resolution-agent-test
  "Stage 2C: tests for the resolution-prompt builders. The agent-
   invocation closure (`agent-driven-edit-fn`) lands in a follow-up
   slice with its own mock-based tests; this file pins the pure-data
   prompt + task assembly used by both Stage 2C and any future policy
   pack that swaps catalog templates per spec §6.1.3."
  (:require
   [ai.miniforge.workflow.merge-resolution :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Test data

(def ^:private sample-conflict-input
  {:task/id "task-c"
   :merge/parents [{:task/id :a :branch "task-a" :commit-sha "aaa1111" :order 0}
                   {:task/id :b :branch "task-b" :commit-sha "bbb2222" :order 1}]
   :merge/strategy :git-merge
   :merge/input-key "input-key-xyz"
   :merge/conflicts [{:path "src/conflict.txt" :stages ["1" "2" "3"]}
                     {:path "src/other.clj"    :stages ["1" "2"]}]})

;; Internal helpers reach in via the namespace's vars so we can test
;; them directly without making them public. Using `var-get` keeps the
;; namespace's `^:private` posture intact while letting the tests
;; assert on their behaviour.
(def ^:private build-resolution-prompt
  (var-get #'sut/build-resolution-prompt))

(def ^:private build-resolution-task
  (var-get #'sut/build-resolution-task))

;------------------------------------------------------------------------------ Pure-data: prompt builder

(deftest build-resolution-prompt-includes-all-sections-test
  (testing "Built prompt includes header (parent count), iteration
            line, strategy, parents block, conflicts block, and the
            instructions. Each section's content comes from the
            workflow message catalog so a policy pack swap propagates
            without code changes (spec §6.1.3)."
    (let [prompt (build-resolution-prompt sample-conflict-input 0 5)]
      (is (string? prompt))
      (is (str/includes? prompt "2 parent")
          "header carries parent count")
      (is (str/includes? prompt "Resolution attempt 1 of 5")
          "iteration line is 1-indexed and shows budget")
      (is (str/includes? prompt ":git-merge")
          "strategy is rendered (printed as the keyword)")
      (is (str/includes? prompt ":a")
          "first parent's :task/id keyword rendered")
      (is (str/includes? prompt ":b")
          "second parent's :task/id keyword rendered")
      (is (str/includes? prompt "aaa1111") "parent commit-sha rendered")
      (is (str/includes? prompt "bbb2222") "parent commit-sha rendered")
      (is (str/includes? prompt "src/conflict.txt") "conflict path rendered")
      (is (str/includes? prompt "src/other.clj") "second conflict path rendered")
      (is (str/includes? prompt "1/2/3")
          "stages joined with '/'")
      (is (str/includes? prompt "Edit and Write")
          "instructions block carries the tool names the agent should use"))))

(deftest build-resolution-prompt-iteration-zero-shows-attempt-one-test
  (testing "Iteration counter is 1-indexed in the prompt so the agent
            sees 'attempt 1 of N' on its first turn rather than '0 of N'."
    (let [prompt (build-resolution-prompt sample-conflict-input 0 3)]
      (is (str/includes? prompt "Resolution attempt 1 of 3")))))

(deftest build-resolution-prompt-later-iteration-test
  (testing "Iteration count increments as the loop advances; budget
            stays fixed."
    (let [prompt (build-resolution-prompt sample-conflict-input 2 5)]
      (is (str/includes? prompt "Resolution attempt 3 of 5")))))

;------------------------------------------------------------------------------ Pure-data: task builder

(deftest build-resolution-task-shape-test
  (testing "Task map carries the description, type :merge-resolution,
            existing-files for the conflicted paths, and the worktree
            path. Existing-files lets the implementer's prompt builder
            slurp the conflicted files into the LLM's context."
    (let [task (build-resolution-task sample-conflict-input
                                      "/tmp/wt" 0 5)]
      (is (string? (:task/description task)))
      (is (= :merge-resolution (:task/type task)))
      (is (= ["src/conflict.txt" "src/other.clj"] (:task/existing-files task))
          "existing-files mirrors the conflict paths in declaration order")
      (is (= "/tmp/wt" (:task/worktree-path task))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.merge-resolution-agent-test)

  :leave-this-here)
