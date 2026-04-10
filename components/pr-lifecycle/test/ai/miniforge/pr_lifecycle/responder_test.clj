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

(ns ai.miniforge.pr-lifecycle.responder-test
  "Unit tests for PR comment responder.
   Tests URL parsing, comment filtering, spec generation, and orchestration
   logic. Does NOT test GitHub API calls or git operations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]
   [ai.miniforge.pr-lifecycle.responder :as responder]))

;------------------------------------------------------------------------------ Layer 0
;; PR URL parsing

(deftest parse-pr-url-test
  (testing "parses standard GitHub PR URL"
    (let [result (responder/parse-pr-url "https://github.com/miniforge-ai/miniforge/pull/483")]
      (is (= "miniforge-ai" (:owner result)))
      (is (= "miniforge" (:repo result)))
      (is (= 483 (:number result)))))

  (testing "parses URL with trailing path"
    (let [result (responder/parse-pr-url "https://github.com/org/repo/pull/42/files")]
      (is (= 42 (:number result)))))

  (testing "returns nil for non-PR URL"
    (is (nil? (responder/parse-pr-url "https://github.com/org/repo")))
    (is (nil? (responder/parse-pr-url "not a url")))
    (is (nil? (responder/parse-pr-url nil)))))

;------------------------------------------------------------------------------ Layer 1
;; Comment filtering

(def review-comment
  {:comment/id 123
   :comment/body "Extract this function"
   :comment/author "reviewer"
   :comment/path "src/foo.clj"
   :comment/line 42
   :comment/type :review-comment})

(def bot-comment
  {:comment/id 456
   :comment/body "CI passed"
   :comment/author "github-actions[bot]"
   :comment/path "src/foo.clj"
   :comment/type :review-comment})

(def issue-comment
  {:comment/id 789
   :comment/body "General feedback"
   :comment/author "reviewer"
   :comment/type :issue-comment})

(deftest filter-actionable-comments-test
  (testing "keeps human review comments"
    (let [result (responder/filter-actionable-comments [review-comment])]
      (is (= 1 (count result)))))

  (testing "filters bot comments"
    (let [result (responder/filter-actionable-comments [review-comment bot-comment])]
      (is (= 1 (count result)))
      (is (= "reviewer" (:comment/author (first result))))))

  (testing "filters issue comments (not inline review)"
    (let [result (responder/filter-actionable-comments [review-comment issue-comment])]
      (is (= 1 (count result)))))

  (testing "returns empty for no actionable comments"
    (is (empty? (responder/filter-actionable-comments [bot-comment issue-comment])))))

;------------------------------------------------------------------------------ Layer 2
;; Comment grouping

(def comment-on-bar
  (assoc review-comment :comment/id 124 :comment/path "src/bar.clj"))

(def second-comment-on-foo
  (assoc review-comment :comment/id 125 :comment/body "Also fix this"))

(deftest group-comments-by-file-test
  (testing "groups comments by path"
    (let [groups (responder/group-comments-by-file
                  [review-comment comment-on-bar second-comment-on-foo])]
      (is (= 2 (count groups)))
      (let [foo-group (first (filter #(= "src/foo.clj" (:path %)) groups))]
        (is (= 2 (count (:comment-ids foo-group))))
        (is (= #{123 125} (set (:comment-ids foo-group)))))))

  (testing "skips comments without path"
    (let [no-path (dissoc review-comment :comment/path)
          groups (responder/group-comments-by-file [no-path])]
      (is (empty? groups)))))

;------------------------------------------------------------------------------ Layer 3
;; Fix spec generation

(deftest build-fix-spec-test
  (testing "generates valid workflow spec"
    (let [group {:path "src/foo.clj"
                 :description "Extract this function"
                 :comment-ids [123]}
          spec (responder/build-fix-spec group)]
      (is (= :canonical-sdlc (:workflow/type spec)))
      (is (= :fix (get-in spec [:spec/intent :type])))
      (is (= ["src/foo.clj"] (get-in spec [:spec/intent :scope])))
      (is (clojure.string/includes? (:spec/description spec) "Extract this function")))))

;------------------------------------------------------------------------------ Layer 4
;; Orchestration

(deftest respond-to-comments-no-comments-test
  (testing "returns clean result when no comments found"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _] (dag/ok {:comments []}))]
      (let [result (responder/respond-to-comments!
                    "https://github.com/org/repo/pull/1"
                    "/tmp/worktree"
                    (fn [_ _] {})
                    (fn [] true)
                    {})]
        (is (= 0 (:comments-found result)))
        (is (= 0 (:files-processed result)))
        (is (false? (:pushed? result)))))))

(deftest respond-to-comments-with-comments-test
  (testing "processes comments, runs fix, and reports results"
    (let [fix-calls (atom [])]
      (with-redefs [poller/fetch-pr-comments
                    (fn [_ _] (dag/ok {:comments [review-comment]}))
                    github/reply-to-comment
                    (fn [_ _ _ _] (dag/ok {:reply-id 1}))
                    github/get-thread-id
                    (fn [_ _ _] (dag/ok {:thread-id "PRRT_123"}))
                    github/resolve-conversation
                    (fn [_ _] (dag/ok {:resolved true}))]
        (let [result (responder/respond-to-comments!
                      "https://github.com/org/repo/pull/1"
                      "/tmp/worktree"
                      (fn [spec _opts]
                        (swap! fix-calls conj spec)
                        {:execution/status :completed})
                      (fn [] true)
                      {})]
          (is (= 1 (:comments-found result)))
          (is (= 1 (:files-processed result)))
          (is (= 1 (count @fix-calls)))
          (is (true? (:pushed? result)))
          (is (true? (get-in result [:fixes 0 :succeeded?])))
          (is (true? (get-in result [:fixes 0 :replied?])))
          (is (true? (get-in result [:fixes 0 :resolved?]))))))))

(deftest respond-to-comments-fix-failure-test
  (testing "reports failure and skips reply when fix fails"
    (with-redefs [poller/fetch-pr-comments
                  (fn [_ _] (dag/ok {:comments [review-comment]}))]
      (let [result (responder/respond-to-comments!
                    "https://github.com/org/repo/pull/1"
                    "/tmp/worktree"
                    (fn [_ _] {:execution/status :failed :error "compile error"})
                    (fn [] true)
                    {})]
        (is (= 1 (:comments-found result)))
        (is (false? (get-in result [:fixes 0 :succeeded?])))
        (is (nil? (get-in result [:fixes 0 :replied?])))
        (is (false? (:pushed? result)))))))

(deftest respond-to-comments-invalid-url-test
  (testing "throws on unparseable PR URL"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Could not parse"
          (responder/respond-to-comments! "not-a-url" "/tmp" (fn [_ _] {}) (fn [] true) {})))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.pr-lifecycle.responder-test)

  :leave-this-here)
