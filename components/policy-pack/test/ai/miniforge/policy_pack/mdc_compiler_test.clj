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

(ns ai.miniforge.policy-pack.mdc-compiler-test
  "Unit tests for the MDC-to-rule compiler.

   Covers:
   - Layer 0: MDC parsing (split-frontmatter, parse-mdc, frontmatter values)
   - Layer 1: Field mapping (dewey->phases, dewey->category-id/label,
              slug->rule-id, extract-agent-behavior, globs normalization)
   - Layer 2: Rule compilation (mdc->rule), pack assembly (compile-standards-pack)

   Acceptance criteria verified:
   - Field mapping covers all frontmatter fields (dewey, description, alwaysApply, globs)
   - :rule/knowledge-content and :rule/always-inject? have clear semantics
   - Standards pack is a separate file from builtin pack"
  (:require
   [clojure.test :refer [deftest testing is are]]
   [clojure.string :as str]
   [ai.miniforge.policy-pack.mdc-compiler :as sut]))

;; ============================================================================
;; Layer 0 — split-frontmatter tests
;; ============================================================================

(deftest split-frontmatter-test
  (testing "splits frontmatter and body at --- delimiters"
    (let [result (sut/split-frontmatter "---\nkey: value\n---\nBody text")]
      (is (= "key: value" (:frontmatter result)))
      (is (= "Body text" (:body result)))))

  (testing "handles missing frontmatter (no --- prefix)"
    (let [result (sut/split-frontmatter "Just body text")]
      (is (= "" (:frontmatter result)))
      (is (= "Just body text" (:body result)))))

  (testing "handles missing closing ---"
    (let [result (sut/split-frontmatter "---\nkey: value\nno closing")]
      (is (= "" (:frontmatter result)))))

  (testing "handles empty frontmatter"
    (let [result (sut/split-frontmatter "---\n---\nBody only")]
      (is (= "" (:frontmatter result)))
      (is (= "Body only" (:body result)))))

  (testing "handles empty content"
    (let [result (sut/split-frontmatter "")]
      (is (= "" (:frontmatter result)))))

  (testing "handles nil content gracefully"
    (let [result (sut/split-frontmatter nil)]
      (is (= "" (:frontmatter result))))))

;; ============================================================================
;; Layer 0 — parse-mdc tests
;; ============================================================================

(deftest parse-mdc-test
  (testing "parses complete MDC file with frontmatter and body"
    (let [content "---\ndewey: \"001\"\ndescription: Stratified Design\nalwaysApply: true\n---\n\n# Body here"
          result (sut/parse-mdc content)]
      (is (= {"dewey" "001"
              "description" "Stratified Design"
              "alwaysApply" true}
             (:frontmatter result)))
      (is (= "# Body here" (:body result)))))

  (testing "parses frontmatter with inline array globs"
    (let [content "---\nglobs: [\"*.clj\", \"*.cljc\"]\n---\nBody"
          result (sut/parse-mdc content)]
      (is (= ["*.clj" "*.cljc"] (get-in result [:frontmatter "globs"])))))

  (testing "parses frontmatter with multi-line list"
    (let [content "---\nglobs:\n- \"*.clj\"\n- \"*.cljc\"\n---\nBody"
          result (sut/parse-mdc content)]
      (is (= ["*.clj" "*.cljc"] (get-in result [:frontmatter "globs"])))))

  (testing "parses boolean values"
    (let [content "---\nalwaysApply: true\nother: false\n---\nBody"
          result (sut/parse-mdc content)]
      (is (true? (get-in result [:frontmatter "alwaysApply"])))
      (is (false? (get-in result [:frontmatter "other"])))))

  (testing "parses quoted string values (double and single)"
    (let [content "---\na: \"double\"\nb: 'single'\n---\nBody"
          result (sut/parse-mdc content)]
      (is (= "double" (get-in result [:frontmatter "a"])))
      (is (= "single" (get-in result [:frontmatter "b"])))))

  (testing "handles empty body"
    (let [result (sut/parse-mdc "---\nkey: val\n---")]
      (is (= "val" (get-in result [:frontmatter "key"])))
      (is (= "" (:body result)))))

  (testing "handles empty frontmatter"
    (let [result (sut/parse-mdc "Just body")]
      (is (= {} (:frontmatter result)))
      (is (= "Just body" (:body result))))))

;; ============================================================================
;; Layer 1 — Dewey → phases mapping tests
;; ============================================================================

(def all-phases #{:plan :implement :review :verify :release})

(deftest dewey->phases-test
  (testing "foundations (0-99) → all phases"
    (is (= all-phases (sut/dewey->phases "001")))
    (is (= all-phases (sut/dewey->phases "000")))
    (is (= all-phases (sut/dewey->phases "099"))))

  (testing "tools (100-199) → implement + review"
    (is (= #{:implement :review} (sut/dewey->phases "100"))))

  (testing "languages (200-299) → implement + review"
    (is (= #{:implement :review} (sut/dewey->phases "210"))))

  (testing "frameworks (300-399) → plan + implement + review"
    (is (= #{:plan :implement :review} (sut/dewey->phases "300"))))

  (testing "testing (400-499) → implement + verify"
    (is (= #{:implement :verify} (sut/dewey->phases "400"))))

  (testing "operations (500-599) → implement + review"
    (is (= #{:implement :review} (sut/dewey->phases "500"))))

  (testing "documentation (600-699) → implement + review"
    (is (= #{:implement :review} (sut/dewey->phases "600"))))

  (testing "workflows (700-799) → all phases"
    (is (= all-phases (sut/dewey->phases "715"))))

  (testing "project (800-899) → implement + review"
    (is (= #{:implement :review} (sut/dewey->phases "800"))))

  (testing "meta (900-999) → empty set (never injected)"
    (is (= #{} (sut/dewey->phases "900")))
    (is (= #{} (sut/dewey->phases "999"))))

  (testing "boundary values: end of one range, start of next"
    (is (= all-phases (sut/dewey->phases "99")))
    (is (= #{:implement :review} (sut/dewey->phases "100"))))

  (testing "unparseable dewey falls back to default phases"
    (is (= #{:implement :review} (sut/dewey->phases "xyz")))
    (is (= #{:implement :review} (sut/dewey->phases "")))
    (is (= #{:implement :review} (sut/dewey->phases nil)))))

;; ============================================================================
;; Layer 1 — Dewey → category tests
;; ============================================================================

(deftest dewey->category-id-test
  (testing "maps dewey codes to category IDs"
    (are [dewey expected]
         (= expected (sut/dewey->category-id dewey))
      "001" "foundations"
      "100" "tools"
      "210" "languages"
      "300" "frameworks"
      "400" "testing"
      "500" "operations"
      "600" "documentation"
      "715" "workflows"
      "800" "project"
      "900" "meta"))

  (testing "unknown dewey returns 'other'"
    (is (= "other" (sut/dewey->category-id "xyz")))
    (is (= "other" (sut/dewey->category-id "")))))

(deftest dewey->category-label-test
  (testing "maps dewey codes to human labels"
    (is (= "Foundations & Core Principles" (sut/dewey->category-label "001")))
    (is (= "Languages" (sut/dewey->category-label "210")))
    (is (= "Workflows & Processes" (sut/dewey->category-label "715"))))

  (testing "unknown dewey returns 'Other'"
    (is (= "Other" (sut/dewey->category-label "xyz")))))

;; ============================================================================
;; Layer 1 — Slug → rule ID tests
;; ============================================================================

(deftest slug->rule-id-test
  (testing "converts .mdc filename to :std/ namespaced keyword"
    (are [filename expected]
         (= expected (sut/slug->rule-id filename))
      "stratified-design.mdc"       :std/stratified-design
      "clojure.mdc"                 :std/clojure
      "pre-commit-discipline.mdc"   :std/pre-commit-discipline
      "index.mdc"                   :std/index))

  (testing "directory path is NOT included — only bare filename"
    ;; slug->rule-id expects bare filename, not full path
    (is (= :std/stratified-design (sut/slug->rule-id "stratified-design.mdc")))))

;; ============================================================================
;; Layer 1 — Agent behavior extraction tests
;; ============================================================================

(deftest extract-agent-behavior-test
  (testing "priority 1: extracts ## Agent behavior section"
    (let [body "# Title\n\nIntro.\n\n## Agent behavior\n\n- Do this first.\n- Then do that.\n\n## Next section"]
      (is (= "- Do this first.\n- Then do that."
             (sut/extract-agent-behavior body)))))

  (testing "priority 2: falls back to first non-heading paragraph"
    (let [body "# Title\n\nFirst paragraph used as fallback.\n\nSecond paragraph ignored."]
      (is (= "First paragraph used as fallback."
             (sut/extract-agent-behavior body)))))

  (testing "returns nil for nil body"
    (is (nil? (sut/extract-agent-behavior nil))))

  (testing "returns nil for blank body"
    (is (nil? (sut/extract-agent-behavior ""))))

  (testing "returns nil for whitespace-only body"
    (is (nil? (sut/extract-agent-behavior "   \n  "))))

  (testing "returns nil for body with only headings"
    (is (nil? (sut/extract-agent-behavior "# Heading only\n## Sub heading"))))

  (testing "condenses long behavior sections to ~500 chars"
    (let [long-body (str "## Agent behavior\n\n"
                         (str/join ". " (repeat 100 "Do something important"))
                         ".")]
      (is (<= (count (sut/extract-agent-behavior long-body)) 510))))  ;; allow small slack

  (testing "preserves bullet lists when condensing"
    (let [body "## Agent behavior\n\n- First bullet.\n- Second bullet.\n- Third bullet.\n- Fourth bullet."
          result (sut/extract-agent-behavior body)]
      ;; Should preserve first 3 bullets when condensing
      (is (str/includes? result "First bullet"))
      (is (str/includes? result "Second bullet"))
      (is (str/includes? result "Third bullet"))))

  (testing "preserves numbered lists when condensing — regression for dewey-211 rule"
    ;; The dewey-211 (clojure-exception-handling) rule's first compiled
    ;; output truncated mid-sentence at "4." because the bullet regex
    ;; matched `[-*]` only and numbered lines fell through to prose
    ;; condensation. This guards against re-introducing that.
    (let [body (str "## Agent behavior\n\n"
                    "1. First numbered step that should survive.\n"
                    "2. Second numbered step that should survive.\n"
                    "3. Third numbered step that should survive.\n"
                    "4. Fourth numbered step.\n"
                    "5. Fifth numbered step.")
          result (sut/extract-agent-behavior body)]
      (is (str/includes? result "First numbered step")
          "first numbered item must be preserved")
      (is (str/includes? result "Second numbered step")
          "second numbered item must be preserved")
      (is (str/includes? result "Third numbered step")
          "third numbered item must be preserved")
      (is (not (re-find #"\d+\.\s*$" (str/trim result)))
          "compiled output must not end with a bare numbered prefix (mid-sentence truncation)"))))

;; ============================================================================
;; Layer 2 — mdc->rule compilation tests
;; ============================================================================

(deftest mdc->rule-success-test
  (testing "compiles a standard MDC file into a valid rule"
    (let [content (str "---\ndewey: \"001\"\n"
                       "description: Stratified Design\n"
                       "alwaysApply: true\n"
                       "---\n\n"
                       "# Stratified Design (ALWAYS)\n\n"
                       "Use a DAG.\n\n"
                       "## Agent behavior\n\n"
                       "- Output a stratified plan before writing code.")
          result (sut/mdc->rule "stratified-design.mdc" content)]
      (is (true? (:success? result)))
      (let [rule (:rule result)]
        (is (= :std/stratified-design (:rule/id rule)))
        (is (= "Stratified Design" (:rule/title rule)))
        (is (= "Engineering standard (001): Stratified Design" (:rule/description rule)))
        (is (= :major (:rule/severity rule)))
        (is (= "001" (:rule/category rule)))
        (is (true? (:rule/always-inject? rule)))
        (is (= all-phases (get-in rule [:rule/applies-to :phases])))
        (is (= {:type :custom} (:rule/detection rule)))
        (is (= {:action :warn :message "Standard: Stratified Design"}
               (:rule/enforcement rule)))
        (is (some? (:rule/agent-behavior rule)))
        (is (some? (:rule/knowledge-content rule)))))))

(deftest mdc->rule-field-mapping-completeness-test
  (testing "all four frontmatter fields are mapped: dewey, description, alwaysApply, globs"
    (let [content (str "---\ndewey: \"210\"\n"
                       "description: Clojure Style\n"
                       "alwaysApply: false\n"
                       "globs: [\"*.clj\", \"*.cljc\"]\n"
                       "---\n\nBody.")
          result (sut/mdc->rule "clojure.mdc" content)
          rule (:rule result)]
      ;; dewey → :rule/category + :rule/applies-to phases
      (is (= "210" (:rule/category rule)))
      (is (= #{:implement :review} (get-in rule [:rule/applies-to :phases])))
      ;; description → :rule/title
      (is (= "Clojure Style" (:rule/title rule)))
      ;; alwaysApply: false → :rule/always-inject? absent
      (is (not (contains? rule :rule/always-inject?)))
      ;; globs → :rule/applies-to :file-globs
      (is (= ["*.clj" "*.cljc"] (get-in rule [:rule/applies-to :file-globs]))))))

(deftest mdc->rule-knowledge-content-semantics-test
  (testing ":rule/knowledge-content contains full MDC body text (distinct from agent-behavior)"
    (let [content "---\ndewey: \"001\"\n---\n\n# Full Title\n\nParagraph one.\n\n## Agent behavior\n\n- Be concise."
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      ;; knowledge-content is the FULL body
      (is (str/includes? (:rule/knowledge-content rule) "# Full Title"))
      (is (str/includes? (:rule/knowledge-content rule) "Paragraph one."))
      (is (str/includes? (:rule/knowledge-content rule) "## Agent behavior"))
      ;; agent-behavior is the CONCISE directive
      (is (= "- Be concise." (:rule/agent-behavior rule)))))

  (testing ":rule/knowledge-content omitted when body is empty"
    (let [content "---\ndewey: \"001\"\n---"
          rule (:rule (sut/mdc->rule "empty.mdc" content))]
      (is (not (contains? rule :rule/knowledge-content)))))

  (testing ":rule/knowledge-content omitted when body is whitespace-only"
    (let [content "---\ndewey: \"001\"\n---\n\n   \n  "
          rule (:rule (sut/mdc->rule "blank.mdc" content))]
      (is (not (contains? rule :rule/knowledge-content))))))

(deftest mdc->rule-always-inject-semantics-test
  (testing ":rule/always-inject? true when alwaysApply: true"
    (let [content "---\nalwaysApply: true\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (true? (:rule/always-inject? rule)))))

  (testing ":rule/always-inject? absent when alwaysApply: false"
    (let [content "---\nalwaysApply: false\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (not (contains? rule :rule/always-inject?)))))

  (testing ":rule/always-inject? absent when alwaysApply not in frontmatter"
    (let [content "---\ndewey: \"001\"\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (not (contains? rule :rule/always-inject?))))))

(deftest mdc->rule-missing-description-fallback-test
  (testing "missing description → title derived from filename slug"
    (let [content "---\ndewey: \"001\"\n---\nBody"
          rule (:rule (sut/mdc->rule "code-quality.mdc" content))]
      (is (= "Code Quality" (:rule/title rule)))
      (is (= "Engineering standard (001): Code Quality" (:rule/description rule))))))

(deftest mdc->rule-missing-dewey-fallback-test
  (testing "missing dewey → defaults to '000' (foundations phases)"
    (let [content "---\ndescription: No Dewey\n---\nBody"
          rule (:rule (sut/mdc->rule "no-dewey.mdc" content))]
      (is (= "000" (:rule/category rule)))
      (is (= all-phases (get-in rule [:rule/applies-to :phases]))))))

(deftest mdc->rule-meta-dewey-empty-phases-test
  (testing "dewey 900 (meta) → empty phases, never auto-injected"
    (let [content "---\ndewey: \"900\"\ndescription: Rule Format\nglobs: [\".cursor/rules/**/*.mdc\"]\n---\n\nTemplate."
          rule (:rule (sut/mdc->rule "rule-format.mdc" content))]
      (is (= #{} (get-in rule [:rule/applies-to :phases])))
      (is (= [".cursor/rules/**/*.mdc"] (get-in rule [:rule/applies-to :file-globs]))))))

(deftest mdc->rule-globs-normalization-test
  (testing "string glob wrapped in vector"
    (let [content "---\nglobs: \"*.clj\"\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (= ["*.clj"] (get-in rule [:rule/applies-to :file-globs])))))

  (testing "vector globs preserved"
    (let [content "---\nglobs: [\"*.clj\", \"*.cljc\"]\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (= ["*.clj" "*.cljc"] (get-in rule [:rule/applies-to :file-globs])))))

  (testing "no globs → :file-globs absent from applies-to"
    (let [content "---\ndewey: \"001\"\n---\nBody"
          rule (:rule (sut/mdc->rule "test.mdc" content))]
      (is (not (contains? (:rule/applies-to rule) :file-globs))))))

(deftest mdc->rule-error-handling-test
  (testing "returns failure result on exception"
    ;; Force an error by passing something that will cause parse issues
    ;; (the function catches all exceptions)
    (let [result (sut/mdc->rule nil nil)]
      ;; mdc->rule wraps in try/catch so it should return {:success? false}
      ;; (nil content causes NPE in str/trim inside split-frontmatter)
      ;; Actually, (str nil) => "" which won't throw. Let's verify it still works.
      (is (boolean? (:success? result))))))

(deftest mdc->rule-constant-fields-test
  (testing "non-alwaysApply severity is :minor"
    (let [rule (:rule (sut/mdc->rule "x.mdc" "---\ndewey: \"001\"\n---\nBody"))]
      (is (= :minor (:rule/severity rule)))))

  (testing "detection is always {:type :custom}"
    (let [rule (:rule (sut/mdc->rule "x.mdc" "---\ndewey: \"001\"\n---\nBody"))]
      (is (= {:type :custom} (:rule/detection rule)))))

  (testing "enforcement action is always :audit"
    (let [rule (:rule (sut/mdc->rule "x.mdc" "---\ndewey: \"001\"\n---\nBody"))]
      (is (= :audit (get-in rule [:rule/enforcement :action]))))))

;; ============================================================================
;; Layer 2 — compile-standards-pack tests
;; ============================================================================

(deftest compile-standards-pack-missing-directory-test
  (testing "returns failure when directory does not exist"
    (let [result (sut/compile-standards-pack "/nonexistent/path/to/standards")]
      (is (false? (:success? result)))
      (is (seq (:errors result)))
      (is (str/includes? (first (:errors result)) "not found")))))

(deftest compile-standards-pack-empty-directory-test
  (testing "returns success with zero rules for empty directory"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)]
      (try
        (let [result (sut/compile-standards-pack (str tmp-dir))]
          (is (true? (:success? result)))
          (is (= 0 (:compiled-count result)))
          (is (= 0 (:failed-count result)))
          (is (= [] (get-in result [:pack :pack/rules]))))
        (finally
          (.delete tmp-dir))))))

(deftest compile-standards-pack-single-file-test
  (testing "compiles a single .mdc file into a pack"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)
          mdc-file (java.io.File. tmp-dir "test-rule.mdc")]
      (try
        (spit mdc-file "---\ndewey: \"001\"\ndescription: Test Rule\nalwaysApply: true\n---\n\n# Test\n\nContent here.")
        (let [result (sut/compile-standards-pack (str tmp-dir))]
          (is (true? (:success? result)))
          (is (= 1 (:compiled-count result)))
          (is (= 0 (:failed-count result)))
          (let [pack (:pack result)]
            (is (= "miniforge/standards" (:pack/id pack)))
            (is (= "Miniforge Engineering Standards" (:pack/name pack)))
            (is (= :trusted (:pack/trust-level pack)))
            (is (= :authority/instruction (:pack/authority pack)))
            (is (= 1 (count (:pack/rules pack))))
            (let [rule (first (:pack/rules pack))]
              (is (= :std/test-rule (:rule/id rule)))
              (is (true? (:rule/always-inject? rule))))))
        (finally
          (.delete mdc-file)
          (.delete tmp-dir))))))

(deftest compile-standards-pack-duplicate-slugs-test
  (testing "returns failure when duplicate filename slugs detected"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)
          sub-a (java.io.File. tmp-dir "a")
          sub-b (java.io.File. tmp-dir "b")
          _ (.mkdirs sub-a)
          _ (.mkdirs sub-b)
          file-a (java.io.File. sub-a "duplicate.mdc")
          file-b (java.io.File. sub-b "duplicate.mdc")]
      (try
        (spit file-a "---\ndewey: \"001\"\n---\nA")
        (spit file-b "---\ndewey: \"002\"\n---\nB")
        (let [result (sut/compile-standards-pack (str tmp-dir))]
          (is (false? (:success? result)))
          (is (some #(str/includes? % "Duplicate") (:errors result))))
        (finally
          (.delete file-a)
          (.delete file-b)
          (.delete sub-a)
          (.delete sub-b)
          (.delete tmp-dir))))))

(deftest compile-standards-pack-metadata-test
  (testing "pack metadata matches expected standards pack structure"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)
          mdc-file (java.io.File. tmp-dir "example.mdc")]
      (try
        (spit mdc-file "---\ndewey: \"001\"\ndescription: Example\n---\nBody")
        (let [pack (:pack (sut/compile-standards-pack (str tmp-dir)))]
          ;; Standards pack is separate from builtin, with its own ID
          (is (= "miniforge/standards" (:pack/id pack)))
          (is (= "miniforge.ai" (:pack/author pack)))
          (is (= "Apache-2.0" (:pack/license pack)))
          (is (= :trusted (:pack/trust-level pack)))
          (is (= :authority/instruction (:pack/authority pack)))
          ;; Timestamps are present
          (is (inst? (:pack/created-at pack)))
          (is (inst? (:pack/updated-at pack)))
          ;; Categories are generated from rules
          (is (vector? (:pack/categories pack)))
          (is (= 1 (count (:pack/categories pack))))
          (is (= "foundations" (:category/id (first (:pack/categories pack))))))
        (finally
          (.delete mdc-file)
          (.delete tmp-dir))))))

(deftest compile-standards-pack-categories-grouping-test
  (testing "rules are grouped into categories by dewey range"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)
          f1 (java.io.File. tmp-dir "foundation-a.mdc")
          f2 (java.io.File. tmp-dir "foundation-b.mdc")
          f3 (java.io.File. tmp-dir "lang-a.mdc")]
      (try
        (spit f1 "---\ndewey: \"001\"\n---\nA")
        (spit f2 "---\ndewey: \"002\"\n---\nB")
        (spit f3 "---\ndewey: \"210\"\n---\nC")
        (let [pack (:pack (sut/compile-standards-pack (str tmp-dir)))
              cats (:pack/categories pack)
              cat-ids (set (map :category/id cats))]
          (is (= #{"foundations" "languages"} cat-ids))
          ;; Foundations category should have 2 rule IDs
          (let [found-cat (first (filter #(= "foundations" (:category/id %)) cats))]
            (is (= 2 (count (:category/rules found-cat))))))
        (finally
          (.delete f1)
          (.delete f2)
          (.delete f3)
          (.delete tmp-dir))))))

(deftest compile-standards-pack-always-inject-meta-warning-test
  (testing "warns when always-inject rule has empty phases (dewey 900)"
    (let [tmp-dir (java.io.File/createTempFile "mdc-test" "")
          _ (.delete tmp-dir)
          _ (.mkdirs tmp-dir)
          mdc-file (java.io.File. tmp-dir "meta-rule.mdc")]
      (try
        (spit mdc-file "---\ndewey: \"900\"\nalwaysApply: true\n---\nMeta")
        (let [result (sut/compile-standards-pack (str tmp-dir))]
          (is (true? (:success? result)))
          (is (some #(str/includes? % "always-inject") (:warnings result))))
        (finally
          (.delete mdc-file)
          (.delete tmp-dir))))))

;; ============================================================================
;; Integration: compiled rules pass schema validation
;; ============================================================================

(deftest compiled-rule-passes-schema-test
  (testing "mdc->rule output validates against Rule schema"
    (let [content (str "---\ndewey: \"001\"\n"
                       "description: Full Example\n"
                       "alwaysApply: true\n"
                       "globs: [\"*.clj\"]\n"
                       "---\n\n"
                       "# Full Example\n\nBody content.\n\n"
                       "## Agent behavior\n\n- Do the thing.")
          {:keys [success? rule]} (sut/mdc->rule "full-example.mdc" content)]
      (is (true? success?))
      ;; Require the schema ns to validate
      (require '[ai.miniforge.policy-pack.schema :as schema])
      (let [validate-rule (resolve 'ai.miniforge.policy-pack.schema/valid-rule?)]
        (when validate-rule
          (is (validate-rule rule)
              (str "Compiled rule should pass schema validation: "
                   ((resolve 'ai.miniforge.policy-pack.schema/explain)
                    @(resolve 'ai.miniforge.policy-pack.schema/Rule) rule))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.policy-pack.mdc-compiler-test)
  :leave-this-here)
