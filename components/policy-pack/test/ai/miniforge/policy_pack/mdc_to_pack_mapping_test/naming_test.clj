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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test.naming-test
  "Acceptance tests for filename/slug/title derivation (Sections 1 and
   2 of work/designs/mdc-to-pack-field-mapping.edn).

   Split out of `ai.miniforge.policy-pack.mdc-to-pack-mapping-test`
   (rule 210 split train, slice 4/7). Assertions are unchanged from the
   parent namespace — only the deftest forms and their dependency on
   `mdc-to-pack-mapping-test.naming` moved."
  (:require
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.inventory :as inventory]
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.naming :as naming]
   [clojure.test :refer [are deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} slug-from-filename-test
  (testing "Strips .mdc extension from filename only"
    (are [filepath expected]
         (= expected (naming/slug-from-filename filepath))
      "foundations/stratified-design.mdc" "stratified-design"
      "index.mdc"                        "index"
      "languages/clojure.mdc"            "clojure"
      "workflows/pr-layering.mdc"        "pr-layering"))

  (testing "Handles nested directory paths"
    (is (= "foo" (naming/slug-from-filename "a/b/c/foo.mdc")))))

(deftest ^{:stratum 0} title-from-slug-test
  (testing "Converts hyphens to spaces and title-cases"
    (are [slug expected]
         (= expected (naming/title-from-slug slug))
      "code-quality"            "Code Quality"
      "pre-commit-discipline"   "Pre Commit Discipline"
      "index"                   "Index"
      "stratified-design"       "Stratified Design"
      "git-branch-management"   "Git Branch Management")))

(deftest ^{:stratum 0} edge-case-duplicate-slugs-test
  (testing "Duplicate filename slugs must be detectable"
    (let [files ["foundations/foo.mdc" "workflows/foo.mdc"]
          slugs (map naming/slug-from-filename files)]
      (is (not= (count slugs) (count (set slugs)))
          "Duplicate slugs should be detected by ETL"))))

;; ===========================================================================
;; Section 1: Rule ID Derivation Tests
;; ===========================================================================
(deftest ^{:stratum 0} rule-id-derivation-test
  (testing "Rule ID is derived from filename slug with :std/ namespace"
    (are [filepath expected-id]
         (= expected-id (naming/rule-id-from-filepath filepath))
      "foundations/stratified-design.mdc"       :std/stratified-design
      "languages/clojure.mdc"                   :std/clojure
      "workflows/pre-commit-discipline.mdc"     :std/pre-commit-discipline
      "testing/standards.mdc"                   :std/standards
      "index.mdc"                               :std/index
      "meta/rule-format.mdc"                    :std/rule-format
      "project/header-copyright.mdc"            :std/header-copyright))

  (testing "Directory path is NOT included in the id"
    (is (= :std/clojure (naming/rule-id-from-filepath "languages/clojure.mdc")))
    (is (= :std/clojure (naming/rule-id-from-filepath "deeply/nested/path/clojure.mdc"))))

  (testing "Complete inventory matches expected IDs"
    (doseq [[filepath expected-id] inventory/complete-inventory]
      (is (= expected-id (naming/rule-id-from-filepath filepath))
          (str "ID mismatch for " filepath)))))

;; ===========================================================================
;; Section 2: Title Derivation Tests
;; ===========================================================================
(deftest ^{:stratum 0} title-derivation-test
  (testing "Uses description frontmatter verbatim when present"
    (let [fm {"description" "Stratified Design — enforce one-way dependencies"}]
      (is (= "Stratified Design — enforce one-way dependencies"
             (naming/derive-title fm "foundations/stratified-design.mdc")))))

  (testing "Falls back to title-cased slug when description missing"
    (is (= "Code Quality" (naming/derive-title {} "foundations/code-quality.mdc")))
    (is (= "Pre Commit Discipline" (naming/derive-title {} "workflows/pre-commit-discipline.mdc"))))

  (testing "Falls back to title-cased slug when description is blank"
    (is (= "Code Quality" (naming/derive-title {"description" ""} "foundations/code-quality.mdc")))
    (is (= "Code Quality" (naming/derive-title {"description" "  "} "foundations/code-quality.mdc")))))
