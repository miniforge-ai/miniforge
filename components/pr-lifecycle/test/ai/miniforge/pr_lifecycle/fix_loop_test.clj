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

(ns ai.miniforge.pr-lifecycle.fix-loop-test
  "Unit tests for fix loop context building and prompt generation.

   Tests fix context creation, prompt building for each failure type,
   and resolve-comment-thread skip logic. Does NOT test actual
   generation or git operations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]))

;------------------------------------------------------------------------------ Test Data

(def test-task
  {:task/id (random-uuid)
   :task/title "Implement feature X"
   :task/acceptance-criteria ["Tests pass" "No lint errors"]
   :task/constraints ["No breaking changes"]})

(def test-pr-info
  {:pr/id 123
   :pr/url "https://github.com/org/repo/pull/123"
   :pr/branch "feat/feature-x"
   :pr/head-sha "abc123"})

;------------------------------------------------------------------------------ Fix Context Creation

(deftest create-fix-context-ci-failure-test
  (testing "CI failure context has correct structure"
    (let [failure {:type :ci-failure
                   :summary "2 test failures"
                   :test-failures ["FAIL in (test-foo)"]
                   :lint-errors []
                   :build-errors []}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= (:task/id test-task) (:fix/task-id ctx)))
      (is (= :ci-failure (:fix/type ctx)))
      (is (= test-pr-info (:fix/pr-info ctx)))
      (is (= "2 test failures" (:fix/failure-summary ctx)))
      (is (= ["FAIL in (test-foo)"] (:fix/failing-tests ctx)))
      (is (= ["Tests pass" "No lint errors"] (:fix/acceptance-criteria ctx)))
      (is (= ["No breaking changes"] (:fix/constraints ctx)))
      (is (vector? (:fix/previous-fixes ctx)))
      (is (inst? (:fix/created-at ctx))))))

(deftest create-fix-context-review-changes-test
  (testing "Review changes context includes comments"
    (let [comments [{:body "Fix the null check" :path "src/foo.clj"}]
          failure {:type :review-changes
                   :summary "1 requested change"
                   :comments comments}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= :review-changes (:fix/type ctx)))
      (is (= comments (:fix/review-comments ctx))))))

(deftest create-fix-context-conflict-test
  (testing "Conflict context includes conflict files"
    (let [failure {:type :conflict
                   :summary "2 files with conflicts"
                   :conflict-files ["src/a.clj" "src/b.clj"]}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= :conflict (:fix/type ctx)))
      (is (= ["src/a.clj" "src/b.clj"] (:fix/conflict-files ctx))))))

(deftest create-fix-context-with-previous-fixes-test
  (testing "Previous fixes are included in context"
    (let [failure {:type :ci-failure :summary "test failures"}
          prev [{:attempt 1 :error "first try failed"}]
          ctx (fix/create-fix-context test-task test-pr-info failure
                                       :previous-fixes prev)]
      (is (= prev (:fix/previous-fixes ctx))))))

(deftest create-fix-context-comment-metadata-test
  (testing "Comment metadata for conversation resolution"
    (let [failure {:type :review-changes
                   :summary "1 change"
                   :comment-id 456
                   :parent-pr-number 100}
          ctx (fix/create-fix-context test-task test-pr-info failure)]
      (is (= 456 (:fix/comment-id ctx)))
      (is (= 100 (:fix/parent-pr-number ctx))))))

;------------------------------------------------------------------------------ Prompt Building

(deftest build-fix-prompt-ci-failure-test
  (testing "CI failure prompt includes relevant info"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :ci-failure
               :fix/failure-summary "2 test failures"
               :fix/affected-files #{"src/foo.clj"}
               :fix/failing-tests ["FAIL in (test-foo)" "FAIL in (test-bar)"]
               :fix/lint-errors ["src/foo.clj:10: warning"]
               :fix/build-errors []}
          prompt (fix/build-fix-prompt ctx)]
      (is (string? prompt))
      (is (str/includes? prompt "Fix CI failures"))
      (is (str/includes? prompt "2 test failures"))
      (is (str/includes? prompt "FAIL in (test-foo)"))
      (is (str/includes? prompt "warning")))))

(deftest build-fix-prompt-review-changes-test
  (testing "Review changes prompt includes requested changes"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :review-changes
               :fix/failure-summary "1 change"
               :fix/affected-files #{}
               :fix/review-comments [{:file "src/a.clj" :line 10 :change "Fix null check"}]}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Address review feedback"))
      (is (str/includes? prompt "Fix null check")))))

(deftest build-fix-prompt-conflict-test
  (testing "Conflict prompt mentions conflicting files"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :conflict
               :fix/failure-summary "2 conflicts"
               :fix/affected-files #{"src/a.clj" "src/b.clj"}}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Resolve merge conflicts"))
      (is (str/includes? prompt "src/a.clj")))))

(deftest build-fix-prompt-default-type-test
  (testing "Unknown fix type produces generic prompt"
    (let [ctx {:fix/task-id (random-uuid)
               :fix/type :unknown
               :fix/failure-summary "something broke"
               :fix/affected-files #{}}
          prompt (fix/build-fix-prompt ctx)]
      (is (str/includes? prompt "Fix issues"))
      (is (str/includes? prompt "something broke")))))

;------------------------------------------------------------------------------ Resolve Comment Thread (Skip Logic)

(deftest resolve-comment-thread-skips-without-metadata-test
  (testing "Skips resolution when comment-id is missing"
    (let [ctx {:fix/comment-id nil :fix/parent-pr-number 123}
          result (fix/resolve-comment-thread "/tmp" ctx 456 nil)]
      (is (dag/ok? result))
      (is (true? (:skipped (:data result)))))))

(deftest resolve-comment-thread-skips-when-disabled-test
  (testing "Skips resolution when auto-resolve is false"
    (let [ctx {:fix/comment-id 789 :fix/parent-pr-number 123}
          result (fix/resolve-comment-thread "/tmp" ctx 456 nil
                                              :auto-resolve false)]
      (is (dag/ok? result))
      (is (true? (:skipped (:data result)))))))