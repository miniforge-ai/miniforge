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

(ns ai.miniforge.compliance-scanner.execute-test
  "Unit tests for execute/patch-file-content — the pure file-patching logic."
  (:require [clojure.test   :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.miniforge.compliance-scanner.execute :as execute]))

;------------------------------------------------------------------------------ Fixtures

(def clojure-violation
  {:rule/id       :std/clojure
   :rule/category "210"
   :line          3
   :current       "(or (:timeout m) 5000)"
   :suggested     "(get m :timeout 5000)"
   :auto-fixable? true})

(def datever-violation
  {:rule/id       :std/datever
   :rule/category "730"
   :line          2
   :current       "1.2.3"
   :suggested     "1.2.3.0"
   :auto-fixable? true})

(def copyright-violation
  {:rule/id              :std/header-copyright
   :rule/category        "810"
   :line                 1
   :current              "(missing copyright header)"
   :suggested            nil
   :auto-fixable?        true
   :remediation-type     :prepend
   :remediation-template "<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->"})

;------------------------------------------------------------------------------ patch-file-content tests

(deftest line-replacement-applies-on-correct-line-test
  (testing "patch-file-content replaces :current with :suggested on the correct line"
    (let [content  "line1\nline2\n(or (:timeout m) 5000)\nline4\n"
          patched  (execute/patch-file-content content [clojure-violation])]
      (is (str/includes? patched "(get m :timeout 5000)")
          "Should contain the suggested replacement")
      (is (not (str/includes? patched "(or (:timeout m) 5000)"))
          "Should not contain the original text"))))

(deftest line-replacement-only-modifies-target-line-test
  (testing "patch-file-content leaves other lines untouched"
    (let [content  "line1\nline2\n(or (:timeout m) 5000)\nline4\n"
          patched  (execute/patch-file-content content [clojure-violation])
          lines    (str/split-lines patched)]
      (is (= "line1" (get lines 0)) "Line 1 unchanged")
      (is (= "line2" (get lines 1)) "Line 2 unchanged")
      (is (= "line4" (get lines 3)) "Line 4 unchanged"))))

(deftest trailing-newline-preserved-test
  (testing "patch-file-content preserves trailing newline"
    (let [content "(or (:timeout m) 5000)\n"
          v       (assoc clojure-violation :line 1)
          patched (execute/patch-file-content content [v])]
      (is (str/ends-with? patched "\n")
          "Trailing newline should be preserved"))))

(deftest no-trailing-newline-preserved-test
  (testing "patch-file-content preserves absence of trailing newline"
    (let [content "(or (:timeout m) 5000)"
          v       (assoc clojure-violation :line 1)
          patched (execute/patch-file-content content [v])]
      (is (not (str/ends-with? patched "\n"))
          "No trailing newline should not be added"))))

(deftest multiple-violations-same-file-applied-test
  (testing "patch-file-content applies multiple violations to the same file"
    (let [content  "line1\n1.2.3\n(or (:timeout m) 5000)\nline4\n"
          patched  (execute/patch-file-content content [clojure-violation datever-violation])]
      (is (str/includes? patched "1.2.3.0")    "DateVer fix applied")
      (is (str/includes? patched "(get m :timeout 5000)") "Clojure map access fix applied"))))

(deftest copyright-header-prepended-test
  (testing "patch-file-content prepends copyright header for missing-header violations"
    (let [content "# My Doc\n\nSome content.\n"
          patched (execute/patch-file-content content [copyright-violation])]
      (is (str/starts-with? patched "<!--")
          "Patched content should start with HTML comment")
      (is (str/includes? patched "Christopher Lester")
          "Copyright header should contain author name")
      (is (str/includes? patched "# My Doc")
          "Original content should be preserved after the header"))))

(deftest copyright-header-before-line-edits-test
  (testing "copyright prepend does not shift line indices for line-replacement violations"
    (let [content  "(or (:timeout m) 5000)\nline2\n"
          v-clj    (assoc clojure-violation :line 1)
          patched  (execute/patch-file-content content [v-clj copyright-violation])]
      (is (str/includes? patched "(get m :timeout 5000)")
          "Clojure fix should still be applied")
      (is (str/starts-with? patched "<!--")
          "Copyright header should be at the top"))))

(deftest no-change-when-suggested-nil-and-not-copyright-test
  (testing "patch-file-content skips violations with nil suggested that aren't copyright"
    (let [content "original content\n"
          v       {:rule/id :std/clojure :line 1 :current "original content"
                   :suggested nil :auto-fixable? false}
          patched (execute/patch-file-content content [v])]
      (is (= content patched)
          "Content should be unchanged when suggested is nil and not copyright"))))

(deftest embedded-pattern-replaces-only-match-test
  (testing "patch-file-content replaces only the matched substring, not the whole line"
    (let [content  "  (let [state (keyword (str/lower-case (or (:state check) \"\")))]\n"
          v        {:rule/id       :std/clojure
                    :rule/category "210"
                    :line          1
                    :current       "(or (:state check) \"\")"
                    :suggested     "(get check :state \"\")"
                    :auto-fixable? true}
          patched  (execute/patch-file-content content [v])]
      (is (= "  (let [state (keyword (str/lower-case (get check :state \"\")))]\n" patched)
          "Only the matched (or ...) form should be replaced, preserving surrounding code"))))

(deftest empty-violations-returns-content-unchanged-test
  (testing "patch-file-content with empty violations returns content unchanged"
    (let [content "some content\n"]
      (is (= content (execute/patch-file-content content []))))))

;------------------------------------------------------------------------------ PR doc generation

(deftest build-pr-doc-contains-attribution-yaml
  (testing "PR doc includes YAML attribution block with rule metadata"
    (let [build-pr-doc #'execute/build-pr-doc
          doc (build-pr-doc "fix/compliance-210-clojure-map-access" "210"
                            "Clojure Map Access"
                            ["components/foo/src/core.clj"]
                            2
                            [{:file "components/foo/src/core.clj" :line 10
                              :current "(or (:k m) nil)" :suggested "(get m :k nil)"}
                             {:file "components/foo/src/core.clj" :line 20
                              :current "(or (:v m) 0)" :suggested "(get m :v 0)"}])]
      (is (str/includes? doc "generated-by: miniforge-compliance-scanner"))
      (is (str/includes? doc "rule-id: 210"))
      (is (str/includes? doc "rule-title: \"Clojure Map Access\""))
      (is (str/includes? doc "fix-type: mechanical"))
      (is (str/includes? doc "violations-fixed: 2"))
      (is (str/includes? doc "files-changed: 1"))
      (is (str/includes? doc "**Branch:** `fix/compliance-210-clojure-map-access`"))
      (is (str/includes? doc "| `components/foo/src/core.clj` | 10")))))

(deftest build-pr-body-contains-attribution-yaml
  (testing "PR body includes YAML attribution block"
    (let [build-pr-body #'execute/build-pr-body
          body (build-pr-body "210" "Clojure Map Access" 5 3
                              "docs/pull-requests/2026-04-07-fix-210-clojure-map-access.md")]
      (is (str/includes? body "generated-by: miniforge-compliance-scanner"))
      (is (str/includes? body "rule-id: 210"))
      (is (str/includes? body "violations-fixed: 5"))
      (is (str/includes? body "files-changed: 3"))
      (is (str/includes? body "docs/pull-requests/2026-04-07-fix-210-clojure-map-access.md")))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.compliance-scanner.execute-test)
  :leave-this-here)
