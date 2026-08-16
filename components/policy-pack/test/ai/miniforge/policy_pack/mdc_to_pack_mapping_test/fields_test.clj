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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.fields-test
  "Acceptance tests for the independent field-derivation helpers
   (Sections 3, 5, 6, and 9 of work/designs/mdc-to-pack-field-mapping.edn).

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210 split train, slice 2/7). Assertions are unchanged from the
   parent namespace — only the deftest forms and their dependency on
   `mdc-to-pack-mapping-test.fields` moved."
  (:require
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.fields :as fields]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

;; ===========================================================================
;; Section 3: Description Generation Tests
;; ===========================================================================
(deftest ^{:stratum 0} description-generation-test
  (testing "Format: 'Engineering standard (<category>): <title>'"
    (is (= "Engineering standard (001): Stratified Design — enforce one-way dependencies"
           (fields/derive-description "001" "Stratified Design — enforce one-way dependencies")))
    (is (= "Engineering standard (210): Clojure Polylith + per-file stratified design"
           (fields/derive-description "210" "Clojure Polylith + per-file stratified design")))
    (is (= "Engineering standard (000): Index"
           (fields/derive-description "000" "Index")))))

;; ===========================================================================
;; Section 5: alwaysApply → :rule/always-inject? Mapping Tests
;; ===========================================================================
(deftest ^{:stratum 0} always-inject-mapping-test
  (testing "alwaysApply: true → {:rule/always-inject? true}"
    (is (= {:rule/always-inject? true} (fields/build-always-inject true))))

  (testing "alwaysApply: false → {} (key omitted)"
    (is (= {} (fields/build-always-inject false))))

  (testing "alwaysApply: nil (absent) → {} (key omitted)"
    (is (= {} (fields/build-always-inject nil))))

  (testing "Consumers get nil (falsy) for missing always-inject?"
    (let [rule-without (merge {} (fields/build-always-inject false))]
      (is (nil? (:rule/always-inject? rule-without))))))

(deftest ^{:stratum 0} globs-normalization-test
  (testing "String globs wrapped in vector"
    (is (= ["*.clj"] (fields/normalize-globs "*.clj"))))

  (testing "Vector globs passed through"
    (is (= ["*.clj" "*.cljc"] (fields/normalize-globs ["*.clj" "*.cljc"]))))

  (testing "nil globs → nil"
    (is (nil? (fields/normalize-globs nil)))))

;; ===========================================================================
;; Section 9: Agent Behavior Extraction Tests
;; ===========================================================================
(deftest ^{:stratum 0} agent-behavior-section-extraction-test
  (testing "Extracts content from ## Agent behavior section"
    (let [body "# Heading\n\nIntro paragraph.\n\n## Agent behavior\n\n- Do X.\n- Do Y.\n\n## Next section"]
      (is (= "- Do X.\n- Do Y." (fields/extract-agent-behavior-section body)))))

  (testing "Case-insensitive heading match"
    (let [body "## agent behavior\n\nDo stuff."]
      (is (= "Do stuff." (fields/extract-agent-behavior-section body)))))

  (testing "Extracts to end of file when no next heading"
    (let [body "## Agent behavior\n\n- Be concise.\n- Be clear."]
      (is (= "- Be concise.\n- Be clear." (fields/extract-agent-behavior-section body)))))

  (testing "Returns nil when section not present"
    (let [body "# Heading\n\nJust content, no agent behavior section."]
      (is (nil? (fields/extract-agent-behavior-section body)))))

  (testing "Returns nil for empty section"
    (let [body "## Agent behavior\n\n## Next heading"]
      (is (nil? (fields/extract-agent-behavior-section body)))))

  (testing "Returns nil for nil body"
    (is (nil? (fields/extract-agent-behavior-section nil)))))

(deftest ^{:stratum 0} first-paragraph-extraction-test
  (testing "Extracts first non-heading paragraph"
    (let [body "# Code Quality (ALWAYS)\n\nEvery function must read as a pipeline: data enters, transforms, exits.\n\nMore text."]
      (is (= "Every function must read as a pipeline: data enters, transforms, exits."
             (fields/extract-first-paragraph body)))))

  (testing "Skips leading headings and blank lines"
    (let [body "\n# Heading\n## Sub\n\nActual content here."]
      (is (= "Actual content here." (fields/extract-first-paragraph body)))))

  (testing "Returns nil for heading-only body"
    (let [body "# Just a heading\n## And subheading"]
      (is (nil? (fields/extract-first-paragraph body)))))

  (testing "Returns nil for nil body"
    (is (nil? (fields/extract-first-paragraph nil)))))

(deftest ^{:stratum 0} edge-case-body-only-headings-test
  (testing "Body with only headings → knowledge-content present, agent-behavior nil"
    (let [body "# Just a heading\n## And subheading"]
      (is (nil? (fields/extract-agent-behavior-section body)))
      (is (nil? (fields/extract-first-paragraph body))))))
