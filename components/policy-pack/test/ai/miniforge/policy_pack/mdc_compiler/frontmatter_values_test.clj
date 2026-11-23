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
(ns ai.miniforge.policy-pack.mdc-compiler.frontmatter-values-test
  "Tests for backslash-escape handling in MDC frontmatter values.

   MDC frontmatter has no block-scalar (`|`) syntax, so a multi-line literal
   can only be declared as a single-line quoted scalar carrying `\\n`. Escape
   expansion is therefore necessary — but it is scoped to literal-text fields,
   because `detection.pattern` is regex source and expanding `\\s`/`\\d`/`\\b`
   there would silently change what a rule matches on (miniforge-standards#129).

   Every regex string asserted unchanged below is a real value from a .mdc file
   on miniforge-standards main, not an invention: a pattern that regressed here
   is a rule that stops firing in production.

   Layer 0: `unescape-literal-text` in isolation, the field scoping in
            `build-remediation-config`, and escape-the-escape through `mdc->rule`
   Layer 1: Round trip of the shared multi-line fixture through `mdc->rule`
            and `compile-standards-pack`"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing are]]
   [ai.miniforge.policy-pack.mdc-compiler :as compiler]
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter-values :as sut]
   [ai.miniforge.policy-pack.mdc-compiler.rule-config :as rule-config]))

;------------------------------------------------------------------------------ Layer 0

;; In every string below, a single backslash in Clojure source ("\\") is a
;; single backslash in the .mdc file on disk. "\\\\" is the two-character
;; sequence an author writes to mean "a literal backslash".
(deftest ^{:stratum 0} unescape-literal-text-expands-recognized-escapes-test
  (testing "\\n becomes a real newline"
    (is (= "<!--\n  Title: Miniforge.ai\n-->"
           (sut/unescape-literal-text "<!--\\n  Title: Miniforge.ai\\n-->")))
    (is (= 3 (count (str/split-lines (sut/unescape-literal-text "a\\nb\\nc"))))))

  (testing "\\t becomes a real tab"
    (is (= "a\tb" (sut/unescape-literal-text "a\\tb"))))

  (testing "a template with no escapes is returned unchanged"
    (is (= "plain text" (sut/unescape-literal-text "plain text")))))

(deftest ^{:stratum 0} unescape-literal-text-escape-the-escape-test
  (testing "\\\\n yields a literal backslash followed by n, not a newline"
    (let [out (sut/unescape-literal-text "a\\\\nb")]
      (is (= "a\\nb" out))
      (is (= 1 (count (str/split-lines out)))
          "escaping the escape must not produce a line break")
      (is (not (str/includes? out "\n")))))

  (testing "\\\\t yields a literal backslash followed by t"
    (is (= "a\\tb" (sut/unescape-literal-text "a\\\\tb"))))

  (testing "\\\\ on its own collapses to one backslash"
    (is (= "\\" (sut/unescape-literal-text "\\\\")))))

(deftest ^{:stratum 0} unescape-literal-text-leaves-regex-source-untouched-test
  (testing "real detection patterns from miniforge-standards survive byte-for-byte"
    (are [pattern] (= pattern (sut/unescape-literal-text pattern))
      ;; languages/clojure-no-requiring-resolve.mdc
      "[(/]requiring-resolve\\b"
      ;; languages/clojure.mdc
      "\\(get\\s+\\[\\{\\}\\]\\d"
      ;; project/header-copyright.mdc (detection.emailPattern)
      "christopher@miniforge\\.ai"
      ;; workflows/bb-over-shell.mdc
      "^#!.*\\b(bash|sh|zsh|python[0-9.]*)\\b"
      ;; workflows/datever.mdc
      "\\b(\\d+)\\.(\\d+)\\.(\\d+)\\b(?!\\.\\d)"))

  (testing "an unrecognized escape keeps its backslash"
    (are [s] (= s (sut/unescape-literal-text s))
      "\\s" "\\d" "\\b" "\\w" "\\(" "\\)" "\\[" "\\]" "\\{" "\\}" "\\." "\\$"))

  (testing "a trailing lone backslash has nothing to escape and survives"
    (is (= "ends with\\" (sut/unescape-literal-text "ends with\\"))))

  (testing "$ is not interpreted as a regex group reference"
    ;; Expansion runs through `str/replace`, whose string-replacement arm treats
    ;; `$1` as a group reference. A `$` in a template — a shell snippet in a
    ;; header comment, say — must reach the file as a `$`.
    (are [s] (= s (sut/unescape-literal-text s))
      "cost is $1 and $2"
      "${HOME}/bin"
      "\\$1")
    (is (= "a$1\nb$2" (sut/unescape-literal-text "a$1\\nb$2"))
        "an expanded escape beside a $ must not disturb the $"))

  (testing "an odd run of backslashes leaves one escaped backslash and expands the rest"
    (is (= "a\\\nb" (sut/unescape-literal-text "a\\\\\\nb"))
        "three backslashes then n = escaped backslash, then a real newline")))

(deftest ^{:stratum 0} unescape-literal-text-passes-non-strings-through-test
  (testing "parsed booleans and inline arrays are returned as-is"
    (are [v] (= v (sut/unescape-literal-text v))
      true
      false
      nil
      ["a" "b"]
      [])))

(deftest ^{:stratum 0} build-remediation-config-unescapes-only-template-test
  (testing "template escapes are expanded"
    (let [cfg (rule-config/build-remediation-config
               {"strategy" "mechanical"
                "type"     "prepend"
                "template" "<!--\\n  Author: Christopher Lester\\n-->"})]
      (is (= "<!--\n  Author: Christopher Lester\n-->" (:template cfg)))
      (is (= 3 (count (str/split-lines (:template cfg)))))))

  (testing "replacement is a regex replacement string and is NOT unescaped"
    ;; Real values: languages/clojure.mdc and workflows/datever.mdc.
    (are [replacement] (= replacement
                          (:replacement (rule-config/build-remediation-config
                                         {"strategy"    "mechanical"
                                          "replacement" replacement})))
      "(get $2 $1 $3)"
      "$1.$2.$3.0"
      "$1\\n$2"))

  (testing "exclude-context path lists are NOT unescaped"
    (let [cfg (rule-config/build-remediation-config
               {"strategy"            "mechanical"
                "excludePathContains" ["a\\nb" "resources\\\\config"]})]
      (is (= [{:path-contains "a\\nb"} {:path-contains "resources\\\\config"}]
             (:exclude-contexts cfg))))))

(def ^{:stratum 0} ^:private multiline-template-mdc
  "An .mdc declaring a multi-line prepend template alongside regex detection —
   the exact shape of 810 `project/header-copyright`."
  (str "---\n"
       "dewey: \"810\"\n"
       "description: Escape Round Trip\n"
       "globs: [\"*.md\"]\n"
       "detection.mode: negative\n"
       "detection.pattern: 'Christopher Lester'\n"
       "detection.emailPattern: 'christopher@miniforge\\.ai'\n"
       "remediation.strategy: mechanical\n"
       "remediation.type: prepend\n"
       "remediation.template: '<!--\\n  Title: Miniforge.ai\\n"
       "  Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)\\n-->'\n"
       "remediation.autoFixable: true\n"
       "---\n\n# Escape Round Trip\n\nBody.\n"))

(deftest ^{:stratum 0} mdc->rule-escape-the-escape-test
  (testing "a template declaring \\\\n compiles to a literal backslash-n"
    (let [content (str "---\ndewey: \"810\"\ndescription: Escaped\n"
                       "remediation.strategy: mechanical\n"
                       "remediation.type: prepend\n"
                       "remediation.template: 'a\\\\nb'\n"
                       "---\n\nBody.\n")
          rule    (:rule (compiler/mdc->rule "escaped.mdc" content))
          template (get-in rule [:rule/remediation :template])]
      (is (= "a\\nb" template))
      (is (= 1 (count (str/split-lines template)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} mdc->rule-compiles-template-escapes-test
  ;; NOTE: mdc->rule takes (filename content) in that order.
  (let [{:keys [success? rule]} (compiler/mdc->rule "escape-round-trip.mdc"
                                                    multiline-template-mdc)
        template (get-in rule [:rule/remediation :template])]
    (testing "the rule compiles"
      (is (true? success?)))

    (testing "the template carries real newlines, not literal backslash-n"
      (is (= (str "<!--\n"
                  "  Title: Miniforge.ai\n"
                  "  Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)\n"
                  "-->")
             template))
      (is (= 4 (count (str/split-lines template))))
      (is (not (str/includes? template "\\n"))
          "a literal backslash-n in the template is the defect this guards"))

    (testing "detection patterns are untouched by the template expansion"
      (is (= "Christopher Lester" (get-in rule [:rule/detection :pattern])))
      (is (= "christopher@miniforge\\.ai"
             (get-in rule [:rule/detection :email-pattern]))))))

(deftest ^{:stratum 1} compile-standards-pack-round-trips-template-escapes-test
  (testing "a pack compiled from disk carries real newlines in its template"
    ;; compile-standards-pack walks with file-seq, so every .mdc goes directly
    ;; in tmp-dir — a nested directory would duplicate filename slugs and fail
    ;; the whole compilation.
    (let [tmp-dir (java.io.File/createTempFile "mdc-escape-test" "")
          _        (.delete tmp-dir)
          _        (.mkdirs tmp-dir)
          mdc-file (java.io.File. tmp-dir "escape-round-trip.mdc")]
      (try
        (spit mdc-file multiline-template-mdc)
        (let [result (compiler/compile-standards-pack (str tmp-dir))
              rule   (first (get-in result [:pack :pack/rules]))]
          (is (true? (:success? result)) (str "errors: " (:errors result)))
          (is (= 1 (:compiled-count result)))
          (is (= :std/escape-round-trip (:rule/id rule)))
          (is (= 4 (count (str/split-lines (get-in rule [:rule/remediation :template])))))
          (is (not (str/includes? (get-in rule [:rule/remediation :template]) "\\n")))
          (is (= "christopher@miniforge\\.ai"
                 (get-in rule [:rule/detection :email-pattern]))))
        (finally
          (.delete mdc-file)
          (.delete tmp-dir))))))
