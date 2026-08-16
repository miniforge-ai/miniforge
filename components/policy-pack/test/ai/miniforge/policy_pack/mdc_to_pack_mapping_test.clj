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
(ns ai.miniforge.policy-pack.mdc-to-pack-mapping-test
  "Acceptance tests for the MDC-to-Pack Rule Field Mapping design.

   These tests verify the mapping specification in
   work/designs/mdc-to-pack-field-mapping.edn is complete, internally
   consistent, and produces correct compiled rules per the worked examples.

   The tests implement the core transformation functions described in the spec
   and validate them against the spec's own examples, edge cases, and the
   actual .standards/ file inventory.

   Once the ETL task (bb standards:pack) is implemented, these tests serve
   as the authoritative acceptance suite."
  (:require
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.dewey :as dewey]
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.fields :as fields]
   [ai.miniforge.policy-pack.mdc-to-pack-mapping-test.naming :as naming]
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [clojure.set :as set]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} build-applies-to
  "Build :rule/applies-to from dewey + optional globs."
  [dewey-str globs]
  (let [phases (dewey/dewey-to-phases dewey-str)
        base   {:phases phases}]
    (if-let [g (fields/normalize-globs globs)]
      (assoc base :file-globs g)
      base)))

;------------------------------------------------------------------------------ Layer 1

;; ===========================================================================
;; Section 6: Applies-To (Phases + Globs) Tests
;; ===========================================================================
(deftest ^{:stratum 1} applies-to-test
  (testing "Dewey-only → phases from dewey, no globs"
    (is (= {:phases #{:plan :implement :review :verify :release}}
           (build-applies-to "001" nil))))

  (testing "Dewey + globs → phases + file-globs"
    (is (= {:phases     #{:implement :review}
            :file-globs ["components/**/src/**/*.clj"]}
           (build-applies-to "210" ["components/**/src/**/*.clj"]))))

  (testing "Meta dewey → empty phases"
    (is (= {:phases #{}} (build-applies-to "900" nil))))

  (testing "Meta dewey + globs → empty phases + globs"
    (is (= {:phases     #{}
            :file-globs [".cursor/rules/**/*.mdc"]}
           (build-applies-to "900" [".cursor/rules/**/*.mdc"])))))

(defn ^{:stratum 1} compile-rule
  "Compile a single MDC file representation into a pack rule map.
   This is the reference implementation of the spec's field mapping."
  [{:keys [filepath frontmatter body]}]
  (let [dewey       (get frontmatter "dewey" "000")
        title       (naming/derive-title frontmatter filepath)
        description (fields/derive-description dewey title)
        always-apply (get frontmatter "alwaysApply")
        globs       (get frontmatter "globs")
        severity    (if always-apply :high :low)
        trimmed-body (when body (str/trim body))
        knowledge   (when (and trimmed-body (not (str/blank? trimmed-body)))
                      trimmed-body)]
    (merge
     {:rule/id                (naming/rule-id-from-filepath filepath)
      :rule/title             title
      :rule/description       description
      :rule/severity          severity
      :rule/category          dewey
      :rule/applies-to        (build-applies-to dewey globs)
      :rule/detection         {:type :custom}
      :rule/enforcement       (fields/build-enforcement title always-apply)}
     (fields/build-always-inject always-apply)
     (when knowledge
       {:rule/knowledge-content knowledge}))))

;------------------------------------------------------------------------------ Layer 2

;; ===========================================================================
;; Section 7: Constant/Default Fields Tests
;; ===========================================================================
(deftest ^{:stratum 2} constant-fields-test
  (testing "Non-alwaysApply severity is :low"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= :low (:rule/severity rule)))))

  (testing "Detection is always {:type :custom}"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= {:type :custom} (:rule/detection rule)))))

  (testing "Non-alwaysApply enforcement is {:action :audit}"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "My Test Rule"}
                              :body "content"})]
      (is (= {:action :audit :message "Standard: My Test Rule"}
             (:rule/enforcement rule)))))

  (testing "alwaysApply enforcement is {:action :warn}"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "My Test Rule"
                                            "alwaysApply" true}
                              :body "content"})]
      (is (= {:action :warn :message "Standard: My Test Rule"}
             (:rule/enforcement rule))))))

;; ===========================================================================
;; Section 8: Knowledge Content Tests
;; ===========================================================================
(deftest ^{:stratum 2} knowledge-content-test
  (testing "Body text preserved as :rule/knowledge-content"
    (let [body "# Heading\n\nSome content here."
          rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body body})]
      (is (= body (:rule/knowledge-content rule)))))

  (testing "Leading/trailing whitespace trimmed"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "  \n  content  \n  "})]
      (is (= "content" (:rule/knowledge-content rule)))))

  (testing "Empty body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body ""})]
      (is (not (contains? rule :rule/knowledge-content)))))

  (testing "Whitespace-only body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "   \n\n   "})]
      (is (not (contains? rule :rule/knowledge-content)))))

  (testing "Nil body → :rule/knowledge-content omitted"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "001"}
                              :body nil})]
      (is (not (contains? rule :rule/knowledge-content))))))

;; ===========================================================================
;; Section 10: Worked Example A — Foundation alwaysApply Rule
;; ===========================================================================
(deftest ^{:stratum 2} worked-example-a-stratified-design-test
  (let [rule (compile-rule
              {:filepath    "foundations/stratified-design.mdc"
               :frontmatter {"dewey"       "001"
                             "description" "Stratified Design — enforce one-way dependencies and clear data flow"
                             "alwaysApply" true}
               :body        "# Stratified Design (ALWAYS)\n\nUse a single-direction DAG...\n\n## Agent behavior\n\n- Before writing code..."})]

    (testing ":rule/id derived from filename slug"
      (is (= :std/stratified-design (:rule/id rule))))

    (testing ":rule/title from description frontmatter"
      (is (= "Stratified Design — enforce one-way dependencies and clear data flow"
             (:rule/title rule))))

    (testing ":rule/description generated from dewey + title"
      (is (= "Engineering standard (001): Stratified Design — enforce one-way dependencies and clear data flow"
             (:rule/description rule))))

    (testing ":rule/severity is :high (alwaysApply: true)"
      (is (= :high (:rule/severity rule))))

    (testing ":rule/category preserves dewey string"
      (is (= "001" (:rule/category rule))))

    (testing ":rule/always-inject? is true (alwaysApply: true)"
      (is (true? (:rule/always-inject? rule))))

    (testing ":rule/applies-to has all phases for dewey 001"
      (is (= {:phases dewey/all-phases}
             (:rule/applies-to rule))))

    (testing ":rule/detection is {:type :custom}"
      (is (= {:type :custom} (:rule/detection rule))))

    (testing ":rule/enforcement is warn with standard message (alwaysApply: true)"
      (is (= {:action  :warn
              :message "Standard: Stratified Design — enforce one-way dependencies and clear data flow"}
             (:rule/enforcement rule))))

    (testing ":rule/knowledge-content contains full body"
      (is (some? (:rule/knowledge-content rule)))
      (is (str/starts-with? (:rule/knowledge-content rule) "# Stratified Design")))))

;; ===========================================================================
;; Section 11: Worked Example B — Language Rule with Globs
;; ===========================================================================
(deftest ^{:stratum 2} worked-example-b-clojure-test
  (let [globs ["components/**/src/**/*.clj"
               "components/**/src/**/*.cljc"
               "bases/**/src/**/*.clj"
               "bases/**/src/**/*.cljc"
               "projects/**/src/**/*.clj"
               "projects/**/src/**/*.cljc"]
        rule (compile-rule
              {:filepath    "languages/clojure.mdc"
               :frontmatter {"dewey"       "210"
                             "description" "Clojure Polylith + per-file stratified design"
                             "globs"       globs}
               :body        "# Clojure style guidelines\n\n## Polylith architecture (ALWAYS)..."})]

    (testing ":rule/id is :std/clojure"
      (is (= :std/clojure (:rule/id rule))))

    (testing ":rule/title from description"
      (is (= "Clojure Polylith + per-file stratified design" (:rule/title rule))))

    (testing ":rule/category is '210'"
      (is (= "210" (:rule/category rule))))

    (testing ":rule/always-inject? omitted (no alwaysApply)"
      (is (not (contains? rule :rule/always-inject?))))

    (testing ":rule/applies-to has implement+review phases and file-globs"
      (is (= #{:implement :review}
             (get-in rule [:rule/applies-to :phases])))
      (is (= globs
             (get-in rule [:rule/applies-to :file-globs]))))

    (testing ":rule/description generated correctly"
      (is (= "Engineering standard (210): Clojure Polylith + per-file stratified design"
             (:rule/description rule))))))

;; ===========================================================================
;; Section 12: Worked Example C — Meta Rule (Not Injected)
;; ===========================================================================
(deftest ^{:stratum 2} worked-example-c-meta-rule-test
  (let [rule (compile-rule
              {:filepath    "meta/rule-format.mdc"
               :frontmatter {"dewey"       "900"
                             "description" "Use ALWAYS when asked to CREATE A RULE or UPDATE A RULE..."
                             "globs"       [".cursor/rules/**/*.mdc"]}
               :body        "# LLM Rules Format\n\n## Core Structure..."})]

    (testing ":rule/id is :std/rule-format"
      (is (= :std/rule-format (:rule/id rule))))

    (testing ":rule/category is '900'"
      (is (= "900" (:rule/category rule))))

    (testing ":rule/always-inject? omitted"
      (is (not (contains? rule :rule/always-inject?))))

    (testing ":rule/applies-to has empty phases (meta) but preserves globs"
      (is (= #{} (get-in rule [:rule/applies-to :phases])))
      (is (= [".cursor/rules/**/*.mdc"]
             (get-in rule [:rule/applies-to :file-globs]))))))

;; ===========================================================================
;; Section 13: Worked Example D — Testing Rule (alwaysApply)
;; ===========================================================================
(deftest ^{:stratum 2} worked-example-d-testing-rule-test
  (let [rule (compile-rule
              {:filepath    "testing/standards.mdc"
               :frontmatter {"dewey"       "400"
                             "description" "Testing standards — factory functions, same code quality as production, no duplication"
                             "alwaysApply" true}
               :body        "# Testing Standards (ALWAYS)\n\n**Test code is production code.**..."})]

    (testing ":rule/id is :std/standards"
      (is (= :std/standards (:rule/id rule))))

    (testing ":rule/always-inject? true"
      (is (true? (:rule/always-inject? rule))))

    (testing ":rule/applies-to has implement+verify phases (dewey 400)"
      (is (= #{:implement :verify}
             (get-in rule [:rule/applies-to :phases]))))

    (testing ":rule/enforcement warn with title (alwaysApply: true)"
      (is (= {:action :warn
              :message "Standard: Testing standards — factory functions, same code quality as production, no duplication"}
             (:rule/enforcement rule))))))

;; ===========================================================================
;; Section 14: Edge Case Tests
;; ===========================================================================
(deftest ^{:stratum 2} edge-case-missing-dewey-test
  (testing "Missing dewey → default to '000', phases all"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {}
                              :body "content"})]
      (is (= "000" (:rule/category rule)))
      (is (= dewey/all-phases
             (get-in rule [:rule/applies-to :phases]))))))

(deftest ^{:stratum 2} edge-case-missing-description-test
  (testing "Missing description → title derived from slug"
    (let [rule (compile-rule {:filepath "foundations/code-quality.mdc"
                              :frontmatter {"dewey" "001"}
                              :body "content"})]
      (is (= "Code Quality" (:rule/title rule))))))

(deftest ^{:stratum 2} edge-case-empty-body-test
  (testing "Empty body → knowledge-content omitted, rule still valid"
    (let [rule (compile-rule {:filepath "stub.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "Stub rule"}
                              :body ""})]
      (is (not (contains? rule :rule/knowledge-content)))
      (is (= :std/stub (:rule/id rule)))
      (is (= "Stub rule" (:rule/title rule))))))

(deftest ^{:stratum 2} edge-case-always-apply-meta-test
  (testing "alwaysApply: true + dewey 900 → always-inject true but empty phases"
    (let [rule (compile-rule {:filepath "meta/weird.mdc"
                              :frontmatter {"dewey"       "900"
                                            "alwaysApply" true
                                            "description" "Weird meta rule"}
                              :body "some content"})]
      (is (true? (:rule/always-inject? rule)))
      (is (= #{} (get-in rule [:rule/applies-to :phases]))))))

(deftest ^{:stratum 2} edge-case-globs-string-instead-of-list-test
  (testing "String globs normalized to vector"
    (let [rule (compile-rule {:filepath "test.mdc"
                              :frontmatter {"dewey" "210"
                                            "globs" "*.clj"}
                              :body "content"})]
      (is (= ["*.clj"] (get-in rule [:rule/applies-to :file-globs]))))))

(deftest ^{:stratum 2} edge-case-index-mdc-test
  (testing "index.mdc compiled like any other file, ID :std/index"
    (let [rule (compile-rule {:filepath "index.mdc"
                              :frontmatter {"dewey" "000"
                                            "description" "Master index of all rules"
                                            "alwaysApply" false}
                              :body "# Rules Catalog"})]
      (is (= :std/index (:rule/id rule)))
      (is (= dewey/all-phases (get-in rule [:rule/applies-to :phases])))
      ;; alwaysApply false → always-inject? omitted
      (is (not (contains? rule :rule/always-inject?))))))

(deftest ^{:stratum 2} all-rule-schema-fields-produced-test
  (testing "Compiled rule produces all required schema fields"
    (let [rule (compile-rule {:filepath "test/example.mdc"
                              :frontmatter {"dewey"       "210"
                                            "description" "Example rule"
                                            "alwaysApply" true
                                            "globs"       ["*.clj"]}
                              :body        "# Example\n\nContent here."})
          required-keys #{:rule/id :rule/title :rule/description
                          :rule/severity :rule/category :rule/applies-to
                          :rule/detection :rule/enforcement}]
      (is (set/subset? required-keys (set (keys rule)))
          (str "Missing required keys: " (set/difference required-keys (set (keys rule))))))))

(deftest ^{:stratum 2} optional-fields-conditionally-present-test
  (testing ":rule/always-inject? present only when alwaysApply is true"
    (let [rule-with    (compile-rule {:filepath "a.mdc" :frontmatter {"alwaysApply" true} :body "x"})
          rule-without (compile-rule {:filepath "b.mdc" :frontmatter {} :body "x"})]
      (is (contains? rule-with :rule/always-inject?))
      (is (not (contains? rule-without :rule/always-inject?)))))

  (testing ":rule/knowledge-content present only when body is non-empty"
    (let [rule-with    (compile-rule {:filepath "a.mdc" :frontmatter {} :body "content"})
          rule-without (compile-rule {:filepath "b.mdc" :frontmatter {} :body ""})]
      (is (contains? rule-with :rule/knowledge-content))
      (is (not (contains? rule-without :rule/knowledge-content))))))

;; ===========================================================================
;; Section 18: Schema Compatibility Tests
;; ===========================================================================
(deftest ^{:stratum 2} compiled-rule-matches-schema-shape-test
  (testing "Compiled rule has correct value types for schema fields"
    (let [rule (compile-rule {:filepath "foundations/stratified-design.mdc"
                              :frontmatter {"dewey" "001"
                                            "description" "Test"
                                            "alwaysApply" true}
                              :body "Body content"})]
      ;; Identity
      (is (keyword? (:rule/id rule)))
      (is (string? (:rule/title rule)))
      (is (string? (:rule/description rule)))
      (is (#{:critical :high :medium :low :info} (:rule/severity rule)))
      (is (string? (:rule/category rule)))

      ;; Applicability
      (is (map? (:rule/applies-to rule)))
      (is (set? (get-in rule [:rule/applies-to :phases])))

      ;; Detection
      (is (map? (:rule/detection rule)))
      (is (#{:plan-output :diff-analysis :state-comparison :content-scan :ast-analysis :custom}
           (get-in rule [:rule/detection :type])))

      ;; Enforcement
      (is (map? (:rule/enforcement rule)))
      (is (#{:hard-halt :require-approval :warn :audit}
           (get-in rule [:rule/enforcement :action])))
      (is (string? (get-in rule [:rule/enforcement :message])))

      ;; Optional
      (is (boolean? (:rule/always-inject? rule)))
      (is (string? (:rule/knowledge-content rule))))))

(deftest ^{:stratum 2} always-inject-does-not-override-phases-test
  (testing "alwaysApply controls injection, phases control which roles"
    (let [rule (compile-rule {:filepath "testing/standards.mdc"
                              :frontmatter {"dewey" "400"
                                            "alwaysApply" true}
                              :body "content"})]
      ;; always-inject is true
      (is (true? (:rule/always-inject? rule)))
      ;; but phases are still limited to testing phases, not all
      (is (= #{:implement :verify}
             (get-in rule [:rule/applies-to :phases]))))))

;; ---------------------------------------------------------------------------
;; Rich Comment
;; ---------------------------------------------------------------------------
(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.mdc-to-pack-mapping-test)
  :leave-this-here)
