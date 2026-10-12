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

   Layer 0: `unescape-literal-text` in isolation. The field scoping that
            decides which frontmatter keys reach it, and the round trip through
            `mdc->rule`/`compile-standards-pack`, are covered alongside the
            `rule-config` call site."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing are]]
   [ai.miniforge.policy-pack.mdc-compiler.frontmatter-values :as sut]))

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
