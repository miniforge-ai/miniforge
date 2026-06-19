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

(ns ai.miniforge.agent.reviewer.prompts-test
  "Tests for `ai.miniforge.agent.reviewer.prompts` — extracted from
   `ai.miniforge.agent.reviewer-test` as part of the PR-E decomposition.

   Covers the pure-function prompt surface: `format-artifact-for-review`
   shape variants, `build-review-prompt` section rendering including
   the `## Scope` boundary, and the required-scope contract."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.prompts :as prompts]
   [ai.miniforge.agent.reviewer.prompts :as reviewer-prompts]))

;------------------------------------------------------------------------------ format-artifact-for-review

(deftest format-artifact-for-review-test
  (testing "CodeArtifact with :code/files renders each file as a markdown block"
    (let [out (reviewer-prompts/format-artifact-for-review
                {:code/files [{:path "src/a.clj" :content "(def x 1)" :action :create}
                              {:path "src/b.clj" :content "(def y 2)" :action :modify}]})]
      (is (re-find #"### src/a\.clj \(create\)" out))
      (is (re-find #"### src/b\.clj \(modify\)" out))
      (is (re-find #"\(def x 1\)" out))
      (is (re-find #"\(def y 2\)" out))))

  (testing "missing :action defaults to :unknown"
    (let [out (reviewer-prompts/format-artifact-for-review
                {:code/files [{:path "p" :content "c"}]})]
      (is (re-find #"\(unknown\)" out))))

  (testing "plain string passes through unchanged"
    (is (= "literal artifact" (reviewer-prompts/format-artifact-for-review "literal artifact"))))

  (testing "other shapes round-trip via pr-str"
    (is (= "{:k 1}" (reviewer-prompts/format-artifact-for-review {:k 1})))))

;------------------------------------------------------------------------------ format-artifact-manifest (N12 §5 shed path)

(deftest format-artifact-manifest-test
  (testing "CodeArtifact renders each file as path + action + size, omitting the body"
    (let [out (reviewer-prompts/format-artifact-manifest
                {:code/files [{:path "src/a.clj" :content "(def x 1)" :action :create}
                              {:path "src/b.clj" :content "(def y 2)" :action :modify}]})]
      (is (re-find #"src/a\.clj \(create\) \(9 chars\)" out))
      (is (re-find #"src/b\.clj \(modify\) \(9 chars\)" out))
      ;; the inlined body is gone — only the manifest line remains
      (is (not (re-find #"\(def x 1\)" out)))
      (is (not (re-find #"```" out)))
      ;; and the reviewer is told how to fetch a body on demand
      (is (re-find #"context_read" out))))

  (testing "missing :action defaults to :unknown"
    (is (re-find #"\(unknown\)"
                 (reviewer-prompts/format-artifact-manifest
                   {:code/files [{:path "p" :content "c"}]}))))

  (testing "non-CodeArtifact shapes have nothing to shed → full rendering"
    (is (= "literal" (reviewer-prompts/format-artifact-manifest "literal")))
    (is (= "{:k 1}" (reviewer-prompts/format-artifact-manifest {:k 1})))))

;------------------------------------------------------------------------------ build-review-prompt — manifest artifact formatter

(deftest render-template-preserves-raw-prompt-content-test
  (testing "Selmer prompt rendering does not HTML-escape code, diffs, markdown, or EDN"
    (is (= "Review <src/a.clj> && {:ok? true}"
           (prompts/render-template "Review {{content}}"
                                    {:content "<src/a.clj> && {:ok? true}"})))))

(deftest build-review-prompt-manifest-arity-test
  (let [input {:task/title "T"
               :task/scope ["src"]
               :task/artifact {:code/files [{:path "src/a.clj"
                                             :content "(def x 1)"
                                             :action :modify}]}}]
    (testing "default arity inlines the file body"
      (let [prompt (reviewer-prompts/build-review-prompt input)]
        (is (re-find #"\(def x 1\)" prompt))))

    (testing "manifest arity sheds the body but keeps the path + context_read hint"
      (let [prompt (reviewer-prompts/build-review-prompt
                     input reviewer-prompts/format-artifact-manifest)]
        (is (re-find #"## Code to Review" prompt))
        (is (re-find #"src/a\.clj" prompt))
        (is (re-find #"context_read" prompt))
        (is (not (re-find #"\(def x 1\)" prompt)))))))

;------------------------------------------------------------------------------ build-review-prompt — section rendering

(deftest build-review-prompt-renders-scope-section-test
  (testing "Scope section appears with bulleted paths when scope is resolved"
    (let [prompt (reviewer-prompts/build-review-prompt
                   {:task/title "Test"
                    :task/intent {:type :refactor
                                  :scope ["components/foo/src" "components/bar/src"]}
                    :task/artifact {:code/files []}})]
      (is (re-find #"## Scope" prompt))
      (is (re-find #"- components/foo/src" prompt))
      (is (re-find #"- components/bar/src" prompt))
      (is (re-find #":review/out-of-scope-observations" prompt)
          "must instruct the LLM where to put out-of-scope findings")))

  (testing "build-review-prompt raises when no scope signal is present"
    ;; PR #1039: missing scope is a programmer error, not a quiet fallback.
    (is (thrown? IllegalArgumentException
                 (reviewer-prompts/build-review-prompt
                   {:task/title "Test"
                    :task/intent "free-form prose"
                    :task/artifact {:code/files []}})))))

(deftest build-review-prompt-omits-empty-sections-test
  (let [prompt (reviewer-prompts/build-review-prompt
                 {:task/title ""
                  :task/description ""
                  :task/intent ""
                  :task/constraints ""
                  :task/scope ["src"]
                  :task/artifact {:code/files []}})]
    (testing "blank title / description / intent / constraints don't emit their sections"
      (is (not (re-find #"## Task:"        prompt)))
      (is (not (re-find #"## Description"  prompt)))
      (is (not (re-find #"## Intent"       prompt)))
      (is (not (re-find #"## Constraints"  prompt))))
    (testing "core sections are still present"
      (is (re-find #"## Scope"          prompt))
      (is (re-find #"## Code to Review" prompt))))

  (testing "whitespace-only title / description are treated as blank (no empty section header)"
    ;; Regression: pre-fix `(when (seq title))` rendered "## Task: " for
    ;; a whitespace-only title because non-empty strings are truthy under
    ;; `seq`. All four optional sections now use str/blank? for parity.
    (let [prompt (reviewer-prompts/build-review-prompt
                   {:task/title "   "
                    :task/description "\t\n  "
                    :task/intent "   "
                    :task/constraints "  \n"
                    :task/scope ["src"]
                    :task/artifact {:code/files []}})]
      (is (not (re-find #"## Task:"       prompt)))
      (is (not (re-find #"## Description" prompt)))
      (is (not (re-find #"## Intent"      prompt)))
      (is (not (re-find #"## Constraints" prompt))))))

(deftest build-review-prompt-includes-tests-section-test
  (let [prompt (reviewer-prompts/build-review-prompt
                 {:task/title "T"
                  :task/scope ["src"]
                  :task/artifact {:code/files []}
                  :task/tests "all 12 tests passed"})]
    (is (re-find #"## Test Results" prompt))
    (is (re-find #"all 12 tests passed" prompt))))
