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
(ns ai.miniforge.bb-test-runner.stable-derived-test
  "Unit tests for `stable-derived`."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-test-runner.stable-derived :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-stable-tag-globs-covers-supported-history
  (testing "stable tag globs cover both historical naming schemes"
    (is (= ["stable-*" "stable/*"]
           (sut/stable-tag-globs)))))

(deftest ^{:stratum 0} test-stable-tags-present-when-tag-seq-non-empty
  (testing "any stable tag list enables since-stable scope"
    (is (true? (sut/stable-tags-present? ["stable-20260506"])))
    (is (true? (sut/stable-tags-present? ["stable/main-2026-02-27"])))))

(deftest ^{:stratum 0} test-stable-tags-absent-when-tag-seq-empty
  (testing "empty stable-tag list forces full-suite fallback"
    (is (false? (sut/stable-tags-present? [])))))

(deftest ^{:stratum 0} test-stable-tags-absent-when-input-has-no-recognized-stable-tags
  (testing "non-stable and blank tag entries do not enable since-stable scope"
    (is (false? (sut/stable-tags-present? ["release-2026-05-10" "feature/foo"])))
    (is (false? (sut/stable-tags-present? ["" "   " nil])))))

(deftest ^{:stratum 0} test-parse-project-selector-supports-poly-and-env-shapes
  (testing "project selectors accept poly syntax and env-friendly delimiters"
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "project:miniforge:miniforge-core")))
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "miniforge:miniforge-core")))
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-selector "miniforge,miniforge-core")))))

(deftest ^{:stratum 0} test-format-project-selector-renders-poly-project-arg
  (testing "project vectors render as a Polylith project selector"
    (is (= "project:miniforge:miniforge-core"
           (sut/format-project-selector ["miniforge" "miniforge-core"])))))

(deftest ^{:stratum 0} test-changed-projects-command-matches-polylith-ws-query
  (testing "stable-derived diagnostics query the native changed project set"
    (is (= ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
            "skip:dev" "color-mode:none"]
           (sut/changed-projects-command)))))

(deftest ^{:stratum 0} test-changed-projects-since-stable-command-inserts-after-change-marker
  (testing "stable-derived diagnostics insert since:stable next to the Polylith change query"
    (is (= ["clojure" "-M:poly" "ws" "get:changes:changed-or-affected-projects"
            "since:stable" "skip:dev" "color-mode:none"]
           (sut/changed-projects-since-stable-command)))))

(deftest ^{:stratum 0} test-parse-project-list-output-reads-edn-vectors
  (testing "Polylith ws output parses into project names"
    (is (= ["miniforge" "miniforge-core"]
           (sut/parse-project-list-output "[\"miniforge\" \"miniforge-core\"]")))))

(deftest ^{:stratum 0} test-parse-project-list-output-returns-error-data-for-invalid-output
  (testing "invalid Polylith ws output is returned as error data"
    (let [result (sut/parse-project-list-output "{:not :a-project-list}")]
      (is (false? (:ok? result)))
      (is (= :bb-test-runner/invalid-project-list
             (get-in result [:error :code])))))
  (testing "unparseable Polylith ws output is returned as error data"
    (let [result (sut/parse-project-list-output "[")]
      (is (false? (:ok? result)))
      (is (= :bb-test-runner/invalid-project-list
             (get-in result [:error :code]))))))

(deftest ^{:stratum 0} test-sanitize-git-worktree-env-strips-worktree-vars
  (testing "git worktree vars do not leak into child test processes"
    (is (= {"PATH" "/usr/bin" "FOO" "bar"}
           (sut/sanitize-git-worktree-env
            {"PATH" "/usr/bin"
             "FOO" "bar"
             "GIT_INDEX_FILE" "/tmp/index"
             "GIT_DIR" "/tmp/git"
             "GIT_WORK_TREE" "/tmp/worktree"
             "GIT_COMMON_DIR" "/tmp/common"})))))

(deftest ^{:stratum 0} test-heartbeat-seconds-defaults-on-missing-env
  (testing "missing env key falls back to the default heartbeat"
    (is (= 30 (sut/heartbeat-seconds {})))))

(deftest ^{:stratum 0} test-heartbeat-seconds-accepts-positive-env-value
  (testing "positive configured heartbeat is honored"
    (is (= 45 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "45"})))))

(deftest ^{:stratum 0} test-heartbeat-seconds-rejects-invalid-env-value
  (testing "invalid heartbeat values fall back to the default"
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "abc"})))
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "0"})))
    (is (= 30 (sut/heartbeat-seconds {"MINIFORGE_TEST_HEARTBEAT_SECONDS" "-5"})))))

(deftest ^{:stratum 0} test-order-projects-supports-declared-and-backward-order
  (testing "diagnostic ordering keeps stable sequences controllable"
    (is (= ["a" "b" "c"]
           (sut/order-projects ["a" "b" "c"] {:order :declared})))
    (is (= ["c" "b" "a"]
           (sut/order-projects ["a" "b" "c"] {:direction :back})))))

(deftest ^{:stratum 0} test-order-projects-random-order-is-deterministic-for-a-seed
  (testing "random ordering is stable for the same input seed"
    (is (= ["a" "c" "e" "d" "b"]
           (sut/order-projects
            ["a" "b" "c" "d" "e"]
            {:order :random
             :seed 17})))))

(deftest ^{:stratum 0} test-expand-project-groups-doubles-prefix-size
  (testing "additive expansion doubles until the full set is included"
    (is (= [["a"] ["a" "b"] ["a" "b" "c" "d"] ["a" "b" "c" "d" "e"]]
           (sut/expand-project-groups ["a" "b" "c" "d" "e"] 1))))
  (testing "empty project input yields an empty expansion plan"
    (is (= []
           (sut/expand-project-groups [] 1)))))

(deftest ^{:stratum 0} test-bisect-project-groups-partitions-breadth-first
  (testing "bisect produces contiguous binary groups"
    (is (= [["a" "b"] ["c" "d"] ["a"] ["b"] ["c"] ["d"]]
           (sut/bisect-project-groups ["a" "b" "c" "d"]))))
  (testing "larger project sets keep a true breadth-first traversal order"
    (is (= [["a" "b" "c" "d"]
            ["e" "f" "g" "h"]
            ["a" "b"]
            ["c" "d"]
            ["e" "f"]
            ["g" "h"]
            ["a"]
            ["b"]
            ["c"]
            ["d"]
            ["e"]
            ["f"]
            ["g"]
            ["h"]]
           (sut/bisect-project-groups ["a" "b" "c" "d" "e" "f" "g" "h"])))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-test-runner.stable-derived-test)

  :leave-this-here)
