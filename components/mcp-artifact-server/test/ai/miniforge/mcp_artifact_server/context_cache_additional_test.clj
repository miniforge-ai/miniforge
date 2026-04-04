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

(ns ai.miniforge.mcp-artifact-server.context-cache-additional-test
  "Additional unit tests for context cache — filter helpers and edge cases."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ai.miniforge.mcp-artifact-server.context-cache :as cache]))

;------------------------------------------------------------------------------ Fixtures

(defn with-clean-cache [f]
  (cache/reset-state!)
  (try (f) (finally (cache/reset-state!))))

(use-fixtures :each with-clean-cache)

;------------------------------------------------------------------------------ Layer 0
;; filter-cached-files

(deftest filter-cached-files-path-test
  (testing "target-path selects single file from map"
    (let [files {"a.clj" "(ns a)" "b.clj" "(ns b)"}
          result (cache/filter-cached-files files "a.clj" nil)]
      (is (= {"a.clj" "(ns a)"} result))))

  (testing "target-path for absent key returns empty map"
    (let [files {"a.clj" "content"}
          result (cache/filter-cached-files files "missing.clj" nil)]
      (is (empty? result)))))

(deftest filter-cached-files-glob-test
  (testing "glob-filter selects matching files"
    (let [files {"src/a.clj" "a" "src/b.clj" "b" "test/a_test.clj" "t"}
          result (cache/filter-cached-files files nil "src/*.clj")]
      (is (= 2 (count result)))
      (is (contains? result "src/a.clj"))
      (is (contains? result "src/b.clj"))))

  (testing "glob-filter with ** matches nested paths"
    (let [files {"src/a.clj" "a" "src/deep/b.clj" "b" "test/t.clj" "t"}
          result (cache/filter-cached-files files nil "src/**/*.clj")]
      (is (= 2 (count result))))))

(deftest filter-cached-files-no-filter-test
  (testing "no path or glob returns all files"
    (let [files {"a.clj" "a" "b.clj" "b"}
          result (cache/filter-cached-files files nil nil)]
      (is (= files result)))))

;------------------------------------------------------------------------------ Layer 0
;; Edge cases for pure helpers

(deftest estimate-tokens-edge-cases-test
  (testing "single character = 1 token"
    (is (= 1 (cache/estimate-tokens "x"))))

  (testing "exactly 4 chars = 1 token"
    (is (= 1 (cache/estimate-tokens "abcd"))))

  (testing "5 chars = 2 tokens (ceil)"
    (is (= 2 (cache/estimate-tokens "abcde"))))

  (testing "large string"
    (let [s (apply str (repeat 1000 "x"))]
      (is (= 250 (cache/estimate-tokens s))))))

(deftest apply-offset-limit-edge-cases-test
  (testing "offset beyond content returns empty"
    (is (= "" (cache/apply-offset-limit "line0\nline1" 10 nil))))

  (testing "limit of 0 returns empty"
    (is (= "" (cache/apply-offset-limit "line0\nline1" nil 0))))

  (testing "single line content"
    (is (= "only" (cache/apply-offset-limit "only" 0 1))))

  (testing "offset 0 is same as nil offset"
    (let [content "a\nb\nc"]
      (is (= (cache/apply-offset-limit content nil nil)
             (cache/apply-offset-limit content 0 nil))))))

(deftest glob-matches-edge-cases-test
  (testing "empty pattern matches empty path"
    (is (cache/glob-matches? "" "")))

  (testing "pattern with no wildcards is exact match"
    (is (cache/glob-matches? "exact.txt" "exact.txt"))
    (is (not (cache/glob-matches? "exact.txt" "other.txt"))))

  (testing "single star does not cross directory boundary"
    (is (not (cache/glob-matches? "*.clj" "src/core.clj"))))

  (testing "double star at start matches any depth"
    (is (cache/glob-matches? "**/test.clj" "a/b/c/test.clj"))))

(deftest grep-file-multiple-matches-test
  (testing "returns all matching lines"
    (let [content "def foo\ndef bar\nlet x = 1\ndef baz"
          results (cache/grep-file "test.clj" content "def")]
      (is (= 3 (count results)))
      (is (= [1 2 4] (mapv :line-number results))))))

;------------------------------------------------------------------------------ Layer 1
;; Tool handler edge cases

(deftest handle-context-read-empty-file-test
  (testing "empty cached file returns empty string"
    (swap! cache/cache-state assoc-in [:files "empty.txt"] "")
    (let [result (cache/handle-context-read {"path" "empty.txt"})]
      (is (= "" (get-in result [:content 0 :text]))))))

(deftest handle-context-grep-multiple-files-test
  (testing "grep searches across all cached files when no filters"
    (swap! cache/cache-state assoc-in [:files "a.clj"] "(defn alpha [])")
    (swap! cache/cache-state assoc-in [:files "b.clj"] "(defn beta [])")
    (swap! cache/cache-state assoc-in [:files "c.clj"] "(defn gamma [])")
    (let [result (cache/handle-context-grep {"pattern" "defn"})
          text (get-in result [:content 0 :text])]
      ;; All three files should have matches
      (is (re-find #"a\.clj" text))
      (is (re-find #"b\.clj" text))
      (is (re-find #"c\.clj" text)))))

(deftest handle-context-glob-deep-match-test
  (testing "** glob pattern matches deeply nested cached paths"
    (swap! cache/cache-state assoc-in [:files "src/a/b/c/deep.clj"] "x")
    (swap! cache/cache-state assoc-in [:files "src/shallow.clj"] "y")
    (let [result (cache/handle-context-glob {"pattern" "src/**/*.clj"})
          text (get-in result [:content 0 :text])]
      (is (re-find #"deep\.clj" text))
      (is (re-find #"shallow\.clj" text)))))