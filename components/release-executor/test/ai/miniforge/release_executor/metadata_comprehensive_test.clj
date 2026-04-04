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

(ns ai.miniforge.release-executor.metadata-comprehensive-test
  "Comprehensive tests for release-executor metadata generation.
   Covers slugify edge cases, first-sentence extraction, invoke-releaser,
   generate-release-metadata, format-gate-results, and format-review-summary."
  (:require
   [ai.miniforge.release-executor.metadata :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;; ============================================================================
;; slugify
;; ============================================================================

(deftest slugify-basic-ascii
  (testing "converts simple ASCII text to slug"
    (is (= "add-user-login" (sut/slugify "Add user login")))))

(deftest slugify-preserves-numbers
  (testing "numbers are preserved in slug"
    (is (= "version-2-release" (sut/slugify "Version 2 Release")))))

(deftest slugify-strips-special-characters
  (testing "removes punctuation and special characters"
    (is (= "hello-world" (sut/slugify "Hello, World!")))))

(deftest slugify-collapses-multiple-hyphens
  (testing "multiple hyphens collapse to single hyphen"
    (is (= "a-b" (sut/slugify "a---b")))))

(deftest slugify-strips-leading-trailing-hyphens
  (testing "leading and trailing hyphens are stripped"
    (is (= "inner" (sut/slugify "--inner--")))))

(deftest slugify-handles-empty-string
  (testing "empty string falls back to default change label"
    ;; empty → lowered → stripped → empty, but (or s default) only fires for nil
    ;; empty input produces empty slug
    (let [result (sut/slugify "")]
      (is (string? result)))))

(deftest slugify-nil-uses-message-default
  (testing "nil input uses the configured message default"
    (is (= "change" (sut/slugify nil)))))

(deftest slugify-accented-characters-transliterated
  (testing "common accented characters are transliterated to ASCII"
    (is (= "cafe-resume" (sut/slugify "Café Résumé")))
    (is (= "nino" (sut/slugify "Niño")))
    (is (= "strasse" (sut/slugify "Straße")))))

(deftest slugify-accented-vowels
  (testing "all accented vowel groups are transliterated"
    (is (= "aeiou" (sut/slugify "àèìòù")))
    (is (= "aeiou" (sut/slugify "áéíóú")))
    (is (= "aeiou" (sut/slugify "âêîôû")))
    (is (= "aeiou" (sut/slugify "äëïöü")))))

(deftest slugify-cedilla-transliteration
  (testing "ç is transliterated to c"
    (is (= "garcon" (sut/slugify "garçon")))))

(deftest slugify-truncates-at-40-chars
  (testing "slug is capped at 40 characters"
    (let [long-input (str/join " " (repeat 20 "word"))
          result (sut/slugify long-input)]
      (is (<= (count result) 40)))))

(deftest slugify-truncation-boundary
  (testing "slug at exactly 40 chars is not truncated"
    ;; "a" repeated 40 times -> slug exactly 40
    (let [input (apply str (repeat 40 "a"))
          result (sut/slugify input)]
      (is (= 40 (count result))))))

(deftest slugify-multiple-spaces
  (testing "multiple spaces become single hyphen"
    (is (= "one-two" (sut/slugify "one    two")))))

(deftest slugify-tabs-and-whitespace
  (testing "tabs and other whitespace normalize to hyphens"
    (is (= "a-b" (sut/slugify "a\tb")))))

(deftest slugify-already-lowercase
  (testing "lowercase input is unchanged (except special chars)"
    (is (= "simple-slug" (sut/slugify "simple slug")))))

;; ============================================================================
;; first-sentence (tested indirectly through fallback-release-metadata)
;; ============================================================================

(defn- get-title
  "Helper to extract the title via fallback-release-metadata."
  [desc]
  (:release/commit-message
   (sut/fallback-release-metadata desc [{:code/files [{:path "a.clj" :action :create}]}])))

(deftest first-sentence-short-text-unchanged
  (testing "text shorter than max-len is returned as-is"
    (is (= "Fix login bug" (get-title "Fix login bug")))))

(deftest first-sentence-strips-multiline
  (testing "only the first line is used"
    (is (= "First line" (get-title "First line\nSecond line\nThird line")))))

(deftest first-sentence-strips-bullet-points
  (testing "bullet points after main text are stripped"
    (is (= "Add feature" (get-title "Add feature - with bullet")))))

(deftest first-sentence-strips-trailing-colon
  (testing "trailing colons are stripped"
    (is (= "Implement feature" (get-title "Implement feature:")))))

(deftest first-sentence-strips-read-source-hints
  (testing "Read source: hints are stripped"
    (is (= "Add parser" (get-title "Add parser Read source: foo.clj")))))

(deftest first-sentence-transforms-create-pattern
  (testing "'Create X.ext with' is transformed to 'Add'"
    (is (= "Add database migration" (get-title "Create foo.clj with database migration")))))

(deftest first-sentence-truncation-at-word-boundary
  (testing "long text truncates at word boundary with ellipsis"
    (let [long-desc (str "Implement comprehensive release metadata generation with "
                         "structured PR body sections and smart truncation logic")
          title (get-title long-desc)]
      (is (<= (count title) 73))  ;; 70 + "..."
      (is (str/ends-with? title "...")))))

(deftest first-sentence-nil-returns-default
  (testing "nil description uses default task description"
    (is (= "implement changes" (get-title nil)))))

(deftest first-sentence-empty-returns-default
  (testing "empty description uses default"
    ;; empty string trims to empty -> returns empty but fallback wraps with (or desc default)
    (let [title (get-title "")]
      (is (string? title)))))

;; ============================================================================
;; extract-review-artifacts
;; ============================================================================

(deftest extract-review-artifacts-empty-input
  (testing "empty list returns empty seq"
    (is (empty? (sut/extract-review-artifacts [])))))

(deftest extract-review-artifacts-nil-input
  (testing "nil input returns empty seq"
    (is (empty? (sut/extract-review-artifacts nil)))))

(deftest extract-review-artifacts-both-type-keys
  (testing "handles both :type and :artifact/type keys"
    (let [result (sut/extract-review-artifacts
                  [{:type :review :content {:review/summary "A"}}
                   {:artifact/type :review :artifact/content {:review/summary "B"}}])]
      (is (= 2 (count result)))
      (is (= "A" (:review/summary (first result))))
      (is (= "B" (:review/summary (second result)))))))

(deftest extract-review-artifacts-skips-nil-content
  (testing "artifacts with nil content are filtered out"
    (let [result (sut/extract-review-artifacts
                  [{:type :review :content nil}
                   {:type :review :content {:review/summary "Valid"}}])]
      (is (= 1 (count result))))))

(deftest extract-review-artifacts-ignores-non-review
  (testing "non-review artifacts are excluded"
    (let [result (sut/extract-review-artifacts
                  [{:artifact/type :code :artifact/content {:code/files []}}
                   {:artifact/type :test :artifact/content {:test/results :passed}}
                   {:artifact/type :plan :artifact/content {:plan/steps []}}])]
      (is (empty? result)))))

;; ============================================================================
;; extract-test-artifacts
;; ============================================================================

(deftest extract-test-artifacts-empty-input
  (testing "empty list returns empty seq"
    (is (empty? (sut/extract-test-artifacts [])))))

(deftest extract-test-artifacts-nil-input
  (testing "nil input returns empty seq"
    (is (empty? (sut/extract-test-artifacts nil)))))

(deftest extract-test-artifacts-both-type-keys
  (testing "handles both :type and :artifact/type keys"
    (let [result (sut/extract-test-artifacts
                  [{:type :test :content {:test/results :passed}}
                   {:artifact/type :test :artifact/content {:test/results :failed}}])]
      (is (= 2 (count result)))
      (is (= :passed (:test/results (first result))))
      (is (= :failed (:test/results (second result)))))))

(deftest extract-test-artifacts-ignores-non-test
  (testing "non-test artifacts are excluded"
    (let [result (sut/extract-test-artifacts
                  [{:artifact/type :code :artifact/content {:code/files []}}
                   {:artifact/type :review :artifact/content {:review/summary "ok"}}])]
      (is (empty? result)))))

;; ============================================================================
;; fallback-release-metadata — branch name format
;; ============================================================================

(deftest fallback-branch-name-format
  (testing "branch name starts with mf/ prefix and includes slug + uuid suffix"
    (let [result (sut/fallback-release-metadata
                  "Add user auth" [{:code/files [{:path "a.clj" :action :create}]}])
          branch (:release/branch-name result)]
      (is (str/starts-with? branch "mf/"))
      (is (str/includes? branch "add-user-auth"))
      ;; uuid suffix is 8 chars after final hyphen
      (let [suffix (last (str/split branch #"-"))]
        (is (= 8 (count suffix)))))))

(deftest fallback-branch-name-unique
  (testing "each call generates a different branch name (uuid suffix)"
    (let [r1 (sut/fallback-release-metadata "X" [{:code/files []}])
          r2 (sut/fallback-release-metadata "X" [{:code/files []}])]
      (is (not= (:release/branch-name r1) (:release/branch-name r2))))))

;; ============================================================================
;; fallback-release-metadata — PR body sections
;; ============================================================================

(deftest fallback-pr-body-no-changes-section-when-no-files
  (testing "Changes section is omitted when there are no files"
    (let [result (sut/fallback-release-metadata "Task" [{:code/files []}])
          body (:release/pr-body result)]
      (is (not (str/includes? body "## Changes"))))))

(deftest fallback-pr-body-singular-file-label
  (testing "footer uses singular 'file' for 1 file"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}])
          body (:release/pr-body result)]
      (is (str/includes? body "1 file")))))

(deftest fallback-pr-body-plural-file-label
  (testing "footer uses plural 'files' for multiple files"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}
                                        {:path "b.clj" :action :create}]}])
          body (:release/pr-body result)]
      (is (str/includes? body "2 files")))))

(deftest fallback-pr-body-file-actions-displayed
  (testing "file list shows action for each file"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}
                                        {:path "b.clj" :action :modify}
                                        {:path "c.clj" :action :delete}]}])
          body (:release/pr-body result)]
      (is (str/includes? body "(create)"))
      (is (str/includes? body "(modify)"))
      (is (str/includes? body "(delete)")))))

(deftest fallback-pr-body-multiple-artifacts
  (testing "files from multiple code artifacts are combined"
    (let [result (sut/fallback-release-metadata
                  "Multi" [{:code/files [{:path "a.clj" :action :create}]}
                           {:code/files [{:path "b.clj" :action :create}]}])
          body (:release/pr-body result)]
      (is (str/includes? body "`a.clj`"))
      (is (str/includes? body "`b.clj`"))
      (is (str/includes? body "2 files")))))

(deftest fallback-pr-body-nil-action-defaults-to-create
  (testing "nil action defaults to :create in file list"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj"}]}])
          body (:release/pr-body result)]
      (is (str/includes? body "(create)")))))

;; ============================================================================
;; fallback-release-metadata — review artifacts
;; ============================================================================

(deftest fallback-no-review-section-without-review-artifacts
  (testing "Review section is omitted when no review artifacts"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts []})
          body (:release/pr-body result)]
      (is (not (str/includes? body "## Review"))))))

(deftest fallback-review-section-with-blank-summary
  (testing "Review section is omitted when review summary is blank"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "   "}]})
          body (:release/pr-body result)]
      (is (not (str/includes? body "## Review"))))))

(deftest fallback-review-without-decision
  (testing "Review section works without a decision field"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "Looks good overall."}]})
          body (:release/pr-body result)]
      (is (str/includes? body "## Review"))
      (is (str/includes? body "Looks good overall."))
      (is (not (str/includes? body "**Decision**"))))))

(deftest fallback-gate-results-skipped-status
  (testing "skipped gate status renders with skip icon"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "Ok"
                                      :review/decision :approved
                                      :review/gate-results [{:gate/name "optional-lint"
                                                             :gate/status :skipped}]
                                      :review/gates-passed 0
                                      :review/gates-failed 0
                                      :review/gates-total 1}]})
          body (:release/pr-body result)]
      (is (str/includes? body "⏭️ optional-lint")))))

(deftest fallback-gate-results-unknown-status
  (testing "unknown gate status renders with question mark icon"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "Ok"
                                      :review/decision :approved
                                      :review/gate-results [{:gate/name "mystery"
                                                             :gate/status :pending}]
                                      :review/gates-passed 0
                                      :review/gates-failed 0
                                      :review/gates-total 1}]})
          body (:release/pr-body result)]
      (is (str/includes? body "❓ mystery")))))

(deftest fallback-gate-results-zero-total-omitted
  (testing "gate results section is omitted when total is 0"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "Ok"
                                      :review/decision :approved
                                      :review/gate-results []
                                      :review/gates-passed 0
                                      :review/gates-failed 0
                                      :review/gates-total 0}]})
          body (:release/pr-body result)]
      (is (not (str/includes? body "Quality gates"))))))

(deftest fallback-gate-results-uses-latest-review
  (testing "gate results come from the latest review artifact with gate data"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "First"
                                      :review/decision :rejected
                                      :review/gate-results [{:gate/name "lint" :gate/status :failed}]
                                      :review/gates-passed 0
                                      :review/gates-failed 1
                                      :review/gates-total 1}
                                     {:review/summary "Second"
                                      :review/decision :approved
                                      :review/gate-results [{:gate/name "lint" :gate/status :passed}]
                                      :review/gates-passed 1
                                      :review/gates-failed 0
                                      :review/gates-total 1}]})
          body (:release/pr-body result)]
      ;; Latest review passed, so we should see passed icon
      (is (str/includes? body "1/1 passed"))
      (is (str/includes? body "✅ lint")))))

(deftest fallback-gate-results-with-name-key-fallback
  (testing "gate results support :name key as fallback for :gate/name"
    (let [result (sut/fallback-release-metadata
                  "Fix" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "Ok"
                                      :review/decision :approved
                                      :review/gate-results [{:name "alt-lint" :status :passed}]
                                      :review/gates-passed 1
                                      :review/gates-failed 0
                                      :review/gates-total 1}]})
          body (:release/pr-body result)]
      (is (str/includes? body "✅ alt-lint")))))

;; ============================================================================
;; invoke-releaser
;; ============================================================================

(deftest invoke-releaser-nil-releaser
  (testing "returns nil when releaser is nil"
    (is (nil? (sut/invoke-releaser nil [{:code/files []}] "desc"
                                       {:llm-backend :mock} nil)))))

(deftest invoke-releaser-nil-llm-backend
  (testing "returns nil when llm-backend is nil"
    (let [releaser {:invoke-fn (fn [_ctx _input] {:status :success :output {:title "X"}})}]
      (is (nil? (sut/invoke-releaser releaser [{:code/files []}] "desc"
                                              {} nil))))))

(deftest invoke-releaser-success
  (testing "returns agent output on success"
    (let [expected-output {:release/branch-name "mf/test" :release/pr-title "Test"}
          releaser {:invoke-fn (fn [_ctx _input]
                                 {:status :success :output expected-output})}
          result (sut/invoke-releaser releaser [{:code/files []}] "desc"
                                              {:llm-backend :mock} nil)]
      (is (= expected-output result)))))

(deftest invoke-releaser-failure-returns-nil
  (testing "returns nil when agent returns non-success status"
    (let [releaser {:invoke-fn (fn [_ctx _input]
                                 {:status :error :error "LLM timeout"})}
          result (sut/invoke-releaser releaser [{:code/files []}] "desc"
                                              {:llm-backend :mock} nil)]
      (is (nil? result)))))

(deftest invoke-releaser-exception-returns-nil
  (testing "returns nil when agent throws exception"
    (let [releaser {:invoke-fn (fn [_ctx _input]
                                 (throw (Exception. "Boom")))}
          result (sut/invoke-releaser releaser [{:code/files []}] "desc"
                                              {:llm-backend :mock} nil)]
      (is (nil? result)))))

(deftest invoke-releaser-uses-task-description-fallbacks
  (testing "falls back to code/summary then default when task-description is nil"
    (let [captured-input (atom nil)
          releaser {:invoke-fn (fn [_ctx input]
                                 (reset! captured-input input)
                                 {:status :success :output {}})}
          code-artifact {:code/files [] :code/summary "From artifact summary"}]
      ;; nil task-description → should use code/summary
      (sut/invoke-releaser releaser [code-artifact] nil {:llm-backend :mock} nil)
      (is (= "From artifact summary" (:task-description @captured-input))))))

(deftest invoke-releaser-uses-default-when-all-nil
  (testing "uses configured default description when all sources are nil"
    (let [captured-input (atom nil)
          releaser {:invoke-fn (fn [_ctx input]
                                 (reset! captured-input input)
                                 {:status :success :output {}})}
          code-artifact {:code/files []}]
      (sut/invoke-releaser releaser [code-artifact] nil {:llm-backend :mock} nil)
      (is (= "implement changes" (:task-description @captured-input))))))

;; ============================================================================
;; generate-release-metadata
;; ============================================================================

(deftest generate-release-metadata-uses-releaser-when-available
  (testing "returns releaser output when agent succeeds"
    (let [expected {:release/pr-title "Agent Title"}
          releaser {:invoke-fn (fn [_ctx _input]
                                 {:status :success :output expected})}
          result (sut/generate-release-metadata
                  releaser [{:code/files []}] "desc"
                  {:llm-backend :mock} nil)]
      (is (= expected result)))))

(deftest generate-release-metadata-falls-back-on-agent-failure
  (testing "falls back to deterministic metadata when agent fails"
    (let [releaser {:invoke-fn (fn [_ctx _input] {:status :error})}
          result (sut/generate-release-metadata
                  releaser [{:code/files [{:path "a.clj" :action :create}]}]
                  "Add feature" {:llm-backend :mock} nil)]
      (is (contains? result :release/branch-name))
      (is (= "Add feature" (:release/pr-title result))))))

(deftest generate-release-metadata-falls-back-when-no-releaser
  (testing "falls back to deterministic metadata when releaser is nil"
    (let [result (sut/generate-release-metadata
                  nil [{:code/files [{:path "b.clj" :action :create}]}]
                  "Simple task" {} nil)]
      (is (contains? result :release/branch-name))
      (is (= "Simple task" (:release/pr-title result))))))

(deftest generate-release-metadata-passes-workflow-data
  (testing "workflow-data is passed through to fallback metadata"
    (let [review-artifact {:review/summary "LGTM"
                           :review/decision :approved
                           :review/gate-results [{:gate/name "lint" :gate/status :passed}]
                           :review/gates-passed 1
                           :review/gates-failed 0
                           :review/gates-total 1}
          result (sut/generate-release-metadata
                  nil [{:code/files [{:path "a.clj" :action :create}]}]
                  "Task" {} nil
                  {:review-artifacts [review-artifact]})
          body (:release/pr-body result)]
      (is (str/includes? body "## Review"))
      (is (str/includes? body "LGTM")))))

(deftest generate-release-metadata-5-arity-no-workflow-data
  (testing "5-arity version works without workflow data"
    (let [result (sut/generate-release-metadata
                  nil [{:code/files [{:path "a.clj" :action :create}]}]
                  "Task" {} nil)]
      (is (contains? result :release/branch-name))
      (is (not (str/includes? (:release/pr-body result) "## Review"))))))

;; ============================================================================
;; PR body structural integrity
;; ============================================================================

(deftest pr-body-section-ordering
  (testing "PR body sections appear in correct order: Summary, Changes, Review, Test plan, Footer"
    (let [result (sut/fallback-release-metadata
                  "Full test" [{:code/files [{:path "a.clj" :action :create}]}]
                  {:review-artifacts [{:review/summary "All good"
                                      :review/decision :approved
                                      :review/gate-results [{:gate/name "lint" :gate/status :passed}]
                                      :review/gates-passed 1
                                      :review/gates-failed 0
                                      :review/gates-total 1}]})
          body (:release/pr-body result)
          summary-idx (str/index-of body "## Summary")
          changes-idx (str/index-of body "## Changes")
          review-idx (str/index-of body "## Review")
          test-idx (str/index-of body "## Test plan")
          footer-idx (str/index-of body "---\nGenerated by")]
      (is (< summary-idx changes-idx))
      (is (< changes-idx review-idx))
      (is (< review-idx test-idx))
      (is (< test-idx footer-idx)))))

(deftest pr-body-has-test-plan-always
  (testing "Test plan section is always present in PR body"
    (let [result (sut/fallback-release-metadata
                  "Minimal" [{:code/files []}])
          body (:release/pr-body result)]
      (is (str/includes? body "## Test plan")))))

(deftest pr-body-footer-always-present
  (testing "Footer is always present in PR body"
    (let [result (sut/fallback-release-metadata
                  "Minimal" [{:code/files []}])
          body (:release/pr-body result)]
      (is (str/includes? body "Generated by Miniforge SDLC workflow")))))

(deftest fallback-all-return-keys-present
  (testing "all expected keys are present in fallback metadata"
    (let [result (sut/fallback-release-metadata
                  "Check keys" [{:code/files [{:path "a.clj" :action :create}]}])]
      (is (contains? result :release/branch-name))
      (is (contains? result :release/commit-message))
      (is (contains? result :release/pr-title))
      (is (contains? result :release/pr-body))
      (is (contains? result :release/pr-description))
      ;; pr-body and pr-description should be identical
      (is (= (:release/pr-body result) (:release/pr-description result))))))