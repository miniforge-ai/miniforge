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

(ns ai.miniforge.pr-lifecycle.conflict-resolution-test
  "Stage 3 slice 3a: tests for the pure-data classifiers and the
   conflict-input builder. Slices 3b and 3c add tests for git-
   plumbing and the orchestrator entry point respectively."
  (:require
   [ai.miniforge.pr-lifecycle.conflict-resolution :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Test data

(def ^:private sample-pr
  {:pr/id        123
   :pr/branch    "feat/widget"
   :pr/base      "main"
   :pr/head-sha  "abc1234deadbeef"
   :pr/base-sha  "def5678cafebabe"})

;------------------------------------------------------------------------------ classify-merge-state

(deftest classify-merge-state-recognizes-conflicting-test
  (testing "GitHub mergeable=CONFLICTING and/or mergeStateStatus=DIRTY
            classify as :conflicting — the only signals the §6.4
            hook should auto-engage on."
    (is (= :conflicting
           (sut/classify-merge-state
            "{\"mergeable\":\"CONFLICTING\",\"mergeStateStatus\":\"DIRTY\"}")))
    (is (= :conflicting
           (sut/classify-merge-state
            "{\"mergeable\":\"UNKNOWN\",\"mergeStateStatus\":\"DIRTY\"}"))
        "DIRTY alone is enough — GitHub flags conflicts that way")))

(deftest classify-merge-state-recognizes-mergeable-test
  (testing "Clean mergeable PRs classify as :mergeable so the
            caller's auto-engage check short-circuits."
    (is (= :mergeable
           (sut/classify-merge-state
            "{\"mergeable\":\"MERGEABLE\",\"mergeStateStatus\":\"CLEAN\"}")))))

(deftest classify-merge-state-defaults-to-unknown-test
  (testing "Anything we can't confidently classify falls into
            :unknown. Nil / blank / weird payloads must not auto-
            trigger resolution — the caller polls or surfaces
            instead."
    (is (= :unknown (sut/classify-merge-state nil)))
    (is (= :unknown (sut/classify-merge-state "")))
    (is (= :unknown (sut/classify-merge-state "  ")))
    (is (= :unknown
           (sut/classify-merge-state
            "{\"mergeable\":\"UNKNOWN\",\"mergeStateStatus\":\"PENDING\"}"))
        "GitHub still computing → :unknown, not :mergeable")))

;------------------------------------------------------------------------------ build-pr-conflict-input

(deftest build-pr-conflict-input-shape-test
  (testing "Two-parent shape per spec §6.4: PR branch first (order 0),
            base second (order 1). Order is fixed so replays produce
            the same input-key. Conflicts carry the placeholder
            stages vector that build-resolution-prompt joins with '/'
            for display."
    (let [paths ["src/conflict.txt" "src/other.clj"]
          input (sut/build-pr-conflict-input sample-pr paths)
          parents (:merge/parents input)]
      (is (= "pr-123" (:task/id input)))
      (is (= 2 (count parents))
          "always two-parent for the §6.4 surface")
      (is (= [:pr-branch :pr-base] (mapv :task/id parents)))
      (is (= [0 1] (mapv :order parents))
          "PR branch is order 0; base is order 1")
      (is (= ["feat/widget" "main"] (mapv :branch parents)))
      (is (= ["abc1234deadbeef" "def5678cafebabe"]
             (mapv :commit-sha parents)))
      (is (= :git-merge (:merge/strategy input)))
      (is (str/starts-with? (:merge/input-key input) "pr-123-")
          "input-key carries the head+base pair so different syncs
           produce different keys — no idempotency cache collisions
           across distinct main-tip moves")
      (is (= 2 (count (:merge/conflicts input))))
      (is (= ["src/conflict.txt" "src/other.clj"]
             (mapv :path (:merge/conflicts input)))))))

(deftest build-pr-conflict-input-empty-paths-test
  (testing "Zero conflict paths still produces a valid shape with
            an empty :merge/conflicts vector. Caller's no-conflicts
            check is what prevents the dead-end resolution
            invocation; the builder itself is total."
    (let [input (sut/build-pr-conflict-input sample-pr [])]
      (is (= [] (:merge/conflicts input))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.pr-lifecycle.conflict-resolution-test)

  :leave-this-here)
